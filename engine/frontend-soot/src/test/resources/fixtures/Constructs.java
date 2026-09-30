import static pag.probe.Reach.reach;

public class Constructs {
    static int count;
    int value;

    public static void main(String[] args) {
        Object o = new Object();                         // new
        String s = "hi";                                 // string constant
        String t = (String) o;                           // cast
        boolean isString = o instanceof String;          // instanceof
        int n = args.length;                             // array length
        String first = args[0];                          // array read
        args[0] = s;                                     // array write
        args[1] = first;
        args[2] = t;
        count = n + 1;                                   // +, static field write
        Constructs c = new Constructs();
        c.value = count - n;                             // -, static field read, instance field write
        int p = c.value * 2;                             // *, instance field read
        if (isString) reach(1);
        if (p < 0) throw new IllegalStateException();    // throw
    }
}
