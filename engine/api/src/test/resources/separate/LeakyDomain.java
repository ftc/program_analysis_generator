import org.junit.Assert;

/**
 * The control for SeparateCompilationTest: it uses JUnit, which the test JVM has
 * but pag.api does not. It must fail to compile, or the stub's success proves
 * nothing about the classpath.
 */
public final class LeakyDomain {
    public static void check() {
        Assert.assertTrue(true);
    }
}
