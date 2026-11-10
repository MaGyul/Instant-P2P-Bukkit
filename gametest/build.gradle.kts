plugins {
    id("net.fabricmc.fabric-loom-remap") version "1.18.2"
}

/**
 * 원본 클라이언트 모드(original/mods)로 서버판에 실제로 입장해 보는 Fabric 클라이언트 GameTest.
 * 메인 빌드와 분리된 프로젝트다(루트 settings에 포함되지 않음):
 *   ./gradlew -p gametest runClientGameTest -Pp2p.code=<초대 코드>
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
    // 서버(Paper 1.21.11 dev 서버)와 같은 버전의 클라이언트
    minecraft("com.mojang:minecraft:1.21.11")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:0.19.5")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.141.6+1.21.11")
    // 시험 대상 클라이언트 모드 (공식 1.5.1) + 정품 로그인용 AuthMe (사람이 한 번 로그인)
    modRuntimeOnly(files("../original/mods/instant-p2p-1.5.1.jar"))
    modRuntimeOnly(files("../original/mods/authme/authme-fabric-9.1.0+1.21.11.jar"))
    // AuthMe가 jar 안에 품은 것들(Loom이 개발 환경에서 중첩 jar를 풀어 주지 않아 따로 둔다 — libs/는 AuthMe jar의 META-INF/jars)
    modRuntimeOnly(files("libs/resourcefulconfig-fabric-1.21.11-3.11.2.jar"))
    runtimeOnly(files("libs/commons-logging-1.3.5.jar", "libs/httpclient-4.5.14.jar", "libs/httpcore-4.4.16.jar"))
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
            runDir = "run-client"
            val code = providers.gradleProperty("p2p.code").orNull
            if (code != null) vmArg("-Dp2p.code=$code")
            providers.gradleProperty("p2p.holdSeconds").orNull?.let { vmArg("-Dp2p.holdSeconds=$it") }
            providers.gradleProperty("p2p.profile").orNull?.let { vmArg("-Dp2p.profile=$it") }
            providers.gradleProperty("p2p.control").orNull?.let { vmArg("-Dp2p.control=$it") }
            providers.gradleProperty("p2p.relay").orNull?.let { vmArg("-Dp2p.relay=$it") }
        }
    }
}
