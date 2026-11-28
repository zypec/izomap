pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

rootProject.name = "izomap"

// The editor kit from a sibling checkout, or from -PeditorKit=<folder>, in place of the published
// one, so a change to it is tried here before it is published. Without either, the published jar.
val editorKit = providers.gradleProperty("editorKit").orNull?.let(::file) ?: file("../editor-kit")
if (File(editorKit, "settings.gradle.kts").isFile) includeBuild(editorKit)
