package dev.magyul.instantp2p.common.signaling;

import java.io.InputStream;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 기기 식별값 — 가맹점 서버 전용. 가맹점 수정판(F3c 기준)의 {@code HardwareId}와 같은 값을 만든다.
 * <p>
 * 가맹점 운영자 설명: 범죄가 생겼을 때 후속 대응(기기 단위 차단·수사 협조)을 위한 값이고, 약관(3. 수집하는 정보)에 적혀 있다.
 * 원래 값은 보내지 않고 SHA-256 해시만 보낸다. <b>가맹점 서버를 쓸 때만</b>(약관 동의 후) 붙는다 — 공식 서버에는 보내지 않는다.
 * <ul>
 *   <li>{@code hwid} — sha256(os.name|os.arch|CPU 수|대표 MAC)</li>
 *   <li>{@code hw2} — 요소별 해시(물리 MAC 최대 4개, OS 설치 ID+호스트명, 시스템 볼륨 번호+호스트명), 최대 8개를 쉼표로</li>
 * </ul>
 * 외부 명령(reg, vol, ioreg)을 실행하므로 처음 한 번만 계산해 캐시한다. 서버 스레드에서 부르지 말 것.
 */
public final class HardwareId {

    /** 가상 어댑터(도커·VPN·가상 머신 등) — 재부팅·설치마다 바뀌므로 뺀다 (가맹점 수정판과 같은 목록) */
    private static final String[] VIRTUAL_PREFIXES = {"docker", "veth", "br-", "virbr", "vbox", "vmnet", "vethernet",
            "tun", "tap", "utun", "wg", "zt", "tailscale", "ham", "llw", "awdl", "anpi", "bridge", "lo"};

    private static volatile String cached;
    private static volatile String cachedComponents;

    private HardwareId() {}

    /** 방 열기·상태 보고 URL에 붙일 쿼리 ({@code &hwid=…&hw2=…}) — 가맹점 서버가 아니면 빈 문자열 */
    public static String query() {
        if (P2PConfig.server() != SignalingServer.FRANCHISE) return "";
        String c = components();
        return "&hwid=" + get() + (c.isEmpty() ? "" : "&hw2=" + c);
    }

    static synchronized String get() {
        if (cached != null) return cached;
        String mac = primaryMacAddress();
        if (mac == null) {
            cached = "";
            return cached;
        }
        String raw = System.getProperty("os.name", "") + "|" + System.getProperty("os.arch", "") + "|"
                + Runtime.getRuntime().availableProcessors() + "|" + mac;
        cached = sha256Hex(raw);
        return cached;
    }

    static synchronized String components() {
        if (cachedComponents != null) return cachedComponents;
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String mac : physicalMacs()) out.add(sha256Hex("mac|" + mac));
        String host = hostName();
        String installId = installId();
        if (installId != null && !installId.isBlank()) {
            out.add(sha256Hex("os|" + installId.trim().toLowerCase(Locale.ROOT) + "|" + host));
        }
        String vol = volumeSerial();
        if (vol != null && !vol.isBlank()) {
            out.add(sha256Hex("vol|" + vol.trim().toUpperCase(Locale.ROOT) + "|" + host));
        }
        List<String> list = new ArrayList<>(out);
        cachedComponents = String.join(",", list.subList(0, Math.min(8, list.size())));
        return cachedComponents;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String hostName() {
        String n = System.getenv("COMPUTERNAME");
        if (n == null || n.isBlank()) n = System.getenv("HOSTNAME");
        if (n == null || n.isBlank()) {
            // 가맹점 수정판과 같은 값이 나오게 같은 순서로 — 리눅스는 HOSTNAME이 보통 JVM에 안 넘어온다.
            // 로컬 이름 조회가 느릴 수 있지만 한 번만 하고(캐시) 서버 스레드 밖에서만 부른다.
            try {
                n = java.net.InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                n = "";
            }
        }
        return n.trim().toLowerCase(Locale.ROOT);
    }

    /** OS 설치 ID — Windows MachineGuid, Linux machine-id, macOS IOPlatformUUID */
    private static String installId() {
        try {
            if (isWindows()) {
                String o = run("reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid");
                Matcher m = Pattern.compile("MachineGuid\\s+REG_SZ\\s+([0-9a-fA-F-]{36})").matcher(o);
                return m.find() ? m.group(1) : null;
            }
            for (String f : new String[]{"/etc/machine-id", "/var/lib/dbus/machine-id"}) {
                Path p = Path.of(f);
                if (Files.isReadable(p)) return Files.readString(p).trim();
            }
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
                String o = run("ioreg", "-rd1", "-c", "IOPlatformExpertDevice");
                Matcher m = Pattern.compile("\"IOPlatformUUID\" = \"([^\"]+)\"").matcher(o);
                return m.find() ? m.group(1) : null;
            }
        } catch (Exception ignored) {
            // 못 읽으면 이 요소만 뺀다
        }
        return null;
    }

    /** 시스템 볼륨 일련번호 (Windows만) */
    private static String volumeSerial() {
        if (!isWindows()) return null;
        try {
            String o = run("cmd", "/c", "vol", "C:");
            Matcher m = Pattern.compile("([0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})").matcher(o);
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] buf;
        try (InputStream in = p.getInputStream()) {
            buf = in.readNBytes(8192);
        }
        if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly();
        return new String(buf, Charset.defaultCharset());
    }

    private static String primaryMacAddress() {
        TreeMap<String, String> byName = physicalMacMap();
        return byName.isEmpty() ? null : byName.firstEntry().getValue();
    }

    private static List<String> physicalMacs() {
        List<String> l = new ArrayList<>(physicalMacMap().values());
        return l.subList(0, Math.min(4, l.size()));
    }

    /** 이름순 물리 어댑터 MAC (루프백·가상·점대점·로컬 관리 주소 제외) */
    private static TreeMap<String, String> physicalMacMap() {
        TreeMap<String, String> byName = new TreeMap<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (ni.isLoopback() || ni.isVirtual() || ni.isPointToPoint()) continue;
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase(Locale.ROOT);
                boolean virtual = false;
                for (String prefix : VIRTUAL_PREFIXES) {
                    if (name.startsWith(prefix)) {
                        virtual = true;
                        break;
                    }
                }
                if (virtual) continue;
                byte[] mac = ni.getHardwareAddress();
                if (mac == null || mac.length != 6 || (mac[0] & 2) != 0) continue;
                boolean allZero = true;
                for (byte b : mac) {
                    if (b != 0) {
                        allZero = false;
                        break;
                    }
                }
                if (!allZero) byName.put(name, HexFormat.of().formatHex(mac));
            }
        } catch (SocketException ignored) {
            // 어댑터를 못 읽으면 MAC 요소 없이
        }
        return byName;
    }

    private static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
