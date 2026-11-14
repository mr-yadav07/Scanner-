package com.espforge.ide

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * App-managed local Arduino compiler for ARM64 Android.
 *
 * PRoot and its loader are Bionic executables packaged in jniLibs. The Linux
 * ARM64 Arduino CLI and board compilers execute inside a private Debian rootfs.
 * The user does not install or launch Termux.
 */
internal class NativeToolchainManager(context: Context) {
    private val app = context.applicationContext
    private val runtimeDir = File(app.filesDir, "espforge-runtime")
    private val rootfs = File(runtimeDir, "rootfs")
    private val rootfsMarker = File(runtimeDir, "rootfs.ready")
    private val workspace = File(runtimeDir, "workspace")
    private val cacheDir = File(app.cacheDir, "espforge-runtime")
    private val proot = File(app.applicationInfo.nativeLibraryDir, "libproot.so")
    private val loader = File(app.applicationInfo.nativeLibraryDir, "libproot-loader.so")

    @Volatile private var activeProcess: Process? = null
    @Volatile private var activeHttpProxy: AndroidHttpProxy? = null

    data class CompileResult(val archive: ByteArray, val log: String)
    private data class RunResult(val exitCode: Int, val output: String, val timedOut: Boolean)

    fun cancel() {
        activeProcess?.destroyForcibly()
    }

    fun supportsLocalBoard(boardId: String): Boolean =
        boardId in setOf("arduino_uno", "arduino_nano", "esp8266", "esp32", "esp32_s3", "stm32_bluepill")

    fun prepareBoard(boardId: String, core: String, fqbn: String, onLine: (String) -> Unit = {}) {
        require(supportsLocalBoard(boardId)) {
            "The official WCH package currently has no Linux ARM64 compiler. Use Remote Arduino CLI for CH32V003."
        }
        ensureNativeRuntime()
        ensureWorkspace()
        writeResolverFiles()
        // PRoot guest DNS/UDP is restricted on some Android networks. Route package
        // manager HTTP(S) traffic through Android's native socket/DNS implementation.
        val proxy = AndroidHttpProxy().start()
        activeHttpProxy = proxy
        try {
            val cliReady = runGuest(
                listOf("/bin/bash", "-lc", "command -v arduino-cli >/dev/null 2>&1"),
                timeoutMs = 30_000
            )
            if (cliReady.exitCode != 0) {
                onLine("Installing Debian runtime dependencies and Arduino CLI…")
                runChecked(
                    listOf("/bin/bash", "-lc",
                        "set -e; export DEBIAN_FRONTEND=noninteractive; apt-get update; " +
                        "apt-get install -y ca-certificates curl python3 unzip zip xz-utils"
                    ), 30 * 60_000L, onLine
                )
                val cliArchive = File(workspace, "Downloads/arduino-cli.tar.gz")
                downloadWithRetry(
                    "https://downloads.arduino.cc/arduino-cli/arduino-cli_1.6.0_Linux_ARM64.tar.gz",
                    cliArchive, onLine
                )
                runChecked(
                    listOf("/bin/bash", "-lc",
                        "set -e; tar -xzf /workspace/Downloads/arduino-cli.tar.gz " +
                        "-C /usr/local/bin arduino-cli; chmod 755 /usr/local/bin/arduino-cli"
                    ), 5 * 60_000L, onLine
                )
                cliArchive.delete()
            }
            configureArduinoCli()
            val installed = runGuest(
                listOf("/bin/bash", "-lc",
                    "arduino-cli core list --format json | grep -F '\"id\": \"$core\"'"),
                timeoutMs = 60_000
            )
            if (installed.exitCode != 0) {
                onLine("Updating board indexes for $fqbn…")
                runChecked(listOf("arduino-cli", "core", "update-index"), 10 * 60_000L, onLine)
                onLine("Installing $core and vendor toolchains through Android-backed HTTPS…")
                runChecked(listOf("arduino-cli", "core", "install", core), 60 * 60_000L, onLine)
            } else onLine("Cached board core found: $core")
            onLine("Local toolchain ready: $fqbn")
        } finally {
            activeHttpProxy = null
            proxy.close()
        }
    }

    fun compile(
        boardId: String,
        core: String,
        fqbn: String,
        boardOption: String?,
        project: String,
        files: Map<String, String>,
        libraryZips: List<File>,
        jobs: Int,
        onLine: (String) -> Unit = {}
    ): CompileResult {
        require(supportsLocalBoard(boardId)) {
            "This board has no supported Linux ARM64 compiler package. Use Remote Arduino CLI."
        }
        require(project.matches(Regex("[A-Za-z0-9_-]{1,48}"))) { "Invalid project name." }
        require(files.isNotEmpty() && files.size <= 40 && files.containsKey("main.ino")) {
            "Project must contain main.ino and no more than 40 source files."
        }
        require(files.keys.all { it.matches(Regex("[A-Za-z0-9_.-]{1,64}\\.(ino|cpp|c|h|hpp)")) }) {
            "Project contains an invalid source filename."
        }
        require(files.values.sumOf { it.toByteArray(Charsets.UTF_8).size } <= 350_000) {
            "Project source exceeds 350 KB."
        }

        ensureNativeRuntime()
        ensureWorkspace()
        prepareBoard(boardId, core, fqbn, onLine)

        val boardCache = fqbn.replace(':', '_')
        val sketchDir = File(workspace, "Projects/$project")
        val libDir = File(workspace, "InstalledLibraries")
        val buildDir = File(workspace, "BuildCache/$project/$boardCache")
        val outputDir = File(workspace, "Builds/$project/artifacts")
        sketchDir.mkdirs()
        libDir.mkdirs()
        buildDir.mkdirs()
        outputDir.deleteRecursively()
        outputDir.mkdirs()

        // Preserve unchanged timestamps so Arduino's build cache can reuse objects.
        val expectedNames = files.keys
        sketchDir.listFiles()?.filter {
            it.isFile && it.extension.lowercase() in setOf("ino", "cpp", "c", "h", "hpp")
        }?.filter { it.name !in expectedNames }?.forEach { it.delete() }
        for ((name, source) in files) {
            val target = File(sketchDir, name)
            if (!target.isFile || target.readText(Charsets.UTF_8) != source) {
                target.parentFile?.mkdirs()
                target.writeText(source, Charsets.UTF_8)
            }
        }
        installLocalLibraries(libraryZips, libDir, onLine)

        val args = mutableListOf(
            "arduino-cli", "compile",
            "--fqbn", fqbn,
            "--libraries", "/workspace/InstalledLibraries",
            "--build-path", "/workspace/BuildCache/$project/$boardCache",
            "--output-dir", "/workspace/Builds/$project/artifacts",
            "--jobs", jobs.coerceIn(1, 4).toString(),
            "--warnings", "all"
        )
        if (!boardOption.isNullOrBlank()) {
            args += "--board-options"
            args += boardOption
        }
        args += "/workspace/Projects/$project"

        onLine("Compiling $project for $fqbn with " + jobs.coerceIn(1, 4) + " worker(s)…")
        val result = runGuest(args, timeoutMs = 30 * 60_000L, onLine = onLine)
        if (result.timedOut) throw IllegalStateException("Local compilation timed out after 30 minutes.")
        if (result.exitCode != 0) {
            throw IllegalStateException("Local compilation failed (exit " + result.exitCode + ").\n" + result.output.takeLast(18000))
        }

        val artifacts = outputDir.listFiles()?.filter {
            it.isFile && it.extension.lowercase() in setOf("bin", "hex")
        }.orEmpty()
        require(artifacts.isNotEmpty()) {
            "Compiler exited successfully but did not produce a supported .bin or .hex firmware file."
        }

        val manifest = JSONObject()
            .put("board", boardId)
            .put("fqbn", fqbn)
            .put("project", project)
            .put("runtime", "espforge-app-managed-arm64")
            .put("artifacts", artifacts.map {
                JSONObject().put("name", it.name).put("size", it.length())
            })

        val zipBytes = ByteArrayOutputStream().use { buffer ->
            ZipOutputStream(buffer).use { zip ->
                for (artifact in artifacts) {
                    zip.putNextEntry(java.util.zip.ZipEntry("firmware/" + artifact.name))
                    FileInputStream(artifact).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                zip.putNextEntry(java.util.zip.ZipEntry("build-manifest.json"))
                zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(java.util.zip.ZipEntry("build.log"))
                zip.write(result.output.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            buffer.toByteArray()
        }
        File(workspace, "Builds/$project/firmware.zip").writeBytes(zipBytes)
        onLine("Local build succeeded. " + artifacts.size + " firmware artifact(s) are ready.")
        return CompileResult(zipBytes, result.output)
    }

    private fun ensureNativeRuntime() {
        require(proot.isFile && loader.isFile) {
            "The Android native PRoot runtime is missing from this APK. Rebuild with tools/native/build-proot.sh."
        }
        if (rootfsMarker.isFile && File(rootfs, "usr/bin/dpkg").exists()) return

        runtimeDir.mkdirs()
        cacheDir.mkdirs()
        val manifestText = URL(ROOTFS_MANIFEST_URL).openConnection().apply {
            connectTimeout = 20_000
            readTimeout = 60_000
        }.getInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
        val manifest = JSONObject(manifestText)
        val version = manifest.getString("version")
        val asset = manifest.getString("asset")
        val expectedSha = manifest.getString("sha256").lowercase()
        val expectedSize = manifest.getLong("size")
        require(version == ROOTFS_VERSION && asset == ROOTFS_ARCHIVE_NAME) {
            "The published Android runtime manifest is invalid."
        }
        require(expectedSize in 1..900_000_000L && expectedSha.matches(Regex("[a-f0-9]{64}"))) {
            "The published Android runtime archive metadata is invalid."
        }

        val archive = File(cacheDir, ROOTFS_ARCHIVE_NAME)
        if (!archive.isFile || archive.length() != expectedSize || sha256(archive) != expectedSha) {
            archive.delete()
            val partial = File(cacheDir, "$ROOTFS_ARCHIVE_NAME.part")
            partial.delete()
            val connection = URL(ROOTFS_RELEASE_BASE + "/" + asset).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 300_000
            connection.instanceFollowRedirects = true
            connection.connect()
            require(connection.responseCode in 200..299) {
                "Unable to download local runtime (HTTP " + connection.responseCode + ")."
            }
            connection.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= expectedSize) { "Runtime archive exceeds published size." }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            connection.disconnect()
            require(partial.length() == expectedSize && sha256(partial) == expectedSha) {
                "Local runtime download failed its SHA-256 integrity check."
            }
            check(partial.renameTo(archive)) { "Unable to finalize local runtime archive." }
        }

        val staging = File(runtimeDir, "rootfs.extracting")
        staging.deleteRecursively()
        staging.mkdirs()
        extractRootfs(archive, staging)
        require(File(staging, "bin/sh").exists()) { "Downloaded runtime does not contain /bin/sh." }
        rootfs.deleteRecursively()
        check(staging.renameTo(rootfs)) { "Unable to install Debian runtime into app storage." }
        rootfsMarker.writeText(version + "\n" + expectedSha + "\n", Charsets.UTF_8)
    }

    private fun extractRootfs(archive: File, destination: File) {
        val base = destination.toPath().toAbsolutePath().normalize()
        var entries = 0
        val hardLinks = mutableListOf<Pair<java.nio.file.Path, java.nio.file.Path>>()
        TarArchiveInputStream(
            GzipCompressorInputStream(BufferedInputStream(FileInputStream(archive)))
        ).use { tar ->
            var entry = tar.nextTarEntry
            while (entry != null) {
                val name = entry.name.removePrefix("./").trimStart('/')
                if (name.isNotBlank() && name != ".") {
                    val target = base.resolve(name).normalize()
                    require(target.startsWith(base)) { "Unsafe path in runtime archive." }
                    if (entry.isDirectory) {
                        target.toFile().mkdirs()
                    } else if (entry.isSymbolicLink) {
                        target.parent?.toFile()?.mkdirs()
                        runCatching { Files.deleteIfExists(target) }
                        Files.createSymbolicLink(target, Paths.get(entry.linkName))
                    } else if (entry.isLink) {
                        val link = base.resolve(entry.linkName.removePrefix("./").trimStart('/')).normalize()
                        require(link.startsWith(base)) { "Unsafe hard link in runtime archive." }
                        target.parent?.toFile()?.mkdirs()
                        hardLinks += target to link
                    } else if (entry.isFile) {
                        target.parent?.toFile()?.mkdirs()
                        FileOutputStream(target.toFile()).use { output -> tar.copyTo(output, 64 * 1024) }
                        val mode = entry.mode
                        target.toFile().setExecutable((mode and 0x49) != 0, false)
                        target.toFile().setReadable((mode and 0x124) != 0, false)
                        target.toFile().setWritable((mode and 0x92) != 0, false)
                    }
                }
                entries++
                if (entries % 1500 == 0) android.util.Log.i(TAG, "Extracted " + entries + " runtime entries")
                entry = tar.nextTarEntry
            }
        }
        for ((target, link) in hardLinks) {
            if (Files.exists(link)) Files.copy(link, target)
        }
    }

    private fun writeResolverFiles() {
        val etc = File(rootfs, "etc")
        etc.mkdirs()
        val resolver = File(etc, "resolv.conf")
        runCatching { Files.deleteIfExists(resolver.toPath()) }
        resolver.writeText("nameserver 1.1.1.1\nnameserver 9.9.9.9\n", Charsets.UTF_8)
        File(etc, "hosts").writeText(
            "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n", Charsets.UTF_8
        )
        for (path in listOf("dev", "proc", "sys", "tmp", "workspace", "root")) {
            File(rootfs, path).mkdirs()
        }
        File(rootfs, ".l2s").mkdirs()
        workspace.mkdirs()
        File(cacheDir, "proot-tmp").mkdirs()
    }

    private fun ensureWorkspace() {
        for (path in listOf("Projects", "InstalledLibraries", "LibraryZips", "BuildCache", "Builds", "Downloads")) {
            File(workspace, path).mkdirs()
        }
    }

    private fun configureArduinoCli() {
        val config = File(rootfs, "root/.arduino15/arduino-cli.yaml")
        config.parentFile?.mkdirs()
        val contents = """
            board_manager:
              additional_urls:
                - $ESP32_INDEX
                - $ESP8266_INDEX
                - $STM32_INDEX
            directories:
              data: /root/.arduino15
              downloads: /root/.arduino15/staging
              user: /root/Arduino
        """.trimIndent() + "\n"
        if (!config.isFile || config.readText(Charsets.UTF_8) != contents) {
            config.writeText(contents, Charsets.UTF_8)
        }
    }

    private fun downloadWithRetry(url: String, destination: File, onLine: (String) -> Unit) {
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, destination.name + ".part")
        var lastError: Exception? = null
        for (attempt in 1..5) {
            var connection: HttpURLConnection? = null
            try {
                onLine("Downloading Arduino CLI via Android network (attempt $attempt/5)…")
                partial.delete()
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 25_000
                    readTimeout = 90_000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "ESPForge-IDE/0.5")
                }
                connection.connect()
                val status = connection.responseCode
                if (status !in 200..299) throw java.io.IOException("Arduino CLI server returned HTTP $status")
                connection.inputStream.use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                if (partial.length() < 1_000_000L) {
                    throw java.io.IOException("Arduino CLI archive is unexpectedly small (" + partial.length() + " bytes)")
                }
                if (destination.exists()) destination.delete()
                check(partial.renameTo(destination)) { "Unable to finalize Arduino CLI download." }
                onLine("Arduino CLI archive downloaded (" + destination.length() + " bytes).")
                return
            } catch (e: Exception) {
                lastError = e
                partial.delete()
                if (attempt < 5) {
                    val delay = 1_500L * attempt
                    onLine("Arduino CLI download failed: " + e.message + ". Retrying in " + (delay / 1000) + "s…")
                    Thread.sleep(delay)
                }
            } finally {
                connection?.disconnect()
            }
        }
        throw IllegalStateException(
            "Unable to download Arduino CLI after 5 attempts. Check mobile data/Wi-Fi, Private DNS, " +
                "or try another network. Details: " + (lastError?.message ?: "unknown network error")
        )
    }

    private fun installLocalLibraries(zips: List<File>, libraryDirectory: File, onLine: (String) -> Unit) {
        val markerDir = File(libraryDirectory, ".espforge-cache")
        markerDir.mkdirs()
        for (zip in zips.filter { it.isFile && it.extension.equals("zip", true) }) {
            val digest = sha256(zip)
            val marker = File(markerDir, zip.name + ".sha256")
            if (marker.isFile && marker.readText(Charsets.UTF_8) == digest) continue
            onLine("Installing local library " + zip.name + "…")
            val temp = File(workspace, "Downloads/library-" + digest.take(16))
            temp.deleteRecursively()
            temp.mkdirs()
            ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { input ->
                var entry = input.nextEntry
                while (entry != null) {
                    val safeName = entry.name.replace('\\', '/').removePrefix("./")
                    val base = temp.toPath().toAbsolutePath().normalize()
                    val target = base.resolve(safeName).normalize()
                    require(target.startsWith(base)) { "Unsafe path in library ZIP " + zip.name + "." }
                    if (entry.isDirectory) target.toFile().mkdirs()
                    else {
                        target.parent?.toFile()?.mkdirs()
                        FileOutputStream(target.toFile()).use { output -> input.copyTo(output, 32 * 1024) }
                    }
                    input.closeEntry()
                    entry = input.nextEntry
                }
            }
            val properties = temp.walkTopDown().firstOrNull { it.isFile && it.name == "library.properties" }
            if (properties != null) {
                val source = properties.parentFile
                val destination = File(libraryDirectory, source.name)
                destination.deleteRecursively()
                source.copyRecursively(destination, overwrite = true)
                marker.writeText(digest, Charsets.UTF_8)
            } else {
                onLine("Skipped " + zip.name + ": library.properties was not found.")
            }
            temp.deleteRecursively()
        }
    }

    private fun runChecked(args: List<String>, timeoutMs: Long, onLine: (String) -> Unit = {}) {
        val result = runGuest(args, timeoutMs, onLine)
        if (result.timedOut) throw IllegalStateException("Local toolchain operation timed out.")
        if (result.exitCode != 0) {
            throw IllegalStateException(
                "Local toolchain command failed (exit " + result.exitCode + ").\n" +
                    result.output.takeLast(16000)
            )
        }
    }

    private fun runGuest(
        guestArgs: List<String>,
        timeoutMs: Long,
        onLine: (String) -> Unit = {}
    ): RunResult {
        val command = mutableListOf(
            proot.absolutePath,
            "--kill-on-exit", "--link2symlink", "--sysvipc", "-L", "-0",
            "--rootfs=" + rootfs.canonicalPath,
            "--cwd=/root",
            "--bind=/dev:/dev",
            "--bind=/proc:/proc",
            "--bind=/sys:/sys",
            "--bind=/dev/urandom:/dev/random",
            "--bind=/proc/self/fd:/dev/fd",
            "--bind=" + workspace.canonicalPath + ":/workspace",
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "LOGNAME=root", "SHELL=/bin/bash",
            "TERM=xterm-256color", "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TMPDIR=/tmp",
            "HTTP_PROXY=http://127.0.0.1:" + (activeHttpProxy?.port ?: 0),
            "HTTPS_PROXY=http://127.0.0.1:" + (activeHttpProxy?.port ?: 0),
            "http_proxy=http://127.0.0.1:" + (activeHttpProxy?.port ?: 0),
            "https_proxy=http://127.0.0.1:" + (activeHttpProxy?.port ?: 0),
            "ALL_PROXY=http://127.0.0.1:" + (activeHttpProxy?.port ?: 0),
            "NO_PROXY=127.0.0.1,localhost"
        )
        command += guestArgs
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        builder.environment().apply {
            clear()
            put("PROOT_LOADER", loader.absolutePath)
            put("PROOT_TMP_DIR", File(cacheDir, "proot-tmp").absolutePath)
            put("PROOT_L2S_DIR", File(rootfs, ".l2s").absolutePath)
            put("PATH", "/system/bin")
            put("ANDROID_ROOT", "/system")
            put("ANDROID_DATA", "/data")
        }
        val process = builder.start()
        activeProcess = process
        val output = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    synchronized(output) {
                        output.append(line).append('\n')
                        if (output.length > MAX_LOG_CHARS) output.delete(0, output.length - MAX_LOG_CHARS)
                    }
                    onLine(line)
                }
            }
        }.apply { isDaemon = true; start() }

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(5_000)
        activeProcess = null
        val code = if (finished) process.exitValue() else -1
        return RunResult(code, synchronized(output) { output.toString() }, !finished)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }


/** HTTP CONNECT proxy: Android process performs DNS and outbound TCP for PRoot. */
private class AndroidHttpProxy : java.io.Closeable {
    private val server = java.net.ServerSocket()
    @Volatile private var running = false
    val port: Int get() = server.localPort

    fun start(): AndroidHttpProxy {
        server.reuseAddress = true
        server.bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), 0), 32)
        running = true
        Thread({
            while (running) try {
                val client = server.accept()
                Thread({ handle(client) }, "espforge-proxy-client").apply { isDaemon = true; start() }
            } catch (_: Exception) {
                if (running) android.util.Log.w("EspForgeProxy", "Proxy accept failed")
            }
        }, "espforge-proxy-accept").apply { isDaemon = true; start() }
        return this
    }

    private fun readHeaders(input: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        var state = 0
        while (out.size() < 65536) {
            val b = input.read()
            if (b < 0) break
            out.write(b)
            state = when {
                state == 0 && b == 13 -> 1
                state == 1 && b == 10 -> 2
                state == 2 && b == 13 -> 3
                state == 3 && b == 10 -> 4
                b == 13 -> 1
                else -> 0
            }
            if (state == 4) return out.toString("ISO-8859-1")
        }
        throw java.io.IOException("Invalid or oversized proxy request headers")
    }

    private fun handle(client: java.net.Socket) {
        var upstream: java.net.Socket? = null
        try {
            client.soTimeout = 120_000
            val input = java.io.BufferedInputStream(client.getInputStream())
            val headerText = readHeaders(input)
            val parts = headerText.substringBefore("\r\n").split(' ')
            if (parts.size < 3) throw java.io.IOException("Malformed proxy request")
            if (parts[0].equals("CONNECT", true)) {
                val authority = parts[1]
                val host = authority.substringBeforeLast(':')
                val remotePort = authority.substringAfterLast(':', "443").toIntOrNull() ?: 443
                val remote = java.net.Socket()
                upstream = remote
                remote.connect(java.net.InetSocketAddress(host, remotePort), 30_000)
                remote.soTimeout = 120_000
                client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                client.getOutputStream().flush()
                tunnel(client, remote)
            } else {
                val uri = java.net.URI(parts[1])
                val host = uri.host ?: throw java.io.IOException("URL has no host")
                val remotePort = if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80
                val remote = java.net.Socket()
                upstream = remote
                remote.connect(java.net.InetSocketAddress(host, remotePort), 30_000)
                remote.soTimeout = 120_000
                val path = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
                val rewritten = buildString {
                    append(parts[0]).append(' ').append(path).append(' ').append(parts[2]).append("\r\n")
                    headerText.substringAfter("\r\n").split("\r\n").forEach { line ->
                        if (line.isNotBlank() && !line.startsWith("Proxy-", true)) append(line).append("\r\n")
                    }
                    append("\r\n")
                }
                upstream.getOutputStream().write(rewritten.toByteArray(Charsets.ISO_8859_1))
                upstream.getOutputStream().flush()
                val writer = Thread {
                    runCatching { input.copyTo(remote.getOutputStream()); remote.shutdownOutput() }
                }.apply { isDaemon = true; start() }
                runCatching { remote.getInputStream().copyTo(client.getOutputStream()) }
                writer.join(120_000)
            }
        } catch (e: Exception) {
            runCatching { client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII)) }
            android.util.Log.w("EspForgeProxy", "Proxy request failed: \${e.message}")
        } finally {
            runCatching { upstream?.close() }
            runCatching { client.close() }
        }
    }

    private fun tunnel(client: java.net.Socket, upstream: java.net.Socket) {
        val writer = Thread {
            runCatching { client.getInputStream().copyTo(upstream.getOutputStream()); upstream.shutdownOutput() }
        }.apply { isDaemon = true; start() }
        runCatching { upstream.getInputStream().copyTo(client.getOutputStream()) }
        writer.join(120_000)
    }

    override fun close() {
        running = false
        runCatching { server.close() }
    }
}

    companion object {
        private const val TAG = "EspForgeNativeRuntime"
        private const val MAX_LOG_CHARS = 160_000
        private const val ROOTFS_VERSION = "1"
        private const val ROOTFS_ARCHIVE_NAME = "debian-arm64-rootfs.tar.gz"
        private const val ROOTFS_RELEASE_BASE =
            "https://github.com/mr-yadav07/Scanner-/releases/download/forge-runtime-v1"
        private const val ROOTFS_MANIFEST_URL = "$ROOTFS_RELEASE_BASE/runtime-manifest.json"
        private const val ESP32_INDEX = "https://espressif.github.io/arduino-esp32/package_esp32_index.json"
        private const val ESP8266_INDEX = "https://arduino.esp8266.com/stable/package_esp8266com_index.json"
        private const val STM32_INDEX =
            "https://github.com/stm32duino/BoardManagerFiles/raw/main/package_stmicroelectronics_index.json"
    }
}
