package dev.magyul.instantp2p.tunnel;

import dev.magyul.instantp2p.Utils;
import org.junit.jupiter.api.Test;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

/** 시그널링 remote(익명 토큰/리터럴) → 서버에 보일 주소. DNS 조회가 일어나면 안 된다. */
class PeerAddressTest {

    @Test
    void tokenBecomesSyntheticFdAddressGolden() {
        InetAddress a = Utils.toPeerAddress("ip-0123456789ab");
        assertInstanceOf(Inet6Address.class, a);
        assertEquals((byte) 0xfd, a.getAddress()[0]);
        assertEquals("fd61:972:1c98:80ef:71e8:1b26:4483:3ddc", a.getHostAddress());
    }

    @Test
    void hostnameLikeTokenIsHashedNotResolved() {
        // 호스트명처럼 보여도 DNS 조회 없이 합성 주소가 나와야 한다
        InetAddress a = Utils.toPeerAddress("localhost");
        assertEquals((byte) 0xfd, a.getAddress()[0]);
        // getByAddress(byte[])로 만든 주소는 호스트명이 없어 toString이 "/addr"로 시작한다
        assertTrue(a.toString().startsWith("/"));
    }

    @Test
    void literalsKept() {
        assertEquals("203.0.113.7", Utils.toPeerAddress("203.0.113.7").getHostAddress());
        assertEquals("2001:db8:0:0:0:0:0:1", Utils.toPeerAddress("[2001:db8::1]").getHostAddress());
        assertEquals("2001:db8:0:0:0:0:0:1", Utils.toPeerAddress("2001:db8::1").getHostAddress());
    }

    @Test
    void registryKeyedByLocalAddressAndSpoofedIsResolved() throws Exception {
        TunnelRegistry reg = new TunnelRegistry();
        InetAddress loopback = InetAddress.getLoopbackAddress();
        TunnelRegistry.Tunnel t = reg.register(new InetSocketAddress(loopback, 50000),
                "ip-0123456789ab", "0123456789abcdef");

        assertSame(t, reg.byRemote(new InetSocketAddress(loopback, 50000)).orElseThrow());
        assertTrue(reg.byRemote(new InetSocketAddress(loopback, 50001)).isEmpty());

        InetSocketAddress spoofed = t.spoofedRemote();
        assertFalse(spoofed.isUnresolved());
        assertNotNull(spoofed.getAddress());
        assertEquals(50000, spoofed.getPort());
        assertSame(t, reg.bySpoofed(spoofed).orElseThrow());

        reg.unregister(t);
        assertEquals(0, reg.size());
    }
}
