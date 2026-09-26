package dev.magyul.instantp2p;

import dev.magyul.instantp2p.webrtc.Roles;

import java.util.UUID;

/**
 * 모드 제작자·서포터 표시 — 제작자는 하늘색 " 🛠", 서포터는 금색 " 💬"을 이름 뒤에 붙인다. 붙이는 방식은 이 클래스 하나로 통일한다.
 * <p>
 * 스코어보드 팀 suffix 대신 플레이어 표시 이름(getDisplayName) 자체에 붙인다(mixin.DevNameMixin) — 채팅, 입장·퇴장,
 * 사망 메시지, /say, 머리 위 이름표가 전부 이 이름을 쓴다. 탭 목록은 따로 받는 이름이라 같은 표시 이름을 그대로
 * 넘긴다(mixin.DevBadgeMixin). 팀으로 하면 한 사람은 팀 하나에만 들 수 있어 월드의 팀 구성을 깨고, /team으로
 * 떼어지거나 목록에 보인다 — 표시 이름에 넣으면 명령어 쪽에선 닉네임과 한 덩어리로만 보인다.
 * <p>
 * UUID는 Mojang 인증 값이라 온라인 모드 방에선 흉내 낼 수 없다. 이 클래스는 mixin 패키지 밖에 둔다 — mixin 패키지
 * 안의 클래스는 일반 클래스처럼 불러 쓸 수 없다.
 * <p>
 * 실제 UUID 목록은 더 이상 여기 하드코딩돼 있지 않다 — {@link Roles}가
 * mc-signaling에서 받아온다(그쪽 클래스 주석 참고). 이 클래스는 그 목록을 이름 표시용으로
 * 소비하는 자리만 그대로 유지한다.
 */
public final class DevBadge {

    /** 특혜(방 정원 무시) 스위치 — false면 표시만 남고 특혜는 꺼진다. 인원 수에서 빼주는 특혜는
     * 없다(등급과 무관하게 전부 센다 — KfcudpClient.activeGuestCount 주석 참고). */
    public static final boolean PERKS_ENABLED = true;

    private DevBadge() {}

    /** 방 정원을 무시하고 들어온다(개발자·서포터) — 들어간 뒤엔 등급과 무관하게 한 자리를
     * 그대로 차지한다. 등급 판정은 {@link #roleSuffix} 하나만 거치므로, 방에 있는 동안은
     * 방장이 내려준 등급이 여기에도 그대로 반영된다. */
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
        // 접속자로 남의 방에 있는 동안은 방장이 내려준 등급이 먼저다 — 판정하는 쪽(방장)과 그리는
        // 쪽(나)이 서로 다른 roles.json 사본을 보면 화면과 실제가 어긋난다(RoomRoles 클래스 주석).
//        Integer pushed = kfc.udp.client.webrtc.RoomRoles.rankOrNull(id);
//        if (pushed != null) {
//            return switch (pushed) {
//                case 3 -> "dev";
//                case 2 -> "supporter";
//                case 1 -> "streamer";
//                default -> null;
//            };
//        }
        if (Roles.isDev(id)) return "dev";
        if (Roles.isSupporter(id)) return "supporter";
        if (Roles.isStreamer(id)) return "streamer";
        return null;
    }
}
