package dev.menthamc.harebell.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Serializable
data class LauncherConfig(
    val installDir: String = "",
    val javaPath: String = "java",
    val maxMemory: String = "",
    val extraJvmArgs: String = "",
    val serverArgs: String = "",
    val jarName: String = "",
    val jarHash: String = "",
    val lastSelectedReleaseTag: String? = null
)

class LauncherConfigStore(
    private val configPath: Path = Paths.get("harebell.json"),
    private val legacyConfigPath: Path = Paths.get("mint-launcher.json")
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun load(): LauncherConfig {
        val pathToRead = existingConfigPath()
        val loaded = try {
            if (pathToRead != null) {
                val text = Files.readString(pathToRead)
                json.decodeFromString(LauncherConfig.serializer(), text)
            } else {
                LauncherConfig()
            }
        } catch (_: Exception) {
            LauncherConfig()
        }
        val defaultDir = Paths.get("").toAbsolutePath().toString()
        return if (loaded.installDir.isBlank()) loaded.copy(installDir = defaultDir) else loaded
    }

    fun save(config: LauncherConfig) {
        val text = json.encodeToString(LauncherConfig.serializer(), config)
        Files.writeString(configPath, text)
    }

    fun hasExistingConfig(): Boolean = existingConfigPath() != null

    fun configSourcePath(): Path? = existingConfigPath()

    private fun existingConfigPath(): Path? = when {
        Files.exists(configPath) -> configPath
        Files.exists(legacyConfigPath) -> legacyConfigPath
        else -> null
    }
}
