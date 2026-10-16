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

  /** Table 1's rows under the run headed `descriptor` (LaTeX-escaped), up to the next run or the table's end. */
  def rowsUnder(t: String, descriptor: String): List[String] =
    t.linesIterator.dropWhile(!_.contains(s"\\textbf{$descriptor}")).drop(1)
      .takeWhile(l => !l.startsWith("\\multicolumn") && l != "\\bottomrule").toList

  test("Table 1: one row per attempt under its run, with its stop, cells, proved, and the inspection if any"):
    withResults(campaigns) { dir =>
      val t = Report.table1(Report.campaigns(dir, "e1-rung0-"), inspections)
      val big = rowsUnder(t, "e1-rung0-qwen3.5-27b with prompt A")
      assertEquals(big.size, 3, t)
      assert(big.head.startsWith("1 & evaluated & 4 & yes & 9/10 & yes & R & A & R & 2 &"), t)
      assert(big.head.endsWith("& intervals & looks sound & yes \\\\"), t)
      assert(big(1).startsWith("2 & evaluated & 4 & yes & 9/10 & yes & R & $\\times$ & A & 1 &"), t)
      assert(rowsUnder(t, "e1-rung0-qwen3.5-0.8b with prompt A").head
        .startsWith("1 & no files in the reply & 0 & no &  & no &  &  &  &  &"), t)
      assert(t.contains("signs\\_ish"), "inspection text is escaped")
      assert(!t.contains("phase10-check"), t)
    }

  test("Table 2: per model, both bars, unsound, median proved, and where the rest stopped"):
    withResults(campaigns) { dir =>
      val t = Report.table2(Report.campaigns(dir, "e1-rung0-"), inspections)
      // 27b: attempt 1 mechanically acceptable (proves 2); attempt 2 unsound; attempt 3 no files
      assert(t.contains("e1-rung0-qwen3.5-27b with prompt A & 3 & 1/3 & 1/1 & 1 & 2 & 0 & 0 & 0 & 1 & 0 & 0 \\\\"), t)
      // 4b: both acceptable, proving 1 and 2: median 1.5; one inspected, rejected
      assert(t.contains("e1-rung0-qwen3.5-4b with prompt A & 2 & 2/2 & 0/1 & 0 & 1.5 &"), t)
      assert(t.contains("e1-rung0-qwen3.5-0.8b with prompt A & 2 & 0/2 & 0/0 & 0 &  & 0 & 0 & 0 & 2 & 0 & 0 \\\\"), t)
    }

  test("write: tables and prompt written, deterministic, the inspection file untouched, stray keys warned of"):
    withResults(campaigns) { dir =>
      import Report.given
      val inspectionFile = dir.resolve("inspection.json")
      val withStray = inspections.copy(attempts = inspections.attempts + ("e1-rung0-gone/attempt-001" -> Inspection()))
      Files.write(inspectionFile, Json.encode(withStray).toByteArray)
      val before = Files.readAllBytes(inspectionFile)
      val out = dir.resolve("tables")
      val warnings = Report.write(dir, List("e1-rung0"), None, inspectionFile, corpus, out).fold(e => fail(e), identity)
        .warnings
      assertEquals(warnings, List("skipped 1 run matching no experiment file in config/: phase10-check (1)",
        "inspection.json names e1-rung0-gone/attempt-001, which is not among the campaigns read"))
      val files = List("e1-rung0/table1.tex", "e1-rung0/table2.tex", "e1-rung0/settings.tex", "corpus.tex",
        "prompt-A.txt", "prompts.tex")
      val first = files.map(f => Files.readString(out.resolve(f)))
      Report.write(dir, List("e1-rung0"), None, inspectionFile, corpus, out)
      assertEquals(files.map(f => Files.readString(out.resolve(f))), first)
      assert(java.util.Arrays.equals(Files.readAllBytes(inspectionFile), before), "the inspection file was written")
      assert(first(4).startsWith("=== system ===\n") && first(4).contains("=== user ===\n# Task"), first(4))
    }

  test("the settings table is read from the records, and the corpus table from the manifest"):
    withResults(campaigns) { dir =>
      val s = Report.settings(Report.campaigns(dir, "e1-rung0-"))
      assert(s.contains("e1-rung0-qwen3.5-27b with prompt A & 0.2 & default & default & default & default & default & default & 1800 s"), s)
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
      Report.write(dir, List("e1-rung0"), None, report.resolve("inspection.json"), corpus, report.resolve("tables"))
        .fold(e => fail(e), identity)
      val p = Processes.run(List("latexmk", "-lualatex", "-outdir=build", "-interaction=nonstopmode",
        "-halt-on-error", s"-cd", report.resolve("report.tex").toString), scala.concurrent.duration.DurationInt(3).minutes)
      assertEquals(p.exitCode, Some(0), p.stdout.takeRight(3000))
      assert(Files.size(report.resolve("build/report.pdf")) > 0)
      val log = Files.readString(report.resolve("build/report.log"))
      for f <- List("arms.tex", "e1-rung0/settings.tex", "corpus.tex", "e1-rung0/table1.tex", "e1-rung0/table2.tex",
          "prompts.tex") do
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
      assert(t.contains("1 & evaluated & 4 & yes & did not compile & yes & A & A & A & 0 &"), t)
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
        val warnings = Report.write(dir, List("e1-rung0"), None, dir.resolve("inspection.json"), corpus,
          dir.resolve("tables")).fold(e => fail(e), identity).warnings
        assert(warnings.contains(s"inspection.json names $older/attempt-001, which is not among the campaigns read"), warnings)
    }

  test("the settings table says when each run started: from its name, or its first attempt for an older one"):
    withResults(Map(
      "e1-rung0-qwen3.5-4b-20261010T093000Z" -> List(evaluated(1, List("R", "A", "R"))),
      "e1-rung0-qwen3.5-9b" -> List(evaluated(1, List("R", "A", "R")).copy(startedAt = "2026-10-08T21:05:59Z"))
    )) { dir =>
      val s = Report.settings(Report.campaigns(dir, "e1-rung0-"))
      assert(s.contains("Run started \\\\"), s)
      assert(s.linesIterator.exists(l => l.startsWith("e1-rung0-qwen3.5-4b with prompt A &") &&
        l.endsWith("& 2026-10-10 09:30Z \\\\")), s)
      assert(s.linesIterator.exists(l => l.startsWith("e1-rung0-qwen3.5-9b with prompt A &") &&
        l.endsWith("& 2026-10-08 21:05Z \\\\")), s)
    }

  // A run is a campaign with a prompt: the report reads the latest run of each pair and names its prompt by a letter.

  /** `r` as if run with another prompt. */
  def withPrompt(r: AttemptRecord, version: String): AttemptRecord =
    r.copy(prompt = r.prompt.copy(version = version, sha256 = version.reverse))

  test("one campaign run with two prompts: both runs read, each named by its prompt's letter, none skipped"):
    withResults(Map(
      "e1fb-rung0-qwen3.5-27B-20261012T013725Z" -> List(withPrompt(failed(1), "generator-v2")),
      "e1fb-rung0-qwen3.5-27B-20261012T171404Z" -> List(withPrompt(evaluated(1, List("R", "A", "R")), "generator-v3"))
    )) { dir =>
      val (cs, warnings) = Report.latestRuns(dir, "e1fb-rung0-")
      val letters = Report.promptLetters(cs)
      assertEquals(cs.map(c => (c.run, Report.descriptor(c, letters))), List(
        "e1fb-rung0-qwen3.5-27B-20261012T013725Z" -> "e1fb-rung0-qwen3.5-27B with prompt A",
        "e1fb-rung0-qwen3.5-27B-20261012T171404Z" -> "e1fb-rung0-qwen3.5-27B with prompt B"))
      assertEquals(warnings, Nil)
      val t = Report.table1(cs, Inspections())
      assertEquals(rowsUnder(t, "e1fb-rung0-qwen3.5-27B with prompt B").map(_.take(13)), List("1 & evaluated"))
    }

  test("the latest run of each campaign and prompt: an older run with the same prompt is skipped and named"):
    withResults(Map(
      "e1-rung0-qwen3.5-9b-20261009T100000Z" -> List(withPrompt(failed(1), "generator-v2")),
      "e1-rung0-qwen3.5-9b-20261010T100000Z" -> List(withPrompt(failed(1), "generator-v2")),
      "e1-rung0-qwen3.5-9b-20261008T100000Z" -> List(withPrompt(failed(1), "generator-v3"))
    )) { dir =>
      val (cs, warnings) = Report.latestRuns(dir, "e1-rung0-")
      assertEquals(cs.map(c => (c.run, c.prompt.version)), List(
        "e1-rung0-qwen3.5-9b-20261010T100000Z" -> "generator-v2", "e1-rung0-qwen3.5-9b-20261008T100000Z" -> "generator-v3"))
      assertEquals(warnings, List("e1-rung0-qwen3.5-9b: read run e1-rung0-qwen3.5-9b-20261010T100000Z, " +
        "skipped e1-rung0-qwen3.5-9b-20261009T100000Z"))
    }

  test("a run's prompt is its pin's when it has one, and a run saying nothing of its prompt is warned of, not read"):
    withResults(Map(
      "e1-rung0-qwen3.5-4b-20261010T000000Z" -> List(failed(1)),
      "e1-rung0-qwen3.5-9b-20261010T000000Z" -> Nil
    )) { dir =>
      pin(dir, "e1-rung0-qwen3.5-4b-20261010T000000Z", 1)
      Files.createDirectories(dir.resolve("e1-rung0-qwen3.5-9b-20261010T000000Z"))
      val (cs, warnings) = Report.latestRuns(dir, "e1-rung0-")
      assertEquals(cs.map(c => (c.run, c.prompt)), List("e1-rung0-qwen3.5-4b-20261010T000000Z" -> PromptKey("v", "s")))
      assertEquals(warnings, List("e1-rung0-qwen3.5-9b-20261010T000000Z: neither its campaign.json nor an attempt " +
        "says which prompt it ran, so it is not read"))
    }

  test("the appendix: each prompt under its letter with its version and hash, its text as its records sent it"):
    withResults(Map(
      "e1-rung0-qwen3.5-4b" -> List(withPrompt(failed(1), "generator-v2")),
      "e1-rung0-qwen3.5-9b" -> List(withPrompt(failed(1), "generator-v3"))
    )) { dir =>
      val out = dir.resolve("tables")
      Report.write(dir, List("e1-rung0"), None, dir.resolve("absent.json"), corpus, out).fold(e => fail(e), identity)
      val tex = Files.readString(out.resolve("prompts.tex"))
      assert(tex.contains("\\subsection*{Prompt A: \\texttt{generator-v2}}\nSHA-256 \\texttt{2v-rotareneg}."), tex)
      assert(tex.contains("{tables/prompt-B.txt}"), tex)
      assertEquals(Files.readString(out.resolve("prompt-B.txt")),
        base.prompt.messages.map(m => s"=== ${m.role} ===\n${m.content}").mkString("\n\n") + "\n")
    }

  test("a run pinned but with no attempts yet: its prompt is listed, without text"):
    assertEquals(Report.promptsTex(List((PromptKey("v", "s"), "A", false))).linesIterator.toList.last,
      "No attempt of these runs recorded its text.")

  // Arms: one section per experiment file, config/<arm>.json, each with its own tables.

  test("the arms are the experiment files' base names, sorted; the provenance records and other files are not"):
    val config = Files.createTempDirectory("config")
    try
      for f <- List("e1fb-rung0.json", "e1-rung0.json", "notes.txt") do Files.writeString(config.resolve(f), "{}")
      Files.writeString(Files.createDirectories(config.resolve("models")).resolve("m.json"), "{}")
      assertEquals(Report.arms(config), List("e1-rung0", "e1fb-rung0"))
    finally Using.resource(Files.walk(config))(_.iterator.asScala.toList.reverse.foreach(Files.delete))

  val arms: List[String] = List("e1-rung0", "e1fb82k-rung0", "e1fb82k-rung0-v4")

  test("a campaign belongs to the longest arm whose name and a dash begin its own"):
    assertEquals(Report.armOf("e1fb82k-rung0-v4-qwen3.8-27B", arms), Some("e1fb82k-rung0-v4"))
    assertEquals(Report.armOf("e1fb82k-rung0-qwen3.8-27B", arms), Some("e1fb82k-rung0"))
    assertEquals(Report.armOf("e1-rung0-qwen3.5-9B", arms), Some("e1-rung0"))
    assertEquals(Report.armOf("e1fb-rung0-qwen3.5-9B", arms), None, "e1 is not a prefix of e1fb-")
    assertEquals(Report.armOf("e1fb82k-rung0", arms), None, "an arm's name alone names no model")
    assertEquals(Report.armOf("phase10-check-qwen3.8-27b", arms), None)

  /** `campaign report` into `dir/tables`: what it wrote. */
  def writeArms(dir: Path, chosen: Option[List[String]], ins: Inspections = Inspections()): Report.Written =
    import Report.given
    Files.write(dir.resolve("inspection.json"), Json.encode(ins).toByteArray)
    Report.write(dir, arms, chosen, dir.resolve("inspection.json"), corpus, dir.resolve("tables"))
      .fold(e => fail(e), identity)

  def table(dir: Path, arm: String, file: String): String = Files.readString(dir.resolve("tables").resolve(arm).resolve(file))

  test("an arm with no longer arm sharing its name gets exactly the tables reading its prefix gives"):
    withResults(campaigns) { dir =>
      writeArms(dir, None, inspections)
      val cs = Report.campaigns(dir, "e1-rung0-")
      assertEquals(table(dir, "e1-rung0", "table1.tex"), Report.table1(cs, inspections))
      assertEquals(table(dir, "e1-rung0", "table2.tex"), Report.table2(cs, inspections))
      assertEquals(table(dir, "e1-rung0", "settings.tex"), Report.settings(cs))
    }

  val overlapping: Map[String, List[AttemptRecord]] = Map(
    "e1fb82k-rung0-qwen3.8-27B-20261013T010115Z" -> List(withPrompt(failed(1), "generator-v3")),
    "e1fb82k-rung0-v4-qwen3.8-27B-20261014T000000Z" -> List(withPrompt(evaluated(1, List("R", "A", "R")), "generator-v4")),
    "phase10-check-qwen3.8-27b" -> List(failed(1)),
    "e1fb-rung0-qwen3.5-9B-20261012T000000Z" -> List(failed(1))
  )

  test("each run in its own arm's section only, prompt letters shared, unmatched runs counted in one warning"):
    withResults(overlapping) { dir =>
      val written = writeArms(dir, None)
      assertEquals(written.arms, List("e1fb82k-rung0", "e1fb82k-rung0-v4"), "e1-rung0 has no runs, so no section")
      val shorter = table(dir, "e1fb82k-rung0", "table1.tex")
      val longer = table(dir, "e1fb82k-rung0-v4", "table1.tex")
      assert(shorter.contains("e1fb82k-rung0-qwen3.8-27B with prompt A") && !shorter.contains("v4"), shorter)
      assert(longer.contains("e1fb82k-rung0-v4-qwen3.8-27B with prompt B"), longer)
      assert(!Files.exists(dir.resolve("tables/e1-rung0")))
      assertEquals(written.warnings, List("skipped 2 runs matching no experiment file in config/: " +
        "e1fb-rung0-qwen3.5-9B (1), phase10-check-qwen3.8-27b (1)"))
      assertEquals(Files.readString(dir.resolve("tables/arms.tex")),
        "% Generated by `campaign report`; do not edit, regenerate instead.\n" +
          "\\subsection{Arm \\texttt{e1fb82k-rung0}}\n\\armtables{e1fb82k-rung0}\n\n" +
          "\\subsection{Arm \\texttt{e1fb82k-rung0-v4}}\n\\armtables{e1fb82k-rung0-v4}\n")
    }

  test("--arms: only the arms named, in their order; one with no runs is warned of; another arm stays silent"):
    withResults(overlapping) { dir =>
      val ins = Inspections(Map("e1fb82k-rung0-qwen3.8-27B-20261013T010115Z/attempt-009" -> Inspection()))
      val written = writeArms(dir, Some(List("e1fb82k-rung0-v4", "e1-rung0")), ins)
      assertEquals(written.arms, List("e1fb82k-rung0-v4"))
      assert(!Files.exists(dir.resolve("tables/e1fb82k-rung0")), "an arm left out gets no tables")
      assertEquals(written.warnings.drop(1), List("--arms names e1-rung0, which has no runs to read in " +
        s"${dir.getFileName}/"), "and an inspection of an arm left out is not stray")
      val wrote = Files.readString(dir.resolve("tables/arms.tex"))
      assert(wrote.contains("\\armtables{e1fb82k-rung0-v4}") && !wrote.contains("\\armtables{e1fb82k-rung0}"), wrote)
    }

  test("--arms naming an arm with no experiment file is an error, naming the arms there are"):
    withResults(overlapping) { dir =>
      val e = Report.write(dir, arms, Some(List("e1fb82k-rung0", "e1fb-rung0")), dir.resolve("absent.json"), corpus,
        dir.resolve("tables"))
      assertEquals(e, Left("--arms names arms with no experiment file in config/: e1fb-rung0; " +
        "the arms are e1-rung0, e1fb82k-rung0, e1fb82k-rung0-v4"))
      assert(!Files.exists(dir.resolve("tables")), "nothing is written")
    }

  // Build feedback (implementation_strategy.md §16, item 25): rounds, summed tokens, and the pinned settings.

  /** Writes a pin with build feedback of `rounds` rounds and every field recorded into a run directory. */
  def pinFeedback(dir: Path, run: String, rounds: Int): Unit =
    import Campaign.given
    Files.write(dir.resolve(run).resolve("campaign.json"), Json.encode(CampaignPin(base.agent, "v", "s", "c", "p",
      Some(4), Some(BuildPin(120, 3)), Some(FeedbackPin(rounds, FeedbackHistory.Latest, "feedback-build-v1", "h")),
      Some("v2"), Some("b1-abc"))).toByteArray)

  /** `r` with every reply reporting `n` completion tokens. */
  def withTokens(r: AttemptRecord, n: Long): AttemptRecord =
    RecordFixtures.withExchange(r)((reply, files, build) =>
      Exchange.Replied(reply.copy(completionTokens = Some(n)), files, build))

  /** `r` after `n` rounds whose domain failed to compile, each reply reporting 100 completion tokens. */
  def afterFailed(r: AttemptRecord, n: Int): AttemptRecord =
    val reply = withTokens(base, 100).reply.get
    val round = FailedRound(Nil, reply, FilesRecord(List("a"), 0, Nil), BuildRecord.Failed(CompileTry("", false), Nil),
      "feedback")
    r.copy(conversation = r.conversation.copy(failed = List.fill(n)(round)))

  def didNotCompile(r: AttemptRecord): AttemptRecord =
    RecordFixtures.withExchange(r)((reply, files, _) =>
      Exchange.Replied(reply, files.copy(written = List("a")), Some(BuildRecord.Failed(CompileTry("", false), Nil))))

  val feedbackRun: String = "e1fb-rung0-m-27b-20261013T000000Z"

  /** Built in round 1; built in round 3; out of rounds after 4; no files in round 2. */
  val feedbackAttempts: List[AttemptRecord] = List(
    withTokens(evaluated(1, List("R", "A", "R")), 100),
    afterFailed(withTokens(evaluated(2, List("R", "A", "R")), 100), 2),
    afterFailed(withTokens(didNotCompile(failed(3)), 100), 3),
    afterFailed(withTokens(failed(4), 100), 1))

  test("a feedback run: Table 1 shows rounds and tokens summed over every round's reply"):
    withResults(Map(feedbackRun -> feedbackAttempts)) { dir =>
      pinFeedback(dir, feedbackRun, 3)
      val t = Report.table1(Report.campaigns(dir, "e1fb-rung0-"), Inspections())
      assert(t.contains("Sample & Stopped at & Rounds & Files &"), t)
      val rows = rowsUnder(t, "e1fb-rung0-m-27b with prompt A")
      assertEquals(rows.map(_.split(" & ").take(3).mkString(" & ")),
        List("1 & evaluated & 1", "2 & evaluated & 3", "3 & did not compile & 4", "4 & no files in the reply & 2"))
      assertEquals(rows.map(_.split(" & ").dropRight(4).last), List("100", "300", "400", "200"), "Tokens, before Time")
    }

  test("a feedback run: Table 2 counts the attempts built in each round, up to the pinned rounds and one"):
    withResults(Map(feedbackRun -> feedbackAttempts)) { dir =>
      pinFeedback(dir, feedbackRun, 3)
      val t = Report.table2(Report.campaigns(dir, "e1fb-rung0-"), Inspections())
      assert(t.contains("Median proved & Built in 1 & Built in 2 & Built in 3 & Built in 4 & no reply &"), t)
      assert(t.contains("e1fb-rung0-m-27b with prompt A & 4 & 2/4 & 0/0 & 0 & 2.0 & 1 & 0 & 1 & 0 & " +
        "0 & 0 & 0 & 1 & 1 & 0 \\\\"), t)
    }

  test("one-shot runs, pinned or not, show no rounds: their tables are those of unpinned runs"):
    val unpinned = withResults(campaigns)(dir => Report.campaigns(dir, "e1-rung0-")).map(_.copy(pin = None))
    withResults(campaigns) { dir =>
      for run <- campaigns.keys.filter(_.startsWith("e1-rung0-")) do pin(dir, run, 3)
      val pinned = Report.campaigns(dir, "e1-rung0-")
      assert(pinned.forall(_.pin.isDefined))
      assertEquals(Report.table1(pinned, inspections), Report.table1(unpinned, inspections))
      assertEquals(Report.table2(pinned, inspections), Report.table2(unpinned, inspections))
    }

  test("the settings table: build, feedback, reply rules and server build from the pin, or what an older pin meant"):
    withResults(Map(feedbackRun -> List(failed(1)), "e1-rung0-m-4b-20261010T000000Z" -> List(failed(1)),
        "e1-rung0-m-9b-20261010T000000Z" -> List(failed(1)))) { dir =>
      pinFeedback(dir, feedbackRun, 3)
      pin(dir, "e1-rung0-m-4b-20261010T000000Z", 1)
      val fb = Report.settings(Report.campaigns(dir, "e1fb-rung0-"))
      assert(fb.contains("Timeout & Build & Feedback & Reply rules & llama.cpp & Revision"), fb)
      assert(fb.contains("& 1800 s & 2 min $\\times$ 3 & 3 rounds, latest, feedback-build-v1 & v2 & b1-abc &"), fb)
      val older = Report.settings(Report.campaigns(dir, "e1-rung0-"))
      assert(older.linesIterator.exists(l => l.startsWith("e1-rung0-m-4b with prompt") &&
        l.contains("& 1800 s & 5 min $\\times$ 1 & none & v1 & not recorded &")), older)
      assert(older.linesIterator.exists(l => l.startsWith("e1-rung0-m-9b with prompt") && l.contains("& 1800 s &  &  &  &  &")),
        older)
    }
