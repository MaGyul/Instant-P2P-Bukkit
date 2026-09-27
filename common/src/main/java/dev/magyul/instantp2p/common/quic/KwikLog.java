package dev.magyul.instantp2p.common.quic;

import java.nio.ByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.kwik.core.log.BaseLogger;

final class KwikLog extends BaseLogger {
   private static final Logger LOG = LoggerFactory.getLogger("quic-kwik");

   private static boolean isOwnShutdown(String message) {
      return message.contains("Socket closed") || message.contains("ClosedChannelException") || message.contains("AsynchronousCloseException");
   }

   private KwikLog() {
      this.logWarning(true);
      this.logInfo(false);
      this.logDebug(false);
      this.logPackets(false);
      this.logRaw(false);
      this.logDecrypted(false);
      this.logSecrets(false);
   }

   static KwikLog quiet() {
      return Boolean.getBoolean("kfcudp.quic.verbose") ? verbose() : new KwikLog();
   }

   static KwikLog verbose() {
      KwikLog l = new KwikLog();
      l.logInfo(true);
      l.logFlowControl(true);
      l.logCongestionControl(true);
      l.logRecovery(true);
      l.logStats(true);
      return l;
   }

   protected void log(String message) {
      if (isOwnShutdown(message)) {
         LOG.debug("[kwik] {}", message.stripTrailing());
      } else {
         LOG.warn("[kwik] {}", message.stripTrailing());
      }
   }

   protected void log(String message, Throwable ex) {
      if (!isOwnShutdown(message) && (ex == null || !isOwnShutdown(String.valueOf(ex)))) {
         LOG.warn("[kwik] {}", message.stripTrailing(), ex);
      } else {
         LOG.debug("[kwik] {}", message.stripTrailing());
      }
   }

   protected void logWithHexDump(String message, byte[] data, int length) {
      LOG.warn("[kwik] {} ({}B)", message.stripTrailing(), length);
   }

   protected void logWithHexDump(String message, ByteBuffer data, int offset, int length) {
      LOG.warn("[kwik] {} ({}B)", message.stripTrailing(), length);
   }
}
