package dev.magyul.instantp2p.common.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * [1.3.1] 유저 신고 증거 조회(원본 1.5) — 시그널링 서버가 신고를 처리하며 방장에게 묻는 세 가지에 답한다.
 * <ul>
 *   <li>{@code chatquery}: 구간의 채팅 — 신고자가 낸 채팅이 방장 기록과 맞는지 대조</li>
 *   <li>{@code signquery}: 좌표의 지금 표지판 글 — 신고자가 글을 지어내지 못하게 대조</li>
 *   <li>{@code bookquery}: 책의 지금 해시 — 내용을 다시 보내지 않고 해시만 대조</li>
 * </ul>
 * 이 클래스는 플랫폼 API가 없는 부분(채팅 기록, 표지판 글 정리, 책 해시)만 가진다. 월드에서 읽는 일은 플랫폼 몫이다
 * ({@link P2PPlatform#signTexts}, {@link P2PPlatform#bookHashes}). 채팅은 <b>메모리에만</b> 두고(디스크에 쓰지 않는다)
 * 시그널링 서버가 물을 때만 보낸다 — 방이 열려 있는 동안 방 안 채팅만 담는다(원본과 같음).
 */
public final class ReportEvidence {

    // 원본(1.5.1) 값
    private static final long KEEP_MS = 3L * 24 * 60 * 60 * 1000;
    private static final int MAX_LINES = 600;
    private static final int MAX_TEXT = 256;
    private static final int MAX_NAME = 32;
    /** 응답에 싣는 채팅은 최근 150줄, 한 줄 200자까지. */
    private static final int MAX_REPLY_LINES = 150;
    private static final int MAX_REPLY_TEXT = 200;
    /** 표지판 글 한 개 상한. */
    public static final int MAX_SIGN_TEXT = 256;
    /** 표지판·책 조회 항목 수 상한(서버가 보내는 최대치). */
    public static final int MAX_SIGNS = 200;
    public static final int MAX_BOOKS = 40;
    /** 표지판 응답 크기 상한 — 랑데부 메시지 하나에 담는다. */
    public static final int MAX_SIGN_REPLY_BYTES = 48 * 1024;
    /** 서버 스레드 대기 상한. */
    private static final long SERVER_THREAD_WAIT_MS = 5_000L;

    private record Line(long t, String from, String text) {}

    private final ArrayDeque<Line> chat = new ArrayDeque<>();

    /** 채팅 한 줄을 남긴다. {@code from}은 보낸 사람 이름(시스템 메시지면 ""), {@code text}는 받는 쪽에 보이는 문장. */
    public synchronized void addChat(String from, String text) {
        if (text == null || text.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT);
        if (from == null) from = "";
        if (from.length() > MAX_NAME) from = from.substring(0, MAX_NAME);
        chat.addLast(new Line(now, from, text));
        while (chat.size() > MAX_LINES || (!chat.isEmpty() && now - chat.peekFirst().t() > KEEP_MS)) chat.pollFirst();
    }

    /** 방이 닫히면 비운다 — 다음 방에 이전 방 채팅이 섞이지 않게. */
    public synchronized void clear() {
        chat.clear();
    }

    /** [fromMs, toMs] 구간 채팅을 응답 형식({@code [{"t","from","line"}]})으로. 최근 150줄, 한 줄 200자까지. */
    public synchronized String chatWindowJson(long fromMs, long toMs) {
        List<Line> lines = new ArrayList<>();
        for (Line l : chat) if (l.t() >= fromMs && l.t() <= toMs) lines.add(l);
        if (lines.size() > MAX_REPLY_LINES) lines = lines.subList(lines.size() - MAX_REPLY_LINES, lines.size());
        JsonArray arr = new JsonArray();
        for (Line l : lines) {
            JsonObject o = new JsonObject();
            o.addProperty("t", l.t());
            o.addProperty("from", l.from());
            o.addProperty("line", l.text().length() > MAX_REPLY_TEXT ? l.text().substring(0, MAX_REPLY_TEXT) : l.text());
            arr.add(o);
        }
        return arr.toString();
    }

    // ── 표지판 ───────────────────────────────────────────────────────────────

    /**
     * 표지판 글(앞면 줄들 다음 뒷면 줄들) — 줄은 앞뒤 공백을 떼고 빈 줄은 버려 " / "로 잇는다(원본 클라이언트와 같은 규칙).
     * 글이 하나도 없으면 null. 줄에는 색 코드 같은 서식이 없는 평문을 넘긴다.
     */
    public static String signText(List<String> plainLines) {
        StringBuilder sb = new StringBuilder();
        for (String raw : plainLines) {
            String s = raw == null ? "" : raw.trim();
            if (!s.isEmpty()) sb.append(sb.length() > 0 ? " / " : "").append(s);
        }
        if (sb.length() == 0) return null;
        return sb.length() > MAX_SIGN_TEXT ? sb.substring(0, MAX_SIGN_TEXT) : sb.toString();
    }

    // ── 책 ───────────────────────────────────────────────────────────────────

    /**
     * 책의 해시 — 종류·제목·저자·쪽 내용의 SHA-256(원본 클라이언트와 <b>글자 하나까지</b> 같아야 대조가 맞는다).
     *
     * @param kind  {@code written}(서명된 책) 또는 {@code writable}(책과 깃펜)
     * @param title 제목(서명된 책만, 앞뒤 공백 제거), writable은 ""
     * @param author 저자(서명된 책만), writable은 ""
     * @param pages 쪽 평문(서식 코드 없음)
     */
    public static String bookHash(String kind, String title, String author, List<String> pages) {
        StringBuilder sb = new StringBuilder();
        sb.append(kind).append('\n').append(title == null ? "" : title.strip()).append('\n')
                .append(author == null ? "" : author.strip());
        for (String p : pages) sb.append('\u0001').append(p.replace("\r\n", "\n").stripTrailing());
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── 서버 스레드 ──────────────────────────────────────────────────────────

    /** 월드 읽기를 서버 스레드에서 돌리고 최대 5초 기다린다. 못 하면 빈 배열(분당 횟수 제한이 있어 서버 부담은 작다). */
    public static JsonArray onServerThread(P2PPlatform platform, Supplier<JsonArray> task) {
        CompletableFuture<JsonArray> f = new CompletableFuture<>();
        platform.runSync(() -> {
            try {
                f.complete(task.get());
            } catch (Throwable t) {
                f.complete(new JsonArray());
            }
        });
        try {
            return f.get(SERVER_THREAD_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return new JsonArray();
        }
    }
}
