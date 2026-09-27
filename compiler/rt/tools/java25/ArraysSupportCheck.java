package probe;

import java.util.Arrays;
import java.util.Random;

/**
 * The substitute ArraysSupport against the behaviour java.util.Arrays is contracted to produce.
 *
 * Arrays.mismatch and Arrays.hashCode are the public face of exactly these helpers, so the JDK's
 * own answers are the reference — including the two cases the class comment singles out: NaN must
 * equal NaN, and +0.0 must differ from -0.0.
 */
public class ArraysSupportCheck {

    static int checked, failed;

    static void eq(String what, Object ours, Object theirs) {
        checked++;
        if (!String.valueOf(ours).equals(String.valueOf(theirs))) {
            failed++;
            System.out.println("  MISMATCH " + what + " ours=" + ours + " jdk=" + theirs);
        }
    }

    public static void main(String[] args) {
        Random r = new Random(20260928);

        for (int round = 0; round < 4000; round++) {
            int n = r.nextInt(12);

            byte[] ba = new byte[n], bb = new byte[n];
            r.nextBytes(ba); System.arraycopy(ba, 0, bb, 0, n);
            if (n > 0 && r.nextBoolean()) bb[r.nextInt(n)] ^= 0x5a;
            eq("mismatch(byte[])", ArraysSupportCopy.mismatch(ba, bb, n), Arrays.mismatch(ba, bb));
            eq("hashCode(byte[])", ArraysSupportCopy.hashCode(ba, 0, n, 1), Arrays.hashCode(ba));

            int[] ia = r.ints(n).toArray(), ib = ia.clone();
            if (n > 0 && r.nextBoolean()) ib[r.nextInt(n)]++;
            eq("mismatch(int[])", ArraysSupportCopy.mismatch(ia, ib, n), Arrays.mismatch(ia, ib));
            eq("hashCode(int[])", ArraysSupportCopy.hashCode(ia, 0, n, 1), Arrays.hashCode(ia));

            long[] la = r.longs(n).toArray(), lb = la.clone();
            if (n > 0 && r.nextBoolean()) lb[r.nextInt(n)]--;
            eq("mismatch(long[])", ArraysSupportCopy.mismatch(la, lb, n), Arrays.mismatch(la, lb));

            char[] ca = new char[n], cb;
            for (int i = 0; i < n; i++) ca[i] = (char) r.nextInt(0x11000);
            cb = ca.clone();
            if (n > 0 && r.nextBoolean()) cb[r.nextInt(n)] += 1;
            eq("mismatch(char[])", ArraysSupportCopy.mismatch(ca, cb, n), Arrays.mismatch(ca, cb));
            eq("hashCode(char[])", ArraysSupportCopy.hashCode(ca, 0, n, 1), Arrays.hashCode(ca));

            short[] sa = new short[n], sb;
            for (int i = 0; i < n; i++) sa[i] = (short) r.nextInt();
            sb = sa.clone();
            if (n > 0 && r.nextBoolean()) sb[r.nextInt(n)]++;
            eq("mismatch(short[])", ArraysSupportCopy.mismatch(sa, sb, n), Arrays.mismatch(sa, sb));
            eq("hashCode(short[])", ArraysSupportCopy.hashCode(sa, 0, n, 1), Arrays.hashCode(sa));

            boolean[] za = new boolean[n], zb;
            for (int i = 0; i < n; i++) za[i] = r.nextBoolean();
            zb = za.clone();
            if (n > 0 && r.nextBoolean()) zb[r.nextInt(n)] ^= true;
            eq("mismatch(boolean[])", ArraysSupportCopy.mismatch(za, zb, n), Arrays.mismatch(za, zb));

            Object[] oa = new Object[n], ob;
            for (int i = 0; i < n; i++) oa[i] = r.nextBoolean() ? null : "s" + r.nextInt(4);
            ob = oa.clone();
            eq("hashCode(Object[])", ArraysSupportCopy.hashCode(oa, 0, n, 1), Arrays.hashCode(oa));
        }

        // The cases the comment singles out.
        float[] nanA = {Float.NaN}, nanB = {Float.NaN};
        eq("float NaN==NaN", ArraysSupportCopy.mismatch(nanA, nanB, 1), Arrays.mismatch(nanA, nanB));
        float[] zeroA = {0.0f}, zeroB = {-0.0f};
        eq("float +0 vs -0", ArraysSupportCopy.mismatch(zeroA, zeroB, 1), Arrays.mismatch(zeroA, zeroB));
        double[] dNanA = {Double.NaN}, dNanB = {Double.NaN};
        eq("double NaN==NaN", ArraysSupportCopy.mismatch(dNanA, dNanB, 1), Arrays.mismatch(dNanA, dNanB));
        double[] dZeroA = {0.0}, dZeroB = {-0.0};
        eq("double +0 vs -0", ArraysSupportCopy.mismatch(dZeroA, dZeroB, 1), Arrays.mismatch(dZeroA, dZeroB));

        // Also NaN and the zeroes mixed into longer runs, where the first difference must be found
        // at the right index rather than merely detected.
        float[] fa = {1f, Float.NaN, 0.0f, 3f}, fb = {1f, Float.NaN, -0.0f, 3f};
        eq("float run", ArraysSupportCopy.mismatch(fa, fb, 4), Arrays.mismatch(fa, fb));
        double[] da = {1, Double.NaN, 0.0, 3}, db = {1, Double.NaN, -0.0, 3};
        eq("double run", ArraysSupportCopy.mismatch(da, db, 4), Arrays.mismatch(da, db));

        // newLength, which callers depend on by its exact answer.
        eq("newLength normal", ArraysSupportCopy.newLength(10, 1, 10), 20);
        eq("newLength minGrowth wins", ArraysSupportCopy.newLength(10, 50, 1), 60);
        eq("newLength soft cap", ArraysSupportCopy.newLength(Integer.MAX_VALUE - 100, 1, 1000),
                ArraysSupportCopy.SOFT_MAX_ARRAY_LENGTH);
        eq("newLength above cap", ArraysSupportCopy.newLength(Integer.MAX_VALUE - 4, 3, 3),
                Integer.MAX_VALUE - 1);
        try {
            ArraysSupportCopy.newLength(Integer.MAX_VALUE, 2, 2);
            eq("newLength overflow", "no throw", "OutOfMemoryError");
        } catch (OutOfMemoryError expected) {
            eq("newLength overflow", "OutOfMemoryError", "OutOfMemoryError");
        }

        Integer[] rev = {1, 2, 3, 4};
        eq("reverse", Arrays.toString(ArraysSupportCopy.reverse(rev)), "[4, 3, 2, 1]");

        System.out.println("checked " + checked + " calls, " + failed + " mismatches");
        if (failed > 0) System.exit(1);
    }
}
