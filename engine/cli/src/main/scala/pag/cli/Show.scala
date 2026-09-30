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
  def cfg(l: Lowered): List[String] =
    s"cfg  init ${loc(l.cfg.init)}  exit ${loc(l.cfg.exit)}" ::
      l.cfg.transitions.map(t => f"  ${loc(t.from)}%-9s —${step(t.step)}→  ${loc(t.to)}")

  def loc(l: Loc): String = l match
    case Loc.InternalMethodEntry(_)  => "entry"
    case Loc.InternalMethodExit(_)   => "exit"
    case Loc.AppLoc(_, i, true)      => s"pre($i)"
    case Loc.AppLoc(_, i, false)     => s"post($i)"

  def cmd(c: Cmd): String = c match
    case Cmd.Assign(t, s)                   => s"${value(t)} := ${value(s)}"
    case Cmd.Goto(RVal.BoolConst(true), to) => s"goto $to"
    case Cmd.Goto(cond, to)                 => s"if ${value(cond)} goto $to"
    case Cmd.Nop                            => "nop"
    case Cmd.Return(v)                      => ("return" :: v.map(value).toList).mkString(" ")
    case Cmd.InvokeStmt(call)               => value(call)
    case Cmd.Throw                          => "throw"

  def step(s: Step): String = s match
    case Step.Assign(t, e)           => s"${value(t)} := ${value(e)}"
    case Step.Assume(cond)           => s"assume(${value(cond)})"
    case Step.Call(Some(t), f, args) => s"${value(t)} := call ${f.qualifiedName}(${args.map(value).mkString(", ")})"
    case Step.Call(None, f, args)    => s"call ${f.qualifiedName}(${args.map(value).mkString(", ")})"
    case Step.Skip                   => "skip"

  def value(v: RVal): String = v match
    case RVal.IntConst(n)                    => n.toString
    case RVal.BoolConst(b)                   => b.toString
    case RVal.Binop(l, op, r)                => s"${value(l)} ${symbol(op)} ${value(r)}"
    case RVal.Invoke(_, f, Some(recv), args) => s"${value(recv)}.${f.name}(${args.map(value).mkString(", ")})"
    case RVal.Invoke(_, f, None, args)       => s"${simpleName(f.declaringClass)}.${f.name}(${args.map(value).mkString(", ")})"
    case RVal.Cast(t, x)                     => s"($t) ${value(x)}"
    case RVal.NewObject(c)                   => s"new $c"
    case RVal.StringConst(s)                 => "\"" + s + "\""
    case RVal.InstanceOf(c, l)               => s"${value(l)} instanceof $c"
    case RVal.ArrayLength(l)                 => s"lengthof ${value(l)}"
    case LVal.Local(name, _)                 => name
    case LVal.Param(i, _)                    => s"@parameter$i"
    case LVal.This(_)                        => "@this"
    case LVal.StaticField(c, name)           => s"${simpleName(c)}.$name"
    case LVal.Field(base, _, name)           => s"${value(base)}.$name"
    case LVal.ArrayRef(b, i)                 => s"${value(b)}[${value(i)}]"

  private def symbol(op: BinOp): String = op match
    case BinOp.Mult => "*"
    case BinOp.Add  => "+"
    case BinOp.Sub  => "-"
    case BinOp.Lt   => "<"
    case BinOp.Le   => "<="
    case BinOp.Gt   => ">"
    case BinOp.Ge   => ">="
    case BinOp.Eq   => "=="
    case BinOp.Ne   => "!="

  private def simpleName(className: String): String = className.substring(className.lastIndexOf('.') + 1)
