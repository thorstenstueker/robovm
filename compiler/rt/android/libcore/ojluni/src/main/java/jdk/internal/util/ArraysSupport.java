/*
 * Copyright (c) 2016, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 */
package jdk.internal.util;

import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;

/**
 * What {@code java.util.Arrays} needs underneath its Java 9+ methods — reimplemented without
 * {@code Unsafe}.
 *
 * <h2>Why this file exists</h2>
 *
 * <p>Written 28.09.2026 while bringing robovm-rt up to Java 25. {@code Arrays.mismatch},
 * {@code Arrays.compare}, {@code Arrays.equals} with ranges and {@code Arrays.hashCode} all route
 * through this class, and it was the single thing standing between us and 59 missing members of
 * {@code Arrays} plus 33 of {@code Properties}: <b>one helper blocking ninety.</b>
 *
 * <p>OpenJDK's version cannot be imported. It reaches for {@code jdk.internal.misc.Unsafe},
 * {@code jdk.internal.access.SharedSecrets} and {@code JavaLangAccess}, none of which robovm-rt
 * has, and it is written in Java 17 syntax while this library is compiled with {@code -source 8}
 * because it <em>is</em> the boot class path.
 *
 * <h2>Why a plain loop is not a compromise here</h2>
 *
 * <p>Read OpenJDK's {@code mismatch} methods and the structure is always the same: a vectorised
 * probe through {@code Unsafe}, then a plain loop that finishes the job — and every one of them
 * <em>ends</em> in that loop. The loop is the definition; the vectorised part is an optimisation
 * that must produce the same answer or it would be a bug. So this is not an approximation of the
 * contract, it is the contract with the shortcut left out.
 *
 * <p>And the shortcut buys nothing here. {@code vectorizedMismatch} is fast because HotSpot
 * recognises it and substitutes a hand-written routine; an ahead-of-time compiler has no
 * intrinsics table, so upstream's version would run its {@code Unsafe} loop one element at a time
 * exactly as this one does.
 *
 * <p>The float and double cases are worth reading twice. Their comparison is
 * {@code Float.floatToIntBits}, not {@code ==} and not {@code floatToRawIntBits}: two NaNs count as
 * equal because the bit patterns are normalised, and {@code +0.0} and {@code -0.0} count as
 * different because their bit patterns are not. Upstream's fast path uses <em>raw</em> bits and
 * then corrects for NaN afterwards; the loop it falls back to is the one copied here.
 */
public class ArraysSupport {

    /**
     * The largest array length that will not upset a typical VM.
     *
     * <p>{@code Integer.MAX_VALUE - 8}: some VMs keep header words in the array object and refuse
     * the last few lengths. Verbatim from OpenJDK, because callers compare against it.
     */
    public static final int SOFT_MAX_ARRAY_LENGTH = Integer.MAX_VALUE - 8;

    private ArraysSupport() {
    }

    // --- mismatch ---------------------------------------------------------------------------
    //
    // Answers the first index at which the two ranges differ, or -1 when the first `length`
    // elements match. The index is relative to the two from-indices, not absolute.

    public static int mismatch(boolean[] a, boolean[] b, int length) {
        return mismatch(a, 0, b, 0, length);
    }

    public static int mismatch(boolean[] a, int aFromIndex, boolean[] b, int bFromIndex, int length) {
        for (int i = 0; i < length; i++) {
            if (a[aFromIndex + i] != b[bFromIndex + i]) return i;
        }
        return -1;
    }

    public static int mismatch(byte[] a, byte[] b, int length) {
        return mismatch(a, 0, b, 0, length);
    }

    public static int mismatch(byte[] a, int aFromIndex, byte[] b, int bFromIndex, int length) {
        for (int i = 0; i < length; i++) {
            if (a[aFromIndex + i] != b[bFromIndex + i]) return i;
        }
        return -1;
    }

    public static int mismatch(char[] a, char[] b, int length) {
        return mismatch(a, 0, b, 0, length);
    }

    public static int mismatch(char[] a, int aFromIndex, char[] b, int bFromIndex, int length) {
        for (int i = 0; i < length; i++) {
            if (a[aFromIndex + i] != b[bFromIndex + i]) return i;
        }
        return -1;
    }

    public static int mismatch(short[] a, short[] b, int length) {
        return mismatch(a, 0, b, 0, length);
    }

    public static int mismatch(short[] a, int aFromIndex, short[] b, int bFromIndex, int length) {
        for (int i = 0; i < length; i++) {
            if (a[aFromIndex + i] != b[bFromIndex + i]) return i;
        }
        return -1;
    }

    public static int mismatch(int[] a, int[] b, int length) {
        return mismatch(a, 0, b, 0, length);
    }

    public static int mismatch(int[] a, int aFromIndex, int[] b, int bFromIndex, int length) {
        for (int i = 0; i < length; i++) {
            if (a[aFromIndex + i] != b[bFromIndex + i]) return i;
        }
        return -1;
    }

    public static int mismatch(long[] a, long[] b, int length) {
        return mismatch(a, 0, b, 0, length);
    }

    public static int mismatch(long[] a, int aFromIndex, long[] b, int bFromIndex, int length) {
        for (int i = 0; i < length; i++) {
            if (a[aFromIndex + i] != b[bFromIndex + i]) return i;
        }
        return -1;
    }

    /** {@code floatToIntBits} and not {@code ==}: NaN equals NaN, and +0.0 differs from -0.0. */
    public static int mismatch(float[] a, float[] b, int length) {
        return mismatch(a, 0, b, 0, length);
    }

    public static int mismatch(float[] a, int aFromIndex, float[] b, int bFromIndex, int length) {
        for (int i = 0; i < length; i++) {
            if (Float.floatToIntBits(a[aFromIndex + i]) != Float.floatToIntBits(b[bFromIndex + i])) {
                return i;
            }
        }
        return -1;
    }

    /** See the float case. */
    public static int mismatch(double[] a, double[] b, int length) {
        return mismatch(a, 0, b, 0, length);
    }

    public static int mismatch(double[] a, int aFromIndex, double[] b, int bFromIndex, int length) {
        for (int i = 0; i < length; i++) {
            if (Double.doubleToLongBits(a[aFromIndex + i])
                    != Double.doubleToLongBits(b[bFromIndex + i])) {
                return i;
            }
        }
        return -1;
    }

    // --- hashCode ---------------------------------------------------------------------------
    //
    // 31 * h + element, the accumulation every Arrays.hashCode has used since Java 1.5. Upstream
    // splits it across a vectorised helper; the Object[] case there is written out as a plain loop
    // and is the reference for all of them.

    public static int hashCode(byte[] a, int fromIndex, int length, int initialValue) {
        int result = initialValue;
        for (int i = fromIndex, end = fromIndex + length; i < end; i++) {
            result = 31 * result + a[i];
        }
        return result;
    }

    public static int hashCodeOfUnsigned(byte[] a, int fromIndex, int length, int initialValue) {
        int result = initialValue;
        for (int i = fromIndex, end = fromIndex + length; i < end; i++) {
            result = 31 * result + (a[i] & 0xff);
        }
        return result;
    }

    public static int hashCode(char[] a, int fromIndex, int length, int initialValue) {
        int result = initialValue;
        for (int i = fromIndex, end = fromIndex + length; i < end; i++) {
            result = 31 * result + a[i];
        }
        return result;
    }

    public static int hashCode(short[] a, int fromIndex, int length, int initialValue) {
        int result = initialValue;
        for (int i = fromIndex, end = fromIndex + length; i < end; i++) {
            result = 31 * result + a[i];
        }
        return result;
    }

    public static int hashCode(int[] a, int fromIndex, int length, int initialValue) {
        int result = initialValue;
        for (int i = fromIndex, end = fromIndex + length; i < end; i++) {
            result = 31 * result + a[i];
        }
        return result;
    }

    public static int hashCode(Object[] a, int fromIndex, int length, int initialValue) {
        int result = initialValue;
        for (int i = fromIndex, end = fromIndex + length; i < end; i++) {
            result = 31 * result + Objects.hashCode(a[i]);
        }
        return result;
    }

    // --- capacity growth --------------------------------------------------------------------

    /**
     * How long an array should become when it has to grow — verbatim from OpenJDK.
     *
     * <p>Copied rather than reasoned out because callers depend on the exact answer, including
     * that it may return a length above {@link #SOFT_MAX_ARRAY_LENGTH} when the minimum demands
     * it, and that it throws rather than returning a negative length on overflow.
     *
     * @param oldLength  current length, must not be negative
     * @param minGrowth  smallest acceptable growth, must be positive
     * @param prefGrowth preferred growth
     * @throws OutOfMemoryError when the result would pass {@code Integer.MAX_VALUE}
     */
    public static int newLength(int oldLength, int minGrowth, int prefGrowth) {
        int prefLength = oldLength + Math.max(minGrowth, prefGrowth);   // may overflow
        if (0 < prefLength && prefLength <= SOFT_MAX_ARRAY_LENGTH) {
            return prefLength;
        }
        return hugeLength(oldLength, minGrowth);
    }

    private static int hugeLength(int oldLength, int minGrowth) {
        int minLength = oldLength + minGrowth;
        if (minLength < 0) {
            throw new OutOfMemoryError(
                    "Required array length " + oldLength + " + " + minGrowth + " is too large");
        }
        return minLength <= SOFT_MAX_ARRAY_LENGTH ? SOFT_MAX_ARRAY_LENGTH : minLength;
    }

    /** Reverses in place and answers the same array. */
    public static <T> T[] reverse(T[] a) {
        for (int i = 0, j = a.length - 1; i < j; i++, j--) {
            T t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
        return a;
    }

    /**
     * The collection's elements into the array in reverse order, following the contract of
     * {@link java.util.Collection#toArray(Object[])} about reusing the array and the trailing null.
     *
     * <p>Verbatim from OpenJDK, and the comment there explains a constraint worth keeping: the
     * collection is asked for its contents exactly once. A separate {@code size()} call, or an
     * iterator, would go wrong if the collection changed between the two — so the elements arrive
     * through a single {@code toArray}, at the cost of one extra copy.
     *
     * <p>The obvious alternative, {@code coll.toArray(array)} followed by reversing in place, does
     * not work: if the array passed in is longer than the collection there is no way to tell how
     * many entries were written, and so no way to reverse the right ones and leave the rest alone.
     *
     * <p>Needed by {@code ReverseOrderListView}, which is what {@code List.reversed()} returns.
     *
     * @throws ArrayStoreException if the collection holds something the array cannot store
     */
    public static <T> T[] toArrayReversed(Collection<?> coll, T[] array) {
        T[] reversed = reverse(coll.toArray(Arrays.copyOfRange(array, 0, 0)));
        if (reversed.length > array.length) {
            return reversed;
        }
        System.arraycopy(reversed, 0, array, 0, reversed.length);
        if (array.length > reversed.length) {
            array[reversed.length] = null;
        }
        return array;
    }
}
