package pag.cli

import pag.core.{Lowered, Profile}
import pag.ir.*

/** Plain-text rendering of the IR for `pag ir` (implementation_strategy.md §11). */
object Show:

  /** The entry method, one command per line: index, source line, command. */
  def program(p: Program, profile: Profile, checked: Boolean, lifted: Boolean): List[String] =
    val m = p.methods.find(_.id == p.entryMethod).get
    val status = List(
      p.sourceFile,
      if checked then s"profile ${profile.name}" else "profile not checked",
      if lifted then "lifted" else "not lifted"
    ).mkString(" · ")
    s"${m.id}   $status" :: m.body.indices.toList.map { i =>
      val line = m.lineOf(i).fold("")(n => s"line $n")
      f"  $i%3d  $line%-8s ${cmd(m.body(i))}"
    }

  /** The lowered CFG: its init and exit, then one transition per line. */
  def cfg(l: Lowered): List[String] = Pretty.cfg(l.cfg)

  def loc(l: Loc): String = Pretty.loc(l)
  def cmd(c: Cmd): String = Pretty.cmd(c)
  def step(s: Step): String = Pretty.step(s)
  def value(v: RVal): String = Pretty.value(v)
