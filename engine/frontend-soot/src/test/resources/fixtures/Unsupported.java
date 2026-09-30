import static pag.probe.Reach.reach;

public class Unsupported {
    public static void main(String[] args) {
        int q = args.length / 2;                   // line 5: division, outside the IR
        if (q > 0) reach(1);                       // q is read, so Soot keeps the division
    }
}
