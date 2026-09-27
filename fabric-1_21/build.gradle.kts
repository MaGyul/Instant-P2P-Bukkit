import org.gradle.kotlin.dsl.support.serviceOf

plugins {
    id("net.fabricmc.fabric-loom-remap")
    id("com.gradleup.shadow")
}

val fabric1_21MinecraftVersion: String by project
val fabricLoaderVersion: String by project
val fabric1_21ApiVersion: String by project

/** 모드 jar에 합칠 것 — common, fabric-base, libdatachannel-java(Java 부분). slf4j는 common 쪽에서 이미 제외. */
val shade: Configuration by configurations.creating {
    isCanBeConsumed = false
}

/** 26.x 구현 (리매핑하지 않은 Mojang 이름 클래스) */
val v26: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    minecraft("com.mojang:minecraft:$fabric1_21MinecraftVersion")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabric1_21ApiVersion")

    implementation(project(":common"))
    implementation(project(":fabric-base"))
    shade(project(":common"))
    shade(project(":fabric-base"))
    v26(project(path = ":fabric-26", configuration = "v26Classes"))
}

// 구현 소스는 fabric-26과 공유한다 (fabric-shared) — 여기서는 1.21.11로 컴파일해 intermediary로 remap한다
sourceSets.main {
    java.srcDir(rootProject.layout.projectDirectory.dir("fabric-shared/src/main/java"))
}

loom {
    runs {
        named("server") {
            runDir = "../run-fabric"
        }
    }
}

tasks {
    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("fabric.mod.json") {
            expand(props)
        }
    }

    shadowJar {
        configurations = listOf(shade)
        archiveClassifier = "dev-shadow"
        // 26.x판과 한 jar에 들어가므로 패키지를 나눈다 (FabricEntry가 이 이름으로 로드)
        relocate("dev.magyul.instantp2p.fabric.impl", "dev.magyul.instantp2p.fabric.v1_21")
    }

    // 1.21.x는 intermediary 이름으로 돈다 — 합친 jar를 통째로 리매핑한다 (common은 MC를 참조하지 않아 그대로)
    remapJar {
        inputFile = shadowJar.flatMap { it.archiveFile }
        archiveClassifier = "1.21-only"
    }

    // 최종 Fabric jar: 리매핑된 1.21.x jar + 리매핑하지 않은 26.x 구현. FabricEntry가 버전을 보고 하나만 로드한다.
    // 26.x 클래스를 remapJar에 넣으면 1.21.x 매핑으로 이름이 바뀌어 버리므로 반드시 리매핑 뒤에 합친다.
    val archives = serviceOf<ArchiveOperations>()
    val fabricJar by registering(Jar::class) {
        archiveBaseName = "instant-p2p-fabric"
        archiveClassifier = ""
        from(remapJar.flatMap { it.archiveFile }.map { archives.zipTree(it) })
        from(v26.elements.map { files -> files.map { archives.zipTree(it.asFile) } }) {
            include("dev/magyul/instantp2p/fabric/v26/**")
        }
        duplicatesStrategy = DuplicatesStrategy.FAIL
    }

    assemble {
        dependsOn(fabricJar)
    }
}

// universal 모듈이 합칠 이 플랫폼의 최종 jar
val platformJar: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts {
    add(platformJar.name, tasks.named("fabricJar"))
}
