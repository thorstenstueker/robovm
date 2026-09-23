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
 * Usage (needs asm on the classpath):
 *   java -cp asm-9.7.1.jar compiler/rt/tools/ApiDelta.java robovm-rt.jar out.csv [jdk8-rt.jar]
 *
 * Output CSV columns: class,kind,member,inJdk8
 */
public class ApiDelta {

    static final class ClassApi {
        final Set<String> members = new TreeSet<>();
        int access;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: ApiDelta <robovm-rt.jar> <out.csv> [<jdk8-rt.jar>]");
            System.exit(1);
        }
        Map<String, ClassApi> rt = readJar(Paths.get(args[0]));
        Map<String, ClassApi> jdk8 = args.length > 2 ? readJar(Paths.get(args[2])) : Collections.emptyMap();
        Map<String, ClassApi> jdk = readJrt();

        int missingClasses = 0;
        Map<String, Integer> perPackage = new TreeMap<>();
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Paths.get(args[1])))) {
            out.println("class,kind,member,inJdk8");
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
                        String kind = member.startsWith("F:") ? "field" : "method";
                        out.println(cls + "," + kind + "," + member.substring(2) + "," + inJdk8);
                        String pkg = cls.contains("/") ? cls.substring(0, cls.lastIndexOf('/')) : "";
                        perPackage.merge(pkg, 1, Integer::sum);
                    }
                }
            }
        }
        System.out.println("java.base classes: " + jdk.size() + ", in robovm-rt: " + (jdk.size() - missingClasses)
                + ", missing classes: " + missingClasses);
        System.out.println("missing members per package:");
        perPackage.forEach((p, n) -> System.out.println("  " + n + "\t" + p));
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

    static void addClass(Map<String, ClassApi> result, byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassApi classApi = new ClassApi();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            String className;
            boolean isApiClass;

            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                className = name;
                classApi.access = access;
                isApiClass = isApi(access);
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
                if (isApiClass) {
                    result.put(className, classApi);
                }
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }
}
