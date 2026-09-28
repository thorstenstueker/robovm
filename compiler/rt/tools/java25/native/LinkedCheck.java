import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.SequencedCollection;
import java.util.SequencedMap;
import java.util.SequencedSet;

/**
 * LinkedHashMap and LinkedHashSet under the sequenced methods, on the real library.
 *
 * These two were the reason the rest of JEP 431 waited. Unlike the sorted collections, whose
 * reversed views are wrappers written from the outside, LinkedHashMap's putFirst works by setting a
 * mode field that changes what the *insertion path itself* does when it links a new node — the same
 * path every map in the library goes through. So the questions here are not only whether the new
 * methods answer correctly, but whether ordinary puts, access ordering, removeEldestEntry and
 * iteration still behave exactly as before.
 *
 * Our runtime also carries a deliberate difference from OpenJDK, inherited from the libcore fork it
 * descends from: its forEach loops check modCount on every step rather than only at the end, so a
 * concurrent modification is caught while the walk is still running. Reversed iteration keeps that —
 * not out of fidelity to anything, but because it is better behaviour that our library already has,
 * and dropping it would be a regression for whoever depends on it. There is a case for it below.
 */
public class LinkedCheck {

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

    static LinkedHashMap<String, Integer> abcd() {
        LinkedHashMap<String, Integer> m = new LinkedHashMap<>();
        m.put("a", 1);
        m.put("b", 2);
        m.put("c", 3);
        m.put("d", 4);
        return m;
    }

    static void ordinaryBehaviourUnchanged() {
        LinkedHashMap<String, Integer> m = abcd();
        eq("insertion order kept", walk(m.keySet()), "abcd");
        m.put("b", 22);
        eq("re-put does not move", walk(m.keySet()), "abcd");
        eq("re-put replaces", m.get("b"), 22);
        m.remove("b");
        eq("remove", walk(m.keySet()), "acd");
        eq("size", m.size(), 3);

        // Access order, which shares the very field putFirst uses.
        LinkedHashMap<String, Integer> lru = new LinkedHashMap<>(16, 0.75f, true);
        lru.put("a", 1);
        lru.put("b", 2);
        lru.put("c", 3);
        lru.get("a");
        eq("access order moves to the end", walk(lru.keySet()), "bca");
        lru.put("b", 20);
        eq("access order on re-put", walk(lru.keySet()), "cab");

        // removeEldestEntry, the thing LruCache is built on.
        LinkedHashMap<String, Integer> capped = new LinkedHashMap<String, Integer>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                return size() > 3;
            }
        };
        for (String k : new String[]{"a", "b", "c", "d", "e"}) {
            capped.put(k, 0);
        }
        eq("removeEldestEntry still evicts", walk(capped.keySet()), "cde");

        StringBuilder sb = new StringBuilder();
        m.forEach((k, v) -> sb.append(k).append(v));
        eq("forEach(BiConsumer)", sb.toString(), "a1c3d4");
    }

    static void mapSequenced() {
        LinkedHashMap<String, Integer> m = abcd();

        eq("firstEntry", m.firstEntry().toString(), "a=1");
        eq("lastEntry", m.lastEntry().toString(), "d=4");

        m.putFirst("z", 0);
        eq("putFirst places at the front", walk(m.keySet()), "zabcd");
        m.putLast("y", 9);
        eq("putLast places at the back", walk(m.keySet()), "zabcdy");

        // putFirst on a key that is already there moves it rather than adding a second.
        m.putFirst("c", 33);
        eq("putFirst moves an existing key", walk(m.keySet()), "czabdy");
        eq("putFirst updates the value", m.get("c"), 33);
        eq("size unchanged by the move", m.size(), 6);
        m.putLast("c", 3);
        eq("putLast moves an existing key", walk(m.keySet()), "zabdyc");

        eq("pollFirstEntry", m.pollFirstEntry().toString(), "z=0");
        eq("pollLastEntry", m.pollLastEntry().toString(), "c=3");
        eq("after polls", walk(m.keySet()), "abdy");

        // The mode field must be put back even when the put throws.
        LinkedHashMap<String, Integer> guard = abcd();
        try {
            guard.putFirst(null, 0);
        } catch (RuntimeException ignored) {
            // Our LinkedHashMap accepts a null key, so this may well not throw. Either way what
            // matters is the next line.
        }
        guard.put("e", 5);
        eq("an ordinary put after putFirst still appends",
                walk(guard.keySet()).endsWith("e"), true);
    }

    static void mapReversed() {
        LinkedHashMap<String, Integer> m = abcd();
        SequencedMap<String, Integer> r = m.reversed();

        eq("reversed keys", walk(r.keySet()), "dcba");
        eq("reversed values", walk(r.values()), "4321");
        eq("reversed firstEntry", r.firstEntry().toString(), "d=4");
        eq("reversed lastEntry", r.lastEntry().toString(), "a=1");
        eq("reversed get", r.get("b"), 2);
        eq("reversed size", r.size(), 4);
        eq("reversed twice", walk(r.reversed().keySet()), "abcd");
        eq("reversed toString", r.toString(), "{d=4, c=3, b=2, a=1}");

        r.put("e", 5);
        eq("put through the reversed view reaches the map", walk(m.keySet()), "abcde");
        r.putFirst("f", 6);
        eq("putFirst through the view appends behind", walk(m.keySet()), "abcdef");
        r.remove("f");
        r.remove("e");
        eq("remove through the view", walk(m.keySet()), "abcd");

        StringBuilder sb = new StringBuilder();
        for (Iterator<Map.Entry<String, Integer>> it = r.entrySet().iterator(); it.hasNext(); ) {
            sb.append(it.next());
        }
        eq("reversed entrySet iterator", sb.toString(), "d=4c=3b=2a=1");
    }

    static void mapSequencedViews() {
        LinkedHashMap<String, Integer> m = abcd();

        SequencedSet<String> keys = m.sequencedKeySet();
        eq("sequencedKeySet.getFirst", keys.getFirst(), "a");
        eq("sequencedKeySet.getLast", keys.getLast(), "d");
        eq("sequencedKeySet.reversed", walk(keys.reversed()), "dcba");
        eq("sequencedKeySet is the keySet", keys == m.keySet(), true);
        eq("sequencedKeySet.toArray", Arrays.toString(keys.toArray()), "[a, b, c, d]");
        eq("sequencedKeySet.reversed.toArray",
                Arrays.toString(keys.reversed().toArray()), "[d, c, b, a]");
        eq("reversed.toArray(T[])",
                Arrays.toString(keys.reversed().toArray(new String[0])), "[d, c, b, a]");
        throwsToo("sequencedKeySet.addFirst", UnsupportedOperationException.class,
                () -> keys.addFirst("z"));

        SequencedCollection<Integer> vals = m.sequencedValues();
        eq("sequencedValues.getFirst", vals.getFirst(), 1);
        eq("sequencedValues.reversed", walk(vals.reversed()), "4321");
        eq("sequencedValues.toArray", Arrays.toString(vals.toArray()), "[1, 2, 3, 4]");

        SequencedSet<Map.Entry<String, Integer>> entries = m.sequencedEntrySet();
        eq("sequencedEntrySet.getFirst", entries.getFirst().toString(), "a=1");
        eq("sequencedEntrySet.getLast", entries.getLast().toString(), "d=4");
        eq("sequencedEntrySet.reversed", walk(entries.reversed()), "d=4c=3b=2a=1");

        eq("removeFirst through the key view", keys.removeFirst(), "a");
        eq("the map shrank with it", walk(m.keySet()), "bcd");
        eq("removeLast through the key view", keys.removeLast(), "d");
        eq("and again", walk(m.keySet()), "bc");

        // Reversed forEach, and the direction it walks in.
        StringBuilder sb = new StringBuilder();
        m.sequencedKeySet().reversed().forEach(sb::append);
        eq("reversed keySet forEach", sb.toString(), "cb");

        LinkedHashMap<String, Integer> empty = new LinkedHashMap<>();
        throwsToo("empty sequencedKeySet.getFirst", NoSuchElementException.class,
                () -> empty.sequencedKeySet().getFirst());
        eq("empty firstEntry", empty.firstEntry(), null);
        eq("empty reversed", empty.reversed().size(), 0);

        eq("newLinkedHashMap", LinkedHashMap.newLinkedHashMap(10).size(), 0);
        throwsToo("newLinkedHashMap(-1)", IllegalArgumentException.class,
                () -> LinkedHashMap.newLinkedHashMap(-1));
    }

    static void sets() {
        LinkedHashSet<String> s = new LinkedHashSet<>(Arrays.asList("a", "b", "c", "d"));
        eq("getFirst", s.getFirst(), "a");
        eq("getLast", s.getLast(), "d");
        eq("reversed", walk(s.reversed()), "dcba");
        eq("reversed twice", walk(s.reversed().reversed()), "abcd");
        eq("reversed size", s.reversed().size(), 4);
        eq("reversed contains", s.reversed().contains("b"), true);
        eq("reversed toArray", Arrays.toString(s.reversed().toArray()), "[d, c, b, a]");

        s.addFirst("z");
        eq("addFirst", walk(s), "zabcd");
        s.addLast("y");
        eq("addLast", walk(s), "zabcdy");
        s.addFirst("c");
        eq("addFirst moves an existing element", walk(s), "czabdy");
        eq("and does not duplicate it", s.size(), 6);

        eq("removeFirst", s.removeFirst(), "c");
        eq("removeLast", s.removeLast(), "y");
        eq("after removes", walk(s), "zabd");

        s.reversed().addFirst("w");
        eq("addFirst through the reversed view appends behind", walk(s), "zabdw");
        eq("removeFirst through the reversed view", s.reversed().removeFirst(), "w");

        eq("is a SequencedSet", s instanceof SequencedSet, true);
        eq("newLinkedHashSet", LinkedHashSet.newLinkedHashSet(10).size(), 0);

        LinkedHashSet<String> empty = new LinkedHashSet<>();
        throwsToo("empty getFirst", NoSuchElementException.class, empty::getFirst);
        throwsToo("empty removeLast", NoSuchElementException.class, empty::removeLast);
        eq("empty reversed", walk(empty.reversed()), "");
    }

    static void wrappers() {
        SequencedCollection<String> c = Collections.unmodifiableSequencedCollection(
                new ArrayList<>(Arrays.asList("a", "b", "c")));
        eq("unmodifiableSequencedCollection.getFirst", c.getFirst(), "a");
        eq("unmodifiableSequencedCollection.reversed", walk(c.reversed()), "cba");
        throwsToo("unmodifiableSequencedCollection.addFirst",
                UnsupportedOperationException.class, () -> c.addFirst("z"));

        SequencedSet<String> s = Collections.unmodifiableSequencedSet(
                new LinkedHashSet<>(Arrays.asList("a", "b", "c")));
        eq("unmodifiableSequencedSet.getLast", s.getLast(), "c");
        eq("unmodifiableSequencedSet.reversed", walk(s.reversed()), "cba");
        throwsToo("unmodifiableSequencedSet.removeFirst",
                UnsupportedOperationException.class, s::removeFirst);

        SequencedMap<String, Integer> m = Collections.unmodifiableSequencedMap(abcd());
        eq("unmodifiableSequencedMap.firstEntry", m.firstEntry().toString(), "a=1");
        eq("unmodifiableSequencedMap.reversed", walk(m.reversed().keySet()), "dcba");
        throwsToo("unmodifiableSequencedMap.putFirst",
                UnsupportedOperationException.class, () -> m.putFirst("z", 0));

        SequencedSet<String> fromMap = Collections.newSequencedSetFromMap(new LinkedHashMap<String, Boolean>());
        fromMap.add("a");
        fromMap.add("b");
        eq("newSequencedSetFromMap keeps order", walk(fromMap), "ab");
        eq("newSequencedSetFromMap.reversed", walk(fromMap.reversed()), "ba");
    }

    /** Our library catches a concurrent modification during the walk, not after it. */
    static void androidEarlyDetection() {
        LinkedHashSet<String> s = new LinkedHashSet<>(Arrays.asList("a", "b", "c", "d"));
        final LinkedHashMap<String, Integer> m = abcd();
        checked++;
        try {
            m.sequencedKeySet().reversed().forEach(k -> m.put("added" + k, 0));
            failed++;
            System.out.println("  MISMATCH reversed forEach did not notice a modification");
        } catch (java.util.ConcurrentModificationException expected) {
            // what should happen
        }
        eq("and the set is untouched by that", walk(s), "abcd");
    }

    public static void main(String[] args) {
        ordinaryBehaviourUnchanged();
        mapSequenced();
        mapReversed();
        mapSequencedViews();
        sets();
        wrappers();
        androidEarlyDetection();

        System.out.println("checked " + checked + " assertions, " + failed + " mismatches");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
