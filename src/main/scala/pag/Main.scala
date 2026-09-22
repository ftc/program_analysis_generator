package pag

/** Entry point for the application. */
object Main:

  def main(args: Array[String]): Unit =
    val name = args.headOption.getOrElse("world")
    println(Greeter.greet(name))
