plugins {
    id("com.gradleup.shadow") version "9.2.2" apply false
    id("xyz.jpenilla.run-paper") version "3.1.0" apply false
}

subprojects {
    apply(plugin = "java-library")

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion = JavaLanguageVersion.of(21)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 21
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.11.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // 서버 클래스패스의 log4j 등이 작업 디렉터리에 logs/를 만들 수 있어 build 아래에서 돌린다
        val workDir = layout.buildDirectory.dir("test-work").get().asFile
        workingDir = workDir
        doFirst { workDir.mkdirs() }
    }
}
