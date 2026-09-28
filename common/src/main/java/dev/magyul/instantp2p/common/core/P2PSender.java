package dev.magyul.instantp2p.common.core;

/**
 * {@code /p2p} 명령어를 실행한 쪽(콘솔 또는 플레이어). 플랫폼이 번역 키 + 인자로 메시지를 만든다.
 * 서버 스레드에서만 부른다 — 비동기 결과는 {@link P2PPlatform#runSync}로 넘겨서 보낸다.
 */
public interface P2PSender {

    void send(String translationKey, Object... args);
}
