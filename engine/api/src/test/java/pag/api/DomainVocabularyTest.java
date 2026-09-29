package pag.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

/** The Java domain vocabulary (implementation_strategy.md §5.4). */
public class DomainVocabularyTest {

    private static final LVal.Local X = new LVal.Local("x", "java.math.BigInteger");
    private static final RVal ONE = new RVal.IntConst(BigInteger.ONE);
    private static final MethodId RAND_INT =
            new MethodId("pag.probe.Rand", "randInt", List.of(), "java.math.BigInteger");

    private static Set<String> permitted(Class<?> sealed) {
        return Arrays.stream(sealed.getPermittedSubclasses())
                .map(Class::getSimpleName)
                .collect(Collectors.toSet());
    }

    /**
     * The vocabulary is exactly what lowering can emit (§5.4). Growing it is a
     * deliberate decision, so changing these expected sets is a flagged change.
     */
    @Test
    public void theVocabularyIsExactlyWhatLoweringEmits() {
        assertEquals(Set.of("Assign", "Assume", "Call"), permitted(Step.class));
        assertEquals(Set.of("IntConst", "Binop", "LVal"), permitted(RVal.class));
        assertEquals(Set.of("Local", "StaticField"), permitted(LVal.class));
    }

    @Test
    public void nullsAreRejected() {
        assertThrows(NullPointerException.class, () -> new Step.Assign(X, null));
        assertThrows(NullPointerException.class, () -> new RVal.Binop(X, null, ONE));
        assertThrows(NullPointerException.class, () -> new Step.Call(null, RAND_INT, List.of()));
    }

    @Test
    public void callArgumentsAreCopiedAndUnmodifiable() {
        List<RVal> args = new ArrayList<>(List.of(ONE));
        Step.Call call = new Step.Call(Optional.of(X), RAND_INT, args);
        args.add(ONE);
        assertEquals(1, call.args().size());
        assertThrows(UnsupportedOperationException.class, () -> call.args().add(ONE));
    }

    @Test
    public void aMethodIdPrintsAsQualifiedNameAndParameters() {
        assertEquals("pag.probe.Rand.randInt()", RAND_INT.toString());
        assertEquals("pag.probe.Rand.randInt", RAND_INT.qualifiedName());
    }

    @Test
    public void overloadsAreDifferentMethods() {
        MethodId addBig = new MethodId("java.math.BigInteger", "add",
                List.of("java.math.BigInteger"), "java.math.BigInteger");
        MethodId addLong = new MethodId("java.math.BigInteger", "add", List.of("long"), "java.math.BigInteger");
        assertNotEquals(addBig, addLong);
        assertEquals(addBig.qualifiedName(), addLong.qualifiedName());
    }

    // A domain's transfer is a switch like these. No default: adding a case
    // without handling it here is a compile error.

    private static String kind(Step s) {
        return switch (s) {
            case Step.Assign a -> "assign";
            case Step.Assume a -> "assume";
            case Step.Call c -> "call";
        };
    }

    private static String kind(RVal v) {
        return switch (v) {
            case RVal.IntConst c -> "int";
            case RVal.Binop b -> "binop";
            case LVal.Local l -> "local";
            case LVal.StaticField f -> "static";
        };
    }

    @Test
    public void switchesOverTheVocabularyAreExhaustive() {
        assertEquals("call", kind(new Step.Call(Optional.of(X), RAND_INT, List.of())));
        assertEquals("local", kind((RVal) X));
    }
}
