package pag.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;
import java.util.List;
import org.junit.Test;

/** The input-list parser shared by Rand and the IR interpreter (implementation_strategy.md §5.6). */
public class InputsTest {

    private static List<BigInteger> ints(String... values) {
        return java.util.Arrays.stream(values).map(BigInteger::new).toList();
    }

    @Test
    public void valuesComeBackInOrder() {
        Inputs in = Inputs.parse("10,-3,0");
        assertEquals(new BigInteger("10"), in.next());
        assertEquals(new BigInteger("-3"), in.next());
        assertEquals(BigInteger.ZERO, in.next());
    }

    @Test
    public void valuesMayExceedLong() {
        String huge = "123456789012345678901234567890";
        assertEquals(ints(huge, "-" + huge), Inputs.parse(huge + ",-" + huge).values());
    }

    @Test
    public void whitespaceAroundValuesIsIgnored() {
        assertEquals(ints("1", "-2", "3"), Inputs.parse(" 1 ,\t-2,3 ").values());
    }

    @Test
    public void aLeadingPlusIsAccepted() {
        assertEquals(ints("5"), Inputs.parse("+5").values());
    }

    @Test
    public void nullMeansNoInputs() {
        assertEquals(List.of(), Inputs.parse(null).values());
    }

    @Test
    public void blankMeansNoInputs() {
        assertEquals(List.of(), Inputs.parse("  ").values());
    }

    @Test
    public void runningOutThrowsAndSaysHowManyThereWere() {
        Inputs in = Inputs.parse("4,5");
        in.next();
        in.next();
        IllegalStateException e = assertThrows(IllegalStateException.class, in::next);
        assertTrue(e.getMessage(), e.getMessage().contains("exhausted after 2 value(s)"));
    }

    @Test
    public void noInputsThrowsOnFirstUse() {
        assertThrows(IllegalStateException.class, () -> Inputs.parse(null).next());
    }

    @Test
    public void aMalformedValueIsNamed() {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> Inputs.parse("1, x2 ,3"));
        assertTrue(e.getMessage(), e.getMessage().contains("\"x2\""));
    }

    @Test
    public void anEmptyValueIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Inputs.parse("1,,2"));
    }

    @Test
    public void aTrailingCommaIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Inputs.parse("1,2,"));
    }

    @Test
    public void valuesIsUnmodifiable() {
        assertThrows(UnsupportedOperationException.class,
                () -> Inputs.parse("1").values().add(BigInteger.TWO));
    }
}
