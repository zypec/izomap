import java.util.zip.ZipFile

plugins {
    id("java")
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.24"
    id("xyz.jpenilla.run-paper") version "3.1.0"
    id("com.gradleup.shadow") version "9.2.2"
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

val wiki: SourceSet by sourceSets.creating

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://zypec.github.io/maven") {
        content { includeGroup("dev.zypec") }
    }
}

// Tests touch the plugin's Bukkit-facing types (Player and the like); paperweight puts the
// server on compileOnly, so the test classpath has to inherit it.
configurations.testImplementation {
    extendsFrom(configurations.compileOnly.get())
}

dependencies {
    // Since 26.1 the server jar is not obfuscated, so there is no reobf step.
    paperweight.paperDevBundle("$minecraftVersion.build.+")

    // The web editor's relay link, tunnel and relay, shared with Particles+. A sibling checkout of
    // ../editor-kit takes its place (settings.gradle.kts).
    implementation("dev.zypec:editor-kit:1.0.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    "wikiImplementation"("org.commonmark:commonmark:0.30.1")
    "wikiImplementation"("org.commonmark:commonmark-ext-gfm-tables:0.30.1")
    "wikiImplementation"("org.yaml:snakeyaml:2.7")
    "wikiImplementation"("com.google.code.gson:gson:2.14.0")
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

    /**
     * The plugin jar carries the editor kit inside its own package; the relay's worker stays out,
     * since only the editor's site needs it.
     */
    shadowJar {
        archiveClassifier = ""
        relocate("dev.zypec.editorkit", "dev.zypec.izomap.lib.editorkit")
        exclude("editorkit/relay/**")
        dependencies {
            include(dependency("dev.zypec:editor-kit"))
        }
    }

    jar {
        archiveClassifier = "plain"
    }

    /**
     * What the relay serves: the editor's page and the kit's tunnel beside it under kit/, and the
     * kit's worker in build/relay. relay/wrangler.toml runs this before every deploy.
     */
    register("editorSite") {
        group = "izomap"
        description = "Writes the web editor's site to build/editor-site and the relay to build/relay."
        val kitJar = configurations.runtimeClasspath.map { classpath ->
            classpath.filter { it.name.startsWith("editor-kit") }
        }
        val page = layout.projectDirectory.dir("src/main/resources/editor")
        val site = layout.buildDirectory.dir("editor-site")
        val relay = layout.buildDirectory.dir("relay")
        inputs.files(kitJar)
        inputs.dir(page)
        outputs.dirs(site, relay)
        doLast {
            val out = site.get().asFile.apply { deleteRecursively() }
            val worker = relay.get().asFile.apply { deleteRecursively() }
            page.asFile.copyRecursively(out)
            ZipFile(kitJar.get().singleFile).use { jar ->
                for (entry in jar.entries()) {
                    if (entry.isDirectory) continue
                    val target = when {
                        entry.name.startsWith("editorkit/web/") -> File(out, "kit/" + entry.name.removePrefix("editorkit/web/"))
                        entry.name == "editorkit/relay/worker.js" -> File(worker, "worker.js")
                        else -> continue
                    }
                    target.parentFile.mkdirs()
                    jar.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                }
            }
        }
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
     * The site: front page and wiki, written to build/site. The wiki is drawn with the shared kit
     * from zypec/zypec.github.io, read from the sibling checkout or from -Pkit=<its kit folder>.
     */
    register<JavaExec>("generateWiki") {
        group = "documentation"
        description = "Builds the front page and the wiki into build/site."
        mainClass = "dev.zypec.izomap.wiki.WikiGenerator"
        classpath = wiki.runtimeClasspath
        val site = layout.buildDirectory.dir("site").get().asFile
        val kit = providers.gradleProperty("kit").orElse(file("../zypec.github.io/kit").absolutePath)
        args(site.absolutePath)
        systemProperty("izomap.root", projectDir.absolutePath)
        systemProperty("izomap.kit", file(kit.get()).absolutePath)
        systemProperty("izomap.version", project.version.toString())
        doFirst { site.deleteRecursively() }
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
