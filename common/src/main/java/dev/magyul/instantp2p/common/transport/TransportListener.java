package dev.magyul.instantp2p.common.transport;

import java.nio.ByteBuffer;

/**
 * 전송 세션 이벤트. 구현 스레드에서 인라인으로 불린다 — 오래 막지 말 것.
 * {@link #onData}는 도착 순서대로, 한 번에 하나씩 불린다.
 */
public interface TransportListener {

    /** 시그널링으로 보낼 로컬 description (WebRTC면 type="answer") */
    void onLocalDescription(String type, String description);

    /** 시그널링으로 보낼 로컬 후보 */
    void onLocalCandidate(String candidate, String mid);

    /** 데이터를 주고받을 수 있게 됐다. 한 번만 불린다. */
    void onOpen();

    /**
     * 받은 데이터. <b>버퍼는 콜백 동안만 유효하다</b>(네이티브 메모리를 감싼 것일 수 있다) —
     * 콜백 안에서 동기적으로 복사해야 하고, 참조를 다른 스레드로 넘기면 안 된다.
     * 콜백이 블로킹하면 송신 측으로 배압이 걸린다.
     */
    void onData(ByteBuffer data);

    /** 연결 경로를 찾지 못했다 (WebRTC면 ICE failed). */
    void onFailed();

    /** 열렸던(또는 열리던) 채널이 닫히거나 오류가 났다. */
    void onClosed();
}
