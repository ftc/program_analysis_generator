package pag.campaign

import java.nio.file.{Files, Path}
import scala.compiletime.constValueTuple
import scala.deriving.Mirror
import io.bullet.borer.{Codec, Dom, Json}
import io.bullet.borer.NullOptions.given
import io.bullet.borer.derivation.MapBasedCodecs.*

/** How the generator is asked, from an experiment file: `AgentConfig` without the model and its source, which
  * come from the model file. The fields that are not options have no default, so the file must state them.
  */
final case class GeneratorSettings(
    baseUrl: String,
    apiKeyEnv: Option[String] = None, // None: no Authorization header
    temperature: Double,
    topP: Option[Double] = None, // the options, None: not sent, so the server's or template's default applies
    topK: Option[Int] = None,
    minP: Option[Double] = None,
    presencePenalty: Option[Double] = None,
    thinking: Option[Boolean] = None,
    maxTokens: Option[Int] = None,
    timeoutSeconds: Int,
    retries: Int
):
  def agent(model: String, source: ModelSource): AgentConfig =
    AgentConfig(baseUrl, model, apiKeyEnv, temperature, topP, topK, minP, presencePenalty, thinking, maxTokens,
      timeoutSeconds, retries, source)

final case class ExperimentAgents(generator: GeneratorSettings)

/** An experiment file as written (implementation_strategy.md §10): every experimental parameter of one arm. */
final case class ExperimentFile(prompt: String, samples: Int, feedbackRounds: Int = 0, agents: ExperimentAgents)

/** A campaign to run: an experiment file applied to a model file (implementation_strategy.md §10). */
final case class Experiment(campaign: String, agent: AgentConfig, prompt: String, samples: Int, feedbackRounds: Int):

  /** A new run, or the run in `dir` continued. A resumed run must be of this campaign, and if it pins a sample
    * count, it must be this file's; the rest of its pin is checked by `Campaign.generate`.
    */
  def start(resume: Option[Path]): Either[String, Start] = resume match
    case None => Right(Start.Fresh(campaign, samples))
    case Some(dir) =>
      val name = Option(dir.toAbsolutePath.normalize.getFileName).fold("")(_.toString)
      val pinned = Campaign.pinned(dir).flatMap(_.samples)
      for
        _ <- Either.cond(name == campaign || name.matches(s"\\Q$campaign\\E-\\d{8}T\\d{6}Z"), (),
          s"$dir is not a run of campaign $campaign, which these files name")
        _ <- pinned.fold(Right(()))(n => Either.cond(n == samples, (),
          s"$dir pins $n samples, but the experiment file says $samples"))
      yield Start.Resume(dir, if pinned.isDefined then None else Some(samples)) // None: the pinned count

object Experiment:
  given Codec[GeneratorSettings] = deriveCodec[GeneratorSettings]
  given Codec[ExperimentAgents] = deriveCodec[ExperimentAgents]
  given Codec[ExperimentFile] = deriveCodec[ExperimentFile]
  given Codec[ModelSource] = deriveCodec[ModelSource]

  /** The keys an object may hold, and the shapes of those that are objects themselves. */
  private final case class Shape(keys: Set[String], nested: Map[String, Shape] = Map.empty)

  private inline def keysOf[T](using m: Mirror.ProductOf[T]): Set[String] =
    constValueTuple[m.MirroredElemLabels].productIterator.map(_.toString).toSet

  private val ExperimentShape: Shape = Shape(keysOf[ExperimentFile], Map(
    "agents" -> Shape(keysOf[ExperimentAgents], Map("generator" -> Shape(keysOf[GeneratorSettings])))))
  private val ModelShape: Shape = Shape(keysOf[ModelSource])

  /** Reads both files. The campaign's name is the experiment file's base name and the model's name: the last
    * segment of its url, first letter lowered. The model id is the GGUF's absolute path, as llama-server reports it.
    */
  def read(experimentFile: Path, modelFile: Path): Either[String, Experiment] =
    for
      e <- decode[ExperimentFile](experimentFile, ExperimentShape, "the model comes from the model file")
      _ <- Either.cond(e.samples > 0, (), s"$experimentFile: samples must be positive, not ${e.samples}")
      m <- decode[ModelSource](modelFile, ModelShape, "a model file is a source block: url, file, revision, sha256")
      url <- m.url.toRight(s"$modelFile: no url")
      file <- m.file.toRight(s"$modelFile: no file")
      gguf = modelFile.toAbsolutePath.normalize.resolveSibling(file)
      _ <- Either.cond(Files.isRegularFile(gguf), (), s"$modelFile names $file, but there is no $gguf")
      model = url.stripSuffix("/").split('/').last
      _ <- Either.cond(model.nonEmpty, (), s"$modelFile: no model name in url $url")
    yield Experiment(s"${experimentFile.getFileName.toString.stripSuffix(".json")}-${model.head.toLower}${model.tail}",
      e.agents.generator.agent(gguf.toString, m), e.prompt, e.samples, e.feedbackRounds)

  /** Decodes `file`, first refusing any key `shape` does not allow: the decoder would ignore it silently. */
  private def decode[T: Codec](file: Path, shape: Shape, hint: String): Either[String, T] =
    def unknown(e: Dom.Element, s: Shape, at: String): List[String] = e match
      case m: Dom.MapElem => m.stringKeyedMembers.toList.flatMap { (k, v) =>
          val here = if at.isEmpty then k else s"$at.$k"
          if !s.keys(k) then List(here) else s.nested.get(k).fold(Nil)(unknown(v, _, here))
        }
      case _ => Nil // not an object: the typed decode reports it
    for
      _ <- Either.cond(Files.isRegularFile(file), (), s"no file at $file")
      bytes = Files.readAllBytes(file)
      dom <- Json.decode(bytes).to[Dom.Element].valueEither.left.map(e => s"$file: ${e.getMessage}")
      _ <- unknown(dom, shape, "") match
        case Nil  => Right(())
        case keys => Left(s"$file: unknown ${keys.mkString(", ")} ($hint)")
      t <- Json.decode(bytes).to[T].valueEither.left.map(e => s"$file: ${e.getMessage}")
    yield t
