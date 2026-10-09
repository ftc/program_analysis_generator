package test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

import pag.api.Domain;
import pag.api.LVal;
import pag.api.RVal;
import pag.api.Step;

import java.math.BigInteger;

@ExtendWith(JUnit5Extension.class)
class ReachabilityDomainTest {

    @BeforeEach
    void setUp() {
        Domain domain = new ReachabilityDomain();
    }

    @Test
    void test_top() {
        assertEquals(0, domain.top());
    }

    @Test
    void test_bottom() {
        assertEquals(Integer.MIN_VALUE, domain.bottom());
    }

    @Test
    void test_isBottom() {
        assertEquals(true, domain.isBottom(Integer.MIN_VALUE));
        assertFalse(domain.isBottom(1));
        assertFalse(domain.isBottom(0));
    }

    @Test
    void test_entails() {
        assertEquals(true, domain.entails(Integer.MIN_VALUE, Integer.MIN_VALUE));
        assertEquals(false, domain.entails(Integer.MIN_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void test_join() {
        assertEquals(Integer.MAX_VALUE, domain.join(Integer.MIN_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void test_widen() {
        assertEquals(Integer.MIN_VALUE, domain.widen(Integer.MIN_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void test_transfer() {
        assertEquals(Integer.MAX_VALUE, domain.transfer(Step.assign(0, BigInteger.ZERO), Integer.MAX_VALUE));
    }

    @Test
    void test_concrete() {
        Domain concrete = new ReachabilityDomain();
        concrete.top() = 0;
        concrete.bottom() = 1;
        assertEquals(Integer.MAX_VALUE, concrete.top());
        assertEquals(Integer.MIN_VALUE, concrete.bottom());
    }

    @Test
    void test_isBottom_concrete() {
        Domain concrete = new ReachabilityDomain();
        concrete.top() = 0;
        concrete.bottom() = 1;
        assertEquals(true, concrete.isBottom(Integer.MIN_VALUE));
        assertEquals(false, concrete.isBottom(1));
    }

    @Test
    void test_entails_concrete() {
        Domain concrete = new ReachabilityDomain();
        concrete.top() = 0;
        concrete.bottom() = 1;
        assertEquals(true, concrete.entails(Integer.MIN_VALUE, Integer.MIN_VALUE));
        assertEquals(false, concrete.entails(Integer.MIN_VALUE, 1));
    }

    @Test
    void test_join_concrete() {
        Domain concrete = new ReachabilityDomain();
        concrete.top() = 0;
        concrete.bottom() = 1;
        assertEquals(Integer.MAX_VALUE, concrete.join(Integer.MIN_VALUE, 1));
    }

    @Test
    void test_widen_concrete() {
        Domain concrete = new ReachabilityDomain();
        concrete.top() = 0;
        concrete.bottom() = 1;
        assertEquals(Integer.MIN_VALUE, concrete.widen(Integer.MIN_VALUE, 1));
    }

    @Test
    void test_transfer_concrete() {
        Domain concrete = new ReachabilityDomain();
        concrete.top() = 0;
        concrete.bottom() = 1;
        assertEquals(Integer.MAX_VALUE, concrete.transfer(Step.assign(0, BigInteger.ZERO), Integer.MAX_VALUE));
    }
}
