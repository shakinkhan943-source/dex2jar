package riad.codes.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

/** Shared, thread-safe JSON mapper used by the bridge and the progress sinks. */
object Json {
    val mapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()

    fun write(value: Any?): String = mapper.writeValueAsString(value)

    @Suppress("UNCHECKED_CAST")
    fun readMap(text: String): Map<String, Any?> =
        mapper.readValue(text, Map::class.java) as Map<String, Any?>
}
