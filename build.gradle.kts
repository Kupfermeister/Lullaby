import java.net.URI
import java.security.MessageDigest

plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.24"
}

group = "io.github.kupfermeister"
version = "0.1.0-SNAPSHOT"

val minecraftVersion = providers.gradleProperty("minecraftVersion").get()
val paperBuild = providers.gradleProperty("paperBuild").get()
val paperJarSha256 = providers.gradleProperty("paperJarSha256").get()

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

repositories {
    mavenCentral()
}

dependencies {
    // Paper including Minecraft's own server code (NMS). Only the `nms` package may use NMS (see CLAUDE.md).
    paperweight.paperDevBundle("$minecraftVersion.build.$paperBuild-stable")

    // LuaJIT through LuaJava, packed into Lullaby's jar. `natives-desktop` holds LuaJIT for Windows, Linux and macOS.
    implementation("party.iroiro.luajava:luajava:4.1.0")
    implementation("party.iroiro.luajava:luajit:4.1.0")
    runtimeOnly("party.iroiro.luajava:luajit-platform:4.1.0:natives-desktop")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
}

// Downloads vanilla's sounds.json for minecraftVersion from Mojang (each step's SHA-1 checked). The server jar doesn't
// have it (only the game does); SoundSeeds.java reads it for sound_seed(). Downloaded again only on a version change.
abstract class DownloadSounds : DefaultTask() {
    @get:Input abstract val minecraftVersion: Property<String>
    @get:OutputFile abstract val target: RegularFileProperty

    private fun fetch(url: String, sha1: String?): ByteArray {
        val bytes = URI(url).toURL().openStream().use { it.readBytes() }
        if (sha1 != null) {
            val actual = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
            if (actual != sha1) throw GradleException("$url has the wrong SHA-1 ($actual, wanted $sha1)")
        }
        return bytes
    }

    @Suppress("UNCHECKED_CAST")
    private fun json(bytes: ByteArray) = groovy.json.JsonSlurper().parse(bytes) as Map<String, Any?>

    @TaskAction
    fun download() {
        val version = minecraftVersion.get()
        val manifest = json(fetch("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json", null))
        val entry = (manifest["versions"] as List<Map<String, Any?>>).firstOrNull { it["id"] == version }
            ?: throw GradleException("Mojang's version list has no Minecraft $version")
        val versionJson = json(fetch(entry["url"] as String, entry["sha1"] as String))
        val assetIndex = versionJson["assetIndex"] as Map<String, Any?>
        val objects = json(fetch(assetIndex["url"] as String, assetIndex["sha1"] as String))["objects"] as Map<String, Any?>
        val hash = (objects["minecraft/sounds.json"] as Map<String, Any?>)["hash"] as String
        val bytes = fetch("https://resources.download.minecraft.net/${hash.substring(0, 2)}/$hash", hash)
        target.get().asFile.apply { parentFile.mkdirs() }.writeBytes(bytes)
    }
}

val soundsVersion = minecraftVersion // the task's own property has the same name
val downloadSounds = tasks.register<DownloadSounds>("downloadSounds") {
    minecraftVersion = soundsVersion
    target = layout.buildDirectory.file("generated/sounds/lullaby/sounds.json")
}

// `gradlew events`: writes src/main/resources/lullaby/events.txt, every Paper event and its fields, from Paper's API
// jar (tools/events/EventList.java). Part of every build, so the list always matches the Paper version above; it runs
// again only when that version or EventList.java changes. The file is in the repo because the docs site reads it too.
val eventsFile = layout.projectDirectory.file("src/main/resources/lullaby/events.txt")
val paperApiJar = configurations.compileClasspath.map { c -> c.filter { it.name.startsWith("paper-api-") } }
val eventList = tasks.register<JavaExec>("events") {
    classpath = configurations.compileClasspath.get() // Paper's API and its libraries (adventure, ...)
    mainClass = file("tools/events/EventList.java").path // one file: Java runs it straight from the source
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    workingDir = temporaryDir // loading Paper's classes starts its logging, which would make a logs/ folder here
    inputs.file("tools/events/EventList.java")
    inputs.files(paperApiJar)
    outputs.file(eventsFile)
    args(listOf(eventsFile.asFile.path) + paperApiJar.get().files.map { it.path })
}

tasks.processResources {
    dependsOn(eventList)
    val props = mapOf("version" to project.version, "minecraftVersion" to minecraftVersion)
    inputs.properties(props)
    filesMatching("paper-plugin.yml") { expand(props) }
    from(downloadSounds) { into("lullaby") }
}

// One jar with LuaJava inside (no shadow plugin, to keep build dependencies few). Nothing is relocated: each Paper
// plugin has its own class loader, so another plugin's copy of LuaJava cannot clash with ours.
// Quirk: Gradle does not notice changed `exclude` patterns below; after changing them run `gradlew jar --rerun`.
tasks.jar {
    archiveBaseName = "lullaby"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.map { files -> files.map { zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF", "**/module-info.class")
    }
    from("LICENSE")
}

// ---- Test server in run/ (git-ignored) ----

val runDir = layout.projectDirectory.dir("run")
val paperJarName = "paper-$minecraftVersion-$paperBuild.jar"

// Copies the newest plugin jar into run/plugins after every build, removing older Lullaby jars.
val deployToRun = tasks.register<Sync>("deployToRun") {
    from(tasks.jar)
    into(runDir.dir("plugins"))
    preserve { exclude("lullaby-*.jar") } // keep everything else in plugins/ (other plugins, data folders)
}
tasks.jar { finalizedBy(deployToRun) }

// Downloads the Paper jar from fill.papermc.io once and checks its SHA-256.
abstract class DownloadPaper : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputFile abstract val target: RegularFileProperty

    @TaskAction
    fun download() {
        val file = target.get().asFile
        if (!file.exists()) {
            URI(url.get()).toURL().openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        if (actual != sha256.get()) {
            file.delete()
            throw GradleException("${file.name} has the wrong SHA-256 ($actual), deleted it")
        }
    }
}

val downloadPaper = tasks.register<DownloadPaper>("downloadPaper") {
    url = "https://fill-data.papermc.io/v1/objects/$paperJarSha256/$paperJarName"
    sha256 = paperJarSha256
    target = runDir.file(paperJarName)
}

// `gradlew runServer`: builds, copies the jar, starts the test server. Type commands into the same window.
tasks.register<JavaExec>("runServer") {
    dependsOn(deployToRun, downloadPaper)
    workingDir = runDir.asFile
    classpath(runDir.file(paperJarName))
    mainClass = "io.papermc.paperclip.Main" // Paper's launcher class inside the jar
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    jvmArgs(
        "-Xms4G", "-Xmx4G",
        "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", // so ⚠ and other symbols survive the console
        // Gradle hands the server's output on through a pipe, so Paper's console thinks there is no real terminal
        // and drops every color; this keeps them (red errors, yellow warnings)
        "-Dterminal.ansi=true",
        "--enable-native-access=ALL-UNNAMED", // allows native code (LuaJIT, the console library) without warnings
    )
    args("--nogui")
    standardInput = System.`in`
}

// `gradlew docs`: builds the docs site into build/docs (tools/docs/Docs.java).
// The GitHub job .github/workflows/docs.yml runs the same program on every push and publishes the site.
tasks.register<JavaExec>("docs") {
    dependsOn(eventList) // the event pages come from events.txt
    classpath = files() // Docs.java is one file: Java runs it straight from the source
    mainClass = "tools/docs/Docs.java"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    args = listOf("src/main/resources/lullaby/lib", "build/docs")
}
