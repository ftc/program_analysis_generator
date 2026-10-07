package pag.campaign

import java.nio.file.{Files, Path}
import io.bullet.borer.{Codec, Json}
import io.bullet.borer.derivation.MapBasedCodecs.*

/** One target: a probe's `reach(id)`, its true answer, and the inputs `pag check`
  * runs with — for a reachable target, inputs that reach it.
  */
final case class Target(probe: String, reach: Int, reachable: Boolean, inputs: List[BigInt], rung: Int, provableBy: String)

/** A corpus of probes with known answers (implementation_strategy.md Phase 10):
  * `<dir>/manifest.json` and one `<probe>.java` per probe. Never shown to a model.
  */
final case class Corpus(about: String, targets: List[Target])

object Corpus:
  given Codec[Target] = deriveCodec[Target]
  given Codec[Corpus] = deriveCodec[Corpus]

  def read(dir: Path): Either[String, Corpus] =
    val manifest = dir.resolve("manifest.json")
    if !Files.isRegularFile(manifest) then Left(s"no corpus manifest at $manifest")
    else
      Json.decode(Files.readAllBytes(manifest)).to[Corpus].valueEither.left.map(e => s"$manifest: ${e.getMessage}")
        .flatMap { c =>
          val missing = c.targets.map(_.probe).distinct.filterNot(p => Files.isRegularFile(dir.resolve(s"$p.java")))
          if missing.isEmpty then Right(c) else Left(s"$dir: no source for probes ${missing.mkString(", ")}")
        }
