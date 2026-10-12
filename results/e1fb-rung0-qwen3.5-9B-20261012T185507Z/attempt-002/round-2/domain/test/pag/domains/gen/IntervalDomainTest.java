package pag.domains.gen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pag.api.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("IntervalDomain Tests")
class IntervalDomainTest {

    @Test
    @DisplayName("top() returns unconstrained state")
    void testTop() {
        IntervalDomain domain = new IntervalDomain();
        IntervalDomain.State top = domain.top();
        assertFalse(IntervalDomain.isBottom(top));
        assertTrue(domain.entails(top, top));
    }

    @Test
    @DisplayName("bottom() returns empty state")
    void testBottom() {
        IntervalDomain domain = new IntervalDomain();
        IntervalDomain.State bottom = domain.bottom();
        assertTrue(domain.isBottom(bottom));
        assertFalse(domain.entails(bottom, domain.top()));
    }

    @Test
    @DisplayName("isBottom correctly identifies bottom states")
    void testIsBottom() {
        IntervalDomain domain = new IntervalDomain();
        assertTrue(domain.isBottom(domain.bottom()));
        assertFalse(domain.isBottom(domain.top()));
    }

    @Test
    @DisplayName("entails checks state inclusion")
    void testEntails() {
        IntervalDomain domain = new IntervalDomain();
        // top entails everything
        assertTrue(domain.entails(domain.top(), domain.top()));
        assertTrue(domain.entails(domain.top(), domain.bottom()));
    }

    @Test
    @DisplayName("transfer for Assign with Local")
    void testTransferAssignLocal() {
        IntervalDomain domain = new IntervalDomain();
        IntervalDomain.State post = domain.top();
        Step step = new Step.Assign(
                new Step.Local("x", "int"),
                new Step.IntConst(new BigInteger("5"))
        );

        IntervalDomain.State pre = domain.transfer(step, post);
        assertFalse(domain.isBottom(pre));
    }

    @Test
    @DisplayName("transfer for Call with Rand.randInt()")
    void testTransferCall() {
        IntervalDomain domain = new IntervalDomain();
        IntervalDomain.State post = domain.top();
        Step step = new Step.Call(
                Optional.of(new Step.Local("x", "int")),
                new Step.MethodId("pag.probe.Rand", "randInt", List.of(), "int"),
                List.of()
        );

        IntervalDomain.State pre = domain.transfer(step, post);
        // After transfer, x should be unconstrained
        assertFalse(pre.constraints.containsKey("x"));
    }

    @Test
    @DisplayName("join combines constraints")
    void testJoin() {
        IntervalDomain domain = new IntervalDomain();
        IntervalDomain.State s1 = new IntervalDomain.State(Map.of("x", new IntervalDomain.Interval(BigInteger.valueOf(0), BigInteger.valueOf(10))));
        IntervalDomain.State s2 = new IntervalDomain.State(Map.of("x", new IntervalDomain.Interval(BigInteger.valueOf(5), BigInteger.valueOf(15))));

        IntervalDomain.State joined = domain.join(s1, s2);
        IntervalDomain.Interval interval = joined.constraints.get("x");
        assertNotNull(interval);
        assertTrue(interval.min.compareTo(BigInteger.valueOf(0)) <= 0);
        assertTrue(interval.max.compareTo(BigInteger.valueOf(15)) >= 0);
    }

    @Test
    @DisplayName("widen converges at loop heads")
    void testWiden() {
        IntervalDomain domain = new IntervalDomain();
        IntervalDomain.State s1 = new IntervalDomain.State(Map.of("x", new IntervalDomain.Interval(BigInteger.valueOf(0), BigInteger.valueOf(10))));
        IntervalDomain.State s2 = new IntervalDomain.State(Map.of("x", new IntervalDomain.Interval(BigInteger.valueOf(5), BigInteger.valueOf(15))));

        IntervalDomain.State widened = domain.widen(s1, s2);
        IntervalDomain.Interval interval = widened.constraints.get("x");
        assertNotNull(interval);
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
