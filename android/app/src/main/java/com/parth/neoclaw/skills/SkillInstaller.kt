package com.parth.neoclaw.skills

import android.content.Context
import android.util.Log
import com.parth.neoclaw.engine.LinuxExecutor
import com.parth.neoclaw.models.Skill
import com.parth.neoclaw.models.SkillMetadata
import com.parth.neoclaw.models.SkillRequirements
import com.parth.neoclaw.models.SkillSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Installs skills from various sources into the user's skills directory.
 *
 * Downloads GitHub/GitLab repos as tarballs using Java's HTTP client and extracts
 * them using Android's native /system/bin/tar. This avoids running wget/git/apk
 * inside proot, which crashes with exit 255 due to SELinux restrictions on the
 * untrusted_app domain.
 */
class SkillInstaller(
    private val linuxExecutor: LinuxExecutor,
    private val skillsDirectory: File
) {
    // Context for cache dir access — derived from skillsDirectory's parent
    private val cacheDir: File get() = File(skillsDirectory.parentFile, "cache")

    companion object {
        private const val TAG = "SkillInstaller"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
    }

    init {
        skillsDirectory.mkdirs()
    }

    /**
     * Install a skill from a Git repository URL.
     * Downloads as a tarball via Java HTTP — no proot/git/apk needed.
     */
    suspend fun installFromGit(url: String): Result<Skill> = withContext(Dispatchers.IO) {
        val repoName = url.trimEnd('/').substringAfterLast('/')
            .removeSuffix(".git")

        val destDir = File(skillsDirectory, repoName)

        // Try tarball download (GitHub/GitLab)
        val tarballUrls = getTarballUrls(url)
        if (tarballUrls.isEmpty()) {
            return@withContext Result.failure(
                SkillInstallException("Only GitHub and GitLab URLs are supported for skill installation")
            )
        }

        val tarFile = File(cacheDir, "skill-$repoName.tar.gz")
        cacheDir.mkdirs()

        var downloaded = false
        var lastError = ""
        for (tarballUrl in tarballUrls) {
            Log.d(TAG, "Trying tarball: $tarballUrl")
            try {
                downloadFile(tarballUrl, tarFile)
                downloaded = true
                Log.d(TAG, "Downloaded ${tarFile.length()} bytes from $tarballUrl")
                break
            } catch (e: Exception) {
                lastError = e.message ?: "Unknown error"
                Log.d(TAG, "Download failed: $lastError")
                tarFile.delete()
            }
        }

        if (!downloaded) {
            return@withContext Result.failure(
                SkillInstallException("Could not download repository: $lastError")
            )
        }

        // Extract tarball using Android's native tar
        val extractDir = File(cacheDir, "skill-$repoName-extract")
        try {
            extractDir.deleteRecursively()
            extractDir.mkdirs()

            val exitCode = extractTarGz(tarFile, extractDir)
            if (exitCode != 0) {
                return@withContext Result.failure(
                    SkillInstallException("Failed to extract tarball (exit $exitCode)")
                )
            }

            // GitHub/GitLab tarballs have a subdirectory — find it
            val subDirs = extractDir.listFiles()?.filter { it.isDirectory }
            val sourceDir = if (subDirs?.size == 1) subDirs[0] else extractDir

            // Verify SKILL.md exists
            val skillMdFile = File(sourceDir, "SKILL.md")
            if (!skillMdFile.exists()) {
                return@withContext Result.failure(
                    SkillInstallException("No SKILL.md found in repository")
                )
            }

            // Copy to skills directory
            if (destDir.exists()) destDir.deleteRecursively()
            sourceDir.copyRecursively(destDir, overwrite = true)

            // Remove .git directory if present
            File(destDir, ".git").deleteRecursively()

            // Parse the installed skill
            val skillMdContent = File(destDir, "SKILL.md").readText()
            val skill = parseSkillMD(skillMdContent, destDir)
                ?: return@withContext Result.failure(
                    SkillInstallException("Failed to parse SKILL.md")
                )

            Log.d(TAG, "Installed skill: ${skill.name} from $url")
            return@withContext Result.success(skill)

        } finally {
            // Cleanup temp files
            tarFile.delete()
            extractDir.deleteRecursively()
        }
    }

    /**
     * Download a file from a URL using Java's HttpURLConnection.
     * Follows redirects automatically.
     */
    private fun downloadFile(urlString: String, destFile: File) {
        var currentUrl = urlString
        var redirects = 0
        val maxRedirects = 5

        while (redirects < maxRedirects) {
            val url = URL(currentUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = false  // Handle redirects manually for cross-protocol
            conn.setRequestProperty("User-Agent", "NeoClaw/1.0")

            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    currentUrl = conn.getHeaderField("Location")
                        ?: throw Exception("Redirect without Location header")
                    redirects++
                    continue
                }
                if (code != 200) {
                    throw Exception("HTTP $code: ${conn.responseMessage}")
                }
                conn.inputStream.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                return
            } finally {
                conn.disconnect()
            }
        }
        throw Exception("Too many redirects")
    }

    /**
     * Extract a .tar.gz file using Android's native /system/bin/tar.
     * Returns the process exit code.
     */
    private fun extractTarGz(tarFile: File, destDir: File): Int {
        val process = ProcessBuilder(
            "/system/bin/tar", "xzf", tarFile.absolutePath, "-C", destDir.absolutePath
        ).redirectErrorStream(true).start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            Log.e(TAG, "tar extraction failed (exit $exitCode): $output")
        }
        return exitCode
    }

    /**
     * Generate candidate tarball download URLs for a Git repo.
     * Tries both 'main' and 'master' branches. Supports GitHub and GitLab.
     */
    private fun getTarballUrls(gitUrl: String): List<String> {
        val cleanUrl = gitUrl.trimEnd('/').removeSuffix(".git")

        // GitHub
        if (cleanUrl.contains("github.com")) {
            return listOf(
                "$cleanUrl/archive/refs/heads/main.tar.gz",
                "$cleanUrl/archive/refs/heads/master.tar.gz"
            )
        }

        // GitLab
        if (cleanUrl.contains("gitlab.com")) {
            val repoName = cleanUrl.substringAfterLast('/')
            return listOf(
                "$cleanUrl/-/archive/main/$repoName-main.tar.gz",
                "$cleanUrl/-/archive/master/$repoName-master.tar.gz"
            )
        }

        return emptyList()
    }

    /**
     * Install a skill from a local directory.
     */
    fun installFromLocal(source: File): Result<Skill> {
        val destDir = File(skillsDirectory, source.name)

        if (destDir.exists()) destDir.deleteRecursively()
        source.copyRecursively(destDir, overwrite = true)

        val skillMD = File(destDir, "SKILL.md")
        if (!skillMD.exists()) {
            destDir.deleteRecursively()
            return Result.failure(SkillInstallException("No SKILL.md found in directory"))
        }

        val skill = parseSkillMD(skillMD.readText(), destDir)
            ?: return Result.failure(SkillInstallException("Failed to parse SKILL.md"))

        return Result.success(skill)
    }

    /**
     * List all installed skill directories.
     */
    fun listInstalled(): List<File> {
        if (!skillsDirectory.exists()) return emptyList()
        return skillsDirectory.listFiles()
            ?.filter { it.isDirectory && File(it, "SKILL.md").exists() }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    /**
     * Uninstall a skill by name.
     */
    fun uninstall(name: String): Boolean {
        val skillDir = File(skillsDirectory, name)
        return if (skillDir.exists()) {
            skillDir.deleteRecursively()
            true
        } else {
            false
        }
    }

    // MARK: - Parsing

    private fun parseSkillMD(content: String, dir: File): Skill? {
        val parts = content.split("---")
        if (parts.size < 3) return null

        val yamlBlock = parts[1].trim()
        val body = parts.drop(2).joinToString("---").trim()

        var name = dir.name
        var description = ""
        var requiredBins: List<String>? = null

        for (line in yamlBlock.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("name:") ->
                    name = trimmed.removePrefix("name:").trim().trim('"', '\'')
                trimmed.startsWith("description:") ->
                    description = trimmed.removePrefix("description:").trim().trim('"', '\'')
                trimmed.startsWith("bins:") ->
                    requiredBins = trimmed.removePrefix("bins:").trim()
                        .replace("[", "").replace("]", "")
                        .replace("\"", "").replace("'", "")
                        .split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }
        }

        return Skill(
            name = name,
            description = description,
            instructions = body,
            source = SkillSource.Local(dir.absolutePath),
            metadata = SkillMetadata(
                requires = requiredBins?.let { SkillRequirements(bins = it) }
            )
        )
    }

    class SkillInstallException(message: String) : Exception(message)
}
