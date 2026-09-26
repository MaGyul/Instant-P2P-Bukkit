package dev.magyul.instantp2p;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

public final class InstantP2PLoader implements PluginLoader {

    record Versions(String ldc) {}

    @Override
    public void classloader(PluginClasspathBuilder builder) {
        Versions version = loadVersions();
        String classifier = platformClassifier();

        MavenLibraryResolver resolver = new MavenLibraryResolver();
        resolver.addRepository(new RemoteRepository.Builder(
                "central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());

        // Java API
        resolver.addDependency(new Dependency(
                new DefaultArtifact("tel.schich:libdatachannel-java:" + version.ldc), null));
        // 네이티브: arch-detect 모듈이 주요 플랫폼 네이티브를 전부 포함
        resolver.addDependency(new Dependency(
                new DefaultArtifact("tel.schich:libdatachannel-java-arch-detect:" + version.ldc), null));

        builder.addLibrary(resolver);
    }

    private static Versions loadVersions() {
        try (InputStream in = InstantP2PLoader.class.getResourceAsStream("/instantp2p-libs.properties")) {
            Properties p = new Properties();
            p.load(in);
            return new Versions(
                    p.getProperty("libdatachannel.version")
            );
        } catch (IOException | NullPointerException e) {
            throw new IllegalStateException("instantp2p-libs.properties를 읽을 수 없습니다", e);
        }
    }

    private static String platformClassifier() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);

        String osPart;
        if (os.contains("win")) osPart = "windows";
        else if (os.contains("mac")) osPart = "macos";
        else if (os.contains("linux")) osPart = "linux";
        else throw unsupported(os, arch);

        String archPart = switch (arch) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            case "arm", "armv7l" -> "aarch32";
            default -> throw unsupported(os, arch);
        };

        return osPart + "-" + archPart;
    }

    private static IllegalStateException unsupported(String os, String arch) {
        return new IllegalStateException(
                "webrtc-java가 지원하지 않는 플랫폼입니다: " + os + " / " + arch);
    }
}