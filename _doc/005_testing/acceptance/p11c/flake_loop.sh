#!/bin/zsh
# Intermittent HTTP-test flake data collection: N full-suite rounds, keep every log,
# and for each round record: rc, summary line, socket errors, and any REUSED ephemeral port.
set -u
cd /Users/zifang/workplace/ceo_workplace/z-opc-foundation/z-bot
ROUNDS=${1:-12}
OUT=/tmp/zbot_flake
mkdir -p $OUT
rm -f $OUT/r*.log $OUT/summary.txt
for i in $(seq 1 $ROUNDS); do
  rm -rf z-bot-core/target/surefire-reports
  mvn -o test > $OUT/r$i.log 2>&1
  rc=$?
  summ=$(grep -E "^\[(INFO|ERROR)\] Tests run: [0-9]+, Failures" $OUT/r$i.log | tail -1)
  sock=$(grep -cE "Unexpected end of file|SocketException" $OUT/r$i.log)
  # ephemeral ports bound by the channels during this run -> duplicates == port recycling
  dup=$(grep -oE "http://127\.0\.0\.1:[0-9]+/" $OUT/r$i.log | sort | uniq -d | wc -l | tr -d ' ')
  total=$(grep -oE "http://127\.0\.0\.1:[0-9]+/" $OUT/r$i.log | sort -u | wc -l | tr -d ' ')
  echo "r$i rc=$rc sockErr=$sock dupPorts=$dup/uniqPorts=$total  ${summ#*] }" >> $OUT/summary.txt
  if [[ $rc != 0 ]]; then
    grep -nE "Tests run:.*(Errors: [1-9]|Failures: [1-9])|Unexpected end of file|SocketException|FAIL" $OUT/r$i.log | head -20 >> $OUT/summary.txt
    cp -r z-bot-core/target/surefire-reports $OUT/reports_r$i 2>/dev/null
  fi
done
echo "=== $OUT/summary.txt ==="
cat $OUT/summary.txt
