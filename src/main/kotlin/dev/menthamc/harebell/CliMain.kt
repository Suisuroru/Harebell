package dev.menthamc.harebell

import dev.menthamc.harebell.data.GithubAsset
import dev.menthamc.harebell.data.GithubRelease
import dev.menthamc.harebell.data.LauncherConfigStore
import dev.menthamc.harebell.data.MintApiClient
import dev.menthamc.harebell.data.ProxyTiming
import dev.menthamc.harebell.data.RepoTarget
import java.nio.file.Paths
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

fun main(args: Array<String>) = CliMain.main(args)

object CliMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val configStore = LauncherConfigStore()
        val repoTarget = RepoTarget()
        val apiClient = MintApiClient(repoTarget = repoTarget)
        var config = configStore.load()

        val releaseTagInput = System.getProperty("minecraftVersion")
            ?: config.lastSelectedReleaseTag
        val installDir = System.getProperty("installDir")
            ?: config.installDir
        val javaPath = System.getProperty("javaPath")
            ?: config.javaPath
        val mem = System.getProperty("mem")
            ?: config.maxMemory
        val extraJvm = System.getProperty("jvmArgs")
            ?: config.extraJvmArgs
        val jarName = System.getProperty("jarName")
            ?: config.jarName

        if (installDir.isBlank()) {
            cliError("缺少下载目录：请提供 -DinstallDir=目录 或在配置文件/参数中指定")
            return
        }

        cliBanner("Harebell")
        cliInfo("目录: $installDir")
        if (jarName.isNotBlank()) {
            cliInfo("文件名: ${normalizeJarName(jarName, "harebell.jar")}")
        }

        val releases = try {
            cliStep("获取 Release 列表...")
            apiClient.listReleases(limit = 50).filterNot { it.draft }
        } catch (e: Exception) {
            cliError("获取 Release 列表失败: ${e.message}")
            return
        }

        val normalizedInput = releaseTagInput?.trim()?.stripLeadingV()
        val release = releases.firstOrNull {
            normalizedInput != null && (it.tagName == releaseTagInput || it.tagName.stripLeadingV() == normalizedInput)
        } ?: releases.firstOrNull()
        if (release == null) {
            cliError("未找到任何 Release")
            return
        }
        val releaseTag = release.tagName
        cliInfo("选用版本: $releaseTag")

        val asset = chooseJarAsset(release)
            ?: run {
                cliError("该 Release 下没有 jar 资源，请检查 GitHub 页面")
                return
            }

        val targetName = normalizeJarName(jarName, asset.name)
        val target = Paths.get(installDir).resolve(targetName)

        var finalHash = config.jarHash
        var needDownload = true
        if (Files.exists(target) && config.jarHash.isNotBlank()) {
            val currentHash = sha256(target)
            if (currentHash.equals(config.jarHash, ignoreCase = true)) {
                cliInfo("本地 hash 与配置一致，跳过下载: $targetName")
                needDownload = false
                finalHash = currentHash
            } else {
                cliInfo("本地 hash 与配置不一致，执行更新: $targetName")
            }
        }

        if (needDownload) {
            val proxyChoice = apiClient.resolveDownloadUrl(asset) { timing ->
                val speedText = timing.bytesPerSec?.let { formatSpeed(it) } ?: "fail"
                cliInfo("测速: ${timing.source} -> $speedText")
            }

            try {
                val timingsText = proxyChoice.timings
                    .sortedWith(compareBy<ProxyTiming> { !it.ok }
                        .thenByDescending { it.bytesPerSec ?: 0 }
                        .thenBy { it.elapsedMs ?: Long.MAX_VALUE })
                    .joinToString(", ") { t ->
                        val speed = t.bytesPerSec?.let { formatSpeed(it) } ?: "fail"
                        "${t.source}=$speed"
                    }
                cliInfo("测速: $timingsText")
                cliStep("下载: $targetName （源文件: ${asset.name}）")
                proxyChoice.proxyHost?.let { cliInfo("使用下载源: ${proxyChoice.source} -> $it") }
                apiClient.downloadAsset(
                    asset = asset,
                    target = target,
                    downloadUrl = proxyChoice.url,
                    onProgress = { downloaded, total, _ ->
                        val totalText = total?.let { "/ ${formatBytes(it)}" } ?: ""
                        val pct = total?.let { (downloaded * 100 / it).coerceIn(0, 100) }
                        val pctText = pct?.let { " ($it%)" } ?: ""
                        cliProgress("下载进度: ${formatBytes(downloaded)}$totalText$pctText")
                    }
                )
                cliOk("下载完成: $targetName")
                finalHash = sha256(target)
            } catch (e: Exception) {
                cliError("下载失败: ${e.message}")
                return
            }
        }

        config = config.copy(
            installDir = installDir,
            javaPath = javaPath,
            maxMemory = mem,
            jarName = targetName,
            jarHash = finalHash ?: "",
            lastSelectedReleaseTag = releaseTag
        )
        configStore.save(config)

        val javaCmd = javaPath.ifBlank { "java" }
        val argsList = mutableListOf<String>()
        argsList += javaCmd
        val memValue = config.maxMemory.trim()
        if (memValue.isNotEmpty()) {
            argsList += "-Xms$memValue"
            argsList += "-Xmx$memValue"
        }
        argsList += extraJvm.split(Regex("\\s+")).filter { it.isNotBlank() }
        argsList += "-jar"
        argsList += target.toAbsolutePath().toString()
        argsList += config.serverArgs.split(Regex("\\s+")).filter { it.isNotBlank() }

        cliStep("启动: ${argsList.joinToString(" ")}")
        try {
            val pb = ProcessBuilder(argsList)
                .directory(Paths.get(installDir).toFile())
                .inheritIO()
            val proc = pb.start()
            val exit = proc.waitFor()
            cliInfo("进程退出，代码=$exit")
        } catch (e: Exception) {
            cliError("启动失败: ${e.message}")
        }
    }
}

private fun argValue(args: Array<String>, vararg keys: String): String? {
    val set = keys.toSet()
    args.forEach { arg ->
        set.firstOrNull { arg.startsWith("$it=") }?.let { key ->
            return arg.removePrefix("$key=")
        }
    }
    return null
}

private fun chooseJarAsset(release: GithubRelease): GithubAsset? {
    val jars = release.assets.filter { it.name.endsWith(".jar", ignoreCase = true) }
    val preferred = jars.firstOrNull {
        val n = it.name.lowercase()
        "paperclip" in n || "server" in n || "mint" in n || "harebell" in n
    }
    return preferred ?: jars.firstOrNull()
}

private fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    val fmt = if (unitIndex == 0 || value >= 100) "%.0f" else "%.1f"
    return fmt.format(value) + " " + units[unitIndex]
}

private fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0) return "0 B/s"
    val units = listOf("B/s", "KB/s", "MB/s", "GB/s")
    var value = bytesPerSec.toDouble()
    var idx = 0
    while (value >= 1024 && idx < units.lastIndex) {
        value /= 1024
        idx++
    }
    val fmt = if (idx == 0 || value >= 100) "%.0f" else "%.1f"
    return fmt.format(value) + " " + units[idx]
}

private fun String.stripLeadingV(): String =
    if (length >= 2 && (this[0] == 'v' || this[0] == 'V')) substring(1) else this

private fun normalizeJarName(desired: String?, assetName: String): String {
    val clean = desired?.trim().orEmpty()
    if (clean.isEmpty()) return assetName
    val assetExt = assetName.substringAfterLast('.', "")
    return if (assetExt.isNotEmpty() && !clean.contains('.')) {
        "$clean.$assetExt"
    } else clean
}

private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buf = ByteArray(8192)
        while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            digest.update(buf, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun cliBanner(title: String, desc: String? = null) {
    val rawLines = listOfNotNull(title, desc)
    if (rawLines.isEmpty()) return
    val rendered = rawLines.mapIndexed { idx, line ->
        if (idx == 0) ">> $line <<" else line
    }
    val width = rendered.maxOf { it.length }.coerceAtLeast(12)
    val top = "+=" + "=".repeat(width + 4) + "=+"
    val bottom = "+-" + "-".repeat(width + 4) + "-+"
    println(top)
    rendered.forEach { line ->
        val pad = width - line.length
        println("||  $line${" ".repeat(pad)}  ||")
    }
    println(bottom)
}

private fun cliStep(msg: String) = println("[*] $msg")
private fun cliInfo(msg: String) = println("[>] $msg")
private fun cliOk(msg: String) = println("[✓] $msg")
private fun cliError(msg: String) = println("[!] $msg")

@Volatile private var lastProgress: String? = null
private fun cliProgress(msg: String) {
    if (msg == lastProgress) return
    lastProgress = msg
    println(msg)
}
