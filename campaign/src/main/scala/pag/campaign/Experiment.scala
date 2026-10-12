package pag.campaign

import java.nio.file.{Files, Path}
import scala.compiletime.constValueTuple
import scala.deriving.Mirror
import io.bullet.borer.{Codec, Dom, Json}
import io.bullet.borer.NullOptions.given
import io.bullet.borer.derivation.MapBasedCodecs.*

/** How the generator is asked, from an experiment file: `AgentConfig` without the model and its source, which
  * come from the provenance record. The fields that are not options have no default, so the file must state them.
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

/** A provenance record as written (implementation_strategy.md §10): the model's source block, and `path`, where its
  * GGUF is on the server. Every field is optional here so that a missing one is reported by name.
  */
final case class ProvenanceRecord(
    url: Option[String] = None,
    file: Option[String] = None,
    revision: Option[String] = None,
    sha256: Option[String] = None,
    path: Option[String] = None
)

/** A provenance record read and checked: the model's name, the GGUF's path (the model id) and its source. */
final case class Model(name: String, gguf: Path, source: ModelSource)

/** A campaign to run: an experiment file applied to a provenance record (implementation_strategy.md §10). */
final case class Experiment(campaign: String, agent: AgentConfig, prompt: String, samples: Int, feedbackRounds: Int):

  /** A new run, or the run in `dir` continued. A resumed run must be of this campaign; its pin, the sample count
    * included, is checked by `Campaign.generate`.
    */
  def start(resume: Option[Path]): Either[String, Start] = resume match
    case None => Right(Start.Fresh(campaign, samples))
    case Some(dir) =>
      val name = Option(dir.toAbsolutePath.normalize.getFileName).fold("")(_.toString)
      Either.cond(name == campaign || name.matches(s"\\Q$campaign\\E-\\d{8}T\\d{6}Z"), Start.Resume(dir, samples),
        s"$dir is not a run of campaign $campaign, which these files name")

object Experiment:
  given Codec[GeneratorSettings] = deriveCodec[GeneratorSettings]
  given Codec[ExperimentAgents] = deriveCodec[ExperimentAgents]
  given Codec[ExperimentFile] = deriveCodec[ExperimentFile]
  given Codec[ProvenanceRecord] = deriveCodec[ProvenanceRecord]

  /** The keys an object may hold, and the shapes of those that are objects themselves. */
  private final case class Shape(keys: Set[String], nested: Map[String, Shape] = Map.empty)

  private inline def keysOf[T](using m: Mirror.ProductOf[T]): Set[String] =
    constValueTuple[m.MirroredElemLabels].productIterator.map(_.toString).toSet

  private val ExperimentShape: Shape = Shape(keysOf[ExperimentFile], Map(
    "agents" -> Shape(keysOf[ExperimentAgents], Map("generator" -> Shape(keysOf[GeneratorSettings])))))
  private val RecordShape: Shape = Shape(keysOf[ProvenanceRecord])

  /** Reads both files and checks that the GGUF is there. The campaign's name is the experiment file's base name and
    * the model's name. The model id is the GGUF's absolute path, as llama-server reports it.
    */
  def read(experimentFile: Path, recordFile: Path): Either[String, Experiment] =
    for
      e <- decode[ExperimentFile](experimentFile, ExperimentShape, "the model comes from the provenance record")
      _ <- Either.cond(e.samples > 0, (), s"$experimentFile: samples must be positive, not ${e.samples}")
      m <- model(recordFile)
      _ <- Either.cond(Files.isRegularFile(m.gguf), (), s"$recordFile: there is no GGUF at ${m.gguf}")
    yield Experiment(s"${experimentFile.getFileName.toString.stripSuffix(".json")}-${m.name}",
      e.agents.generator.agent(m.gguf.toString, m.source), e.prompt, e.samples, e.feedbackRounds)

  /** Reads a provenance record, without looking for its GGUF. Its `path` must be absolute and end in its `file`. The
    * model's name is the last segment of its url, first letter lowered.
    */
  def model(recordFile: Path): Either[String, Model] =
    for
      r <- decode[ProvenanceRecord](recordFile, RecordShape,
        "a provenance record holds url, file, revision, sha256 and path")
      url <- r.url.toRight(s"$recordFile: no url")
      file <- r.file.toRight(s"$recordFile: no file")
      path <- r.path.toRight(s"$recordFile: no path")
      gguf = Path.of(path)
      _ <- Either.cond(gguf.isAbsolute && gguf.getFileName.toString == file, (),
        s"$recordFile: path $path must be absolute and name its file, $file")
      name = url.stripSuffix("/").split('/').last
      _ <- Either.cond(name.nonEmpty, (), s"$recordFile: no model name in url $url")
    yield Model(s"${name.head.toLower}${name.tail}", gguf, ModelSource(r.url, r.file, r.revision, r.sha256))

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
