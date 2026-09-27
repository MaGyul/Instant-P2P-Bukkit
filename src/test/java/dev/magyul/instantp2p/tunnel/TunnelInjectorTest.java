package dev.magyul.instantp2p.tunnel;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 이름 없이 타입만으로 MC 내부(리스닝 채널 목록, Connection 주소 필드)를 찾는지 — 가짜 클래스로 확인한다. */
class TunnelInjectorTest {

    // ── MC 구조를 흉내 낸 가짜 클래스 (필드 이름은 일부러 난독화된 것처럼) ─────────────

    static class FakeListener {
        private final Object a = new Object();
        private final List<String> b = new ArrayList<>();
        private final List<ChannelFuture> c = Collections.synchronizedList(new ArrayList<>());
    }

    static class BaseServer {
        private final String x = "not this";
        private FakeListener y = new FakeListener();
    }

    static class FakeServer extends BaseServer {
        private final Thread z = Thread.currentThread();
        private final int port = 25565;
    }

    /** Paper Connection처럼 SocketAddress 필드가 여러 개 — 채널 주소와 같은 값을 가진 것이 진짜 */
    static class FakeConnection extends ChannelInboundHandlerAdapter {
        public InetSocketAddress virtualHost;
        private SocketAddress q;
        public SocketAddress haProxy;

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            q = ctx.channel().remoteAddress();
            ctx.fireChannelActive();
        }
    }

    /** remoteAddress가 InetSocketAddress인 EmbeddedChannel (다이얼 소켓 = 루프백) */
    static class ChildChannel extends EmbeddedChannel {
        private final InetSocketAddress remote;

        ChildChannel(int port) {
            super(false, false);
            this.remote = new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
        }

        @Override
        protected SocketAddress remoteAddress0() {
            return remote;
        }
    }

    private final EmbeddedChannel serverChannel = new EmbeddedChannel();

    @AfterEach
    void tearDown() {
        TunnelInjector.uninject();
        serverChannel.finishAndReleaseAll();
    }

    @Test
    void findsListenerChannelsByGenericType() throws Exception {
        FakeServer server = new FakeServer();
        List<ChannelFuture> found = TunnelInjector.findListenerChannels(server);
        assertSame(fakeChannels(server), found);
    }

    @Test
    void returnsNullWhenNoListener() throws Exception {
        assertNull(TunnelInjector.findListenerChannels(new Object() { private final String s = "x"; }));
    }

    @Test
    void swapsAddressOfTunnelConnectionOnFirstRead() throws Exception {
        FakeServer server = new FakeServer();
        fakeChannels(server).add(serverChannel.newSucceededFuture());
        TunnelRegistry registry = new TunnelRegistry();
        AtomicInteger unavailable = new AtomicInteger();

        assertTrue(TunnelInjector.inject(server, registry, unavailable::incrementAndGet));

        ChildChannel child = new ChildChannel(50123);
        TunnelRegistry.Tunnel tunnel = registry.register(child.remoteAddress(), "ip-0123456789ab", "0123456789abcdef");
        FakeConnection conn = accept(child);

        assertEquals(child.remoteAddress(), conn.q); // 첫 read 전에는 루프백 그대로
        child.writeInbound(Unpooled.wrappedBuffer(new byte[]{1, 2, 3}));

        assertEquals(tunnel.spoofedRemote(), conn.q);
        assertNull(conn.virtualHost);
        assertNull(conn.haProxy);
        assertSame(tunnel, child.attr(TunnelInjector.TUNNEL).get());
        assertNull(child.pipeline().get("instantp2p_tunnel")); // 한 번 쓰고 빠진다
        assertEquals(0, unavailable.get());
        child.finishAndReleaseAll();
    }

    @Test
    void nonTunnelConnectionUntouched() throws Exception {
        FakeServer server = new FakeServer();
        fakeChannels(server).add(serverChannel.newSucceededFuture());
        assertTrue(TunnelInjector.inject(server, new TunnelRegistry(), () -> fail("should not report")));

        ChildChannel child = new ChildChannel(50124);
        FakeConnection conn = accept(child);
        child.writeInbound(Unpooled.wrappedBuffer(new byte[]{1}));

        assertEquals(child.remoteAddress(), conn.q);
        assertNull(child.pipeline().get("instantp2p_tunnel"));
        child.finishAndReleaseAll();
    }

    @Test
    void reportsUnavailableWhenListenerMissing() {
        AtomicInteger unavailable = new AtomicInteger();
        assertFalse(TunnelInjector.inject(new Object(), new TunnelRegistry(), unavailable::incrementAndGet));
        assertEquals(1, unavailable.get());
    }

    @Test
    void reportsUnavailableWhenAddressFieldMissing() throws Exception {
        FakeServer server = new FakeServer();
        fakeChannels(server).add(serverChannel.newSucceededFuture());
        TunnelRegistry registry = new TunnelRegistry();
        AtomicInteger unavailable = new AtomicInteger();
        assertTrue(TunnelInjector.inject(server, registry, unavailable::incrementAndGet));

        ChildChannel child = new ChildChannel(50125);
        registry.register(child.remoteAddress(), "ip-0123456789ab", "0123456789abcdef");
        serverChannel.writeInbound(child);
        // 주소 필드가 없는 packet_handler
        child.pipeline().addLast("packet_handler", new ChannelInboundHandlerAdapter());
        child.register();
        child.writeInbound(Unpooled.wrappedBuffer(new byte[]{1}));

        assertEquals(1, unavailable.get());
        child.finishAndReleaseAll();
    }

    /** 리스닝 채널로 자식 채널이 accept된 것처럼 흘려보내고, MC처럼 packet_handler를 붙인 뒤 활성화한다. */
    private FakeConnection accept(ChildChannel child) throws Exception {
        serverChannel.writeInbound(child);
        assertNotNull(child.pipeline().get("instantp2p_tunnel"));
        FakeConnection conn = new FakeConnection();
        child.pipeline().addLast("packet_handler", conn);
        child.register();
        child.pipeline().fireChannelActive();
        return conn;
    }

    @SuppressWarnings("unchecked")
    private static List<ChannelFuture> fakeChannels(FakeServer server) throws Exception {
        var y = BaseServer.class.getDeclaredField("y");
        y.setAccessible(true);
        var c = FakeListener.class.getDeclaredField("c");
        c.setAccessible(true);
        return (List<ChannelFuture>) c.get(y.get(server));
    }
}
