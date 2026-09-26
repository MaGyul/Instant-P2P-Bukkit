package dev.magyul.instantp2p.webrtc;

import tel.schich.libdatachannel.PeerConnection;

import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 활성 ICE 경로가 TURN 릴레이를 타는지 판별.
 * <p>
 * libdatachannel에는 webrtc-java의 getStats()(candidate-pair/candidateType)가 없다.
 * {@code PeerConnection.selectedCandidatePair()}도 쓰지 않는다 — JNI 쪽 버퍼가 50바이트라
 * 네이티브가 채우는 "a=candidate:..." 전체 문자열이 들어가지 않아 TooSmallException이 나고,
 * 들어가더라도 래퍼가 그 문자열을 "ip:port"로 파싱하려다 깨진다.
 * <p>
 * 대신 양쪽에서 오간 후보 중 {@code typ relay}인 것의 주소를 모아 두고, 선택된 경로의
 * 로컬/원격 주소({@code rtcGetLocalAddress}/{@code rtcGetRemoteAddress}, "ip:port")가
 * 그 집합에 있는지로 판단한다. 로컬이 relay면 호스트 쪽이, 원격이 relay면 조인자 쪽이
 * TURN을 거치는 것이다.
 */
final class WebRtcStats {

    private WebRtcStats() {}

    static final class RelayTracker {
        private final Set<String> relayAddrs = ConcurrentHashMap.newKeySet();

        /** 로컬(onLocalCandidate)·원격(addRemoteIce) 후보 모두 넣는다. "a=" 유무 상관없음. */
        void observe(String candidate) {
            if (candidate == null) return;
            String c = candidate.startsWith("a=") ? candidate.substring(2) : candidate;
            // candidate:<foundation> <component> <proto> <priority> <addr> <port> typ <type> ...
            String[] f = c.trim().split("\\s+");
            if (f.length < 8 || !"typ".equals(f[6]) || !"relay".equalsIgnoreCase(f[7])) return;
            relayAddrs.add(key(f[4], f[5]));
        }

        /**
         * @param allowRelay 이 세션이 TURN 후보를 쓸 수 있었는지. false면 relay일 수 없으므로 바로 false.
         * @return 판별 불가(아직 경로 미선택 등)면 null
         */
        Boolean usesRelay(PeerConnection pc, boolean allowRelay) {
            if (!allowRelay) return false;
            if (pc == null) return null;
            try {
                InetSocketAddress local = pc.localAddress();
                InetSocketAddress remote = pc.remoteAddress();
                return relayAddrs.contains(key(local)) || relayAddrs.contains(key(remote));
            } catch (Exception e) {
                return null;
            }
        }

        private static String key(InetSocketAddress a) {
            if (a == null) return "";
            return key(a.getHostString(), Integer.toString(a.getPort()));
        }

        private static String key(String host, String port) {
            String h = host;
            if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
            return h.toLowerCase(Locale.ROOT) + "|" + port;
        }
    }
}
