// Fabric 공통부 — MC 클래스를 참조하지 않는다(진입점, 설정, op/밴 목록 파일).
// 1.21.x(intermediary)와 26.x(Mojang 이름) 구현 모듈이 함께 쓴다.
val fabricLoaderVersion: String by project

repositories {
    maven("https://maven.fabricmc.net/")
}

dependencies {
    compileOnly(project(":common"))
    compileOnly("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    compileOnly("org.slf4j:slf4j-api:2.0.9")
    compileOnly("com.google.code.gson:gson:2.10.1")
    // MixinPlugin(버전별 Mixin 선택) — 런타임은 Fabric Loader가 제공
    compileOnly("net.fabricmc:sponge-mixin:0.17.4+mixin.0.8.7")
    compileOnly("org.ow2.asm:asm-tree:9.8")
}
