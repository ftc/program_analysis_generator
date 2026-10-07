package pag.results

import io.bullet.borer.{Cbor, Codec, Decoder, Encoder, Json}
import io.bullet.borer.NullOptions.given
import io.bullet.borer.derivation.MapBasedCodecs.*

/** Every codec, in one object (implementation_strategy.md §13), derived rather
  * than written: a new case of a sealed type cannot silently go unencoded.
  * Case classes are JSON objects keyed by field name; `None` is `null`.
  *
  * Record types must not have default values: Borer leaves a field out of the
  * encoding when it equals its default, so a record would silently lose it.
  */
object Codecs:
  given Codec[ErrorInfo] = deriveCodec[ErrorInfo]
  given Codec[Incomplete] = deriveAllCodecs[Incomplete]
  given Codec[Verdict] = deriveAllCodecs[Verdict]
  given Codec[Outcome] = deriveAllCodecs[Outcome]
  given Codec[Reachable] = deriveCodec[Reachable]
  given Codec[Envelope] = deriveCodec[Envelope]
  given Codec[AnalysisSummary] = deriveCodec[AnalysisSummary]
  given Codec[RunSummary] = deriveCodec[RunSummary]
  given Codec[CheckResult] = deriveCodec[CheckResult]

/** The two wire formats §13 provides for; one set of codecs serves both. */
enum Format:
  case Json, Cbor

/** The one place records are turned into bytes and back. */
object Wire:
  def encode[T: Encoder](value: T, format: Format = Format.Json): Array[Byte] = format match
    case Format.Json => Json.encode(value).toByteArray
    case Format.Cbor => Cbor.encode(value).toByteArray

  def decode[T: Decoder](bytes: Array[Byte], format: Format = Format.Json): Either[String, T] =
    val decoded = format match
      case Format.Json => Json.decode(bytes).to[T].valueEither
      case Format.Cbor => Cbor.decode(bytes).to[T].valueEither
    decoded.left.map(_.getMessage)
