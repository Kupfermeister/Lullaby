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

tasks.processResources {
    val props = mapOf("version" to project.version, "minecraftVersion" to minecraftVersion)
    inputs.properties(props)
    filesMatching("paper-plugin.yml") { expand(props) }
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
        "--enable-native-access=ALL-UNNAMED", // allows native code (LuaJIT, the console library) without warnings
    )
    args("--nogui")
    standardInput = System.`in`
}

// `gradlew docs`: builds the docs site into build/docs (tools/docs/Docs.java; drafts shown: `gradlew docs -Pdrafts`).
// The GitHub job .github/workflows/docs.yml runs the same program on every push and publishes the site.
tasks.register<JavaExec>("docs") {
    classpath = files() // Docs.java is one file: Java runs it straight from the source
    mainClass = "tools/docs/Docs.java"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    args = listOfNotNull("src/main/resources/lullaby/lib", "build/docs", if (project.hasProperty("drafts")) "drafts" else null)
}
