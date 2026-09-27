package dev.magyul.instantp2p.common.quic;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class Turn {
   private static final int HEADER = 20;
   private static final int MAGIC = 554869826;
   static final int ALLOCATE = 3;
   static final int REFRESH = 4;
   static final int CREATE_PERMISSION = 8;
   static final int CHANNEL_BIND = 9;
   private static final int CLASS_REQUEST = 0;
   private static final int CLASS_SUCCESS = 256;
   private static final int CLASS_ERROR = 272;
   private static final int ATTR_USERNAME = 6;
   private static final int ATTR_MESSAGE_INTEGRITY = 8;
   private static final int ATTR_ERROR_CODE = 9;
   private static final int ATTR_REALM = 20;
   private static final int ATTR_NONCE = 21;
   private static final int ATTR_XOR_PEER_ADDRESS = 18;
   private static final int ATTR_XOR_RELAYED_ADDRESS = 22;
   private static final int ATTR_REQUESTED_TRANSPORT = 25;
   private static final int ATTR_LIFETIME = 13;
   private static final int ATTR_CHANNEL_NUMBER = 12;
   private static final int DATA_INDICATION = 23;
   private static final int ATTR_DATA = 19;
   static final int CHANNEL_MIN = 16384;
   static final int CHANNEL_MAX = 32767;

   private Turn() {
   }

   static byte[] allocate(byte[] txId, Credentials cred, int lifetimeSec) {
      ByteArrayOutputStream attrs = new ByteArrayOutputStream();
      attrs.writeBytes(attr(25, new byte[]{17, 0, 0, 0}));
      attrs.writeBytes(attr(13, int32(lifetimeSec)));
      return finish(3, txId, attrs.toByteArray(), cred);
   }

   static byte[] refresh(byte[] txId, Credentials cred, int lifetimeSec) {
      return finish(4, txId, attr(13, int32(lifetimeSec)), cred);
   }

   static byte[] createPermission(byte[] txId, Credentials cred, InetSocketAddress peer) {
      return finish(8, txId, xorAddr(18, peer, txId), cred);
   }

   static byte[] channelBind(byte[] txId, Credentials cred, int channel, InetSocketAddress peer) {
      ByteArrayOutputStream attrs = new ByteArrayOutputStream();
      attrs.writeBytes(attr(12, new byte[]{(byte)(channel >> 8), (byte)channel, 0, 0}));
      attrs.writeBytes(xorAddr(18, peer, txId));
      return finish(9, txId, attrs.toByteArray(), cred);
   }

   static int method(byte[] b, int off) {
      return messageType(b, off) & 4095;
   }

   static boolean isSuccess(byte[] b, int off) {
      return (messageType(b, off) & 272) == 256;
   }

   static boolean isError(byte[] b, int off) {
      return (messageType(b, off) & 272) == 272;
   }

   private static int messageType(byte[] b, int off) {
      return (b[off] & 255) << 8 | b[off + 1] & 255;
   }

   static Challenge challenge(byte[] b, int off, int len) {
      String realm = text(find(b, off, len, 20), b);
      String nonce = text(find(b, off, len, 21), b);
      return realm != null && nonce != null ? new Challenge(realm, nonce) : null;
   }

   static int errorCode(byte[] b, int off, int len) {
      int[] a = find(b, off, len, 9);
      return a != null && a[1] >= 4 ? (b[a[0] + 2] & 7) * 100 + (b[a[0] + 3] & 255) : -1;
   }

   static InetSocketAddress relayedAddress(byte[] b, int off, int len, byte[] txId) {
      int[] a = find(b, off, len, 22);
      return a != null ? parseXorAddr(b, a[0], a[1], txId) : null;
   }

   static boolean isDataIndication(byte[] b, int off, int len) {
      return len >= 20 && messageType(b, off) == 23;
   }

   static InetSocketAddress dataPeer(byte[] b, int off, int len, byte[] txId) {
      int[] a = find(b, off, len, 18);
      return a != null ? parseXorAddr(b, a[0], a[1], txId) : null;
   }

   static int[] dataPayload(byte[] b, int off, int len) {
      return find(b, off, len, 19);
   }

   static boolean isChannelData(byte[] b, int off, int len) {
      return len >= 4 && (b[off] & 192) == 64;
   }

   static int channelOf(byte[] b, int off) {
      return (b[off] & 255) << 8 | b[off + 1] & 255;
   }

   static int channelDataLength(byte[] b, int off) {
      return (b[off + 2] & 255) << 8 | b[off + 3] & 255;
   }

   static byte[] wrapChannelData(int channel, byte[] payload, int off, int len) {
      byte[] out = new byte[4 + len];
      out[0] = (byte)(channel >> 8);
      out[1] = (byte)channel;
      out[2] = (byte)(len >> 8);
      out[3] = (byte)len;
      System.arraycopy(payload, off, out, 4, len);
      return out;
   }

   private static byte[] finish(int type, byte[] txId, byte[] attrs, Credentials cred) {
      if (!cred.signed()) {
         return message(type, txId, attrs);
      } else {
         ByteArrayOutputStream withAuth = new ByteArrayOutputStream();
         withAuth.writeBytes(attrs);
         withAuth.writeBytes(attr(6, cred.username().getBytes(StandardCharsets.UTF_8)));
         withAuth.writeBytes(attr(20, cred.realm().getBytes(StandardCharsets.UTF_8)));
         withAuth.writeBytes(attr(21, cred.nonce().getBytes(StandardCharsets.UTF_8)));
         byte[] body = withAuth.toByteArray();
         byte[] partial = message(type, txId, body, body.length + 4 + 20);
         byte[] mac = hmacSha1(longTermKey(cred), partial);
         ByteArrayOutputStream full = new ByteArrayOutputStream();
         full.writeBytes(body);
         full.writeBytes(attr(8, mac));
         return message(type, txId, full.toByteArray());
      }
   }

   private static byte[] longTermKey(Credentials c) {
      try {
         String var10000 = c.username();
         String s = var10000 + ":" + c.realm() + ":" + c.password();
         return MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
      } catch (Exception e) {
         throw new IllegalStateException(e);
      }
   }

   private static byte[] hmacSha1(byte[] key, byte[] data) {
      try {
         Mac mac = Mac.getInstance("HmacSHA1");
         mac.init(new SecretKeySpec(key, "HmacSHA1"));
         return mac.doFinal(data);
      } catch (Exception e) {
         throw new IllegalStateException(e);
      }
   }

   private static byte[] message(int type, byte[] txId, byte[] attrs) {
      return message(type, txId, attrs, attrs.length);
   }

   private static byte[] message(int type, byte[] txId, byte[] attrs, int declaredLength) {
      byte[] m = new byte[20 + attrs.length];
      m[0] = (byte)(type >> 8);
      m[1] = (byte)type;
      m[2] = (byte)(declaredLength >> 8);
      m[3] = (byte)declaredLength;
      m[4] = 33;
      m[5] = 18;
      m[6] = -92;
      m[7] = 66;
      System.arraycopy(txId, 0, m, 8, 12);
      System.arraycopy(attrs, 0, m, 20, attrs.length);
      return m;
   }

   private static byte[] attr(int type, byte[] value) {
      int pad = (4 - value.length % 4) % 4;
      byte[] out = new byte[4 + value.length + pad];
      out[0] = (byte)(type >> 8);
      out[1] = (byte)type;
      out[2] = (byte)(value.length >> 8);
      out[3] = (byte)value.length;
      System.arraycopy(value, 0, out, 4, value.length);
      return out;
   }

   private static byte[] int32(int v) {
      return new byte[]{(byte)(v >>> 24), (byte)(v >>> 16), (byte)(v >>> 8), (byte)v};
   }

   private static byte[] xorAddr(int type, InetSocketAddress addr, byte[] txId) {
      byte[] ip = addr.getAddress().getAddress();
      if (ip.length != 4) {
         throw new IllegalArgumentException("IPv4 만 지원");
      } else {
         byte[] v = new byte[8];
         v[0] = 0;
         v[1] = 1;
         int xport = addr.getPort() ^ 8466;
         v[2] = (byte)(xport >> 8);
         v[3] = (byte)xport;

         for(int i = 0; i < 4; ++i) {
            v[4 + i] = (byte)(ip[i] ^ (byte)(554869826 >>> 24 - 8 * i));
         }

         return attr(type, v);
      }
   }

   private static InetSocketAddress parseXorAddr(byte[] b, int off, int len, byte[] txId) {
      if (len < 8) {
         return null;
      } else {
         int port = ((b[off + 2] & 255) << 8 | b[off + 3] & 255) ^ 8466;
         byte[] ip = new byte[4];

         for(int i = 0; i < 4; ++i) {
            ip[i] = (byte)(b[off + 4 + i] ^ (byte)(554869826 >>> 24 - 8 * i));
         }

         try {
            return new InetSocketAddress(InetAddress.getByAddress(ip), port);
         } catch (Exception var7) {
            return null;
         }
      }
   }

   private static int[] find(byte[] b, int off, int len, int wanted) {
      int end = off + len;

      int alen;
      int body;
      for(int p = off + 20; p + 4 <= end; p = body + (alen + 3 & -4)) {
         int type = (b[p] & 255) << 8 | b[p + 1] & 255;
         alen = (b[p + 2] & 255) << 8 | b[p + 3] & 255;
         body = p + 4;
         if (body + alen > end) {
            return null;
         }

         if (type == wanted) {
            return new int[]{body, alen};
         }
      }

      return null;
   }

   private static String text(int[] a, byte[] b) {
      return a != null ? new String(Arrays.copyOfRange(b, a[0], a[0] + a[1]), StandardCharsets.UTF_8) : null;
   }

   static record Challenge(String realm, String nonce) {
   }

   static record Credentials(String username, String password, String realm, String nonce) {
      boolean signed() {
         return this.realm != null && this.nonce != null;
      }
   }
}
