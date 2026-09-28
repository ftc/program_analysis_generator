package pag.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import pag.probe.fixtures.Fixtures;

/**
 * Rand and Reach in a real JVM, the way Stage 2 runs a probe (§5.6, §5.8, §9).
 *
 * <p>Output goes to files, never a pipe read after the fact: Process.destroyForcibly
 * closes the parent's end of the child's stdout, so anything unread is lost.
 */
public class ProbeRunTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private record Run(int exit, String out, String err) {}

    /** Starts main in a fresh JVM, stdout to a file; stderr to a file, or a pipe if err is null. */
    private Process start(Class<?> main, String inputs, File out, File err) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(System.getProperty("java.home") + "/bin/java");
        if (inputs != null) cmd.add("-D" + Inputs.PROPERTY + "=" + inputs);
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(main.getName());
        ProcessBuilder b = new ProcessBuilder(cmd).redirectOutput(out);
        if (err != null) b.redirectError(err);
        return b.start();
    }

    private Run run(Class<?> main, String inputs) throws Exception {
        File out = tmp.newFile();
        File err = tmp.newFile();
        Process p = start(main, inputs, out, err);
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail(main.getSimpleName() + " did not finish");
        }
        return new Run(p.exitValue(), Files.readString(out.toPath()), Files.readString(err.toPath()));
    }

    private static String lines(String... ls) {
        StringBuilder b = new StringBuilder();
        for (String l : ls) b.append(l).append(System.lineSeparator());
        return b.toString();
    }

    @Test
    public void reachPrintsItsMarkerAndNothingElse() throws Exception {
        Run r = run(Fixtures.ReachOnce.class, null);
        assertEquals(0, r.exit);
        assertEquals(lines("REACHED-7"), r.out);
    }

    @Test
    public void randIntReturnsTheInputsInOrder() throws Exception {
        Run r = run(Fixtures.EchoFourInputs.class, "10, -3, 99999999999999999999");
        assertEquals(lines("10", "-3", "99999999999999999999"), r.out);
        assertTrue(r.err, r.err.contains("inputs exhausted after 3 value(s)"));
    }

    @Test
    public void withEnoughInputsBothMarkersPrint() throws Exception {
        Run r = run(Fixtures.ReachRandReach.class, "5");
        assertEquals(0, r.exit);
        assertEquals(lines("REACHED-1", "REACHED-2"), r.out);
    }

    /** The "inputs exhausted" case: the marker before the throw must survive it. */
    @Test
    public void aMarkerSurvivesAnExceptionRightAfterIt() throws Exception {
        Run r = run(Fixtures.ReachRandReach.class, null);
        assertEquals(1, r.exit);
        assertEquals(lines("REACHED-1"), r.out);
        assertTrue(r.err, r.err.contains("inputs exhausted after 0 value(s)"));
    }

    /**
     * The harness-timeout case: a marker printed before SIGKILL must survive it.
     * The fixture writes READY to stderr only after reach has returned, so the kill
     * lands after the marker without the test having looked at stdout.
     */
    @Test(timeout = 30_000)
    public void aMarkerSurvivesSigkill() throws Exception {
        File out = tmp.newFile();
        Process p = start(Fixtures.ReachThenBlock.class, null, out, null);
        try (BufferedReader err = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
            assertEquals("READY", err.readLine()); // blocks until the fixture is ready
            p.destroyForcibly(); // SIGKILL: ProcessHandleImpl.destroy0, forcibly = true
        }
        p.waitFor();
        assertEquals("killed by SIGKILL (128 + 9)", 137, p.exitValue());
        assertEquals(lines("REACHED-7"), Files.readString(out.toPath()));
    }
}
