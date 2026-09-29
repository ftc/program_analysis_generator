package pag.ir

/** A method's fully qualified identity (implementation_strategy.md §5.1). Printed
  * as `Probe.main(java.lang.String[])`.
  */
final case class MethodId(
    declaringClass: String,
    name: String,
    paramTypes: List[JType],
    returnType: JType
):
  for (t, i) <- paramTypes.zipWithIndex do JType.requireValueType(t, s"parameter $i of $name")

  /** `java.math.BigInteger.add`: class and name, ignoring overloads (§5.2 callees). */
  def qualifiedName: String = s"$declaringClass.$name"

  override def toString: String = s"$qualifiedName(${paramTypes.mkString(",")})"
