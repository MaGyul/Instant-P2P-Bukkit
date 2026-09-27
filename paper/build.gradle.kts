plugins {
    id("com.gradleup.shadow")
    id("xyz.jpenilla.run-paper")
}

val libdatachannelVersion: String by project

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
        val props = mapOf(
            "version" to project.version,
            "libdatachannelVersion" to libdatachannelVersion)
        inputs.properties(props)
        filesMatching(listOf("paper-plugin.yml", "instantp2p-libs.properties")) {
            expand(props)
        }
    }
}
