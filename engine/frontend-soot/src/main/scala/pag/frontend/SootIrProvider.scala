package pag.frontend

import java.nio.file.Path
import scala.jdk.CollectionConverters.*

import pag.ir.*
import soot.jimple.*
import soot.options.Options
import soot.tagkit.SourceFileTag

/** Loads a class through Soot 4.7.1 and translates its Jimple to the IR
  * (implementation_strategy.md §5.5). The only class that touches Soot.
  */
final class SootIrProvider extends IrProvider:

  def load(classes: Path): Program = SootIrProvider.synchronized {
    configure(classes)
    soot.Scene.v.loadNecessaryClasses()
    val found = soot.Scene.v.getApplicationClasses.asScala.toList
    require(found.size == 1, s"$classes must hold exactly one class; found ${found.map(_.getName).mkString(", ")}")
    val cls = found.head
    val methods = cls.getMethods.asScala.toList.filter(_.isConcrete).map(translate)
    val main = cls.getMethods.asScala
      .find(m => m.isStatic && m.getSubSignature == "void main(java.lang.String[])")
      .getOrElse(throw IllegalArgumentException(s"${cls.getName} has no static void main(String[])"))
    val sourceFile = Option(cls.getTag(SourceFileTag.NAME)).collect { case t: SourceFileTag => t.getSourceFile }
    Program(sourceFile.getOrElse(s"${cls.getName}.class"), methods, idOf(main.makeRef))
  }

  /** Soot's Scene is process-wide, so every load starts from G.reset. */
  private def configure(classes: Path): Unit =
    soot.G.reset()
    val o = Options.v
    o.set_src_prec(Options.src_prec_class)
    o.set_process_dir(java.util.List.of(classes.toString))
    o.set_prepend_classpath(true)   // the JDK, so java.math.BigInteger resolves
    o.set_allow_phantom_refs(true)  // probe-lib need not be present: calls keep their signatures
    o.set_whole_program(false)
    o.set_keep_line_number(true)
    o.set_output_format(Options.output_format_none)
    // setPhaseOption returns false, logging only at debug level, when it does not
    // take effect; these settings are load-bearing (§5.5), so that is an error.
    def phase(name: String, option: String): Unit =
      require(soot.PhaseOptions.v.setPhaseOption(name, option), s"Soot ignored $name $option")
    phase("jb", "use-original-names:true")
    phase("jb.dae", "enabled:false") // would delete assignments a query may ask about
    phase("jb.uce", "enabled:false") // would delete statements with no path to them

  private def translate(m: soot.SootMethod): Method =
    val units = m.retrieveActiveBody().getUnits.asScala.toVector
    val at = Where(idOf(m.makeRef), units)
    Method(at.method, units.map(u => cmd(u)(using at)), units.map(line))

  private def line(u: soot.Unit): Int =
    val n = u.getJavaSourceStartLineNumber // -1 when the unit has no line tag
    if n >= 1 then n else Method.UnknownLine

  private def cmd(u: soot.Unit)(using at: Where): Cmd = u match
    case s: IdentityStmt   => Cmd.Assign(local(s.getLeftOp, u), identity(s.getRightOp, u))
    case s: AssignStmt     => Cmd.Assign(local(s.getLeftOp, u), rval(s.getRightOp, u))
    case s: InvokeStmt     => Cmd.InvokeStmt(invoke(s.getInvokeExpr, u))
    case _: ReturnVoidStmt => Cmd.Return(None)
    case s: ReturnStmt     => Cmd.Return(Some(rval(s.getOp, u)))
    case _                 => at.fail(u, "statement")

  private def identity(v: soot.Value, u: soot.Unit)(using at: Where): LVal = v match
    case p: ParameterRef => LVal.Param(p.getIndex, jtype(p.getType, u))
    case t: ThisRef      => LVal.This(t.getType.toString)
    case _               => at.fail(u, s"identity value $v")

  private def local(v: soot.Value, u: soot.Unit)(using at: Where): LVal.Local = v match
    case l: soot.Local => LVal.Local(l.getName, jtype(l.getType, u))
    case _             => at.fail(u, s"assignment target $v")

  private def rval(v: soot.Value, u: soot.Unit)(using at: Where): RVal = v match
    case l: soot.Local      => local(l, u)
    case c: IntConstant     => RVal.IntConst(c.value)
    case c: LongConstant    => RVal.IntConst(c.value)
    case f: StaticFieldRef  => LVal.StaticField(f.getFieldRef.declaringClass.getName, f.getFieldRef.name)
    case e: InvokeExpr      => invoke(e, u)
    case _                  => at.fail(u, s"value $v")

  private def invoke(e: InvokeExpr, u: soot.Unit)(using at: Where): RVal.Invoke =
    val kind = e match
      case _: StaticInvokeExpr    => InvokeKind.Static
      case _: VirtualInvokeExpr   => InvokeKind.Virtual
      case _: SpecialInvokeExpr   => InvokeKind.Special
      case _: InterfaceInvokeExpr => InvokeKind.Interface
      case _                      => at.fail(u, s"invoke $e") // invokedynamic
    val receiver = e match
      case i: InstanceInvokeExpr => Some(rval(i.getBase, u))
      case _                     => None
    RVal.Invoke(kind, idOf(e.getMethodRef), receiver, e.getArgs.asScala.toList.map(rval(_, u)))

  private def idOf(r: soot.SootMethodRef): MethodId =
    MethodId(r.getDeclaringClass.getName, r.getName,
      r.getParameterTypes.asScala.toList.map(jtype(_, r)), jtype(r.getReturnType, r))

  private def jtype(t: soot.Type, where: Any): JType = t match
    case _: soot.VoidType    => JType.Void
    case _: soot.BooleanType => JType.Prim(PrimKind.Boolean)
    case _: soot.ByteType    => JType.Prim(PrimKind.Byte)
    case _: soot.CharType    => JType.Prim(PrimKind.Char)
    case _: soot.ShortType   => JType.Prim(PrimKind.Short)
    case _: soot.IntType     => JType.Prim(PrimKind.Int)
    case _: soot.LongType    => JType.Prim(PrimKind.Long)
    case _: soot.FloatType   => JType.Prim(PrimKind.Float)
    case _: soot.DoubleType  => JType.Prim(PrimKind.Double)
    case r: soot.RefType     => JType.Ref(r.getClassName)
    case a: soot.ArrayType   => JType.ArrayOf(jtype(a.getElementType, where))
    case _                   => throw Untranslatable(s"type $t in $where")

  /** The method being translated, for error messages that name the line. */
  private final case class Where(method: MethodId, units: Vector[soot.Unit]):
    def fail(u: soot.Unit, what: String): Nothing =
      val lineText = line(u) match
        case Method.UnknownLine => "unknown line"
        case n                  => s"line $n"
      throw Untranslatable(s"$method, $lineText: cannot represent $what: $u")

object SootIrProvider
