# Cross-checks StitchDraw's DST/EXP/JEF/PES/VP3/XXX output against the independent pyembroidery reader.
# Usage: python3 validate_exports.py <pyembroidery-path>   (needs files/t.<ext> and files/plan.txt)
import sys
sys.path.insert(0, sys.argv[1] if len(sys.argv) > 1 else 'ref/pyembroidery')
import pyembroidery as pe
from pyembroidery.EmbConstant import *

plan = []
for line in open('files/plan.txt'):
    c, x, y = line.split(); plan.append((c, int(x), int(y)))
pts = [(x, y) for c, x, y in plan if c in ('STITCH', 'JUMP')]
cx = (min(p[0] for p in pts) + max(p[0] for p in pts)) // 2
cy = (min(p[1] for p in pts) + max(p[1] for p in pts)) // 2
nst = sum(1 for c, _, _ in plan if c == 'STITCH')
ncc = sum(1 for c, _, _ in plan if c == 'COLOR_CHANGE')

def cum(stitches, flip):
    """absolute positions of STITCH/JUMP in the file, relative to the centre; returns list in design (y-down) space"""
    out = []
    for x, y, f in stitches:
        c = f & COMMAND_MASK
        if c in (STITCH, JUMP):
            out.append((round(x), round(-y if flip else y), c))
    return out

ok = True
for ext, flip in (('dst', True), ('exp', True), ('jef', True), ('pes', False), ('vp3', False), ('xxx', True)):
    p = pe.read('files/t.' + ext)
    if p is None:
        print(ext, 'FAILED TO READ'); ok = False; continue
    got = cum(p.stitches, False)
    # pyembroidery reads in its own (y-down) space; compare to design space shifted by the centre
    src = [(x, y) for c, x, y in plan if (c == 'STITCH' or (c == 'JUMP' and ext != 'vp3'))]   # VP3 has no jump records
    exp = [(x - cx, y - cy) for x, y in src]
    gpts = [(x, y) for x, y, _ in got]
    # long moves are split: every expected point must appear in order
    gi = 0; missing = 0
    for e in exp:
        while gi < len(gpts) and gpts[gi] != e: gi += 1
        if gi >= len(gpts): missing += 1; gi = 0 if False else gi
        else: gi += 1
    n_st = sum(1 for _, _, c in got if c == STITCH)
    cc = sum(1 for s in p.stitches if s[2] & COMMAND_MASK == COLOR_CHANGE)
    print(f'{ext}: read ok, records={len(p.stitches)} stitches={n_st} (plan {nst}) colour changes={cc} (plan {ncc}) threads={len(p.threadlist)} missing_points={missing}')
    if missing: ok = False
print('ALL OK' if ok else 'PROBLEMS')
