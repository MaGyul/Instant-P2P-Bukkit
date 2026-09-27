package dev.magyul.instantp2p.common.webrtc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tel.schich.libdatachannel.LibDataChannel;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * libdatachannel 네이티브를 데이터 폴더에 받아서 로드한다. 로더(Paper/Spigot/Velocity/Fabric)와 무관하게 동작한다.
 * <p>
 * Java 부분(tel.schich.libdatachannel.*)은 플러그인 jar에 들어 있고(JNI가 클래스 이름으로 찾으므로 relocate 금지),
 * 네이티브는 Maven Central의 classifier jar({@code libdatachannel-java-<ver>-<classifier>.jar})를
 * {@code <dataFolder>/native/}에 받아 {@code /native/<file>}만 푼다. classpath에 올리지 않는다.
 * <p>
 * classifier jar의 SHA-256은 빌드 때 고정한 값({@code /instantp2p-natives.properties})과 맞아야 한다.
 * 이미 받아 둔 jar가 해시가 맞으면 다시 받지 않는다 — 오프라인 서버는 그 jar를 직접 넣어 두면 된다.
 * <p>
 * /tmp가 noexec인 호스팅이 있어 라이브러리 기본 동작(/tmp 추출) 대신 데이터 폴더에 풀고
 * {@code libdatachannel.native.datachannel-java.path}로 경로를 넘긴다.
 * 같은 JVM에서 다른 클래스로더가 같은 .so를 다시 로드할 수 없으므로 /reload는 지원하지 않는다.
 */
public final class NativeLibrary {

    private static final Logger LOG = LoggerFactory.getLogger("webrtc-bridge");

    private static final String HASHES_RESOURCE = "/instantp2p-natives.properties";
    private static final String DEFAULT_REPOSITORY = "https://repo1.maven.org/maven2";
    /** 미러를 쓰려면 -Dinstantp2p.maven.repo=https://... */
    private static final String REPOSITORY_PROPERTY = "instantp2p.maven.repo";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(60);

    /** 같은 JVM에서 한 번만 로드한다 (클래스로더와 무관하게 .so는 프로세스 단위). */
    private static volatile boolean loaded = false;

    private NativeLibrary() {}

    public static synchronized void load(Path dataFolder) {
        if (loaded) return;
        Properties hashes = loadHashes();
        String version = hashes.getProperty("version");
        String classifier = platformClassifier();
        String expected = hashes.getProperty("sha256." + classifier);
        if (version == null || expected == null) {
            throw new IllegalStateException("네이티브 해시 정보가 없습니다 (" + classifier + ") — 빌드가 잘못됐습니다");
        }

        String file = nativeFileName();
        try {
            Path dir = dataFolder.resolve("native");
            Files.createDirectories(dir);
            Path jar = dir.resolve("libdatachannel-java-" + version + "-" + classifier + ".jar");
            ensureJar(jar, version, classifier, expected);

            Path out = dir.resolve(file);
            extract(jar, file, out);
            System.setProperty("libdatachannel.native.datachannel-java.path", out.toAbsolutePath().toString());
            LibDataChannel.initialize();
            loaded = true;
            LOG.info("[WebRTC] libdatachannel loaded from {}", out);
        } catch (IOException | LinkageError e) {
            // LinkageError(UnsatisfiedLinkError)는 Exception이 아니라서 호출부 catch(Exception)에
            // 안 걸린다 — 여기서 원인을 붙여 IllegalStateException으로 바꿔 던진다.
            throw new IllegalStateException("libdatachannel 네이티브 로드 실패: " + e, e);
        }
    }

    /** 받아 둔 jar가 없거나 해시가 다르면 새로 받는다. 받은 파일도 해시가 맞아야 쓴다. */
    private static void ensureJar(Path jar, String version, String classifier, String expected) throws IOException {
        if (Files.exists(jar)) {
            String actual = sha256(jar);
            if (actual.equalsIgnoreCase(expected)) return;
            LOG.warn("[WebRTC] {} 해시가 다릅니다 (expected={}, actual={}) — 다시 받습니다", jar.getFileName(), expected, actual);
        }

        String repo = System.getProperty(REPOSITORY_PROPERTY, DEFAULT_REPOSITORY);
        if (repo.endsWith("/")) repo = repo.substring(0, repo.length() - 1);
        String url = repo + "/tel/schich/libdatachannel-java/" + version + "/" + jar.getFileName();
        LOG.info("[WebRTC] 네이티브 라이브러리를 받는 중: {}", url);

        Path tmp = Files.createTempFile(jar.getParent(), jar.getFileName().toString(), ".part");
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(HTTP_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(HTTP_TIMEOUT).GET().build();
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(tmp));
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " (" + url + ")");
            }
            String actual = sha256(tmp);
            if (!actual.equalsIgnoreCase(expected)) {
                throw new IOException("받은 파일의 SHA-256이 다릅니다: expected=" + expected + ", actual=" + actual);
            }
            Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("다운로드가 중단됐습니다", e);
        } catch (IOException e) {
            throw new IOException("네이티브 라이브러리를 받지 못했습니다. 오프라인 서버라면 " + url
                    + " 을 직접 받아 " + jar + " 에 넣어 주세요 (" + e.getMessage() + ")", e);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void extract(Path jar, String file, Path out) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("native/" + file);
            if (entry == null) throw new IOException(jar.getFileName() + "에 native/" + file + "이 없습니다");
            try (InputStream in = zip.getInputStream(entry)) {
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            } catch (FileSystemException e) {
                // 윈도우에서 이전 실행이 파일을 잡고 있는 경우 등 — 기존 파일이 있으면 그걸 쓴다
                if (!Files.exists(out)) throw e;
                LOG.warn("[WebRTC] could not overwrite {}, using existing file ({})", out, e.getMessage());
            }
        }
    }

    private static Properties loadHashes() {
        try (InputStream in = NativeLibrary.class.getResourceAsStream(HASHES_RESOURCE)) {
            if (in == null) throw new IllegalStateException(HASHES_RESOURCE + "가 없습니다 — 빌드가 잘못됐습니다");
            Properties p = new Properties();
            p.load(in);
            return p;
        } catch (IOException e) {
            throw new IllegalStateException(HASHES_RESOURCE + "를 읽을 수 없습니다", e);
        }
    }

    static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256은 모든 JVM에 필수로 들어 있다
        }
    }

    /** Maven Central에 올라와 있는 classifier (빌드 스크립트의 목록과 같아야 한다) */
    static String platformClassifier() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");

        if (os.contains("linux")) {
            if (x64) return "x86_64";
            if (arm64) return "aarch64";
        } else if (os.contains("win")) {
            if (x64) return "windows-x86_64";
        } else if (os.contains("mac")) {
            if (x64) return "macos-x86_64";
            if (arm64) return "macos-arm64";
        }
        throw new IllegalStateException("libdatachannel-java가 지원하지 않는 플랫폼입니다: " + os + " / " + arch);
    }

    private static String nativeFileName() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "libdatachannel-java.dll";
        if (os.contains("mac")) return "libdatachannel-java.dylib";
        return "libdatachannel-java.so";
    }
}
