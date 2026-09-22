package pag

/** A trivial piece of domain logic, kept separate from `Main` so it is easy to
  * unit test.
  */
object Greeter:

  /** Builds a greeting for `name`, falling back to "world" when it is blank. */
  def greet(name: String): String =
    val target = if name.trim.isEmpty then "world" else name.trim
    s"Hello, $target!"
