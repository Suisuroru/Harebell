package dev.menthamc.harebell

import dev.menthamc.harebell.data.GithubAsset
import dev.menthamc.harebell.data.GithubRelease
import dev.menthamc.harebell.data.LauncherConfigStore
import dev.menthamc.harebell.data.MintApiClient
import dev.menthamc.harebell.data.ProxyTiming
import dev.menthamc.harebell.data.RepoTarget
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

private const val REPO_URL = "https://github.com/MenthaMC/Harebell"
private const val ANSI_RESET = "\u001B[0m"
private val ANSI_ENABLED = System.getenv("NO_COLOR") == null
private val ANSI_REGEX = Regex("\u001B\\[[;?0-9]*[ -/]*[@-~]")

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

        val hasConfigFile = configStore.hasExistingConfig()
        val hasInstallProp = System.getProperty("installDir")?.isNotBlank() == true
        val configSourcePath = configStore.configSourcePath()
        printIntro(
            repoUrl = REPO_URL,
            installDir = installDir,
            jarName = jarName,
            showDir = hasConfigFile || hasInstallProp
        )
        configSourcePath?.let {
            cliInfo("已找到配置文件: ${it.toAbsolutePath()}")
        } ?: cliInfo("未找到配置文件，将使用默认配置并生成 harebell.json")

        if (installDir.isBlank()) {
            cliError("缺少下载目录：请提供 -DinstallDir=目录 或在配置文件/参数中指定")
            return
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
        cliInfo("最新版本: $releaseTag")

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

@Suppress("UNUSED_PARAMETER")
private fun printIntro(repoUrl: String, installDir: String, jarName: String, showDir: Boolean) {
    val palette = if (ANSI_ENABLED) {
        listOf("\u001B[38;5;45m", "\u001B[38;5;81m", "\u001B[38;5;117m", "\u001B[38;5;153m", "\u001B[38;5;189m", "\u001B[38;5;219m")
    } else {
        listOf("")
    }
    val letters = listOf(
        arrayOf("██╗  ██╗", "██║  ██║", "███████║", "██╔══██║", "██║  ██║", "╚═╝  ╚═╝"),
        arrayOf(" █████╗ ", "██╔══██╗", "███████║", "██╔══██║", "██║  ██║", "╚═╝  ╚═╝"),
        arrayOf("██████╗ ", "██╔══██╗", "██████╔╝", "██╔══██╗", "██║  ██║", "╚═╝  ╚═╝"),
        arrayOf("███████╗", "██╔════╝", "█████╗  ", "██╔══╝  ", "███████╗", "╚══════╝"),
        arrayOf("██████╗ ", "██╔══██╗", "██████╔╝", "██╔══██╗", "██████╔╝", "╚═════╝ "),
        arrayOf("███████╗", "██╔════╝", "█████╗  ", "██╔══╝  ", "███████╗", "╚══════╝"),
        arrayOf("██╗     ", "██║     ", "██║     ", "██║     ", "███████╗", "╚══════╝"),
        arrayOf("██╗     ", "██║     ", "██║     ", "██║     ", "███████╗", "╚══════╝")
    )
    val rows = letters.first().size
    val spacing = "  "
    for (row in 0 until rows) {
        val line = buildString {
            letters.forEachIndexed { idx, letter ->
                val color = palette[idx % palette.size]
                append(colorize(letter[row], color))
                if (idx != letters.lastIndex) append(spacing)
            }
        }
        println(line)
    }

    println()
    val infoLines = mutableListOf<String>()
    infoLines += accent("Harebell 更新程序已准备就绪", "\u001B[38;5;183m")
    infoLines += "了解更多: $REPO_URL"
    printBox(infoLines)
}

private fun printBox(lines: List<String>) {
    if (lines.isEmpty()) return
    val contentWidth = lines.maxOf { visibleLength(it) }
    val innerWidth = contentWidth + 2
    val borderColor = if (ANSI_ENABLED) "\u001B[38;5;105m" else ""
    val reset = if (ANSI_ENABLED) ANSI_RESET else ""
    val horizontal = "─".repeat(innerWidth)
    println(borderColor + "┌$horizontal┐" + reset)
    lines.forEach { line ->
        val pad = contentWidth - visibleLength(line)
        println(borderColor + "│ " + reset + line + " ".repeat(pad) + borderColor + " │" + reset)
    }
    println(borderColor + "└$horizontal┘" + reset)
}

private fun visibleLength(text: String): Int {
    val cleaned = ANSI_REGEX.replace(text, "")
    return cleaned.sumOf { chDisplayWidth(it) }
}


private fun chDisplayWidth(ch: Char): Int = when {
    ch.code in 0x3400..0x4DBF -> 2 // CJK Extension A
    ch.code in 0x4E00..0x9FFF -> 2 // CJK Unified
    ch.code in 0x3040..0x30FF -> 2 // Hiragana/Katakana
    ch.code in 0x3000..0x303F -> 2 // CJK punctuation
    ch.code in 0xFF00..0xFFEF -> 2 // Fullwidth forms
    Character.getType(ch) == Character.NON_SPACING_MARK.toInt() -> 0
    else -> 1
}

private fun accent(text: String, color: String = "\u001B[38;5;111m"): String =
    colorize(text, color)

private fun colorize(text: String, color: String): String =
    if (ANSI_ENABLED && color.isNotEmpty()) "$color$text$ANSI_RESET" else text

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
