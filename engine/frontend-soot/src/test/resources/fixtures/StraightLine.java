import java.math.BigInteger;
import pag.probe.Rand;
import static pag.probe.Reach.reach;

public class StraightLine {                        // line 5
    public static void main(String[] args) {       // line 6
        BigInteger x = Rand.randInt();             // line 7
        BigInteger y = x.add(BigInteger.ONE);      // line 8: y is never read
        reach(1);                                  // line 9
    }                                              // line 10
}
