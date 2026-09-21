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

import java.lang.reflect.RecordComponent;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * On-device tests for Java 9 - 17 language features (compiled with --release 17).
 */
public class Java17LanguageTest {

    // ---------------------------------------------------------------- records

    public record Point(int x, long y, double z, float f, boolean b, char c, byte by, short sh,
                        String label, int[] arr, List<String> list) {}

    public record Empty() {}

    public record Boxed(Integer i, Object o) {}

    @Test
    public void testRecordToString() {
        int[] arr = { 1 };
        Point p = new Point(1, 2L, 3.0, 4f, true, 'q', (byte) 5, (short) 6, "lbl", arr, List.of("a"));
        String s = p.toString();
        assertTrue(s, s.startsWith("Point[x=1, y=2, z=3.0, f=4.0, b=true, c=q, by=5, sh=6, label=lbl, arr=[I@"));
        assertTrue(s, s.endsWith(", list=[a]]"));
        assertEquals("Empty[]", new Empty().toString());
        assertEquals("Boxed[i=null, o=null]", new Boxed(null, null).toString());
    }

    @Test
    public void testRecordEquals() {
        int[] arr = { 1 };
        Point p1 = new Point(1, 2L, 3.0, 4f, true, 'q', (byte) 5, (short) 6, "lbl", arr, List.of("a"));
        Point p2 = new Point(1, 2L, 3.0, 4f, true, 'q', (byte) 5, (short) 6, "lbl", arr, List.of("a"));
        Point p3 = new Point(1, 2L, 3.0, 4f, true, 'q', (byte) 5, (short) 6, "lbl", new int[] { 1 }, List.of("a"));
        Point p4 = new Point(9, 2L, 3.0, 4f, true, 'q', (byte) 5, (short) 6, "lbl", arr, List.of("a"));
        assertEquals(p1, p1);
        assertEquals(p1, p2);
        assertEquals(p2, p1);
        assertNotEquals(p1, p3); // arrays compare by identity
        assertNotEquals(p1, p4);
        assertNotEquals(p1, null);
        assertNotEquals(p1, "Point");
        assertEquals(new Empty(), new Empty());
        assertEquals(new Boxed(null, null), new Boxed(null, null));
        assertEquals(new Boxed(1, "x"), new Boxed(1, "x"));
        assertNotEquals(new Boxed(1, "x"), new Boxed(1, "y"));
        // NaN and -0.0 follow Double.compare semantics like the JDK
        record D(double d) {}
        assertEquals(new D(Double.NaN), new D(Double.NaN));
        assertNotEquals(new D(0.0), new D(-0.0));
    }

    @Test
    public void testRecordHashCode() {
        int[] arr = { 1 };
        Point p1 = new Point(1, 2L, 3.0, 4f, true, 'q', (byte) 5, (short) 6, "lbl", arr, List.of("a"));
        Point p2 = new Point(1, 2L, 3.0, 4f, true, 'q', (byte) 5, (short) 6, "lbl", arr, List.of("a"));
        assertEquals(p1.hashCode(), p2.hashCode());
        assertEquals(0, new Empty().hashCode());
        // same algorithm as OpenJDK's ObjectMethods: h = 31 * h + hash(component)
        record IL(int i, long l, String s) {}
        int expected = 0;
        expected = 31 * expected + Integer.hashCode(7);
        expected = 31 * expected + Long.hashCode(8L);
        expected = 31 * expected + "s".hashCode();
        assertEquals(expected, new IL(7, 8L, "s").hashCode());
        assertEquals(31 * 31 * 7 + 31 * Long.hashCode(8L), new IL(7, 8L, null).hashCode());
    }

    @Test
    public void testRecordReflection() {
        assertTrue(Point.class.isRecord());
        assertTrue(Empty.class.isRecord());
        assertFalse(String.class.isRecord());
        assertFalse(Object.class.isRecord());
        assertNull(String.class.getRecordComponents());

        RecordComponent[] components = Point.class.getRecordComponents();
        assertNotNull(components);
        assertEquals(11, components.length);
        assertEquals("x", components[0].getName());
        assertEquals(int.class, components[0].getType());
        assertEquals("list", components[10].getName());
        assertEquals(List.class, components[10].getType());
        assertNotNull(components[0].getAccessor());
        assertSame(Point.class, components[0].getDeclaringRecord());
        assertEquals(0, Empty.class.getRecordComponents().length);
        assertSame(Record.class, Point.class.getSuperclass());
    }

    // ---------------------------------------------------------------- sealed

    public sealed interface Shape permits Circle, Square {}
    public record Circle(double r) implements Shape {}
    public static final class Square implements Shape {
        final int side;
        Square(int side) { this.side = side; }
    }

    @Test
    public void testSealedHierarchy() {
        Shape s1 = new Circle(1.0);
        Shape s2 = new Square(2);
        assertTrue(s1 instanceof Circle);
        assertTrue(s2 instanceof Square);
        assertEquals(1.0, ((Circle) s1).r(), 0.0);
        // RoboVM does not retain the PermittedSubclasses attribute
        assertFalse(Shape.class.isSealed());
    }

    // ---------------------------------------------------------------- nestmates

    static class Outer {
        private int secret = 41;
        private static String sstat = "S";
        private String hidden() { return "H" + secret; }
        private Outer() {}

        static class Nested {
            String peek(Outer o) { o.secret++; return o.hidden() + sstat + new Outer().secret; }
            private int mine = 7;
            private String own() { return "own" + mine; }
        }
        class Inner {
            String peek() { return hidden() + secret; }
        }
        String fromOuter() {
            Nested n = new Nested();
            n.mine = 8;
            Runnable r = () -> secret += 100;
            r.run();
            return n.own() + secret;
        }
    }

    @Test
    public void testNestmatePrivateAccess() {
        Outer o = new Outer();
        Outer.Nested n = new Outer.Nested();
        assertEquals("H42S41", n.peek(o));
        assertEquals("H4242", o.new Inner().peek());
        assertEquals("own8142", o.fromOuter());
    }

    @Test
    public void testNestReflection() {
        assertSame(Java17LanguageTest.class, Outer.class.getNestHost());
        assertSame(Java17LanguageTest.class, Outer.Nested.class.getNestHost());
        assertTrue(Outer.class.isNestmateOf(Outer.Inner.class));
        assertFalse(Outer.class.isNestmateOf(String.class));
    }

    // ---------------------------------------------------------------- private interface methods

    interface Greeter {
        default String greet(String who) { return prefix() + who + suffix(); }
        private String prefix() { return "Hi, "; }
        private static String suffix() { return "!"; }
        static String staticGreet() { return suffix(); }
        class Impl implements Greeter {
            String viaNested() { Greeter g = this; return g.prefix(); }
        }
    }

    @Test
    public void testPrivateInterfaceMethods() {
        Greeter.Impl g = new Greeter.Impl();
        assertEquals("Hi, Bob!", g.greet("Bob"));
        assertEquals("!", Greeter.staticGreet());
        assertEquals("Hi, ", g.viaNested());
    }

    // ---------------------------------------------------------------- syntax that is plain bytecode

    enum Color { RED, GREEN, BLUE }

    @Test
    public void testSwitchExpressions() {
        String k = "two";
        int n = switch (k) {
            case "one" -> 1;
            case "two" -> { int t = 1; yield t + 1; }
            default -> 0;
        };
        assertEquals(2, n);
        Color c = Color.GREEN;
        String cs = switch (c) {
            case RED -> "r";
            case GREEN -> "g";
            case BLUE -> "b";
        };
        assertEquals("g", cs);
    }

    @Test
    public void testTextBlockAndVar() {
        var s = """
            line1
              line2
            """;
        assertEquals("line1\n  line2\n", s);
    }

    @Test
    public void testPatternMatchingInstanceof() {
        Object o = "hello";
        String r = "no";
        if (o instanceof String str && str.length() > 2) {
            r = str.toUpperCase();
        }
        assertEquals("HELLO", r);
    }

    @Test
    public void testStringConcatWithConstants() {
        int i = 5; Object o = Color.RED; double d = 1.5;
        assertEquals("i=5 o=RED d=1.5!", "i=" + i + " o=" + o + " d=" + d + '!');
    }

    @Test
    public void testSystemProperties() {
        assertEquals("17", System.getProperty("java.specification.version"));
        assertEquals("61.0", System.getProperty("java.class.version"));
        assertEquals(17, Runtime.version().feature());
    }
}
