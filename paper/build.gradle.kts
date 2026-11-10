plugins {
    id("com.gradleup.shadow")
    id("xyz.jpenilla.run-paper")
}

dependencies {
    implementation(project(":common"))
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

tasks {
    jar {
        archiveClassifier = "plain"
    }

    shadowJar {
        archiveClassifier = ""
        archiveBaseName = rootProject.name
        // MC 내부를 이름으로 참조하지 않으므로 Paper의 플러그인 리매핑이 필요 없다 (Spigot은 무시)
        manifest.attributes("paperweight-mappings-namespace" to "mojang")
    }

    assemble {
        dependsOn(shadowJar)
    }

    runServer {
        // -PmcVersion=26.3 -PrunDir=run-26 으로 다른 MC 버전·폴더에서 시험(월드는 앞으로만 업그레이드되므로 폴더를 나눈다)
        minecraftVersion(providers.gradleProperty("mcVersion").getOrElse("1.21.11"))
        jvmArgs("-Xms2G", "-Xmx2G")
        // 개발 서버는 JDK 25로 — JDK 21.0.3 등 오래된 JDK는 JreGuard(CVE-2026-91403)가 방 열기를 막는다
        javaLauncher = project.extensions.getByType(JavaToolchainService::class.java).launcherFor { languageVersion = JavaLanguageVersion.of(25) }
        runDirectory = rootProject.layout.projectDirectory.dir(providers.gradleProperty("runDir").getOrElse("run"))
    }

    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}

// universal 모듈이 합칠 이 플랫폼의 최종 jar
val platformJar: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts {
    add(platformJar.name, tasks.named("shadowJar"))
}
