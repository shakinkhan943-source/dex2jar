package riad.codes.agent

import riad.codes.agent.tools.BaksmaliTool
import riad.codes.agent.tools.Dex2JarTool
import riad.codes.agent.tools.DexInfoTool

/** Holds the tools an agent bridge exposes. */
class ToolRegistry(tools: List<AgentTool>) {

    private val byName: Map<String, AgentTool> = tools.associateBy { it.spec.name }

    fun get(name: String): AgentTool? = byName[name]

    fun specs(): List<ToolSpec> = byName.values.map { it.spec }.sortedBy { it.name }

    companion object {
        /** The default tool set shipped with the bridge. */
        fun defaults(): ToolRegistry = ToolRegistry(
            listOf(
                Dex2JarTool(),
                BaksmaliTool(),
                DexInfoTool(),
            ),
        )
    }
}
