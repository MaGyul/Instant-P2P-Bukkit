plugins {
    id("net.fabricmc.fabric-loom-remap") version "1.18.2"
}

/**
 * 배포 jar(universal)를 실제 Fabric 전용 서버에 모드로 넣어 돌려 보는 시험용 프로젝트 — fabric-1_21:runServer는
 * 모듈 클래스만 올라가 relocate된 구현(v1_21)이 없어 실제 배포 형태를 시험할 수 없다.
 *   ./gradlew build  (루트)  →  ./gradlew -p fabricserver runServer
 */
java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.11")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:0.19.5")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.141.6+1.21.11")
    // 시험 대상: 루트 빌드가 만든 배포 jar (intermediary로 remap된 1.21.x판 + 26.x판 포함)
    modRuntimeOnly(files(fileTree("../universal/build/libs") { include("instant-p2p-server-*.jar") }.files.maxByOrNull { it.name }!!))
}

loom {
    runs {
        named("server") {
            runDir = "run"
        }
    }
}
