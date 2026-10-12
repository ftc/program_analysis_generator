package pag.campaign

/** Where a model came from and which bytes it is (implementation_strategy.md
  * §10): the source block of a provenance record, written once per GGUF by
  * `scripts/serve_model.sh` (or by hand for a model it did not make), and copied
  * into every attempt record uninterpreted — a placeholder for a fuller
  * provenance scheme. A field left None was not entered.
  */
final case class ModelSource(
    url: Option[String] = None,
    file: Option[String] = None,
    revision: Option[String] = None,
    sha256: Option[String] = None
)

/** One agent: an OpenAI-compatible endpoint, the model to ask, and how. The key
  * is named by environment variable, never written here (§10); with none, no
  * Authorization header is sent. A campaign's comes from `Experiment.read`, which
  * sets every field, so the defaults here serve only tests.
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
