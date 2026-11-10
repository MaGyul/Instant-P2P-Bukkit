package dev.magyul.instantp2p.clienttest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 원본 모드 클라이언트로 서버판 방에 초대 코드({@code quic.<코드>})로 입장한다.
 * 코드는 {@code -Dp2p.code=}, 입장 뒤 머무는 시간은 {@code -Dp2p.holdSeconds=}(기본 15초).
 */
public class P2PJoinTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        String code = System.getProperty("p2p.code");
        if (code == null || code.isBlank()) throw new AssertionError("-Pp2p.code=<초대 코드> 가 필요합니다");
        int hold = Integer.parseInt(System.getProperty("p2p.holdSeconds", "15"));

        context.waitForScreen(TitleScreen.class);
        context.takeScreenshot("00-title");

        // 개발 환경 계정은 오프라인("FabricMC" 토큰)이라 시그널링 서버가 거부한다 — 사람이 AuthMe로 정품 로그인할 때까지 기다린다
        AtomicReference<Boolean> real = new AtomicReference<>(false);
        context.runOnClient(client -> real.set(!"FabricMC".equals(client.getUser().getAccessToken())));
        if (!real.get()) {
            // AuthMe 로그인 버튼은 멀티플레이 화면에 있다 — 거기까지 열어 둔다
            context.setScreen(() -> new JoinMultiplayerScreen(new TitleScreen()));
            System.out.println("[P2PTest] 정품 로그인이 필요합니다 — 클라이언트 창에서 AuthMe로 로그인하세요 (최대 10분 대기)");
            context.waitFor(client -> !"FabricMC".equals(client.getUser().getAccessToken()), 20 * 60 * 10);
            context.waitTicks(40);
        }
        context.runOnClient(client -> System.out.println("[P2PTest] 로그인 계정 확인됨, 입장 시도"));

        String address = "quic." + code.trim();
        context.runOnClient(client -> ConnectScreen.startConnecting(
                client.gui.screen(), client, ServerAddress.parseString(address),
                new ServerData("p2p-test", address, ServerData.Type.OTHER), false, null));

        // QUIC 펀칭은 최대 35초 + 인증/동의 확인 — 넉넉히 기다린다 (20틱 = 1초)
        context.waitFor(client -> client.level != null || client.gui.screen() instanceof DisconnectedScreen, 20 * 90);

        AtomicReference<String> failure = new AtomicReference<>();
        context.runOnClient(client -> {
            if (client.gui.screen() instanceof DisconnectedScreen) failure.set(client.gui.screen().getTitle().getString());
        });
        if (failure.get() != null) {
            context.takeScreenshot("01-disconnected");
            throw new AssertionError("입장 실패(연결 끊김 화면): " + failure.get());
        }

        context.takeScreenshot("01-joined");
        context.runOnClient(client -> client.player.connection.sendChat("gametest hello from p2p client"));
        context.waitTicks(20 * hold);
        context.takeScreenshot("02-after-hold");
        context.runOnClient(client -> {
            if (client.level == null) throw new AssertionError("머무는 동안 연결이 끊겼다");
        });
    }
}
