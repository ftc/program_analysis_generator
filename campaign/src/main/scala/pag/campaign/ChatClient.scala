package pag.campaign

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.annotation.tailrec

import io.bullet.borer.{Codec, Dom, Json}
import io.bullet.borer.NullOptions.given
import io.bullet.borer.derivation.MapBasedCodecs.*
import io.bullet.borer.derivation.key

/** One chat message. */
final case class ChatMessage(role: String, content: String)

/** What a model answered, and everything about the exchange an attempt record
  * keeps (experiments.md E1): the raw response body as well as the parts read
  * from it.
  */
final case class ChatReply(
    content: String,
    reasoning: Option[String], // a reasoning model's separate thinking, if the server returns it
    finishReason: Option[String],
    promptTokens: Option[Long],
    completionTokens: Option[Long],
    model: Option[String], // as the server names it
    rawResponse: String,
    elapsedMs: Long,
    tries: Int
)

/** Why there is no reply. `retryable` failures were retried until the budget ran out. */
final case class ChatFailure(message: String, status: Option[Int], body: Option[String], tries: Int)

/** A client for the OpenAI-compatible `/v1/chat/completions` API that Ollama,
  * vLLM, llama.cpp and hosted models serve (implementation_strategy.md §10).
  * Connection failures, 429 and 5xx are retried with backoff (§12); anything else
  * is reported at once. `env` and `sleep` are parameters so tests can replace them.
  */
final class ChatClient(
    agent: AgentConfig,
    env: String => Option[String] = name => Option(System.getenv(name)),
    sleep: Long => Unit = ms => Thread.sleep(ms)
):
  private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
  private val base: String = agent.baseUrl.stripSuffix("/")

  def chat(messages: List[ChatMessage]): Either[ChatFailure, ChatReply] =
    val body = Json.encode(ChatClient.Request(agent.model, messages, agent.temperature, agent.maxTokens)).toUtf8String
    val request = authorized(HttpRequest.newBuilder(URI.create(s"$base/chat/completions")))
      .timeout(Duration.ofSeconds(agent.timeoutSeconds.toLong))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build()
    val started = System.currentTimeMillis()

    @tailrec
    def attempt(tries: Int): Either[ChatFailure, ChatReply] =
      val outcome =
        try
          val response = http.send(request, HttpResponse.BodyHandlers.ofString())
          if response.statusCode == 200 then
            ChatClient.parse(response.body, System.currentTimeMillis() - started, tries).left
              .map(m => (ChatFailure(m, Some(200), Some(response.body), tries), false))
          else
            val retryable = response.statusCode == 429 || response.statusCode >= 500
            Left((ChatFailure(s"HTTP ${response.statusCode}", Some(response.statusCode), Some(response.body), tries),
              retryable))
        catch
          case e: java.io.IOException => Left((ChatFailure(s"no response: $e", None, None, tries), true))
      outcome match
        case Left((_, true)) if tries <= agent.retries =>
          sleep(1000L << (tries - 1)) // 1 s, 2 s, 4 s, ...
          attempt(tries + 1)
        case Left((failure, _)) => Left(failure)
        case Right(reply)       => Right(reply)

    attempt(1)

  /** The server's own report of its models (`/v1/models`), raw, for the attempt record. */
  def models(): Either[String, String] =
    try
      val request = authorized(HttpRequest.newBuilder(URI.create(s"$base/models")))
        .timeout(Duration.ofSeconds(30)).GET().build()
      val response = http.send(request, HttpResponse.BodyHandlers.ofString())
      if response.statusCode == 200 then Right(response.body) else Left(s"HTTP ${response.statusCode}")
    catch case e: java.io.IOException => Left(s"no response: $e")

  private def authorized(b: HttpRequest.Builder): HttpRequest.Builder =
    agent.apiKeyEnv.flatMap(env).fold(b)(key => b.header("Authorization", s"Bearer $key"))

object ChatClient:

  /** The request body. Field names are the API's, hence `max_tokens`. Its default
    * of None makes Borer leave it out entirely rather than send null, which not
    * every server accepts.
    */
  final case class Request(
      model: String,
      messages: List[ChatMessage],
      temperature: Double,
      @key("max_tokens") maxTokens: Option[Int] = None
  )

  given Codec[ChatMessage] = deriveCodec[ChatMessage]
  given Codec[Request] = deriveCodec[Request]

  /** The parts of a chat completion we keep, read from the generic JSON tree so
    * the many other fields servers add do not matter.
    */
  def parse(body: String, elapsedMs: Long, tries: Int): Either[String, ChatReply] =
    Json.decode(body.getBytes("UTF-8")).to[Dom.Element].valueEither.left.map(_.getMessage).flatMap { root =>
      val choice = field(root, "choices").collect { case a: Dom.ArrayElem => a.elems.headOption }.flatten
      val message = choice.flatMap(field(_, "message"))
      message.flatMap(m => text(field(m, "content"))) match
        case None => Left(s"no choices[0].message.content in the response")
        case Some(content) =>
          val usage = field(root, "usage")
          Right(ChatReply(
            content,
            message.flatMap(m => text(field(m, "reasoning_content"))),
            choice.flatMap(c => text(field(c, "finish_reason"))),
            usage.flatMap(u => number(field(u, "prompt_tokens"))),
            usage.flatMap(u => number(field(u, "completion_tokens"))),
            text(field(root, "model")),
            body,
            elapsedMs,
            tries
          ))
    }

  private def field(e: Dom.Element, name: String): Option[Dom.Element] = e match
    case m: Dom.MapElem => m.toMap.collectFirst { case (Dom.StringElem(k), v) if k == name => v }
    case _              => None

  private def text(e: Option[Dom.Element]): Option[String] = e.collect { case Dom.StringElem(s) => s }

  private def number(e: Option[Dom.Element]): Option[Long] = e.collect {
    case Dom.IntElem(n)  => n.toLong
    case Dom.LongElem(n) => n
  }
