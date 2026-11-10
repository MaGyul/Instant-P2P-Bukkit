package dev.magyul.instantp2p.clienttest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * 일반 클라이언트(입력이 막히지 않음)에서 돌리는 자동 입장 시험 — Fabric 클라이언트 GameTest는 사람 입력을 막아서
 * AuthMe로 정품 로그인을 할 수 없다. 로그인은 사람이 하고, 그 뒤는 이 클래스가 자동으로 진행한다:
 * 멀티플레이 화면 열기 → (정품 로그인 대기) → {@code quic.<코드>} 입장 → 잠시 머묾 → 결과 출력 후 종료.
 * {@code -Dp2p.autojoin=true -Dp2p.code=<코드>}일 때만 동작한다.
 */
public class P2PAutoJoin implements ClientModInitializer {
    private enum State { WAIT_TITLE, WAIT_LOGIN, CONNECTING, WAIT_CONSENT, JOINED, DONE }

    private State state = State.WAIT_TITLE;
    private boolean askedConsent;
    private long tick;
    private long stateSince;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("p2p.autojoin")) return;
        String code = System.getProperty("p2p.code", "").trim();
        int holdTicks = 20 * Integer.parseInt(System.getProperty("p2p.holdSeconds", "20"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> onTick(client, code, holdTicks));
    }

    private void go(State next) {
        state = next;
        stateSince = tick;
    }

    private void onTick(Minecraft client, String code, int holdTicks) {
        tick++;
        long inState = tick - stateSince;
        switch (state) {
            case WAIT_TITLE -> {
                if (client.screen instanceof TitleScreen) {
                    if ("FabricMC".equals(client.getUser().getAccessToken())) {
                        client.setScreen(new JoinMultiplayerScreen(client.screen));
                        System.out.println("[P2PTest] 정품 로그인이 필요합니다 — 멀티플레이 화면의 AuthMe 버튼으로 로그인하세요 (최대 15분)");
                        go(State.WAIT_LOGIN);
                    } else {
                        go(State.WAIT_LOGIN);
                    }
                }
            }
            case WAIT_LOGIN -> {
                if (!"FabricMC".equals(client.getUser().getAccessToken())) {
                    if (inState > 40 && !(client.screen == null)) {
                        System.out.println("[P2PTest] 로그인 확인: " + client.getUser().getName() + " — 입장 시도");
                        String address = "quic." + code;
                        ConnectScreen.startConnecting(client.screen, client, ServerAddress.parseString(address),
                                new ServerData("p2p-test", address, ServerData.Type.OTHER), false, null);
                        go(State.CONNECTING);
                    }
                } else if (inState > 20 * 60 * 15) {
                    finish(client, "TIMEOUT 로그인 대기 시간 초과");
                }
            }
            case CONNECTING -> {
                if (client.level != null && client.player != null) {
                    System.out.println("[P2PTest] 입장 성공 (" + (inState / 20) + "초)");
                    client.player.connection.sendChat("gametest hello from p2p client");
                    go(State.JOINED);
                } else if (client.screen instanceof DisconnectedScreen) {
                    if (!consentRecorded() && !askedConsent) {
                        // 공식 서버는 입장자 동의(Join Room 화면의 동의창)가 있어야 받는다 — 동의는 사람이 직접 한다
                        askedConsent = true;
                        client.setScreen(new JoinMultiplayerScreen(new TitleScreen()));
                        System.out.println("[P2PTest] 입장 동의가 필요합니다 — 멀티플레이 화면에서 모드의 Join Room(방 참가) 화면에 들어가 동의창을 읽고 직접 선택하세요 (최대 15분). 동의가 기록되면 자동으로 다시 입장합니다");
                        go(State.WAIT_CONSENT);
                    } else {
                        finish(client, "FAIL 연결 끊김 화면: " + client.screen.getTitle().getString());
                    }
                } else if (inState > 20 * 120) {
                    finish(client, "FAIL 입장 시간 초과(120초)");
                }
            }
            case WAIT_CONSENT -> {
                if (consentRecorded()) {
                    System.out.println("[P2PTest] 동의 기록 확인 — 다시 입장 시도");
                    String address = "quic." + code;
                    ConnectScreen.startConnecting(client.screen, client, ServerAddress.parseString(address),
                            new ServerData("p2p-test", address, ServerData.Type.OTHER), false, null);
                    go(State.CONNECTING);
                } else if (inState > 20 * 60 * 15) {
                    finish(client, "TIMEOUT 동의 대기 시간 초과");
                }
            }
            case JOINED -> {
                if (client.level == null) {
                    finish(client, "FAIL 머무는 동안 연결이 끊겼다");
                } else if (inState > holdTicks) {
                    finish(client, "PASS 입장 후 " + (holdTicks / 20) + "초 유지");
                }
            }
            case DONE -> { }
        }
    }

    /** 모드가 남기는 동의 기록(config/instant-p2p/public_consent.json)에 입장 동의("join")가 있는지. */
    private static boolean consentRecorded() {
        try {
            java.nio.file.Path f = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                    .resolve("instant-p2p").resolve("public_consent.json");
            return java.nio.file.Files.isRegularFile(f) && java.nio.file.Files.readString(f).contains("\"join\"");
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private void finish(Minecraft client, String result) {
        state = State.DONE;
        System.out.println("[P2PTest] RESULT " + result);
        client.stop();
    }
}
