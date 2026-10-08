package pag.campaign

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Using

import io.bullet.borer.Json
import pag.campaign.FakeServer.{completion, withServer}
import pag.cli.DomainJars.apiPath

/** `campaign report` (experiments.md, The report): Tables 1 and 2 from attempt
  * records and the inspection file, which it never writes.
  */
class ReportSuite extends munit.FunSuite:

  val repo: Path = Repo.root().fold(e => throw IllegalStateException(e), identity)

  /** A real record from a fast run (the model answers without files), varied per test. */
  lazy val base: AttemptRecord =
    withServer(200 -> completion("no files")) { s =>
      val dir = Files.createTempDirectory("results")
      try
        val agent = AgentConfig(s.baseUrl, "m", retries = 0)
        val tools = Tools(List("true"), repo.resolve("domains/build-template"), apiPath,
          Paths.get(classOf[pag.probe.Rand].getProtectionDomain.getCodeSource.getLocation.toURI), repo.resolve("corpora/smoke"))
        val prompt = Prompt.assemble(repo, "generator-v1").fold(e => fail(e), identity)
        Attempt.run("c", 1, agent, ChatClient(agent), prompt, tools, dir.resolve("attempt-001"))
      finally Using.resource(Files.walk(dir))(_.iterator.asScala.toList.reverse.foreach(Files.delete))
    }

  val probes: List[String] = List("Const1", "Const2", "Sign1")

  /** An evaluated attempt with these cells (over three targets). */
  def evaluated(n: Int, cells: List[String]): AttemptRecord =
    val proved = cells.count(_ == "R")
    base.copy(attempt = f"attempt-$n%03d", sample = n, elapsedMs = 90000,
      targets = probes.zip(cells).map((p, c) => TargetRecord(p, 1, p == "Const2", 0, c, Some(0), None, "", false, 1)),
      summary = Summary(false, 4, builds = true, 10, 1, loads = true, cells, proved, cells.contains("✗")))

  def failed(n: Int): AttemptRecord = base.copy(attempt = f"attempt-$n%03d", sample = n) // "no files in the reply"

  /** A results directory holding these campaigns, deleted afterwards. */
  def withResults[A](campaigns: Map[String, List[AttemptRecord]])(body: Path => A): A =
    import AttemptRecord.given
    val dir = Files.createTempDirectory("results")
    try
      for (name, records) <- campaigns; r <- records do
        val d = Files.createDirectories(dir.resolve(name).resolve(r.attempt))
        Files.write(d.resolve("attempt.json"), Json.encode(r).toByteArray)
      body(dir)
    finally Using.resource(Files.walk(dir))(_.iterator.asScala.toList.reverse.foreach(Files.delete))

  val campaigns: Map[String, List[AttemptRecord]] = Map(
    "e1-rung0-qwen3.5-27b" -> List(evaluated(1, List("R", "A", "R")), evaluated(2, List("R", "✗", "A")), failed(3)),
    "e1-rung0-qwen3.5-0.8b" -> List(failed(1), failed(2)),
    "e1-rung0-qwen3.5-4b" -> List(evaluated(1, List("A", "A", "R")), evaluated(2, List("R", "A", "R"))),
    "phase10-check" -> List(failed(1))
  )

  val inspections: Inspections = Inspections(Map(
    "e1-rung0-qwen3.5-27b/attempt-001" -> Inspection(Some("intervals"), Some("looks sound"), acceptable = Some(true)),
    "e1-rung0-qwen3.5-4b/attempt-002" -> Inspection(Some("signs_ish"), acceptable = Some(false))
  ))

  test("model sizes are read from campaign names"):
    assertEquals(List("qwen3.5-0.8b", "x-27b", "qwen3.5-9b-q8", "unnamed").map(Report.size),
      List(0.8, 27.0, 9.0, Double.MaxValue))

  test("only the prefix's campaigns, smallest model first"):
    withResults(campaigns) { dir =>
      assertEquals(Report.campaigns(dir, "e1-rung0-").map(_.model), List("qwen3.5-0.8b", "qwen3.5-4b", "qwen3.5-27b"))
    }

  test("Table 1: one row per attempt, with its stop, cells, proved, and the inspection where there is one"):
    withResults(campaigns) { dir =>
      val t = Report.table1(Report.campaigns(dir, "e1-rung0-"), inspections)
      assert(t.contains("qwen3.5-27b & 1 & evaluated & 4 & yes & 9/10 & yes & R & A & R & 2 &"), t)
      assert(t.contains("& intervals & looks sound & yes \\\\"), t)
      assert(t.contains("qwen3.5-27b & 2 & evaluated & 4 & yes & 9/10 & yes & R & $\\times$ & A & 1 &"), t)
      assert(t.contains("qwen3.5-0.8b & 1 & no files in the reply & 0 & no &  & no &  &  &  &  &"), t)
      assert(t.contains("signs\\_ish"), "inspection text is escaped")
      assert(!t.contains("phase10-check"), t)
    }

  test("Table 2: per model, both bars, unsound, median proved, and where the rest stopped"):
    withResults(campaigns) { dir =>
      val t = Report.table2(Report.campaigns(dir, "e1-rung0-"), inspections)
      // 27b: attempt 1 mechanically acceptable (proves 2); attempt 2 unsound; attempt 3 no files
      assert(t.contains("qwen3.5-27b & 3 & 1/3 & 1/1 & 1 & 2 & 0 & 0 & 0 & 1 & 0 & 0 \\\\"), t)
      // 4b: both acceptable, proving 1 and 2: median 1.5; one inspected, rejected
      assert(t.contains("qwen3.5-4b & 2 & 2/2 & 0/1 & 0 & 1.5 &"), t)
      assert(t.contains("qwen3.5-0.8b & 2 & 0/2 & 0/0 & 0 &  & 0 & 0 & 0 & 2 & 0 & 0 \\\\"), t)
    }

  test("write: tables and prompt written, deterministic, the inspection file untouched, stray keys warned of"):
    withResults(campaigns) { dir =>
      import Report.given
      val inspectionFile = dir.resolve("inspection.json")
      val withStray = inspections.copy(attempts = inspections.attempts + ("e1-rung0-gone/attempt-001" -> Inspection()))
      Files.write(inspectionFile, Json.encode(withStray).toByteArray)
      val before = Files.readAllBytes(inspectionFile)
      val out = dir.resolve("tables")
      val warnings = Report.write(dir, "e1-rung0-", inspectionFile, out).fold(e => fail(e), identity)
      assertEquals(warnings, List("inspection.json names e1-rung0-gone/attempt-001, which is not among the campaigns read"))
      val first = List("table1.tex", "table2.tex", "prompt.txt").map(f => Files.readString(out.resolve(f)))
      Report.write(dir, "e1-rung0-", inspectionFile, out)
      assertEquals(List("table1.tex", "table2.tex", "prompt.txt").map(f => Files.readString(out.resolve(f))), first)
      assert(java.util.Arrays.equals(Files.readAllBytes(inspectionFile), before), "the inspection file was written")
      assert(first(2).startsWith("=== system ===\n") && first(2).contains("=== user ===\n# Task"), first(2))
    }

  test("no inspection file yet: blanks, not an error"):
    withResults(campaigns) { dir =>
      assertEquals(Report.readInspections(dir.resolve("absent.json")), Right(Inspections()))
    }

  test("escaping LaTeX's special characters"):
    assertEquals(Report.escape("a_b & 50% #1 $x {y} ~ ^ \\"),
      "a\\_b \\& 50\\% \\#1 \\$x \\{y\\} \\textasciitilde{} \\textasciicircum{} \\textbackslash{}")
