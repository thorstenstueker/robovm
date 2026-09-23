/*
 * Copyright (C) 2026 RoboVM AB
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/gpl-2.0.html>.
 */
package org.robovm.compiler.plugin.invokedynamic;

import org.apache.commons.io.IOUtils;
import org.junit.BeforeClass;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.robovm.compiler.ClassPathUtils;
import org.robovm.compiler.ModuleBuilder;
import org.robovm.compiler.clazz.ClassFileScanner;
import org.robovm.compiler.clazz.Clazz;
import org.robovm.compiler.clazz.RewritingClassProvider;
import org.robovm.compiler.config.Config;
import org.robovm.compiler.config.FakeHome;
import org.robovm.compiler.plugin.invokedynamic.record.RecordObjectMethodsDelegate;
import soot.Body;
import soot.SootMethod;
import soot.Unit;
import soot.ValueBox;
import soot.jimple.DynamicInvokeExpr;
import soot.jimple.InvokeExpr;
import soot.jimple.NewExpr;
import soot.jimple.StringConstant;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests that the {@code invokedynamic} call sites javac 17 emits for records
 * ({@code java.lang.runtime.ObjectMethods} bootstrap) are desugared by
 * {@link RecordObjectMethodsDelegate} and that the class file pre-processing in
 * {@link RewritingClassProvider} lets Soot 2.5 parse such classes.
 */
public class RecordObjectMethodsDelegateTest {

    static Config config;

    @BeforeClass
    public static void initialize() throws IOException {
        Config.Builder builder = new Config.Builder();
        for (File p : ClassPathUtils.getBcPaths()) {
            builder.addBootClasspathEntry(p);
        }
        for (String p : System.getProperty("java.class.path").split(File.pathSeparator)) {
            builder.addClasspathEntry(new File(p));
        }
        builder.home(new FakeHome());
        builder.mainClass("Main");
        File cacheDir = Files.createTempDirectory(RecordObjectMethodsDelegateTest.class.getSimpleName()).toFile();
        builder.cacheDir(cacheDir);
        config = builder.build();
    }

    // --- fixtures (compiled with --release 17 by the build) ---

    public record Point(int x, long y, double z, float f, boolean b, char c, byte by, short sh,
                        String label, int[] arr, List<String> list) {}

    public record Empty() {}

    public record Boxed(Integer i, Object o) {}

    public sealed interface Shape permits Circle, Square {}

    public record Circle(double r) implements Shape {}

    public static final class Square implements Shape {
        final int side;
        Square(int side) { this.side = side; }
    }

    private static Clazz toClazz(Class<?> cls) {
        return config.getClazzes().load(cls.getName().replace('.', '/'));
    }

    private static byte[] classBytes(Class<?> cls) throws IOException {
        try (InputStream in = cls.getClassLoader().getResourceAsStream(cls.getName().replace('.', '/') + ".class")) {
            assertNotNull(in);
            return IOUtils.toByteArray(in);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> values(Body body) {
        List<Object> result = new ArrayList<>();
        for (Unit unit : body.getUnits()) {
            for (ValueBox box : (List<ValueBox>) unit.getUseAndDefBoxes()) {
                result.add(box.getValue());
            }
        }
        return result;
    }

    private static boolean containsDynamicInvoke(Body body) {
        for (Object v : values(body)) {
            if (v instanceof DynamicInvokeExpr) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsNew(Body body, String className) {
        for (Object v : values(body)) {
            if (v instanceof NewExpr && ((NewExpr) v).getBaseType().getClassName().equals(className)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsInvoke(Body body, String className, String methodName) {
        for (Object v : values(body)) {
            if (v instanceof InvokeExpr) {
                InvokeExpr expr = (InvokeExpr) v;
                if (expr.getMethodRef().declaringClass().getName().equals(className)
                        && expr.getMethodRef().name().equals(methodName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsStringConstant(Body body, String value) {
        for (Object v : values(body)) {
            if (v instanceof StringConstant && ((StringConstant) v).value.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Body> desugar(Class<?> cls) throws IOException {
        Clazz clazz = toClazz(cls);
        assertNotNull(clazz);
        InvokeDynamicCompilerPlugin plugin = new InvokeDynamicCompilerPlugin();
        plugin.beforeClass(config, clazz, new ModuleBuilder());
        Map<String, Body> bodies = new HashMap<>();
        for (String name : new String[] { "toString", "hashCode", "equals" }) {
            SootMethod method = clazz.getSootClass().getMethodByName(name);
            Body body = method.retrieveActiveBody();
            assertFalse(name + " still contains invokedynamic", containsDynamicInvoke(body));
            assertFalse(name + " was replaced by the NoSuchMethodError fallback",
                    containsNew(body, "java.lang.NoSuchMethodError"));
            bodies.put(name, body);
        }
        return bodies;
    }

    @Test
    public void testRecordWithAllComponentKinds() throws Exception {
        Map<String, Body> bodies = desugar(Point.class);

        Body toString = bodies.get("toString");
        assertTrue(containsNew(toString, "java.lang.StringBuilder"));
        assertTrue(containsStringConstant(toString, "Point[x="));
        assertTrue(containsStringConstant(toString, ", label="));
        assertTrue(containsStringConstant(toString, "]"));
        assertTrue(containsInvoke(toString, "java.lang.StringBuilder", "append"));
        assertTrue(containsInvoke(toString, "java.lang.StringBuilder", "toString"));

        Body hashCode = bodies.get("hashCode");
        assertTrue(containsInvoke(hashCode, "java.lang.Long", "hashCode"));
        assertTrue(containsInvoke(hashCode, "java.lang.Double", "hashCode"));
        assertTrue(containsInvoke(hashCode, "java.lang.Float", "hashCode"));
        assertTrue(containsInvoke(hashCode, "java.lang.Boolean", "hashCode"));
        assertTrue(containsInvoke(hashCode, "java.util.Objects", "hashCode"));

        Body equals = bodies.get("equals");
        assertTrue(containsInvoke(equals, "java.lang.Float", "compare"));
        assertTrue(containsInvoke(equals, "java.lang.Double", "compare"));
        assertTrue(containsInvoke(equals, "java.util.Objects", "equals"));
    }

    @Test
    public void testEmptyRecord() throws Exception {
        Map<String, Body> bodies = desugar(Empty.class);
        assertTrue(containsStringConstant(bodies.get("toString"), "Empty[]"));
    }

    @Test
    public void testRecordWithReferenceComponents() throws Exception {
        Map<String, Body> bodies = desugar(Boxed.class);
        assertTrue(containsInvoke(bodies.get("equals"), "java.util.Objects", "equals"));
        assertTrue(containsInvoke(bodies.get("hashCode"), "java.util.Objects", "hashCode"));
        assertFalse(containsInvoke(bodies.get("hashCode"), "java.lang.Long", "hashCode"));
    }

    @Test
    public void testSealedHierarchyLoads() throws Exception {
        // PermittedSubclasses attribute must not break class loading
        desugar(Circle.class);
        Clazz square = toClazz(Square.class);
        assertNotNull(square);
        assertNotNull(square.getSootClass().getMethodByName("<init>"));
    }

    @Test
    public void testSimpleName() throws Exception {
        assertEquals("Point", RecordObjectMethodsDelegate.simpleName(toClazz(Point.class).getSootClass()));
    }

    @Test
    public void testClassFileScanner() throws Exception {
        ClassFileScanner point = ClassFileScanner.scan(classBytes(Point.class));
        assertNotNull(point);
        assertEquals(ClassFileScanner.JAVA_17_MAJOR_VERSION, point.majorVersion);
        assertEquals(17, point.javaVersion());
        assertTrue(point.hasFieldMethodHandles);
        assertTrue(point.hasObjectMethodsReference);
        assertFalse(point.hasConstantDynamic);

        ClassFileScanner empty = ClassFileScanner.scan(classBytes(Empty.class));
        assertNotNull(empty);
        assertFalse(empty.hasFieldMethodHandles);
        assertTrue(empty.hasObjectMethodsReference);

        ClassFileScanner square = ClassFileScanner.scan(classBytes(Square.class));
        assertNotNull(square);
        assertFalse(square.hasFieldMethodHandles);
        assertFalse(square.hasObjectMethodsReference);

        assertEquals(null, ClassFileScanner.scan(new byte[] { 1, 2, 3 }));
    }

    @Test
    public void testFieldHandlesAreStrippedFromBootstrapArguments() throws Exception {
        byte[] original = classBytes(Point.class);
        byte[] rewritten = new RewritingClassProvider(null).prepare(Point.class.getName(), original);

        final Map<String, Object[]> bsmArgs = new HashMap<>();
        final Map<String, Handle> bsms = new HashMap<>();
        new ClassReader(rewritten).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitInvokeDynamicInsn(String name, String descriptor, Handle bsm, Object... args) {
                        bsms.put(name, bsm);
                        bsmArgs.put(name, args);
                    }
                };
            }
        }, 0);

        for (String name : new String[] { "toString", "hashCode", "equals" }) {
            Object[] args = bsmArgs.get(name);
            assertNotNull(name, args);
            assertEquals(2, args.length);
            assertEquals("x;y;z;f;b;c;by;sh;label;arr;list", args[1]);
            assertTrue(bsms.get(name).getDesc().endsWith(")Ljava/lang/invoke/CallSite;"));
        }
    }
}
