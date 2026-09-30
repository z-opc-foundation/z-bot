#!/usr/bin/env python3
"""复现 MemoryStoreContractTest.replaceAndRemoveHitExactlyOneEntry 的间歇红：
真 classpath + 真编译产物（run 3 留下的 target/），逐次起 JVM 跑整类，数失败。
不碰 mvn，不碰 ~/.zbot，只在 java.io.tmpdir 里建 TemporaryFolder。"""
import subprocess, os, re, sys, json, time

REPO = '/Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot'
OUT = os.path.expanduser('~/.cache/zbot-recheck-0930')
CP = ('%s/z-bot-core/target/classes:%s/z-bot-core/target/test-classes:%s'
      % (REPO, REPO, open(OUT + '/cp.txt').read().strip()))
JAVA = '/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home/bin/java'
N = int(sys.argv[1]) if len(sys.argv) > 1 else 150
CLS = 'com.zifang.z.bot.memory.MemoryStoreContractTest'

hits = {}
runs = 0
other = []
t0 = time.time()
for i in range(N):
    p = subprocess.run([JAVA, '-cp', CP, 'org.junit.runner.JUnitCore', CLS],
                       cwd=REPO, capture_output=True, text=True)
    out = p.stdout + p.stderr
    m = re.search(r'^Tests run: (\d+),\s*Failures: (\d+)', out, re.M)
    runs += 1
    names = re.findall(r'^\d+\) (\w+)\(', out, re.M)
    if not m:
        other.append(('NO_PARSE', i, out[-400:]))
        continue
    tr, fl = int(m.group(1)), int(m.group(2))
    if fl:
        for n in names:
            hits[n] = hits.get(n, 0) + 1
        if not names:
            other.append(('FL_but_no_names', i, out[-400:]))
    if tr != 22:
        other.append(('tests_run!=22', i, tr))
    if (i + 1) % 25 == 0:
        print('PROGRESS|%d runs, hits=%s, elapsed=%.0fs' % (i + 1, hits, time.time() - t0), flush=True)
print('LOOP_DONE|runs=%d hits=%s' % (runs, json.dumps(hits, ensure_ascii=False)))
print('LOOP_DONE|target_hit=%s rate=%s' % (hits.get('replaceAndRemoveHitExactlyOneEntry', 0),
                                           '%.4f' % (hits.get('replaceAndRemoveHitExactlyOneEntry', 0) / float(runs))))
for kind, i, detail in other[:5]:
    print('OTHER|%s|iter=%d|%s' % (kind, i, detail))
open(OUT + '/flake_loop.json', 'w').write(json.dumps(
    {'runs': runs, 'hits': hits, 'other': [str(x) for x in other], 'n': N}, ensure_ascii=False, indent=1))
