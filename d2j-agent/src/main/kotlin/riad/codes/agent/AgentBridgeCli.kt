package riad.codes.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Entry point: `d2j-agent [--host H] [--port P] [--progress-url URL]`.
 *
 * Starts the local HTTP bridge so an AI agent can discover and invoke the
 * dex2jar tools and subscribe to real-time progress.
 */
fun main(args: Array<String>) {
    AgentBridgeCli.run(args)
}

object AgentBridgeCli {

    private const val DEFAULT_HOST = "127.0.0.1"
    private const val DEFAULT_PORT = 8787

    fun run(argv: Array<String>) {
        var host = DEFAULT_HOST
        var port = DEFAULT_PORT
        var progressUrl: String? = null

        var i = 0
        while (i < argv.size) {
            when (val a = argv[i]) {
                "--host" -> host = argv.getOrNull(++i) ?: fail("--host requires a value")
                "--port" -> port = (argv.getOrNull(++i) ?: fail("--port requires a value")).toInt()
                "--progress-url" -> progressUrl = argv.getOrNull(++i) ?: fail("--progress-url requires a value")
                "-h", "--help" -> {
                    printHelp()
                    return
                }
                else -> fail("unknown argument: $a")
            }
            i++
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sinks = progressUrl?.let { listOf<ProgressListener>(RemoteProgressSink(it)) } ?: emptyList()
        val jobs = JobManager(scope, sinks)
        val server = AgentBridgeServer(host, port, ToolRegistry.defaults(), scope, jobs)
        server.start()

        val actualPort = server.boundPort()
        println("dex2jar agent bridge v${server.version}")
        println("  http://$host:$actualPort/v1/health")
        println("  http://$host:$actualPort/v1/tools")
        if (progressUrl != null) println("  progress push -> $progressUrl")
        println("press Ctrl+C to stop")

        Thread.currentThread().join()
    }

    private fun printHelp() {
        println(
            """
            Usage: d2j-agent [options]

              --host <host>          bind address (default $DEFAULT_HOST)
              --port <port>          bind port (default $DEFAULT_PORT)
              --progress-url <url>   POST every progress event to this http(s) URL
              -h, --help             show this help
            """.trimIndent(),
        )
    }

    private fun fail(message: String): Nothing {
        System.err.println("d2j-agent: $message")
        throw IllegalArgumentException(message)
    }
}
