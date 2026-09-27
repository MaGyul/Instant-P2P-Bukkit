plugins {
    id("com.gradleup.shadow")
    id("xyz.jpenilla.run-paper")
}

dependencies {
    implementation(project(":common"))
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // nativeLogLevel 조정(Configurator)용 — 서버에 있고, 없으면 무시한다
    compileOnly("org.apache.logging.log4j:log4j-core:2.22.1")
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
        minecraftVersion("1.21.11")
        jvmArgs("-Xms2G", "-Xmx2G")
        runDirectory = rootProject.layout.projectDirectory.dir("run")
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
