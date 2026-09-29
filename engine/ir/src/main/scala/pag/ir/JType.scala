package pag.ir

/** A Java type (implementation_strategy.md §5.1). Structured, so there is exactly
  * one form of each type: `java.lang.String[]` cannot also appear as
  * `[Ljava/lang/String;`. Only the front end builds these, from its own type
  * objects; `toString` is the one printed form, and the only source of the type
  * strings in the Java domain vocabulary (§5.4).
  */
enum JType:
  /** Return types only. */
  case Void
  case Prim(kind: PrimKind)
  case Ref(className: String)
  case ArrayOf(elem: JType)

  override def toString: String = this match
    case Void          => "void"
    case Prim(kind)    => kind.toString.toLowerCase
    case Ref(name)     => name
    case ArrayOf(elem) => s"$elem[]"

enum PrimKind:
  case Boolean, Byte, Char, Short, Int, Long, Float, Double

object JType:
  /** Fails unless `t` can be the type of a value: anything but Void. */
  def requireValueType(t: JType, what: => String): Unit =
    require(t != JType.Void, s"$what cannot have type void")
