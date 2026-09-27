package dev.magyul.instantp2p.common;

import com.google.common.net.InetAddresses;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.Random;
import java.util.UUID;

public class Utils {
    private static final Random RANDOM = new Random();
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    public static String encodePlayerHashes(Collection<UUID> players, String roomCode) {
        return String.join(",", players.stream().map(player -> {
            try {
                byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                        .digest((roomCode + ":" + player).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(d, 8));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e); // SHA-256은 모든 JVM에 필수로 들어 있다
            }
        }).toList());
    }

    public static String generateCode() {
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    /**
     * 시그널링이 준 접속자 식별자 → 서버에 보여줄 주소. 리터럴 IP면 그대로, 익명 토큰(ip-xxxx)이면
     * SHA-256으로 240.0.0.0/4(예약 대역) 합성 IPv4를 만든다. 첫 옥텟은 240~254라 브로드캐스트가 나오지 않는다.
     * <p>
     * IPv6(fd00::/8)를 쓰지 않는 이유: 바닐라 IP 밴 검사는 주소 문자열을 첫 ':'에서 잘라 IPv6를 못 읽고,
     * /ban-ip·/pardon-ip 인자(Brigadier word)는 ':'를 받지 않는다.
     */
    public static InetAddress toPeerAddress(String peer) {
        String s = (peer.startsWith("[") && peer.endsWith("]"))
                ? peer.substring(1, peer.length() - 1) : peer;
        if (InetAddresses.isInetAddress(s)) {
            return InetAddresses.forString(s);
        }
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            byte[] a = {(byte) (240 + (h[0] & 0xFF) % 15), h[1], h[2], h[3]};
            return InetAddress.getByAddress(a);   // byte[] 버전은 DNS 조회 안 함
        } catch (GeneralSecurityException | UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

}
