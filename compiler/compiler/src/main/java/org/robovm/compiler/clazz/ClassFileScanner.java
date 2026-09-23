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

import java.util.Arrays;

/**
 * Minimal class file header / constant pool scanner. It does not build any model of the class,
 * it only extracts the facts the compiler needs to decide whether the class file can be handed
 * to the Soot frontend as is:
 * <ul>
 *     <li>class file major/minor version</li>
 *     <li>presence of CONSTANT_Dynamic (condy, JVMS 4.4.10) entries which Soot 2.5 can't parse</li>
 *     <li>presence of CONSTANT_MethodHandle entries with a field reference kind (REF_getField..REF_putStatic)
 *     which Soot 2.5 fails to convert when they appear as bootstrap method arguments
 *     (this is what javac emits for records' ObjectMethods bootstrap)</li>
 * </ul>
 */
public final class ClassFileScanner {
    public static final int MAGIC = 0xCAFEBABE;

    public static final int CONSTANT_Utf8 = 1;
    public static final int CONSTANT_Integer = 3;
    public static final int CONSTANT_Float = 4;
    public static final int CONSTANT_Long = 5;
    public static final int CONSTANT_Double = 6;
    public static final int CONSTANT_Class = 7;
    public static final int CONSTANT_String = 8;
    public static final int CONSTANT_Fieldref = 9;
    public static final int CONSTANT_Methodref = 10;
    public static final int CONSTANT_InterfaceMethodref = 11;
    public static final int CONSTANT_NameAndType = 12;
    public static final int CONSTANT_MethodHandle = 15;
    public static final int CONSTANT_MethodType = 16;
    public static final int CONSTANT_Dynamic = 17;
    public static final int CONSTANT_InvokeDynamic = 18;
    public static final int CONSTANT_Module = 19;
    public static final int CONSTANT_Package = 20;

    public static final int REF_getField = 1;
    public static final int REF_putStatic = 4;

    /** Class file major version of Java 17 (JDK 17) class files. */
    public static final int JAVA_17_MAJOR_VERSION = 61;

    public final int minorVersion;
    public final int majorVersion;
    public final boolean hasConstantDynamic;
    public final boolean hasFieldMethodHandles;
    /** true if the constant pool references {@code java.lang.runtime.ObjectMethods} (record bootstrap). */
    public final boolean hasObjectMethodsReference;

    private static final byte[] OBJECT_METHODS_UTF8 = "java/lang/runtime/ObjectMethods".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private ClassFileScanner(int minorVersion, int majorVersion, boolean hasConstantDynamic,
                             boolean hasFieldMethodHandles, boolean hasObjectMethodsReference) {
        this.minorVersion = minorVersion;
        this.majorVersion = majorVersion;
        this.hasConstantDynamic = hasConstantDynamic;
        this.hasFieldMethodHandles = hasFieldMethodHandles;
        this.hasObjectMethodsReference = hasObjectMethodsReference;
    }

    /** Java language/platform version that corresponds to the class file major version (45 = 1.1 ... 61 = 17). */
    public int javaVersion() {
        return majorVersion - 44;
    }

    /**
     * Scans the class file bytes.
     * @return the extracted facts or {@code null} if the bytes do not look like a class file
     *         or the constant pool can't be walked (in that case Soot will produce its own error)
     */
    public static ClassFileScanner scan(byte[] b) {
        try {
            if (b.length < 10 || u4(b, 0) != MAGIC) {
                return null;
            }
            int minor = u2(b, 4);
            int major = u2(b, 6);
            int cpCount = u2(b, 8);
            boolean condy = false;
            boolean fieldHandles = false;
            boolean objectMethods = false;
            int off = 10;
            for (int i = 1; i < cpCount; i++) {
                int tag = b[off] & 0xff;
                switch (tag) {
                    case CONSTANT_Utf8: {
                        int len = u2(b, off + 1);
                        if (len == OBJECT_METHODS_UTF8.length && Arrays.equals(b, off + 3, off + 3 + len,
                                OBJECT_METHODS_UTF8, 0, OBJECT_METHODS_UTF8.length)) {
                            objectMethods = true;
                        }
                        off += 3 + len;
                        break;
                    }
                    case CONSTANT_Integer:
                    case CONSTANT_Float:
                    case CONSTANT_Fieldref:
                    case CONSTANT_Methodref:
                    case CONSTANT_InterfaceMethodref:
                    case CONSTANT_NameAndType:
                    case CONSTANT_InvokeDynamic:
                        off += 5;
                        break;
                    case CONSTANT_Dynamic:
                        condy = true;
                        off += 5;
                        break;
                    case CONSTANT_Long:
                    case CONSTANT_Double:
                        off += 9;
                        i++; // takes two constant pool slots
                        break;
                    case CONSTANT_Class:
                    case CONSTANT_String:
                    case CONSTANT_MethodType:
                    case CONSTANT_Module:
                    case CONSTANT_Package:
                        off += 3;
                        break;
                    case CONSTANT_MethodHandle: {
                        int kind = b[off + 1] & 0xff;
                        if (kind >= REF_getField && kind <= REF_putStatic) {
                            fieldHandles = true;
                        }
                        off += 4;
                        break;
                    }
                    default:
                        return null;
                }
            }
            return new ClassFileScanner(minor, major, condy, fieldHandles, objectMethods);
        } catch (ArrayIndexOutOfBoundsException e) {
            return null;
        }
    }

    private static int u2(byte[] b, int off) {
        return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
    }

    private static int u4(byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16) | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }
}
