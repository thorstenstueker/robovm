#!/usr/bin/env python3
# Real member removals in compiler/cocoatouch/src/main/java against HEAD: ignores reordering and
# parameter names, which change between SDKs. Run from the repository root after generate.sh;
# a removed, non-deprecated member means Apple removed it — check the consumers.
import subprocess, re, collections, sys
root = "compiler/cocoatouch/src/main/java"
files = subprocess.run(["git", "diff", "--name-only", "--", root], capture_output=True, text=True).stdout.split()
pat = re.compile(r"^\s+(public|protected)\s.*[;{]\s*$")
def norm(l):
    l = l.strip()
    return re.sub(r"(\w+(?:<[^>]*>)?(?:\[\])?)\s+\w+(\s*[,)])", r"\1\2", l)   # drop parameter names
def members(text):
    return collections.Counter(norm(l) for l in text.splitlines() if pat.match(l) and "class " not in l)
gone = {}
for f in files:
    old = subprocess.run(["git", "show", "HEAD:" + f], capture_output=True, text=True).stdout
    d = members(old) - members(open(f).read())
    if d: gone[f.replace(root + "/org/robovm/apple/", "")] = list(d)
for f, ms in sorted(gone.items()):
    print(f)
    for m in ms: print("    ", m[:160])
print(len(gone), "files with real removals")
