package dev.magyul.instantp2p.common.quic;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.Arrays;

final class Stun {
   private static final int HEADER = 20;
   private static final int MAGIC = 554869826;
   private static final int BINDING_REQUEST = 1;
   private static final int BINDING_SUCCESS = 257;
   private static final int XOR_MAPPED_ADDRESS = 32;
   private static final SecureRandom RANDOM = new SecureRandom();
   private static final int QUIC_INITIAL_SIZE = 1200;

   private Stun() {
   }

   static boolean looksLikeStun(byte[] buf, int off, int len) {
      return len >= 20 && (buf[off] & 192) == 0;
   }

   static int messageType(byte[] buf, int off) {
      return (buf[off] & 255) << 8 | buf[off + 1] & 255;
   }

   static boolean isRequest(byte[] buf, int off) {
      return messageType(buf, off) == 1;
   }

   static boolean isSuccess(byte[] buf, int off) {
      return messageType(buf, off) == 257;
   }

   static byte[] newTransactionId() {
      byte[] id = new byte[12];
      RANDOM.nextBytes(id);
      return id;
   }

   static byte[] transactionId(byte[] buf, int off) {
      return Arrays.copyOfRange(buf, off + 8, off + 20);
   }

   static byte[] bindingRequest(byte[] txId) {
      byte[] m = new byte[20];
      m[0] = 0;
      m[1] = 1;
      m[2] = 0;
      m[3] = 0;
      writeInt(m, 4, 554869826);
      System.arraycopy(txId, 0, m, 8, 12);
      return m;
   }

   static byte[] paddedBindingRequest(byte[] txId) {
      int payload = 1176;
      byte[] m = new byte[24 + payload];
      m[0] = 0;
      m[1] = 1;
      int len = 4 + payload;
      m[2] = (byte)(len >> 8);
      m[3] = (byte)len;
      writeInt(m, 4, 554869826);
      System.arraycopy(txId, 0, m, 8, 12);
      m[20] = -64;
      m[21] = 0;
      m[22] = (byte)(payload >> 8);
      m[23] = (byte)payload;
      return m;
   }

   static byte[] bindingSuccess(byte[] txId, InetSocketAddress reflexive) {
      byte[] addr = reflexive.getAddress().getAddress();
      if (addr.length != 4) {
         return null;
      } else {
         int attrLen = 8;
         byte[] m = new byte[24 + attrLen];
         m[0] = 1;
         m[1] = 1;
         m[2] = (byte)(4 + attrLen >> 8);
         m[3] = (byte)(4 + attrLen);
         writeInt(m, 4, 554869826);
         System.arraycopy(txId, 0, m, 8, 12);
         int p = 20;
         m[p] = 0;
         m[p + 1] = 32;
         m[p + 2] = 0;
         m[p + 3] = (byte)attrLen;
         m[p + 4] = 0;
         m[p + 5] = 1;
         int xport = reflexive.getPort() ^ 8466;
         m[p + 6] = (byte)(xport >> 8);
         m[p + 7] = (byte)xport;

         for(int i = 0; i < 4; ++i) {
            m[p + 8 + i] = (byte)(addr[i] ^ (byte)(554869826 >>> 24 - 8 * i));
         }

         return m;
      }
   }

   static InetSocketAddress mappedAddress(byte[] buf, int off, int len) {
      int end = off + len;

      int alen;
      int body;
      for(int p = off + 20; p + 4 <= end; p = body + (alen + 3 & -4)) {
         int type = (buf[p] & 255) << 8 | buf[p + 1] & 255;
         alen = (buf[p + 2] & 255) << 8 | buf[p + 3] & 255;
         body = p + 4;
         if (type == 32 && alen >= 8 && body + 8 <= end) {
            int port = ((buf[body + 2] & 255) << 8 | buf[body + 3] & 255) ^ 8466;
            byte[] addr = new byte[4];

            for(int i = 0; i < 4; ++i) {
               addr[i] = (byte)(buf[body + 4 + i] ^ (byte)(554869826 >>> 24 - 8 * i));
            }

            try {
               return new InetSocketAddress(InetAddress.getByAddress(addr), port);
            } catch (Exception var11) {
               return null;
            }
         }
      }

      return null;
   }

   static DatagramPacket packet(byte[] msg, InetSocketAddress to) {
      return new DatagramPacket(msg, msg.length, to);
   }

   private static void writeInt(byte[] b, int off, int v) {
      b[off] = (byte)(v >>> 24);
      b[off + 1] = (byte)(v >>> 16);
      b[off + 2] = (byte)(v >>> 8);
      b[off + 3] = (byte)v;
   }
}
