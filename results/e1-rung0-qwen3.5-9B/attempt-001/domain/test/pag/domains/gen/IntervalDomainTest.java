package pag.domains.gen;

import org.junit.jupiter.api.Test;
import pag.api.*;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigInteger;

class IntervalDomainTest {
    
    private final IntervalDomain domain = new IntervalDomain();
    
    @Test
    void testTopBottomDistinct() {
        Map<String, Interval> top = domain.top();
        Map<String, Interval> bottom = domain.bottom();
        
        assertTrue(domain.isBottom(bottom));
        assertFalse(domain.isBottom(top));
        
        // top and bottom should be different
        assertFalse(top.equals(bottom));
    }
    
    @Test
    void testIsBottom() {
        Map<String, Interval> bottom = domain.bottom();
        Map<String, Interval> top = domain.top();
        
        assertTrue(domain.isBottom(bottom));
        assertFalse(domain.isBottom(top));
    }
    
    @Test
    void testEntailsBottom() {
        Map<String, Interval> bottom = domain.bottom();
        
        // bottom entails everything
        assertTrue(domain.entails(bottom, domain.top()));
        assertTrue(domain.entails(bottom, bottom));
    }
    
    @Test
    void testEntailsTop() {
        Map<String, Interval> top = domain.top();
        
        // top entails nothing (except bottom)
        assertTrue(domain.entails(top, domain.bottom()));
        assertFalse(domain.entails(top, top));
    }
    
    @Test
    void testJoin() {
        Map<String, Interval> a = new HashMap<>();
        a.put("x", new Interval(BigInteger.valueOf(0), BigInteger.valueOf(10)));
        
        Map<String, Interval> b = new HashMap<>();
        b.put("x", new Interval(BigInteger.valueOf(5), BigInteger.valueOf(15)));
        
        Map<String, Interval> result = domain.join(a, b);
        
        assertEquals(2, result.size());
        assertEquals(
            new Interval(BigInteger.valueOf(0), BigInteger.valueOf(15)),
            result.get("x")
        );
    }
    
    @Test
    void testJoinWithNull() {
        Map<String, Interval> a = new HashMap<>();
        a.put("x", new Interval(BigInteger.valueOf(0), BigInteger.valueOf(10)));
        
        Map<String, Interval> b = new HashMap<>();
        // b doesn't have "x"
        
        Map<String, Interval> result = domain.join(a, b);
        
        assertEquals(1, result.size());
        assertEquals(
            new Interval(BigInteger.valueOf(0), BigInteger.valueOf(10)),
            result.get("x")
        );
    }
    
    @Test
    void testTransferAssign() {
        Map<String, Interval> post = new HashMap<>();
        post.put("x", new Interval(BigInteger.valueOf(0), BigInteger.valueOf(10)));
        
        Assign step = new Assign(
            new IntervalDomain.Local("x", "int"),
            new IntervalDomain.IntConst(BigInteger.valueOf(5))
        );
        
        Map<String, Interval> pre = domain.transfer(step, post);
        
        // Before the assignment, x is unconstrained
        assertFalse(pre.containsKey("x"));
    }
    
    @Test
    void testTransferCall() {
        Map<String, Interval> post = new HashMap<>();
        post.put("x", new Interval(BigInteger.valueOf(0), BigInteger.valueOf(10)));
        
        Call step = new Call(
            Optional.of(new IntervalDomain.Local("x", "int")),
            new MethodId("pag.probe.Rand", "randInt", List.of(), "int"),
            List.of()
        );
        
        Map<String, Interval> pre = domain.transfer(step, post);
        
        // Before the call, x is unconstrained
        assertFalse(pre.containsKey("x"));
    }
    
    @Test
    void testIntervalContains() {
        Interval interval = new Interval(BigInteger.valueOf(0), BigInteger.valueOf(10));
        
        assertTrue(interval.contains(BigInteger.valueOf(0)));
        assertTrue(interval.contains(BigInteger.valueOf(5)));
        assertTrue(interval.contains(BigInteger.valueOf(10)));
        assertFalse(interval.contains(BigInteger.valueOf(-1)));
        assertFalse(interval.contains(BigInteger.valueOf(11)));
    }
    
    @Test
    void testIntervalWiden() {
        Interval a = new Interval(BigInteger.valueOf(0), BigInteger.valueOf(10));
        Interval b = new Interval(BigInteger.valueOf(5), BigInteger.valueOf(15));
        
        Interval widened = a.widen(b);
        
        assertEquals(
            new Interval(BigInteger.valueOf(0), BigInteger.valueOf(15)),
            widened
        );
    }
    
    @Test
    void testSoundnessEntails() {
        // [0, 10] entails [5, 8]
        Map<String, Interval> wider = new HashMap<>();
        wider.put("x", new Interval(BigInteger.valueOf(0), BigInteger.valueOf(10)));
        
        Map<String, Interval> narrower = new HashMap<>();
        narrower.put("x", new Interval(BigInteger.valueOf(5), BigInteger.valueOf(8)));
        
        assertTrue(domain.entails(wider, narrower));
        
        // [5, 8] does not entail [0, 10]
        assertFalse(domain.entails(narrower, wider));
    }
    
    @Test
    void testSoundnessJoin() {
        // join should be a superset of both inputs
        Map<String, Interval> a = new HashMap<>();
        a.put("x", new Interval(BigInteger.valueOf(0), BigInteger.valueOf(10)));
        
        Map<String, Interval> b = new HashMap<>();
        b.put("x", new Interval(BigInteger.valueOf(5), BigInteger.valueOf(15)));
        
        Map<String, Interval> result = domain.join(a, b);
        
        // result should entail both a and b
        assertTrue(domain.entails(result, a));
        assertTrue(domain.entails(result, b));
    }
}
