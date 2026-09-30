import java.math.BigInteger;
import pag.probe.Rand;
import static pag.probe.Reach.reach;

public class Equals {
    public static void main(String[] args) {
        BigInteger x = Rand.randInt();
        if (x.equals(BigInteger.TEN)) reach(1);
    }
}
