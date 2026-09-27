package dev.magyul.instantp2p.common;

import dev.magyul.instantp2p.common.signaling.Roles;

import java.util.UUID;

/**
 * 역할 판정 — 원본 모드에서 이름 배지·특혜를 정하던 규칙을 서버 쪽에서 같은 순서로 쓴다.
 * 실제 UUID 목록은 {@link Roles}가 시그널링 서버에서 받아온다.
 */
public final class DevBadge {

    /** 특혜(방 정원 무시) 스위치. 인원 수에서 빼주지는 않는다(원본과 같음). 서버 쪽 정원 무시 입장은 아직 미구현. */
    public static final boolean PERKS_ENABLED = true;

    private DevBadge() {}

    /** 방 정원을 무시하고 들어올 수 있는 등급(개발자·서포터)인지. 들어간 뒤엔 한 자리를 그대로 차지한다. */
    public static boolean hasPerk(UUID id) {
        if (!PERKS_ENABLED) return false;
        String suffix = roleSuffix(id);
        return "dev".equals(suffix) || "supporter".equals(suffix);
    }

    /**
     * 역할 번역 키 접미사 — {@code "dev"}/{@code "supporter"}/{@code "streamer"}, 셋 다 아니면 null.
     * <p>
     * <b>역할 우선순위(개발자 &gt; 서포터 &gt; 방송인)를 정하는 곳은 여기 하나뿐이다.</b> 한 사람이
     * 여러 역할을 동시에 가질 수 있어서(서포터이면서 방송인 등) 이 순서가 화면마다 어긋나면 같은
     * 사람이 화면마다 다른 역할로 보인다 — 실제로 ESC 일시정지 화면만 개발자 → 방송인 → 서포터
     * 순으로 복붙돼 있어서, 서포터 겸 방송인에게 "스트리머"라고 떴다. 새로 역할을 쓰는 곳이
     * 생기면 직접 isDev/isSupporter/isStreamer를 늘어놓지 말고 이걸 쓸 것.
     * {@code ExpelManager.priority}(3/2/1)도 같은 순서다.
     */
    public static String roleSuffix(UUID id) {
        // 서버가 방장이라 자기 Roles 사본이 곧 기준이다 (원본 클라이언트의 RoomRoles — 방장이 내려준 등급 — 는 필요 없다)
        if (Roles.isDev(id)) return "dev";
        if (Roles.isSupporter(id)) return "supporter";
        if (Roles.isStreamer(id)) return "streamer";
        return null;
    }
}
