package dev.magyul.instantp2p.common.quic;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public final class QuicCert {
   private static final int VALID_DAYS = 2;
   private static final byte[] OID_CN = new byte[]{6, 3, 85, 4, 3};
   private static final byte[] OID_ECDSA_SHA256 = new byte[]{6, 8, 42, -122, 72, -50, 61, 4, 3, 2};
   static final String ALIAS = "instant-p2p";
   static final char[] PASSWORD = "instant-p2p".toCharArray();

   private QuicCert() {
   }

   public static Identity generate() throws Exception {
      KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
      gen.initialize(new ECGenParameterSpec("secp256r1"));
      KeyPair kp = gen.generateKeyPair();
      X509Certificate cert = selfSigned(kp);
      KeyStore ks = KeyStore.getInstance("PKCS12");
      ks.load((InputStream)null, (char[])null);
      ks.setKeyEntry("instant-p2p", kp.getPrivate(), PASSWORD, new Certificate[]{cert});
      return new Identity(ks, fingerprint(cert));
   }

   public static String fingerprint(X509Certificate cert) throws Exception {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
      StringBuilder sb = new StringBuilder(digest.length * 2);

      for(byte b : digest) {
         sb.append(String.format("%02X", b));
      }

      return sb.toString();
   }

   private static X509Certificate selfSigned(KeyPair kp) throws Exception {
      byte[] algId = seq(OID_ECDSA_SHA256);
      byte[] name = seq(set(seq(OID_CN, utf8("instant-p2p"))));
      ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
      byte[] tbs = seq(tlv(160, integer(BigInteger.TWO)), integer((new BigInteger(64, new SecureRandom())).add(BigInteger.ONE)), algId, name, seq(utcTime(now.minusHours(1L)), utcTime(now.plusDays(2L))), name, kp.getPublic().getEncoded());
      Signature signer = Signature.getInstance("SHA256withECDSA");
      signer.initSign(kp.getPrivate());
      signer.update(tbs);
      byte[] der = seq(tbs, algId, bitString(signer.sign()));
      return (X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
   }

   private static byte[] len(int n) {
      if (n < 128) {
         return new byte[]{(byte)n};
      } else {
         return n < 256 ? new byte[]{-127, (byte)n} : new byte[]{-126, (byte)(n >> 8), (byte)n};
      }
   }

   private static byte[] tlv(int tag, byte[]... parts) {
      ByteArrayOutputStream body = new ByteArrayOutputStream();

      for(byte[] p : parts) {
         body.writeBytes(p);
      }

      byte[] b = body.toByteArray();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      out.write(tag);
      out.writeBytes(len(b.length));
      out.writeBytes(b);
      return out.toByteArray();
   }

   private static byte[] seq(byte[]... parts) {
      return tlv(48, parts);
   }

   private static byte[] set(byte[]... parts) {
      return tlv(49, parts);
   }

   private static byte[] integer(BigInteger v) {
      return tlv(2, v.toByteArray());
   }

   private static byte[] utf8(String s) {
      return tlv(12, s.getBytes(StandardCharsets.UTF_8));
   }

   private static byte[] utcTime(ZonedDateTime t) {
      String s = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").format(t.withZoneSameInstant(ZoneOffset.UTC));
      return tlv(23, s.getBytes(StandardCharsets.US_ASCII));
   }

   private static byte[] bitString(byte[] raw) {
      byte[] padded = new byte[raw.length + 1];
      System.arraycopy(raw, 0, padded, 1, raw.length);
      return tlv(3, padded);
   }

   public static record Identity(KeyStore keyStore, String fingerprint) {
   }
}
