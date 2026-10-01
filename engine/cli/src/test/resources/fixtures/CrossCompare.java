import java.math.BigInteger;
import pag.probe.Rand;
import static pag.probe.Reach.reach;

public class CrossCompare {
    public static void main(String[] args) {
        BigInteger x = Rand.randInt();
        BigInteger y = Rand.randInt();
        if (x.compareTo(y) < 0) reach(1);
        if (x.compareTo(y) <= 0) reach(2);
        if (x.compareTo(y) > 0) reach(3);
        if (x.compareTo(y) >= 0) reach(4);
        if (x.compareTo(y) == 0) reach(5);
        if (x.compareTo(y) != 0) reach(6);
        if (x.equals(y)) reach(7);
        if (x.compareTo(y) < 0 && y.compareTo(BigInteger.TEN) < 0) reach(8);
        reach(9);
    }
}
