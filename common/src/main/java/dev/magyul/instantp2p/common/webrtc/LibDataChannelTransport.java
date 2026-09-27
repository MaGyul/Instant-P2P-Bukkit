package dev.magyul.instantp2p.common.webrtc;

import dev.magyul.instantp2p.common.transport.HostTransport;
import dev.magyul.instantp2p.common.transport.TransportListener;
import dev.magyul.instantp2p.common.transport.TransportSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tel.schich.libdatachannel.DataChannel;
import tel.schich.libdatachannel.DataChannelCallback;
import tel.schich.libdatachannel.IceState;
import tel.schich.libdatachannel.PeerConnection;
import tel.schich.libdatachannel.SessionDescriptionType;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebRTC DataChannel 전송 (libdatachannel-java). 조인자(모드, libwebrtc)가 offer와 DataChannel을 만들고
 * 호스트는 answer한다.
 * <p>
 * libdatachannel 관련 주의점은 전부 이 클래스 안에 있다:
 * <ul>
 *   <li>콜백은 네이티브 스레드에서 인라인 실행된다({@code createPeer}의 기본 executor가 {@code Runnable::run}).
 *       스레드 풀 executor로 바꾸지 말 것 — onMessage 순서가 뒤섞여 MC 스트림이 깨지고,
 *       수신 버퍼가 해제된 뒤 읽히게 된다.</li>
 *   <li>콜백 안에서 PeerConnection/DataChannel.close()를 직접 부르지 않는다. close()는 진행 중인 콜백이
 *       끝나길 기다리므로 네이티브 정리는 {@link #nativeCloser}로 넘긴다.</li>
 *   <li>래퍼 close()가 핸들 삭제 후 콜백 해제를 시도해 "ID does not exist" 에러를 찍는다 —
 *       닫기 전 리스너 컨테이너를 전부 {@code deregisterAll()}. <b>새 리스너를 등록하면 {@link #releaseNative}에도 추가할 것.</b></li>
 *   <li>{@code selectedCandidatePair()}는 쓰지 않는다 — relay 판별은 {@link WebRtcStats.RelayTracker}.</li>
 * </ul>
 */
final class LibDataChannelTransport implements HostTransport {

    private static final Logger LOG = LoggerFactory.getLogger("webrtc-host");

    // 버퍼 한도는 P2PConfig에서 관리 — 지연/처리량 트레이드오프 근거와
    // -Dkfcudp.pipe.* 되돌리기 방법은 그쪽 주석 참고.
    private static final long DC_BUF_HIGH = P2PConfig.DC_BUF_HIGH;
    private static final long DC_BUF_LOW  = P2PConfig.DC_BUF_LOW; // 이하로 빠지면 송신 재개

    private final boolean relayOnly;

    /** PeerConnection/DataChannel 네이티브 정리 전용 (releaseNative 참고) */
    private final ExecutorService nativeCloser =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "webrtc-host-closer");
                t.setDaemon(true);
                return t;
            });

    LibDataChannelTransport(boolean relayOnly) {
        this.relayOnly = relayOnly;
    }

    @Override
    public TransportSession createSession(String sid, boolean allowRelay, List<String[]> relays, TransportListener listener) {
        return new Session(sid, allowRelay, relays, listener);
    }

    @Override
    public void close() {
        // 세션들이 넘긴 네이티브 정리를 마저 끝낸다 (서버 종료 시 PeerConnection 누수 방지)
        nativeCloser.shutdown();
        try {
            if (!nativeCloser.awaitTermination(3, TimeUnit.SECONDS)) {
                LOG.warn("[host] native cleanup did not finish in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final class Session implements TransportSession {
        private final String sid;
        private final boolean allowRelay;
        private final TransportListener listener;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private volatile PeerConnection peerConnection;
        private volatile DataChannel dataChannel;

        /** 백프레셔 대기/웨이크업 (onBufferedAmountLow 이벤트 기반) */
        private final Object bpLock = new Object();
        /** remote description 적용 전에 도착한 후보 {candidate, mid} */
        private final List<String[]> queuedIce = new ArrayList<>();
        private volatile boolean remoteSet = false;
        /** DataChannel open 처리를 한 번만 하기 위한 플래그 (onOpen/isOpen 양쪽에서 들어올 수 있음) */
        private final AtomicBoolean openHandled = new AtomicBoolean(false);
        /** 협상된 원격 max-message-size와 BATCH_MAX 중 작은 값. open 시점에 확정. */
        private volatile int maxSendSize = BatchPipe.BATCH_MAX;
        private final WebRtcStats.RelayTracker relayTracker = new WebRtcStats.RelayTracker();

        Session(String sid, boolean allowRelay, List<String[]> relays, TransportListener listener) {
            this.sid = sid;
            this.allowRelay = allowRelay;
            this.listener = listener;

            // webrtc-java 시절의 -Dkfcudp.ice.anyaddress(portAllocatorConfig)는 libdatachannel에 대응 옵션이 없다.
            PeerConnection pc = PeerConnection.createPeer(IceConfig.build(relays, "host", allowRelay, relayOnly));
            peerConnection = pc;

            // answer는 setRemoteDescription(offer) 후 자동 협상으로 만들어져 이 콜백으로 온다.
            // (webrtc-java의 createAnswer/setLocalDescription 단계가 없다)
            pc.onLocalDescription.register((p, sdp, type) -> {
                if (closed.get() || type != SessionDescriptionType.ANSWER) return;
                listener.onLocalDescription("answer", sdp);
            });

            pc.onLocalCandidate.register((p, candidate, mid) -> {
                if (closed.get()) return;
                // libwebrtc(조인자)가 보내는 형식과 맞춘다: "a=" 없이 "candidate:..."
                String c = candidate.startsWith("a=") ? candidate.substring(2) : candidate;
                relayTracker.observe(c);
                listener.onLocalCandidate(c, mid != null && !mid.isEmpty() ? mid : "0");
            });

            pc.onIceStateChange.register((p, state) -> {
                if (closed.get()) return;
                // FAILED에서만 종료, DISCONNECTED는 자동 복구 대기
                if (state == IceState.RTC_ICE_FAILED) {
                    LOG.warn("[host] ICE failed sid={} (allowRelay={})", sid, allowRelay);
                    listener.onFailed();
                } else if (state == IceState.RTC_ICE_DISCONNECTED) {
                    LOG.warn("[host] ICE disconnected sid={}, waiting for reconnect...", sid);
                }
            });

            pc.onDataChannel.register((p, channel) -> {
                if (closed.get()) return;
                LOG.info("[host] DataChannel attached sid={} label={}", sid, channel.label());
                dataChannel = channel;
                setupDataChannel(channel);
            });
        }

        @Override
        public void begin(String offer) {
            peerConnection.setRemoteDescription(offer, SessionDescriptionType.OFFER);
            flushQueuedIce();
        }

        @Override
        public void addRemoteCandidate(String candidate, String mid) {
            relayTracker.observe(candidate);
            synchronized (queuedIce) {
                if (!remoteSet) {
                    queuedIce.add(new String[]{candidate, mid});
                    return;
                }
            }
            applyRemoteIce(candidate, mid);
        }

        private void flushQueuedIce() {
            List<String[]> toApply;
            synchronized (queuedIce) {
                remoteSet = true;
                toApply = new ArrayList<>(queuedIce);
                queuedIce.clear();
            }
            for (String[] c : toApply) applyRemoteIce(c[0], c[1]);
        }

        private void applyRemoteIce(String candidate, String mid) {
            PeerConnection pc = peerConnection;
            if (pc == null || closed.get()) return;
            try {
                pc.addRemoteCandidate(candidate, mid);
            } catch (Exception e) {
                // 해석 못 하는 후보(mDNS .local 등)는 하나 빠져도 나머지로 연결된다
                LOG.debug("[host] remote candidate rejected sid={}: {} ({})", sid, candidate, e.toString());
            }
        }

        private void setupDataChannel(DataChannel channel) {
            try {
                channel.bufferedAmountLowThreshold((int) DC_BUF_LOW);
            } catch (Exception e) {
                LOG.warn("[host] bufferedAmountLowThreshold failed sid={}: {}", sid, e.toString());
            }
            // 하강 에지(threshold 초과 → 이하)에서만 불린다
            channel.onBufferedAmountLow.register(c -> {
                synchronized (bpLock) { bpLock.notifyAll(); }
            });
            channel.onOpen.register(this::onChannelOpen);
            channel.onClosed.register(c -> {
                LOG.info("[host] DataChannel closed sid={}", sid);
                listener.onClosed();
            });
            channel.onError.register((c, error) -> {
                LOG.warn("[host] DataChannel error sid={}: {}", sid, error);
                listener.onClosed();
            });
            // 수신 버퍼는 네이티브 메모리를 그대로 감싼 것이라 이 콜백이 끝나면 해제된다.
            // 리스너(onData)가 콜백 안에서 동기적으로 복사해야 한다 — TransportListener.onData 참고.
            channel.onMessage.register(DataChannelCallback.Message.handleBinary((c, buffer) -> {
                if (!closed.get()) listener.onData(buffer);
            }));

            // 원격이 만든 채널은 콜백 시점에 이미 open일 수 있다
            if (channel.isOpen()) onChannelOpen(channel);
        }

        private void onChannelOpen(DataChannel channel) {
            if (closed.get() || !openHandled.compareAndSet(false, true)) return;
            try {
                maxSendSize = Math.max(1, Math.min(BatchPipe.BATCH_MAX, channel.maxMessageSize()));
            } catch (Exception e) {
                maxSendSize = 64 * 1024; // 협상값을 못 읽으면 SDP 미기재 시 기본값으로 보수적으로
            }
            listener.onOpen();
            LOG.info("[host] DataChannel open; waiting for first data sid={} (maxMessageSize={})", sid, maxSendSize);
        }

        @Override
        public boolean send(ByteBuffer buf) throws InterruptedException {
            DataChannel ch = dataChannel;
            if (ch == null || closed.get() || !ch.isOpen()) return false;

            // 이벤트 기반 백프레셔: onBufferedAmountLow가 깨움 (50ms 안전 타임아웃)
            while (ch.bufferedAmount() > DC_BUF_HIGH) {
                if (closed.get() || !ch.isOpen()) return false;
                synchronized (bpLock) {
                    if (ch.bufferedAmount() > DC_BUF_HIGH) bpLock.wait(50);
                }
            }
            if (closed.get()) return false;

            // sendMessage(ByteBuffer)는 position~limit 구간을 보내고 호출 중에 네이티브로
            // 복사한다 (webrtc-java처럼 slice()할 필요 없음). 원격 max-message-size를 넘지 않게 잘라서 보낸다.
            final int start = buf.position();
            final int end = buf.limit();
            final int max = maxSendSize;
            try {
                while (buf.position() < end) {
                    int next = Math.min(end, buf.position() + max);
                    buf.limit(next);
                    ch.sendMessage(buf);
                    buf.position(next);
                    buf.limit(end);
                }
            } finally {
                buf.limit(end);
                buf.position(start);
            }
            return true;
        }

        @Override
        public Boolean usesRelay() {
            return relayTracker.usesRelay(peerConnection, allowRelay);
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            synchronized (bpLock) { bpLock.notifyAll(); } // 백프레셔 대기 해제
            DataChannel dc = dataChannel;
            dataChannel = null;
            PeerConnection pc = peerConnection;
            peerConnection = null;
            releaseNative(dc, pc);
        }
    }

    /**
     * 네이티브 정리는 전용 스레드에서 한다. close()는 진행 중인 콜백이 끝나길 기다리는데,
     * 우리 close 경로는 그 콜백 안(onClosed/onError/onIceStateChange)에서 시작되는 경우가 많다.
     */
    private void releaseNative(DataChannel dc, PeerConnection pc) {
        if (dc == null && pc == null) return;
        Runnable r = () -> {
            // libdatachannel-java의 close()는 핸들을 먼저 지우고 나서 콜백을 해제하려다
            // "ID does not exist" 에러를 찍는다. 핸들이 살아 있을 때 먼저 비워둔다.
            if (dc != null) {
                quietly(dc.onOpen::deregisterAll, dc.onClosed::deregisterAll, dc.onError::deregisterAll,
                        dc.onMessage::deregisterAll, dc.onBufferedAmountLow::deregisterAll);
                try { dc.close(); } catch (Exception ignored) {}
            }
            if (pc != null) {
                quietly(pc.onLocalDescription::deregisterAll, pc.onLocalCandidate::deregisterAll,
                        pc.onIceStateChange::deregisterAll, pc.onDataChannel::deregisterAll);
                try { pc.close(); } catch (Exception ignored) {}
            }
        };
        try {
            nativeCloser.execute(r);
        } catch (RejectedExecutionException e) {
            r.run();
        }
    }

    private static void quietly(Runnable... actions) {
        for (Runnable a : actions) {
            try { a.run(); } catch (Exception ignored) {}
        }
    }
}
