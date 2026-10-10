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

    // Code only: Starsector reads data/ and mod_info.json from the mod folder (the classpath is only
    // its last fallback), so copies in the jar were dead weight (2026-10-10, as Covenant Remnants).
    from(sourceSets.main.get().output)

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

// Release packaging (developer rulings 2026-10-10, same shape as Covenant Remnants): what the game
// needs to run plus the modder docs, inside one "LemonLib/" folder so players unzip it straight into
// mods/. The single file list for releases; package_mod.bat / package_mod.sh just run packageMod.
val modFolderName = "LemonLib"

val releaseFiles: CopySpec = copySpec {
    from(".") {
        include("mod_info.json")
        // Version Checker: data/config/version/version_files.csv points at this root file;
        // shipping the CSV without it makes LunaLib's loadJSON throw at startup.
        include("*.version")
        include("data/**")
        // Not the jars/*.stale-* copies left by locked rebuilds
        include("jars/LemonLib.jar")
        // Library docs for modders (README links into docs/)
        include("README.md", "docs/**")
    }
    includeEmptyDirs = false
}

// Fresh jar + release files in build/mod_package/LemonLib/ (Sync drops files from older runs)
val stageMod = tasks.register<Sync>("stageMod") {
    dependsOn("buildMod")
    into(layout.buildDirectory.dir("mod_package/$modFolderName"))
    with(releaseFiles)
}

// "LemonLib.zip" in the project root (git-ignored), holding the staged folder only
tasks.register<Zip>("packageMod") {
    archiveFileName.set("$modFolderName.zip")
    destinationDirectory.set(layout.projectDirectory)
    from(stageMod) {
        into(modFolderName)
    }
    doLast {
        println("Mod packaged to ${archiveFile.get().asFile}")
    }
}
