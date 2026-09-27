package pag.api;

import static org.junit.Assert.assertThrows;

import org.junit.Test;

/**
 * Module wiring for api, which is pure Java (implementation_strategy.md §3).
 *
 * <p>Mostly a placeholder: it proves JUnit runs in a module without the Scala
 * library. The real boundary is the compile classpath, enforced by the build's
 * checkPureJava and checkNoSootOnCompileClasspath tasks before these tests run.
 * These only add that nothing reaches the runtime classpath either. The
 * stronger check comes in Phase 1: a stub compiled with javac against the jar
 * alone.
 */
public class ApiModuleTest {

    @Test
    public void scalaLibraryIsNotOnTheClasspath() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("scala.Predef"));
    }

    @Test
    public void sootIsNotOnTheClasspath() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("soot.G"));
    }
}
