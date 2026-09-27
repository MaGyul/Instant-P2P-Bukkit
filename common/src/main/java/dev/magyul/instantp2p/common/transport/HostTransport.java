package dev.magyul.instantp2p.common.transport;

import java.util.List;

/**
 * 조인자와 데이터를 주고받는 전송 수단. 현재 구현은 WebRTC DataChannel(libdatachannel),
 * 원본 모드가 QUIC로 바뀌면 구현을 하나 더 둔다.
 * <p>
 * 시그널링(메시지 형식, 피어 이름, 세션 경로)은 모른다 — 호출자가 {@link TransportListener}로 받은
 * 로컬 description/후보를 시그널링으로 보내고, 받은 원격 후보를 {@link TransportSession}에 넣는다.
 */
public interface HostTransport {

    /**
     * 조인자 하나와의 세션을 만든다. 콜백 등록까지만 하고, 연결은 {@link TransportSession#begin}에서 시작한다
     * (세션 참조를 먼저 넘겨 두어야 begin 도중 도착하는 원격 후보를 받을 수 있다).
     *
     * @param sid        조인자 세션 식별자 (로그용)
     * @param allowRelay false면 중계(TURN) 경로를 만들지 않는다 — 직결 우선 1차 시도
     * @param relays     시그널링 서버가 내려준 {url, user, pass} (없으면 기본값)
     */
    TransportSession createSession(String sid, boolean allowRelay, List<String[]> relays, TransportListener listener);

    /** 세션들이 넘긴 정리 작업을 마무리하고 멈춘다. 모든 세션을 닫은 뒤 부른다. */
    void close();
}
