package pag.campaign

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

import com.sun.net.httpserver.HttpServer

/** A request the fake server received. */
final case class Received(method: String, path: String, authorization: Option[String], body: String)

/** An OpenAI-compatible endpoint for tests, on the JDK's own HTTP server: it
  * answers each request with the next scripted (status, body), repeating the
  * last, and keeps what it received. Mutable by nature — it observes requests.
  */
final class FakeServer(script: List[(Int, String)], delayMs: Long = 0):
  private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
  private val pending: ConcurrentLinkedQueue[(Int, String)] = ConcurrentLinkedQueue(script.asJava)
  private val log: ConcurrentLinkedQueue[Received] = ConcurrentLinkedQueue()

  server.createContext("/", exchange => {
    val body = String(exchange.getRequestBody.readAllBytes(), UTF_8)
    log.add(Received(exchange.getRequestMethod, exchange.getRequestURI.getPath,
      Option(exchange.getRequestHeaders.getFirst("Authorization")), body))
    if delayMs > 0 then Thread.sleep(delayMs) // a slow model: received, then answered late
    val (status, reply) = if pending.size > 1 then pending.poll() else pending.peek()
    val bytes = reply.getBytes(UTF_8)
    exchange.sendResponseHeaders(status, bytes.length.toLong)
    exchange.getResponseBody.write(bytes)
    exchange.close()
  })
  server.start()

  val baseUrl: String = s"http://127.0.0.1:${server.getAddress.getPort}/v1"
  def received: List[Received] = log.asScala.toList
  def stop(): Unit = server.stop(0)

object FakeServer:
  def withServer[A](script: (Int, String)*)(body: FakeServer => A): A =
    val server = FakeServer(script.toList)
    try body(server) finally server.stop()

  /** A chat completion as llama.cpp returns one, with fields the client ignores.
    * The texts are JSON-escaped, so a whole Java file can be the content.
    */
  def completion(content: String, reasoning: Option[String] = None): String =
    def quoted(text: String): String = io.bullet.borer.Json.encode(text).toUtf8String
    val thinking = reasoning.fold("")(r => s""","reasoning_content":${quoted(r)}""")
    s"""{"id":"chatcmpl-1","object":"chat.completion","created":1791328342,"model":"m.gguf",""" +
      s""""system_fingerprint":"b1","choices":[{"index":0,"finish_reason":"stop",""" +
      s""""message":{"role":"assistant","content":${quoted(content)}$thinking}}],""" +
      """"usage":{"prompt_tokens":120,"completion_tokens":45,"total_tokens":165},"timings":{"predicted_ms":900.5}}"""
