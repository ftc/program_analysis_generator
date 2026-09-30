package pag.ir

import java.nio.file.Path

/** Bytecode the IR cannot represent at all (implementation_strategy.md §5.5).
  * Distinct from a profile violation: one is a limit of the IR, the other a
  * limit of what we choose to accept.
  */
final class Untranslatable(message: String) extends Exception(message)

/** The only way a program enters the system (§5.5). Implementations are found
  * with java.util.ServiceLoader, so no module above the front end names one.
  */
trait IrProvider:

  /** The program in `classes`, a directory holding exactly one class with a
    * `public static void main(String[])`, as IR with a source line for each
    * command. Translates everything the IR can represent; the profile check
    * (§5.2) decides what is accepted.
    *
    * @throws Untranslatable for bytecode the IR cannot represent
    * @throws IllegalArgumentException if `classes` is not one class with a main
    */
  def load(classes: Path): Program
