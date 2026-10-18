plugins {
    id("java")
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.24"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "dev.zypec.izomap"
version = "1.0.0"
description = "Isometric photography for Minecraft servers, printed onto maps"

/** The Paper release the plugin is built against and the development server runs. */
val minecraftVersion = "26.3"

java {
    // Paper 26.1+ refuses to start on anything older than Java 25.
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// Tests touch the plugin's Bukkit-facing types (Player and the like); paperweight puts the
// server on compileOnly, so the test classpath has to inherit it.
configurations.testImplementation {
    extendsFrom(configurations.compileOnly.get())
}

dependencies {
    // Since 26.1 the server jar is not obfuscated, so there is no reobf step.
    paperweight.paperDevBundle("$minecraftVersion.build.+")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    processResources {
        val props = mapOf("version" to project.version.toString(), "apiVersion" to minecraftVersion)
        inputs.properties(props)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }

    compileJava {
        options.encoding = "UTF-8"
        options.release = 25
    }

    javadoc {
        options.encoding = "UTF-8"
    }

    test {
        useJUnitPlatform()
        testLogging {
            events("failed")
            showStandardStreams = false
        }
    }

    /**
     * The development server: `run/` beside the project, not in git. `-Dizomap.dev=true` turns on
     * the console-only tools the docs' sample photos are shot with.
     */
    runServer {
        minecraftVersion(minecraftVersion)
        runDirectory = layout.projectDirectory.dir("run")
        jvmArgs("-Xms2G", "-Xmx6G", "-Dizomap.dev=true", "-Dcom.mojang.eula.agree=true")
    }
}
