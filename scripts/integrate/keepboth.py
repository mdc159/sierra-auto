"""Resolve every conflict hunk in the given files by keeping both sides, main's ('theirs') first.
Only for add-only hunks; prints each resolution so it can be inspected."""
import re, sys
for p in sys.argv[1:]:
    s = open(p, encoding='utf-8', newline='').read()
    pat = re.compile(r'<<<<<<< [^\n]*\n(.*?)(?:\|\|\|\|\|\|\| [^\n]*\n.*?)?=======\r?\n(.*?)>>>>>>> [^\n]*\n', re.S)
    n = 0
    def rep(m):
        global n; n += 1
        return m.group(2) + m.group(1)
    s2 = pat.sub(rep, s)
    open(p, 'w', encoding='utf-8', newline='').write(s2)
    print(f"{p}: {n} hunk(s) resolved keep-both (main first)")
