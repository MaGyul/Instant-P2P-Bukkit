package dev.magyul.instantp2p.common.quic;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class TurnAllocation {
   private static final Logger LOG = LoggerFactory.getLogger("quic-turn");
   private static final int LIFETIME_SEC = 600;
   private static final long REFRESH_MS = 300000L;
   private static final int RTT_TIMEOUT_MS = 4000;
   private final DatagramSocket socket;
   private final InetSocketAddress server;
   private final String username;
   private final String password;
   private volatile Turn.Credentials cred;
   private volatile InetSocketAddress relayed;
   private volatile Thread refresher;
   private final AtomicBoolean closed = new AtomicBoolean(false);
   private final Map<String, CompletableFuture<byte[]>> pending = new ConcurrentHashMap<>();
   private final Map<String, Integer> channels = new ConcurrentHashMap<>();
   private final Map<String, InetSocketAddress> addresses = new ConcurrentHashMap<>();
   private final AtomicInteger nextChannel = new AtomicInteger(16384);

   TurnAllocation(DatagramSocket socket, InetSocketAddress server, String username, String password) {
      this.socket = socket;
      this.server = server;
      this.username = username;
      this.password = password;
   }

   InetSocketAddress server() {
      return this.server;
   }

   InetSocketAddress relayedAddress() {
      return this.relayed;
   }

   InetSocketAddress allocate() {
      try {
         int oldTimeout = this.socket.getSoTimeout();
         try {
            this.socket.setSoTimeout(4000);
            byte[] tx = Stun.newTransactionId();
            Turn.Credentials anon = new Turn.Credentials(this.username, this.password, null, null);
            byte[] resp = this.request(Turn.allocate(tx, anon, 600), tx, true);
            if (resp == null) {
               LOG.info("[turn] allocate 무응답 — 중계 없이 진행");
               return null;
            }
            Turn.Challenge ch = Turn.challenge(resp, 0, resp.length);
            if (ch == null) {
               LOG.warn("[turn] REALM/NONCE 를 못 받았다(code={}) — 중계 없이 진행", Turn.errorCode(resp, 0, resp.length));
               return null;
            }
            this.cred = new Turn.Credentials(this.username, this.password, ch.realm(), ch.nonce());
            byte[] tx2 = Stun.newTransactionId();
            byte[] ok = this.request(Turn.allocate(tx2, this.cred, 600), tx2, true);
            if (ok == null || !Turn.isSuccess(ok, 0)) {
               LOG.warn("[turn] allocate 실패(code={}) — 중계 없이 진행", ok != null ? Turn.errorCode(ok, 0, ok.length) : -1);
               return null;
            }
            this.relayed = Turn.relayedAddress(ok, 0, ok.length, tx2);
            if (this.relayed == null) {
               LOG.warn("[turn] relayed 주소를 못 읽었다 — 중계 없이 진행");
               return null;
            }
            LOG.info("[turn] allocation 확보 — relayed port={}", this.relayed.getPort());
            this.startRefresher();
            return this.relayed;
         } finally {
            this.socket.setSoTimeout(oldTimeout);
         }
      } catch (Exception e) {
         LOG.warn("[turn] allocate 중 예외: {} — 중계 없이 진행", e.toString());
         return null;
      }
   }

   boolean createPermission(InetSocketAddress peer) {
      if (this.cred == null || this.relayed == null) return false;
      try {
         int oldTimeout = this.socket.getSoTimeout();
         try {
            this.socket.setSoTimeout(4000);
            byte[] tx = Stun.newTransactionId();
            byte[] r = this.request(Turn.createPermission(tx, this.cred, peer), tx, false);
            boolean ok = r != null && Turn.isSuccess(r, 0);
            if (!ok) {
               int code = r != null ? Turn.errorCode(r, 0, r.length) : -1;
               if (code == 400) {
                  LOG.warn("[turn] 권한 등록 거부(400) — coturn 의 multiplex-peer 제약으로 보인다 (그 주소가 다른 allocation 에 이미 묶여 있음)");
               } else {
                  LOG.debug("[turn] createPermission 실패 code={}", code);
               }
            }
            return ok;
         } finally {
            this.socket.setSoTimeout(oldTimeout);
         }
      } catch (Exception e) {
         return false;
      }
   }

   int bindChannel(InetSocketAddress peer) {
      if (this.cred == null || this.relayed == null) return -1;
      Integer existing = this.channels.get(key(peer));
      if (existing != null) return existing;
      int channel = this.nextChannel.getAndIncrement();
      if (channel > 32767) {
         LOG.warn("[turn] 채널 번호가 고갈됐다");
         return -1;
      }
      this.addresses.put(key(peer), peer);
      try {
         int oldTimeout = this.socket.getSoTimeout();
         try {
            this.socket.setSoTimeout(4000);
            byte[] tx = Stun.newTransactionId();
            byte[] r = this.request(Turn.channelBind(tx, this.cred, channel, peer), tx, false);
            boolean ok = r != null && Turn.isSuccess(r, 0);
            LOG.info("[turn] channelBind {}", ok ? "성공 — 이후 4바이트 헤더로 중계" : "실패");
            if (!ok) return -1;
            this.channels.put(key(peer), channel);
            return channel;
         } finally {
            this.socket.setSoTimeout(oldTimeout);
         }
      } catch (Exception e) {
         return -1;
      }
   }

   int channelFor(InetSocketAddress peer) {
      Integer c = this.channels.get(key(peer));
      return c != null ? c : -1;
   }

   InetSocketAddress peerForChannel(int channel) {
      for(Map.Entry<String, Integer> e : this.channels.entrySet()) {
         if ((Integer)e.getValue() == channel) {
            return (InetSocketAddress)this.addresses.get(e.getKey());
         }
      }

      return null;
   }

   private static String key(InetSocketAddress a) {
      String var10000 = a.getAddress().getHostAddress();
      return var10000 + ":" + a.getPort();
   }

   /**
    * 할당을 반납한다 (Refresh lifetime=0). 원본은 반납하지 않아 할당이 수명(600초)까지 TURN 서버에 남는데,
    * 서버를 짧게 여러 번 재시작하면 쌓여서 486(Allocation Quota Reached)이 난다.
    * 소켓 읽기 루프가 이미 멈춘 뒤라 응답은 기다리지 않는다(nonce가 만료됐으면 무시되고 수명대로 사라진다).
    */
   void close() {
      if (this.closed.compareAndSet(false, true)) {
         Thread t = this.refresher;
         if (t != null) {
            t.interrupt();
         }

         Turn.Credentials c = this.cred;
         if (c != null && this.relayed != null) {
            try {
               byte[] msg = Turn.refresh(Stun.newTransactionId(), c, 0);
               this.socket.send(new DatagramPacket(msg, msg.length, this.server));
               LOG.info("[turn] allocation 반납");
            } catch (IOException e) {
               LOG.debug("[turn] allocation 반납 실패: {}", e.getMessage());
            }
         }
      }
   }

   void onResponse(byte[] buf, int off, int len) {
      CompletableFuture<byte[]> f = this.pending.remove(key(Stun.transactionId(buf, off)));
      if (f != null) {
         f.complete(Arrays.copyOfRange(buf, off, off + len));
      }

   }

   private byte[] request(byte[] msg, byte[] txId, boolean pump) throws IOException {
      CompletableFuture<byte[]> f = new CompletableFuture<>();
      this.pending.put(key(txId), f);
      try {
         this.socket.send(new DatagramPacket(msg, msg.length, this.server));
         if (pump) {
            // 소켓을 읽는 루프가 아직 없을 때(할당 단계) 직접 읽어서 응답을 받는다
            long deadline = System.currentTimeMillis() + 4000L;
            while (!f.isDone() && System.currentTimeMillis() < deadline) {
               byte[] buf = new byte[1500];
               DatagramPacket p = new DatagramPacket(buf, buf.length);
               try {
                  ((QuicIce.IceSocket) this.socket).receiveRaw(p);
               } catch (SocketTimeoutException e) {
                  break;
               }
               if (p.getLength() >= 20) this.onResponse(p.getData(), p.getOffset(), p.getLength());
            }
         }
         byte[] now = f.getNow(null);
         return now != null ? now : f.get(4000L, TimeUnit.MILLISECONDS);
      } catch (Exception e) {
         return null;
      } finally {
         this.pending.remove(key(txId));
      }
   }

   private static String key(byte[] txId) {
      return HexFormat.of().formatHex(txId);
   }

   private void startRefresher() {
      Thread t = new Thread(() -> {
         while(!this.closed.get()) {
            try {
               Thread.sleep(300000L);
            } catch (InterruptedException var4) {
               return;
            }

            if (this.closed.get()) {
               return;
            }

            Turn.Credentials c = this.cred;
            if (c != null) {
               try {
                  byte[] msg = Turn.refresh(Stun.newTransactionId(), c, 600);
                  this.socket.send(new DatagramPacket(msg, msg.length, this.server));
               } catch (IOException e) {
                  LOG.debug("[turn] refresh 전송 실패: {}", e.getMessage());
               }
            }
         }

      }, "quic-turn-refresh");
      t.setDaemon(true);
      this.refresher = t;
      t.start();
   }

   static InetSocketAddress parseUrl(String url) {
      String s = url.startsWith("turn:") ? url.substring(5) : url;
      int q = s.indexOf(63);
      if (q >= 0) {
         s = s.substring(0, q);
      }

      int colon = s.lastIndexOf(58);
      if (colon <= 0) {
         return null;
      } else {
         try {
            return new InetSocketAddress(s.substring(0, colon), Integer.parseInt(s.substring(colon + 1)));
         } catch (Exception var5) {
            return null;
         }
      }
   }

   static boolean sameAddress(InetSocketAddress a, InetSocketAddress b) {
      return a != null && b != null && a.getPort() == b.getPort() && Arrays.equals(a.getAddress().getAddress(), b.getAddress().getAddress());
   }
}
