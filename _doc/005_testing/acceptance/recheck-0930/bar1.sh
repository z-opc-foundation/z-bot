#!/bin/bash
# 杠①：全 reactor `mvn -o test` 串行三跑（前置一次 clean 预跑取真实总数）；跑在哪棵树由 identity.txt 现读，别信注释。
# 三把尺对着读：surefire 合计 == 跑后 XML 求和 == 文本 @Test − 注释里的字样。
# 日志落 ~/.cache（同机会话会扫空 /tmp），*.log 本身被根 .gitignore 挡着，不进库。
set -u
REPO=/Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot
OUT=$HOME/.cache/zbot-recheck-0930
export PATH=/usr/bin:/bin:/usr/sbin:/sbin:/usr/local/bin:/opt/homebrew/bin:$PATH
mkdir -p "$OUT"
cd "$REPO" || exit 1
# RealMcpHarness.python() 读 P21_PYTHON；系统 `python3` 是 3.9.6 且没装 mcp，
# 有 mcp 1.27.1 的是 /usr/local/bin/python3.14 —— 不设这个就会 9 条 MCP 用例外挂在"SDK 不可用"。
export P21_PYTHON=/usr/local/bin/python3.14

{
  date '+IDENTITY|start|%Y-%m-%d %H:%M:%S%z'
  echo "IDENTITY|head=$(git rev-parse HEAD)"
  echo "IDENTITY|status_lines=$(git status --porcelain | wc -l | tr -d ' ')"
  echo "IDENTITY|mvn=$(mvn -v 2>&1 | head -1)"
  echo "IDENTITY|java=$(java -version 2>&1 | head -1)"
  echo "IDENTITY|settings=$(md5 -q "$OUT/settings-offline.xml") -s $OUT/settings-offline.xml"
  echo "IDENTITY|P21_PYTHON=$P21_PYTHON -> $($P21_PYTHON -c 'import mcp; print(getattr(mcp, "mcpver", getattr(mcp, "__version__", "?")))' 2>&1 | tail -1)"
  echo "IDENTITY|ps=$(command -v ps || echo ABSENT)"
} | tee "$OUT/identity.txt"

run_one() {
  tag=$1
  goal=$2
  echo "############ RUN $tag ############"
  echo "tag=$tag head=$(git rev-parse HEAD)"
  date '+BAR1_START|%Y-%m-%d %H:%M:%S%z'
  rm -rf z-bot-core/target/surefire-reports z-bot-desktop-packager/target/surefire-reports
  mvn -o -s "$OUT/settings-offline.xml" $goal test > "$OUT/bar1-$tag.log" 2>&1
  rc=$?
  date '+BAR1_END|%Y-%m-%d %H:%M:%S%z'
  L="$OUT/bar1-$tag.log"
  echo "tag=$tag rc=$rc"
  # 失败跑的合计行前缀是 [ERROR] 不是 [INFO]：只认 [INFO] 的尺会把"有红"读成"没跑到"（本期踩过）
  grep -E '^\[(INFO|ERROR)\] Tests run: [0-9]+, Failures' "$L" | tail -1
  grep -E '^\[(INFO|ERROR)\] BUILD (SUCCESS|FAILURE)|Total time' "$L" | head -3
  echo "tag=$tag socket_hits=$(grep -cE 'BindException|Connection refused|SocketTimeout' "$L")"
  echo "tag=$tag fail_or_err_lines=$(grep -cE '<<< (FAILURE|ERROR)' "$L")"
  python3 - "$L" <<'PY'
import sys, glob, xml.etree.ElementTree as ET
log = sys.argv[1]
xmls = sorted(glob.glob('*/target/surefire-reports/*.xml'))
tot = 0
skipped = 0
for f in xmls:
    r = ET.parse(f).getroot()
    tot += int(r.get('tests') or 0)
    skipped += int(r.get('skipped') or 0)
reactor = None
for line in open(log, encoding='utf-8', errors='replace'):
    if (line.startswith('[INFO] ') or line.startswith('[ERROR] ')) and 'Tests run: ' in line \
            and 'Time elapsed' not in line and 'Failures' in line:
        reactor = int(line.split('Tests run:')[1].split(',')[0].strip())
files = glob.glob('z-bot-core/src/test/java/**/*.java', recursive=True)
text_hits = 0
comment_hits = []
for f in files:
    for n, line in enumerate(open(f, encoding='utf-8'), 1):
        c = line.count('@Test')
        if not c:
            continue
        text_hits += c
        s = line.strip()
        if s.startswith('*') or s.startswith('//') or s.startswith('/*'):
            comment_hits.append('%s:%d' % (f, n))
main_hits = 0
for f in glob.glob('z-bot-core/src/main/java/**/*.java', recursive=True):
    main_hits += open(f, encoding='utf-8').read().count('@Test')
print('RULER|tag=%s reactor=%s test_files=%d xml_files=%d xml_sum=%d xml_skipped=%d'
      % (log.split('bar1-')[-1].split('.')[0], reactor, len(files), len(xmls), tot, skipped))
print('RULER|text_at_test=%d comment_lines=%d minus=%s main_src_hits=%d'
      % (text_hits, len(comment_hits), text_hits - len(comment_hits), main_hits))
print('RULER|comment_hits=%s' % (','.join(comment_hits) if comment_hits else '无'))
PY
}

run_one 0 clean
run_one 1 ""
run_one 2 ""
run_one 3 ""
date '+ALL_DONE|%Y-%m-%d %H:%M:%S%z'
