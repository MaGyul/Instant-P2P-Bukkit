plugins {
    id("net.fabricmc.fabric-loom")
    id("com.gradleup.shadow")
}

// 26.x용 Fabric API는 Java 25 바이트코드라 JDK 25 컴파일러로 읽는다. 출력은 다른 모듈과 같은 Java 21 바이트코드.
java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

val fabric26MinecraftVersion: String by project
val fabric26ApiVersion: String by project
val fabricLoaderVersion: String by project

dependencies {
    minecraft("com.mojang:minecraft:$fabric26MinecraftVersion")
    implementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabric26ApiVersion")

    compileOnly(project(":common"))
    compileOnly(project(":fabric-base"))
}

// 구현 소스는 fabric-1_21과 공유한다 (fabric-shared) — 여기서는 26.1로 컴파일하고 remap하지 않는다
sourceSets.main {
    java.srcDir(rootProject.layout.projectDirectory.dir("fabric-shared/src/main/java"))
}

tasks.shadowJar {
    configurations = emptyList() // 이 모듈의 클래스만
    archiveClassifier = "v26"
    // 1.21.x판과 한 jar에 들어가므로 패키지를 나눈다 (FabricEntry가 이 이름으로 로드)
    relocate("dev.magyul.instantp2p.fabric.impl", "dev.magyul.instantp2p.fabric.v26")
}

loom {
    runs {
        named("server") {
            runDir = "../run-fabric"
        }
    }
}

// 26.x 구현 클래스만 fabric-1_21 모듈의 최종 jar에 합친다 (리매핑하지 않는다)
val v26Classes: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts {
    add(v26Classes.name, tasks.shadowJar)
}
