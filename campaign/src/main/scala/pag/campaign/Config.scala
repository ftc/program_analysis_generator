package pag.campaign

import java.nio.file.{Files, Path}
import io.bullet.borer.{Codec, Json}
import io.bullet.borer.NullOptions.given
import io.bullet.borer.derivation.MapBasedCodecs.*

/** Where a model came from and which bytes it is (implementation_strategy.md
  * §10), entered by hand once per model and copied into every attempt record
  * uninterpreted — a placeholder for a fuller provenance scheme. A field left
  * None was not entered.
  */
final case class ModelSource(
    url: Option[String] = None,
    file: Option[String] = None,
    revision: Option[String] = None,
    sha256: Option[String] = None
)

/** One agent: an OpenAI-compatible endpoint, the model to ask, and how. The key
  * is named by environment variable, never written here (§10); with none, no
  * Authorization header is sent.
  */
final case class AgentConfig(
    baseUrl: String,
    model: String,
    apiKeyEnv: Option[String] = None, // None: no Authorization header
    temperature: Double = 0.2,
    topP: Option[Double] = None, // the sampling fields llama.cpp accepts; unset means the server's default
    topK: Option[Int] = None,
    minP: Option[Double] = None,
    presencePenalty: Option[Double] = None,
    thinking: Option[Boolean] = None, // sent as chat_template_kwargs.enable_thinking; unset: the template's default
    maxTokens: Option[Int] = None, // counts the thinking too; unset: the server's default
    timeoutSeconds: Int = 1800, // one answer from a local 27B model can take many minutes
    retries: Int = 3, // after the first try, for connection failures, 429 and 5xx
    source: ModelSource = ModelSource()
)

final case class Agents(generator: AgentConfig)

/** A campaign's configuration (§10): JSON, read once. Optional fields may be left out. */
final case class CampaignConfig(agents: Agents)

object CampaignConfig:
  given Codec[ModelSource] = deriveCodec[ModelSource]
  given Codec[AgentConfig] = deriveCodec[AgentConfig]
  given Codec[Agents] = deriveCodec[Agents]
  given Codec[CampaignConfig] = deriveCodec[CampaignConfig]

  def parse(json: String): Either[String, CampaignConfig] =
    Json.decode(json.getBytes("UTF-8")).to[CampaignConfig].valueEither.left.map(_.getMessage)

  def read(file: Path): Either[String, CampaignConfig] =
    if !Files.isRegularFile(file) then Left(s"no config file at $file")
    else
      val text = Files.readString(file)
      val placeholders = Placeholder.findAllMatchIn(text).map(_.group(0)).toList
      if placeholders.nonEmpty then
        Left(s"$file still has placeholders to fill in: ${placeholders.mkString(", ")} " +
          "(a campaign pins its config on the first run, so it must not start with one)")
      else parse(text).left.map(e => s"$file: $e")

  /** A string value that is still an example's `<...>` placeholder. */
  private val Placeholder = "\"<[^\"]*>\"".r
