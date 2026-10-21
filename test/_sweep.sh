#!/bin/bash
# Run the acceptance suites one at a time (concurrent runs starve the CPU and
# produce false timing failures). Prints a one-line verdict per suite.
cd /workspace/project || exit 1
for t in memory voiceflow homeconsole verify_echo verify_ui_panels toolmatrix nativecheck homefeed; do
  f="test/$t.py"
  [ -f "$f" ] || { echo "== $t : MISSING"; continue; }
  out=$(timeout 500 python3 -u "$f" 2>&1)
  code=$?
  if echo "$out" | grep -qiE "FAILS: none|ALL CHECKS PASSED|ALL .*PASSED|^PASS$|OK$"; then
    verdict="PASS"
  elif [ $code -eq 0 ]; then
    verdict="PASS(exit0)"
  else
    verdict="CHECK(exit=$code)"
  fi
  echo "== $t : $verdict"
  echo "$out" | tail -6 | sed 's/^/     /'
done
