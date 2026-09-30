#!/usr/bin/env python3
# 杠① 逐跑红集清点（只读日志，不读盘上 surefire XML —— XML 会被后一跑覆盖）。
# 修的是 v1 尺的一处瞎判：聚合行在失败跑里前缀是 [ERROR] 而非 [INFO]，v1 只认 [INFO] 于是 reactor=None。
import re, sys, glob, hashlib

OUT = sys.argv[1] if len(sys.argv) > 1 else '.'
pat_total = re.compile(r'^\[(?:INFO|ERROR)\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)$')
pat_class = re.compile(r'^\[(?:INFO|ERROR)\] Tests run: .*<<< (?:FAILURE|ERROR)! - in (\S+)')
pat_red = re.compile(r'^\[ERROR\]   (\S+?\.\w+)(?::(\d+))? ')

rows = []
for log in sorted(glob.glob(OUT + '/bar1-?.log'), key=lambda p: int(re.search(r'bar1-(\d)\.log', p).group(1))):
    tag = re.search(r'bar1-(\d)\.log', log).group(1)
    total = None
    reds = []
    classes = []
    for line in open(log, encoding='utf-8', errors='replace'):
        m = pat_total.match(line.rstrip('\n'))
        if m:
            total = tuple(int(x) for x in m.groups())
        if 'Time elapsed' not in line:
            m2 = pat_red.match(line.rstrip('\n'))
            if m2:
                reds.append(m2.group(1))
            m3 = pat_class.match(line.rstrip('\n'))
            if m3:
                classes.append(m3.group(1))
    sha = hashlib.sha1(','.join(sorted(reds)).encode()).hexdigest()[:12]
    rows.append((tag, total, len(reds), sha, sorted(reds)))

for tag, total, n, sha, reds in rows:
    print('CENSUS|tag=%s total=%s n_red=%s set_sha=%s' % (tag, total, n, sha))
    for r in reds:
        print('   RED %s' % r)
if len(rows) >= 2:
    same = len({r[3] for r in rows}) == 1
    print('CENSUS|runs=%d identical_red_set=%s sets=%s' % (len(rows), same, sorted({r[3] for r in rows})))
    print('CENSUS|totals=%s' % [r[1] for r in rows])
