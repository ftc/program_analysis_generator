package pag.cli

import java.nio.file.Path

import pag.core.{AnalysisResult, Lowered}
import pag.ir.{Cmd, Loc, Pretty, Program, Transition}
import pag.results.{Incomplete, Reachable, Verdict}

/** What `pag analyze` prints (implementation_strategy.md §11): the program and
  * query, the invariant map in program order, the search's cost, the
  * certification, and the verdict.
  *
  * Debugging output, so it calls the domain as little as possible: a location
  * absent from the map prints as ⊥, anything else by the state's own
  * `toString`, guarded because that is domain code too. ⊤ is not recognised —
  * that would take an `entails` call per location.
  */
object AnalyzeReport:

  def lines(
      classes: Path,
      program: Program,
      lowered: Lowered,
      domain: String,
      query: Reachable,
      targets: Set[Loc],
      r: AnalysisResult[?],
      all: Boolean
  ): List[String] =
    val main = program.methods.find(_.id == program.entryMethod).get
    val cfg = lowered.cfg
    val locations = cfg.transitions.flatMap(t => List(t.from, t.to)).distinct.size
    def state(l: Loc): String = r.states.get(l).fold("⊥")(safe)

    /** Every location in program order; without --all, only entry and the pre of each command but nop. */
    val shown: List[(Loc, String)] =
      val commands = main.body.indices.toList.flatMap { i =>
        val c = main.body(i)
        val pre = if all || c != Cmd.Nop then List(Loc.AppLoc(main.id, i, true) -> Show.cmd(c)) else Nil
        val post = if all then List(Loc.AppLoc(main.id, i, false) -> "") else Nil
        pre ++ post
      }
      ((cfg.init -> "") :: commands) ++ (if all then List(cfg.exit -> "") else Nil)

    val header = List(
      s"classes   $classes   $locations locations · profile bigint-main-v1",
      s"domain    $domain",
      s"query     Reachable(${query.id}) → ${targets.toList.map(Pretty.loc).sorted.mkString(", ")}",
      ""
    )
    val map = shown.map { (l, cmd) =>
      val mark = if targets(l) then "   ← target" else ""
      f"  ${Pretty.loc(l)}%-9s $cmd%-28s ${state(l)}$mark"
    } ++ (if all then Nil else List("  (post locations and nops elided; --all shows them)"))

    val widening =
      if r.widenedAt.isEmpty then "no widening"
      else s"widened at ${r.widenedAt.toList.map(Pretty.loc).sorted.mkString(", ")}"
    val certified = r.certification match
      case None => List("certified   not run")
      case Some(c) =>
        s"certified   ${c.edges - c.uncertified.size}/${c.edges} edges inductive" ::
          c.uncertified.zipWithIndex.map((t, i) => (if i == 0 then "uncertified " else "            ") + edge(t))
    val footer = List("", s"worklist    ${r.iterations} iterations · $widening · ${r.elapsedMs}ms") ++ certified ++
      List(s"entry       I(entry) = ${state(cfg.init)}", "", verdict(query, r))

    header ++ map ++ footer

  private def verdict(q: Reachable, r: AnalysisResult[?]): String = r.verdict match
    case Verdict.Refuted => "REFUTED"
    case Verdict.Alarm =>
      r.certification.map(_.uncertified.size).filter(_ > 0) match
        case Some(n) => s"ALARM — the map is not inductive at $n edge${if n == 1 then "" else "s"}, so it is not a proof (§7)"
        case None    => s"ALARM — could not prove reach(${q.id}) unreachable"
    case Verdict.Inconclusive(Incomplete.IterationLimit(n)) =>
      s"INCONCLUSIVE — iteration limit $n reached, ${r.unexplored} transitions unexplored"
    case Verdict.Inconclusive(Incomplete.Deadline(ms)) =>
      s"INCONCLUSIVE — deadline reached after ${ms}ms, ${r.unexplored} transitions unexplored"
    case Verdict.Inconclusive(Incomplete.DomainFailure(op, e)) => s"DOMAIN FAILURE — $op threw ${safe(e)}"

  private def edge(t: Transition): String = s"${Pretty.loc(t.from)} —${Pretty.step(t.step)}→ ${Pretty.loc(t.to)}"

  /** `toString` on a domain's object is domain code: it may throw or return null. */
  private def safe(a: Any): String =
    try String.valueOf(a) catch case e: Throwable => s"(toString threw ${e.getClass.getName})"
