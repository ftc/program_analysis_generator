package pag.probe.fixtures;

import pag.probe.Rand;
import pag.probe.Reach;

/** Tiny programs run in their own JVM by ProbeRunTest. Not probes: they may use System.err. */
public final class Fixtures {

    private Fixtures() {}

    /** reach(7), then exit normally. */
    public static final class ReachOnce {
        public static void main(String[] args) {
            Reach.reach(7);
        }
    }

    /** reach(1), one randInt, reach(2): the second marker needs one input. */
    public static final class ReachRandReach {
        public static void main(String[] args) {
            Reach.reach(1);
            Rand.randInt();
            Reach.reach(2);
        }
    }

    /** Prints four randInts. Given three inputs, the fourth call runs them out. */
    public static final class EchoFourInputs {
        public static void main(String[] args) {
            for (int i = 0; i < 4; i++) {
                System.out.println(Rand.randInt());
            }
        }
    }

    /** reach(7), signal readiness on stderr once reach has returned, then block until killed. */
    public static final class ReachThenBlock {
        public static void main(String[] args) throws InterruptedException {
            Reach.reach(7);
            System.err.println("READY");
            Thread.sleep(Long.MAX_VALUE);
        }
    }
}
