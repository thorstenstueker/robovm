import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.SequencedCollection;

/**
 * The sequenced methods, run on the real library rather than on a copy of it.
 *
 * This is compiled ahead of time against robovm-rt and executed as a native macOS binary, so the
 * List it exercises is the one a phone gets — with Android's ArrayList and LinkedList underneath,
 * not the JDK's. Transplanting the sources into a probe package would only have proved that a copy
 * of OpenJDK agrees with OpenJDK; the question here is whether the new supertype and its defaults
 * fit onto libcore's collections.
 */
public class SeqCheck {

    static int checked, failed;

    static void eq(String what, Object got, Object want) {
        checked++;
        if (!String.valueOf(got).equals(String.valueOf(want))) {
            failed++;
            System.out.println("  MISMATCH " + what + ": got " + got + ", wanted " + want);
        }
    }

    static void throwsToo(String what, Class<?> expected, Runnable r) {
        checked++;
        try {
            r.run();
            failed++;
            System.out.println("  MISMATCH " + what + ": nothing thrown, wanted " + expected.getName());
        } catch (Throwable t) {
            if (!expected.isInstance(t)) {
                failed++;
                System.out.println("  MISMATCH " + what + ": " + t.getClass().getName()
                        + ", wanted " + expected.getName());
            }
        }
    }

    static void exercise(String kind, List<String> list) {
        eq(kind + ".getFirst", list.getFirst(), "a");
        eq(kind + ".getLast", list.getLast(), "d");
        eq(kind + ".reversed", list.reversed().toString(), "[d, c, b, a]");
        eq(kind + ".reversed.getFirst", list.reversed().getFirst(), "d");
        eq(kind + ".reversed.getLast", list.reversed().getLast(), "a");
        eq(kind + ".reversed.reversed", list.reversed().reversed().toString(), "[a, b, c, d]");
        eq(kind + ".reversed.get(1)", list.reversed().get(1), "c");
        eq(kind + ".reversed.indexOf(c)", list.reversed().indexOf("c"), 1);
        eq(kind + ".reversed.size", list.reversed().size(), 4);
        eq(kind + ".reversed.contains", list.reversed().contains("b"), true);
        eq(kind + ".reversed.toArray", Arrays.toString(list.reversed().toArray()), "[d, c, b, a]");
        eq(kind + ".reversed.toArray(T[])",
                Arrays.toString(list.reversed().toArray(new String[0])), "[d, c, b, a]");
        // A longer array must be reused and a null written after the last element.
        String[] roomy = new String[6];
        Arrays.fill(roomy, "x");
        String[] back = list.reversed().toArray(roomy);
        eq(kind + ".reversed.toArray reuses", back == roomy, true);
        eq(kind + ".reversed.toArray terminates", Arrays.toString(back), "[d, c, b, a, null, x]");

        StringBuilder walked = new StringBuilder();
        for (String s : list.reversed()) {
            walked.append(s);
        }
        eq(kind + ".reversed iteration", walked.toString(), "dcba");

        // The list itself is a SequencedCollection now.
        SequencedCollection<String> sc = list;
        eq(kind + " is SequencedCollection", sc.getFirst(), "a");
    }

    static void exerciseMutable(String kind, List<String> list) {
        exercise(kind, list);
        list.addFirst("z");
        eq(kind + ".addFirst", list.toString(), "[z, a, b, c, d]");
        list.addLast("y");
        eq(kind + ".addLast", list.toString(), "[z, a, b, c, d, y]");
        eq(kind + ".removeFirst", list.removeFirst(), "z");
        eq(kind + ".removeLast", list.removeLast(), "y");
        eq(kind + ".after removes", list.toString(), "[a, b, c, d]");

        // Writing through the reversed view must reach the backing list.
        list.reversed().set(0, "D");
        eq(kind + ".reversed.set writes through", list.toString(), "[a, b, c, D]");
        list.reversed().set(0, "d");

        list.reversed().addFirst("w");
        eq(kind + ".reversed.addFirst", list.toString(), "[a, b, c, d, w]");
        eq(kind + ".reversed.removeFirst", list.reversed().removeFirst(), "w");

        list.clear();
        throwsToo(kind + ".getFirst when empty", NoSuchElementException.class, list::getFirst);
        throwsToo(kind + ".getLast when empty", NoSuchElementException.class, list::getLast);
        throwsToo(kind + ".removeFirst when empty", NoSuchElementException.class, list::removeFirst);
        throwsToo(kind + ".removeLast when empty", NoSuchElementException.class, list::removeLast);
    }

    public static void main(String[] args) {
        exerciseMutable("ArrayList", new ArrayList<>(Arrays.asList("a", "b", "c", "d")));
        exerciseMutable("LinkedList", new LinkedList<>(Arrays.asList("a", "b", "c", "d")));

        // Unmodifiable lists must stay unmodifiable through the view.
        List<String> fixed = Collections.unmodifiableList(
                new ArrayList<>(Arrays.asList("a", "b", "c", "d")));
        exercise("unmodifiableList", fixed);
        throwsToo("unmodifiableList.addFirst", UnsupportedOperationException.class,
                () -> fixed.addFirst("z"));
        throwsToo("unmodifiableList.reversed.addFirst", UnsupportedOperationException.class,
                () -> fixed.reversed().addFirst("z"));
        throwsToo("unmodifiableList.reversed.set", UnsupportedOperationException.class,
                () -> fixed.reversed().set(0, "z"));

        // Arrays.asList is fixed-size but settable.
        List<String> fixedSize = Arrays.asList("a", "b", "c", "d");
        exercise("Arrays.asList", fixedSize);
        throwsToo("Arrays.asList.addFirst", UnsupportedOperationException.class,
                () -> fixedSize.addFirst("z"));

        // A sublist is a List too, and its view must respect the window.
        List<String> window = new ArrayList<>(Arrays.asList("x", "a", "b", "c", "d", "y"))
                .subList(1, 5);
        exercise("subList", window);

        eq("emptyList.reversed", Collections.emptyList().reversed().toString(), "[]");
        throwsToo("emptyList.getFirst", NoSuchElementException.class,
                () -> Collections.emptyList().getFirst());

        List<String> one = new ArrayList<>(Collections.singletonList("only"));
        eq("single.getFirst", one.getFirst(), "only");
        eq("single.getLast", one.getLast(), "only");
        eq("single.reversed", one.reversed().toString(), "[only]");

        // The two members added alongside.
        eq("Duration.isPositive(+1s)", Duration.ofSeconds(1).isPositive(), true);
        eq("Duration.isPositive(0)", Duration.ZERO.isPositive(), false);
        eq("Duration.isPositive(-1s)", Duration.ofSeconds(-1).isPositive(), false);
        eq("Duration.isPositive(1ns)", Duration.ofNanos(1).isPositive(), true);
        eq("Duration.isPositive(-1ns)", Duration.ofNanos(-1).isPositive(), false);

        Object obj = new Object() {
            @Override public String toString() { return "lying"; }
            @Override public int hashCode() { return 42; }
        };
        String identity = Objects.toIdentityString(obj);
        eq("toIdentityString ignores toString", identity.startsWith("SeqCheck$"), true);
        eq("toIdentityString ignores hashCode", identity.endsWith("@2a"), false);
        throwsToo("toIdentityString(null)", NullPointerException.class,
                () -> Objects.toIdentityString(null));

        System.out.println("checked " + checked + " assertions, " + failed + " mismatches");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
