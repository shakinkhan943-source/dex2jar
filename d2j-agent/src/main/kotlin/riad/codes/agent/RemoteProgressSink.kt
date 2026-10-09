package riad.codes.agent

import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Pushes every progress event as JSON to a remote endpoint.
 *
 * This is the "real-time progress send API": point an agent dashboard (or any
 * log ingest) at a URL and it receives a POST per event with the same schema the
 * SSE stream uses.
 */
class RemoteProgressSink(private val url: String) : ProgressListener {

    private val log = LoggerFactory.getLogger(RemoteProgressSink::class.java)
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    init {
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "progress URL must be http(s): $url"
        }
    }

    override fun onEvent(event: ProgressEvent) {
        try {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(event)))
                .build()
            client.send(request, HttpResponse.BodyHandlers.discarding())
        } catch (t: Throwable) {
            log.warn("failed to push progress event: {}", t.message)
        }
    }
}
