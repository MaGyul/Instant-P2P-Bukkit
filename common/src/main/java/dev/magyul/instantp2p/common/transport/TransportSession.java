package dev.magyul.instantp2p.common.transport;

import java.nio.ByteBuffer;

/** 조인자 하나와의 전송 세션. */
public interface TransportSession {

    /**
     * 조인자의 연결 제안(WebRTC면 SDP offer)을 적용해 연결을 시작한다. 응답은
     * {@link TransportListener#onLocalDescription}으로 나온다.
     *
     * @throws Exception 제안을 적용하지 못했다 — 호출자가 세션을 닫는다
     */
    void begin(String offer) throws Exception;

    /** 시그널링으로 받은 원격 후보. begin 전에 와도 된다(구현이 보관했다가 적용). */
    void addRemoteCandidate(String candidate, String mid);

    /**
     * position~limit 구간을 보낸다. 협상된 최대 메시지 크기로 나누고, 송신 버퍼가 차 있으면 빠질 때까지 블로킹한다.
     * 버퍼의 position/limit은 호출 전과 같게 되돌려 놓는다.
     *
     * @return 세션이 닫혔거나 아직 열리지 않았으면 false
     */
    boolean send(ByteBuffer data) throws Exception;

    /** 선택된 경로가 중계를 타는지. 판별 전이면 null. */
    Boolean usesRelay();

    /** 멱등. 콜백 안에서 불러도 된다(무거운 정리는 구현이 다른 스레드로 넘긴다). */
    void close();
}
