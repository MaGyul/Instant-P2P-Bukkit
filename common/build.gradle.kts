import java.security.MessageDigest
import java.util.Properties

// 플랫폼과 무관한 코드. MC·플랫폼 API에 의존하지 않는다.
// 아래 라이브러리는 Paper/Velocity/Fabric(MC) 모두 런타임에 제공하므로 포함하지 않는다(compileOnly).
// 버전은 지원 하한(MC 1.21.0 번들)에 맞춰 새 버전에만 있는 API를 쓰지 않게 한다.
val provided = listOf(
    "org.slf4j:slf4j-api:2.0.9",
    "com.google.code.gson:gson:2.10.1",
    "com.google.guava:guava:32.1.2-jre",
    "io.netty:netty-buffer:4.1.97.Final",
    "io.netty:netty-transport:4.1.97.Final",
    "io.netty:netty-codec:4.1.97.Final",
)

val libdatachannelVersion: String by project

/** Maven Central에 있는 네이티브 classifier — NativeLibrary.platformClassifier()와 같아야 한다. */
val nativeClassifiers = listOf("x86_64", "aarch64", "windows-x86_64", "macos-x86_64", "macos-arm64")

val natives: Configuration by configurations.creating {
    isTransitive = false
    isCanBeConsumed = false
}

dependencies {
    provided.forEach {
        compileOnly(it)
        testImplementation(it)
    }
    // Java 부분은 플러그인 jar에 포함한다. JNI가 tel.schich.libdatachannel.* 이름으로 링크하므로 relocate 금지.
    // slf4j는 서버가 제공한다.
    implementation("tel.schich:libdatachannel-java:$libdatachannelVersion") {
        exclude(group = "org.slf4j")
    }
    nativeClassifiers.forEach { natives("tel.schich:libdatachannel-java:$libdatachannelVersion:$it") }
}

// 네이티브 classifier jar의 SHA-256을 빌드 시점에 고정한다 — 런타임에 받은 jar를 이 값으로 검증한다.
val generateNativeHashes by tasks.registering {
    val nativeJars: FileCollection = natives
    val version = libdatachannelVersion
    val outDir = layout.buildDirectory.dir("generated/natives")
    inputs.files(nativeJars)
    inputs.property("version", version)
    outputs.dir(outDir)
    doLast {
        val props = Properties()
        props["version"] = version
        nativeJars.files.forEach { jar ->
            val classifier = jar.name.removePrefix("libdatachannel-java-$version-").removeSuffix(".jar")
            val digest = MessageDigest.getInstance("SHA-256").digest(jar.readBytes())
            props["sha256.$classifier"] = digest.joinToString("") { "%02x".format(it) }
        }
        val file = outDir.get().file("instantp2p-natives.properties").asFile
        file.parentFile.mkdirs()
        // 매 빌드마다 날짜 주석이 바뀌지 않게 직접 쓴다
        file.writeText(props.entries.sortedBy { it.key.toString() }
            .joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" })
    }
}

sourceSets.main {
    resources.srcDir(generateNativeHashes)
}
