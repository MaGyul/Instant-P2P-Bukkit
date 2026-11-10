package dev.magyul.instantp2p.common.signaling;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * [1.3.0] 시그널링 서버의 공개 방 상태({@code GET /api/v1/public-rooms}, 원본 1.5.1):
 * {@code {"mode":"on"|"roles"|"off","notice":"…"}}. {@code roles}는 개발자·서포터·방송인 계정만 공개 방을 열 수 있고
 * 목록은 모두에게 보인다. {@code off}(점검 등)면 공개 방 연결이 거부되므로 계속 두드리지 말고 가끔만 다시 확인한다.
 */
public record PublicRoomsMode(String mode, String notice) {
    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-public");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** 공개 방을 받는 상태인지(on 또는 roles). */
    public boolean enabled() {
        return "on".equals(mode) || "roles".equals(mode);
    }

    public boolean rolesOnly() {
        return "roles".equals(mode);
    }

    /** 조회한다(블로킹, 최대 약 5초). 실패하거나 형식이 다르면 null. */
    public static PublicRoomsMode fetch() {
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(
                            URI.create(P2PConfig.signalingHttpUrl() + "/api/v1/public-rooms"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return null;
            return parse(r.body());
        } catch (IOException e) {
            LOG.debug("[public-room] 공개 방 상태 조회 실패: {}", e.toString());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    static PublicRoomsMode parse(String body) {
        String mode = VillasMsg.field(body, "mode");
        if (mode == null) return null;
        String notice = VillasMsg.field(body, "notice");
        return new PublicRoomsMode(mode, notice == null ? "" : notice);
    }
}
