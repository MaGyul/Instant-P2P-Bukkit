package dev.magyul.instantp2p.common.webrtc;

import dev.magyul.instantp2p.common.DevBadge;
import dev.magyul.instantp2p.common.core.P2PPlatform;
import dev.magyul.instantp2p.common.network.packet.Moderation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 개발자·서포터·방송인이 인게임에서 "차단"(BlockedPlayersScreen의 ❌ 버튼, 곧 P2PBanManager.banPlayer)한
 * 상대를, 차단자가 그 방에 있는 동안 강제로 추방(즉시 킥 + 재입장 거부)한다. 투표도, 단계적 제재도
 * 없다 — 등급이 충분하면 1명의 판단만으로 즉시 무력화된다(요구사항: "가해자가 즉시 그곳에서
 * 쫓겨나는게 최선").
 * <p>
 * <b>이 방식을 다시 "접속은 유지하고 묶어두는" 쪽으로 되돌리지 말 것</b> — 예전엔 관전 모드·위치
 * 고정·명령어 차단으로 묶어뒀는데, 명령어를 전부 막는 전용 믹스인과 매 틱 위치 강제가 필요했고
 * 그래도 순수 GUI 동작(예: 방장의 CustomRoomScreen)이나 권한 우회(예: 방장이 자기 대신 공범에게
 * /op) 같은 구멍이 계속 나왔다. 서버에서 내보내 버리면 그 사람은 애초에 접속해 있지 않으니 그런
 * 구멍 자체가 성립하지 않는다.
 * <p>
 * <b>등급</b> — 개발자(3) &gt; 서포터(2) &gt; 방송인(1) &gt; 무등급(0). 내 등급이 상대 등급보다 "엄격히"
 * 높아야만 내 차단이 추방을 건다 — 낮으면 당연히 안 통하고, 같아도(방송인이 방송인을 차단하는 등)
 * 안 통한다(동급끼리는 서로 못 쫓아낸다) — {@link #priority}.
 * <p>
 * <b>대상이 방장이면 추방하지 않는다</b> — 방장은 그 방의 서버 그 자체라 내보낼 방법이 없다(방장을
 * 끊으면 방 전체가 끝난다). 그 경우엔 이 클래스가 아무 것도 안 하고, 차단이 원래 하는 일반적인
 * 개인 효과(P2PBanManager.banPlayer의 ChatHideSync — 서로 채팅만 안 보이게)만 그대로 적용된다.
 * <p>
 * <b>차단은 원래 클라이언트 로컬 동작이다</b>(P2PBanManager 클래스 주석 참고 — 각자 자기 파일만 고친다).
 * 그런데 추방은 그 방의 실제 서버(=방장의 통합 서버)만 걸 수 있는 상태 변화라, 내가 접속자로 남의
 * 방에서 차단 버튼을 눌렀을 때는 그 요청이 방장에게 전달돼야 한다 — 새 패킷을 만드는 대신 이미
 * 연결된 바닐라 채팅 경로에 숨겨서 보낸다(KfcudpClient의 CAPACITY_MARKER와 같은 요령). 방장 자신이
 * 차단한 경우도 자기 자신에게 이 메시지를 보내는 것으로 통일해서, 두 경로가 서버(방장) 쪽에서
 * 완전히 같은 코드를 타게 한다.
 * <p>
 * <b>상태는 대상 UUID 기준으로 서버(방장)가 들고 있다</b> — 세션이 아니라 UUID라서 재접속으로는
 * 못 풀린다(재입장 자체가 checkCanJoin에서 거부됨). 1명을 여러 등급자가 동시에 차단했으면 각자의
 * 추방은 독립적으로 기록되고(holders), 그 차단자 전원이 나가거나 해제해야 실제로 재입장이
 * 허용된다.
 * <p>
 * <b>"킥"은 추방과 별개의, 훨씬 가벼운 기능이다</b>({@link #kick}) - holders에 아무 것도 남기지
 * 않는 1회성 강퇴라 재입장을 막지 않는다("물갈이"·경고 목적). 차단(BlockedPlayersScreen)과는
 * 연동되지 않고, BlockedPlayersScreen의 온라인 목록에서만 따로 건다. 등급·방장 예외 규칙은
 * 추방과 동일하게 그대로 적용된다.
 */
public final class ExpelManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("Instant-P2P");

    /** 추방 대상 UUID → 그 추방을 건 사람들(등급자) UUID 집합. 비어있으면(또는 키가 없으면) 추방
     * 상태 아님 — P2PBanManager.checkCanJoin이 재입장을 거부할 때, 그리고 onDisconnect가 나가는
     * 사람이 걸어둔 추방을 풀 때 이 맵을 본다. */
    private final Map<UUID, Set<UUID>> holders = new ConcurrentHashMap<>();

    private final P2PPlatform platform;
    /** 방 상태 요청(ACTION_REQUEST_STATE)에 답하는 방법 */
    private final Runnable broadcastRoomState;

    public ExpelManager(P2PPlatform platform, Runnable broadcastRoomState) {
        this.platform = platform;
        this.broadcastRoomState = broadcastRoomState;
    }

    /** 개발자 3 &gt; 서포터 2 &gt; 방송인 1 &gt; 무등급 0. 역할 판정·우선순위는 DevBadge.roleSuffix
     * 하나만 쓴다 — 순서를 여기 한 벌 더 적어두면 또 어긋난다(그쪽 주석 참고). */
    public static int priority(UUID id) {
        if (id == null) return 0;
        String suffix = DevBadge.roleSuffix(id);
        if (suffix == null) return 0;
        return switch (suffix) {
            case "dev" -> 3;
            case "supporter" -> 2;
            default -> 1;
        };
    }

    /** 지금 이 UUID가 추방 상태라 재입장이 막혀야 하는지 — 로그인 전 검사에서 부른다. */
    public boolean isExpelled(UUID id) {
        Set<UUID> h = holders.get(id);
        return h != null && !h.isEmpty();
    }

    /** 요청 하나를 등급 검사 후 실행한다 — 서버(방장) 스레드에서만 불린다. */
    public void handleRequest(UUID sender, int action, UUID target) {
        // 방 상태 요청은 등급과 무관하다 — 누구나 자기 화면을 맞추려고 보낼 수 있다.
        if (action == Moderation.ACTION_REQUEST_STATE) {
            broadcastRoomState.run();
            return;
        }
        // 내 등급이 상대보다 "엄격히" 높아야만 통과 — <=로 걸어서 동급끼리(둘 다 방송인끼리 등)
        // 서로 추방하는 것도 막는다. 등급 0(무등급)은 상대가 몇 등급이든 항상 0<=priority(target)이라
        // 자동으로 걸러진다(따로 0 체크를 안 해도 됨). 방장은 등급과 무관하게 최상위다.
        int senderPriority = platform.isHost(sender) ? 4 : priority(sender);
        if (senderPriority <= priority(target)) return;
        // 스트리머 등급(1)의 추방·강퇴 권한은 방송 허용 방에서만 유효 — 방송 중인 스트리머 보호가
        // 목적이다(CustomRoomScreen의 스트리머 보호 안내 팝업 참고). 해제는 막지 않는다 — 방송
        // 허용을 중간에 껐다고 이미 추방한 사람을 영영 못 풀게 되면 안 된다. 개발자·서포터는 무관하게 그대로.
        if (action != Moderation.ACTION_READMIT && senderPriority == 1 && !platform.settings().allowBroadcast()) return;
        dispatch(sender, action, target);
    }

    /** 등급 검사를 통과한(또는 방장 본인의) 요청을 실제로 실행한다. */
    private void dispatch(UUID senderUuid, int action, UUID target) {
        switch (action) {
            case Moderation.ACTION_EXPEL -> expel(senderUuid, target);
            case Moderation.ACTION_READMIT -> readmit(senderUuid, target);
            case Moderation.ACTION_KICK -> kick(senderUuid, target);
            default -> { }
        }
    }

    private void expel(UUID expellerUuid, UUID targetUuid) {
        // 오프라인 대상도 기록한다 — 클라이언트가 접속 때마다 차단 목록을 다시 보낸다.
        boolean online = platform.isOnline(targetUuid);
        if (online && platform.isHost(targetUuid)) return;
        boolean alreadyExpelled = isExpelled(targetUuid);
        holders.computeIfAbsent(targetUuid, k -> ConcurrentHashMap.newKeySet()).add(expellerUuid);
        if (alreadyExpelled || !online) return;
        platform.kick(targetUuid, "instant-p2p.msg.expelled_by", nameOrUnknown(expellerUuid));
        LOGGER.info("[expel] {} expelled by {}", targetUuid, expellerUuid);
    }

    private void readmit(UUID expellerUuid, UUID targetUuid) {
        Set<UUID> h = holders.get(targetUuid);
        if (h == null) return;
        h.remove(expellerUuid);
        if (!h.isEmpty()) return; // 다른 차단자가 아직 남아있음
        holders.remove(targetUuid);
        LOGGER.info("[expel] {} readmitted", targetUuid);
    }

    /** 추방과 달리 holders에 아무 것도 남기지 않는 1회성 강퇴 — 재입장은 막지 않는다
     * (BlockedPlayersScreen 3번째 버튼, "물갈이"용). 방장은 추방과 같은 이유로 대상에서 제외. */
    private void kick(UUID kickerUuid, UUID targetUuid) {
        if (!platform.isOnline(targetUuid) || platform.isHost(targetUuid)) return;
        platform.kick(targetUuid, "instant-p2p.msg.kicked_by", nameOrUnknown(kickerUuid));
        LOGGER.info("[expel] {} kicked by {}", targetUuid, kickerUuid);
    }

    /** 나가는 사람이 걸어둔 추방을 푼다. */
    public void onDisconnect(UUID leaving) {
        for (UUID target : List.copyOf(holders.keySet())) {
            readmit(leaving, target);
        }
    }

    private String nameOrUnknown(UUID id) {
        String name = platform.playerName(id);
        return name != null ? name : "?";
    }
}
