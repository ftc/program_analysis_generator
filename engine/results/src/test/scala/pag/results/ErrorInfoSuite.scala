package pag.results

/** ErrorInfo: a Throwable as data, printed as the Throwable would be. */
class ErrorInfoSuite extends munit.FunSuite:

  test("toString matches Throwable.toString, with and without a message"):
    val boom = IllegalStateException("boom")
    assertEquals(ErrorInfo.of(boom).toString, boom.toString)
    val bare = StackOverflowError()
    assertEquals(ErrorInfo.of(bare).toString, bare.toString)

  test("the stack trace is kept, causes included"):
    val e = RuntimeException("outer", IllegalArgumentException("inner"))
    val info = ErrorInfo.of(e)
    assert(info.stackTrace.contains("at pag.results.ErrorInfoSuite"), info.stackTrace)
    assert(info.stackTrace.contains("Caused by: java.lang.IllegalArgumentException: inner"), info.stackTrace)

  test("two captures of the same exception are equal"):
    val boom = RuntimeException("boom")
    assertEquals(ErrorInfo.of(boom), ErrorInfo.of(boom))
