package dev.magyul.instantp2p.common.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Optional;

/**
 * 로그인 정보(갱신 토큰 등)를 AES-256-GCM으로 암호화해 저장한다.
 * <p>
 * <b>1차 보안</b>이 목적이다 — 암호문은 서버 데이터 폴더({@code account.dat})에, 키는 <b>서버 폴더 밖</b>
 * ({@code ~/.instant-p2p/keys/<serverUuid>.key})에 둔다. 서버 폴더를 백업·공유·업로드해도 키가 따라가지 않으므로
 * 토큰을 꺼낼 수 없다. 같은 OS 계정으로 서버에 접근할 수 있는 사람까지 막지는 못한다.
 * 서버 UUID를 AAD로 묶어 다른 서버의 파일로 바꿔치기해도 풀리지 않는다.
 * <p>
 * 홈 폴더에 쓸 수 없으면(일부 컨테이너) 키를 데이터 폴더에 두고 경고한다. 키가 없어지면(서버 이전·컨테이너 재생성)
 * 복호화가 안 되므로 다시 로그인하면 된다.
 */
final class TokenStore {

    private static final Logger LOG = LoggerFactory.getLogger("instant-p2p-auth");
    private static final byte[] MAGIC = "IP2PA1".getBytes(StandardCharsets.US_ASCII);
    private static final int IV_BYTES = 12;
    private static final int KEY_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path dataFile;
    private final Path homeKeyFile;
    private final Path localKeyFile;
    private final byte[] aad;

    TokenStore(Path dataFolder, String serverId) {
        this.dataFile = dataFolder.resolve("account.dat");
        this.homeKeyFile = homeKeyDir().map(d -> d.resolve(serverId + ".key")).orElse(null);
        this.localKeyFile = dataFolder.resolve(".account.key");
        this.aad = serverId.getBytes(StandardCharsets.UTF_8);
    }

    boolean exists() {
        return Files.exists(dataFile);
    }

    /** 저장된 평문(JSON). 없거나 풀 수 없으면 empty — 풀 수 없으면 이유를 경고로 남긴다. */
    Optional<String> load() {
        if (!Files.exists(dataFile)) return Optional.empty();
        try {
            byte[] all = Files.readAllBytes(dataFile);
            if (all.length < MAGIC.length + IV_BYTES + 16 || !Arrays.equals(Arrays.copyOf(all, MAGIC.length), MAGIC)) {
                LOG.warn("[auth] 저장된 로그인 파일 형식이 맞지 않습니다 — 다시 로그인해 주세요");
                return Optional.empty();
            }
            byte[] key = readKey();
            if (key == null) {
                LOG.warn("[auth] 로그인 정보를 풀 키가 없습니다(서버 이전·컨테이너 재생성 등) — 다시 로그인해 주세요");
                return Optional.empty();
            }
            byte[] iv = Arrays.copyOfRange(all, MAGIC.length, MAGIC.length + IV_BYTES);
            byte[] ct = Arrays.copyOfRange(all, MAGIC.length + IV_BYTES, all.length);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            c.updateAAD(aad);
            return Optional.of(new String(c.doFinal(ct), StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            LOG.warn("[auth] 저장된 로그인 정보를 풀 수 없습니다(키가 바뀌었거나 파일이 손상됨) — 다시 로그인해 주세요");
            return Optional.empty();
        } catch (IOException e) {
            LOG.warn("[auth] 저장된 로그인 정보를 읽지 못했습니다: {}", e.getMessage());
            return Optional.empty();
        }
    }

    void save(String plain) throws IOException {
        try {
            byte[] key = readKey();
            if (key == null) key = createKey();
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            c.updateAAD(aad);
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[MAGIC.length + IV_BYTES + ct.length];
            System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
            System.arraycopy(iv, 0, out, MAGIC.length, IV_BYTES);
            System.arraycopy(ct, 0, out, MAGIC.length + IV_BYTES, ct.length);
            writeAtomically(dataFile, out);
        } catch (GeneralSecurityException e) {
            throw new IOException("암호화 실패: " + e.getMessage(), e);
        }
    }

    /** 로그아웃 — 암호문과 키를 모두 지운다. */
    void delete() {
        for (Path p : new Path[]{dataFile, homeKeyFile, localKeyFile}) {
            if (p == null) continue;
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                LOG.warn("[auth] {} 삭제 실패: {}", p.getFileName(), e.getMessage());
            }
        }
    }

    private byte[] readKey() throws IOException {
        for (Path p : new Path[]{homeKeyFile, localKeyFile}) {
            if (p != null && Files.exists(p)) {
                byte[] k = Files.readAllBytes(p);
                if (k.length == KEY_BYTES) return k;
            }
        }
        return null;
    }

    private byte[] createKey() throws IOException {
        byte[] key = new byte[KEY_BYTES];
        RANDOM.nextBytes(key);
        if (homeKeyFile != null) {
            try {
                Path dir = homeKeyFile.getParent();
                Files.createDirectories(dir);
                restrict(dir, "rwx------");
                writeAtomically(homeKeyFile, key);
                return key;
            } catch (IOException e) {
                LOG.warn("[auth] 홈 폴더에 키를 둘 수 없습니다({}) — 서버 데이터 폴더에 둡니다. 서버 폴더를 공유할 때 {}도 함께 빠지지 않게 주의하세요",
                        e.getMessage(), localKeyFile.getFileName());
            }
        }
        writeAtomically(localKeyFile, key);
        return key;
    }

    private static void writeAtomically(Path target, byte[] data) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, data);
        restrict(tmp, "rw-------");
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** POSIX 파일 시스템이면 소유자만 읽게 한다 (Windows는 사용자 프로필 폴더 ACL에 맡긴다). */
    private static void restrict(Path p, String perms) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(perms));
        } catch (UnsupportedOperationException | IOException ignored) {
        }
    }

    private static Optional<Path> homeKeyDir() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) return Optional.empty();
        return Optional.of(Path.of(home, ".instant-p2p", "keys"));
    }
}
