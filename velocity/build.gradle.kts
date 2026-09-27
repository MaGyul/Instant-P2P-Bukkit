plugins {
    id("com.gradleup.shadow")
}

val velocityApiVersion: String by project

dependencies {
    implementation(project(":common"))
    compileOnly("com.velocitypowered:velocity-api:$velocityApiVersion")
    // velocity-plugin.json 생성
    annotationProcessor("com.velocitypowered:velocity-api:$velocityApiVersion")
    // 프록시가 런타임에 제공한다 (IP 복원용 Netty 파이프라인·HAProxyMessage, nativeLogLevel용 log4j-core)
    compileOnly("io.netty:netty-transport:4.1.97.Final")
    compileOnly("io.netty:netty-codec-haproxy:4.1.97.Final")
    compileOnly("org.apache.logging.log4j:log4j-core:2.22.1")

    testImplementation("com.velocitypowered:velocity-api:$velocityApiVersion")
}

// @Plugin(version = ...)은 컴파일 상수여야 해서 버전 상수 클래스를 생성한다
val generateBuildInfo by tasks.registering {
    val version = project.version.toString()
    val outDir = layout.buildDirectory.dir("generated/buildinfo")
    inputs.property("version", version)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().file("dev/magyul/instantp2p/velocity/BuildInfo.java").asFile
        file.parentFile.mkdirs()
        file.writeText("""
            |package dev.magyul.instantp2p.velocity;
            |
            |final class BuildInfo {
            |    static final String VERSION = "$version";
            |
            |    private BuildInfo() {}
            |}
            |""".trimMargin())
    }
}

sourceSets.main {
    java.srcDir(generateBuildInfo)
}

tasks {
    jar {
        archiveClassifier = "plain"
    }

    shadowJar {
        archiveBaseName = "instant-p2p-velocity"
        archiveClassifier = ""
    }

    assemble {
        dependsOn(shadowJar)
    }
}
