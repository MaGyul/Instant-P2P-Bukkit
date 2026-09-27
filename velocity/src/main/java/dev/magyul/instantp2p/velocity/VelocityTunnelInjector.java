package dev.magyul.instantp2p.velocity;

import dev.magyul.instantp2p.common.tunnel.TunnelRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyProtocolVersion;
import io.netty.handler.codec.haproxy.HAProxyProxiedProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Velocity의 IP 복원. 필드를 직접 바꾸지 않고 Velocity가 원래 지원하는 경로를 쓴다 —
 * {@code MinecraftConnection.channelRead}는 {@link HAProxyMessage}를 받으면 {@code remoteAddress}를 그 주소로 바꾼다.
 * 터널 접속의 첫 read에서, 실제 바이트보다 먼저 합성 HAProxyMessage를 흘려보낸다
 * (중간 디코더들은 ByteBuf가 아닌 메시지를 그대로 넘긴다). 3.x·4.x 소스로 확인.
 * <p>
 * 주입 지점은 {@code ConnectionManager.getServerChannelInitializer()}(ServerChannelInitializerHolder) — ViaVersion 등이
 * 쓰는 확장 지점이다. 프록시 내부 클래스라 리플렉션으로 찾는다(ConnectionManager는 getter 없는 private 필드). bind 전에(ProxyInitializeEvent) 걸어야 한다.
 * <p>
 * 주의: proxy-protocol을 켠 리스너에서는 터널 접속(PROXY 헤더 없음)이 디코더에서 거부된다.
 */
final class VelocityTunnelInjector {

    private static final Logger LOGGER = LoggerFactory.getLogger("Instant-P2P");
    private static final String TUNNEL_HANDLER = "instantp2p_tunnel";

    private Object holder;
    private Method holderSet;
    private ChannelInitializer<Channel> original;

    /** @return 주입했으면 true. false면 IP 복원 없이 접속만 된다. */
    @SuppressWarnings("unchecked")
    boolean inject(Object proxyServer, TunnelRegistry tunnels) {
        try {
            Object connectionManager = connectionManager(proxyServer);
            if (connectionManager == null) {
                LOGGER.error("[tunnel] {}에서 getServerChannelInitializer()를 가진 필드(ConnectionManager)를 찾지 못했습니다"
                        + " — IP 복원을 끕니다 (접속은 됩니다)", proxyServer.getClass().getName());
                return false;
            }
            Object holder = connectionManager.getClass().getMethod("getServerChannelInitializer").invoke(connectionManager);
            Method get = holder.getClass().getMethod("get");
            Method set = holder.getClass().getMethod("set", ChannelInitializer.class);
            ChannelInitializer<Channel> original = (ChannelInitializer<Channel>) get.invoke(holder);

            Method initChannel = ChannelInitializer.class.getDeclaredMethod("initChannel", Channel.class);
            initChannel.setAccessible(true);

            set.invoke(holder, new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel ch) throws Exception {
                    initChannel.invoke(original, ch);
                    ch.pipeline().addFirst(TUNNEL_HANDLER, new TunnelAddressHandler(tunnels));
                }
            });
            this.holder = holder;
            this.holderSet = set;
            this.original = original;
            LOGGER.info("[tunnel] injected into Velocity server channel initializer");
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.error("[tunnel] Velocity 채널 초기화 지점(ConnectionManager.getServerChannelInitializer)을 찾지 못했습니다"
                    + " — IP 복원을 끕니다 (접속은 됩니다)", e);
            return false;
        }
    }

    /**
     * VelocityServer의 {@code private final ConnectionManager cm} — getter가 없다(3.x·4.x 동일).
     * 이름 대신 {@code getServerChannelInitializer()}를 가진 타입의 필드로 찾는다.
     */
    private static Object connectionManager(Object proxyServer) throws IllegalAccessException {
        for (Class<?> c = proxyServer.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.getType().getMethod("getServerChannelInitializer");
                } catch (NoSuchMethodException e) {
                    continue;
                }
                f.setAccessible(true);
                Object value = f.get(proxyServer);
                if (value != null) return value;
            }
        }
        return null;
    }

    void uninject() {
        if (holder == null) return;
        try {
            holderSet.invoke(holder, original);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        holder = null;
    }

    /** 자식 채널용: 첫 read 한 번만 처리하고 제거된다. */
    private static final class TunnelAddressHandler extends ChannelInboundHandlerAdapter {
        private final TunnelRegistry tunnels;

        TunnelAddressHandler(TunnelRegistry tunnels) {
            this.tunnels = tunnels;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                SocketAddress remote = ctx.channel().remoteAddress();
                SocketAddress local = ctx.channel().localAddress();
                tunnels.byRemote(remote).ifPresent(t -> {
                    try {
                        InetSocketAddress spoofed = t.spoofedRemote();
                        ctx.fireChannelRead(proxyMessage(spoofed, (InetSocketAddress) local));
                    } catch (Exception e) {
                        LOGGER.warn("[tunnel] invalid peer address for {}, keeping loopback ({})", t, e.toString());
                    }
                });
            } catch (Throwable e) {
                LOGGER.warn("[tunnel] address swap failed", e);
            } finally {
                ctx.pipeline().remove(this);
            }
            ctx.fireChannelRead(msg);
        }

        private static HAProxyMessage proxyMessage(InetSocketAddress source, InetSocketAddress destination) {
            boolean v4 = source.getAddress() instanceof Inet4Address;
            // 합성 주소는 IPv4지만 리터럴 IPv6가 올 수도 있다 — 목적지도 같은 계열로 맞춘다
            String dst = v4 ? "127.0.0.1" : "::1";
            if (destination != null && destination.getAddress() != null
                    && (destination.getAddress() instanceof Inet4Address) == v4) {
                dst = destination.getAddress().getHostAddress();
            }
            return new HAProxyMessage(HAProxyProtocolVersion.V2, HAProxyCommand.PROXY,
                    v4 ? HAProxyProxiedProtocol.TCP4 : HAProxyProxiedProtocol.TCP6,
                    source.getAddress().getHostAddress(), dst,
                    source.getPort(), destination != null ? destination.getPort() : 0);
        }
    }
}
