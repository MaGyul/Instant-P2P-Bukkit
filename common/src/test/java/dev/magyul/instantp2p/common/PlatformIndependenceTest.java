package dev.magyul.instantp2p.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * common 클래스가 플랫폼 API를 참조하지 않는지 — 한 번이라도 참조하면 다른 로더에서 NoClassDefFoundError가 난다.
 * 컴파일된 클래스 파일의 상수 풀에서 패키지 이름을 찾는다.
 */
class PlatformIndependenceTest {

    private static final List<String> FORBIDDEN = List.of(
            "org/bukkit/", "io/papermc/", "net/minecraft/", "com/mojang/", "net/kyori/",
            "com/velocitypowered/", "net/fabricmc/", "org/apache/logging/", "org/jspecify/");

    @Test
    void commonDoesNotReferencePlatformPackages() throws Exception {
        Path classes = Path.of(Utils.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(Files.isDirectory(classes), "compiled classes dir expected: " + classes);

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                String content = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                for (String pkg : FORBIDDEN) {
                    if (content.contains(pkg)) violations.add(classes.relativize(file) + " → " + pkg);
                }
            }
        }
        assertTrue(violations.isEmpty(), "플랫폼 참조: " + violations);
    }
}
