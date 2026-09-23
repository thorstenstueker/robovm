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
package org.robovm.cocoatouch.prune;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The cut on a handful of synthetic classes: what a seed drags along, what an exclusion strips,
 * what stays out, and that cutting twice changes nothing.
 */
public class PruneTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private Path classes;

    private static final String A = "org/robovm/apple/uikit/A";            // seed
    private static final String A_INNER = "org/robovm/apple/uikit/A$Inner"; // comes with A
    private static final String B = "org/robovm/apple/foundation/B";       // field type of A
    private static final String C = "org/robovm/apple/foundation/C";       // referenced by nobody
    private static final String D = "org/robovm/apple/metal/D";            // excluded, named by A.draw
    private static final String E = "org/robovm/apple/coregraphics/E";     // referenced only from A.draw's body
    private static final String F = "org/robovm/apple/coreaudio/F";        // param of A.play, kept package

    @Before
    public void classes() throws IOException {
        classes = temp.newFolder("classes").toPath();
        write(A, "java/lang/Object", cw -> {
            cw.visitField(Opcodes.ACC_PUBLIC, "b", "L" + B + ";", null, null).visitEnd();
            // draw names the excluded D in its descriptor and goes; a body reference inside a
            // stripped method vanishes with it, so E is named from play, which stays.
            MethodVisitor draw = cw.visitMethod(Opcodes.ACC_PUBLIC, "draw", "(L" + D + ";)V", null, null);
            draw.visitCode();
            draw.visitInsn(Opcodes.RETURN);
            draw.visitMaxs(0, 2);
            draw.visitEnd();
            MethodVisitor play = cw.visitMethod(Opcodes.ACC_PUBLIC, "play", "(L" + F + ";)V", null, null);
            play.visitCode();
            play.visitTypeInsn(Opcodes.NEW, E);
            play.visitInsn(Opcodes.POP);
            play.visitInsn(Opcodes.RETURN);
            play.visitMaxs(1, 2);
            play.visitEnd();
            cw.visitInnerClass(A_INNER, A, "Inner", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        });
        write(A_INNER, "java/lang/Object", cw -> cw.visitInnerClass(A_INNER, A, "Inner", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC));
        for (String plain : new String[] {B, C, D, E, F}) {
            write(plain, "java/lang/Object", cw -> { });
        }
    }

    @Test
    public void aSeedDragsItsReferencesAlongAndNothingElse() throws IOException {
        Prune prune = new Prune(classes, List.of("org.robovm.apple.uikit.A"), List.of("metal*"));
        prune.run();

        Set<String> kept = prune.kept();
        assertTrue(kept.contains(A));
        assertTrue("inner classes come with their outer class", kept.contains(A_INNER));
        assertTrue("field type", kept.contains(B));
        assertTrue("parameter type of a kept method", kept.contains(F));
        assertTrue("named only in a method body of a kept method", kept.contains(E));
        assertFalse("referenced by nobody", kept.contains(C));
        assertFalse("excluded package", kept.contains(D));

        assertTrue(Files.exists(classes.resolve(A + ".class")));
        assertFalse(Files.exists(classes.resolve(C + ".class")));
        assertFalse(Files.exists(classes.resolve(D + ".class")));
    }

    @Test
    public void aMemberNamingAnExcludedClassIsStrippedAndReported() throws IOException {
        Prune prune = new Prune(classes, List.of("org.robovm.apple.uikit.A"), List.of("metal*"));
        String report = prune.run();

        assertEquals(List.of("draw(L" + D + ";)V"), prune.stripped().get(A.replace('/', '.')));
        assertTrue(report.contains("org.robovm.apple.uikit.A: draw(Lorg/robovm/apple/metal/D;)V"));
        assertFalse("the rewritten class no longer names metal at all",
                Prune.references(Files.readAllBytes(classes.resolve(A + ".class"))).contains(D));
        assertTrue("play survives — coreaudio is not excluded", report.contains("coreaudio"));
    }

    @Test
    public void cuttingTwiceChangesNothing() throws IOException {
        new Prune(classes, List.of("org.robovm.apple.uikit.A"), List.of("metal*")).run();
        byte[] once = Files.readAllBytes(classes.resolve(A + ".class"));
        Set<String> files = listing();

        Prune again = new Prune(classes, List.of("org.robovm.apple.uikit.A"), List.of("metal*"));
        again.run();

        assertEquals(files, listing());
        assertTrue(java.util.Arrays.equals(once, Files.readAllBytes(classes.resolve(A + ".class"))));
        assertTrue(again.stripped().isEmpty());
    }

    @Test
    public void aSeedThatDoesNotExistIsAnError() throws IOException {
        try {
            new Prune(classes, List.of("org.robovm.apple.uikit.Gone"), List.of()).run();
            fail("a missing seed must not pass silently");
        } catch (Prune.PruneException refused) {
            assertTrue(refused.getMessage(), refused.getMessage().contains("Gone"));
        }
    }

    @Test
    public void anExcludedSuperclassIsAnError() throws IOException {
        write("org/robovm/apple/uikit/Sub", D, cw -> { });
        try {
            new Prune(classes, List.of("org.robovm.apple.uikit.Sub"), List.of("metal*")).run();
            fail("a kept class cannot extend an excluded one");
        } catch (Prune.PruneException refused) {
            assertTrue(refused.getMessage(), refused.getMessage().contains("metal.D"));
        }
    }

    /** The compiler finds {@code org.robovm.objc.$M} by name; nothing references it, it must stay. */
    @Test
    public void classesOutsideAppleAlwaysStay() throws IOException {
        write("org/robovm/objc/$M", "java/lang/Object", cw -> { });
        Prune prune = new Prune(classes, List.of("org.robovm.apple.uikit.A"), List.of("metal*"));
        prune.run();
        assertTrue(prune.kept().contains("org/robovm/objc/$M"));
        assertTrue(Files.exists(classes.resolve("org/robovm/objc/$M.class")));
    }

    @Test
    public void globsMatchWholePackagesAndPrefixes() throws IOException {
        Prune prune = new Prune(classes,
                List.of("org.robovm.apple.foundation.*", "org.robovm.apple.coreaudio.F*"), List.of());
        prune.run();
        assertEquals(Set.of(B, C, F), prune.kept());   // no non-apple classes in this fixture
    }

    // --- helpers -------------------------------------------------------------------------

    private interface Body {
        void define(ClassWriter cw);
    }

    private void write(String name, String superName, Body body) throws IOException {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null);
        body.define(cw);
        cw.visitEnd();
        Path file = classes.resolve(name + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, cw.toByteArray());
    }

    private Set<String> listing() throws IOException {
        try (var paths = Files.walk(classes)) {
            return paths.filter(Files::isRegularFile)
                    .map(p -> classes.relativize(p).toString())
                    .collect(java.util.stream.Collectors.toSet());
        }
    }
}
