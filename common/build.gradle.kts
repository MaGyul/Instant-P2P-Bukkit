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

dependencies {
    provided.forEach {
        compileOnly(it)
        testImplementation(it)
    }
    compileOnly("tel.schich:libdatachannel-java:$libdatachannelVersion")
}
