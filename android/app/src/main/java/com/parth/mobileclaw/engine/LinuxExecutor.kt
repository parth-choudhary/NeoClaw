package com.parth.mobileclaw.engine

import android.content.Context
import android.util.Log
import com.parth.mobileclaw.models.ToolResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.URL
import java.util.UUID

/**
 * Native Linux execution engine using ProcessBuilder + proot + Alpine rootfs.
 * Replaces the iOS QEMU VM — commands execute at near-native speed.
 *
 * Architecture:
 * - Alpine Linux mini rootfs extracted to app's internal storage
 * - proot binary bundled as a native lib (arm64-v8a)
 * - Commands run via: proot -r <rootfs> /bin/sh -c "<command>"
 * - User's Documents dir is bind-mounted into the rootfs
 */
class LinuxExecutor(private val context: Context) {

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady

    private val _bootLogs = MutableStateFlow<List<String>>(emptyList())
    val bootLogs: StateFlow<List<String>> = _bootLogs

    private val rootfsDir: File get() = File(context.filesDir, "alpine-rootfs")
    private val nativeLibDir: File get() = File(context.applicationInfo.nativeLibraryDir)
    private val prootBin: File get() = File(nativeLibDir, "libproot.so")
    private val loaderFile: File get() = File(nativeLibDir, "libproot_loader.so")
    private val loader32File: File get() = File(nativeLibDir, "libproot_loader32.so")
    private val documentsDir: File get() = File(context.getExternalFilesDir(null), "Documents").also { it.mkdirs() }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "LinuxExecutor"
        private const val COMMAND_TIMEOUT_MS = 60_000L
    }

    /**
     * Initialize the Linux environment. Downloads Alpine rootfs on first run.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        log("Initializing Linux executor...")
        log("Native lib dir: ${nativeLibDir.absolutePath}")

        // --- Rootfs setup ---
        if (!rootfsDir.exists()) {
            log("First launch — extracting bundled Alpine Linux rootfs...")
            try {
                extractBuiltinRootfs()
                setupRootfs()
            } catch (e: Exception) {
                log("ERROR: Failed to set up rootfs: ${e.message}")
                return@withContext
            }
        } else {
            log("Alpine rootfs found at ${rootfsDir.absolutePath}")
        }

        // Verify rootfs integrity (check for busybox instead of sh to avoid symlink issues)
        if (!File(rootfsDir, "bin/busybox").exists()) {
            log("ERROR: Rootfs corrupted (/bin/busybox missing). Re-extracting...")
            try {
                rootfsDir.deleteRecursively()
                extractBuiltinRootfs()
                setupRootfs()
            } catch (e: Exception) {
                log("ERROR: Re-extraction failed: ${e.message}")
                return@withContext
            }
            // Verify again after re-extraction
            if (!File(rootfsDir, "bin/busybox").exists()) {
                log("ERROR: /bin/busybox still missing after re-extraction. Giving up.")
                return@withContext
            }
        }

        // --- Verify proot binary ---
        if (!prootBin.exists()) {
            log("WARNING: proot binary not found at ${prootBin.absolutePath}")
            log("Using fallback: direct shell execution (limited)")
        } else {
            log("proot binary: ${prootBin.absolutePath}")
        }

        // Verify loader files
        if (loaderFile.exists()) {
            log("proot loader: ${loaderFile.absolutePath}")
        } else {
            log("WARNING: proot loader not found at ${loaderFile.absolutePath}")
        }
        if (loader32File.exists()) {
            log("proot loader32: ${loader32File.absolutePath}")
        }

        // Ensure Documents dir and cache dir exist
        documentsDir.mkdirs()
        context.cacheDir.mkdirs()
        log("Documents dir: ${documentsDir.absolutePath}")

        // --- Self-test: actually run proot to verify it works ---
        if (prootBin.exists() && rootfsDir.exists()) {
            log("Running self-test...")
            try {
                val testResult = runInternal("echo proot_ok")
                if (testResult.exitCode == 0 && testResult.output.contains("proot_ok")) {
                    log("Self-test passed ✓")
                } else {
                    log("WARNING: Self-test returned exit=${testResult.exitCode}: ${testResult.output}")
                }
            } catch (e: Exception) {
                log("WARNING: Self-test exception: ${e.message}")
            }
        }

        _isReady.value = true
        log("✅ Linux executor ready!")
    }

    /**
     * Execute a shell command in the Alpine Linux environment.
     */
    suspend fun run(command: String): ToolResult = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()

        if (!_isReady.value) {
            return@withContext ToolResult(
                id = id,
                output = "Linux environment not ready yet",
                exitCode = 1,
                isError = true
            )
        }

        try {
            runInternal(command).copy(id = id)
        } catch (e: Exception) {
            Log.e(TAG, "Command execution failed", e)
            ToolResult(
                id = id,
                output = "Execution error: ${e.message}",
                exitCode = 1,
                isError = true
            )
        }
    }

    private suspend fun runInternal(command: String): ToolResult = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()

        try {
            val processBuilder = if (prootBin.exists() && rootfsDir.exists()) {
                val cmdList = mutableListOf(
                    prootBin.absolutePath,
                    "-0", // Fake root
                    "-r", rootfsDir.absolutePath,
                    "-w", "/root",
                    "-b", "/dev",
                    "-b", "/proc",
                    "-b", "/sys",
                    "-b", "${documentsDir.absolutePath}:/root/Documents",
                    "-b", nativeLibDir.absolutePath,
                    "-b", context.cacheDir.absolutePath,
                    "-b", "/system",
                    "-b", "/apex",
                    "-b", "/data/data",
                    "--kill-on-exit",
                    "/bin/sh", "-c", command
                )
                
                ProcessBuilder(cmdList)
            } else {
                ProcessBuilder("/system/bin/sh", "-c", command)
            }

            processBuilder.redirectErrorStream(true)
            processBuilder.environment()["HOME"] = "/root"
            processBuilder.environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            processBuilder.environment()["TERM"] = "xterm"
            processBuilder.environment()["PROOT_NO_SECCOMP"] = "1"
            
            // Critical: Remove Android's native library paths so they don't leak into Alpine's musl dynamic linker.
            // When building 'apk', it links libcrypto.so. If LD_LIBRARY_PATH contains Android's paths,
            // musl loads Bionic's libcrypto.so and instantly crashes with exit code 255.
            processBuilder.environment().remove("LD_LIBRARY_PATH")
            processBuilder.environment().remove("LD_PRELOAD")

            // proot requires its loader binaries and a writable tmp dir
            if (loaderFile.exists()) {
                processBuilder.environment()["PROOT_LOADER"] = loaderFile.absolutePath
            }
            if (loader32File.exists()) {
                processBuilder.environment()["PROOT_LOADER_32"] = loader32File.absolutePath
            }
            processBuilder.environment()["PROOT_TMP_DIR"] = context.cacheDir.absolutePath

            val process = processBuilder.start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            val output = StringBuilder()
            val job = scope.launch {
                try {
                    reader.forEachLine { line ->
                        output.appendLine(line)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Output read error: ${e.message}")
                }
            }

            // Wait with timeout
            val completed = withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { process.waitFor() }
            }

            if (completed == null) {
                process.destroyForcibly()
                job.cancel()
                return@withContext ToolResult(
                    id = id,
                    output = output.toString() + "\n[Command timed out after ${COMMAND_TIMEOUT_MS / 1000}s]",
                    exitCode = 124,
                    isError = true
                )
            }

            job.join()
            val exitCode = process.exitValue()

            // In self test, log the full output since it has environment details
            if (command == "echo proot_ok") {
                log("Self-test diagnostic output:\n$output")
            }

            ToolResult(
                id = id,
                output = output.toString().trimEnd(),
                exitCode = exitCode,
                isError = exitCode != 0
            )
        } catch (e: Exception) {
            log("runInternal EXCEPTION: ${e.stackTraceToString()}")
            throw e
        }
    }

    /**
     * Install a package using apk.
     */
    suspend fun installPackage(name: String): ToolResult {
        return run("apk add --no-cache $name")
    }

    // MARK: - Rootfs Setup

    private suspend fun extractBuiltinRootfs() = withContext(Dispatchers.IO) {
        log("Copying bundled Alpine mini rootfs to cache...")

        val baseName = "alpine-minirootfs-3.21.6-aarch64"
        var assetName = "$baseName.tar.gz"
        var isGz = true
        var assetInput = try {
            context.assets.open(assetName)
        } catch (e: Exception) {
            null
        }
        
        if (assetInput == null) {
            assetName = "$baseName.tar"
            isGz = false
            assetInput = context.assets.open(assetName)
        }

        val tarFile = File(context.cacheDir, if (isGz) "alpine-rootfs.tar.gz" else "alpine-rootfs.tar")

        // Copy from assets
        assetInput.use { input ->
            tarFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        // Extract
        log("Extracting rootfs...")
        rootfsDir.mkdirs()

        val process = ProcessBuilder(
            "/system/bin/tar", if (isGz) "xzf" else "xf", tarFile.absolutePath, "-C", rootfsDir.absolutePath
        ).redirectErrorStream(true).start()

        // Capture tar output/errors
        val tarOutput = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        tarFile.delete()

        if (exitCode != 0) {
            log("ERROR: tar extraction failed (exit=$exitCode): $tarOutput")
            throw RuntimeException("tar extraction failed with exit code $exitCode: $tarOutput")
        }

        // Fix Toybox tar symlink extraction issues
        try {
            val shFile = File(rootfsDir, "bin/sh")
            if (!shFile.exists() || shFile.length() == 0L) {
                shFile.delete()
                android.system.Os.symlink("busybox", shFile.absolutePath)
                log("Created missing symlink: /bin/sh -> busybox")
            }

            val ldMuslFile = File(rootfsDir, "lib/ld-musl-aarch64.so.1")
            if (!ldMuslFile.exists() || ldMuslFile.length() == 0L) {
                ldMuslFile.parentFile?.mkdirs()
                ldMuslFile.delete()
                android.system.Os.symlink("libc.musl-aarch64.so.1", ldMuslFile.absolutePath)
                log("Created missing symlink: /lib/ld-musl-aarch64.so.1 -> libc.musl-aarch64.so.1")
            }
            
            val envFile = File(rootfsDir, "usr/bin/env")
            if (!envFile.exists() || envFile.length() == 0L) {
                envFile.parentFile?.mkdirs()
                envFile.delete()
                android.system.Os.symlink("../../bin/busybox", envFile.absolutePath)
            }
        } catch (e: Exception) {
            log("WARNING: Failed to create critical symlinks: ${e.message}")
        }

        log("Rootfs extracted to ${rootfsDir.absolutePath}")
    }

    private suspend fun setupRootfs() = withContext(Dispatchers.IO) {
        log("Configuring Alpine rootfs...")

        // Set up DNS
        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        resolvConf.parentFile?.mkdirs()
        resolvConf.writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")

        // Create /root directory
        File(rootfsDir, "root").mkdirs()

        // Create Documents symlink target
        File(rootfsDir, "root/Documents").mkdirs()

        // Set up a simple profile
        File(rootfsDir, "root/.profile").writeText(
            """
            export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            export HOME="/root"
            export PS1="# "
            """.trimIndent() + "\n"
        )

        // Set up apk repositories
        val reposFile = File(rootfsDir, "etc/apk/repositories")
        reposFile.parentFile?.mkdirs()
        reposFile.writeText(
            """
            https://dl-cdn.alpinelinux.org/alpine/v3.21/main
            https://dl-cdn.alpinelinux.org/alpine/v3.21/community
            """.trimIndent() + "\n"
        )

        log("Rootfs configuration complete")
    }

    // MARK: - Logging

    private fun log(message: String) {
        Log.d(TAG, message)
        _bootLogs.value = _bootLogs.value + "[${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())}] $message"
    }
}
