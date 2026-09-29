package dev.magyul.instantp2p.fabric.impl;

import dev.magyul.instantp2p.common.core.P2PCore;
import dev.magyul.instantp2p.common.core.JsonSettings;
import dev.magyul.instantp2p.common.core.P2PSettings;
import dev.magyul.instantp2p.common.tunnel.TunnelInjector;
import dev.magyul.instantp2p.fabric.FabricEntry;
import dev.magyul.instantp2p.fabric.PlayerLimitBypass;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fabric 구현. {@link FabricEntry}가 버전을 확인한 뒤 리플렉션으로 로드한다.
 * <p>
 * <b>이 소스는 두 번 컴파일된다</b> — {@code fabric-1_21} 모듈(1.21.11, remap해 intermediary로 → 패키지 {@code v1_21})과
 * {@code fabric-26} 모듈(26.1, remap 없음 → 패키지 {@code v26}). 런타임 이름이 버전마다 달라 결과물은 둘이어야 한다.
 * 그래서 여기서는 1.21.0~26.x에 모두 같은 이름으로 있는 MC/Fabric API만 쓴다. 이름이 다른 곳은 모듈별 {@link Compat}에 둔다.
 */
public final class FabricImpl implements FabricEntry.Impl {

    private static final Logger LOGGER = LoggerFactory.getLogger("Instant-P2P");
    private static final String MOD_DIR = "instant-p2p-server";

    private P2PCore core;
    private FabricPlatform platform;
    /** 입장 메시지에 붙일 suffix (INIT에서 기록, 입장 메시지가 나갈 때 소비) */
    private final Map<ServerPlayer, PendingSuffix> pendingJoinSuffix = new ConcurrentHashMap<>();

    /** 입장 메시지를 끈 서버 등에서 소비되지 않은 항목은 이 시간이 지나면 버린다 */
    private static final long PENDING_SUFFIX_TTL_MS = 30_000;

    private record PendingSuffix(String key, long createdAt) {}

    @Override
    public void init() {
        FabricLoader loader = FabricLoader.getInstance();
        Path dataFolder = loader.getConfigDir().resolve(MOD_DIR);
        P2PSettings settings;
        try {
            LOGGER.info("컨피그를 불러오는 중...");
            settings = JsonSettings.load(dataFolder.resolve("config.json"));
        } catch (Exception e) {
            LOGGER.error("컨피그를 불러오는데 실패 했습니다! config/{}/config.json 파일에 문제가 없나요?", MOD_DIR, e);
            return;
        }
        String version = loader.getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        platform = new FabricPlatform(settings, dataFolder, loader.getGameDir(), version);
        core = new P2PCore(platform);

        FabricCommand.register(core, platform);
        Compat.registerPayloads();
        // 1.20.5+ 수신 핸들러는 서버 스레드에서 돈다
        ServerPlayNetworking.registerGlobalReceiver(Payloads.ModerationPayload.TYPE,
                (payload, context) -> core.onModeration(context.player().getUUID(), payload.data()));

        ServerLifecycleEvents.SERVER_STARTED.register(this::onStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> onStopping());

        // 정원 초과 입장: 터널로 들어온 개발자·서포터 (Mixin이 바닐라 정원 검사 직전에 부른다)
        // 정원 검사는 로그인·설정 단계에서 두 번 불린다 — 같은 입장에 로그를 두 번 남기지 않는다
        Map<UUID, Long> bypassLogged = new ConcurrentHashMap<>();
        PlayerLimitBypass.set((address, profile) -> {
            UUID id = Profiles.id(profile);
            boolean ok = id != null && address instanceof InetSocketAddress isa
                    && core.canBypassPlayerLimit(id, isa.getAddress());
            if (ok) {
                long now = System.currentTimeMillis();
                bypassLogged.values().removeIf(t -> now - t > 30_000);
                if (bypassLogged.putIfAbsent(id, now) == null) {
                    String name = Profiles.name(profile);
                    LOGGER.info("[host] 정원 초과 입장 허용: {} (개발자·서포터)", name != null ? name : id);
                }
            }
            return ok;
        });
        // P2P 최대 인원: 바닐라가 받아 준 터널 접속을 P2P 정원 기준으로 막는다 (로그 중복은 P2PCore가 거른다)
        PlayerLimitBypass.setDenial((address, profile) -> {
            UUID id = Profiles.id(profile);
            if (id == null || !(address instanceof InetSocketAddress isa)) return null;
            return core.isP2PFull(id, Profiles.name(profile), isa.getAddress())
                    ? FabricText.translatable(P2PCore.SERVER_FULL) : null;
        });

        // 로그인 전 검사: 추방 상태면 설정 단계에서 끊는다 (월드에 들어오기 전)
        ServerConfigurationConnectionEvents.CONFIGURE.register((handler, server) -> {
            UUID id = Profiles.id(handler.getOwner());
            if (id != null && !core.onPreLogin(id)) {
                handler.disconnect(FabricText.translatable("instant-p2p.msg.still_expelled"));
            }
        });

        // 게임 핸들러가 생길 때(입장 메시지보다 먼저) 터널을 묶고 suffix를 준비한다
        ServerPlayConnectionEvents.INIT.register((handler, server) -> {
            ServerPlayer player = handler.player;
            core.tunnels().bySpoofed(asInet(handler.getRemoteAddress())).ifPresent(t -> {
                core.tunnels().bindPlayer(t, player.getUUID());
                long now = System.currentTimeMillis();
                pendingJoinSuffix.values().removeIf(p -> now - p.createdAt() > PENDING_SUFFIX_TTL_MS);
                pendingJoinSuffix.put(player, new PendingSuffix(Boolean.TRUE.equals(t.usesRelay())
                        ? "instant-p2p.msg.join_suffix_relay"
                        : "instant-p2p.msg.join_suffix_direct", now));
            });
        });

        ServerMessageEvents.ALLOW_GAME_MESSAGE.register(this::appendJoinSuffix);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.player;
            // JOIN은 입장 메시지보다 먼저 온다 — 여기서 suffix를 지우면 안 된다.
            // (server.execute도 서버 스레드에서 부르면 미루지 않고 즉시 실행한다)
            core.onJoin(player.getUUID());
            if (core.ipRestoreUnavailable() && platform.isAdmin(player)) {
                player.sendSystemMessage(FabricText.translatable(P2PCore.IP_RESTORE_UNAVAILABLE), false); // (Component) 오버로드는 1.21.0에 없다
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            pendingJoinSuffix.remove(handler.player);
            // 접속이 끊기는 쪽에서 오면 Netty 스레드에서 불린다(1.21.11 실측) — 퇴장 처리는 추방 해제·room_state 전송까지
            // 플레이어 목록을 건드리므로 항상 서버 스레드로 넘긴다. 다음 틱이라 나가는 플레이어는 이미 목록에서 빠져 있다.
            UUID id = handler.player.getUUID();
            platform.runSync(() -> core.onQuit(id));
        });
    }

    private void onStarted(MinecraftServer server) {
        platform.attach(server);
        // IP 복원은 실패해도 접속은 되므로 끄지 않는다 (관리자에게만 알림)
        TunnelInjector.inject(server, core.tunnels(), core::markIpRestoreUnavailable);

        core.host().autoStart();
    }

    private void onStopping() {
        PlayerLimitBypass.set(null);
        PlayerLimitBypass.setDenial(null);
        TunnelInjector.uninject();
        core.tunnels().clear();
        core.host().shutdown();
    }

    /**
     * 바닐라는 입장 메시지를 플레이어를 목록에 넣기 전에 보내므로 입장한 본인은 원래 자기 입장 메시지를 못 본다
     * (Paper는 순서를 바꿔 본인에게도 보인다).
     * 바닐라 입장 메시지("multiplayer.player.joined")는 이벤트 없이 브로드캐스트되므로, 나가는 순간 가로채서
     * suffix를 붙인 메시지로 다시 보낸다. 인자(표시 이름)로 대상 플레이어를 찾는다.
     */
    private boolean appendJoinSuffix(MinecraftServer server, Component message, boolean overlay) {
        if (pendingJoinSuffix.isEmpty() || overlay) return true;
        if (!(message.getContents() instanceof TranslatableContents tc)
                || !tc.getKey().startsWith("multiplayer.player.joined") || tc.getArgs().length == 0) {
            return true;
        }
        String shown = tc.getArgs()[0] instanceof Component c ? c.getString() : String.valueOf(tc.getArgs()[0]);
        for (Map.Entry<ServerPlayer, PendingSuffix> e : pendingJoinSuffix.entrySet()) {
            if (!e.getKey().getDisplayName().getString().equals(shown)) continue;
            pendingJoinSuffix.remove(e.getKey());
            Component withSuffix = message.copy().append(" ").append(FabricText.translatable(e.getValue().key()));
            server.getPlayerList().broadcastSystemMessage(withSuffix, false);
            return false;
        }
        return true;
    }

    private static java.net.InetSocketAddress asInet(java.net.SocketAddress address) {
        return address instanceof java.net.InetSocketAddress isa ? isa : null;
    }
}
