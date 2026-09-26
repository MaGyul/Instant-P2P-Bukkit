package dev.magyul.instantp2p.tunnel;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import java.lang.reflect.Field;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConnectionListener;

import static dev.magyul.instantp2p.InstantP2pBukkit.LOGGER;
import static dev.magyul.instantp2p.InstantP2pBukkit.TUNNEL_REGISTRY;

/**
 * PlayerManagerMixin 대체.
 * <p>
 * 서버 리스닝 채널 파이프라인 맨 앞에 핸들러를 끼워서, accept된 자식 채널마다
 * TunnelAddressHandler를 붙인다. 이 핸들러는 첫 channelRead(= handshake 바이트)에서
 * 레지스트리를 조회하고, 터널이면 Connection.address를 실제 IP로 교체한 뒤 스스로 빠진다.
 * <p>
 * handshake 패킷이 디코딩/처리되기 "전"에 교체되므로 Paper의 connection-throttle,
 * 밴/IP밴, 화이트리스트, getAddress() 전부 실제 IP 기준으로 동작한다.
 */

public final class TunnelInjector {

    public static final AttributeKey<TunnelRegistry.Tunnel> TUNNEL =
            AttributeKey.valueOf("instantp2p:tunnel");

    private static final String ACCEPT_HANDLER = "instantp2p_accept";
    private static final String TUNNEL_HANDLER = "instantp2p_tunnel";
    private static final String MC_PACKET_HANDLER = "packet_handler";

    private static final Field CHANNELS_FIELD;
    private static final Field ADDRESS_FIELD;

    static {
        try {
            CHANNELS_FIELD = ServerConnectionListener.class.getDeclaredField("channels");
            CHANNELS_FIELD.setAccessible(true);
            ADDRESS_FIELD = Connection.class.getDeclaredField("address");
            ADDRESS_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final List<Channel> injected = new ArrayList<>();

    /** onEnable에서 호출. 서버 포트가 바인딩된 이후여야 한다(기본 load: POSTWORLD면 OK). */
    @SuppressWarnings("unchecked")
    public static void inject() throws ReflectiveOperationException {
        ServerConnectionListener listener = MinecraftServer.getServer().getConnection();
        List<ChannelFuture> futures = (List<ChannelFuture>) CHANNELS_FIELD.get(listener);

        synchronized (futures) {
            for (ChannelFuture cf : futures) {
                Channel server = cf.channel();
                if (server.pipeline().get(ACCEPT_HANDLER) != null) continue;
                server.pipeline().addFirst(ACCEPT_HANDLER, new AcceptHandler());
                injected.add(server);
            }
        }
        LOGGER.info("[tunnel] injected into {} listener channel(s)", injected.size());
    }

    /** onDisable에서 호출. 이미 붙은 자식 채널 핸들러는 첫 read 후 스스로 빠지므로 건드릴 필요 없다. */
    public static void uninject() {
        for (Channel server : injected) {
            try {
                if (server.pipeline().get(ACCEPT_HANDLER) != null) {
                    server.pipeline().remove(ACCEPT_HANDLER);
                }
            } catch (Exception ignored) {
            }
        }
        injected.clear();
    }

    /** 서버(리스닝) 채널용: accept된 자식 채널을 msg로 받는다. */
    private static final class AcceptHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Channel child) {
                // ServerBootstrapAcceptor가 MC의 childHandler를 addLast하기 전이므로
                // 우리 핸들러가 자식 파이프라인 맨 앞에 위치한다.
                child.pipeline().addFirst(TUNNEL_HANDLER, new TunnelAddressHandler());
            }
            ctx.fireChannelRead(msg);
        }
    }

    /** 자식 채널용: 첫 read 한 번만 처리하고 제거된다. */
    private static final class TunnelAddressHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                SocketAddress remote = ctx.channel().remoteAddress();
                TUNNEL_REGISTRY.byRemote(remote).ifPresent(t -> swap(ctx, t));
            } catch (Throwable e) {
                LOGGER.warn("[tunnel] address swap failed", e);
            } finally {
                ctx.pipeline().remove(this);
            }
            ctx.fireChannelRead(msg);
        }

        private void swap(ChannelHandlerContext ctx, TunnelRegistry.Tunnel t) {
            if (!(ctx.pipeline().get(MC_PACKET_HANDLER) instanceof Connection conn)) {
                LOGGER.warn("[tunnel] packet_handler not found; cannot restore IP for {}", t);
                return;
            }
            SocketAddress spoofed;
            try {
                spoofed = t.spoofedRemote();
            } catch (IllegalArgumentException | UnknownHostException e) {
                LOGGER.warn("[tunnel] invalid peer IP '{}', keeping loopback", t.realIp());
                return;
            }
            try {
                ADDRESS_FIELD.set(conn, spoofed);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
            ctx.channel().attr(TUNNEL).set(t);
        }
    }
}
