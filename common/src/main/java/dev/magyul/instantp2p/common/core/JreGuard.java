package dev.magyul.instantp2p.common.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 서버 JVM의 Java 취약점(CVE-2026-91403) 점검 — 원본 1.5.1 / 가맹점 F4 {@code JreGuard}와 같은 판정 방식.
 * <p>
 * 이 취약점은 eMudhra의 손상된 중간 인증서 8개를 JSSE(TLS)가 믿는 문제다. 그 인증서들은 {@code emSign ECC Root CA - G3}와
 * {@code AAA Certificate Services}(Sectigo/Comodo) 루트에 묶여 있다. 배포판마다 고친 방식·버전 번호가 달라 버전 번호로는
 * 판정이 틀리므로 <b>이 JVM이 실제로 쓰는 신뢰 저장소</b>를 본다:
 * <ol>
 *   <li>두 루트가 신뢰 목록에 없다 → 패치됨(또는 영향 없음).</li>
 *   <li>루트가 있어도 {@code lib/security/blocked.certs}가 갱신판(해시 {@link #BLOCKLIST_PATCHED_MIN}줄 이상)이다 → 패치됨.</li>
 *   <li>둘 다 아니다 → 패치 안 됨(UNPATCHED). 시그널링 서버가 {@code java-old}로 거부할 수 있고, 서버판은 방 열기를 막는다.</li>
 *   <li>신뢰 저장소를 못 읽으면 예전 버전 표({@link #classify})로 판정한다.</li>
 * </ol>
 * <b>우회(문제 루트 4개를 기본 TLS 신뢰 목록에서 빼기)는 운영자가 명령으로 고를 때만</b> 적용한다({@link #fixNow}). 이 조치는
 * 서버 프로세스 전체의 기본 {@link SSLContext}에 영향을 주므로(다른 플러그인 포함) 기본으로 켜지지 않는다.
 * 패치 안 된 JVM에서는 시작할 때 동작이 그대로인 교체형 TrustManager만 깔아 두어, 나중에 고르면 이미 만들어진 HTTP 클라이언트까지
 * 그 자리에서 고쳐지게 한다. 이 클래스는 JDK와 slf4j만 쓴다(플랫폼 독립).
 */
public final class JreGuard {
    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-jre");

    public enum Level { OK, WARN, VULNERABLE }

    public enum Patch { ROOTS_ABSENT, BLOCKLIST, MITIGATED, UNPATCHED, UNKNOWN }

    /** 손상된 중간 인증서가 묶인 루트 — 이게 신뢰 목록에 있으면 영향권. */
    static final String[] AFFECTED_ROOTS = {
            "CN=emSign ECC Root CA - G3",
            "CN=AAA Certificate Services,O=Comodo CA Limited",
    };
    /** 우회할 때 빼는 루트 — 패치된 OpenJDK가 cacerts에서 지운 4개(JDK-8395690)와 같다. */
    static final String[] REMOVE_ROOTS = {
            "CN=emSign ECC Root CA - G3",
            "CN=emSign Root CA - G1",
            "CN=emSign Root CA - G2",
            "CN=AAA Certificate Services,O=Comodo CA Limited",
    };
    /** 차단 목록 갱신판으로 볼 최소 해시 줄 수(갱신 전 39 → 갱신 후 87). */
    static final int BLOCKLIST_PATCHED_MIN = 70;

    /** 신뢰 저장소를 못 읽을 때만 쓰는 예전 버전 표: 기능 줄 → 취약한 마지막 버전(이 버전 <b>이하</b>가 취약). */
    private static final Map<Integer, int[]> AFFECTED = new TreeMap<>(Map.of(
            8, new int[]{8, 0, 511},
            11, new int[]{11, 0, 33},
            17, new int[]{17, 0, 21},
            21, new int[]{21, 0, 13},
            25, new int[]{25, 0, 5},
            27, new int[]{27, 0, 1}));
    private static final int WARN_ONLY_FEATURE = 26;
    private static final int NEWEST_CHECKED_FEATURE = 27;

    public static final String DOWNLOAD_URL = "https://adoptium.net/temurin/releases/";

    private static volatile boolean ignoreThisRun;
    private static volatile SwitchTrustManager switchTm;
    private static volatile Path autofixMarker;
    private static volatile Level cached;
    private static volatile Patch patch;

    private JreGuard() {}

    /**
     * 시작할 때 한 번 — 다른 HTTP/TLS 클라이언트가 만들어지기 전에 부른다. 판정만 한다(패치 안 된 JVM이면 동작이 그대로인
     * 교체형 TrustManager를 깔아 둔다). 예전에 {@code /p2p java fix always}를 골랐으면 바로 적용한다.
     *
     * @param dataFolder {@code fix always} 표식({@code jre-autofix})을 두는 폴더
     */
    public static synchronized void init(Path dataFolder) {
        if (cached != null) return;
        autofixMarker = dataFolder != null ? dataFolder.resolve("jre-autofix") : null;
        Patch p;
        try {
            p = inspect();
        } catch (Throwable t) {
            p = Patch.UNKNOWN;
        }
        if (p == Patch.UNPATCHED) {
            installSwitch();
            Path marker = autofixMarker;
            if (marker != null && Files.exists(marker) && applyFix()) p = Patch.MITIGATED;
        }
        patch = p;
        cached = switch (p) {
            case ROOTS_ABSENT, BLOCKLIST, MITIGATED -> Level.OK;
            case UNPATCHED -> Level.VULNERABLE;
            case UNKNOWN -> {
                try {
                    yield classify(currentVersion());
                } catch (RuntimeException e) {
                    yield Level.OK;
                }
            }
        };
        LOG.info("[jre] {} — CVE-2026-91403 판정: {} → {}", currentText(), p, cached);
        if (cached == Level.VULNERABLE) {
            LOG.warn("[jre] 이 Java는 CVE-2026-91403 패치가 안 됐습니다 — 방 열기를 막습니다. Java를 업데이트하거나 /p2p java 로 선택하세요 ({})",
                    DOWNLOAD_URL);
        }
    }

    /**
     * 우회를 지금 적용한다. {@code always}면 다음 시작부터도 자동으로 적용한다(표식 파일).
     *
     * @return 적용됐으면 true (이미 패치된 Java면 할 일이 없어 true)
     */
    public static synchronized boolean fixNow(boolean always) {
        if (cached == null) return false;
        if (patch == Patch.MITIGATED || cached == Level.OK) return true;
        if (switchTm == null) installSwitch();
        boolean ok = applyFix();
        if (ok) {
            patch = Patch.MITIGATED;
            cached = Level.OK;
            if (always && autofixMarker != null) {
                try {
                    Files.createDirectories(autofixMarker.getParent());
                    Files.writeString(autofixMarker, "1\n");
                } catch (IOException e) {
                    LOG.warn("[jre] jre-autofix 표식을 쓰지 못했다: {}", e.toString());
                }
            }
        }
        return ok;
    }

    /** 운영자가 위험을 알고 이번 실행만 방 열기를 허용한다. */
    public static void ignoreThisRun() {
        ignoreThisRun = true;
    }

    /** 저장된 {@code fix always}를 지운다. */
    public static void clearAutofix() {
        Path m = autofixMarker;
        if (m == null) return;
        try {
            Files.deleteIfExists(m);
        } catch (IOException ignored) {
        }
    }

    public static boolean autofixSaved() {
        Path m = autofixMarker;
        return m != null && Files.exists(m);
    }

    public static Patch patch() {
        return patch;
    }

    public static Level level() {
        Level l = cached;
        return l != null ? l : Level.OK;
    }

    /** 방 열기를 지금 거절해야 하는지 — 취약하고, 우회도 무시 선택도 안 했을 때. */
    public static boolean blocksP2p() {
        return level() == Level.VULNERABLE && !ignoreThisRun;
    }

    public static boolean isIgnoringThisRun() {
        return ignoreThisRun;
    }

    /** 표시용 현재 버전 (예: "21.0.12+7"). */
    public static String currentText() {
        try {
            return Runtime.version().toString();
        } catch (RuntimeException e) {
            return System.getProperty("java.version", "?");
        }
    }

    /** 표시용 "이 버전보다 새 것이 필요" 기준. */
    public static String affectedText() {
        List<Integer> v = currentVersion();
        int[] a = AFFECTED.get(v.isEmpty() ? 0 : v.get(0));
        if (a == null) a = AFFECTED.get(25);
        return a[0] == 8 ? "8u" + a[2] : a[0] + "." + a[1] + "." + a[2];
    }

    // ── 판정 ────────────────────────────────────────────────────────────────

    /** 이 JVM이 실제로 쓰는 신뢰 목록(기본 TrustManager)을 본다. */
    static Patch inspect() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        List<String> dns = new ArrayList<>();
        for (TrustManager tm : tmf.getTrustManagers()) {
            if (!(tm instanceof X509TrustManager x)) continue;
            for (X509Certificate c : x.getAcceptedIssuers()) {
                dns.add(c.getSubjectX500Principal().getName(X500Principal.RFC2253));
            }
        }
        return decide(dns, blocklistHashes());
    }

    /**
     * 판정 규칙(테스트용으로 분리). 신뢰 목록이 비어 보이면 판정하지 않는다(특수 공급자).
     *
     * @param subjectDns 신뢰 목록 루트들의 주체(RFC2253)
     */
    static Patch decide(Collection<String> subjectDns, int blocklistHashes) {
        if (subjectDns.isEmpty()) return Patch.UNKNOWN;
        boolean affected = false;
        for (String dn : subjectDns) {
            if (matches(dn, AFFECTED_ROOTS)) {
                affected = true;
                break;
            }
        }
        if (!affected) return Patch.ROOTS_ABSENT;
        return blocklistHashes >= BLOCKLIST_PATCHED_MIN ? Patch.BLOCKLIST : Patch.UNPATCHED;
    }

    /** 주체가 spec의 모든 속성(예: "CN=…,O=…")을 가졌는지. 속성 순서는 상관없다. */
    static boolean matches(String dn, String[] specs) {
        Set<String> have = new HashSet<>(Arrays.asList(dn.split(",")));
        for (String s : specs) {
            if (have.containsAll(Arrays.asList(s.split(",")))) return true;
        }
        return false;
    }

    /** {@code java.home/lib/security/blocked.certs}의 SHA-256 해시 줄 수(없으면 0). */
    static int blocklistHashes() {
        try {
            Path f = Path.of(System.getProperty("java.home"), "lib", "security", "blocked.certs");
            if (!Files.isRegularFile(f)) return 0;
            int n = 0;
            for (String line : Files.readAllLines(f)) {
                String t = line.trim();
                if (t.length() == 64 && t.chars().allMatch(ch -> Character.digit(ch, 16) >= 0)) n++;
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 신뢰 저장소를 못 읽을 때의 버전 표 판정. */
    static Level classify(List<Integer> v) {
        if (v.isEmpty()) return Level.OK;
        int feature = v.get(0);
        if (feature > NEWEST_CHECKED_FEATURE) return Level.OK;
        if (feature == WARN_ONLY_FEATURE) return Level.WARN;
        int[] affected = AFFECTED.get(feature);
        if (affected == null) return Level.VULNERABLE; // 지원이 끝난 줄 — 고친 판이 나오지 않는다
        return compare(v, affected) <= 0 ? Level.VULNERABLE : Level.OK;
    }

    private static List<Integer> currentVersion() {
        try {
            return new ArrayList<>(Runtime.version().version());
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
    }

    static int compare(List<Integer> a, int[] b) {
        for (int i = 0; i < Math.max(a.size(), b.length); i++) {
            int x = i < a.size() ? a.get(i) : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    // ── 우회(운영자가 고를 때만) ─────────────────────────────────────────────

    /** 기본 TLS에 교체형 TrustManager를 깐다(위임 대상은 처음엔 원래 기본 TrustManager — 동작 변화 없음). */
    private static void installSwitch() {
        try {
            X509ExtendedTrustManager orig = defaultTrustManager(null);
            if (orig == null) return;
            SwitchTrustManager tm = new SwitchTrustManager(orig);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(defaultKeyManagers(), new TrustManager[]{tm}, null);
            SSLContext.setDefault(ctx);
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
            switchTm = tm;
        } catch (Throwable t) {
            LOG.warn("[jre] TLS 준비 실패: {}", t.toString());
        }
    }

    /** 문제 루트를 뺀 신뢰 목록으로 위임 대상을 바꾼다. */
    private static boolean applyFix() {
        SwitchTrustManager tm = switchTm;
        if (tm == null) return false;
        try {
            KeyStore src = loadDefaultTrustStore();
            if (src == null) return false;
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int kept = 0, removed = 0;
            for (Enumeration<String> e = src.aliases(); e.hasMoreElements(); ) {
                String alias = e.nextElement();
                java.security.cert.Certificate c = src.getCertificate(alias);
                if (!(c instanceof X509Certificate x)) continue;
                if (matches(x.getSubjectX500Principal().getName(X500Principal.RFC2253), REMOVE_ROOTS)) {
                    removed++;
                    continue;
                }
                ks.setCertificateEntry(alias, x);
                kept++;
            }
            if (kept == 0 || removed == 0) return false;
            X509ExtendedTrustManager filtered = defaultTrustManager(ks);
            if (filtered == null) return false;
            tm.delegate = filtered;
            LOG.warn("[jre] 패치 안 된 Java — 문제 루트 인증서 {}개를 기본 TLS 신뢰 목록에서 뺐다(운영자 선택, 서버 프로세스 전체에 적용)", removed);
            return true;
        } catch (Throwable t) {
            LOG.warn("[jre] 우회 실패: {}", t.toString());
            return false;
        }
    }

    private static X509ExtendedTrustManager defaultTrustManager(KeyStore ks) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        for (TrustManager t : tmf.getTrustManagers()) {
            if (t instanceof X509ExtendedTrustManager x) return x;
        }
        return null;
    }

    /** JDK 기본과 같게 javax.net.ssl.keyStore가 있으면 그 클라이언트 인증서를 쓴다(없으면 null = 없음). */
    private static KeyManager[] defaultKeyManagers() {
        try {
            String path = System.getProperty("javax.net.ssl.keyStore");
            if (path == null || path.isEmpty() || "NONE".equals(path)) return null;
            String type = System.getProperty("javax.net.ssl.keyStoreType", KeyStore.getDefaultType());
            String pass = System.getProperty("javax.net.ssl.keyStorePassword");
            char[] pw = pass != null ? pass.toCharArray() : null;
            KeyStore ks = KeyStore.getInstance(type);
            try (InputStream in = Files.newInputStream(Path.of(path))) {
                ks.load(in, pw);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, pw);
            return kmf.getKeyManagers();
        } catch (Exception e) {
            return null;
        }
    }

    /** JDK와 같은 순서로 기본 신뢰 저장소를 찾는다: javax.net.ssl.trustStore → jssecacerts → cacerts. */
    private static KeyStore loadDefaultTrustStore() throws Exception {
        String prop = System.getProperty("javax.net.ssl.trustStore");
        String type = System.getProperty("javax.net.ssl.trustStoreType", KeyStore.getDefaultType());
        String pass = System.getProperty("javax.net.ssl.trustStorePassword");
        Path sec = Path.of(System.getProperty("java.home"), "lib", "security");
        Path file = null;
        if (prop != null && !prop.isEmpty() && !"NONE".equals(prop)) file = Path.of(prop);
        else if (Files.isRegularFile(sec.resolve("jssecacerts"))) file = sec.resolve("jssecacerts");
        else if (Files.isRegularFile(sec.resolve("cacerts"))) file = sec.resolve("cacerts");
        KeyStore ks = KeyStore.getInstance(type);
        if (file == null) {
            if (!type.toLowerCase(Locale.ROOT).startsWith("windows")) return null;
            ks.load(null, null);
            return ks;
        }
        try (InputStream in = Files.newInputStream(file)) {
            ks.load(in, pass != null ? pass.toCharArray() : null);
        }
        return ks;
    }

    /** 위임 대상을 실행 중에 바꿀 수 있는 TrustManager. */
    static final class SwitchTrustManager extends X509ExtendedTrustManager {
        volatile X509ExtendedTrustManager delegate;

        SwitchTrustManager(X509ExtendedTrustManager d) { this.delegate = d; }

        @Override public void checkClientTrusted(X509Certificate[] c, String a) throws CertificateException { delegate.checkClientTrusted(c, a); }
        @Override public void checkServerTrusted(X509Certificate[] c, String a) throws CertificateException { delegate.checkServerTrusted(c, a); }
        @Override public X509Certificate[] getAcceptedIssuers() { return delegate.getAcceptedIssuers(); }
        @Override public void checkClientTrusted(X509Certificate[] c, String a, java.net.Socket s) throws CertificateException { delegate.checkClientTrusted(c, a, s); }
        @Override public void checkServerTrusted(X509Certificate[] c, String a, java.net.Socket s) throws CertificateException { delegate.checkServerTrusted(c, a, s); }
        @Override public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) throws CertificateException { delegate.checkClientTrusted(c, a, e); }
        @Override public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) throws CertificateException { delegate.checkServerTrusted(c, a, e); }
    }
}
