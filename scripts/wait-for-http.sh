#!/usr/bin/env bash
# Waits for an HTTP endpoint to answer, then prints its status code and body.
# Usage: wait_for_http <url> <timeout-seconds> [expected-codes-regex]
set -uo pipefail

url="$1"
timeout_s="${2:-120}"
expected="${3:-^[23]}"
deadline=$((SECONDS + timeout_s))

while [ "$SECONDS" -lt "$deadline" ]; do
  body_file="$(mktemp)"
  # curl prints the code and exits non-zero on connection failure. Capture only the code and
  # treat any non-numeric or 000 result as "not up yet" rather than comparing the raw string.
  code="$(curl -s -o "$body_file" -w '%{http_code}' --max-time 5 "$url" 2>/dev/null)"
  case "$code" in
    '' | *[!0-9]*) code=000 ;;
  esac
  if [ "$code" != "000" ] && printf '%s' "$code" | grep -qE "$expected"; then
    echo "READY $code $url"
    cat "$body_file"
    rm -f "$body_file"
    exit 0
  fi
  rm -f "$body_file"
  sleep 2
done

echo "TIMEOUT after ${timeout_s}s: $url" >&2
exit 1
