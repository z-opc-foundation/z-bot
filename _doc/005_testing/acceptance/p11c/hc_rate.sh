#!/bin/bash
# 量 HttpChannelTest 单独跑的失败率：每轮 fresh JVM，记录 RC + 失败明细
cd /Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot || exit 9
N=${1:-15}
for i in $(seq 1 "$N"); do
  rm -rf z-bot-core/target/surefire-reports
  mvn -o test -Dtest=HttpChannelTest > /tmp/hc_run.log 2>&1
  rc=$?
  line=$(grep -h "Tests run:" z-bot-core/target/surefire-reports/*.txt 2>/dev/null | head -1)
  red=$(grep -oh "^[a-zA-Z]*\.[a-zA-Z]*  -- in\|<<< FAILURE\|<<< ERROR" z-bot-core/target/surefire-reports/*.txt 2>/dev/null | head -3 | tr '\n' '|')
  detail=$(grep -h "expected:<" z-bot-core/target/surefire-reports/*.txt 2>/dev/null | head -2 | tr '\n' '#')
  echo "run=$i rc=$rc | ${line} | ${red} ${detail}"
done
