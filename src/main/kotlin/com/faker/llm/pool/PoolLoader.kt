package com.faker.llm.pool

import com.faker.llm.domain.PoolEntry
import com.faker.llm.domain.ResponsePart
import com.faker.llm.domain.SuccessEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.io.File
import java.net.JarURLConnection
import java.net.URL
import java.util.jar.JarFile

/**
 * Loads [PoolEntry] definitions from `*.json` resources on the classpath.
 *
 * Works both when running from an exploded directory (`file:` URLs, e.g. `gradle run`) and
 * from a fat jar (`jar:` URLs, e.g. `java -jar faker-llm-all.jar`). Intended to run **once at
 * startup**; the parsed list is handed to a long-lived [PoolSelector].
 *
 * Each file may contain either a single entry object or a JSON array of entries.
 */
class PoolLoader {

    private val logger = LoggerFactory.getLogger(PoolLoader::class.java)
    private val json = PoolJson.json

    /**
     * @param directory classpath directory to scan (default `"pool"`)
     * @return all valid entries; invalid ones are logged (warn) and skipped
     */
    fun load(directory: String = DEFAULT_DIRECTORY): List<PoolEntry> {
        val classLoader = Thread.currentThread().contextClassLoader
            ?: PoolLoader::class.java.classLoader
        val roots = classLoader.getResources(directory).toList()
        if (roots.isEmpty()) {
            logger.warn("Pool directory '{}' not found on classpath — loaded 0 entries", directory)
            return emptyList()
        }

        val fileContents = roots.flatMap { url -> readJsonResources(url, directory, classLoader) }

        var invalid = 0
        val entries = fileContents.flatMap { (fileName, content) ->
            parseFile(fileName, content) { invalid++ }
        }

        logger.info(
            "Pool loaded: {} file(s) from '{}', {} valid entries, {} invalid skipped",
            fileContents.size, directory, entries.size, invalid,
        )
        return entries
    }

    /** Parses one file's text into 0+ valid entries, invoking [onInvalid] for each skipped one. */
    private fun parseFile(fileName: String, content: String, onInvalid: () -> Unit): List<PoolEntry> {
        val raw = try {
            when (val element = json.parseToJsonElement(content)) {
                is JsonArray -> element.map { json.decodeFromJsonElement(PoolEntry.serializer(), it) }
                is JsonObject -> listOf(json.decodeFromJsonElement(PoolEntry.serializer(), element))
                else -> {
                    logger.warn("Pool file '{}' is neither an object nor an array — skipped", fileName)
                    onInvalid()
                    return emptyList()
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse pool file '{}': {} — skipped", fileName, e.message)
            onInvalid()
            return emptyList()
        }

        return raw.filter { entry ->
            val reason = validate(entry)
            if (reason != null) {
                logger.warn("Invalid entry id='{}' in '{}': {} — skipped", entry.id, fileName, reason)
                onInvalid()
                false
            } else {
                true
            }
        }
    }

    /** @return null when valid, otherwise a human-readable reason. */
    private fun validate(entry: PoolEntry): String? = when {
        entry.weight <= 0.0 -> "weight must be > 0 (was ${entry.weight})"
        entry is SuccessEntry && entry.requiresTools && entry.parts.none { it is ResponsePart.ToolCall } ->
            "requiresTools=true but no ToolCall part present"
        else -> null
    }

    /** Lists `*.json` resources directly under [directory], dispatching on URL protocol. */
    private fun readJsonResources(
        url: URL,
        directory: String,
        classLoader: ClassLoader,
    ): List<Pair<String, String>> = when (url.protocol) {
        "file" -> readFromDirectory(File(url.toURI()))
        "jar" -> readFromJar(url, directory)
        else -> {
            logger.warn("Unsupported pool resource protocol '{}' at {} — skipped", url.protocol, url)
            emptyList()
        }
    }

    private fun readFromDirectory(dir: File): List<Pair<String, String>> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(JSON_SUFFIX) } ?: emptyArray()
        return files.sortedBy { it.name }.map { it.name to it.readText() }
    }

    private fun readFromJar(url: URL, directory: String): List<Pair<String, String>> {
        val connection = url.openConnection() as JarURLConnection
        // Do NOT close a shared (cached) JarFile obtained via getJarFile(); only close our own.
        val jarFile: JarFile = connection.jarFile
        val prefix = "$directory/"
        return jarFile.entries().asSequence()
            .filter { entry ->
                !entry.isDirectory &&
                    entry.name.startsWith(prefix) &&
                    entry.name.endsWith(JSON_SUFFIX) &&
                    // direct children only — no nested subdirectories
                    !entry.name.substring(prefix.length).contains('/')
            }
            .sortedBy { it.name }
            .map { entry ->
                val fileName = entry.name.substringAfterLast('/')
                fileName to jarFile.getInputStream(entry).bufferedReader().use { it.readText() }
            }
            .toList()
    }

    companion object {
        const val DEFAULT_DIRECTORY = "pool"
        private const val JSON_SUFFIX = ".json"
    }
}
