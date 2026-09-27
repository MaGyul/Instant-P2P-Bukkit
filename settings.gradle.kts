pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        gradlePluginPortal()
    }
}

rootProject.name = "instant-p2p-bukkit"

include("common", "paper", "fabric")
