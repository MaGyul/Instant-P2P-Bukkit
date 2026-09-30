package dev.magyul.instantp2p.common.core;

import dev.magyul.instantp2p.common.auth.HostAccount;
import dev.magyul.instantp2p.common.auth.MicrosoftAuth;
import dev.magyul.instantp2p.common.i18n.I18n;
import dev.magyul.instantp2p.common.signaling.SignalingServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * {@code /p2p} 명령어 — 플랫폼은 권한 확인과 인자 전달만 하고 동작은 여기서 정한다.
 * <pre>
 *   /p2p [status]   계정·방 상태
 *   /p2p login      Microsoft 기기 코드 로그인
 *   /p2p logout     로그아웃(저장된 로그인 삭제, 열려 있으면 닫음)
 *   /p2p open [publicRoom]   방 열기 (publicRoom: 이번 방은 설정과 관계없이 공개 방 목록에도)
 *   /p2p close      방 닫기
 *   /p2p code       초대 코드 보기
 *   /p2p newcode    초대 코드 새로 만들기
 *   /p2p reload     설정 파일 다시 읽기
 *   /p2p max-players [on|off|set &lt;인원&gt;]   P2P 최대 인원 (설정 파일에 저장)
 *   /p2p server [official|franchise [accept|deny]]   방을 올릴 서버 (가맹점은 약관 동의, 설정 파일에 저장)
 * </pre>
 * 권한: {@value #PERMISSION} (Fabric은 op).
 */
public final class P2PCommand {

    public static final String NAME = "p2p";
    public static final String PERMISSION = "instantp2p.admin";
    public static final List<String> SUBCOMMANDS = List.of("status", "login", "logout", "open", "close", "code", "newcode", "reload", "max-players", "server");
    public static final List<String> MAX_PLAYERS_ACTIONS = List.of("on", "off", "set");
    public static final List<String> SERVERS = List.of("official", "franchise");
    public static final List<String> TERMS_ACTIONS = List.of("accept", "deny");

    private static final String K = "instant-p2p-server.";
    private static final Logger LOG = LoggerFactory.getLogger("Instant-P2P");

    private P2PCommand() {}

    /** 서버 스레드. */
    public static void execute(P2PCore core, P2PSender sender, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        HostController host = core.host();
        switch (sub) {
            case "status" -> status(core, sender);
            case "login" -> login(core, sender);
            case "logout" -> host.logout(sender);
            case "open" -> {
                if (args.length < 2) host.open(sender);
                else if (args[1].equalsIgnoreCase("publicRoom") || args[1].equalsIgnoreCase("public")) host.open(sender, true);
                else sender.send(K + "open.usage");
            }
            case "close" -> host.close(sender);
            case "code" -> {
                String code = host.displayCode();
                if (code != null) sender.send(K + "code.show", new P2PText.Copy(code));
                else sender.send(K + "code.none");
            }
            case "newcode" -> host.newCode(sender);
            case "reload" -> host.reload(sender);
            case "max-players" -> maxPlayers(core, sender, args);
            case "server" -> server(core, sender, args);
            default -> sender.send(K + "usage");
        }
    }

    /** 인자 자동 완성 — 마지막 인자에 대한 후보 */
    public static List<String> complete(String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("open")) {
            return "publicroom".startsWith(args[1].toLowerCase(Locale.ROOT)) ? List.of("publicRoom") : List.of();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("max-players")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return MAX_PLAYERS_ACTIONS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("server")) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return SERVERS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("server") && args[1].equalsIgnoreCase("franchise")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return TERMS_ACTIONS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        return List.of();
    }

    /**
     * {@code /p2p server [official|franchise [accept|deny]]} — 방을 올릴 서버. 한 번에 한쪽만 쓴다.
     * 가맹점은 약관({@link SignalingServer#FRANCHISE_TERMS_VERSION})에 동의해야 쓴다 — 동의 전이면 약관 전문과 [동의]/[거부] 버튼을 보여 준다.
     * 설정 파일(signalingServer, franchiseTermsAccepted)에 저장하고, 방이 열려 있으면 새 서버로 다시 연다.
     */
    private static void server(P2PCore core, P2PSender sender, String[] args) {
        P2PSettings s = core.settings();
        if (args.length < 2) {
            serverStatus(core, sender);
            return;
        }
        SignalingServer want = SignalingServer.parse(args[1]);
        String action = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
        if (want == null || (want == SignalingServer.OFFICIAL && !action.isEmpty())) {
            sender.send(K + "server.usage");
            return;
        }
        if (want == SignalingServer.OFFICIAL) {
            switchServer(core, sender, SignalingServer.OFFICIAL, s.franchiseTerms());
            return;
        }
        switch (action) {
            case "" -> {
                if (s.franchiseTermsAccepted()) switchServer(core, sender, SignalingServer.FRANCHISE, s.franchiseTerms());
                else showTerms(sender);
            }
            case "accept" -> switchServer(core, sender, SignalingServer.FRANCHISE, SignalingServer.FRANCHISE_TERMS_VERSION);
            case "deny" -> {
                // 동의를 거두면 가맹점은 못 쓴다 — 가맹점이었으면 본점으로 돌아간다(가맹점 수정판과 같은 규칙)
                sender.send(K + "server.terms_denied");
                if (s.server() == SignalingServer.FRANCHISE || s.franchiseTermsAccepted()) {
                    switchServer(core, sender, SignalingServer.OFFICIAL, "");
                }
            }
            default -> sender.send(K + "server.usage");
        }
    }

    private static void serverStatus(P2PCore core, P2PSender sender) {
        SignalingServer now = core.settings().effectiveServer();
        sender.send(K + "status.server", serverLabel(now));
    }

    /** 가맹점 약관 전문 + [동의]/[거부] 버튼 (콘솔은 명령 평문) */
    private static void showTerms(P2PSender sender) {
        sender.send(K + "terms.heading", SignalingServer.FRANCHISE_TERMS_VERSION);
        sender.send(K + "terms.body");
        sender.send(K + "terms.server_note");
        sender.send(K + "terms.disclaimer");
        sender.send(K + "terms.prompt",
                new P2PText.Run("/" + NAME + " server franchise accept", K + "terms.accept"),
                new P2PText.Run("/" + NAME + " server franchise deny", K + "terms.deny"));
    }

    private static void switchServer(P2PCore core, P2PSender sender, SignalingServer target, String terms) {
        P2PSettings s = core.settings();
        if (s.server() == target && s.franchiseTerms().equals(terms)) {
            sender.send(K + "server.already", serverLabel(target));
            return;
        }
        try {
            core.platform().saveSettings(Map.of("signalingServer", target.id(), "franchiseTermsAccepted", terms));
        } catch (Exception e) {
            String why = String.valueOf(e.getMessage());
            int nl = why.indexOf('\n');
            sender.send(K + "max_players.save_failed", nl > 0 ? why.substring(0, nl).trim() : why);
            return;
        }
        boolean changed = core.applySettings(s.withServer(target, terms));
        if (target == SignalingServer.FRANCHISE && !s.franchiseTermsAccepted()) {
            LOG.info("[host] 가맹점 약관(버전 {})에 동의했습니다", terms);
        }
        sender.send(K + "server.changed", serverLabel(target));
        if (changed) core.host().reopenIfOpen(sender);
    }

    private static String serverLabel(SignalingServer s) {
        return I18n.stripLegacy(I18n.fallback(K + "server." + s.id()));
    }

    /**
     * {@code /p2p max-players [on|off|set <인원>]} — 켜져 있으면 터널 접속은 서버 정원 대신 이 값으로 막는다({@link P2PCore#isP2PFull}).
     * 값은 서버 정원을 넘을 수 없다. 설정 파일(maxPlayersEnabled, maxPlayers)에 저장한다.
     */
    private static void maxPlayers(P2PCore core, P2PSender sender, String[] args) {
        P2PSettings s = core.settings();
        int server = core.platform().maxPlayers();
        if (args.length < 2) {
            maxPlayersStatus(core, sender);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "on" -> {
                if (s.maxPlayers() < 1) {
                    sender.send(K + "max_players.need_value");
                    return;
                }
                if (saveMaxPlayers(core, sender, true, s.maxPlayers())) {
                    sender.send(K + "max_players.on", core.maxPlayers());
                    if (s.maxPlayers() > server) sender.send(K + "max_players.clamped", s.maxPlayers(), server);
                }
            }
            case "off" -> {
                if (saveMaxPlayers(core, sender, false, s.maxPlayers())) sender.send(K + "max_players.off", server);
            }
            case "set" -> {
                int n;
                try {
                    n = Integer.parseInt(args.length > 2 ? args[2] : "");
                } catch (NumberFormatException e) {
                    sender.send(K + "max_players.usage");
                    return;
                }
                if (n < 1) {
                    sender.send(K + "max_players.invalid");
                    return;
                }
                // 서버 정원이 0이어도 그대로 따른다 — 바닐라는 정원 0이면 아무도 못 들어오므로, 더 크게 정하면 모드에만 빈자리로 보인다
                if (n > server) {
                    sender.send(K + "max_players.over_server", server);
                    return;
                }
                if (saveMaxPlayers(core, sender, s.maxPlayersEnabled(), n)) {
                    sender.send(K + (s.maxPlayersEnabled() ? "max_players.set" : "max_players.set_off"), n);
                }
            }
            default -> sender.send(K + "max_players.usage");
        }
    }

    private static void maxPlayersStatus(P2PCore core, P2PSender sender) {
        if (core.settings().limitsMaxPlayers()) {
            sender.send(K + "status.max_players_on", core.maxPlayers(), core.platform().maxPlayers());
        } else {
            sender.send(K + "status.max_players_off", core.platform().maxPlayers());
        }
    }

    /** 설정 파일에 먼저 저장하고, 성공하면 적용해 모드에 보이는 정원을 다시 알린다. */
    private static boolean saveMaxPlayers(P2PCore core, P2PSender sender, boolean enabled, int max) {
        try {
            core.platform().saveSettings(Map.of("maxPlayersEnabled", enabled, "maxPlayers", max));
        } catch (Exception e) {
            String why = String.valueOf(e.getMessage());
            int nl = why.indexOf('\n');
            sender.send(K + "max_players.save_failed", nl > 0 ? why.substring(0, nl).trim() : why);
            return false;
        }
        core.applySettings(core.settings().withMaxPlayers(enabled, max));
        core.onMaxPlayersChanged();
        return true;
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
            case OPEN -> sender.send(K + "status.open", new P2PText.Copy(host.displayCode()));
            case OPENING -> sender.send(K + "status.opening");
            case CLOSED -> sender.send(K + "status.closed");
        }
        if (host.state() == HostController.RoomState.OPEN) players(core, sender);
        sender.send(K + (core.settings().enabled() ? "status.auto_on" : "status.auto_off"));
        maxPlayersStatus(core, sender);
        serverStatus(core, sender);
    }

    /** P2P로 들어와 있는 플레이어와 연결 방식 — IP는 보여 주지 않는다(방송 화면에 띄워도 되게) */
    private static void players(P2PCore core, P2PSender sender) {
        Map<String, Boolean> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        core.tunnels().players().forEach((id, relay) -> {
            String name = core.platform().playerName(id);
            if (name != null) byName.put(name, relay);
        });
        if (byName.isEmpty()) {
            sender.send(K + "status.players_none");
            return;
        }
        String direct = I18n.stripLegacy(I18n.fallback(K + "status.direct"));
        String relay = I18n.stripLegacy(I18n.fallback(K + "status.relay"));
        String list = byName.entrySet().stream()
                .map(e -> e.getKey() + "(" + (e.getValue() ? relay : direct) + ")")
                .collect(Collectors.joining(", "));
        sender.send(K + "status.players", byName.size(), list);
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
