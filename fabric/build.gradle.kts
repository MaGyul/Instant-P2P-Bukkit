plugins {
    id("net.fabricmc.fabric-loom-remap")
    id("com.gradleup.shadow")
}

val fabricMinecraftVersion: String by project
val fabricLoaderVersion: String by project
val fabricApiVersion: String by project

/** 모드 jar에 합칠 것 — common과 libdatachannel-java(Java 부분). slf4j는 common 쪽에서 이미 제외. */
val shade: Configuration by configurations.creating {
    isCanBeConsumed = false
}

dependencies {
    minecraft("com.mojang:minecraft:$fabricMinecraftVersion")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    implementation(project(":common"))
    shade(project(":common"))
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
    }

    // 1.21.x는 intermediary 이름으로 돈다 — 합친 jar를 통째로 리매핑한다 (common은 MC를 참조하지 않아 그대로)
    remapJar {
        inputFile = shadowJar.flatMap { it.archiveFile }
        archiveBaseName = "instant-p2p-fabric"
        archiveClassifier = ""
    }
}
