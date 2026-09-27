import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("com.gradleup.shadow") version "9.2.2" apply false
    id("xyz.jpenilla.run-paper") version "3.1.0" apply false
    id("net.fabricmc.fabric-loom-remap") version "1.18.2" apply false
    id("net.fabricmc.fabric-loom") version "1.18.2" apply false
}

subprojects {
    apply(plugin = "java-library")

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion = JavaLanguageVersion.of(21)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 21
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.11.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    // QUIC 라이브러리(kwik과 의존성)는 jar에 넣되 패키지를 옮긴다 — 원본 클라이언트 모드도 kwik을 품고 있어
    // 같은 서버(특히 Fabric)에 함께 깔리면 클래스가 겹친다. 모든 플랫폼 jar가 같은 규칙이어야 universal에서 합쳐진다.
    plugins.withId("com.gradleup.shadow") {
        tasks.withType<ShadowJar>().configureEach {
            val libs = "dev.magyul.instantp2p.libs"
            relocate("tech.kwik", "$libs.kwik")
            relocate("at.favre.lib.hkdf", "$libs.hkdf")
            relocate("io.whitfin.siphash", "$libs.siphash")
            exclude("tech/kwik/cli/**", "module-info.class", "META-INF/versions/*/module-info.class", "META-INF/maven/**")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // 서버 클래스패스의 log4j 등이 작업 디렉터리에 logs/를 만들 수 있어 build 아래에서 돌린다
        val workDir = layout.buildDirectory.dir("test-work").get().asFile
        workingDir = workDir
        doFirst { workDir.mkdirs() }
    }
}
