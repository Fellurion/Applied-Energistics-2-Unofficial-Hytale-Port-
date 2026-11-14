plugins {
    java
}

group = "de.fellurion"
version = providers.gradleProperty("mod_version").get()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
}

// Hytale-Installation: Standard ist der macOS-Pfad, sonst hytaleHome in ~/.gradle/gradle.properties setzen
val userHome = System.getProperty("user.home")
val hytaleHome = providers.gradleProperty("hytaleHome")
    .orElse("$userHome/Library/Application Support/Hytale/install/release/package/game/latest").get()
val ftcoreDir = providers.gradleProperty("ftcoreDir")
    .orElse("$userHome/Library/Application Support/Hytale/UserData/Mods").get()
val hytaleServerJar = file("$hytaleHome/Server/HytaleServer.jar")

dependencies {
    // Hytale-Server-API: wird zur Laufzeit vom Server bereitgestellt
    compileOnly(files(hytaleServerJar))
    // FTCore (optional): eigene Oberfläche (Schale, Inventar daneben, Drag & Drop), falls installiert
    compileOnly(fileTree(ftcoreDir) { include("FTCore-*.jar") })
    testImplementation(files(hytaleServerJar))

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    filesMatching("manifest.json") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveBaseName.set("AppliedEnergistics2-Hytale")
    // Lizenztexte und Credits müssen bei jeder Weitergabe dabei sein (LGPL-3.0, MIT, CC BY-NC-SA 3.0)
    from("LICENSE", "LICENSE-API.md", "LICENSE-ASSETS.md", "NOTICE.md")
}

// ---------------------------------------------------------------------------------------------
// Optional: lokales Deploy in die eigene Hytale-Installation. Nur aktiv, wenn scripts/deploy.sh
// vorhanden ist (die Skripte liegen nicht im Repository).
//   ./gradlew deployMod   -> bauen und in UserData/Mods legen
//   ./gradlew deploy      -> zusätzlich den Server auf einer Test-Welt starten
// ---------------------------------------------------------------------------------------------
if (file("scripts/deploy.sh").exists()) {
    val deployMod by tasks.registering(Exec::class) {
        group = "hytale"
        description = "Prüft die Assets und legt das Mod-Jar in UserData/Mods (alte Version -> Mods-backup)"
        dependsOn(tasks.jar)
        workingDir = projectDir
        commandLine("scripts/deploy.sh", "--no-build")
    }

    // Jeder Build legt das Jar automatisch in Hytale ab (abschalten mit -PnoDeploy)
    tasks.build {
        if (!providers.gradleProperty("noDeploy").isPresent) {
            finalizedBy(deployMod)
        }
    }

    tasks.register<Exec>("deploy") {
        group = "hytale"
        description = "Jar deployen und Hytale-Server auf der Test-Welt starten"
        dependsOn(deployMod)
        workingDir = projectDir
        standardInput = System.`in`
        commandLine(
            "scripts/run-server.sh",
            "--world", providers.gradleProperty("hytaleWorld").orElse("test3").get(),
            "--port", providers.gradleProperty("hytalePort").orElse("5520").get(),
            "--auth-mode", providers.gradleProperty("hytaleAuthMode").orElse("AUTHENTICATED").get(),
        )
    }
}
