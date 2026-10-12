package pag.domains.gen;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import pag.api.*;

public class IntervalDomainTest {

    private final IntervalDomain domain = new IntervalDomain();
    
    @Test
    public void testTopAndBottom() {
        State top = domain.top();
        State bottom = domain.bottom();
        
        assertFalse(domain.isBottom(top));
        assertTrue(domain.isBottom(bottom));
    }
    
    @Test
    public void testEntails() {
        State top = domain.top();
        State bottom = domain.bottom();
        
        assertTrue(domain.entails(bottom, top));
        assertTrue(domain.entails(bottom, bottom));
        assertFalse(domain.entails(top, bottom));
        assertTrue(domain.entails(top, top));
    }
    
    @Test
    public void testTransferAssignConstant() {
        State post = domain.top();
        
        Step.Assign assign = new Step.Assign(
            new LVal.Local("x", "int"),
            new RVal.IntConst(BigInteger.valueOf(5))
        );
        
        State pre = domain.transfer(assign, post);
        
        assertFalse(domain.isBottom(pre));
    }
    
    @Test
    public void testTransferAssignImpossible() {
        State post = domain.top();
        
        Step.Assign assign = new Step.Assign(
            new LVal.Local("x", "int"),
            new RVal.IntConst(BigInteger.valueOf(5))
        );
        
        State pre = domain.transfer(assign, post);
        
        Step.Assume assume = new Step.Assume(
            new RVal.Binop(
                new LVal.Local("x", "int"),
                BinOp.Eq,
                new RVal.IntConst(BigInteger.valueOf(10))
            )
        );
        
        State pre2 = domain.transfer(assume, pre);
        
        assertTrue(domain.isBottom(pre2));
    }
    
    @Test
    public void testTransferAssume() {
        State post = domain.top();
        
        Step.Assume assume = new Step.Assume(
            new RVal.Binop(
                new LVal.Local("x", "int"),
                BinOp.Le,
                new RVal.IntConst(BigInteger.valueOf(10))
            )
        );
        
        State pre = domain.transfer(assume, post);
        
        assertFalse(domain.isBottom(pre));
    }
    
    @Test
    public void testTransferAssumeImpossible() {
        State post = domain.top();
        
        Step.Assume assume1 = new Step.Assume(
            new RVal.Binop(
                new LVal.Local("x", "int"),
                BinOp.Ge,
                new RVal.IntConst(BigInteger.valueOf(10))
            )
        );
        
        State pre1 = domain.transfer(assume1, post);
        
        Step.Assume assume2 = new Step.Assume(
            new RVal.Binop(
                new LVal.Local("x", "int"),
                BinOp.Lt,
                new RVal.IntConst(BigInteger.valueOf(5))
            )
        );
        
        State pre2 = domain.transfer(assume2, pre1);
        
        assertTrue(domain.isBottom(pre2));
    }
    
    @Test
    public void testJoin() {
        State top = domain.top();
        State bottom = domain.bottom();
        
        State joined = domain.join(top, bottom);
        assertFalse(domain.isBottom(joined));
        
        State joined2 = domain.join(bottom, bottom);
        assertTrue(domain.isBottom(joined2));
    }
    
    @Test
    public void testWiden() {
        State top = domain.top();
        State bottom = domain.bottom();
        
        State widened = domain.widen(top, bottom);
        assertFalse(domain.isBottom(widened));
        
        State widened2 = domain.widen(bottom, bottom);
        assertTrue(domain.isBottom(widened2));
    }
    
    @Test
    public void testTransferCall() {
        State post = domain.top();
        
        Step.Call call = new Step.Call(
            Optional.of(new LVal.Local("x", "int")),
            new MethodId("pag.probe.Rand", "randInt", List.of(), "int"),
            List.of()
        );
        
        State pre = domain.transfer(call, post);
        
        assertFalse(domain.isBottom(pre));
    }
    
    @Test
    public void testSimpleUnreachableProof() {
        State post = domain.top();
        
        Step.Assign assign = new Step.Assign(
            new LVal.Local("x", "int"),
            new RVal.IntConst(BigInteger.valueOf(5))
        );
        
        State pre1 = domain.transfer(assign, post);
        
        Step.Assume assume = new Step.Assume(
            new RVal.Binop(
                new LVal.Local("x", "int"),
                BinOp.Eq,
                new RVal.IntConst(BigInteger.valueOf(10))
            )
        );
        
        State pre2 = domain.transfer(assume, pre1);
        
        assertTrue(domain.isBottom(pre2));
    }
}
