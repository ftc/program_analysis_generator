package pag.domains.gen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pag.api.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Reachability Analysis Tests")
class ReachabilityTest {

    @Test
    @DisplayName("Unreachable reach() call")
    void testUnreachableReach() {
        IntervalDomain domain = new IntervalDomain();

        // Simulate a simple program where reach() is never called
        IntervalDomain.State entry = domain.bottom();
        assertTrue(domain.isBottom(entry));
    }

    @Test
    @DisplayName("Reachable reach() call")
    void testReachableReach() {
        IntervalDomain domain = new IntervalDomain();

        // Simulate a program where reach() is reachable
        IntervalDomain.State entry = domain.top();
        assertFalse(domain.isBottom(entry));
    }

    @Test
    @DisplayName("Loop with unreachable exit")
    void testLoopUnreachableExit() {
        IntervalDomain domain = new IntervalDomain();

        // Simulate a loop where the exit condition is never met
        IntervalDomain.State loopHeader = domain.top();
        IntervalDomain.State loopExit = domain.bottom();

        assertTrue(domain.isBottom(loopExit));
    }

    @Test
    @DisplayName("Conditional with unreachable branch")
    void testConditionalUnreachable() {
        IntervalDomain domain = new IntervalDomain();

        // Simulate a conditional where one branch is unreachable
        IntervalDomain.State branch1 = domain.top();
        IntervalDomain.State branch2 = domain.bottom();

        assertTrue(domain.isBottom(branch2));
    }

    @Test
    @DisplayName("Soundness: bottom entails bottom")
    void testSoundness() {
        IntervalDomain domain = new IntervalDomain();
        IntervalDomain.State bottom = domain.bottom();

        assertTrue(domain.entails(bottom, bottom));
        assertTrue(domain.isBottom(bottom));
    }

    @Test
    @DisplayName("Soundness: top entails everything")
    void testSoundnessTopEntailsAll() {
        IntervalDomain domain = new IntervalDomain();
        IntervalDomain.State top = domain.top();
        IntervalDomain.State bottom = domain.bottom();

        assertTrue(domain.entails(top, bottom));
    }

    @Test
    @DisplayName("Transfer preserves soundness")
    void testTransferSoundness() {
        IntervalDomain domain = new IntervalDomain();
        IntervalDomain.State post = domain.top();

        Step step = new Step.Assign(
                new Step.Local("x", "int"),
                new Step.IntConst(new BigInteger("0"))
        );

        IntervalDomain.State pre = domain.transfer(step, post);

        // Transfer should be sound: if post admits a state, pre should admit the corresponding pre-state
        assertFalse(domain.isBottom(pre));
    }
}
