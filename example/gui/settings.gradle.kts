pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "monero-lws-library-gui"

includeBuild("../../library") {
    dependencySubstitution {
        substitute(module("org.monero.lws.lib:monero-lws-library:1.0.0")).using(project(":"))
    }
}
