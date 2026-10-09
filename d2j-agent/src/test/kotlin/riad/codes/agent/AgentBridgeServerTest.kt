package riad.codes.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class AgentBridgeServerTest {

    private lateinit var scope: CoroutineScope
    private lateinit var server: AgentBridgeServer
    private lateinit var baseUrl: String
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val sampleDex = File("../dex-translator/src/test/resources/dexes/dex038.dex")

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val jobs = JobManager(scope)
        server = AgentBridgeServer("127.0.0.1", 0, ToolRegistry.defaults(), scope, jobs)
        server.start()
        baseUrl = "http://127.0.0.1:${server.boundPort()}"
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
        scope.cancel()
    }

    private fun get(path: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("$baseUrl$path"))
                .timeout(Duration.ofSeconds(30))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun post(path: String, body: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("$baseUrl$path"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `health reports ok`() {
        val res = get("/v1/health")
        assertEquals(200, res.statusCode())
        val body = Json.readMap(res.body())
        assertEquals("ok", body["status"])
        assertEquals(3, (body["toolCount"] as Number).toInt())
    }

    @Test
    fun `tools endpoint returns the catalogue`() {
        val res = get("/v1/tools")
        assertEquals(200, res.statusCode())
        val tools = Json.readMap(res.body())["tools"] as List<*>
        assertEquals(3, tools.size)
    }

    @Test
    fun `sync tool run returns a result`() {
        val res = post("/v1/tools/dexInfo", """{"input":"${sampleDex.absolutePath}"}""")
        assertEquals(200, res.statusCode())
        val body = Json.readMap(res.body())
        assertEquals(true, body["success"])
        @Suppress("UNCHECKED_CAST")
        val output = body["output"] as Map<String, Any?>
        assertTrue((output["classCount"] as Number).toInt() > 0)
    }

    @Test
    fun `streaming run emits progress events over SSE`() {
        val res = post("/v1/tools/dexInfo/stream", """{"input":"${sampleDex.absolutePath}"}""")
        assertEquals(200, res.statusCode())
        assertTrue(res.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"))

        val dataLines = res.body().lines().filter { it.startsWith("data: ") }
        assertTrue(dataLines.isNotEmpty(), "expected SSE data lines")
        assertTrue(dataLines.any { it.contains("\"type\":\"progress\"") })
        assertTrue(dataLines.any { it.contains("\"type\":\"result\"") })
        assertTrue(dataLines.any { it.contains("COMPLETED") })
    }

    @Test
    fun `unknown tool returns 404`() {
        val res = post("/v1/tools/nope", "{}")
        assertEquals(404, res.statusCode())
    }
}
