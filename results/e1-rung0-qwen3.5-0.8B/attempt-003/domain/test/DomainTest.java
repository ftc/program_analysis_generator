package test;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import pag.api.BinOp;
import pag.api.Domain;
import pag.api.LVal;
import pag.api.MethodId;
import pag.api.RVal;
import pag.api.Step;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the domain.
 */
@ExtendWith({
    org.junit.jupiter.api.Test,
    org.junit.jupiter.api.extension.ExtendWith
})
class DomainTest {

    @BeforeEach
    void setUp() {
        // Domain domain is a concrete instance of Domain
        Domain domain = new Domain();
    }

    @Test
    void top_returns_target_state() {
        assertSame(domain.top(), new RVal.IntConst(1L));
    }

    @Test
    void bottom_returns_entry_point_state() {
        assertSame(domain.bottom(), new RVal.IntConst(0L));
    }

    @Test
    void entails_returns_false_when_a_is_not_b() {
        assertNotEquals(domain.entails(domain.top(), domain.bottom()), true);
    }

    @Test
    void join_returns_join_state() {
        assertSame(domain.join(domain.top(), domain.bottom()), domain.top());
    }

    @Test
    void widen_returns_wider_state() {
        assertSame(domain.widen(domain.top(), domain.bottom()), domain.top());
    }

    @Test
    void transfer_returns_post_state() {
        assertSame(domain.transfer(domain.top(), domain.bottom()), domain.top());
    }

    @Test
    void isBottom_returns_true_for_target() {
        assertSame(domain.isBottom(domain.top()), true);
    }

    @Test
    void isBottom_returns_false_for_entry() {
        assertNotSame(domain.isBottom(domain.bottom()), true);
    }

    @Test
    void isBottom_returns_false_for_non_target() {
        assertNotSame(domain.isBottom(domain.join(domain.top(), domain.bottom())), false);
    }

    @Test
    void isBottom_returns_true_for_non_target() {
        assertSame(domain.isBottom(domain.join(domain.top(), domain.bottom())), true);
    }

    @Test
    void name_returns_domain_name() {
        assertSame(domain.name(), "Domain");
    }

    @Test
    void constructor_returns_no_args() {
        assertNotSame(domain, null);
    }

    @Test
    void constructor_returns_instance_of_domain() {
        Domain domain = new Domain();
        assertNotNull(domain);
    }

    @Test
    void constructor_returns_instance_of_domain() {
        Domain domain = new Domain();
        assertNotNull(domain);
    }
}
