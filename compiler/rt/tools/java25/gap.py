#!/usr/bin/env python3
"""
What robovm-rt is still missing against Java 25, as a table that can be pasted into a roadmap.

Why this exists rather than a shell one-liner: the class-level difference was computed with `comm`
twice, and was wrong both times. `comm` requires its two inputs to be sorted identically, and a
locale that is not C sorts differently from the byte order everything else here uses. The first
failure reported 1070 missing classes instead of 432 — caught only because java.io.InputStreamReader
appeared in the list, which cannot be. The second happened while verifying the first. Set arithmetic
belongs somewhere that has sets.

The member-level difference comes from ApiDelta.java next door, which does its comparing inside Java
and was never affected by that. This script runs it and reads its CSV.

Usage: gap.py <robovm-rt.jar> [jdk25-home] [jdk8-rt.jar]
"""
import collections
import pathlib
import re
import subprocess
import sys
import tempfile
import zipfile

# Present in java.base 25, absent here, and no loss on a phone. Each one is a judgement, so each one
# is named: a number that hides its exclusions cannot be argued with.
IGNORABLE = {
    'java/lang/classfile': 'the Class-File API — nothing on a phone reads class files',
    'java/lang/invoke': 'method handle internals',
    'java/lang/foreign': 'the FFM API — we have Bro for native calls',
    'java/lang/constant': 'constant descriptors, which exist for the Class-File API',
    'java/lang/module': 'the module system, which an AOT image does not have',
}


def jdk_classes(jdk_home):
    """Public java.* classes of java.base, top-level only."""
    out = subprocess.run([str(jdk_home / 'bin/jimage'), 'list', str(jdk_home / 'lib/modules')],
                         capture_output=True, text=True, check=True).stdout
    found, module = set(), None
    for line in out.splitlines():
        m = re.match(r'^Module: (\S+)', line)
        if m:
            module = m.group(1)
            continue
        name = line.strip()
        if module == 'java.base' and name.endswith('.class'):
            name = name[:-len('.class')]
            if name.startswith('java/') and '$' not in name:
                found.add(name)
    return found


def jar_classes(jar):
    with zipfile.ZipFile(jar) as z:
        return {n[:-len('.class')] for n in z.namelist()
                if n.endswith('.class') and n.startswith('java/') and '$' not in n}


def duplicates(jar):
    """Entries whose names differ only by an iCloud ' 2' — the defect that invalidated two days."""
    with zipfile.ZipFile(jar) as z:
        return [n for n in z.namelist() if re.search(r' \d+\.class$', n)]


def members(jar, jdk_home, rt8):
    """ApiDelta's CSV as {class: count}, counting only what is genuinely absent.

    A member the class does not declare may still be reachable through a supertype, and ApiDelta's
    `inherited` column says which. Our Properties inherits get, put, size and twenty-eight more from
    Hashtable where OpenJDK's overrides them — nothing missing, but a declaration-level count calls
    all thirty-one absent and sends someone off to write them.
    """
    here = pathlib.Path(__file__).resolve().parent
    asm = next((p for p in pathlib.Path.home().glob('.gradle/caches/**/asm-9*.jar')
                if 'sources' not in p.name), None)
    if asm is None:
        print('  (no asm-9 jar in ~/.gradle/caches — skipping the member count)')
        return None
    csv = pathlib.Path(tempfile.mkdtemp()) / 'delta.csv'
    subprocess.run([str(jdk_home / 'bin/java'), '-cp', str(asm),
                    str(here.parent / 'ApiDelta.java'), str(jar), str(csv), str(rt8)],
                   capture_output=True, text=True, check=True)
    counts, inherited = collections.Counter(), 0
    for line in csv.read_text().splitlines()[1:]:
        f = line.split(',')
        if f[-1] == 'true':
            inherited += 1
        else:
            counts[f[0]] += 1
    return counts, inherited


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__.strip().splitlines()[-1])
    jar = pathlib.Path(sys.argv[1])
    jdk = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else pathlib.Path(
        subprocess.run(['/usr/libexec/java_home', '-v', '25'],
                       capture_output=True, text=True, check=True).stdout.strip())
    rt8 = pathlib.Path(sys.argv[3]) if len(sys.argv) > 3 else pathlib.Path(
        '/Library/Java/JavaVirtualMachines/liberica-jdk-8.jdk/Contents/Home/jre/lib/rt.jar')

    dupes = duplicates(jar)
    if dupes:
        print(f'!! {len(dupes)} duplicated classes in this jar — every number below is unreliable.')
        for d in dupes[:3]:
            print(f'     {d}')
        print('   Sweep the build tree: find . -name "* [0-9].*" -delete\n')

    base, have = jdk_classes(jdk), jar_classes(jar)
    missing = sorted(base - have)

    version = subprocess.run([str(jdk / 'bin/java'), '-version'],
                             capture_output=True, text=True).stderr.splitlines()[0]
    print(f'{jar.name}, against java.base of {version}\n')
    print('## Classes\n')
    print(f'| java.base public java.* classes | {len(base)} |')
    print('|---|---|')
    print(f'| present in robovm-rt | {len(base & have)} |')
    print(f'| **absent** | **{len(missing)}** |')

    rest = list(missing)
    for prefix, why in IGNORABLE.items():
        hit = [c for c in rest if c.startswith(prefix + '/')]
        if hit:
            rest = [c for c in rest if not c.startswith(prefix + '/')]
            print(f'| of those, `{prefix.replace("/", ".")}` | {len(hit)} · {why} |')
    print(f'| **of real interest** | **{len(rest)}** |')

    measured = members(jar, jdk, rt8)
    if measured is not None:
        counts, inherited = measured
        total = sum(counts.values())
        print(f'\n## Members of classes that do exist\n')
        print(f'| not declared on the class | {total + inherited} |')
        print('|---|---|')
        print(f'| of those, reached through a supertype | {inherited} |')
        print(f'| **genuinely absent** | **{total}** in {len(counts)} classes |')
        for label, pred in (
                ('`sun.*`', lambda c: c.startswith('sun/')),
                ('Unicode tables', lambda c: 'Unicode' in c),
                ('`java.lang.invoke`', lambda c: c.startswith('java/lang/invoke/'))):
            n = sum(v for k, v in counts.items() if pred(k))
            print(f'| of those, {label} | {n} |')
        relevant = sum(v for k, v in counts.items()
                       if k.startswith('java/') and 'Unicode' not in k
                       and not k.startswith('java/lang/invoke/'))
        print(f'| **relevant `java.*`** | **{relevant}** |')
        print('\nLargest single gaps:\n')
        for k, v in sorted(((k, v) for k, v in counts.items()
                            if k.startswith('java/') and 'Unicode' not in k
                            and not k.startswith('java/lang/invoke/')),
                           key=lambda kv: -kv[1])[:10]:
            print(f'  {v:4d}  {k.replace("/", ".")}')


if __name__ == '__main__':
    main()
