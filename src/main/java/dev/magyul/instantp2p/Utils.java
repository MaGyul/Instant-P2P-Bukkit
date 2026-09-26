package dev.magyul.instantp2p;

import com.google.common.net.InetAddresses;
import dev.magyul.instantp2p.i18n.I18n;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.UUID;

public class Utils {
    private static final Random RANDOM = new Random();
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    public static void sendAdministratorMessage(String translationKey) {
        InstantP2pBukkit.LOGGER.info(I18n.translatableStr(translationKey));
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("instantp2p.notify.host")) {
                player.sendMessage(I18n.translatable(translationKey));
            }
        }
    }

    public static String encodePlayerHashes(Collection<UUID> players, String roomCode) {
        return String.join(",", players.stream().map(player -> {
            try {
                byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                        .digest((roomCode + ":" + player).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(d, 8));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e); // SHA-256은 모든 JVM에 필수로 들어 있다
            }
        }).toList());
    }

    public static String generateCode() {
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    public static InetAddress toPeerAddress(String peer) {
        String s = (peer.startsWith("[") && peer.endsWith("]"))
                ? peer.substring(1, peer.length() - 1) : peer;
        if (InetAddresses.isInetAddress(s)) {
            return InetAddresses.forString(s);
        }
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            byte[] a = new byte[16];
            a[0] = (byte) 0xfd;
            System.arraycopy(h, 0, a, 1, 15);
            return InetAddress.getByAddress(a);   // byte[] 버전은 DNS 조회 안 함
        } catch (GeneralSecurityException | UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

}
