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
package org.robovm.compiler.clazz;

import org.apache.commons.io.IOUtils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.robovm.compiler.CompilerException;
import org.robovm.compiler.log.Logger;
import soot.ClassProvider;
import soot.ClassSource;
import soot.CoffiClassSource;
import soot.SourceLocator;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Soot {@link ClassProvider} that sits in front of Soot's own coffi provider. It reads the class
 * bytes from Soot's classpath exactly like {@code soot.CoffiClassProvider} does but inspects (and
 * if required rewrites) them before handing them to the coffi frontend:
 * <ul>
 *     <li>Records: javac emits {@code invokedynamic} call sites for {@code toString/hashCode/equals}
 *     bootstrapped by {@code java.lang.runtime.ObjectMethods.bootstrap} whose static arguments contain
 *     {@code REF_getField} method handles for every record component. Soot 2.5 can't convert field
 *     method handles and fails while jimplifying the method. The handles are not needed by RoboVM
 *     (component names + record class are sufficient, see {@code RecordObjectMethodsDelegate}) so they
 *     are stripped from the call site.</li>
 *     <li>Field method handles used as arguments of any other bootstrap method are replaced with a
 *     marker string so that the class can at least be parsed (such call sites are then turned into
 *     {@code NoSuchMethodError} by the invokedynamic plugin).</li>
 *     <li>CONSTANT_Dynamic entries can't be parsed by Soot at all: a clear error is reported instead of
 *     an obscure Soot failure.</li>
 *     <li>Class files newer than Java 17 (major version 61) produce a warning.</li>
 * </ul>
 */
public class RewritingClassProvider implements ClassProvider {
    static final String OBJECT_METHODS_BOOTSTRAP_OWNER = "java/lang/runtime/ObjectMethods";
    static final String STRIPPED_FIELD_HANDLE_MARKER = "robovm:stripped-field-method-handle";

    private final Logger logger;
    private final Set<String> warnedClasses = ConcurrentHashMap.newKeySet();

    public RewritingClassProvider(Logger logger) {
        this.logger = logger != null ? logger : Logger.NULL_LOGGER;
    }

    @Override
    public ClassSource find(String className) {
        SourceLocator.FoundFile file = SourceLocator.v().lookupInClassPath(className.replace('.', '/') + ".class");
        if (file == null) {
            return null;
        }
        byte[] bytes;
        try (InputStream in = file.inputStream()) {
            bytes = IOUtils.toByteArray(in);
        } catch (IOException e) {
            throw new CompilerException("Failed to read class file of " + className, e);
        }
        return new CoffiClassSource(className, new ByteArrayInputStream(prepare(className, bytes)));
    }

    /**
     * Inspects the class bytes and returns bytes Soot 2.5 can parse.
     */
    public byte[] prepare(String className, byte[] bytes) {
        ClassFileScanner info = ClassFileScanner.scan(bytes);
        if (info == null) {
            return bytes;
        }
        if (info.majorVersion > ClassFileScanner.JAVA_17_MAJOR_VERSION && warnedClasses.add(className)) {
            logger.warn("Class %s was compiled for Java %d (class file version %d). RoboVM supports class files "
                    + "up to Java 17 (version %d), compilation may fail or produce unsupported invokedynamic call sites.",
                    className, info.javaVersion(), info.majorVersion, ClassFileScanner.JAVA_17_MAJOR_VERSION);
        }
        if (info.hasConstantDynamic) {
            throw new CompilerException(String.format("Class %s uses CONSTANT_Dynamic (condy) constant pool entries "
                    + "which are not supported by RoboVM. Compile the sources with javac --release 17 "
                    + "(or Kotlin jvmTarget 17) and avoid compiler options that emit condy constants.", className));
        }
        if (info.hasFieldMethodHandles || info.hasObjectMethodsReference) {
            try {
                return stripFieldMethodHandles(bytes);
            } catch (IllegalArgumentException e) {
                // ASM refused the class (e.g. newer than ASM supports), let Soot report its own error
                logger.warn("Failed to pre-process class %s: %s", className, e.getMessage());
            }
        }
        return bytes;
    }

    private static boolean isFieldHandle(Object o) {
        if (!(o instanceof Handle)) {
            return false;
        }
        int tag = ((Handle) o).getTag();
        return tag >= Opcodes.H_GETFIELD && tag <= Opcodes.H_PUTSTATIC;
    }

    private static final String CALL_SITE_RETURN = ")Ljava/lang/invoke/CallSite;";

    /**
     * Soot 2.5 insists that a bootstrap method returns {@code java.lang.invoke.CallSite} while
     * newer bootstraps (e.g. {@code ObjectMethods.bootstrap}) are declared to return {@code Object}.
     * The declared return type is irrelevant for RoboVM (call sites are desugared at compile time)
     * so it is patched to what Soot expects.
     */
    private static Handle withCallSiteReturnType(Handle bsm) {
        String desc = bsm.getDesc();
        if (desc.endsWith(CALL_SITE_RETURN)) {
            return bsm;
        }
        String patched = desc.substring(0, desc.lastIndexOf(')')) + CALL_SITE_RETURN;
        return new Handle(bsm.getTag(), bsm.getOwner(), bsm.getName(), patched, bsm.isInterface());
    }

    static byte[] stripFieldMethodHandles(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitInvokeDynamicInsn(String name, String descriptor, Handle bsm, Object... bsmArgs) {
                        Object[] args = bsmArgs;
                        if (OBJECT_METHODS_BOOTSTRAP_OWNER.equals(bsm.getOwner()) && "bootstrap".equals(bsm.getName())) {
                            // ObjectMethods.bootstrap(lookup, name, type, recordClass, names, getters...):
                            // keep record class and component names, drop the getter handles
                            if (bsmArgs.length > 2) {
                                args = Arrays.copyOf(bsmArgs, 2);
                            }
                        } else {
                            for (int i = 0; i < bsmArgs.length; i++) {
                                if (isFieldHandle(bsmArgs[i])) {
                                    if (args == bsmArgs) {
                                        args = bsmArgs.clone();
                                    }
                                    args[i] = STRIPPED_FIELD_HANDLE_MARKER;
                                }
                            }
                        }
                        super.visitInvokeDynamicInsn(name, descriptor, withCallSiteReturnType(bsm), args);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }
}
