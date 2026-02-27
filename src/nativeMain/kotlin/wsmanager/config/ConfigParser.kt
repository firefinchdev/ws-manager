package wsmanager.config

import kotlinx.serialization.json.Json
import wsmanager.util.FileUtils

/**
 * Parses workspace configuration files.
 */
object ConfigParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        encodeDefaults = true
    }

    /**
     * Parse a workspace config from a JSON file path.
     */
    fun parse(filePath: String): WorkspaceConfig {
        val content = FileUtils.readFile(filePath)
            ?: throw ConfigParseException("Configuration file not found: $filePath")

        if (content.isBlank()) {
            throw ConfigParseException("Configuration file is empty: $filePath")
        }

        return try {
            json.decodeFromString<WorkspaceConfig>(content)
        } catch (e: Exception) {
            throw ConfigParseException("Failed to parse configuration file '$filePath': ${e.message}")
        }
    }

    /**
     * Parse and validate configuration.
     */
    fun parseAndValidate(filePath: String): WorkspaceConfig {
        val config = parse(filePath)
        ConfigValidator.validateOrThrow(config)
        return config
    }

    /**
     * Serialize a config to JSON string.
     */
    fun serialize(config: WorkspaceConfig): String {
        return json.encodeToString(WorkspaceConfig.serializer(), config)
    }

    /**
     * Write a config to file.
     */
    fun write(config: WorkspaceConfig, filePath: String) {
        val content = serialize(config)
        FileUtils.writeFile(filePath, content)
    }
}

/**
 * Exception thrown when configuration parsing fails.
 */
class ConfigParseException(message: String) : Exception(message)
