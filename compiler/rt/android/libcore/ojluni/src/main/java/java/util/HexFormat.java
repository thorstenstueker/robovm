/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates. All rights reserved.
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
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package java.util;

import java.io.IOException;
import java.io.UncheckedIOException;

// RoboVM Note: added for Java 17 API parity (from OpenJDK 17u, adapted: no JavaLangAccess/Unsafe fast paths)

/**
 * {@code HexFormat} converts between bytes and chars and hex-encoded strings which may include
 * additional formatting markup such as prefixes, suffixes, and delimiters (Java 17).
 */
public final class HexFormat {

    private static final byte[] UPPERCASE_DIGITS = {
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'A', 'B', 'C', 'D', 'E', 'F',
    };
    private static final byte[] LOWERCASE_DIGITS = {
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'a', 'b', 'c', 'd', 'e', 'f',
    };
    // Analysis has shown that generating the whole array allows the JIT to generate
    // better code compared to a slimmed down array, such as one cutting off after 'f'
    private static final byte[] DIGITS = {
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, -1, -1, -1, -1, -1, -1,
            -1, 10, 11, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, 10, 11, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
    };

    /**
     * Format each byte of an array as a pair of hexadecimal digits.
     * The hexadecimal characters are from lowercase alpha digits.
     */
    private static final HexFormat HEX_FORMAT =
            new HexFormat("", "", "", LOWERCASE_DIGITS);

    private static final byte[] EMPTY_BYTES = {};

    private final String delimiter;
    private final String prefix;
    private final String suffix;
    private final byte[] digits;

    private HexFormat(String delimiter, String prefix, String suffix, byte[] digits) {
        this.delimiter = Objects.requireNonNull(delimiter, "delimiter");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        this.suffix = Objects.requireNonNull(suffix, "suffix");
        this.digits = digits;
    }

    /**
     * Returns a hexadecimal formatter with no delimiter and lowercase characters.
     */
    public static HexFormat of() {
        return HEX_FORMAT;
    }

    /**
     * Returns a hexadecimal formatter with the delimiter and lowercase characters.
     */
    public static HexFormat ofDelimiter(String delimiter) {
        return new HexFormat(delimiter, "", "", LOWERCASE_DIGITS);
    }

    /**
     * Returns a copy of this {@code HexFormat} with the delimiter.
     */
    public HexFormat withDelimiter(String delimiter) {
        return new HexFormat(delimiter, this.prefix, this.suffix, this.digits);
    }

    /**
     * Returns a copy of this {@code HexFormat} with the prefix.
     */
    public HexFormat withPrefix(String prefix) {
        return new HexFormat(this.delimiter, prefix, this.suffix, this.digits);
    }

    /**
     * Returns a copy of this {@code HexFormat} with the suffix.
     */
    public HexFormat withSuffix(String suffix) {
        return new HexFormat(this.delimiter, this.prefix, suffix, this.digits);
    }

    /**
     * Returns a copy of this {@code HexFormat} to use uppercase hexadecimal characters.
     */
    public HexFormat withUpperCase() {
        return new HexFormat(this.delimiter, this.prefix, this.suffix, UPPERCASE_DIGITS);
    }

    /**
     * Returns a copy of this {@code HexFormat} to use lowercase hexadecimal characters.
     */
    public HexFormat withLowerCase() {
        return new HexFormat(this.delimiter, this.prefix, this.suffix, LOWERCASE_DIGITS);
    }

    public String delimiter() {
        return delimiter;
    }

    public String prefix() {
        return prefix;
    }

    public String suffix() {
        return suffix;
    }

    public boolean isUpperCase() {
        return digits == UPPERCASE_DIGITS;
    }

    /**
     * Returns a hexadecimal string formatted from a byte array.
     */
    public String formatHex(byte[] bytes) {
        return formatHex(bytes, 0, bytes.length);
    }

    /**
     * Returns a hexadecimal string formatted from a byte array range.
     */
    public String formatHex(byte[] bytes, int fromIndex, int toIndex) {
        Objects.requireNonNull(bytes, "bytes");
        checkFromToIndex(fromIndex, toIndex, bytes.length);
        if (toIndex - fromIndex == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(checkMaxArraySize((long) (toIndex - fromIndex) * 2L));
        formatHex(sb, bytes, fromIndex, toIndex);
        return sb.toString();
    }

    /**
     * Appends formatted hexadecimal strings from a byte array to the {@link Appendable}.
     */
    public <A extends Appendable> A formatHex(A out, byte[] bytes) {
        return formatHex(out, bytes, 0, bytes.length);
    }

    /**
     * Appends formatted hexadecimal strings from a byte array range to the {@link Appendable}.
     */
    public <A extends Appendable> A formatHex(A out, byte[] bytes, int fromIndex, int toIndex) {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(bytes, "bytes");
        checkFromToIndex(fromIndex, toIndex, bytes.length);

        int length = toIndex - fromIndex;
        if (length > 0) {
            try {
                String between = suffix + delimiter + prefix;
                out.append(prefix);
                toHexDigits(out, bytes[fromIndex]);
                if (between.isEmpty()) {
                    for (int i = 1; i < length; i++) {
                        toHexDigits(out, bytes[fromIndex + i]);
                    }
                } else {
                    for (int i = 1; i < length; i++) {
                        out.append(between);
                        toHexDigits(out, bytes[fromIndex + i]);
                    }
                }
                out.append(suffix);
            } catch (IOException ioe) {
                throw new UncheckedIOException(ioe.getMessage(), ioe);
            }
        }
        return out;
    }

    /**
     * Returns a byte array containing hexadecimal values parsed from the string.
     */
    public byte[] parseHex(CharSequence string) {
        return parseHex(string, 0, string.length());
    }

    /**
     * Returns a byte array containing hexadecimal values parsed from a range of the string.
     */
    public byte[] parseHex(CharSequence string, int fromIndex, int toIndex) {
        Objects.requireNonNull(string, "string");
        checkFromToIndex(fromIndex, toIndex, string.length());

        if (fromIndex != 0 || toIndex != string.length()) {
            string = string.subSequence(fromIndex, toIndex);
        }

        if (string.length() == 0)
            return EMPTY_BYTES;
        if (delimiter.isEmpty() && prefix.isEmpty() && suffix.isEmpty())
            return parseNoDelimiter(string);

        // avoid overflow for max length prefix and suffix
        long valueChars = (long) prefix.length() + 2L + suffix.length();
        long stride = valueChars + delimiter.length();
        if ((string.length() - valueChars) % stride != 0)
            throw new IllegalArgumentException("extra or missing delimiters " +
                    "or values consisting of prefix, two hexadecimal digits, and suffix");

        checkLiteral(string, 0, prefix);
        checkLiteral(string, string.length() - suffix.length(), suffix);
        String between = suffix + delimiter + prefix;
        final int len = (int) ((string.length() - valueChars) / stride + 1L);
        byte[] bytes = new byte[len];
        int i, offset;
        for (i = 0, offset = prefix.length(); i < len - 1; i++, offset += 2 + between.length()) {
            int v = fromHexDigits(string, offset);
            if (v < 0)
                throw new IllegalArgumentException("input contains non-hexadecimal characters");
            bytes[i] = (byte) v;
            checkLiteral(string, offset + 2, between);
        }
        int v = fromHexDigits(string, offset);
        if (v < 0)
            throw new IllegalArgumentException("input contains non-hexadecimal characters");
        bytes[i] = (byte) v;

        return bytes;
    }

    /**
     * Returns a byte array containing hexadecimal values parsed from a range of the character array.
     */
    public byte[] parseHex(char[] chars, int fromIndex, int toIndex) {
        Objects.requireNonNull(chars, "chars");
        checkFromToIndex(fromIndex, toIndex, chars.length);
        CharBufferSeq cb = new CharBufferSeq(chars, fromIndex, toIndex);
        return parseHex(cb, 0, cb.length());
    }

    private static final class CharBufferSeq implements CharSequence {
        private final char[] chars;
        private final int offset;
        private final int length;

        CharBufferSeq(char[] chars, int from, int to) {
            this.chars = chars;
            this.offset = from;
            this.length = to - from;
        }

        @Override public int length() { return length; }
        @Override public char charAt(int index) { return chars[offset + index]; }
        @Override public CharSequence subSequence(int start, int end) {
            return new CharBufferSeq(chars, offset + start, offset + end);
        }
        @Override public String toString() { return new String(chars, offset, length); }
    }

    private static void checkLiteral(CharSequence string, int index, String literal) {
        assert index <= string.length() - literal.length() : "pre-checked invariant error";
        if (literal.isEmpty() ||
                (literal.length() == 1 && literal.charAt(0) == string.charAt(index))) {
            return;
        }
        for (int i = 0; i < literal.length(); i++) {
            if (string.charAt(index + i) != literal.charAt(i)) {
                throw new IllegalArgumentException(escapeNL("found: \"" +
                        string.subSequence(index, index + literal.length()) +
                        "\", expected: \"" + literal + "\", index: " + index +
                        " ch: " + (int)string.charAt(index + i)));
            }
        }
    }

    private static String escapeNL(String string) {
        return string.replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * Returns the hexadecimal character for the low 4 bits of the value considering it to be a byte.
     */
    public char toLowHexDigit(int value) {
        return (char)digits[value & 0xf];
    }

    /**
     * Returns the hexadecimal character for the high 4 bits of the value considering it to be a byte.
     */
    public char toHighHexDigit(int value) {
        return (char)digits[(value >> 4) & 0xf];
    }

    /**
     * Appends two hexadecimal characters for the byte value to the {@link Appendable}.
     */
    public <A extends Appendable> A toHexDigits(A out, byte value) {
        Objects.requireNonNull(out, "out");
        try {
            out.append(toHighHexDigit(value));
            out.append(toLowHexDigit(value));
            return out;
        } catch (IOException ioe) {
            throw new UncheckedIOException(ioe.getMessage(), ioe);
        }
    }

    /**
     * Returns the two hexadecimal characters for the {@code byte} value.
     */
    public String toHexDigits(byte value) {
        char[] rep = new char[2];
        rep[0] = toHighHexDigit(value);
        rep[1] = toLowHexDigit(value);
        return new String(rep);
    }

    /**
     * Returns the four hexadecimal characters for the {@code char} value.
     */
    public String toHexDigits(char value) {
        return toHexDigits((short)value);
    }

    /**
     * Returns the four hexadecimal characters for the {@code short} value.
     */
    public String toHexDigits(short value) {
        char[] rep = new char[4];
        rep[0] = toHighHexDigit((byte)(value >> 8));
        rep[1] = toLowHexDigit((byte)(value >> 8));
        rep[2] = toHighHexDigit((byte)value);
        rep[3] = toLowHexDigit((byte)value);
        return new String(rep);
    }

    /**
     * Returns the eight hexadecimal characters for the {@code int} value.
     */
    public String toHexDigits(int value) {
        char[] rep = new char[8];
        rep[0] = toHighHexDigit((byte)(value >> 24));
        rep[1] = toLowHexDigit((byte)(value >> 24));
        rep[2] = toHighHexDigit((byte)(value >> 16));
        rep[3] = toLowHexDigit((byte)(value >> 16));
        rep[4] = toHighHexDigit((byte)(value >> 8));
        rep[5] = toLowHexDigit((byte)(value >> 8));
        rep[6] = toHighHexDigit((byte)value);
        rep[7] = toLowHexDigit((byte)value);
        return new String(rep);
    }

    /**
     * Returns the sixteen hexadecimal characters for the {@code long} value.
     */
    public String toHexDigits(long value) {
        char[] rep = new char[16];
        rep[0] = toHighHexDigit((byte)(value >>> 56));
        rep[1] = toLowHexDigit((byte)(value >>> 56));
        rep[2] = toHighHexDigit((byte)(value >>> 48));
        rep[3] = toLowHexDigit((byte)(value >>> 48));
        rep[4] = toHighHexDigit((byte)(value >>> 40));
        rep[5] = toLowHexDigit((byte)(value >>> 40));
        rep[6] = toHighHexDigit((byte)(value >>> 32));
        rep[7] = toLowHexDigit((byte)(value >>> 32));
        rep[8] = toHighHexDigit((byte)(value >>> 24));
        rep[9] = toLowHexDigit((byte)(value >>> 24));
        rep[10] = toHighHexDigit((byte)(value >>> 16));
        rep[11] = toLowHexDigit((byte)(value >>> 16));
        rep[12] = toHighHexDigit((byte)(value >>> 8));
        rep[13] = toLowHexDigit((byte)(value >>> 8));
        rep[14] = toHighHexDigit((byte)value);
        rep[15] = toLowHexDigit((byte)value);
        return new String(rep);
    }

    /**
     * Returns up to sixteen hexadecimal characters for the {@code long} value.
     */
    public String toHexDigits(long value, int digits) {
        if (digits < 0 || digits > 16)
            throw new IllegalArgumentException("number of digits: " + digits);
        if (digits == 0)
            return "";
        char[] rep = new char[digits];
        for (int i = rep.length - 1; i >= 0; i--) {
            rep[i] = toLowHexDigit((byte)value);
            value = value >>> 4;
        }
        return new String(rep);
    }

    /**
     * Returns {@code true} if the character is a valid hexadecimal character or codepoint.
     */
    public static boolean isHexDigit(int ch) {
        return ((ch >>> 8) == 0 && DIGITS[ch] >= 0);
    }

    /**
     * Returns the value for the hexadecimal character or codepoint.
     */
    public static int fromHexDigit(int ch) {
        int value;
        if ((ch >>> 8) == 0 && (value = DIGITS[ch]) >= 0) {
            return value;
        }
        throw new NumberFormatException("not a hexadecimal digit: \"" + (char) ch + "\" = " + ch);
    }

    /**
     * Returns a value parsed from two hexadecimal characters in a string.
     */
    private static int fromHexDigits(CharSequence string, int index) {
        Objects.requireNonNull(string, "string");
        int high = fromHexDigit(string.charAt(index));
        int low = fromHexDigit(string.charAt(index + 1));
        return (high << 4) | low;
    }

    /**
     * Returns the {@code int} value parsed from a string of up to eight hexadecimal characters.
     */
    public static int fromHexDigits(CharSequence string) {
        Objects.requireNonNull(string, "string");
        return fromHexDigits(string, 0, string.length());
    }

    /**
     * Returns the {@code int} value parsed from a string range of up to eight hexadecimal characters.
     */
    public static int fromHexDigits(CharSequence string, int fromIndex, int toIndex) {
        Objects.requireNonNull(string, "string");
        checkFromToIndex(fromIndex, toIndex, string.length());
        int length = toIndex - fromIndex;
        if (length > 8)
            throw new IllegalArgumentException("string length greater than 8: " + length);
        int value = 0;
        for (int i = 0; i < length; i++) {
            value = (value << 4) + fromHexDigit(string.charAt(fromIndex + i));
        }
        return value;
    }

    /**
     * Returns the long value parsed from a string of up to sixteen hexadecimal characters.
     */
    public static long fromHexDigitsToLong(CharSequence string) {
        Objects.requireNonNull(string, "string");
        return fromHexDigitsToLong(string, 0, string.length());
    }

    /**
     * Returns the long value parsed from a string range of up to sixteen hexadecimal characters.
     */
    public static long fromHexDigitsToLong(CharSequence string, int fromIndex, int toIndex) {
        Objects.requireNonNull(string, "string");
        checkFromToIndex(fromIndex, toIndex, string.length());
        int length = toIndex - fromIndex;
        if (length > 16)
            throw new IllegalArgumentException("string length greater than 16: " + length);
        long value = 0L;
        for (int i = 0; i < length; i++) {
            value = (value << 4) + fromHexDigit(string.charAt(fromIndex + i));
        }
        return value;
    }

    private byte[] parseNoDelimiter(CharSequence string) {
        if ((string.length() & 1) != 0)
            throw new IllegalArgumentException("string length not even: " + string.length());

        byte[] bytes = new byte[string.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = fromHexDigits(string, i * 2);
            if (v < 0)
                throw new IllegalArgumentException("input contains non-hexadecimal characters");
            bytes[i] = (byte) v;
        }
        return bytes;
    }

    private static int checkMaxArraySize(long length) {
        if (length > Integer.MAX_VALUE)
            throw new OutOfMemoryError("String size " + length + " exceeds maximum " + Integer.MAX_VALUE);
        return (int)length;
    }

    private static void checkFromToIndex(int fromIndex, int toIndex, int length) {
        if (fromIndex < 0 || fromIndex > toIndex || toIndex > length)
            throw new IndexOutOfBoundsException("Range [" + fromIndex + ", " + toIndex + ") out of bounds for length " + length);
    }

    @Override
    public boolean equals(Object o) {
        if (o == this)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        HexFormat otherHex = (HexFormat) o;
        return Arrays.equals(digits, otherHex.digits) &&
                delimiter.equals(otherHex.delimiter) &&
                prefix.equals(otherHex.prefix) &&
                suffix.equals(otherHex.suffix);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(delimiter, prefix, suffix);
        result = 31 * result + Boolean.hashCode(isUpperCase());
        return result;
    }

    @Override
    public String toString() {
        return escapeNL("uppercase: " + isUpperCase() +
                ", delimiter: \"" + delimiter +
                "\", prefix: \"" + prefix +
                "\", suffix: \"" + suffix + "\"");
    }
}
