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

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Cuts the compiled CocoaTouch bindings down to what the consumers reach.
 *
 * <p>The bindings are generated from Apple's SDK headers by bro-gen, framework by framework, and
 * upstream MobiVM refreshes them for every iOS release. This fork ships only what the tsbMobile
 * consumers (RapidFX and RapidJ, module {@code swing-ios}) reach: Swing on a {@code UIView}, plus
 * Bluetooth, GPS, motion and the camera. Until 22.09.2026 that cut was made in the <em>sources</em>
 * — 134 package directories deleted, cross references removed by hand in 3,069 files — and every
 * upstream merge would have reopened those edits. Now the sources stay identical to upstream and
 * the cut happens here, on the class files, before the jar is packed:
 *
 * <ol>
 *   <li><b>Seeds</b> ({@code prune/seeds.txt}): the classes the consumers name, plus whole
 *       frameworks kept for later. A seed that does not exist is an error — upstream renamed or
 *       removed it, and that must be looked at, not skipped.</li>
 *   <li><b>Closure</b>: every class a kept class references — in its constant pool, in any
 *       descriptor or signature, in an annotation — is kept as well, transitively; inner classes
 *       come with their outer class.</li>
 *   <li><b>Exclusions</b> ({@code prune/exclude.txt}): packages that are never kept. A kept class
 *       loses every field and method whose descriptor, signature, exceptions or annotations name an
 *       excluded class (that is what removes the {@code @WeaklyLinked} iAd/MediaPlayer/SceneKit
 *       delegations from UIKit, the Metal members from CoreAnimation and CoreVideo, CoreMIDI from
 *       AudioToolbox). A kept class whose superclass or interface is excluded is an error.</li>
 *   <li>Rewritten classes get a fresh constant pool, the closure is recomputed, and the two steps
 *       repeat until nothing changes. Everything outside the closure is deleted.</li>
 *   <li>A <b>report</b> lists what was kept per package, which members were stripped, and which
 *       method <em>bodies</em> still mention an excluded class. RoboVM compiles those (Soot allows
 *       phantom references); they throw {@code NoClassDefFoundError} only if that path is executed,
 *       and the report is where a reviewer sees them.</li>
 * </ol>
 *
 * <p>Usage: {@code Prune <classes dir> <seeds.txt> <exclude.txt> <report.txt>}. Exit code 1 with a
 * message on a missing seed or an excluded supertype.
 */
public final class Prune {

    private static final String APPLE = "org/robovm/apple/";
    /**
     * A class name inside a descriptor or a generic signature. In a descriptor it ends with
     * {@code ;}, in a signature a parameterized type continues with {@code <} — the case that hid
     * {@code NSOrderedCollectionChange} (named only as a block type argument) on the first run.
     */
    private static final Pattern DESCRIPTOR = Pattern.compile("L(org/robovm/apple/[A-Za-z0-9_/$]+)[;<]");

    private final Path classes;
    private final List<String> seedTexts;
    private final List<Pattern> seeds;
    private final List<Pattern> excluded;

    /** All top-level and inner classes under {@code classes}, by internal name. */
    private final Map<String, Path> all = new HashMap<>();
    private final Map<String, List<String>> inner = new HashMap<>();
    private final Map<String, Set<String>> refs = new HashMap<>();

    private final Map<String, List<String>> stripped = new TreeMap<>();
    private final Map<String, Set<String>> phantoms = new TreeMap<>();
    private final Set<String> kept = new LinkedHashSet<>();

    Prune(Path classes, List<String> seedPatterns, List<String> excludePatterns) {
        this.classes = classes;
        this.seedTexts = new ArrayList<>(seedPatterns);
        this.seeds = seedPatterns.stream().map(Prune::glob).collect(Collectors.toList());
        this.excluded = excludePatterns.stream()
                .map(p -> glob(APPLE.replace('/', '.') + p + ".*")).collect(Collectors.toList());
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            System.err.println("usage: Prune <classes dir> <seeds.txt> <exclude.txt> <report.txt>");
            System.exit(2);
        }
        Prune prune = new Prune(Path.of(args[0]), entries(Path.of(args[1])), entries(Path.of(args[2])));
        try {
            String report = prune.run();
            Files.writeString(Path.of(args[3]), report, StandardCharsets.UTF_8);
            System.out.println(report.lines().limit(12).collect(Collectors.joining("\n")));
            System.out.println("  full report: " + args[3]);
        } catch (PruneException refused) {
            System.err.println("cocoatouch prune: " + refused.getMessage());
            System.exit(1);
        }
    }

    /** One pattern per line; {@code #} starts a comment. */
    static List<String> entries(Path file) throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.trim();
            if (!line.isEmpty()) out.add(line);
        }
        return out;
    }

    /** {@code org.robovm.apple.avfoundation.AVCapture*} → a pattern over dotted class names. */
    static Pattern glob(String pattern) {
        StringBuilder sb = new StringBuilder("^");
        for (char c : pattern.toCharArray()) {
            if (c == '*') sb.append(".*");
            else sb.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(sb.append('$').toString());
    }

    /** Runs the cut on the classes directory and returns the report. */
    String run() throws IOException {
        scan();
        List<String> roots = seedRoots();
        // Whatever the module keeps outside org.robovm.apple stays: today that is the one class
        // org.robovm.objc.$M, the marshaler table the compiler finds by name, not by reference —
        // measured: without it every app dies at launch with NoClassDefFoundError org/robovm/objc/$M.
        for (String c : all.keySet()) {
            if (!c.startsWith(APPLE)) roots.add(c);
        }
        closure(roots);

        // Strip, recompute, until the closure stops shrinking or growing.
        int round = 0;
        while (true) {
            round++;
            int removedMembers = stripExcludedMembers();
            Set<String> before = new LinkedHashSet<>(kept);
            kept.clear();
            closure(roots);
            if (kept.equals(before) && removedMembers == 0) break;
            if (round > 10) throw new PruneException("the cut does not converge after 10 rounds");
        }

        int deleted = 0;
        for (Map.Entry<String, Path> e : all.entrySet()) {
            if (!kept.contains(e.getKey())) {
                Files.delete(e.getValue());
                deleted++;
            }
        }
        collectPhantoms();
        return report(deleted);
    }

    // --- scanning ------------------------------------------------------------------------

    private void scan() throws IOException {
        try (Stream<Path> paths = Files.walk(classes)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".class")).collect(Collectors.toList())) {
                String name = classes.relativize(file).toString()
                        .replace(java.io.File.separatorChar, '/');
                name = name.substring(0, name.length() - ".class".length());
                all.put(name, file);
                int dollar = name.indexOf('$');
                if (dollar > 0) {
                    inner.computeIfAbsent(name.substring(0, dollar), k -> new ArrayList<>()).add(name);
                }
            }
        }
        for (Map.Entry<String, Path> e : all.entrySet()) {
            refs.put(e.getKey(), references(Files.readAllBytes(e.getValue())));
        }
    }

    /**
     * Every {@code org/robovm/apple} class a class file mentions: the constant pool's Class
     * entries and every descriptor inside any UTF8 entry — fields, methods, signatures,
     * annotations, bootstrap arguments. Read straight from the pool, JVMS 4.4.
     */
    static Set<String> references(byte[] bytes) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) throw new IOException("not a class file");
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        String[] utf8 = new String[count];
        int[] classNames = new int[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF();
                case 3, 4 -> in.readInt();
                case 5, 6 -> { in.readLong(); i++; }
                case 7 -> classNames[i] = in.readUnsignedShort();
                case 8, 16, 19, 20 -> in.readUnsignedShort();
                case 9, 10, 11, 12, 17, 18 -> { in.readUnsignedShort(); in.readUnsignedShort(); }
                case 15 -> { in.readUnsignedByte(); in.readUnsignedShort(); }
                default -> throw new IOException("unknown constant pool tag " + tag);
            }
        }
        for (int i = 1; i < count; i++) {
            if (classNames[i] != 0) {
                String name = utf8[classNames[i]];
                if (name != null && name.startsWith(APPLE)) out.add(name);
            }
        }
        for (String s : utf8) {
            if (s == null || !s.contains(APPLE)) continue;
            Matcher m = DESCRIPTOR.matcher(s);
            while (m.find()) out.add(m.group(1));
        }
        return out;
    }

    // --- the cut ---------------------------------------------------------------------------

    private List<String> seedRoots() {
        List<String> roots = new ArrayList<>();
        for (int i = 0; i < seeds.size(); i++) {
            Pattern seed = seeds.get(i);
            List<String> matched = all.keySet().stream()
                    .filter(n -> n.indexOf('$') < 0 && seed.matcher(n.replace('/', '.')).matches())
                    .sorted().collect(Collectors.toList());
            if (matched.isEmpty()) {
                throw new PruneException("seed " + seedTexts.get(i) + " matches no class — upstream"
                        + " renamed or removed it; fix prune/seeds.txt");
            }
            roots.addAll(matched);
        }
        return roots;
    }

    private void closure(List<String> roots) {
        Deque<String> todo = new ArrayDeque<>(roots);
        while (!todo.isEmpty()) {
            String c = todo.pop();
            if (kept.contains(c) || !all.containsKey(c)) continue;
            if (isExcluded(c)) continue;   // references into excluded packages are stripped, not followed
            kept.add(c);
            for (String r : refs.get(c)) {
                if (!kept.contains(r)) todo.push(r);
            }
            for (String i : inner.getOrDefault(c, List.of())) {
                if (!kept.contains(i)) todo.push(i);
            }
            int dollar = c.indexOf('$');
            if (dollar > 0) {
                String outer = c.substring(0, dollar);
                if (!kept.contains(outer)) todo.push(outer);
            }
        }
    }

    private boolean isExcluded(String internalName) {
        String dotted = internalName.replace('/', '.');
        for (Pattern p : excluded) {
            if (p.matcher(dotted).matches()) return true;
        }
        return false;
    }

    /**
     * Removes from every kept class the members that name an excluded class, rewrites the file
     * and refreshes its references. Returns how many members went.
     */
    private int stripExcludedMembers() throws IOException {
        int removed = 0;
        for (String name : new ArrayList<>(kept)) {
            Path file = all.get(name);
            byte[] bytes = Files.readAllBytes(file);
            if (!mentionsExcluded(refs.get(name))) continue;

            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);

            if (node.superName != null && isExcluded(node.superName)) {
                throw new PruneException(name.replace('/', '.') + " extends excluded "
                        + node.superName.replace('/', '.') + " — either keep that package in"
                        + " prune/exclude.txt or drop the class from prune/seeds.txt");
            }
            for (String itf : node.interfaces) {
                if (isExcluded(itf)) {
                    throw new PruneException(name.replace('/', '.') + " implements excluded "
                            + itf.replace('/', '.') + " — see prune/exclude.txt");
                }
            }

            List<String> gone = new ArrayList<>();
            for (FieldNode f : new ArrayList<>(node.fields)) {
                if (mentionsExcluded(f.desc, f.signature, f.visibleAnnotations, f.invisibleAnnotations)) {
                    node.fields.remove(f);
                    gone.add(f.name);
                }
            }
            for (MethodNode m : new ArrayList<>(node.methods)) {
                boolean hit = mentionsExcluded(m.desc, m.signature, m.visibleAnnotations, m.invisibleAnnotations)
                        || (m.exceptions != null && m.exceptions.stream().anyMatch(this::isExcluded))
                        || parameterAnnotationsMentionExcluded(m);
                if (hit) {
                    node.methods.remove(m);
                    gone.add(m.name + m.desc);
                }
            }
            // The InnerClasses attribute may list excluded nested types of other classes; harmless
            // for the verifier, but it would keep them in the closure. Drop those entries.
            boolean attributesChanged = node.innerClasses.removeIf(ic -> isExcluded(ic.name)
                    || (ic.outerName != null && isExcluded(ic.outerName)));
            if (node.nestMembers != null && node.nestMembers.removeIf(this::isExcluded)) attributesChanged = true;
            if (node.permittedSubclasses != null && node.permittedSubclasses.removeIf(this::isExcluded)) attributesChanged = true;

            if (gone.isEmpty() && !attributesChanged) continue;   // only phantoms in bodies: leave the file

            ClassWriter writer = new ClassWriter(0);
            node.accept(writer);
            byte[] rewritten = writer.toByteArray();
            Files.write(file, rewritten);
            refs.put(name, references(rewritten));
            if (!gone.isEmpty()) {
                stripped.computeIfAbsent(name.replace('/', '.'), k -> new ArrayList<>()).addAll(gone);
                removed += gone.size();
            }
        }
        return removed;
    }

    private boolean mentionsExcluded(Set<String> names) {
        for (String n : names) if (isExcluded(n)) return true;
        return false;
    }

    private boolean mentionsExcluded(String desc, String signature,
                                     List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        return descriptorMentionsExcluded(desc) || descriptorMentionsExcluded(signature)
                || annotationsMentionExcluded(visible) || annotationsMentionExcluded(invisible);
    }

    private boolean parameterAnnotationsMentionExcluded(MethodNode m) {
        for (List<AnnotationNode>[] lists : new List[][] {m.visibleParameterAnnotations, m.invisibleParameterAnnotations}) {
            if (lists == null) continue;
            for (List<AnnotationNode> l : lists) if (annotationsMentionExcluded(l)) return true;
        }
        return false;
    }

    private boolean annotationsMentionExcluded(List<AnnotationNode> annotations) {
        if (annotations == null) return false;
        for (AnnotationNode a : annotations) {
            if (descriptorMentionsExcluded(a.desc)) return true;
            if (a.values != null) {
                for (Object v : a.values) {
                    if (v instanceof org.objectweb.asm.Type t && t.getSort() == org.objectweb.asm.Type.OBJECT
                            && isExcluded(t.getInternalName())) return true;
                    if (v instanceof String s && descriptorMentionsExcluded(s)) return true;
                    if (v instanceof AnnotationNode nested && annotationsMentionExcluded(List.of(nested))) return true;
                    if (v instanceof List<?> list) {
                        for (Object o : list) {
                            if (o instanceof org.objectweb.asm.Type t && t.getSort() == org.objectweb.asm.Type.OBJECT
                                    && isExcluded(t.getInternalName())) return true;
                            if (o instanceof AnnotationNode nested && annotationsMentionExcluded(List.of(nested))) return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean descriptorMentionsExcluded(String descriptor) {
        if (descriptor == null) return false;
        Matcher m = DESCRIPTOR.matcher(descriptor);
        while (m.find()) if (isExcluded(m.group(1))) return true;
        return false;
    }

    /** After the cut: which kept classes still name an excluded class somewhere in a method body. */
    private void collectPhantoms() {
        for (String name : kept) {
            for (String r : refs.get(name)) {
                if (isExcluded(r)) {
                    phantoms.computeIfAbsent(name.replace('/', '.'), k -> new TreeSet<>()).add(r.replace('/', '.'));
                }
            }
        }
    }

    // --- report ----------------------------------------------------------------------------

    private String report(int deleted) {
        Map<String, int[]> perPackage = new TreeMap<>();
        for (String c : all.keySet()) {
            String pkg = c.startsWith(APPLE) ? c.substring(APPLE.length()).split("/")[0] : "(other)";
            int[] counts = perPackage.computeIfAbsent(pkg, k -> new int[2]);
            counts[1]++;
            if (kept.contains(c)) counts[0]++;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("cocoatouch prune report\n");
        sb.append(String.format("kept %d of %d classes, deleted %d, stripped %d members in %d classes, %d classes with phantom references%n",
                kept.size(), all.size(), deleted, stripped.values().stream().mapToInt(List::size).sum(),
                stripped.size(), phantoms.size()));
        sb.append("\nkept per package (kept / total; packages with 0 kept are omitted)\n");
        for (Map.Entry<String, int[]> e : perPackage.entrySet()) {
            if (e.getValue()[0] > 0) {
                sb.append(String.format("  %-22s %5d / %5d%n", e.getKey(), e.getValue()[0], e.getValue()[1]));
            }
        }
        sb.append("\nstripped members (class: member…)\n");
        for (Map.Entry<String, List<String>> e : stripped.entrySet()) {
            sb.append("  ").append(e.getKey()).append(": ").append(String.join(", ", e.getValue())).append('\n');
        }
        sb.append("\nphantom references left in method bodies (class -> excluded class); RoboVM compiles them,\n");
        sb.append("they throw NoClassDefFoundError only if that code path runs\n");
        for (Map.Entry<String, Set<String>> e : phantoms.entrySet()) {
            sb.append("  ").append(e.getKey()).append(" -> ").append(String.join(", ", e.getValue())).append('\n');
        }
        return sb.toString();
    }

    /** A cut that must not silently succeed. */
    static final class PruneException extends RuntimeException {
        PruneException(String message) {
            super(message);
        }
    }

    /** Test hook: the kept classes after {@link #run()}. */
    Set<String> kept() {
        return kept;
    }

    /** Test hook: stripped members by class. */
    Map<String, List<String>> stripped() {
        return new LinkedHashMap<>(stripped);
    }

}
