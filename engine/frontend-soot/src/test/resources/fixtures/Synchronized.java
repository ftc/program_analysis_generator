import static pag.probe.Reach.reach;

public class Synchronized {
    public static void main(String[] args) {
        synchronized (args) { reach(1); }
    }
}
