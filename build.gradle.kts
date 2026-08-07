plugins {
    id("java")
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
}

group = "dev.zypec.izomap"
version = "1.0.0"
description = "Izometrik fotoğraf çekimi ve harita panosu eklentisi"

java {
    // Minecraft/Paper 26.1+ sunucusu Java 25+ ile çalışmayı zorunlu kılar
    // (paperclip Java 22'yi reddeder), bu yüzden derleme araç zinciri de 25'tir.
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper 26.2 dev bundle. 26.1+ itibarıyla sunucu jar'ları obfuscate edilmediği
    // için ayrıca reobf/remap adımı gerekmez; Mojang-mapped çıktı doğrudan üretilir.
    paperweight.paperDevBundle("26.2.build.+")
}

tasks {
    // paper-plugin.yml içindeki ${version} alanını doldurmak için.
    processResources {
        val props = mapOf("version" to project.version.toString())
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
}
