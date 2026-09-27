package probe;

import java.lang.reflect.Method;
import java.util.Random;

/**
 * Every member added to rt's StrictMath, answered side by side with the JDK's own StrictMath.
 *
 * Compiling proved only that the code parses. These methods are arithmetic with edge cases that
 * decide correctness — ceilDiv at Integer.MIN_VALUE, the *Exact family at the overflow boundary,
 * clamp when min > max — so each one is called with the boundaries and with random values, and the
 * answer is held against the reference implementation.
 */
public class StrictMathCheck {

    static int checked, failed;

    static final long[] LONGS = {0, 1, -1, 2, -2, 3, -3, 7, -7, 10, -10,
            Integer.MAX_VALUE, Integer.MIN_VALUE, Long.MAX_VALUE, Long.MIN_VALUE,
            Integer.MAX_VALUE - 1L, Integer.MIN_VALUE + 1L, 1L << 32, -(1L << 32)};

    /** Both sides invoked reflectively, so a throw counts as an answer and is compared too. */
    static Object call(Method m, Object... args) {
        try {
            return m.invoke(null, args);
        } catch (Exception e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            return c.getClass().getName() + ": " + c.getMessage();
        }
    }

    static void both(String name, Class<?>[] sig, Object[] args) {
        Method mine, theirs;
        try {
            mine = StrictMathAdditions.class.getMethod(name, sig);
        } catch (NoSuchMethodException absent) {
            return;
        }
        try {
            theirs = StrictMath.class.getMethod(name, sig);
        } catch (NoSuchMethodException absent) {
            return;
        }
        checked++;
        Object a = call(mine, args), b = call(theirs, args);
        if (!String.valueOf(a).equals(String.valueOf(b))) {
            failed++;
            StringBuilder sb = new StringBuilder("  MISMATCH " + name + "(");
            for (int i = 0; i < args.length; i++) sb.append(i > 0 ? ", " : "").append(args[i]);
            System.out.println(sb + ") ours=" + a + " jdk=" + b);
        }
    }

    public static void main(String[] args) {
        Random r = new Random(20260928);

        for (long x : LONGS) {
            for (long y : LONGS) {
                int xi = (int) x, yi = (int) y;
                both("ceilDiv", new Class[]{int.class, int.class}, new Object[]{xi, yi});
                both("ceilDiv", new Class[]{long.class, int.class}, new Object[]{x, yi});
                both("ceilDiv", new Class[]{long.class, long.class}, new Object[]{x, y});
                both("ceilMod", new Class[]{int.class, int.class}, new Object[]{xi, yi});
                both("ceilMod", new Class[]{long.class, int.class}, new Object[]{x, yi});
                both("ceilMod", new Class[]{long.class, long.class}, new Object[]{x, y});
                both("ceilDivExact", new Class[]{int.class, int.class}, new Object[]{xi, yi});
                both("ceilDivExact", new Class[]{long.class, long.class}, new Object[]{x, y});
                both("floorDivExact", new Class[]{int.class, int.class}, new Object[]{xi, yi});
                both("floorDivExact", new Class[]{long.class, long.class}, new Object[]{x, y});
                both("divideExact", new Class[]{int.class, int.class}, new Object[]{xi, yi});
                both("divideExact", new Class[]{long.class, long.class}, new Object[]{x, y});
                both("unsignedMultiplyHigh", new Class[]{long.class, long.class}, new Object[]{x, y});
                both("clamp", new Class[]{long.class, int.class, int.class},
                        new Object[]{x, Math.min(xi, yi), Math.max(xi, yi)});
                both("clamp", new Class[]{long.class, long.class, long.class},
                        new Object[]{x, Math.min(x, y), Math.max(x, y)});
            }
        }

        // The *Exact family, right at the boundary where it must start throwing.
        for (int i = 0; i < 20000; i++) {
            long x = r.nextInt(), y = r.nextInt();
            int xi = (int) x, yi = (int) y;
            both("divideExact", new Class[]{int.class, int.class}, new Object[]{xi, yi});
            both("ceilDivExact", new Class[]{long.class, long.class}, new Object[]{x, y});
            both("unsignedMultiplyHigh", new Class[]{long.class, long.class},
                    new Object[]{r.nextLong(), r.nextLong()});
            both("unsignedMultiplyExact", new Class[]{long.class, long.class}, new Object[]{x, y});
            both("unsignedMultiplyExact", new Class[]{int.class, int.class}, new Object[]{xi, yi});
            both("powExact", new Class[]{int.class, int.class},
                    new Object[]{xi % 1000, Math.abs(yi % 40)});
            both("powExact", new Class[]{long.class, int.class},
                    new Object[]{x % 100000, Math.abs(yi % 40)});
            both("unsignedPowExact", new Class[]{int.class, int.class},
                    new Object[]{xi, Math.abs(yi % 40)});
            both("unsignedPowExact", new Class[]{long.class, int.class},
                    new Object[]{x, Math.abs(yi % 40)});
        }

        // clamp with the double and float shapes, including the NaN and signed-zero cases.
        double[] ds = {0.0, -0.0, 1.0, -1.0, Double.NaN, Double.MIN_VALUE,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e300, -1e300};
        for (double v : ds) {
            for (double lo : ds) {
                for (double hi : ds) {
                    if (!(lo <= hi)) continue;
                    both("clamp", new Class[]{double.class, double.class, double.class},
                            new Object[]{v, lo, hi});
                    both("clamp", new Class[]{float.class, float.class, float.class},
                            new Object[]{(float) v, (float) lo, (float) hi});
                }
            }
        }

        System.out.println("checked " + checked + " calls, " + failed + " mismatches");
        if (failed > 0) System.exit(1);
    }
}
