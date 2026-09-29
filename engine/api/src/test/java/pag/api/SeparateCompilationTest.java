package pag.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The api compiles separately (glossary.md): a domain needs nothing but the JDK
 * and pag.api, as it will in the Phase 9 containers, where the domain build is
 * `javac -cp api.jar`. After Ali and Lhoták's separate compilation assumption,
 * checked here rather than assumed.
 */
public class SeparateCompilationTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** Where pag.api's compiled classes are: a directory under sbt, the jar when packaged. */
    private static Path apiClasses() throws Exception {
        return Path.of(Domain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    /** Compiles one test resource with pag.api as the entire classpath; returns the diagnostics. */
    private String compileAgainstApiAlone(String resource, boolean[] ok) throws Exception {
        Path src = tmp.newFolder().toPath().resolve(Path.of(resource).getFileName());
        try (InputStream in = getClass().getResourceAsStream("/separate/" + resource)) {
            Files.copy(in, src);
        }
        JavaCompiler javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, null, null)) {
            // An explicit -classpath: without it javac falls back to this JVM's own
            // classpath, which has JUnit and everything else the tests use.
            List<String> options = List.of(
                    "--release", "21",
                    "-classpath", apiClasses().toString(),
                    "-d", tmp.newFolder().toString(),
                    "-proc:none");
            ok[0] = javac.getTask(null, files, diagnostics, options, null,
                    files.getJavaFileObjects(src)).call();
        }
        return diagnostics.getDiagnostics().toString();
    }

    @Test
    public void aDomainCompilesAgainstTheApiAlone() throws Exception {
        boolean[] ok = new boolean[1];
        String diagnostics = compileAgainstApiAlone("StubDomain.java", ok);
        assertTrue(diagnostics, ok[0]);
    }

    /** The control: if this compiled, the classpath would not be pag.api alone. */
    @Test
    public void codeUsingAnythingElseDoesNot() throws Exception {
        boolean[] ok = new boolean[1];
        String diagnostics = compileAgainstApiAlone("LeakyDomain.java", ok);
        assertFalse("compiled against more than pag.api", ok[0]);
        assertTrue(diagnostics, diagnostics.contains("org.junit"));
    }

    /** Every class pag.api refers to is in java.* or pag.api.*. */
    @Test
    public void theApiDependsOnNothingButTheJdk() throws Exception {
        java.util.spi.ToolProvider jdeps = java.util.spi.ToolProvider.findFirst("jdeps").orElseThrow();
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = jdeps.run(new PrintWriter(out), new PrintWriter(err),
                "-verbose:class", apiClasses().toString());
        assertEquals(err.toString(), 0, exit);

        // Lines look like:   pag.api.Step -> java.lang.Object   java.base
        int references = 0;
        Set<String> outside = new TreeSet<>();
        for (String line : out.toString().split("\\R")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 3 && parts[1].equals("->") && parts[0].startsWith("pag.api.")) {
                references++;
                String target = parts[2];
                if (!target.startsWith("java.") && !target.startsWith("pag.api.")) outside.add(target);
            }
        }
        assertTrue("jdeps reported no references at all:\n" + out, references > 0);
        assertEquals(Set.of(), outside);
    }
}
