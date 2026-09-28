import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.NoSuchElementException;
import java.util.SequencedCollection;
import java.util.SequencedMap;
import java.util.SequencedSet;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The sequenced views of the sorted collections and the deques, on the real library.
 *
 * SeqCheck covers List. This covers the rest of what JEP 431 reaches: Deque, SortedSet,
 * NavigableSet, SortedMap, NavigableMap, TreeSet, TreeMap and LinkedList, which is the awkward one
 * because it implements both List and Deque and their {@code reversed()} declarations return
 * different types.
 *
 * Reversed views are the part worth testing rather than reading. They are not copies — a write
 * through the view must reach the collection behind it, iteration must run the other way at every
 * level, and reversing twice must hand back the original rather than a second wrapper.
 */
public class SortedCheck {

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
            System.out.println("  MISMATCH " + what + ": nothing thrown, wanted "
                    + expected.getSimpleName());
        } catch (Throwable t) {
            if (!expected.isInstance(t)) {
                failed++;
                System.out.println("  MISMATCH " + what + ": " + t.getClass().getSimpleName()
                        + ", wanted " + expected.getSimpleName());
            }
        }
    }

    static <E> String walk(Iterable<E> it) {
        StringBuilder sb = new StringBuilder();
        for (E e : it) {
            sb.append(e);
        }
        return sb.toString();
    }

    static void deques() {
        Deque<String> d = new ArrayDeque<>(Arrays.asList("a", "b", "c", "d"));
        eq("ArrayDeque.getFirst", d.getFirst(), "a");
        eq("ArrayDeque.getLast", d.getLast(), "d");
        eq("ArrayDeque.reversed", walk(d.reversed()), "dcba");
        eq("ArrayDeque.reversed.getFirst", d.reversed().getFirst(), "d");
        eq("ArrayDeque.reversed twice", walk(d.reversed().reversed()), "abcd");
        eq("ArrayDeque.reversed is the original back", d.reversed().reversed() == d, true);
        eq("ArrayDeque.reversed.size", d.reversed().size(), 4);

        d.reversed().addFirst("e");
        eq("ArrayDeque.reversed.addFirst appends behind", walk(d), "abcde");
        eq("ArrayDeque.reversed.removeFirst", d.reversed().removeFirst(), "e");

        // A Deque is a SequencedCollection, which is the point of the new supertype.
        SequencedCollection<String> sc = d;
        eq("Deque as SequencedCollection", sc.getLast(), "d");

        Deque<String> empty = new ArrayDeque<>();
        throwsToo("empty deque getFirst", NoSuchElementException.class, empty::getFirst);
        throwsToo("empty reversed deque getFirst", NoSuchElementException.class,
                () -> empty.reversed().getFirst());
    }

    static void linkedList() {
        // LinkedList implements List and Deque, whose reversed() declarations disagree on the
        // return type. Its own override is what settles that, so both views are asked here.
        LinkedList<String> l = new LinkedList<>(Arrays.asList("a", "b", "c", "d"));
        eq("LinkedList.reversed", l.reversed().toString(), "[d, c, b, a]");
        eq("LinkedList.reversed is a LinkedList", l.reversed() instanceof LinkedList, true);
        eq("LinkedList.reversed.getFirst", l.reversed().getFirst(), "d");
        eq("LinkedList.reversed.get(1)", l.reversed().get(1), "c");
        eq("LinkedList.reversed iteration", walk(l.reversed()), "dcba");
        eq("LinkedList.reversed twice", l.reversed().reversed().toString(), "[a, b, c, d]");

        l.reversed().addFirst("e");
        eq("LinkedList.reversed.addFirst appends behind", l.toString(), "[a, b, c, d, e]");
        eq("LinkedList.reversed.removeFirst", l.reversed().removeFirst(), "e");
        l.reversed().set(0, "D");
        eq("LinkedList.reversed.set writes through", l.toString(), "[a, b, c, D]");

        List<String> asList = l;
        Deque<String> asDeque = l;
        eq("LinkedList as List", asList.getFirst(), "a");
        eq("LinkedList as Deque", asDeque.getLast(), "D");
    }

    static void sortedSets() {
        TreeSet<String> t = new TreeSet<>(Arrays.asList("b", "d", "a", "c"));
        eq("TreeSet.getFirst", t.getFirst(), "a");
        eq("TreeSet.getLast", t.getLast(), "d");
        eq("TreeSet.reversed", walk(t.reversed()), "dcba");
        eq("TreeSet.reversed.getFirst", t.reversed().getFirst(), "d");
        eq("TreeSet.reversed twice", walk(t.reversed().reversed()), "abcd");
        eq("TreeSet.removeFirst", t.removeFirst(), "a");
        eq("TreeSet after removeFirst", walk(t), "bcd");
        eq("TreeSet.removeLast", t.removeLast(), "d");
        eq("TreeSet after removeLast", walk(t), "bc");

        // A sorted set decides its own order, so placing an element is meaningless.
        throwsToo("TreeSet.addFirst", UnsupportedOperationException.class, () -> t.addFirst("z"));
        throwsToo("TreeSet.addLast", UnsupportedOperationException.class, () -> t.addLast("z"));

        SortedSet<String> ss = new TreeSet<>(Arrays.asList("a", "b", "c"));
        eq("SortedSet.getFirst", ss.getFirst(), "a");
        eq("SortedSet.reversed", walk(ss.reversed()), "cba");
        eq("SortedSet is a SequencedSet", ss instanceof SequencedSet, true);
        eq("SortedSet.reversed.comparator reverses",
                ss.reversed().comparator().compare("a", "b") > 0, true);

        NavigableSet<String> ns = new TreeSet<>(Arrays.asList("a", "b", "c"));
        eq("NavigableSet.reversed", walk(ns.reversed()), "cba");
        eq("NavigableSet.removeLast", ns.removeLast(), "c");

        TreeSet<String> empty = new TreeSet<>();
        throwsToo("empty TreeSet.getFirst", NoSuchElementException.class, empty::getFirst);
        throwsToo("empty TreeSet.removeLast", NoSuchElementException.class, empty::removeLast);
    }

    static void sortedMaps() {
        TreeMap<String, Integer> m = new TreeMap<>();
        m.put("b", 2);
        m.put("d", 4);
        m.put("a", 1);
        m.put("c", 3);

        eq("TreeMap.firstEntry", m.firstEntry().toString(), "a=1");
        eq("TreeMap.lastEntry", m.lastEntry().toString(), "d=4");
        eq("TreeMap.reversed keys", walk(m.reversed().keySet()), "dcba");
        eq("TreeMap.reversed values", walk(m.reversed().values()), "4321");
        eq("TreeMap.reversed.firstEntry", m.reversed().firstEntry().toString(), "d=4");
        eq("TreeMap.reversed.lastEntry", m.reversed().lastEntry().toString(), "a=1");
        eq("TreeMap.reversed twice", walk(m.reversed().reversed().keySet()), "abcd");
        eq("TreeMap.reversed.get", m.reversed().get("b"), 2);
        eq("TreeMap.reversed.size", m.reversed().size(), 4);
        eq("TreeMap.reversed.containsKey", m.reversed().containsKey("c"), true);

        // Writing through the reversed view must reach the map behind it.
        m.reversed().put("e", 5);
        eq("TreeMap.reversed.put writes through", walk(m.keySet()), "abcde");
        m.reversed().remove("e");
        eq("TreeMap.reversed.remove writes through", walk(m.keySet()), "abcd");

        // A sorted map decides its own order.
        throwsToo("TreeMap.putFirst", UnsupportedOperationException.class, () -> m.putFirst("z", 0));
        throwsToo("TreeMap.putLast", UnsupportedOperationException.class, () -> m.putLast("z", 0));

        eq("TreeMap.pollFirstEntry", m.pollFirstEntry().toString(), "a=1");
        eq("TreeMap.pollLastEntry", m.pollLastEntry().toString(), "d=4");
        eq("TreeMap after polls", walk(m.keySet()), "bc");

        SortedMap<String, Integer> sm = new TreeMap<>(m);
        eq("SortedMap is a SequencedMap", sm instanceof SequencedMap, true);
        eq("SortedMap.reversed", walk(sm.reversed().keySet()), "cb");
        throwsToo("SortedMap.putFirst", UnsupportedOperationException.class,
                () -> sm.putFirst("z", 0));

        NavigableMap<String, Integer> nm = new TreeMap<>(m);
        eq("NavigableMap.reversed", walk(nm.reversed().keySet()), "cb");

        TreeMap<String, Integer> empty = new TreeMap<>();
        eq("empty TreeMap.reversed", empty.reversed().size(), 0);
        eq("empty TreeMap.firstEntry", empty.firstEntry(), null);

        // The entry set of a reversed map, walked by hand: the iterator is its own implementation.
        StringBuilder sb = new StringBuilder();
        for (Iterator<java.util.Map.Entry<String, Integer>> it =
                     m.reversed().entrySet().iterator(); it.hasNext(); ) {
            sb.append(it.next());
        }
        eq("TreeMap.reversed.entrySet iterator", sb.toString(), "c=3b=2");
    }

    public static void main(String[] args) {
        deques();
        linkedList();
        sortedSets();
        sortedMaps();

        eq("unmodifiableSortedSet.reversed",
                walk(Collections.unmodifiableSortedSet(
                        new TreeSet<>(Arrays.asList("a", "b"))).reversed()), "ba");

        System.out.println("checked " + checked + " assertions, " + failed + " mismatches");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
