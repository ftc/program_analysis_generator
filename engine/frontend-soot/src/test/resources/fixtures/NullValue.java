import static pag.probe.Reach.reach;

public class NullValue {
    public static void main(String[] args) {
        Object o = args.length > 0 ? args : null;  // line 5: null, read below
        if (o == args) reach(1);
    }
}
