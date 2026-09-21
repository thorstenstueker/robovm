/*
 * Copyright (C) 2026 RoboVM AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.robovm.rt.java17;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * On-device tests for the Java 9 - 17 API additions of the RoboVM runtime library.
 */
public class Java17ApiTest {

    @Test
    public void testStringAdditions() {
        assertEquals("ababab", "ab".repeat(3));
        assertEquals("", "ab".repeat(0));
        assertTrue("  \t".isBlank());
        assertFalse(" a ".isBlank());
        assertEquals("a b", "  a b  ".strip());
        assertEquals("a b  ", "  a b  ".stripLeading());
        assertEquals("  a b", "  a b  ".stripTrailing());
        assertEquals(List.of("a", "b", "c"), "a\nb\r\nc".lines().collect(Collectors.toList()));
        assertEquals("7-z", "%d-%s".formatted(7, "z"));
        assertEquals("  a\n  b\n", "a\nb".indent(2));
        assertEquals("a\nb\n", "  a\n  b".indent(-2));
        assertEquals("a\n b", "  a\n   b".stripIndent());
        assertEquals("a\tb\n", "a\\tb\\n".translateEscapes());
        assertEquals(3, (int) "abc".transform(String::length));
        assertEquals(0, CharSequence.compare("abc", new StringBuilder("abc")));
        assertTrue(CharSequence.compare("abc", "abd") < 0);
        assertTrue(new StringBuilder("a").compareTo(new StringBuilder("b")) < 0);
        assertEquals("😀", Character.toString(0x1F600));
    }

    @Test
    public void testMathAndNumbers() {
        assertEquals(5, Math.absExact(-5));
        try {
            Math.absExact(Integer.MIN_VALUE);
            fail();
        } catch (ArithmeticException expected) {
        }
        assertEquals(7.0, Math.fma(2.0, 3.0, 1.0), 0.0);
        assertEquals(7.0f, Math.fma(2.0f, 3.0f, 1.0f), 0.0f);
        assertEquals(123, Integer.parseInt("x123y", 1, 4, 10));
        assertEquals(-123L, Long.parseLong("x-123y", 1, 5, 10));
        assertEquals(255, Integer.parseUnsignedInt("ff", 0, 2, 16));
    }

    @Test
    public void testOptionalAdditions() {
        assertTrue(Optional.empty().isEmpty());
        assertEquals("x", Optional.of("x").orElseThrow());
        assertEquals("y", Optional.empty().or(() -> Optional.of("y")).get());
        assertEquals(1L, Optional.of("x").stream().count());
        StringBuilder sb = new StringBuilder();
        Optional.empty().ifPresentOrElse(v -> sb.append("present"), () -> sb.append("empty"));
        assertEquals("empty", sb.toString());
        try {
            Optional.empty().orElseThrow();
            fail();
        } catch (java.util.NoSuchElementException expected) {
        }
    }

    @Test
    public void testStreamAdditions() {
        assertEquals(List.of("a", "b"), Stream.of("a", "b").toList());
        try {
            Stream.of("a").toList().add("b");
            fail();
        } catch (UnsupportedOperationException expected) {
        }
        assertEquals(List.of(1, 2), Stream.of(1, 2, 3, 1).takeWhile(i -> i < 3).collect(Collectors.toList()));
        assertEquals(List.of(3, 1), Stream.of(1, 2, 3, 1).dropWhile(i -> i < 3).collect(Collectors.toList()));
        assertEquals(List.of(1, 2, 4, 8), Stream.iterate(1, i -> i < 10, i -> i * 2).collect(Collectors.toList()));
        assertEquals(0L, Stream.ofNullable(null).count());
        assertEquals(List.of(1, 1, 2, 2), Stream.of(1, 2).<Integer>mapMulti((i, c) -> { c.accept(i); c.accept(i); }).collect(Collectors.toList()));
        assertEquals(List.of(1, 2), IntStream.of(1, 2, 3, 1).takeWhile(i -> i < 3).boxed().collect(Collectors.toList()));
        assertEquals(List.of(1, 2, 4), IntStream.iterate(1, i -> i < 5, i -> i * 2).boxed().collect(Collectors.toList()));
        assertEquals(Set.of(2), Stream.of(1, 2, 3).collect(Collectors.filtering(i -> i == 2, Collectors.toUnmodifiableSet())));
        assertEquals(List.of(1, 2), Stream.of(List.of(1), List.of(2)).collect(Collectors.flatMapping(List::stream, Collectors.toUnmodifiableList())));
        assertEquals(Map.of("a", 1), Stream.of("a").collect(Collectors.toUnmodifiableMap(s -> s, s -> 1)));
        assertEquals(2.0, Stream.of(1, 2, 3).collect(Collectors.teeing(Collectors.summingInt(i -> i), Collectors.counting(), (sum, n) -> (double) sum / n)), 0.0);
        assertEquals(List.of(1), Stream.of(1, 2).filter(Predicate.not(i -> i == 2)).collect(Collectors.toList()));
        assertArrayEquals(new Integer[] { 1 }, List.of(1).toArray(Integer[]::new));
    }

    @Test
    public void testArraysAdditions() {
        assertEquals(-1, Arrays.mismatch(new int[] { 1, 2 }, new int[] { 1, 2 }));
        assertEquals(1, Arrays.mismatch(new int[] { 1, 2 }, new int[] { 1, 3 }));
        assertEquals(2, Arrays.mismatch(new int[] { 1, 2 }, new int[] { 1, 2, 3 }));
        assertTrue(Arrays.compare(new int[] { 1, 2 }, new int[] { 1, 3 }) < 0);
        assertTrue(Arrays.equals(new byte[] { 0, 1, 2 }, 1, 3, new byte[] { 1, 2 }, 0, 2));
        assertTrue(Arrays.compareUnsigned(new byte[] { (byte) 0xff }, new byte[] { 1 }) > 0);
        assertTrue(Arrays.compare(new String[] { "a" }, new String[] { "b" }) < 0);
        assertEquals(0, Arrays.mismatch(new Object[] { "a", "b" }, new Object[] { "x" }));
    }

    @Test
    public void testIoAdditions() throws Exception {
        InputStream in = new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8));
        assertEquals("hello", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        in = new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8));
        assertEquals("hel", new String(in.readNBytes(3), StandardCharsets.UTF_8));
        in = new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(5L, in.transferTo(out));
        assertEquals("hello", out.toString(StandardCharsets.UTF_8));
        assertEquals(-1, InputStream.nullInputStream().read());
        StringWriter sw = new StringWriter();
        assertEquals(3L, new StringReader("abc").transferTo(sw));
        assertEquals("abc", sw.toString());
        out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] { 65, 66 });
        assertEquals("AB", out.toString(StandardCharsets.US_ASCII));

        Path p = Files.createTempFile("java17", ".txt");
        Files.writeString(p, "hällo");
        assertEquals("hällo", Files.readString(p));
        Path p2 = Files.createTempFile("java17", ".txt");
        Files.writeString(p2, "hällö");
        assertEquals(5L, Files.mismatch(p, p2));
        assertEquals(p, Path.of(p.toString()));
        Files.delete(p);
        Files.delete(p2);
    }

    @Test
    public void testTimeAdditions() {
        LocalDate start = LocalDate.of(2020, 1, 30);
        assertEquals(List.of(LocalDate.of(2020, 1, 30), LocalDate.of(2020, 1, 31)),
                start.datesUntil(LocalDate.of(2020, 2, 1)).collect(Collectors.toList()));
        assertEquals(LocalDate.of(1970, 1, 2), LocalDate.ofInstant(Instant.ofEpochSecond(86400), ZoneOffset.UTC));
        assertEquals(86400L, LocalDate.of(1970, 1, 2).toEpochSecond(java.time.LocalTime.MIDNIGHT, ZoneOffset.UTC));
    }

    @Test
    public void testRegexAdditions() {
        assertTrue(Pattern.compile("a+").asMatchPredicate().test("aaa"));
        assertFalse(Pattern.compile("a+").asMatchPredicate().test("aab"));
        assertEquals(2L, Pattern.compile("a").matcher("banana").results().count());
        assertEquals("bXnXnX", Pattern.compile("a").matcher("banana").replaceAll(m -> "X"));
        assertEquals("bXnana", Pattern.compile("a").matcher("banana").replaceFirst(m -> "X"));
    }

    @Test
    public void testHexFormat() {
        HexFormat hex = HexFormat.of();
        assertEquals("00ff10", hex.formatHex(new byte[] { 0, (byte) 0xff, 0x10 }));
        assertArrayEquals(new byte[] { 0, (byte) 0xff, 0x10 }, hex.parseHex("00ff10"));
        assertEquals("0A:0B", HexFormat.ofDelimiter(":").withUpperCase().formatHex(new byte[] { 10, 11 }));
        assertEquals("000000ff", hex.toHexDigits(255));
        assertEquals(255, HexFormat.fromHexDigits("ff"));
        assertTrue(HexFormat.isHexDigit('f'));
        assertFalse(HexFormat.isHexDigit('g'));
    }

    @Test
    public void testRuntimeVersion() {
        Runtime.Version v = Runtime.version();
        assertEquals(17, v.feature());
        assertTrue(v.compareTo(Runtime.Version.parse("11.0.2")) > 0);
        assertEquals("17.0.0", v.toString());
        assertEquals(17, Runtime.Version.parse("17.0.1+9-LTS").feature());
    }
}
