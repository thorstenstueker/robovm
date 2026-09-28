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

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Lists the public/protected API members of the host JDK's java.base module (run it with the JDK
 * whose API level you want to compare against, e.g. JDK 17) that are missing in robovm-rt.jar.
 * Only classes that exist in robovm-rt are considered (packages RoboVM never shipped are not
 * interesting). Optionally an old rt.jar (JDK 8) can be given to mark members that are Java 9+
 * additions.
 *
 * A member the class does not declare is not necessarily missing: it may be inherited. The
 * `inherited` column says which, and only the rest are counted as genuinely absent — see
 * {@link #inheritedFrom}.
 *
 * Usage (needs asm on the classpath):
 *   java -cp asm-9.7.1.jar compiler/rt/tools/ApiDelta.java robovm-rt.jar out.csv [jdk8-rt.jar]
 *
 * Output CSV columns: class,kind,member,inJdk8,inherited
 */
public class ApiDelta {

    static final class ClassApi {
        final Set<String> members = new TreeSet<>();
        final List<String> supertypes = new ArrayList<>();
        int access;
    }

    /**
     * Class names seen more than once while reading a jar.
     *
     * <p>A jar can hold two entries at different paths whose class files declare the same name —
     * iCloud writes {@code Arrays 2.class} beside {@code Arrays.class} inside {@code target/}, and
     * the assembly packs both. This map is keyed by declared name, so the second silently replaces
     * the first and which one wins depends on zip order. That produced a day of work against
     * 59 methods reported missing from {@code java.util.Arrays} which have been there since Java 9.
     *
     * <p>Counted rather than tolerated: the run still finishes, but it says on the way out that
     * nothing it printed can be trusted.
     */
    static int duplicates;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: ApiDelta <robovm-rt.jar> <out.csv> [<jdk8-rt.jar>]");
            System.exit(1);
        }
        Map<String, ClassApi> rt = readJar(Paths.get(args[0]));
        Map<String, ClassApi> jdk8 = args.length > 2 ? readJar(Paths.get(args[2])) : Collections.emptyMap();
        Map<String, ClassApi> jdk = readJrt();

        int missingClasses = 0;
        int inherited = 0;
        int absent = 0;
        Map<String, Integer> perPackage = new TreeMap<>();
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Paths.get(args[1])))) {
            out.println("class,kind,member,inJdk8,inherited");
            for (Map.Entry<String, ClassApi> e : jdk.entrySet()) {
                String cls = e.getKey();
                ClassApi jdkApi = e.getValue();
                ClassApi rtApi = rt.get(cls);
                if (rtApi == null) {
                    missingClasses++;
                    continue;
                }
                ClassApi jdk8Api = jdk8.get(cls);
                for (String member : jdkApi.members) {
                    if (!rtApi.members.contains(member)) {
                        boolean inJdk8 = jdk8Api != null && jdk8Api.members.contains(member);
                        boolean byInheritance = inheritedFrom(rt, cls, member) != null;
                        String kind = member.startsWith("F:") ? "field" : "method";
                        out.println(cls + "," + kind + "," + member.substring(2) + "," + inJdk8
                                + "," + byInheritance);
                        if (byInheritance) {
                            inherited++;
                        } else {
                            absent++;
                            String pkg = cls.contains("/") ? cls.substring(0, cls.lastIndexOf('/')) : "";
                            perPackage.merge(pkg, 1, Integer::sum);
                        }
                    }
                }
            }
        }
        System.out.println("java.base classes: " + jdk.size() + ", in robovm-rt: " + (jdk.size() - missingClasses)
                + ", missing classes: " + missingClasses);
        System.out.println("members not declared on the class: " + (inherited + absent)
                + " — of those " + inherited + " are reached through a supertype, "
                + absent + " are genuinely absent");
        System.out.println("genuinely absent members per package:");
        perPackage.forEach((p, n) -> System.out.println("  " + n + "\t" + p));
        if (duplicates > 0) {
            System.out.println();
            System.out.println("!! " + duplicates + " class names occurred twice while reading."
                    + " Every number above is unreliable: sweep the tree for iCloud's"
                    + " \"Foo 2.class\" copies and build again.");
        }
    }

    /**
     * The supertype that already provides this member, or null if nothing does.
     *
     * <p>Comparing declared members alone overstates the gap badly. Our {@code Properties} — from
     * the libcore fork this runtime descends from — inherits {@code get}, {@code put}, {@code size}
     * and twenty-eight others from {@code Hashtable}, where OpenJDK's overrides every one of them
     * because it keeps its entries in a {@code ConcurrentHashMap} instead. Nothing is missing: a
     * caller reaches all of them. But a declaration-level diff calls all thirty-one absent, and
     * someone then sets out to write them.
     *
     * <p>Constructors are the exception the walk has to make: {@code <init>} is not inherited, so
     * {@code Properties(int)} really is missing while its thirty-one neighbours are not.
     *
     * <p>Static methods count as reached. {@code Sub.staticFromSuper()} compiles, and compiling is
     * what this tool is asked about.
     */
    static String inheritedFrom(Map<String, ClassApi> world, String cls, String member) {
        if (member.startsWith("M:<init>")) {
            return null;
        }
        return search(world, cls, member, new TreeSet<>(), true);
    }

    private static String search(Map<String, ClassApi> world, String cls, String member,
                                 Set<String> seen, boolean isStart) {
        if (cls == null || !seen.add(cls)) {
            return null;
        }
        ClassApi api = world.get(cls);
        if (api == null) {
            return null;
        }
        if (!isStart && api.members.contains(member)) {
            return cls;
        }
        for (String supertype : api.supertypes) {
            String found = search(world, supertype, member, seen, false);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    static Map<String, ClassApi> readJrt() throws IOException {
        FileSystem fs = FileSystems.getFileSystem(URI.create("jrt:/"));
        Path base = fs.getPath("/modules/java.base");
        Map<String, ClassApi> result = new TreeMap<>();
        try (Stream<Path> files = Files.walk(base)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                String name = base.relativize(p).toString();
                if (!name.endsWith(".class") || name.equals("module-info.class")) {
                    continue;
                }
                try (InputStream in = Files.newInputStream(p)) {
                    addClass(result, in.readAllBytes());
                }
            }
        }
        return result;
    }

    static Map<String, ClassApi> readJar(Path jar) throws IOException {
        Map<String, ClassApi> result = new TreeMap<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            for (Enumeration<JarEntry> en = jf.entries(); en.hasMoreElements(); ) {
                JarEntry entry = en.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream in = jf.getInputStream(entry)) {
                    addClass(result, in.readAllBytes());
                }
            }
        }
        return result;
    }

    static boolean isApi(int access) {
        return (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0
                && (access & Opcodes.ACC_SYNTHETIC) == 0;
    }

    /**
     * The same bytes, with the class file version lowered so ASM will read them.
     *
     * <p>Run against a JDK newer than the ASM on the class path, every class throws
     * {@code Unsupported class file major version 69} and the tool reports nothing at all — which
     * is exactly when it is needed, because comparing against the *newest* JDK is the whole point.
     * Measured 28.09.2026: ASM 9.8 refuses JDK 25.
     *
     * <p>Lowering the version is safe here and nowhere else. This reads names, descriptors and
     * access flags out of the constant pool and the member tables, and those have not changed
     * shape since Java 8. It never looks at code, and a class whose *bodies* need a newer reader
     * would still be listed correctly. Anything that executed these bytes would be lied to; this
     * tool does not.
     */
    static byte[] readableBy(byte[] bytes) {
        final int CLASS_FILE_8 = 52;
        if (bytes.length < 8) {
            return bytes;
        }
        int major = ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff);
        if (major <= CLASS_FILE_8) {
            return bytes;
        }
        byte[] lowered = bytes.clone();
        lowered[6] = (byte) (CLASS_FILE_8 >> 8);
        lowered[7] = (byte) CLASS_FILE_8;
        return lowered;
    }

    static void addClass(Map<String, ClassApi> result, byte[] bytes) {
        ClassReader reader = new ClassReader(readableBy(bytes));
        ClassApi classApi = new ClassApi();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            String className;
            boolean isApiClass;

            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                className = name;
                classApi.access = access;
                isApiClass = isApi(access);
                if (superName != null) {
                    classApi.supertypes.add(superName);
                }
                Collections.addAll(classApi.supertypes, interfaces);
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                if (isApiClass && isApi(access)) {
                    classApi.members.add("F:" + name);
                }
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (isApiClass && isApi(access) && !name.equals("<clinit>")) {
                    classApi.members.add("M:" + name + descriptor);
                }
                return null;
            }

            @Override
            public void visitEnd() {
                if (isApiClass && result.put(className, classApi) != null) {
                    duplicates++;
                }
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }
}
