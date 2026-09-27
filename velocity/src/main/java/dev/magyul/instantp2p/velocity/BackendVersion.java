package dev.magyul.instantp2p.velocity;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * room_update.version을 정한다. 프록시는 여러 클라이언트 버전을 받아 "서버 버전"이 없으므로, 첫 접속 대상
 * (try 목록 첫 서버, 없으면 아무 등록 서버) 백엔드에 status ping을 보내 그 버전을 쓴다.
 * 클라이언트는 이 값을 자기 버전과 문자열 비교하므로 백엔드와 같은 버전이 나와야 한다.
 */
final class BackendVersion {

    /** "Paper 1.21.11", "1.21.11" 등에서 버전 부분 */
    private static final Pattern VERSION = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)");

    private BackendVersion() {}

    static CompletableFuture<String> ping(ProxyServer server) {
        Optional<RegisteredServer> target = firstServer(server);
        if (target.isEmpty()) return CompletableFuture.failedFuture(new IllegalStateException("등록된 백엔드 서버가 없습니다"));
        return target.get().ping().thenApply(BackendVersion::fromPing);
    }

    private static Optional<RegisteredServer> firstServer(ProxyServer server) {
        List<String> tryOrder = server.getConfiguration().getAttemptConnectionOrder();
        for (String name : tryOrder) {
            Optional<RegisteredServer> s = server.getServer(name);
            if (s.isPresent()) return s;
        }
        return server.getAllServers().stream().findFirst();
    }

    static String fromPing(ServerPing ping) {
        ServerPing.Version version = ping.getVersion();
        String parsed = parse(version.getName());
        if (parsed != null) return parsed;
        // 이름이 버전 형식이 아니면(커스텀 브랜드 등) 프로토콜 번호로 추정한다
        ProtocolVersion protocol = ProtocolVersion.getProtocolVersion(version.getProtocol());
        if (protocol == ProtocolVersion.UNKNOWN) {
            throw new IllegalStateException("백엔드 버전을 알 수 없습니다: " + version.getName() + " / " + version.getProtocol());
        }
        return protocol.getMostRecentSupportedVersion();
    }

    static String parse(String name) {
        if (name == null) return null;
        Matcher m = VERSION.matcher(name);
        String last = null;
        while (m.find()) last = m.group(1);
        return last;
    }
}
