package pag.domains.gen;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

import pag.api.*;

public class SimpleIntDomainTest {

    private SimpleIntDomain domain = new SimpleIntDomain();

    @Test
    void testName() {
        assertEquals("SimpleIntDomain", domain.name());
    }

    @Test
    void testTop() {
        Map<String, SimpleIntDomain.Interval> top = domain.top();
        assertNotNull(top);
        assertTrue(top.containsKey("__dummy__"));
    }

    @Test
    void testBottom() {
        Map<String, SimpleIntDomain.Interval> bottom = domain.bottom();
        assertNotNull(bottom);
        assertTrue(bottom.isEmpty());
    }

    @Test
    void testIsBottom() {
        assertTrue(domain.isBottom(domain.bottom()));
        assertFalse(domain.isBottom(domain.top()));
        Map<String, SimpleIntDomain.Interval> nonEmpty = new HashMap<>();
        nonEmpty.put("x", new SimpleIntDomain.Interval(BigInteger.ZERO, BigInteger.ONE));
        assertFalse(domain.isBottom(nonEmpty));
    }

    @Test
    void testEntails() {
        // Empty entails empty
        assertTrue(domain.entails(domain.bottom(), domain.bottom()));
        // Top entails top
        assertTrue(domain.entails(domain.top(), domain.top()));
        // Non-empty does not entail empty
        Map<String, SimpleIntDomain.Interval> a = new HashMap<>();
        a.put("x", new SimpleIntDomain.Interval(BigInteger.ZERO, BigInteger.ONE));
        assertFalse(domain.entails(a, domain.bottom()));
    }

    @Test
    void testJoin() {
        Map<String, SimpleIntDomain.Interval> a = new HashMap<>();
        a.put("x", new SimpleIntDomain.Interval(BigInteger.ZERO, BigInteger.TEN));
        a.put("y", new SimpleIntDomain.Interval(BigInteger.ZERO, BigInteger.FIVE));

        Map<String, SimpleIntDomain.Interval> b = new HashMap<>();
        b.put("x", new SimpleIntDomain.Interval(BigInteger.FIVE, BigInteger.TEN));
        b.put("y", new SimpleIntDomain.Interval(BigInteger.ZERO, BigInteger.TEN));

        Map<String, SimpleIntDomain.Interval> result = domain.join(a, b);

        assertEquals(BigInteger.ZERO, result.get("x").min);
        assertEquals(BigInteger.TEN, result.get("x").max);
        assertEquals(BigInteger.ZERO, result.get("y").min);
        assertEquals(BigInteger.TEN, result.get("y").max);
    }

    @Test
    void testTransferAssign() {
        Map<String, SimpleIntDomain.Interval> post = new HashMap<>();
        post.put("x", new SimpleIntDomain.Interval(BigInteger.ZERO, BigInteger.TEN));

        Assign step = new Assign(new SimpleIntDomain.Interval.Local("x", "int"),
            new SimpleIntDomain.Interval.IntConst(BigInteger.ZERO));

        Map<String, SimpleIntDomain.Interval> pre = domain.transfer(step, post);

        // After assignment, x is unconstrained
        assertEquals(BigInteger.ZERO, pre.get("x").min);
        assertEquals(BigInteger.TEN, pre.get("x").max);
    }

    @Test
    void testTransferCall() {
        Map<String, SimpleIntDomain.Interval> post = new HashMap<>();
        post.put("x", new SimpleIntDomain.Interval(BigInteger.ZERO, BigInteger.TEN));

        Call step = new Call(
            Optional.of(new SimpleIntDomain.Interval.Local("x", "int")),
            new MethodId("pag.probe.Rand", "randInt", List.of(), "int"),
            List.of()
        );

        Map<String, SimpleIntDomain.Interval> pre = domain.transfer(step, post);

        // After call to randInt, x is unconstrained
        assertEquals(BigInteger.ZERO, pre.get("x").min);
        assertEquals(BigInteger.TEN, pre.get("x").max);
    }

    @Test
    void testTransferAssume() {
        Map<String, SimpleIntDomain.Interval> post = new HashMap<>();
        post.put("x", new SimpleIntDomain.Interval(BigInteger.ZERO, BigInteger.TEN));

        Assume step = new Assume(new SimpleIntDomain.Interval.IntConst(BigInteger.ONE));

        Map<String, SimpleIntDomain.Interval> pre = domain.transfer(step, post);

        // After assume, state is restricted
        assertEquals(BigInteger.ZERO, pre.get("x").min);
        assertEquals(BigInteger.TEN, pre.get("x").max);
    }

    @Test
    void testWiden() {
        Map<String, SimpleIntDomain.Interval> a = new HashMap<>();
        a.put("x", new SimpleIntDomain.Interval(BigInteger.ZERO, BigInteger.TEN));

        Map<String, SimpleIntDomain.Interval> b = new HashMap<>();
        b.put("x", new SimpleIntDomain.Interval(BigInteger.FIVE, BigInteger.TEN));

        Map<String, SimpleIntDomain.Interval> widened = domain.widen(a, b);

        assertEquals(BigInteger.ZERO, widened.get("x").min);
        assertEquals(BigInteger.TEN, widened.get("x").max);
    }

    @Test
    void testSoundnessBottom() {
        // Bottom should never entail anything
        assertFalse(domain.entails(domain.bottom(), domain.top()));
    }

    @Test
    void testIntervalJoin() {
        SimpleIntDomain.Interval i1 = new SimpleIntDomain.Interval(BigInteger.ZERO, BigInteger.TEN);
        SimpleIntDomain.Interval i2 = new SimpleIntDomain.Interval(BigInteger.FIVE, BigInteger.TWENTY);

        SimpleIntDomain.Interval result = i1.join(i2);

        assertEquals(BigInteger.ZERO, result.min);
        assertEquals(BigInteger.TWENTY, result.max);
    }
}
