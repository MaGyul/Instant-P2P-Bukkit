package dev.magyul.instantp2p.common.quic;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class QuicIce {
   private static final Logger LOG = LoggerFactory.getLogger("quic-ice");
   private static final int GATHER_TIMEOUT_MS = 1500;
   private static final long CHECK_INTERVAL_MS = 200L;
   private static final long POLL_INTERVAL_MS = 5L;
   private static final long KEEPALIVE_MS = 10000L;
   private final IceSocket socket;
   private final AtomicBoolean ownsLoop = new AtomicBoolean(true);
   private final AtomicBoolean closed = new AtomicBoolean(false);
   private final Map<String, InetSocketAddress> pending = new ConcurrentHashMap();
   private final List<Candidate> remote = new CopyOnWriteArrayList();
   private volatile InetSocketAddress validated;
   private volatile String validatedType = "?";
   private final Set<String> responsive = ConcurrentHashMap.newKeySet();
   private volatile long rttMs = 0L;
   private final Map<String, Long> sentAt = new ConcurrentHashMap();
   private volatile Thread loopThread;
   private volatile Thread keepaliveThread;
   private final String stunUrl;
   private volatile TurnAllocation turn;
   private static final boolean SRFLX_ONLY = Boolean.getBoolean("kfcudp.quic.srflxonly");
   private final boolean relayOnly;

   QuicIce(String stunUrl, boolean relayOnly) throws SocketException {
      this.stunUrl = stunUrl;
      this.relayOnly = relayOnly;
      this.socket = new IceSocket(this);
   }

   void enableTurn(String turnUrl, String user, String pass) {
      InetSocketAddress server = turnUrl != null ? TurnAllocation.parseUrl(turnUrl) : null;
      if (server != null) {
         TurnAllocation alloc = new TurnAllocation(this.socket, server, user, pass);
         if (alloc.allocate() != null) {
            this.turn = alloc;
            this.socket.turn = alloc;
         }

      }
   }

   DatagramSocket socket() {
      return this.socket;
   }

   boolean isRelayOnly() {
      return this.relayOnly;
   }

   long lastRttMs() {
      return this.rttMs;
   }

   int localPort() {
      return this.socket.getLocalPort();
   }

   InetSocketAddress validated() {
      return this.validated;
   }

   String validatedType() {
      return this.validatedType;
   }

   boolean traversedNat() {
      return !"host".equals(this.validatedType);
   }

   boolean usesRelay(Candidate c) {
      if (c == null) {
         return false;
      } else {
         return "relay".equals(c.type()) || this.socket.hasRelayChannel(c.address());
      }
   }

   List<Candidate> gather() {
      Set<Candidate> out = new LinkedHashSet();
      int port = this.socket.getLocalPort();
      if (!SRFLX_ONLY) {
         for(String ip : localAddresses()) {
            out.add(new Candidate(ip, port, "host"));
         }
      }

      InetSocketAddress srflx = this.discoverReflexive();
      if (srflx != null) {
         out.add(new Candidate(srflx.getAddress().getHostAddress(), srflx.getPort(), "srflx"));
      } else {
         LOG.info("[ice] srflx 수집 실패 — host 후보로만 진행(같은 LAN 한정)");
      }

      TurnAllocation alloc = this.turn;
      if (alloc != null && alloc.relayedAddress() != null) {
         InetSocketAddress r = alloc.relayedAddress();
         out.add(new Candidate(r.getAddress().getHostAddress(), r.getPort(), "relay"));
      }

      if (SRFLX_ONLY) {
         LOG.info("[ice] srflxonly — host 후보를 버린다(공인 주소 경로만 시험)");
      }

      List<Candidate> list = new ArrayList(out);
      LOG.info("[ice] 후보 {}개 수집(host {}, srflx {})", new Object[]{list.size(), list.stream().filter((c) -> c.type().equals("host")).count(), list.stream().filter((c) -> c.type().equals("srflx")).count()});
      return list;
   }

   /** STUN 서버에 물어 이 소켓의 공인 주소(srflx)를 알아낸다. 실패하면 null. */
   private InetSocketAddress discoverReflexive() {
      try {
         InetSocketAddress stunServer = parseStunUrl(this.stunUrl);
         if (stunServer == null) return null;
         byte[] txId = Stun.newTransactionId();
         this.socket.send(Stun.packet(Stun.bindingRequest(txId), stunServer));
         long deadline = System.currentTimeMillis() + 1500L;
         byte[] buf = new byte[1500];
         while (System.currentTimeMillis() < deadline) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            this.socket.setSoTimeout((int) Math.max(1L, deadline - System.currentTimeMillis()));
            this.socket.receiveRaw(p);
            if (Stun.looksLikeStun(p.getData(), p.getOffset(), p.getLength())
                  && Stun.isSuccess(p.getData(), p.getOffset())
                  && Arrays.equals(txId, Stun.transactionId(p.getData(), p.getOffset()))) {
               return Stun.mappedAddress(p.getData(), p.getOffset(), p.getLength());
            }
         }
         return null;
      } catch (Exception e) {
         LOG.debug("[ice] srflx 수집 중 예외: {}", e.getMessage());
         return null;
      } finally {
         try {
            this.socket.setSoTimeout(0);
         } catch (SocketException ignored) {
         }
      }
   }

   private static InetSocketAddress parseStunUrl(String url) {
      String s = url.startsWith("stun:") ? url.substring(5) : url;
      int colon = s.lastIndexOf(58);
      if (colon <= 0) {
         return null;
      } else {
         try {
            return new InetSocketAddress(s.substring(0, colon), Integer.parseInt(s.substring(colon + 1)));
         } catch (Exception var4) {
            return null;
         }
      }
   }

   private static List<String> localAddresses() {
      List<String> out = new ArrayList();

      try {
         Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();

         while(e.hasMoreElements()) {
            NetworkInterface ni = (NetworkInterface)e.nextElement();
            if (ni.isUp() && !ni.isLoopback() && !ni.isVirtual()) {
               Enumeration<InetAddress> a = ni.getInetAddresses();

               while(a.hasMoreElements()) {
                  InetAddress addr = (InetAddress)a.nextElement();
                  if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                     out.add(addr.getHostAddress());
                  }
               }
            }
         }
      } catch (Exception var5) {
      }

      if (out.isEmpty()) {
         out.add("127.0.0.1");
      }

      return out;
   }

   static List<Candidate> advertised(List<Candidate> all, boolean relayOnly, boolean peerUsesRelay) {
      if (relayOnly) {
         List<Candidate> out = new ArrayList();

         for(Candidate c : all) {
            if ("relay".equals(c.type())) {
               out.add(c);
            }
         }

         return out;
      } else if (peerUsesRelay) {
         List<Candidate> out = new ArrayList();

         for(Candidate c : all) {
            if (!"host".equals(c.type())) {
               out.add(c);
            }
         }

         return out;
      } else {
         return all;
      }
   }

   void addRemote(Candidate c) {
      if (c != null) {
         if (!SRFLX_ONLY || !"host".equals(c.type())) {
            if (!this.remote.contains(c)) {
               this.remote.add(c);
            }

            TurnAllocation alloc = this.turn;
            if (alloc != null) {
               if (!"host".equals(c.type())) {
                  Thread perm = new Thread(() -> alloc.createPermission(c.address()), "quic-turn-perm");
                  perm.setDaemon(true);
                  perm.start();
               }
            }
         }
      }
   }

   Candidate punch(long timeoutMs, boolean ownLoop) {
      return this.punch(this.remote, timeoutMs, ownLoop, this.relayOnly);
   }

   Candidate punchDirectOnly(long timeoutMs, boolean ownLoop) {
      List<Candidate> direct = new ArrayList();

      for(Candidate c : this.remote) {
         if (!"relay".equals(c.type())) {
            direct.add(c);
         }
      }

      if (direct.isEmpty()) {
         return null;
      } else {
         return this.punch(direct, timeoutMs, ownLoop, false);
      }
   }

   boolean peerIsRelayOnly() {
      if (this.remote.isEmpty()) {
         return false;
      } else {
         for(Candidate c : this.remote) {
            if (!"relay".equals(c.type())) {
               return false;
            }
         }

         return true;
      }
   }

   boolean sendsViaRelayTo(String ip) {
      return this.socket.hasRelayChannelForIp(ip);
   }

   Candidate punch(List<Candidate> cands, long timeoutMs, boolean ownLoop, boolean viaRelay) {
      if (ownLoop) {
         this.startOwnLoop();
      }

      long deadline = System.currentTimeMillis() + timeoutMs;
      Candidate hit = null;
      Candidate bound = viaRelay ? this.bindRelayFor(cands) : null;
      long nextSend = 0L;

      while(hit == null && System.currentTimeMillis() < deadline && !this.closed.get()) {
         long now = System.currentTimeMillis();
         if (now >= nextSend) {
            if (viaRelay && bound == null) {
               bound = this.bindRelayFor(cands);
            }

            if (bound != null) {
               this.sendCheck(bound.address());
            } else {
               for(Candidate c : cands) {
                  this.sendCheck(c.address());
               }
            }

            nextSend = now + 200L;
         }

         hit = this.firstResponsive(bound != null ? List.of(bound) : cands);
         if (hit != null) {
            break;
         }

         try {
            Thread.sleep(5L);
         } catch (InterruptedException var16) {
            break;
         }
      }

      if (hit != null) {
         long took = timeoutMs - (deadline - System.currentTimeMillis());
         boolean relayed = this.usesRelay(hit);
         LOG.info("[ice] 경로 확정: 상대 후보 typ={} ({}) — {}ms, 상대 후보 {}개 중 — keepalive 시작", new Object[]{hit.type(), relayed ? "중계 경유" : ("host".equals(hit.type()) ? "같은 LAN" : "공인 주소로 통함 = NAT 통과"), took, cands.size()});
         if (this.validated == null) {
            this.validated = hit.address();
            this.validatedType = hit.type();
         }

         this.startKeepalive();
         return hit;
      } else {
         LOG.warn("[ice] 경로를 못 뚫었다 — 상대 후보 {}개 전부 무응답", cands.size());
         return null;
      }
   }

   List<Candidate> responsiveCandidates() {
      List<Candidate> out = new ArrayList();

      for(String type : new String[]{"srflx", "relay", "host"}) {
         for(Candidate c : this.remote) {
            if (type.equals(c.type()) && this.responsive.contains(addrKey(c.address())) && !out.contains(c)) {
               out.add(c);
            }
         }
      }

      return out;
   }

   boolean isClosing() {
      return this.closed.get();
   }

   private Candidate firstResponsive(List<Candidate> cands) {
      for(Candidate c : cands) {
         if (this.responsive.contains(addrKey(c.address()))) {
            return c;
         }
      }

      return null;
   }

   private static String addrKey(InetSocketAddress a) {
      String var10000 = a.getAddress().getHostAddress();
      return var10000 + ":" + a.getPort();
   }

   Candidate bindRelayFor(List<Candidate> cands) {
      TurnAllocation alloc = this.turn;
      if (alloc != null && !cands.isEmpty()) {
         for(String type : new String[]{"srflx", "relay"}) {
            for(Candidate c : cands) {
               if (type.equals(c.type())) {
                  int channel = alloc.bindChannel(c.address());
                  if (channel >= 0) {
                     this.socket.addRelayPeer(c.address(), channel);
                     LOG.info("[turn] 중계 경로 준비 — 상대 후보 typ={}", c.type());
                     return c;
                  }
               }
            }
         }

         return null;
      } else {
         return null;
      }
   }

   private void sendCheck(InetSocketAddress to) {
      try {
         byte[] txId = Stun.newTransactionId();
         this.pending.put(key(txId), to);
         this.sentAt.put(key(txId), System.currentTimeMillis());
         this.socket.send(Stun.packet(Stun.paddedBindingRequest(txId), to));
      } catch (IOException e) {
         LOG.debug("[ice] 체크 전송 실패: {}", e.getMessage());
      }

   }

   void onStun(DatagramPacket p) {
      byte[] buf = p.getData();
      int off = p.getOffset();
      InetSocketAddress from = (InetSocketAddress)p.getSocketAddress();
      if (Stun.isRequest(buf, off)) {
         byte[] resp = Stun.bindingSuccess(Stun.transactionId(buf, off), from);
         if (resp != null) {
            try {
               this.socket.send(Stun.packet(resp, from));
            } catch (IOException var9) {
            }
         }

      } else if (Stun.isSuccess(buf, off)) {
         String tx = key(Stun.transactionId(buf, off));
         Long sent = (Long)this.sentAt.remove(tx);
         if (sent != null) {
            this.rttMs = Math.max(1L, System.currentTimeMillis() - sent);
         }

         InetSocketAddress target = (InetSocketAddress)this.pending.remove(tx);
         if (target != null) {
            String type = this.candidateType(from);
            if (type != null) {
               this.responsive.add(addrKey(target));
               if (this.validated == null) {
                  this.validatedType = type;
                  this.validated = target;
               }

            }
         }
      }
   }

   private String candidateType(InetSocketAddress from) {
      for(Candidate c : this.remote) {
         if (c.port() == from.getPort() && c.ip().equals(from.getAddress().getHostAddress())) {
            return c.type();
         }
      }

      return null;
   }

   private void startOwnLoop() {
      if (this.loopThread == null) {
         Thread t = new Thread(() -> {
            byte[] buf = new byte[2048];

            while(this.ownsLoop.get() && !this.closed.get()) {
               try {
                  DatagramPacket p = new DatagramPacket(buf, buf.length);
                  this.socket.receive(p);
               } catch (IOException e) {
                  if (!this.closed.get()) {
                     LOG.debug("[ice] 루프 종료: {}", e.getMessage());
                  }

                  return;
               }
            }

         }, "quic-ice-loop");
         t.setDaemon(true);
         this.loopThread = t;
         t.start();
      }
   }

   void stopOwnLoop() {
      this.ownsLoop.set(false);
      Thread t = this.loopThread;
      if (t != null) {
         try {
            byte[] wake = new byte[]{87, 65, 75, 69};
            this.socket.send(new DatagramPacket(wake, wake.length, new InetSocketAddress(InetAddress.getLoopbackAddress(), this.socket.getLocalPort())));
         } catch (IOException var4) {
         }

         try {
            t.join(500L);
         } catch (InterruptedException var3) {
         }

         this.loopThread = null;
      }
   }

   private void startKeepalive() {
      if (this.keepaliveThread == null) {
         Thread t = new Thread(() -> {
            for(; !this.closed.get(); this.pending.clear()) {
               try {
                  Thread.sleep(10000L);
               } catch (InterruptedException var2) {
                  return;
               }

               InetSocketAddress v = this.validated;
               if (v != null) {
                  this.sendCheck(v);
               }
            }

         }, "quic-ice-keepalive");
         t.setDaemon(true);
         this.keepaliveThread = t;
         t.start();
      }
   }

   /** TURN 할당만 먼저 반납한다 (소켓은 그대로). close()에서 다시 불려도 한 번만 보낸다. */
   void releaseTurn() {
      TurnAllocation alloc = this.turn;
      if (alloc != null) {
         alloc.close();
      }
   }

   void close() {
      if (this.closed.compareAndSet(false, true)) {
         TurnAllocation alloc = this.turn;
         if (alloc != null) {
            alloc.close();
         }

         this.stopOwnLoop();
         Thread k = this.keepaliveThread;
         if (k != null) {
            k.interrupt();
         }

         this.socket.close();
      }
   }

   private static String key(byte[] txId) {
      return HexFormat.of().formatHex(txId);
   }

   static record Candidate(String ip, int port, String type) {
      String line() {
         return this.ip + " " + this.port + " " + this.type;
      }

      static Candidate parse(String line) {
         String[] p = line.trim().split("\\s+");
         if (p.length < 3) {
            return null;
         } else {
            try {
               return new Candidate(p[0], Integer.parseInt(p[1]), p[2]);
            } catch (NumberFormatException var3) {
               return null;
            }
         }
      }

      InetSocketAddress address() {
         return new InetSocketAddress(this.ip, this.port);
      }
   }

   static final class IceSocket extends DatagramSocket {
      private final QuicIce ice;
      volatile TurnAllocation turn;
      private final Map<String, Integer> relayChannels = new ConcurrentHashMap();

      void addRelayPeer(InetSocketAddress peer, int channel) {
         this.relayChannels.put(QuicIce.addrKey(peer), channel);
      }

      boolean hasRelayChannel(InetSocketAddress peer) {
         return this.turn != null && this.relayChannels.containsKey(QuicIce.addrKey(peer));
      }

      boolean hasRelayChannelForIp(String ip) {
         if (this.turn == null) {
            return false;
         } else {
            String prefix = ip + ":";

            for(String k : this.relayChannels.keySet()) {
               if (k.startsWith(prefix)) {
                  return true;
               }
            }

            return false;
         }
      }

      private int channelFor(InetSocketAddress to) {
         Integer c = (Integer)this.relayChannels.get(QuicIce.addrKey(to));
         return c != null ? c : -1;
      }

      IceSocket(QuicIce ice) throws SocketException {
         super(new InetSocketAddress(0));
         this.ice = ice;
      }

      public void send(DatagramPacket p) throws IOException {
         TurnAllocation alloc = this.turn;
         InetSocketAddress to = (InetSocketAddress)p.getSocketAddress();
         int channel = this.channelFor(to);
         if (channel >= 0 && alloc != null) {
            byte[] framed = Turn.wrapChannelData(channel, p.getData(), p.getOffset(), p.getLength());
            super.send(new DatagramPacket(framed, framed.length, alloc.server()));
         } else if (!this.ice.relayOnly || alloc == null || TurnAllocation.sameAddress(to, alloc.server()) || to.getAddress().isLoopbackAddress()) {
            super.send(p);
         }
      }

      public void receive(DatagramPacket p) throws IOException {
         while(true) {
            super.receive(p);
            TurnAllocation alloc = this.turn;
            if (alloc == null || !TurnAllocation.sameAddress((InetSocketAddress)p.getSocketAddress(), alloc.server()) || this.unwrapRelayed(p, alloc)) {
               if (!Stun.looksLikeStun(p.getData(), p.getOffset(), p.getLength())) {
                  return;
               }

               this.ice.onStun(p);
            }
         }
      }

      private boolean unwrapRelayed(DatagramPacket p, TurnAllocation alloc) {
         byte[] buf = p.getData();
         int off = p.getOffset();
         int len = p.getLength();
         if (Turn.isChannelData(buf, off, len)) {
            InetSocketAddress peer = alloc.peerForChannel(Turn.channelOf(buf, off));
            if (peer == null) {
               return false;
            } else {
               int dataLen = Math.min(Turn.channelDataLength(buf, off), len - 4);
               System.arraycopy(buf, off + 4, buf, off, dataLen);
               p.setLength(dataLen);
               p.setSocketAddress(peer);
               return true;
            }
         } else if (Turn.isDataIndication(buf, off, len)) {
            byte[] txId = Stun.transactionId(buf, off);
            InetSocketAddress peer = Turn.dataPeer(buf, off, len, txId);
            int[] payload = Turn.dataPayload(buf, off, len);
            if (peer != null && payload != null) {
               if (this.channelFor(peer) < 0) {
                  QuicIce.LOG.info("[turn] 중계로 상대 도착 — 채널을 붙인다");
                  Thread bind = new Thread(() -> {
                     int ch = alloc.bindChannel(peer);
                     if (ch >= 0) {
                        this.addRelayPeer(peer, ch);
                     }

                  }, "quic-turn-bind");
                  bind.setDaemon(true);
                  bind.start();
               }

               int dataLen = Math.min(payload[1], len - (payload[0] - off));
               System.arraycopy(buf, payload[0], buf, off, dataLen);
               p.setLength(dataLen);
               p.setSocketAddress(peer);
               return true;
            } else {
               return false;
            }
         } else {
            alloc.onResponse(buf, off, len);
            return false;
         }
      }

      void receiveRaw(DatagramPacket p) throws IOException {
         super.receive(p);
      }
   }
}
