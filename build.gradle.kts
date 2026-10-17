import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val localPath = providers.gradleProperty("platformLocalPath").orNull
        if (!localPath.isNullOrBlank() && file(localPath).exists()) {
            local(localPath)
        } else {
            androidStudio(providers.gradleProperty("platformVersion"))
        }
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Usa los métodos por defecto de las interfaces de la plataforma en lugar de generar
        // puentes en nuestras clases (el Plugin Verifier los marca como uso de API interna).
        jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.dertoo.jsonmock"
        name = "JSON Mock Server"
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
        changeNotes = providers.gradleProperty("pluginChangeNotes")
    }

    // Firma: requerida por JetBrains Marketplace. Los valores llegan por variables de entorno.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    // Publicación: token personal de https://plugins.jetbrains.com/author/me/tokens
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // "default" = estable. Usa -PpublishChannel=beta para publicar en un canal beta.
        channels = providers.gradleProperty("publishChannel").map { listOf(it) }.orElse(listOf("default"))
    }

    // verifyPlugin: compatibilidad binaria contra las versiones de IDE recomendadas.
    pluginVerification {
        ides {
            // -PverifyLocalOnly=true evita descargar los IDEs recomendados (varios GB).
            if (!providers.gradleProperty("verifyLocalOnly").isPresent) recommended()
            // Verifica también contra tu IntelliJ IDEA instalado (./gradlew verifyPlugin).
            providers.gradleProperty("intellijLocalPath").orNull
                ?.takeIf { file(it).exists() }
                ?.let { local(it) }
        }
    }

    buildSearchableOptions = false
}

// ./gradlew runIntelliJ → abre tu IntelliJ IDEA instalado (sandbox) con el plugin cargado.
// ./gradlew runIde      → lo mismo con la plataforma principal (Android Studio).
intellijPlatformTesting {
    runIde {
        register("runIntelliJ") {
            localPath = providers.gradleProperty("intellijLocalPath").map { file(it) }
        }
    }
}
