import java.math.BigInteger;
import pag.probe.Rand;
import static pag.probe.Reach.reach;

/** Outside the profile: reach(7) appears twice, which only --no-enforce admits. */
public class DuplicateReach {
    public static void main(String[] args) {
        BigInteger x = Rand.randInt();
        if (x.compareTo(BigInteger.ZERO) > 0) {
            reach(7);
        } else {
            reach(7);
        }
    }
}
