package dev.magyul.instantp2p.common.core;

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
 * 등급자(개발자·서포터·방송인)가 모드 UI에서 차단한 상대를, 차단자가 서버에 있는 동안 추방한다(즉시 킥 + 재입장 거부).
 * 투표도 단계적 제재도 없다 — 등급이 충분하면 한 명의 판단으로 즉시 내보낸다(원본 요구사항).
 * <p>
 * 요청은 모드 클라이언트가 {@code instant-p2p:moderation} 채널로 보낸다. 클라이언트는 roles.json에 역할이 있는
 * 계정만 보내므로 서버 op·호스트 권한자는 모드 UI 대신 서버 명령어를 쓴다.
 * <p>
 * <b>등급</b> — 호스트 4 &gt; 개발자 3 &gt; 서포터 2 &gt; 방송인 1 &gt; 무등급 0. 보낸 사람 등급이 대상보다 <b>엄격히</b>
 * 높아야 실행한다(동급끼리는 서로 못 내보낸다) — {@link #priority}. 방송인의 추방·강퇴는 {@code allowBroadcast}일 때만.
 * <p>
 * <b>호스트는 대상이 아니다</b> — 원본에서 방장은 서버 그 자체라 내보낼 수 없었고, 여기서도 같은 규칙을 둔다.
 * <p>
 * <b>상태는 대상 UUID 기준</b>이라 재접속으로 풀리지 않는다. 여러 등급자가 같은 사람을 차단하면 각자 따로 기록되고(holders),
 * 차단자 전원이 나가거나 해제해야 재입장이 허용된다. 클라이언트는 접속할 때마다 자기 차단 목록 전체를 다시 보내므로
 * <b>오프라인 대상도 기록</b>해야 한다.
 * <p>
 * <b>킥</b>({@link #kick})은 holders에 남기지 않는 1회성 강퇴라 재입장을 막지 않는다. 등급·호스트 규칙은 같다.
 */
public final class ExpelManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("Instant-P2P");

    /** 추방 대상 UUID → 그 추방을 건 등급자 UUID 집합. 비어 있으면(또는 키가 없으면) 추방 상태가 아니다.
     * 로그인 전 검사({@link #isExpelled})와 차단자 퇴장 시 해제({@link #onDisconnect})가 이 맵을 본다. */
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
        // 목적이다(원본 모드의 스트리머 보호 안내 참고). 해제는 막지 않는다 — 방송
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
     * ("물갈이"·경고용). 호스트는 추방과 같은 이유로 대상에서 제외. */
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
