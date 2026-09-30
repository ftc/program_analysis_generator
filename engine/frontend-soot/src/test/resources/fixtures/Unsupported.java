import static pag.probe.Reach.reach;

public class Unsupported {
    public static void main(String[] args) {
        Object o = new Object();                   // line 5: `new`, not yet translated
        reach(1);
    }
}
