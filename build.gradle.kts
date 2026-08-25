plugins {
    kotlin("jvm") version "2.1.0"
    java
}

group = "org.combustiblelemon"
version = "0.1.0"

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

sourceSets {
    main {
        kotlin {
            srcDirs("src")
        }
        java {
            srcDirs("src")
        }
        resources {
            srcDirs("data")
        }
    }
}

dependencies {
    implementation(kotlin("stdlib"))

    // Starsector API
    compileOnly(files("../../starsector-core/starfarer.api.jar"))
    compileOnly(files("../../starsector-core/fs.common_obf.jar"))
    compileOnly(files("../../starsector-core/starfarer_obf.jar"))

    // LWJGL for Vector2f and GL classes
    compileOnly(files("../../starsector-core/lwjgl.jar"))
    compileOnly(files("../../starsector-core/lwjgl_util.jar"))

    // Log4j for logging (Starsector standard)
    compileOnly("log4j:log4j:1.2.17")

    // Console Commands (optional runtime dependency — only the ShaderLayers command uses it)
    compileOnly(files("../Console Commands/jars/lw_Console.jar"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }
}

// Task to build the mod jar
tasks.register<Jar>("buildMod") {
    dependsOn("classes")
    archiveFileName.set("LemonLib.jar")
    destinationDirectory.set(file("jars"))

    // Windows: the previous jar may be locked by a JVM (Gradle/Kotlin daemon,
    // IDE language server, the game). A locked file cannot be overwritten, but
    // it can usually be renamed — so move it aside and let the new jar take its
    // place. Stale copies are cleaned up on later builds once their holders exit.
    doFirst {
        val out = destinationDirectory.get().file(archiveFileName.get()).asFile
        out.parentFile?.listFiles()?.filter { it.name.contains(".stale-") }?.forEach { it.delete() }
        if (out.exists()) {
            // Rename first: it frees the file NAME immediately even when a
            // share-delete handle would leave a deleted file's name occupied.
            val stale = File(out.parentFile, "${out.name}.stale-${System.currentTimeMillis()}")
            if (!out.renameTo(stale) && !out.delete()) {
                throw GradleException(
                    "${out.name} is locked and could not be replaced or renamed. " +
                        "Close the game/IDE holding it or run 'gradlew --stop', then rebuild."
                )
            }
        }
    }

    from(sourceSets.main.get().output)

    // Include mod resources
    from("data") {
        into("data")
    }

    // Include mod_info.json
    from("mod_info.json")

    manifest {
        attributes(
            "Implementation-Title" to "LemonLib",
            "Implementation-Version" to version
        )
    }
}

// Task to clean build artifacts
tasks.register("cleanMod") {
    dependsOn("clean")
    doLast {
        delete("jars")
    }
}
