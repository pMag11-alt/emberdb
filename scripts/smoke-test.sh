#!/usr/bin/env bash
# Checks a running EmberDB server with the real redis-cli: replies must match what Redis would print.
#
#   scripts/smoke-test.sh [port]
set -euo pipefail
PORT="${1:-6379}"
failures=0

cli() {
  redis-cli -p "$PORT" "$@" 2>&1 || true # redis-cli exits non-zero on error replies, which some checks expect
}

check() {
  local expected="$1"
  shift
  local actual
  actual="$(cli "$@")"
  if [[ "$actual" == "$expected" ]]; then
    printf '  ok    %s\n' "$*"
  else
    printf '  FAIL  %s\n        expected: %q\n        actual:   %q\n' "$*" "$expected" "$actual"
    failures=$((failures + 1))
  fi
}

cli flushall >/dev/null

echo "strings"
check "PONG" ping
check "OK" set smoke:greeting "hello from ci"
check "hello from ci" get smoke:greeting
check "1" incr smoke:counter
check "11" incrby smoke:counter 10
check "1" expire smoke:counter 100
check "100" ttl smoke:counter

echo "lists, hashes, sets, sorted sets"
check "3" rpush smoke:list a b c
check $'a\nb\nc' lrange smoke:list 0 -1
check "2" hset smoke:user name Ada lang Java
check "Ada" hget smoke:user name
check "2" sadd smoke:tags java redis
check "1" sismember smoke:tags redis
check "2" zadd smoke:board 10 alice 20 bob
check $'bob\n20' zrevrange smoke:board 0 0 withscores
check "zset" type smoke:board

echo "errors"
check "WRONGTYPE Operation against a key holding the wrong kind of value" lpush smoke:greeting x
check "ERR value is not an integer or out of range" incr smoke:greeting

echo "transactions (MULTI/EXEC from stdin)"
actual="$(printf 'MULTI\nINCR smoke:tx\nINCR smoke:tx\nEXEC\n' | cli)"
if [[ "$actual" == $'OK\nQUEUED\nQUEUED\n1\n2' ]]; then
  echo "  ok    MULTI / INCR / INCR / EXEC"
else
  printf '  FAIL  MULTI/EXEC\n        actual: %q\n' "$actual"
  failures=$((failures + 1))
fi

echo "mass insertion (redis-cli --pipe)"
for i in $(seq 1 10000); do
  printf 'SET bulk:%d %d\r\n' "$i" "$i"
done | cli --pipe >/dev/null
count="$(cli keys 'bulk:*' | wc -l | tr -d ' ')"
if [[ "$count" == "10000" ]]; then
  echo "  ok    10000 keys inserted with --pipe"
else
  echo "  FAIL  expected 10000 bulk keys, found $count"
  failures=$((failures + 1))
fi

if ((failures > 0)); then
  echo "$failures check(s) failed"
  exit 1
fi
echo "All checks passed"
