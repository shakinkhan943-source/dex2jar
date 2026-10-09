package riad.codes.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class ToolRegistryTest {

    @Test
    fun `default registry exposes the core tools`() {
        val registry = ToolRegistry.defaults()
        val names = registry.specs().map { it.name }
        assertEquals(listOf("baksmali", "dex2jar", "dexInfo"), names)
        assertNotNull(registry.get("dex2jar"))
        assertNotNull(registry.get("baksmali"))
        assertNotNull(registry.get("dexInfo"))
    }

    @Test
    fun `every tool declares an input parameter`() {
        ToolRegistry.defaults().specs().forEach { spec ->
            val hasInput = spec.parameters.any { it.name == "input" && it.required }
            assertEquals(true, hasInput, "tool ${spec.name} must require an input")
        }
    }
}
