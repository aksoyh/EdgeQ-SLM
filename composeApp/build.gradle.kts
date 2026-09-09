import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    android()
    
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(project(":shared"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.coil.compose)
        }
        getByName("androidInstrumentedTest").dependencies {
            implementation(libs.junit)
            implementation(libs.androidx.test.ext.junit)
            implementation(libs.androidx.test.runner)
        }
    }
}

android {
    namespace = "com.aksoyapps.edgeqslm"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aksoyapps.edgeqslm"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1-thesis-recovery"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "com.aksoyapps.edgeqslm.validation.M1ValidationRunner"
    }
    testBuildType = "validation"
    buildTypes {
        create("validation") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".validation"
            matchingFallbacks += "debug"
        }
        create("thesisValidation") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".thesisvalidation"
            matchingFallbacks += "debug"
        }
        create("releaseSmall") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
        }
        create("demoFull") {
            initWith(getByName("release"))
            applicationIdSuffix = ".demo"
            matchingFallbacks += "release"
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    androidResources {
        noCompress += listOf("onnx", "bin", "gguf", "json")
    }
    if (providers.gradleProperty("thesisDemoValidation").orNull == "true") {
        testBuildType = "thesisValidation"
        defaultConfig.testInstrumentationRunner = "com.aksoyapps.edgeqslm.validation.ThesisDemoValidationRunner"
    }
    if (providers.gradleProperty("thesisRecoveryValidation").orNull == "true") {
        testBuildType = "thesisValidation"
        defaultConfig.testInstrumentationRunner = "com.aksoyapps.edgeqslm.validation.ThesisRecoveryValidationRunner"
    }
}

abstract class PrepareDemoFullModelAssets : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
}

val bundledDescriptor = file("src/demoFull/model_payloads.json")
@Suppress("UNCHECKED_CAST")
val bundledModels = (JsonSlurper().parse(bundledDescriptor) as Map<String, Any>)["models"] as List<Map<String, Any>>
val prepareDemoFullModels by tasks.registering(PrepareDemoFullModelAssets::class) {
    inputs.file(bundledDescriptor)
    inputs.files(bundledModels.map { rootProject.file(it.getValue("source")) })
    outputDirectory.set(layout.buildDirectory.dir("generated/demoFullModelAssets"))
    doLast {
        fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        check(bundledModels.size == 6 && bundledModels.map { it["id"] }.toSet().size == 6)
        val output = outputDirectory.get().dir("thesis_models").asFile
        check(output.isDirectory || output.mkdirs())
        bundledModels.forEach { item ->
            val source = rootProject.file(item.getValue("source"))
            val filename = item.getValue("filename").toString()
            check(filename == File(filename).name && filename !in setOf(".", "..", "manifest.json"))
            check(source.canonicalPath.startsWith(rootProject.projectDir.canonicalPath + File.separator))
            val expectedSize = (item.getValue("size_bytes") as Number).toLong()
            val expectedHash = item.getValue("sha256").toString()
            check(source.length() == expectedSize && hash(source) == expectedHash) { "Bundled source identity mismatch: ${item["id"]}" }
            val target = File(output, filename)
            if (!target.isFile || target.length() != expectedSize || hash(target) != expectedHash) {
                source.copyTo(target, overwrite = true)
            }
            check(target.length() == expectedSize && hash(target) == expectedHash) { "Bundled copy identity mismatch: ${item["id"]}" }
        }
        val manifest = mapOf("schema_version" to 1, "models" to bundledModels.map { item ->
            mapOf("id" to item.getValue("id"), "asset_path" to "thesis_models/${item.getValue("filename")}",
                "size_bytes" to item.getValue("size_bytes"), "sha256" to item.getValue("sha256"))
        })
        File(output, "manifest.json").writeText(JsonOutput.toJson(manifest))
        check(output.listFiles()!!.map { it.name }.toSet() == bundledModels.map { it["filename"].toString() }.toSet() + "manifest.json") {
            "Unexpected bundled asset membership"
        }
    }
}
androidComponents {
    onVariants(selector().withBuildType("demoFull")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(prepareDemoFullModels, PrepareDemoFullModelAssets::outputDirectory)
    }
    if (providers.gradleProperty("thesisBundledValidation").orNull == "true") {
        onVariants(selector().withBuildType("thesisValidation")) { variant ->
            variant.sources.assets?.addGeneratedSourceDirectory(prepareDemoFullModels, PrepareDemoFullModelAssets::outputDirectory)
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "1.8"
    }
}
