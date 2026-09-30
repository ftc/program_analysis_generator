import static pag.probe.Reach.reach;

public class Switch {
    public static void main(String[] args) {
        switch (args.length) {                     // line 5
            case 0: reach(1); break;
            case 1: reach(2); break;
            default: reach(3);
        }
    }
}
