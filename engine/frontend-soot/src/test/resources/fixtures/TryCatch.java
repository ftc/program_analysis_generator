import static pag.probe.Reach.reach;

public class TryCatch {
    public static void main(String[] args) {
        try { reach(1); } catch (RuntimeException e) { reach(2); }
    }
}
