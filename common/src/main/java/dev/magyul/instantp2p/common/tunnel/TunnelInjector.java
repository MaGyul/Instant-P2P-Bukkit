package dev.magyul.instantp2p.common.tunnel;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 터널 접속의 서버 쪽 주소를 실제 접속자 식별자로 바꾼다 (PlayerManagerMixin 대체).
 * <p>
 * 서버 리스닝 채널 파이프라인 맨 앞에 핸들러를 끼워서, accept된 자식 채널마다
 * TunnelAddressHandler를 붙인다. 이 핸들러는 첫 channelRead(= handshake 바이트)에서
 * 레지스트리를 조회하고, 터널이면 Connection의 주소 필드를 합성 주소로 교체한 뒤 스스로 빠진다.
 * <p>
 * handshake 패킷이 디코딩/처리되기 "전"에 교체되므로 connection-throttle,
 * 밴/IP밴, 화이트리스트, getAddress() 전부 실제 IP 기준으로 동작한다.
 * <p>
 * <b>MC 내부는 이름이 아니라 타입으로 찾는다</b> — 매핑이 로더·버전마다 다르다(Paper 1.20.5+ Mojang,
 * Spigot 난독화, Fabric intermediary). 그래서 MC 클래스를 import하지 않고 Paper/Spigot/Fabric이 이 클래스를 같이 쓴다.
 * <ul>
 *   <li>리스닝 채널 목록: MinecraftServer 필드 중, {@code List<ChannelFuture>} 필드를 가진 객체(ServerConnectionListener)의 그 필드</li>
 *   <li>접속 주소: {@code packet_handler}(Connection)의 {@code SocketAddress} 타입 필드.
 *       여러 개면 첫 read 시점에 값이 채널 remoteAddress와 같은 필드</li>
 * </ul>
 * 찾지 못하면 IP 복원만 끄고 접속은 그대로 되게 한다 — 이때 onUnavailable로 관리자에게 알린다.
 */
public final class TunnelInjector {

    private static final Logger LOGGER = LoggerFactory.getLogger("Instant-P2P");

    public static final AttributeKey<TunnelRegistry.Tunnel> TUNNEL =
            AttributeKey.valueOf("instantp2p:tunnel");

    private static final String ACCEPT_HANDLER = "instantp2p_accept";
    private static final String TUNNEL_HANDLER = "instantp2p_tunnel";
    /** MC가 Connection을 파이프라인에 붙일 때 쓰는 이름 — 문자열이라 매핑과 무관하다. */
    private static final String MC_PACKET_HANDLER = "packet_handler";

    private static final List<Channel> injected = new ArrayList<>();
    /** Connection 클래스 → 주소 필드 (첫 접속에서 판정해 캐시) */
    private static final Map<Class<?>, Field> addressFields = new ConcurrentHashMap<>();

    private static volatile TunnelRegistry registry;
    private static volatile Runnable onUnavailable = () -> {};
    /** 주소 교체 실패를 관리자에게 한 번만 알리기 위한 플래그 */
    private static final AtomicBoolean reportedFailure = new AtomicBoolean();

    private TunnelInjector() {}

    /**
     * 서버 포트가 바인딩된 이후 호출한다.
     *
     * @param minecraftServer MinecraftServer 인스턴스 (플랫폼이 얻어서 넘긴다)
     * @param onUnavailable   IP 복원을 못 하게 됐을 때 한 번 호출 (관리자 알림용)
     * @return 리스닝 채널에 주입했으면 true. false면 IP 복원이 꺼진 상태로 접속만 된다.
     */
    public static boolean inject(Object minecraftServer, TunnelRegistry tunnels, Runnable onUnavailable) {
        registry = tunnels;
        TunnelInjector.onUnavailable = Objects.requireNonNull(onUnavailable);
        reportedFailure.set(false);

        List<ChannelFuture> futures;
        try {
            futures = findListenerChannels(minecraftServer);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.error("[tunnel] 리스닝 채널 탐색 중 오류 — IP 복원을 끕니다 (접속은 됩니다)", e);
            reportUnavailable();
            return false;
        }
        if (futures == null) {
            LOGGER.error("[tunnel] {}에서 List<ChannelFuture> 필드를 가진 객체(ServerConnectionListener)를 찾지 못했습니다"
                    + " — IP 복원을 끕니다 (접속은 됩니다)", minecraftServer.getClass().getName());
            reportUnavailable();
            return false;
        }

        synchronized (futures) {
            for (ChannelFuture cf : futures) {
                Channel server = cf.channel();
                if (server.pipeline().get(ACCEPT_HANDLER) != null) continue;
                server.pipeline().addFirst(ACCEPT_HANDLER, new AcceptHandler());
                injected.add(server);
            }
        }
        LOGGER.info("[tunnel] injected into {} listener channel(s)", injected.size());
        return true;
    }

    /** 플러그인 종료 시 호출. 이미 붙은 자식 채널 핸들러는 첫 read 후 스스로 빠지므로 건드릴 필요 없다. */
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

    // ── 타입 기반 탐색 ────────────────────────────────────────────────────────

    /**
     * MinecraftServer의 인스턴스 필드 값들 중에서 {@code List<ChannelFuture>} 필드를 가진 객체를 찾아
     * 그 리스트를 돌려준다. 없으면 null.
     */
    @SuppressWarnings("unchecked")
    static List<ChannelFuture> findListenerChannels(Object minecraftServer) throws ReflectiveOperationException {
        for (Field f : instanceFields(minecraftServer.getClass())) {
            Class<?> type = f.getType();
            if (type.isPrimitive() || type.isArray() || isJdkType(type)) continue;
            if (!f.trySetAccessible()) continue;
            Object value = f.get(minecraftServer);
            if (value == null || isJdkType(value.getClass())) continue;
            Field channels = channelFutureListField(value.getClass());
            if (channels != null) {
                return (List<ChannelFuture>) channels.get(value);
            }
        }
        return null;
    }

    /** 제네릭 시그니처가 {@code List<ChannelFuture>}인 필드. 없으면 null. */
    private static Field channelFutureListField(Class<?> type) {
        for (Field f : instanceFields(type)) {
            if (!List.class.isAssignableFrom(f.getType())) continue;
            if (!isListOf(f.getGenericType(), ChannelFuture.class)) continue;
            if (f.trySetAccessible()) return f;
        }
        return null;
    }

    private static boolean isListOf(Type type, Class<?> element) {
        if (!(type instanceof ParameterizedType p)) return false;
        Type[] args = p.getActualTypeArguments();
        return args.length == 1 && args[0] instanceof Class<?> c && element.isAssignableFrom(c);
    }

    /**
     * Connection의 주소 필드. SocketAddress 필드가 하나면 그것, 여러 개면 지금 값이 채널 remoteAddress와
     * 같은 필드. 첫 read 시점에는 channelActive가 주소를 이미 채웠으므로 값으로 판별할 수 있다.
     */
    static Field addressField(Object connection, SocketAddress remote) throws IllegalAccessException {
        Class<?> type = connection.getClass();
        Field cached = addressFields.get(type);
        if (cached != null) return cached;

        List<Field> candidates = new ArrayList<>();
        for (Field f : instanceFields(type)) {
            if (SocketAddress.class.isAssignableFrom(f.getType()) && f.trySetAccessible()) candidates.add(f);
        }
        Field found = null;
        if (candidates.size() == 1) {
            found = candidates.getFirst();
        } else {
            for (Field f : candidates) {
                if (Objects.equals(f.get(connection), remote)) {
                    found = f;
                    break;
                }
            }
        }
        if (found != null) addressFields.put(type, found);
        return found;
    }

    /** 클래스 계층 전체의 인스턴스 필드 (Object 제외) */
    private static List<Field> instanceFields(Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) out.add(f);
            }
        }
        return out;
    }

    private static boolean isJdkType(Class<?> type) {
        String name = type.getName();
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.") || name.startsWith("sun.");
    }

    private static void reportUnavailable() {
        if (reportedFailure.compareAndSet(false, true)) {
            onUnavailable.run();
        }
    }

    // ── Netty 핸들러 ─────────────────────────────────────────────────────────

    /** 서버(리스닝) 채널용: accept된 자식 채널을 msg로 받는다. */
    @ChannelHandler.Sharable
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
                TunnelRegistry tunnels = registry;
                if (tunnels != null) tunnels.byRemote(remote).ifPresent(t -> swap(ctx, remote, t));
            } catch (Throwable e) {
                LOGGER.warn("[tunnel] address swap failed", e);
            } finally {
                ctx.pipeline().remove(this);
            }
            ctx.fireChannelRead(msg);
        }

        private void swap(ChannelHandlerContext ctx, SocketAddress remote, TunnelRegistry.Tunnel t) {
            Object conn = ctx.pipeline().get(MC_PACKET_HANDLER);
            if (conn == null) {
                LOGGER.warn("[tunnel] packet_handler not found; cannot restore IP for {}", t);
                reportUnavailable();
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
                Field field = addressField(conn, remote);
                if (field == null) {
                    LOGGER.error("[tunnel] {}에서 접속 주소(SocketAddress) 필드를 찾지 못했습니다 — IP 복원을 할 수 없습니다",
                            conn.getClass().getName());
                    reportUnavailable();
                    return;
                }
                field.set(conn, spoofed);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
            ctx.channel().attr(TUNNEL).set(t);
        }
    }
}
