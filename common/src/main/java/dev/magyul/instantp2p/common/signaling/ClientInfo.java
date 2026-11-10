package dev.magyul.instantp2p.common.signaling;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * [1.3.0] 방장 연결 URL에 붙이는 클라이언트 정보(원본 1.5.1 {@code QuicBridge.clientInfo}):
 * {@code &v=<모드 버전>&mc=<MC 버전>&j=<Java 버전>&b=<jar SHA-256>}.
 * <p>
 * 시그널링 서버는 이 값으로 너무 오래된 빌드({@code too-old}), 배포본이 아닌 빌드({@code unofficial}),
 * 취약한 Java({@code java-old})를 거를 수 있다. 서버판은 <b>사실만</b> 보낸다: {@code v}는 이 서버판이 따르는 프로토콜의
 * 모드 버전(공개 방 로비에 쓰는 것과 같은 값), {@code b}는 이 플러그인 jar 자체의 해시(jar를 못 찾으면 {@code dev}).
 * 서버판 jar를 시그널링 서버 쪽에 등록해야 하면 그건 운영자 협의 사항이다.
 */
public final class ClientInfo {
    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-clientinfo");
    private static volatile String jarHash;

    private ClientInfo() {}

    /** 항상 {@code &}로 시작한다. mc가 null이면 빠진다. */
    public static String query(String modVersion, String minecraftVersion) {
        StringBuilder sb = new StringBuilder();
        if (modVersion != null) sb.append("&v=").append(enc(modVersion));
        if (minecraftVersion != null) sb.append("&mc=").append(enc(minecraftVersion));
        sb.append("&j=").append(enc(javaVersion()));
        sb.append("&b=").append(enc(jarHash()));
        return sb.toString();
    }

    static String javaVersion() {
        try {
            return Runtime.version().toString();
        } catch (RuntimeException e) {
            return System.getProperty("java.version", "unknown");
        }
    }

    /** 이 플러그인 jar의 SHA-256(소문자 hex). jar가 아니거나 못 읽으면 {@code dev}. */
    static String jarHash() {
        String h = jarHash;
        if (h != null) return h;
        h = "dev";
        try {
            var src = ClientInfo.class.getProtectionDomain().getCodeSource();
            if (src != null && src.getLocation() != null) {
                Path p = Path.of(src.getLocation().toURI());
                if (Files.isRegularFile(p)) {
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    try (InputStream in = Files.newInputStream(p)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
                    }
                    h = HexFormat.of().formatHex(md.digest());
                }
            }
        } catch (Exception e) {
            LOG.debug("[clientinfo] jar 해시 계산 실패: {}", e.toString());
        }
        jarHash = h;
        return h;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
