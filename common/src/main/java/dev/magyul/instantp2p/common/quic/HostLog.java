package dev.magyul.instantp2p.common.quic;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.function.Predicate;

/**
 * [1.3.0] 방장 → 시그널링 서버 "방 기록" 해시 체인(원본 1.5.1 {@code QuicHost.sendHostLog}와 같은 형식).
 * <p>
 * 메시지: {@code {"hostlog":{"s":세션,"n":번호,"p":이전해시,"e":이벤트JSON문자열,"h":해시}}}.
 * {@code h = sha256hex(p + "|" + n + "|" + e)}, 첫 메시지의 {@code p}는 빈 문자열, {@code s}는 방마다 무작위 hex.
 * 서버는 체인이 이어지는지로 기록이 빠지거나 바뀌지 않았음을 확인하고, 주기적인 하트비트({@code {"k":"hb"}})가 오는 동안만
 * 공개 방 목록에 둔다. 연결이 끊긴 동안의 기록은 최대 {@link #MAX_PENDING}개까지 쌓았다가 다시 붙으면 순서대로 보낸다.
 */
final class HostLog {
    static final int MAX_PENDING = 200;
    static final int MAX_EVENT_CHARS = 4096;
    private static final SecureRandom RNG = new SecureRandom();

    private final Predicate<String> sink;
    private final Object lock = new Object();
    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private final String session;
    private long seq;
    private String prev = "";

    /** @param sink 메시지를 보내고, 연결이 없어 못 보냈으면 false */
    HostLog(Predicate<String> sink) {
        this.sink = sink;
        this.session = Long.toHexString(RNG.nextLong() & 0xFFFFFFFFFFFFL);
    }

    /** 이벤트 하나를 체인에 이어 보낸다(연결이 없으면 쌓아 둔다). 너무 길면 버린다. */
    void send(String eventJson) {
        if (eventJson == null || eventJson.length() > MAX_EVENT_CHARS) return;
        synchronized (lock) {
            long n = ++seq;
            String h = sha256Hex(prev + "|" + n + "|" + eventJson);
            JsonObject m = new JsonObject();
            m.addProperty("s", session);
            m.addProperty("n", n);
            m.addProperty("p", prev);
            m.addProperty("e", eventJson);
            m.addProperty("h", h);
            prev = h;
            JsonObject wrap = new JsonObject();
            wrap.add("hostlog", m);
            String msg = wrap.toString();
            if (!flushLocked() || !sink.test(msg)) {
                if (pending.size() >= MAX_PENDING) pending.pollFirst();
                pending.addLast(msg);
            }
        }
    }

    /** 다시 붙었을 때 — 쌓인 기록을 순서대로 보낸다. */
    void flush() {
        synchronized (lock) {
            flushLocked();
        }
    }

    int pendingCount() {
        synchronized (lock) {
            return pending.size();
        }
    }

    /** @return 쌓인 것을 모두 보냈으면 true */
    private boolean flushLocked() {
        String q;
        while ((q = pending.peekFirst()) != null) {
            if (!sink.test(q)) return false;
            pending.pollFirst();
        }
        return true;
    }

    static String heartbeat() {
        return "{\"k\":\"hb\"}";
    }

    static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
