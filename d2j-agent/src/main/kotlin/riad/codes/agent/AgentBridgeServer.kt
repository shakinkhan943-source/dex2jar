package riad.codes.agent

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Local HTTP bridge that exposes the dex2jar tools to an AI agent.
 *
 * Routes
 * ------
 *  GET  /v1/health                    liveness + tool count
 *  GET  /v1/tools                     machine-readable tool catalogue ([ToolSpec])
 *  POST /v1/tools/{name}              run a tool, return [ToolResult] as JSON
 *  POST /v1/tools/{name}/stream       run a tool, stream progress as Server-Sent Events
 *  GET  /v1/jobs                      list known job ids
 *  GET  /v1/jobs/{id}                 fetch a job result (202 while running)
 *  GET  /v1/jobs/{id}/events          stream a job's progress as Server-Sent Events
 */
class AgentBridgeServer(
    private val host: String,
    private val port: Int,
    private val registry: ToolRegistry,
    private val scope: CoroutineScope,
    private val jobs: JobManager,
) {
    private val log = LoggerFactory.getLogger(AgentBridgeServer::class.java)
    private lateinit var server: HttpServer

    val version: String = AgentBridgeServer::class.java.`package`?.implementationVersion ?: "dev"

    fun start() {
        server = HttpServer.create(InetSocketAddress(host, port), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/", ::route)
        server.start()
        log.info("dex2jar agent bridge listening on http://{}:{}", host, port)
    }

    fun stop(delaySeconds: Int = 0) {
        server.stop(delaySeconds)
    }

    fun boundPort(): Int = server.address.port

    private fun route(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path.trimEnd('/').ifEmpty { "/" }
            val method = exchange.requestMethod.uppercase()
            log.info("{} {}", method, path)

            when {
                path == "/v1/health" && method == "GET" -> health(exchange)
                path == "/v1/tools" && method == "GET" -> tools(exchange)
                path == "/v1/jobs" && method == "GET" -> jobList(exchange)
                path.startsWith("/v1/tools/") && path.endsWith("/stream") && method == "POST" ->
                    runStreaming(exchange, toolName(path.removeSuffix("/stream")))
                path.startsWith("/v1/tools/") && method == "POST" ->
                    runSync(exchange, toolName(path))
                path.startsWith("/v1/jobs/") && path.endsWith("/events") && method == "GET" ->
                    jobEvents(exchange, path.removePrefix("/v1/jobs/").removeSuffix("/events"))
                path.startsWith("/v1/jobs/") && method == "GET" ->
                    jobResult(exchange, path.removePrefix("/v1/jobs/"))
                else -> json(exchange, 404, mapOf("error" to "not found: $path"))
            }
        } catch (t: Throwable) {
            log.warn("request failed: {}", t.message)
            runCatching { json(exchange, 500, mapOf("error" to (t.message ?: t.javaClass.name))) }
        } finally {
            exchange.close()
        }
    }

    private fun toolName(path: String): String = path.removePrefix("/v1/tools/")

    private fun health(exchange: HttpExchange) = json(
        exchange,
        200,
        mapOf(
            "status" to "ok",
            "version" to version,
            "toolCount" to registry.specs().size,
            "tools" to registry.specs().map { it.name },
        ),
    )

    private fun tools(exchange: HttpExchange) = json(exchange, 200, mapOf("tools" to registry.specs()))

    private fun jobList(exchange: HttpExchange) = json(exchange, 200, mapOf("jobs" to jobs.ids()))

    private fun jobResult(exchange: HttpExchange, id: String) {
        val handle = jobs.get(id) ?: return json(exchange, 404, mapOf("error" to "unknown job: $id"))
        val result = handle.result
        if (result == null) {
            json(exchange, 202, mapOf("jobId" to id, "tool" to handle.tool, "status" to "running"))
        } else {
            json(exchange, 200, result)
        }
    }

    private fun runSync(exchange: HttpExchange, name: String) {
        val tool = registry.get(name) ?: return json(exchange, 404, mapOf("error" to "unknown tool: $name"))
        val args = readArgs(exchange)
        val result = runBlocking { jobs.runBlocking(tool, args) }
        json(exchange, if (result.success) 200 else 500, result)
    }

    private fun runStreaming(exchange: HttpExchange, name: String) {
        val tool = registry.get(name) ?: return json(exchange, 404, mapOf("error" to "unknown tool: $name"))
        val args = readArgs(exchange)
        val handle = jobs.start(tool, args)
        // Announce the job id first so an agent can reconnect to /v1/jobs/{id}/events.
        sseHeaders(exchange)
        val out = exchange.responseBody
        writeEvent(out, mapOf("type" to "job", "jobId" to handle.id, "tool" to tool.spec.name))
        pump(out, handle)
        handle.result?.let { writeEvent(out, mapOf("type" to "result", "result" to it)) }
        out.flush()
    }

    private fun jobEvents(exchange: HttpExchange, id: String) {
        val handle = jobs.get(id) ?: return json(exchange, 404, mapOf("error" to "unknown job: $id"))
        sseHeaders(exchange)
        val out = exchange.responseBody
        writeEvent(out, mapOf("type" to "job", "jobId" to id, "tool" to handle.tool))
        pump(out, handle)
        handle.result?.let { writeEvent(out, mapOf("type" to "result", "result" to it)) }
        out.flush()
    }

    /**
     * Stream [handle]'s progress to [out]: replay everything a late/reconnecting
     * subscriber missed, then follow live events until the job completes. Uses
     * the job's shared flow, so several SSE clients can watch the same job
     * concurrently without stealing each other's events.
     */
    private fun pump(out: OutputStream, handle: JobManager.JobHandle) {
        if (handle.done) {
            handle.replay().forEach { writeProgress(out, it) }
            return
        }
        runBlocking {
            val collector = launch {
                handle.events().collect { writeProgress(out, it) }
            }
            handle.awaitCompletion()
            // Give the collector a moment to drain the buffered tail before stopping.
            delay(100)
            collector.cancelAndJoin()
        }
    }

    private fun writeProgress(out: OutputStream, event: ProgressEvent) {
        writeEvent(out, mapOf("type" to "progress", "event" to event))
    }

    private fun sseHeaders(exchange: HttpExchange) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.responseHeaders.add("Connection", "keep-alive")
        exchange.sendResponseHeaders(200, 0)
    }

    private fun writeEvent(out: OutputStream, payload: Map<String, Any?>) {
        out.write("data: ${Json.write(payload)}\n\n".toByteArray(StandardCharsets.UTF_8))
        out.flush()
    }

    @Suppress("UNCHECKED_CAST")
    private fun readArgs(exchange: HttpExchange): Map<String, Any?> {
        val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8).trim()
        if (body.isEmpty()) return emptyMap()
        return Json.readMap(body)
    }

    private fun json(exchange: HttpExchange, status: Int, body: Any?) {
        val bytes = Json.write(body).toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
