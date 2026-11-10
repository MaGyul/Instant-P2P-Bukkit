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
    private enum State { WAIT_TITLE, WAIT_LOGIN, WAIT_SETUP, CONNECTING, WAIT_CONSENT, JOINED, CONTROL, DONE }

    private State state = State.WAIT_TITLE;
    private boolean askedConsent;
    /** -Dp2p.control=true: 입장 뒤 끝내지 않고 run 폴더의 p2p-control.txt 명령(connect/disconnect/status/chat/quit)을 기다린다 — 한 번 로그인으로 여러 시나리오를 시험 */
    private final boolean control = Boolean.getBoolean("p2p.control");
    private boolean wasInWorld;
    private String savedCode = "";
    /** -Dp2p.profile=franchise: 가맹점 서버 시험 — 서버 선택·약관 동의(사람이 직접)가 settings.json에 기록될 때까지 기다린다 */
    private final boolean franchise = "franchise".equalsIgnoreCase(System.getProperty("p2p.profile"));
    private long tick;
    private long stateSince;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("p2p.autojoin")) return;
        String code = System.getProperty("p2p.code", "").trim();
        int holdTicks = 20 * Integer.parseInt(System.getProperty("p2p.holdSeconds", "20"));
        savedCode = code;
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
                        System.out.println("[P2PTest] 로그인 확인: " + client.getUser().getName());
                        if (franchise && !franchiseReady()) {
                            System.out.println("[P2PTest] 가맹점 설정이 필요합니다 — 모드의 서버 선택에서 가맹점을 고르고 약관을 읽은 뒤 직접 동의하세요 (최대 15분). 설정이 기록되면 자동으로 입장합니다");
                            go(State.WAIT_SETUP);
                        } else {
                            connect(client, code);
                        }
                    }
                } else if (inState > 20 * 60 * 15) {
                    finish(client, "TIMEOUT 로그인 대기 시간 초과");
                }
            }
            case CONNECTING -> {
                if (client.level != null && client.player != null) {
                    System.out.println("[P2PTest] 입장 성공 (" + (inState / 20) + "초)");
                    if (control) {
                        wasInWorld = true;
                        go(State.CONTROL);
                    } else {
                        client.player.connection.sendChat("gametest hello from p2p client");
                        go(State.JOINED);
                    }
                } else if (client.screen instanceof DisconnectedScreen) {
                    if (!control && !consentRecorded() && !askedConsent) {
                        // 공식 서버는 입장자 동의(Join Room 화면의 동의창)가 있어야 받는다 — 동의는 사람이 직접 한다
                        askedConsent = true;
                        client.setScreen(new JoinMultiplayerScreen(new TitleScreen()));
                        System.out.println("[P2PTest] 입장 동의가 필요합니다 — 멀티플레이 화면에서 모드의 Join Room(방 참가) 화면에 들어가 동의창을 읽고 직접 선택하세요 (최대 15분). 동의가 기록되면 자동으로 다시 입장합니다");
                        go(State.WAIT_CONSENT);
                    } else if (control) {
                        System.out.println("[P2PTest] CONNECT_FAIL " + describe(client.screen));
                        go(State.CONTROL);
                    } else {
                        finish(client, "FAIL 연결 끊김 화면: " + client.screen.getTitle().getString());
                    }
                } else if (inState > 20 * 120) {
                    finish(client, "FAIL 입장 시간 초과(120초)");
                }
            }
            case WAIT_SETUP -> {
                if (franchiseReady()) {
                    System.out.println("[P2PTest] 가맹점 설정 확인 — 입장 시도");
                    connect(client, code);
                } else if (inState > 20 * 60 * 15) {
                    finish(client, "TIMEOUT 가맹점 설정 대기 시간 초과");
                }
            }
            case WAIT_CONSENT -> {
                if (consentRecorded()) {
                    System.out.println("[P2PTest] 동의 기록 확인 — 다시 입장 시도");
                    connect(client, code);
                } else if (inState > 20 * 60 * 15) {
                    finish(client, "TIMEOUT 동의 대기 시간 초과");
                }
            }
            case CONTROL -> {
                boolean inWorld = client.level != null;
                if (wasInWorld && !inWorld && client.screen != null) {
                    System.out.println("[P2PTest] LEFT_WORLD " + describe(client.screen));
                }
                wasInWorld = inWorld;
                if (tick % 10 == 0) runControl(client);
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

    /** 화면 종류와, 화면이 가진 Component 필드(끊김 사유 등)의 글을 한 줄로. */
    private static String describe(net.minecraft.client.gui.screens.Screen screen) {
        StringBuilder sb = new StringBuilder(screen.getClass().getSimpleName());
        for (Class<?> c = screen.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                try {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object v = f.get(screen);
                    if (v instanceof net.minecraft.network.chat.Component comp) {
                        sb.append(" | ").append(comp.getString().replace((char) 10, ' '));
                    } else if (v instanceof java.util.Optional<?> o && o.isPresent() && o.get() instanceof net.minecraft.network.chat.Component comp2) {
                        sb.append(" | ").append(comp2.getString().replace((char) 10, ' '));
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                }
            }
        }
        return sb.toString();
    }

    private void runControl(Minecraft client) {
        java.nio.file.Path f = java.nio.file.Path.of("p2p-control.txt");
        if (!java.nio.file.Files.isRegularFile(f)) return;
        java.util.List<String> lines;
        try {
            lines = java.nio.file.Files.readAllLines(f);
            java.nio.file.Files.delete(f);
        } catch (java.io.IOException e) {
            return;
        }
        for (String line : lines) {
            String cmd = line.trim();
            if (cmd.isEmpty()) continue;
            System.out.println("[P2PTest] CMD " + cmd);
            if (cmd.equals("connect")) {
                connect(client, savedCode);
                return; // 상태가 바뀌었다
            } else if (cmd.equals("disconnect")) {
                if (client.player != null) client.player.connection.getConnection().disconnect(net.minecraft.network.chat.Component.literal("test disconnect"));
            } else if (cmd.equals("status")) {
                System.out.println("[P2PTest] STATUS inWorld=" + (client.level != null) + " screen="
                        + (client.screen == null ? "null" : client.screen.getClass().getSimpleName()));
            } else if (cmd.startsWith("chat ")) {
                if (client.player != null) client.player.connection.sendChat(cmd.substring(5));
            } else if (cmd.equals("quit")) {
                finish(client, "PASS control end");
                return;
            }
        }
    }

    private void connect(Minecraft client, String code) {
        String address = "quic." + code;
        ConnectScreen.startConnecting(client.screen, client, ServerAddress.parseString(address),
                new ServerData("p2p-test", address, ServerData.Type.OTHER), false, null);
        go(State.CONNECTING);
    }

    private static boolean franchiseReady() {
        try {
            java.nio.file.Path f = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                    .resolve("instant-p2p").resolve("settings.json");
            if (!java.nio.file.Files.isRegularFile(f)) return false;
            String t = java.nio.file.Files.readString(f);
            return t.contains("\"FRANCHISE\"") && t.contains("termsAccepted");
        } catch (java.io.IOException e) {
            return false;
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
