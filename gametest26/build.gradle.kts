plugins {
    id("net.fabricmc.fabric-loom") version "1.18.2"
}

/**
 * 원본 클라이언트 모드(original/mods)로 서버판에 실제로 입장해 보는 Fabric 클라이언트 GameTest.
 * 메인 빌드와 분리된 프로젝트다(루트 settings에 포함되지 않음):
 *   ./gradlew -p gametest26 runAutoJoin -Pp2p.code=<초대 코드>
 * 서버는 따로 띄운다(예: ./gradlew :paper:runServer 후 /p2p login → /p2p open).
 */
java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

dependencies {
    // 26.x는 난독화가 없다 — 매핑·remap 없이 그대로 쓴다. 서버도 26.3(Paper)으로 띄운다.
    minecraft("com.mojang:minecraft:26.3")
    implementation("net.fabricmc:fabric-loader:0.19.5")
    implementation("net.fabricmc.fabric-api:fabric-api:0.166.0+26.3")
    // 시험 대상: 가맹점 클라이언트 F4.0.2 (26.3) + 정품 로그인용 AuthMe
    // 기본은 공식 1.5.1(공식 서버 시험), -Pp2p.mod=franchise면 가맹점 F4.0.2
    runtimeOnly(files(if (providers.gradleProperty("p2p.mod").getOrElse("official") == "franchise")
        "../original/mods/instant-p2p-franchise-26.3-1.5.1-F4.0.2.jar" else "../original/mods/instant-p2p-1.5.1.jar"))
    runtimeOnly(files("../original/mods/authme/authme-fabric-9.3.0+26.3.jar"))
    // AuthMe가 jar 안에 품은 것들(Loom이 개발 환경에서 중첩 jar를 풀어 주지 않아 따로 둔다 — libs/는 AuthMe jar의 META-INF/jars)
    runtimeOnly(files("libs/resourcefulconfig-fabric-26.3-6.0.0.jar", "libs/commons-logging-1.3.6.jar",
        "libs/httpclient-4.5.14.jar", "libs/httpcore-4.4.16.jar"))
}

fabricApi {
    configureTests {
        createSourceSet = true
        modId = "p2p-clienttest"
        enableGameTests = false
        enableClientGameTests = true
        eula = true
    }
}

loom {
    runs {
        // 일반 클라이언트로 자동 입장 시험 (입력이 막히지 않아 사람이 AuthMe 로그인 가능): ./gradlew -p gametest runAutoJoin -Pp2p.code=...
        create("autoJoin") {
            client()
            ideConfigGenerated(false)
            source(sourceSets.getByName("gametest"))
            vmArg("-Dp2p.autojoin=true")
        }
        // 로그인 정보(AuthMe)를 일반 실행과 GameTest가 같이 쓰도록 같은 폴더를 쓴다
        configureEach {
            runDir = providers.gradleProperty("p2p.runDir").getOrElse("run-client")
            val code = providers.gradleProperty("p2p.code").orNull
            if (code != null) vmArg("-Dp2p.code=$code")
            providers.gradleProperty("p2p.holdSeconds").orNull?.let { vmArg("-Dp2p.holdSeconds=$it") }
            providers.gradleProperty("p2p.profile").orNull?.let { vmArg("-Dp2p.profile=$it") }
            providers.gradleProperty("p2p.control").orNull?.let { vmArg("-Dp2p.control=$it") }
            providers.gradleProperty("p2p.relay").orNull?.let { vmArg("-Dp2p.relay=$it") }
            providers.gradleProperty("p2p.waitgo").orNull?.let { vmArg("-Dp2p.waitgo=$it") }
        }
    }
}
