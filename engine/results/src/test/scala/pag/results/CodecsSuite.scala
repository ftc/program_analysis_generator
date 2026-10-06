package pag.results

import java.nio.charset.StandardCharsets.UTF_8
import pag.results.Codecs.given

/** The codecs (implementation_strategy.md §13): every case of every sealed type
  * round-trips in both formats, and the JSON reads the way `cat` should show it.
  */
class CodecsSuite extends munit.FunSuite:

  val boom: ErrorInfo = ErrorInfo("java.lang.IllegalStateException", Some("boom"), "trace")
  val verdicts: List[Verdict] = List(
    Verdict.Refuted,
    Verdict.Alarm,
    Verdict.Inconclusive(Incomplete.IterationLimit(10)),
    Verdict.Inconclusive(Incomplete.Deadline(60000)),
    Verdict.Inconclusive(Incomplete.DomainFailure("transfer", boom)),
    Verdict.Inconclusive(Incomplete.DomainFailure("join", ErrorInfo("java.lang.NullPointerException", None, "")))
  )
  val outcomes: List[Outcome] =
    List(Outcome.Unsound, Outcome.Consistent, Outcome.NoVerdict(Incomplete.IterationLimit(3)))

  def sample(verdict: Verdict, outcome: Outcome): CheckResult = CheckResult(
    Envelope("0123456789abcdef0123456789abcdef01234567", dirty = false, "bigint-main-v1"),
    Reachable(1),
    "probes/c08/out",
    List(BigInt(5), BigInt("-123456789012345678901234567890")),
    AnalysisSummary(verdict, 9, 0, 3, Some(27), Some(0)),
    RunSummary(List(BigInt(1)), 0, timedOut = false, 412),
    outcome
  )

  test("every verdict and every outcome round-trips, in JSON and in CBOR"):
    for v <- verdicts; o <- outcomes; f <- Format.values do
      val r = sample(v, o)
      assertEquals(Wire.decode[CheckResult](Wire.encode(r, f), f), Right(r), s"$v, $o, $f")

  test("the JSON reads as it should: field names as keys, null for absent, big integers exact"):
    val r = sample(Verdict.Refuted, Outcome.Unsound).copy(
      analysis = AnalysisSummary(Verdict.Refuted, 9, 0, 3, None, None))
    assertEquals(String(Wire.encode(r), UTF_8),
      """{"envelope":{"commit":"0123456789abcdef0123456789abcdef01234567","dirty":false,"profile":"bigint-main-v1"},""" +
        """"query":{"id":1},"classes":"probes/c08/out","inputs":[5,-123456789012345678901234567890],""" +
        """"analysis":{"verdict":"Refuted","iterations":9,"unexplored":0,"elapsedMs":3,"edges":null,"uncertified":null},""" +
        """"run":{"reached":[1],"exitCode":0,"timedOut":false,"elapsedMs":412},"outcome":"Unsound"}""")

  test("a case with fields is a one-key object naming the case"):
    val json = String(Wire.encode[Verdict](Verdict.Inconclusive(Incomplete.IterationLimit(10))), UTF_8)
    assertEquals(json, """{"Inconclusive":{"why":{"IterationLimit":{"at":10}}}}""")

  test("decoding is strict: a missing field is an error, not a default"):
    val r = Wire.decode[Reachable]("{}".getBytes(UTF_8))
    assert(r.isLeft && r.left.exists(_.contains("id")), r)

  test("the build info names a commit"):
    assert(BuildInfo.commit.matches("[0-9a-f]{40}"), BuildInfo.commit)
    assertEquals(Envelope.current("bigint-main-v1").commit, BuildInfo.commit)
