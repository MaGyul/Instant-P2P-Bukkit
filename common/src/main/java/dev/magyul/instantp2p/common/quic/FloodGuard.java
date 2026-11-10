package dev.magyul.instantp2p.common.quic;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * [1.3.0] 모르는 주소에서 UDP 패킷이 몰려오는 걸 걸러낸다(원본 1.5.1 {@code FloodGuard}의 동작을 따름).
 * <p>
 * "아는 주소"(STUN/TURN 서버, 협상 중인 상대의 후보, 확인된 경로 이동)는 항상 통과한다. 모르는 주소는
 * 전체 초당 {@link #UNKNOWN_PPS}개를 넘으면 {@link #DEFENSE_MS} 동안 전부 버리고, 그 밑에서도 주소 하나당
 * 초당 {@link #PER_IP_PPS}개까지만 받는다. 방어가 켜지고 꺼질 때 {@code onChange}로 알린다.
 * 이미 연결된 접속자는 QUIC 연결 ID로 아는 주소로 취급되므로 영향이 없다(상대 후보가 {@code markKnown}에 들어가 있다).
 */
final class FloodGuard {
    static final int UNKNOWN_PPS = 300;
    static final int PER_IP_PPS = 60;
    static final long DEFENSE_MS = 300_000L;
    private static final int MAX_IPS = 4096;

    private final Consumer<Boolean> onChange;
    private long windowStart;
    private long unknownInWindow;
    private long defenseUntil;
    private boolean announced;
    private final Map<String, int[]> perIp = new HashMap<>();

    FloodGuard(Consumer<Boolean> onChange) {
        this.onChange = onChange;
    }

    /** 이 패킷을 받아들일지. */
    synchronized boolean admit(String ip, boolean known, long now) {
        if (announced && now >= defenseUntil) {
            announced = false;
            onChange.accept(false);
        }
        if (now - windowStart >= 1000L) {
            windowStart = now;
            unknownInWindow = 0;
            perIp.clear();
        }
        if (known) return true;
        if (++unknownInWindow > UNKNOWN_PPS) {
            defenseUntil = now + DEFENSE_MS;
            if (!announced) {
                announced = true;
                onChange.accept(true);
            }
        }
        if (now < defenseUntil) return false;
        int[] c = perIp.get(ip);
        if (c == null) {
            if (perIp.size() >= MAX_IPS) return false;
            c = new int[1];
            perIp.put(ip, c);
        }
        return ++c[0] <= PER_IP_PPS;
    }

    synchronized boolean defending(long now) {
        return now < defenseUntil;
    }
}
