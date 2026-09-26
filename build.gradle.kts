plugins {
    id("java-library")
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

val libdatachannelVersion = "0.24.1.1"

dependencies {
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
    compileOnly ("tel.schich:libdatachannel-java:${libdatachannelVersion}")
    compileOnly ("tel.schich:libdatachannel-java-arch-detect:${libdatachannelVersion}")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

// 1.20.5+ Paper는 Mojang 매핑 런타임이라 reobf가 필요 없음
paperweight.reobfArtifactConfiguration =
    io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION

tasks {
    runServer {
        minecraftVersion("1.21.11")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf(
            "version" to version,
            "libdatachannelVersion" to libdatachannelVersion)
        inputs.properties(props)
        filesMatching(listOf("paper-plugin.yml", "instantp2p-libs.properties")) {
            expand(props)
        }
    }
}
