package dev.menthamc.harebell.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private const val USER_AGENT = "Harebell/1.0"

@Serializable
data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url")
    val browserDownloadUrl: String,
    @SerialName("content_type")
    val contentType: String? = null
)

@Serializable
data class GithubRelease(
    @SerialName("tag_name")
    val tagName: String,
    val name: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at")
    val publishedAt: String? = null,
    val assets: List<GithubAsset> = emptyList()
)

class MintApiClient(
    private val repoTarget: RepoTarget = RepoTarget(),
    private val proxySources: List<ProxySource> = ProxySource.values().toList()
) {

    private val httpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    @Throws(IOException::class, InterruptedException::class)
    fun listReleases(limit: Int = 20): List<GithubRelease> {
        val url = "https://api.github.com/repos/${repoTarget.owner}/${repoTarget.repo}/releases?per_page=$limit"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IOException("GitHub Releases API 返回状态码 ${response.statusCode()}")
        }

        return json.decodeFromString(
            ListSerializer(GithubRelease.serializer()),
            response.body()
        )
    }

    fun resolveDownloadUrl(
        asset: GithubAsset,
        onTest: ((ProxyTiming) -> Unit)? = null
    ): ProxyChoice {
        val origin = asset.browserDownloadUrl
        val options = proxySources.map { source ->
            val url = buildProxyUrl(source, origin)
            source to url
        }

        val results = mutableListOf<ProxyTiming>()
        var best: Pair<Long, Pair<ProxySource, String>>? = null
        var bestSpeed: Long = 0L
        val testRange = 128 * 1024
        val testTimeout = Duration.ofSeconds(2)
        options.forEach { (source, url) ->
            try {
                val start = System.nanoTime()
                val req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/octet-stream")
                    .header("User-Agent", USER_AGENT)
                    .header("Range", "bytes=0-${testRange - 1}")
                    .timeout(testTimeout)
                    .GET()
                    .build()
                val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray())
                if (resp.statusCode() !in listOf(200, 206)) {
                    throw IOException("bad status ${resp.statusCode()}")
                }
                val elapsedNs = System.nanoTime() - start
                val elapsedMs = (elapsedNs / 1_000_000).coerceAtLeast(1)
                val bytes = resp.body().size.toLong().coerceAtLeast(1L)
                val speed = (bytes * 1_000_000_000L) / elapsedNs
                if (speed > bestSpeed) {
                    bestSpeed = speed
                    best = elapsedMs to (source to url)
                }
                val timing = ProxyTiming(source, elapsedMs, true, speed)
                results += timing
                onTest?.invoke(timing)
            } catch (_: Exception) {
                val timing = ProxyTiming(source, null, false, null)
                results += timing
                onTest?.invoke(timing)
            }
        }
        val finalPair = best?.second ?: (ProxySource.ORIGIN to origin)
        val finalUrl = finalPair.second
        val proxyHost = runCatching { URL(finalUrl).host }.getOrNull()
        if (results.none { it.source == ProxySource.ORIGIN }) {
            results += ProxyTiming(ProxySource.ORIGIN, null, false, null)
        }
        return ProxyChoice(url = finalUrl, proxyHost = proxyHost, source = finalPair.first, timings = results)
    }

    @Throws(IOException::class, InterruptedException::class)
    fun downloadAsset(
        asset: GithubAsset,
        target: Path,
        onProgress: ((downloaded: Long, total: Long?, bytesPerSec: Long) -> Unit)? = null,
        threadCount: Int = 4,
        downloadUrl: String? = null
    ) {
        target.parent?.let { Files.createDirectories(it) }

        val chosenUrl = downloadUrl ?: resolveDownloadUrl(asset).url
        val headLen = fetchContentLength(chosenUrl)
        val workers = threadCount.coerceAtLeast(1)

        if (headLen == null || headLen < 2L * 1024 * 1024 || workers == 1) {
            singleThreadDownload(chosenUrl, target, onProgress, headLen)
            return
        }

        multiThreadDownload(chosenUrl, target, headLen, workers, onProgress)
    }

    private fun singleThreadDownload(
        url: String,
        target: Path,
        onProgress: ((downloaded: Long, total: Long?, bytesPerSec: Long) -> Unit)?,
        totalBytesFromHead: Long? = null
    ) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/octet-stream")
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            throw IOException("下载失败，状态码 ${response.statusCode()}")
        }

        val totalBytes = totalBytesFromHead ?: response.headers()
            .firstValue("Content-Length")
            .map { it.toLongOrNull() }
            .orElse(null)

        val startNs = System.nanoTime()
        var lastUpdateNs = startNs
        var lastReportBytes = 0L
        var lastReportNs = startNs
        var downloaded = 0L

        response.body().use { input ->
            Files.newOutputStream(target).use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    downloaded += read

                    val now = System.nanoTime()
                    if (now - lastUpdateNs >= 150_000_000L) {
                        val speed = instantSpeed(startNs, lastReportBytes, downloaded, lastReportNs, now)
                        onProgress?.invoke(downloaded, totalBytes, speed)
                        lastUpdateNs = now
                        lastReportBytes = downloaded
                        lastReportNs = now
                    }
                }
            }
        }

        val finalSpeed = instantSpeed(startNs, lastReportBytes, downloaded, lastReportNs, System.nanoTime())
        onProgress?.invoke(downloaded, totalBytes, finalSpeed)
    }

    private fun fetchContentLength(url: String): Long? {
        return try {
            val head = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .header("User-Agent", USER_AGENT)
                .build()
            val response = httpClient.send(head, HttpResponse.BodyHandlers.discarding())
            if (response.statusCode() !in 200..299) return null
            response.headers()
                .firstValue("Content-Length")
                .map { it.toLongOrNull() }
                .orElse(null)
        } catch (_: Exception) {
            null
        }
    }

    private fun buildProxyUrl(source: ProxySource, origin: String): String {
        if (source == ProxySource.ORIGIN) return origin
        val base = source.baseUrl.trimEnd('/')
        return "$base/$origin"
    }

    private fun multiThreadDownload(
        url: String,
        target: Path,
        totalBytes: Long,
        threadCount: Int,
        onProgress: ((downloaded: Long, total: Long?, bytesPerSec: Long) -> Unit)?
    ) {
        val partSize = totalBytes / threadCount
        val downloaded = AtomicLong(0L)
        val startNs = System.nanoTime()
        var lastReportBytes = 0L
        var lastReportNs = startNs

        RandomAccessFile(target.toFile(), "rw").use { raf ->
            raf.setLength(totalBytes)
        }

        val pool = Executors.newFixedThreadPool(threadCount)
        val ticker = Executors.newSingleThreadScheduledExecutor()

        ticker.scheduleAtFixedRate(
            {
                val now = System.nanoTime()
                val current = downloaded.get()
                val speed = instantSpeed(startNs, lastReportBytes, current, lastReportNs, now)
                onProgress?.invoke(current, totalBytes, speed)
                lastReportBytes = current
                lastReportNs = now
            },
            0,
            200,
            TimeUnit.MILLISECONDS
        )

        try {
            val tasks = (0 until threadCount).map { idx ->
                val start = idx * partSize
                val endExclusive = if (idx == threadCount - 1) totalBytes else start + partSize
                pool.submit {
                    val req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Accept", "application/octet-stream")
                        .header("User-Agent", USER_AGENT)
                        .header("Range", "bytes=$start-${endExclusive - 1}")
                        .GET()
                        .build()

                    val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream())
                    if (resp.statusCode() !in listOf(200, 206)) {
                        throw IOException("分片下载失败，状态码 ${resp.statusCode()}")
                    }

                    RandomAccessFile(target.toFile(), "rw").use { raf ->
                        raf.seek(start)
                        resp.body().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var offset = start
                            while (offset < endExclusive) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                raf.write(buffer, 0, read)
                                offset += read
                                downloaded.addAndGet(read.toLong())
                            }
                        }
                    }
                }
            }

            tasks.forEach { it.get() }
        } finally {
            pool.shutdownNow()
            ticker.shutdownNow()
        }

        val finalSpeed = instantSpeed(startNs, lastReportBytes, downloaded.get(), lastReportNs, System.nanoTime())
        onProgress?.invoke(downloaded.get(), totalBytes, finalSpeed)
    }
}

private fun bytesPerSecond(startNs: Long, downloaded: Long, nowNs: Long): Long {
    val elapsedNs = (nowNs - startNs).coerceAtLeast(1_000_000L)
    val elapsedSec = elapsedNs / 1_000_000_000.0
    return (downloaded / elapsedSec).toLong()
}

private fun instantSpeed(
    startNs: Long,
    lastBytes: Long,
    currentBytes: Long,
    lastNs: Long,
    currentNs: Long
): Long {
    val deltaBytes = (currentBytes - lastBytes).coerceAtLeast(0L)
    val deltaNs = (currentNs - lastNs).coerceAtLeast(5_000_000L)
    val deltaSec = deltaNs / 1_000_000_000.0
    val inst = (deltaBytes / deltaSec).toLong()
    val overall = bytesPerSecond(startNs, currentBytes, currentNs)
    return listOf(inst, overall).filter { it > 0 }.minOrNull() ?: 0L
}

data class ProxyChoice(
    val url: String,
    val proxyHost: String?,
    val source: ProxySource,
    val timings: List<ProxyTiming>
)

data class ProxyTiming(
    val source: ProxySource,
    val elapsedMs: Long?,
    val ok: Boolean,
    val bytesPerSec: Long?
)
