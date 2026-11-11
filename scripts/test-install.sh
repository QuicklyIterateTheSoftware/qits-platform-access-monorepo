#!/usr/bin/env bash
# Offline test for install.sh, against stub servers on this machine.
#
# It never reaches a real idp or a real qits-artifacts. It starts one
# python3 http.server standing in for both (they are addressed through
# different env vars, so one stub can serve both), feeds install.sh a fake
# authorization code twice (the first try fails, like a mistyped code), and
# checks: the binary lands, the PATH hint appears, the Git credential helper
# is set up (replacing an old entry and leaving an unrelated one alone),
# docker-credential-qits is written beside the binary, and the access token
# never appears in what install.sh prints.
#
# Run it from anywhere; it finds install.sh next to this script's parent
# directory. Needs bash, curl, git and python3.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/.." && pwd)"
INSTALL_SCRIPT="$REPO_ROOT/install.sh"

WORKDIR="$(mktemp -d)"
cleanup() {
    if [[ -n "${SERVER_PID:-}" ]]; then
        kill "$SERVER_PID" >/dev/null 2>&1 || true
        wait "$SERVER_PID" 2>/dev/null || true
    fi
    rm -rf "$WORKDIR"
}
trap cleanup EXIT

PORT=18765
TEST_VERSION="2026.913.99"
TEST_TOKEN="FAKE-ACCESS-TOKEN-DO-NOT-PRINT"
STUB_MARKER="qits-test-stub-ran"

fail() {
    echo "FAIL: $*" >&2
    echo "--- install.sh stdout+stderr ---" >&2
    cat "$WORKDIR/output.log" >&2 2>/dev/null || true
    exit 1
}

# ---- the stub server ------------------------------------------------------

cat >"$WORKDIR/stub_server.py" <<PYEOF
import http.server
import sys

PORT = $PORT
VERSION = "$TEST_VERSION"
TOKEN = "$TEST_TOKEN"
STUB_MARKER = "$STUB_MARKER"

token_attempts = {"count": 0}

BINARY = (
    "#!/usr/bin/env bash\n"
    "echo " + STUB_MARKER + "\n"
    "exit 0\n"
).encode("utf-8")


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # keep the test output quiet

    def _send_json(self, status, body):
        payload = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def _authorized(self):
        return self.headers.get("Authorization") == "Bearer " + TOKEN

    def do_POST(self):
        if self.path == "/token":
            token_attempts["count"] += 1
            if token_attempts["count"] == 1:
                self._send_json(400, '{"error":"invalid_grant","error_description":"expired, used or mistyped"}')
            else:
                self._send_json(200, '{"access_token":"' + TOKEN + '","expires_in":600,'
                                      '"refresh_token":"unused","refresh_expires_in":86400}')
            return
        self._send_json(404, "{}")

    def do_GET(self):
        if self.path == "/artifacts/api/repositories/daemons/daemons":
            if not self._authorized():
                self._send_json(401, "{}")
                return
            self._send_json(200, '{"daemons":[{"name":"qits-platform-access-cli","latestVersion":"' + VERSION + '"}]}')
            return
        if self.path == "/artifacts/daemons/qits-platform-access-cli/" + VERSION:
            if not self._authorized():
                self._send_json(401, "{}")
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(BINARY)))
            self.end_headers()
            self.wfile.write(BINARY)
            return
        self._send_json(404, "{}")


http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
PYEOF

python3 "$WORKDIR/stub_server.py" &
SERVER_PID=$!

READY=0
for _ in $(seq 1 50); do
    # No -f: the stub answers 401 here without a token, which still proves it is up.
    if curl -sS -o /dev/null "http://127.0.0.1:$PORT/artifacts/api/repositories/daemons/daemons" 2>/dev/null; then
        READY=1
        break
    fi
    sleep 0.1
done
[[ $READY -eq 1 ]] || fail "the stub server never came up on port $PORT"

# ---- Git scratch config, pre-seeded to prove replace and leave-alone -------

GIT_CONFIG_GLOBAL="$WORKDIR/gitconfig"
export GIT_CONFIG_GLOBAL
touch "$GIT_CONFIG_GLOBAL"
OLD_QITS_PATH="/home/someone/old-checkout/target/qits"
git config --file "$GIT_CONFIG_GLOBAL" --add credential.https://githost.test.example.helper "!${OLD_QITS_PATH} git-credential"
git config --file "$GIT_CONFIG_GLOBAL" --add credential.https://github.com.helper "manager-core"

# ---- run install.sh --------------------------------------------------------

# A fresh, never-created directory: install.sh must create it, and it is
# never already on PATH, so the PATH hint is exercised.
INSTALL_DIR="$WORKDIR/bin"
mkdir -p "$WORKDIR/home"

set +e
printf 'fake-code\nfake-code\n' | env \
    HOME="$WORKDIR/home" \
    GIT_CONFIG_GLOBAL="$GIT_CONFIG_GLOBAL" \
    QITS_IDP_URL="http://127.0.0.1:$PORT" \
    QITS_ARTIFACTS_URL="http://127.0.0.1:$PORT" \
    QITS_GIT_HOST_URL="https://githost.test.example" \
    QITS_INSTALL_DIR="$INSTALL_DIR" \
    "$INSTALL_SCRIPT" >"$WORKDIR/output.log" 2>&1
STATUS=$?
set -e

echo "--- install.sh stdout+stderr ---"
cat "$WORKDIR/output.log"
echo "--- end ---"

[[ $STATUS -eq 0 ]] || fail "install.sh exited $STATUS"

# ---- assertions -------------------------------------------------------------

[[ -x "$INSTALL_DIR/qits" ]] || fail "the binary was not installed at $INSTALL_DIR/qits"

RUN_OUTPUT="$("$INSTALL_DIR/qits" --help)"
[[ "$RUN_OUTPUT" == "$STUB_MARKER" ]] || fail "the installed binary did not run as expected (got: $RUN_OUTPUT)"

grep -q "$TEST_TOKEN" "$WORKDIR/output.log" && fail "the access token appeared in install.sh's output"

grep -q "is not on PATH" "$WORKDIR/output.log" || fail "the PATH hint did not appear"
grep -qF "export PATH=\"\$PATH:$INSTALL_DIR\"" "$WORKDIR/output.log" || fail "the suggested PATH line did not appear"

# --replace-all with an empty value is Git's own idiom for "clear the helper
# chain up to here"; it leaves one blank entry ahead of the new one, exactly
# as `qits git-login --configure`'s own two commands do (GitSetup.commands).
HELPER_VALUES="$(git config --file "$GIT_CONFIG_GLOBAL" --get-all credential.https://githost.test.example.helper)"
EXPECTED_HELPER="!$INSTALL_DIR/qits git-credential"
LAST_HELPER="$(printf '%s\n' "$HELPER_VALUES" | tail -n1)"
[[ "$LAST_HELPER" == "$EXPECTED_HELPER" ]] || fail "the git credential helper is '$HELPER_VALUES', expected it to end with '$EXPECTED_HELPER'"
printf '%s\n' "$HELPER_VALUES" | grep -qF "$OLD_QITS_PATH" && fail "the old qits binary's helper entry was not replaced"

UNRELATED_HELPER="$(git config --file "$GIT_CONFIG_GLOBAL" --get credential.https://github.com.helper)"
[[ "$UNRELATED_HELPER" == "manager-core" ]] || fail "an unrelated credential helper was touched (now '$UNRELATED_HELPER')"

# The Docker credential helper: beside the binary, executable, running `qits docker-credential`.
SHIM="$INSTALL_DIR/docker-credential-qits"
[[ -x "$SHIM" ]] || fail "docker-credential-qits was not written executable at $SHIM"
EXPECTED_SHIM="$(printf '#!/bin/sh\nexec %s docker-credential "$@"\n' "'$INSTALL_DIR/qits'")"
[[ "$(cat "$SHIM")" == "$EXPECTED_SHIM" ]] || fail "docker-credential-qits is '$(cat "$SHIM")', expected '$EXPECTED_SHIM'"
SHIM_OUTPUT="$("$SHIM" get </dev/null)"
[[ "$SHIM_OUTPUT" == "$STUB_MARKER" ]] || fail "docker-credential-qits did not run the installed binary (got: $SHIM_OUTPUT)"

echo "PASS: install.sh installed the binary, showed the PATH hint, set up Git, wrote the Docker credential helper, and never printed the token."
