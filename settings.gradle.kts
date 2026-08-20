pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("libs.versions.toml"))
        }
    }
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.supersanta.me/snapshots")
        mavenCentral()
    }
}

rootProject.name = "serenitea-pot"
