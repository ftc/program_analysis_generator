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
  val corpus: Path = repo.resolve("corpora/smoke")

  /** An evaluated attempt with these cells (over three targets). */
  def evaluated(n: Int, cells: List[String], tests: Tests = Tests.Ran(10, 1, 0, "")): AttemptRecord =
    RecordFixtures.evaluated(base, cells, probes, (p, _) => p == "Const2", tests)
      .copy(attempt = f"attempt-$n%03d", sample = n, elapsedMs = 90000)

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
      val warnings = Report.write(dir, "e1-rung0-", inspectionFile, corpus, out).fold(e => fail(e), identity)
      assertEquals(warnings, List("inspection.json names e1-rung0-gone/attempt-001, which is not among the campaigns read"))
      val files = List("table1.tex", "table2.tex", "settings.tex", "corpus.tex", "prompt.txt")
      val first = files.map(f => Files.readString(out.resolve(f)))
      Report.write(dir, "e1-rung0-", inspectionFile, corpus, out)
      assertEquals(files.map(f => Files.readString(out.resolve(f))), first)
      assert(java.util.Arrays.equals(Files.readAllBytes(inspectionFile), before), "the inspection file was written")
      assert(first(4).startsWith("=== system ===\n") && first(4).contains("=== user ===\n# Task"), first(4))
    }

  test("the settings table is read from the records, and the corpus table from the manifest"):
    withResults(campaigns) { dir =>
      val s = Report.settings(Report.campaigns(dir, "e1-rung0-"))
      assert(s.contains("qwen3.5-27b & 0.2 & default & default & default & default & default & default & 1800 s"), s)
      val c = Report.corpusTable(Corpus.read(corpus).fold(e => fail(e), identity))
      assert(c.contains("Loop2 & 4 & yes & 2 & nothing: reachable"), c)
      assert(c.contains("Const2 & 0 & yes & no input"), c)
    }

  test("the report builds with LuaLaTeX from generated tables, unsound marks and the prompt's Unicode included"):
    val latexmk = sys.env.getOrElse("PATH", "").split(java.io.File.pathSeparator).map(Paths.get(_).resolve("latexmk"))
      .exists(Files.isExecutable)
    assume(latexmk, "latexmk is not installed; the report's build is not checked here")
    withResults(campaigns) { dir =>
      import Report.given
      val report = Files.createDirectories(dir.resolve("report"))
      Files.copy(repo.resolve("report/report.tex"), report.resolve("report.tex"))
      Files.write(report.resolve("inspection.json"), Json.encode(inspections).toByteArray)
      Report.write(dir, "e1-rung0-", report.resolve("inspection.json"), corpus, report.resolve("tables"))
        .fold(e => fail(e), identity)
      val p = Processes.run(List("latexmk", "-lualatex", "-outdir=build", "-interaction=nonstopmode",
        "-halt-on-error", s"-cd", report.resolve("report.tex").toString), scala.concurrent.duration.DurationInt(3).minutes)
      assertEquals(p.exitCode, Some(0), p.stdout.takeRight(3000))
      assert(Files.size(report.resolve("build/report.pdf")) > 0)
      val log = Files.readString(report.resolve("build/report.log"))
      for f <- List("settings.tex", "corpus.tex", "table1.tex", "table2.tex", "prompt.txt") do
        assert(log.contains(s"pag-report: included $f"), s"the build did not include $f, so the report shows a placeholder")
      assert(!log.contains("Missing character"), "a character the fonts lack: " +
        log.linesIterator.filter(_.contains("Missing character")).take(5).mkString("\n"))
    }

  test("no inspection file yet: blanks, not an error"):
    withResults(campaigns) { dir =>
      assertEquals(Report.readInspections(dir.resolve("absent.json")), Right(Inspections()))
    }

  test("escaping LaTeX's special characters"):
    assertEquals(Report.escape("a_b & 50% #1 $x {y} ~ ^ \\"),
      "a\\_b \\& 50\\% \\#1 \\$x \\{y\\} \\textasciitilde{} \\textasciicircum{} \\textbackslash{}")

  test("the Tests cell: passing out of run, did not compile, or blank when the domain did not build"):
    val built = Summary(false, 2, builds = true, 4, 1, loads = true, Nil, 0, false, None)
    assertEquals(Report.tests(built.copy(testsCompiled = Some(true))), "3/4")
    assertEquals(Report.tests(built.copy(testsRun = 0, testsFailed = 0, testsCompiled = Some(false))), "did not compile")
    assertEquals(Report.tests(built), "3/4", "a record from before testsCompiled was recorded")
    assertEquals(Report.tests(built.copy(builds = false)), "")

  test("Table 1 shows tests that did not compile"):
    val noCompile = evaluated(1, List("A", "A", "A"), Tests.DidNotCompile("BrokenTest.java:3: error"))
    withResults(Map("e1-rung0-qwen3.5-27b" -> List(noCompile))) { dir =>
      val t = Report.table1(Report.campaigns(dir, "e1-rung0-"), inspections)
      assert(t.contains("qwen3.5-27b & 1 & evaluated & 4 & yes & did not compile & yes & A & A & A & 0 &"), t)
    }

  // Runs: every rerun has its own directory, <campaign>-<UTC start>, and the report reads the latest of each campaign.

  test("a run directory's name splits into its campaign and start; other names are runs from before start times"):
    assertEquals(Report.runName("e1-rung0-qwen3.5-9B-20261009T172600Z"),
      RunName("e1-rung0-qwen3.5-9B", Some(java.time.Instant.parse("2026-10-09T17:26:00Z"))))
    assertEquals(Report.runName("e1-rung0-qwen3.5-9B"), RunName("e1-rung0-qwen3.5-9B", None))
    assertEquals(Report.runName("e1-test-2026"), RunName("e1-test-2026", None))
    assertEquals(Report.runName("e1-x-20261399T999999Z"), RunName("e1-x-20261399T999999Z", None), "not a real time")

  /** Writes a pin with this sample count into a run directory. */
  def pin(dir: Path, run: String, samples: Int): Unit =
    import Campaign.given
    Files.write(dir.resolve(run).resolve("campaign.json"),
      Json.encode(CampaignPin(base.agent, "v", "s", "c", "p", Some(samples))).toByteArray)

  test("two runs of one campaign: the later is read, and the skipped one is named"):
    withResults(Map(
      "e1-rung0-qwen3.5-4b-20261009T100000Z" -> List(failed(1)),
      "e1-rung0-qwen3.5-4b-20261010T100000Z" -> List(evaluated(1, List("R", "A", "R")))
    )) { dir =>
      val (cs, warnings) = Report.latestRuns(dir, "e1-rung0-")
      assertEquals(cs.map(c => (c.name, c.run, c.model)),
        List(("e1-rung0-qwen3.5-4b", "e1-rung0-qwen3.5-4b-20261010T100000Z", "qwen3.5-4b")))
      assertEquals(warnings, List("e1-rung0-qwen3.5-4b: read run e1-rung0-qwen3.5-4b-20261010T100000Z, " +
        "skipped e1-rung0-qwen3.5-4b-20261009T100000Z"))
    }

  test("a directory from before start times is older than any timestamped run of the same campaign"):
    withResults(Map(
      "e1-rung0-qwen3.5-9b" -> List(evaluated(1, List("R", "A", "R"))),
      "e1-rung0-qwen3.5-9b-20261009T100000Z" -> List(failed(1))
    )) { dir =>
      assertEquals(Report.campaigns(dir, "e1-rung0-").map(_.run), List("e1-rung0-qwen3.5-9b-20261009T100000Z"))
    }

  test("each campaign's latest run, smallest model first, mixing old and new directories"):
    withResults(Map(
      "e1-rung0-qwen3.5-27b" -> List(failed(1)),
      "e1-rung0-qwen3.5-0.8b-20261011T000000Z" -> List(failed(1)),
      "e1-rung0-qwen3.5-4b-20261010T000000Z" -> List(failed(1))
    )) { dir =>
      assertEquals(Report.campaigns(dir, "e1-rung0-").map(_.model), List("qwen3.5-0.8b", "qwen3.5-4b", "qwen3.5-27b"))
    }

  test("a run read with fewer attempts than its pinned samples is warned of; a complete one is not"):
    withResults(Map(
      "e1-rung0-qwen3.5-4b-20261010T000000Z" -> List(failed(1), failed(2), failed(3)),
      "e1-rung0-qwen3.5-9b-20261010T000000Z" -> List(failed(1), failed(2))
    )) { dir =>
      pin(dir, "e1-rung0-qwen3.5-4b-20261010T000000Z", 5)
      pin(dir, "e1-rung0-qwen3.5-9b-20261010T000000Z", 2)
      assertEquals(Report.latestRuns(dir, "e1-rung0-")._2,
        List("e1-rung0-qwen3.5-4b-20261010T000000Z holds 3 of its 5 samples: resume it before trusting its rows"))
    }

  test("inspections are keyed by run directory: the run read is shown, a skipped run's key is warned of"):
    val older = "e1-rung0-qwen3.5-4b-20261009T100000Z"
    val newer = "e1-rung0-qwen3.5-4b-20261010T100000Z"
    withResults(Map(older -> List(evaluated(1, List("R", "A", "R"))), newer -> List(evaluated(1, List("R", "A", "R"))))) {
      dir =>
        import Report.given
        val ins = Inspections(Map(s"$newer/attempt-001" -> Inspection(Some("intervals")),
          s"$older/attempt-001" -> Inspection(Some("signs"))))
        val t = Report.table1(Report.campaigns(dir, "e1-rung0-"), ins)
        assert(t.contains("& intervals &") && !t.contains("signs"), t)
        Files.write(dir.resolve("inspection.json"), Json.encode(ins).toByteArray)
        val warnings = Report.write(dir, "e1-rung0-", dir.resolve("inspection.json"), corpus, dir.resolve("tables"))
          .fold(e => fail(e), identity)
        assert(warnings.contains(s"inspection.json names $older/attempt-001, which is not among the campaigns read"), warnings)
    }

  test("the settings table says when each run started: from its name, or its first attempt for an older one"):
    withResults(Map(
      "e1-rung0-qwen3.5-4b-20261010T093000Z" -> List(evaluated(1, List("R", "A", "R"))),
      "e1-rung0-qwen3.5-9b" -> List(evaluated(1, List("R", "A", "R")).copy(startedAt = "2026-10-08T21:05:59Z"))
    )) { dir =>
      val s = Report.settings(Report.campaigns(dir, "e1-rung0-"))
      assert(s.contains("Run started \\\\"), s)
      assert(s.linesIterator.exists(l => l.startsWith("qwen3.5-4b &") && l.endsWith("& 2026-10-10 09:30Z \\\\")), s)
      assert(s.linesIterator.exists(l => l.startsWith("qwen3.5-9b &") && l.endsWith("& 2026-10-08 21:05Z \\\\")), s)
    }
