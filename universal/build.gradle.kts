import org.gradle.kotlin.dsl.support.serviceOf
import java.util.zip.ZipFile

// 배포용 단일 jar — plugins/나 mods/에 넣으면 Paper/Spigot, Velocity, Fabric 어디서든 돈다.
// 각 로더는 자기 descriptor(plugin.yml / velocity-plugin.json / fabric.mod.json)만 읽고 거기 적힌 진입 클래스만 로드한다.

fun platform(name: String): Configuration = configurations.create(name) {
    isCanBeConsumed = false
    isTransitive = false
}

val paperJar = platform("paperJar")
val velocityJar = platform("velocityJar")
val fabricJar = platform("fabricJar")

dependencies {
    paperJar(project(path = ":paper", configuration = "platformJar"))
    velocityJar(project(path = ":velocity", configuration = "platformJar"))
    fabricJar(project(path = ":fabric-1_21", configuration = "platformJar"))
}

val universalJar by tasks.registering(Jar::class) {
    // 스크립트 최상위 값을 람다가 잡으면 구성 캐시에 스크립트 객체가 딸려 가므로 여기서 지역으로 둔다
    val archives = project.serviceOf<ArchiveOperations>()
    val paperFiles = paperJar.elements
    val velocityFiles = velocityJar.elements
    val fabricFiles = fabricJar.elements
    archiveBaseName = "instant-p2p-server"
    archiveClassifier = ""
    destinationDirectory = layout.buildDirectory.dir("libs")

    // 공통 코드(common, relocate된 kwik, i18n)는 리매핑하지 않은 Paper jar의 것을 쓴다
    from(paperFiles.map { files -> files.map { archives.zipTree(it.asFile) } }) {
        exclude("META-INF/MANIFEST.MF")
    }
    from(velocityFiles.map { files -> files.map { archives.zipTree(it.asFile) } }) {
        include("dev/magyul/instantp2p/velocity/**", "velocity-plugin.json")
    }
    // 1.21.x(intermediary로 remap)와 26.x(Mojang 이름) 구현, 버전을 보고 고르는 진입점
    from(fabricFiles.map { files -> files.map { archives.zipTree(it.asFile) } }) {
        include("dev/magyul/instantp2p/fabric/**", "fabric.mod.json", "instant-p2p-server.mixins.json")
    }

    // MC 내부를 이름으로 참조하지 않으므로 Paper의 플러그인 리매핑이 필요 없다 (다른 로더는 무시)
    manifest.attributes("paperweight-mappings-namespace" to "mojang")
    duplicatesStrategy = DuplicatesStrategy.FAIL
}

// 합친 jar가 세 로더 모두에서 로드될 수 있는 모양인지 검사한다
val checkUniversalJar by tasks.registering {
    val jar = universalJar.flatMap { it.archiveFile }
    inputs.file(jar)
    doLast {
        ZipFile(jar.get().asFile).use { zip ->
            fun has(path: String) = zip.getEntry(path) != null
            fun text(path: String) = String(zip.getInputStream(zip.getEntry(path)).readBytes(), Charsets.ISO_8859_1)
            val problems = mutableListOf<String>()

            listOf(
                "plugin.yml", "velocity-plugin.json", "fabric.mod.json", "instant-p2p-server.mixins.json",
                "dev/magyul/instantp2p/paper/PaperEntry.class",
                "dev/magyul/instantp2p/velocity/VelocityEntry.class",
                "dev/magyul/instantp2p/fabric/FabricEntry.class",
                "dev/magyul/instantp2p/fabric/v1_21/FabricImpl.class",
                "dev/magyul/instantp2p/fabric/v26/FabricImpl.class",
                "dev/magyul/instantp2p/fabric/mixin/v1_21/PlayerListMixin.class",
                "dev/magyul/instantp2p/fabric/mixin/v26/PlayerListMixin.class",
                // QUIC — relocate된 이름으로 들어 있어야 한다
                "dev/magyul/instantp2p/libs/kwik/core/QuicConnection.class",
                "dev/magyul/instantp2p/libs/kwik/core/version.properties",
                "i18n/ko.json",
            ).filterNot(::has).forEach { problems += "없음: $it" }

            // paper-plugin.yml이 있으면 Paper와 Spigot 동작이 갈린다
            if (has("paper-plugin.yml")) problems += "paper-plugin.yml이 들어 있다"
            // 서버가 제공하는 라이브러리는 넣지 않는다
            // relocate 안 된 kwik이 있으면 원본 클라이언트 모드와 겹친다
            val bundled = listOf("org/slf4j/", "com/google/gson/", "com/google/common/", "io/netty/",
                "net/kyori/", "org/apache/logging/", "net/minecraft/", "org/bukkit/",
                "tech/kwik/", "at/favre/", "io/whitfin/", "tel/schich/")
            zip.entries().asSequence().map { it.name }
                .filter { name -> bundled.any { name.startsWith(it) } }
                .take(5).forEach { problems += "포함되면 안 되는 클래스: $it" }
            // 1.21.x 구현은 intermediary 이름, 26.x 구현은 Mojang 이름을 참조해야 한다
            if (has("dev/magyul/instantp2p/fabric/v1_21/FabricImpl.class")) {
                val v121 = text("dev/magyul/instantp2p/fabric/v1_21/FabricImpl.class")
                if (!v121.contains("net/minecraft/class_")) problems += "v1_21이 intermediary로 remap되지 않았다"
            }
            if (has("dev/magyul/instantp2p/fabric/v26/FabricImpl.class")) {
                val v26 = text("dev/magyul/instantp2p/fabric/v26/FabricImpl.class")
                if (v26.contains("net/minecraft/class_")) problems += "v26이 intermediary로 remap됐다"
            }

            if (problems.isNotEmpty()) {
                throw GradleException("universal jar 검사 실패:\n  " + problems.joinToString("\n  "))
            }
        }
    }
}

tasks {
    jar { enabled = false }
    assemble { dependsOn(universalJar) }
    check { dependsOn(checkUniversalJar) }
}
