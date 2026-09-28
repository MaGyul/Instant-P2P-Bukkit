package dev.magyul.instantp2p.common.core;

import dev.magyul.instantp2p.common.auth.HostAccount;
import dev.magyul.instantp2p.common.auth.MicrosoftAuth;

import java.util.List;
import java.util.Locale;

/**
 * {@code /p2p} 명령어 — 플랫폼은 권한 확인과 인자 전달만 하고 동작은 여기서 정한다.
 * <pre>
 *   /p2p [status]   계정·방 상태
 *   /p2p login      Microsoft 기기 코드 로그인
 *   /p2p logout     로그아웃(저장된 로그인 삭제, 열려 있으면 닫음)
 *   /p2p open       방 열기
 *   /p2p close      방 닫기
 *   /p2p code       초대 코드 보기
 *   /p2p newcode    초대 코드 새로 만들기
 *   /p2p reload     설정 파일 다시 읽기
 * </pre>
 * 권한: {@value #PERMISSION} (Fabric은 op).
 */
public final class P2PCommand {

    public static final String NAME = "p2p";
    public static final String PERMISSION = "instantp2p.admin";
    public static final List<String> SUBCOMMANDS = List.of("status", "login", "logout", "open", "close", "code", "newcode", "reload");

    private static final String K = "instant-p2p-server.";

    private P2PCommand() {}

    /** 서버 스레드. */
    public static void execute(P2PCore core, P2PSender sender, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        HostController host = core.host();
        switch (sub) {
            case "status" -> status(core, sender);
            case "login" -> login(core, sender);
            case "logout" -> host.logout(sender);
            case "open" -> host.open(sender);
            case "close" -> host.close(sender);
            case "code" -> {
                String code = host.inviteCode();
                if (code != null) sender.send(K + "code.show", new P2PText.Copy(code));
                else sender.send(K + "code.none");
            }
            case "newcode" -> host.newCode(sender);
            case "reload" -> host.reload(sender);
            default -> sender.send(K + "usage");
        }
    }

    /** 첫 번째 인자 자동 완성 */
    public static List<String> complete(String[] args) {
        if (args.length > 1) return List.of();
        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
    }

    private static void status(P2PCore core, P2PSender sender) {
        HostAccount account = core.account();
        switch (account.state()) {
            case LOGGED_IN -> sender.send(K + "status.account", account.name());
            case PENDING -> sender.send(K + "status.pending");
            case LOGGED_OUT -> sender.send(K + "status.logged_out");
        }
        HostController host = core.host();
        switch (host.state()) {
            case OPEN -> sender.send(K + "status.open", new P2PText.Copy(host.inviteCode()));
            case OPENING -> sender.send(K + "status.opening");
            case CLOSED -> sender.send(K + "status.closed");
        }
        sender.send(K + (core.settings().enabled() ? "status.auto_on" : "status.auto_off"));
    }

    private static void login(P2PCore core, P2PSender sender) {
        HostAccount account = core.account();
        if (account.state() == HostAccount.State.LOGGED_IN) {
            sender.send(K + "login.already", account.name());
            return;
        }
        MicrosoftAuth.DeviceCode pending = account.pendingCode();
        if (pending != null) {
            sender.send(K + "login.code", new P2PText.Link(pending.verificationUri()), new P2PText.Copy(pending.userCode()), minutesLeft(pending));
            return;
        }
        P2PPlatform platform = core.platform();
        boolean started = account.login(
                code -> platform.runSync(() -> sender.send(K + "login.code", new P2PText.Link(code.verificationUri()),
                        new P2PText.Copy(code.userCode()), minutesLeft(code))),
                error -> platform.runSync(() -> {
                    if (error != null) {
                        sender.send(K + "login.failed", error);
                        return;
                    }
                    sender.send(K + "login.success", account.name());
                    // 자동 열기가 켜져 있는데 로그인 전이라 못 열었던 경우 — 이제 연다
                    if (core.settings().enabled() && core.host().state() == HostController.RoomState.CLOSED) {
                        core.host().open(sender);
                    }
                }));
        sender.send(K + (started ? "login.starting" : "login.pending"));
    }

    private static long minutesLeft(MicrosoftAuth.DeviceCode code) {
        return Math.max(1, (code.expiresAtMs() - System.currentTimeMillis()) / 60_000);
    }
}
