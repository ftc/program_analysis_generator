import java.math.BigInteger;
import pag.probe.Rand;
import static pag.probe.Reach.reach;

public class DeadStores {
    public static void main(String[] args) {
        BigInteger x = Rand.randInt();
        BigInteger y = x.add(BigInteger.ONE);      // line 8: never read; a call on the right
        BigInteger z = BigInteger.TEN;             // line 9: never read; no side effect
        reach(1);
    }
}
