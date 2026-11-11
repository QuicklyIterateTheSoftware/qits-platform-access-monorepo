#!/usr/bin/env bash
# Install the qits CLI to a stable path.
#
# What it does:
#   0. Signs you in through the idp, the same way `qits login` does, and holds
#      the access token in memory only.
#   1. Finds the latest released version in qits-artifacts and downloads it.
#   2. Installs it as `qits` in the install directory.
#   3. Checks whether the install directory is on PATH.
#   4. Points Git's credential helper at the installed binary, the way
#      `qits git-login --configure` does.
#   5. Writes docker-credential-qits next to the binary, the script Docker runs
#      for the platform's registry once `qits registry-login --configure` has
#      named it in Docker's config.
#
# The sign-in here differs from a device flow: the idp behind `qits login`
# shows a code on its own page after you sign in, and you paste it back. It
# never hands out a separate device code to poll for, so this script does not
# poll a token endpoint. It asks for the pasted code instead, exactly as
# `qits login` does (see LoginFlow.java in this repository).
#
# Environment overrides:
#   QITS_DOMAIN          your platform's public domain, used to derive the idp URL when
#                        QITS_IDP_URL is not set (https://idp.qits.$QITS_DOMAIN/idp). With
#                        neither QITS_DOMAIN nor QITS_IDP_URL set, this script refuses (exit 2):
#                        there is no built-in default domain.
#   QITS_IDP_URL        the idp's base URL. Overrides QITS_DOMAIN's derived address.
#   QITS_ARTIFACTS_URL   the artifacts store's base URL (default: derived from
#                        the idp URL, swapping the idp host label for `registry`)
#   QITS_GIT_HOST_URL    the git host's base URL (default: derived from the idp
#                        URL, swapping the idp host label for `githost`)
#   QITS_INSTALL_DIR     where to install (default ~/.local/bin)
#
# This script never prints or stores the access token. It needs bash, curl,
# and one of jq or python3 to read JSON answers.
set -euo pipefail
set +x

DAEMON_NAME="qits-platform-access-cli"
CLIENT_ID="qits-cli"
CODE_TTL_SECONDS=300

if [[ -n "${QITS_IDP_URL:-}" ]]; then
    IDP_URL="$QITS_IDP_URL"
elif [[ -n "${QITS_DOMAIN:-}" ]]; then
    IDP_URL="https://idp.qits.${QITS_DOMAIN}/idp"
else
    echo "Set QITS_DOMAIN (your platform's public domain) or QITS_IDP_URL." >&2
    exit 2
fi
INSTALL_DIR="${QITS_INSTALL_DIR:-$HOME/.local/bin}"

# ---- helpers ------------------------------------------------------------

strip_trailing_slash() {
    local value="$1"
    while [[ "$value" == */ ]]; do
        value="${value%/}"
    done
    printf '%s' "$value"
}

need_cmd() {
    command -v "$1" >/dev/null 2>&1
}

# Rewrites an idp URL's host label (`idp`) to another app's label, keeping the
# scheme and any port. Mirrors PlatformUrls.resolve in the CLI's own code.
# Prints nothing when the idp host does not start with "idp.".
derive_url() {
    local app="$1" scheme rest host_port host port
    scheme="${IDP_URL%%://*}"
    rest="${IDP_URL#*://}"
    host_port="${rest%%/*}"
    host="${host_port%%:*}"
    if [[ "$host_port" == *:* ]]; then
        port=":${host_port#*:}"
    else
        port=""
    fi
    case "$host" in
        idp.*)
            printf '%s://%s.%s%s' "$scheme" "$app" "${host#idp.}" "$port"
            ;;
        *)
            printf ''
            ;;
    esac
}

# Lower-cases scheme and host and drops a default port. Mirrors GitOrigin.normalize.
normalize_origin() {
    local raw="$1" scheme rest host_port host port
    scheme="${raw%%://*}"
    rest="${raw#*://}"
    host_port="${rest%%/*}"
    host="${host_port%%:*}"
    if [[ "$host_port" == *:* ]]; then
        port="${host_port#*:}"
    else
        port=""
    fi
    scheme="$(printf '%s' "$scheme" | tr 'A-Z' 'a-z')"
    host="$(printf '%s' "$host" | tr 'A-Z' 'a-z')"
    if [[ -n "$port" ]] && { [[ "$scheme" == "http" && "$port" == "80" ]] || [[ "$scheme" == "https" && "$port" == "443" ]]; }; then
        port=""
    fi
    if [[ -n "$port" ]]; then
        printf '%s://%s:%s' "$scheme" "$host" "$port"
    else
        printf '%s://%s' "$scheme" "$host"
    fi
}

IDP_URL="$(strip_trailing_slash "$IDP_URL")"

if ! need_cmd curl; then
    echo "This script needs curl. Install it and run again." >&2
    exit 1
fi

JSON_TOOL=""
if need_cmd jq; then
    JSON_TOOL="jq"
elif need_cmd python3; then
    JSON_TOOL="python3"
else
    echo "This script needs jq or python3 to read JSON answers. Install one and run again." >&2
    exit 1
fi

CRYPTO_TOOL=""
if need_cmd openssl; then
    CRYPTO_TOOL="openssl"
elif need_cmd python3; then
    CRYPTO_TOOL="python3"
else
    echo "This script needs openssl or python3 for the sign-in. Install one and run again." >&2
    exit 1
fi

# Reads a top-level JSON field from stdin. Prints nothing for a missing or null field.
json_field() {
    local field="$1"
    if [[ "$JSON_TOOL" == "jq" ]]; then
        jq -r --arg f "$field" '.[$f] // empty' 2>/dev/null
    else
        python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
except ValueError:
    sys.exit(0)
value = data.get(sys.argv[1]) if isinstance(data, dict) else None
if value is not None:
    print(value)
' "$field"
    fi
}

# Reads the daemon list body from stdin and prints DAEMON_NAME's latestVersion.
latest_version_of() {
    local name="$1"
    if [[ "$JSON_TOOL" == "jq" ]]; then
        jq -r --arg n "$name" '(.daemons // [])[] | select(.name == $n) | .latestVersion' 2>/dev/null | head -n1
    else
        python3 -c '
import json, sys
try:
    data = json.load(sys.stdin)
except ValueError:
    sys.exit(0)
name = sys.argv[1]
for entry in data.get("daemons", []) or []:
    if entry.get("name") == name:
        version = entry.get("latestVersion")
        if version:
            print(version)
        break
' "$name"
    fi
}

urlencode() {
    local value="$1"
    if [[ "$JSON_TOOL" == "jq" ]]; then
        printf '%s' "$value" | jq -sRr @uri
    else
        python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$value"
    fi
}

random_verifier() {
    if [[ "$CRYPTO_TOOL" == "openssl" ]]; then
        openssl rand 48 | base64 | tr '+/' '-_' | tr -d '=\n'
    else
        python3 -c 'import secrets, base64; print(base64.urlsafe_b64encode(secrets.token_bytes(48)).decode().rstrip("="))'
    fi
}

challenge_of() {
    local verifier="$1"
    if [[ "$CRYPTO_TOOL" == "openssl" ]]; then
        printf '%s' "$verifier" | openssl dgst -sha256 -binary | base64 | tr '+/' '-_' | tr -d '=\n'
    else
        python3 -c '
import sys, hashlib, base64
digest = hashlib.sha256(sys.argv[1].encode("ascii")).digest()
print(base64.urlsafe_b64encode(digest).decode().rstrip("="))
' "$verifier"
    fi
}

# ---- 0. sign in ----------------------------------------------------------

ARTIFACTS_URL="${QITS_ARTIFACTS_URL:-$(derive_url registry)}"
ARTIFACTS_URL="$(strip_trailing_slash "$ARTIFACTS_URL")"
if [[ -z "$ARTIFACTS_URL" ]]; then
    echo "Cannot work out the artifacts host from $IDP_URL. Set QITS_ARTIFACTS_URL and run again." >&2
    exit 1
fi

REDIRECT_URI="${IDP_URL}/connect/cli"
VERIFIER="$(random_verifier)"
CHALLENGE="$(challenge_of "$VERIFIER")"
AUTHORIZE_URL="${IDP_URL}/authorize?response_type=code&client_id=${CLIENT_ID}&redirect_uri=$(urlencode "$REDIRECT_URI")&code_challenge=${CHALLENGE}&code_challenge_method=S256"

echo "Open this address in your browser and sign in:"
echo
echo "  $AUTHORIZE_URL"
echo
echo "The page then shows a code."

ACCESS_TOKEN=""
DEADLINE=""
while true; do
    if ! read -r -p "Paste the code: " CODE; then
        echo
        echo "No code given. Nothing was installed." >&2
        exit 1
    fi
    # Trim surrounding whitespace without a subshell that could echo it.
    CODE="${CODE#"${CODE%%[![:space:]]*}"}"
    CODE="${CODE%"${CODE##*[![:space:]]}"}"
    if [[ -z "$CODE" ]]; then
        echo "No code given. Nothing was installed." >&2
        exit 1
    fi
    if [[ -z "$DEADLINE" ]]; then
        DEADLINE=$(($(date +%s) + CODE_TTL_SECONDS))
    fi

    set +e
    RESPONSE="$(curl -sS -X POST "${IDP_URL}/token" \
        -H "Content-Type: application/x-www-form-urlencoded" \
        -H "Accept: application/json" \
        --data-urlencode "grant_type=authorization_code" \
        --data-urlencode "client_id=${CLIENT_ID}" \
        --data-urlencode "code=${CODE}" \
        --data-urlencode "redirect_uri=${REDIRECT_URI}" \
        --data-urlencode "code_verifier=${VERIFIER}" \
        -w $'\n%{http_code}')"
    CURL_STATUS=$?
    set -e
    if [[ $CURL_STATUS -ne 0 ]]; then
        echo "Cannot reach ${IDP_URL}/token (curl exit code $CURL_STATUS)." >&2
        if [[ $(date +%s) -lt "$DEADLINE" ]]; then
            echo "Paste the code again to try again." >&2
            continue
        fi
        echo "Run this script again." >&2
        exit 1
    fi
    HTTP_STATUS="${RESPONSE##*$'\n'}"
    TOKEN_BODY="${RESPONSE%$'\n'*}"

    if [[ "$HTTP_STATUS" -ge 200 && "$HTTP_STATUS" -lt 300 ]]; then
        ACCESS_TOKEN="$(json_field access_token <<<"$TOKEN_BODY")"
        if [[ -z "$ACCESS_TOKEN" ]]; then
            echo "The idp's token answer is missing a field. Nothing was installed." >&2
            exit 1
        fi
        break
    fi

    ERROR="$(json_field error <<<"$TOKEN_BODY")"
    DESCRIPTION="$(json_field error_description <<<"$TOKEN_BODY")"
    if [[ "$ERROR" == "invalid_grant" ]]; then
        if [[ $(date +%s) -lt "$DEADLINE" ]]; then
            echo "The idp did not accept that code: it is expired, used or mistyped. Paste it again." >&2
            continue
        fi
        echo "The idp did not accept that code, and the codes of this sign-in have expired. Run this script again." >&2
        exit 1
    fi
    MESSAGE="the idp refused the request (HTTP ${HTTP_STATUS}"
    [[ -n "$ERROR" ]] && MESSAGE="${MESSAGE}, ${ERROR}"
    [[ -n "$DESCRIPTION" ]] && MESSAGE="${MESSAGE}: ${DESCRIPTION}"
    MESSAGE="${MESSAGE})"
    echo "Cannot sign in: ${MESSAGE}. Nothing was installed." >&2
    exit 1
done

echo "Signed in."

# ---- 1. find and download the latest version -----------------------------

echo "Finding the latest published version of $DAEMON_NAME..."
LIST_URL="${ARTIFACTS_URL}/artifacts/api/repositories/daemons/daemons"
LIST_BODY="$(printf 'Authorization: Bearer %s\n' "$ACCESS_TOKEN" | curl -fsS -H @- "$LIST_URL")" || {
    echo "Cannot read the daemon list from $LIST_URL." >&2
    exit 1
}
VERSION="$(latest_version_of "$DAEMON_NAME" <<<"$LIST_BODY")"
if [[ -z "$VERSION" ]]; then
    echo "No published version of $DAEMON_NAME was found at $LIST_URL." >&2
    exit 1
fi
echo "Latest version: $VERSION"

mkdir -p "$INSTALL_DIR"
TMP_TARGET="$(mktemp "${INSTALL_DIR}/.qits.XXXXXX")"
cleanup() {
    rm -f "$TMP_TARGET"
}
trap cleanup EXIT

DOWNLOAD_URL="${ARTIFACTS_URL}/artifacts/daemons/${DAEMON_NAME}/${VERSION}"
echo "Downloading $DOWNLOAD_URL..."
printf 'Authorization: Bearer %s\n' "$ACCESS_TOKEN" | curl -fsS -H @- -o "$TMP_TARGET" "$DOWNLOAD_URL"

if [[ ! -s "$TMP_TARGET" ]]; then
    echo "The download is empty. Nothing was installed." >&2
    exit 1
fi
chmod +x "$TMP_TARGET"

if need_cmd file; then
    KIND="$(file -b "$TMP_TARGET")"
    case "$KIND" in
        *ELF*executable* | *executable*) ;;
        *)
            echo "Warning: the downloaded file does not look like a program ($KIND)." >&2
            ;;
    esac
fi

# ---- 2. install ------------------------------------------------------------

echo "Checking the binary runs..."
if ! "$TMP_TARGET" --help >/dev/null 2>&1; then
    echo "The downloaded binary did not run. Nothing was installed." >&2
    exit 1
fi

TARGET="${INSTALL_DIR}/qits"
mv -f "$TMP_TARGET" "$TARGET"
trap - EXIT
echo "Installed qits $VERSION to $TARGET."

# ---- 3. PATH check ----------------------------------------------------------

case ":${PATH}:" in
    *":${INSTALL_DIR}:"*)
        echo "$INSTALL_DIR is already on PATH."
        ;;
    *)
        echo "$INSTALL_DIR is not on PATH. Add it:"
        echo
        echo "  echo 'export PATH=\"\$PATH:$INSTALL_DIR\"' >> ~/.bashrc"
        echo
        echo "Using zsh? Add the same line to ~/.zshrc instead. Then open a new shell."
        ;;
esac

# ---- 4. point Git at the installed binary -----------------------------------

GIT_HOST_URL="${QITS_GIT_HOST_URL:-$(derive_url githost)}"
GIT_HOST_URL="$(strip_trailing_slash "$GIT_HOST_URL")"
if [[ -z "$GIT_HOST_URL" ]]; then
    echo
    echo "Cannot work out the git host from $IDP_URL. Set QITS_GIT_HOST_URL to set up Git yourself, or run"
    echo "\`qits git-login --configure\` after signing in."
elif ! need_cmd git; then
    echo
    echo "git is not installed, so Git was not set up. Install git, then run \`qits git-login --configure\`."
else
    GIT_ORIGIN="$(normalize_origin "$GIT_HOST_URL")"
    GIT_KEY="credential.${GIT_ORIGIN}.helper"
    echo
    echo "Setting up Git for $GIT_ORIGIN..."
    # The empty value first clears every existing helper for this host (including
    # one that points at another qits binary, such as a checkout's target/qits).
    # A global helper for another host, such as GitHub's, is untouched.
    git config --global --replace-all "$GIT_KEY" ""
    git config --global --add "$GIT_KEY" "!${TARGET} git-credential"
    echo "Git is set up: $GIT_KEY -> $TARGET git-credential"
fi

# ---- 5. the Docker credential helper ----------------------------------------

# Docker runs docker-credential-<name> from PATH for a credHelpers entry, so the
# script sits beside qits. The same content `qits registry-login --configure`
# writes (DockerShim.java): it only runs `qits docker-credential`.
SHIM="${INSTALL_DIR}/docker-credential-qits"
ABSOLUTE_TARGET="$(cd "$INSTALL_DIR" && pwd -P)/qits"
QUOTED_TARGET="'$(printf '%s' "$ABSOLUTE_TARGET" | sed "s/'/'\\\\''/g")'"
SHIM_CONTENT="$(printf '#!/bin/sh\nexec %s docker-credential "$@"' "$QUOTED_TARGET")"
if [[ -f "$SHIM" && -x "$SHIM" && "$(cat "$SHIM")" == "$SHIM_CONTENT" ]]; then
    echo
    echo "$SHIM is up to date."
else
    TMP_SHIM="$(mktemp "${INSTALL_DIR}/.docker-credential-qits.XXXXXX")"
    printf '%s\n' "$SHIM_CONTENT" >"$TMP_SHIM"
    chmod 755 "$TMP_SHIM"
    mv -f "$TMP_SHIM" "$SHIM"
    echo
    echo "Wrote $SHIM for Docker."
fi

echo
echo "Next steps:"
echo "  qits login                        sign in for a platform session"
echo "  qits git-login                    sign in for Git pushes (Git already points at $TARGET)"
echo "  qits registry-login --configure   npm, Maven and Docker logins for the platform's registries"
