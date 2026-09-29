package pag.ir

/** A method's commands and the source line of each (implementation_strategy.md
  * §5.1): `lines(i)` is the source line of `body(i)`, or `Method.UnknownLine`.
  */
final case class Method(id: MethodId, body: Vector[Cmd], lines: Vector[Int]):
  require(body.size == lines.size, s"$id: ${body.size} commands but ${lines.size} lines")
  for (line, i) <- lines.zipWithIndex do
    require(line >= 1 || line == Method.UnknownLine, s"$id: command $i has line $line")
  for case (Cmd.Goto(_, target), i) <- body.zipWithIndex do
    require(target < body.size, s"$id: command $i jumps to $target, past the last command ${body.size - 1}")

  /** The source line of command `index`, if known. */
  def lineOf(index: Int): Option[Int] = Some(lines(index)).filter(_ != Method.UnknownLine)

  /** The pre (`isPre`) or post locations of every command on `line`, in body
    * order. A line can hold several commands with no order between them, so
    * callers treat the list as a disjunction (§5.1, §6).
    */
  def locationsOn(line: Int, isPre: Boolean): List[Loc.AppLoc] =
    lines.indices.filter(lines(_) == line).map(Loc.AppLoc(id, _, isPre)).toList

object Method:
  val UnknownLine: Int = -1

/** A loaded program (§5.1). No two methods share a MethodId. */
final case class Program(sourceFile: String, methods: List[Method], entryMethod: MethodId):
  private val ids = methods.map(_.id)
  require(ids.distinct.size == ids.size, s"two methods ${ids.diff(ids.distinct).mkString(", ")}")
  require(ids.contains(entryMethod), s"no entry method $entryMethod")
