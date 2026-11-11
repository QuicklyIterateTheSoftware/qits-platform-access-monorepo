# The tapes' hook (epic qits-881). SOURCED, never executed: qits-ci's cli-tapes generator runs
# `. ./tapes/prepare.sh` from the repository root, under bash `set -eu`, before it plays
# tapes/*.tape, and vhs hands the environment this leaves to the terminal it records. So this file
# must not `exit` (that would end the generator; a failing command fails it instead), must be
# `set -u` safe, sends the output of what it leaves running to a file, and stops it again with an
# EXIT trap. The generator is qits-ci-service's platform-pipelines/automations/cli-tapes.yml.
#
# What it does:
#   1. builds docker/Dockerfile's `tapes-kit` target with buildctl — the same arguments as the QA
#      build in .config/qits/release.yml (docker/buildctl-args.sh), so the native compile is a cache
#      hit — into a scratch directory: the static `qits` and the fake platform's class path
#   2. puts that `qits` first on PATH
#   3. starts the fake platform (FakePlatformMain, from the commands module's test classes) on
#      loopback, its output in a file, and waits until it answers
#   4. exports what the tapes' commands read, and nothing of the step's own platform: every QITS_*
#      variable the step had is dropped first, so no real address, token or agent id reaches a tape
#
# PLAYING TAPES BY HAND. Without buildctl there is no step's builder; name a kit instead:
#   QITS_TAPES_KIT=<dir>        a kit exported before (`docker build --target tapes-kit --output
#                               type=local,dest=<dir> -f docker/Dockerfile .`); nothing is built
#   QITS_TAPES_BINARY=<path>    the `qits` the tapes run, instead of the kit's (a host build, say)
#   QITS_TAPES_CLASSPATH=<cp>   the fake platform's class path, instead of the kit's
# With both of the last two, no kit is needed at all. README, "The tapes", has the recipes.
#
# Everything the tapes see is fixed, because a tape's text golden must be the same on every play:
# the ports below, the token, the domain, the agent, the time zone and a scratch directory at a
# fixed path, which the tapes that need files (checkout-daemon, checkout, update, changelog) work
# in.

qits_tapes_prepare() {
  local root scratch kit binary classpath java_bin waited
  root=$(pwd)
  scratch=/tmp/qits-tapes
  rm -rf "$scratch"
  mkdir -p "$scratch/bin" "$scratch/config" "$scratch/cache"

  kit=${QITS_TAPES_KIT:-}
  if [ -z "$kit" ] && { [ -z "${QITS_TAPES_BINARY:-}" ] || [ -z "${QITS_TAPES_CLASSPATH:-}" ]; }; then
    kit="$scratch/kit"
    if ! command -v buildctl > /dev/null 2>&1; then
      echo "tapes/prepare.sh: no buildctl here, and no QITS_TAPES_KIT, QITS_TAPES_BINARY or QITS_TAPES_CLASSPATH to play with instead (README, The tapes)" >&2
      return 1
    fi
    # The QA build's arguments, argument for argument (docker/buildctl-args.sh), and the QA build's
    # secrets: then `build` is the same vertex as the QA step's `binary` build, and only the kit's
    # own RUN executes. Before anything below replaces QITS_TOKEN, which the tarball secret reads.
    . docker/buildctl-args.sh
    echo "tapes/prepare.sh: building the tapes' kit (docker/Dockerfile, target tapes-kit)"
    # shellcheck disable=SC2086 # tarball_auth is empty or two words, as in release.yml
    buildctl build --frontend dockerfile.v0 \
      --local context=. --local dockerfile=docker \
      --opt target=tapes-kit \
      --opt build-arg:MUSL_URL="$musl_url" \
      --opt build-arg:ZLIB_URL="$zlib_url" \
      $tarball_auth \
      --opt build-arg:QITS_DOMAIN="$qits_domain" \
      --secret id=qits-client-id,src=/tmp/qits-client-id \
      --secret id=qits-client-secret,src=/tmp/qits-client-secret \
      --output "type=local,dest=$kit"
    unset qits_domain maven_root musl_url zlib_url tarball_auth tarball_host
  fi

  binary=${QITS_TAPES_BINARY:-$kit/qits}
  classpath=${QITS_TAPES_CLASSPATH:-$kit/test-classes:$kit/classes:$kit/lib/*}
  if [ ! -f "$binary" ]; then
    echo "tapes/prepare.sh: there is no qits at $binary" >&2
    return 1
  fi
  # A copy, not a link: the local export loses the mode bit, and `qits update`'s tape replaces a
  # copy of its own, never this one.
  cp "$binary" "$scratch/bin/qits"
  chmod +x "$scratch/bin/qits"

  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    java_bin="$JAVA_HOME/bin/java"
  elif [ -x /opt/java/openjdk/bin/java ]; then
    java_bin=/opt/java/openjdk/bin/java
  else
    java_bin=$(command -v java) || {
      echo "tapes/prepare.sh: no java to run the fake platform with" >&2
      return 1
    }
  fi

  # The fake platform, in the background, its output in a file and never on this shell's: a
  # process holding the step's output open would keep the step from ending. Small and quick to
  # start; it only answers canned JSON.
  "$java_bin" -Xmx256m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 \
    -cp "$classpath" eu.wohlben.qits.cli.access.FakePlatformMain 47080 47081 \
    > "$scratch/fake-platform.log" 2>&1 < /dev/null &
  qits_tapes_fake_pid=$!
  trap 'kill "$qits_tapes_fake_pid" 2> /dev/null || true' EXIT
  waited=0
  until curl -fsS -o /dev/null http://127.0.0.1:47080/tapes/ready 2> /dev/null; do
    if ! kill -0 "$qits_tapes_fake_pid" 2> /dev/null || [ "$waited" -ge 120 ]; then
      echo "tapes/prepare.sh: the fake platform did not answer; its log:" >&2
      cat "$scratch/fake-platform.log" >&2
      return 1
    fi
    sleep 0.5
    waited=$((waited + 1))
  done
  echo "tapes/prepare.sh: the fake platform answers on 127.0.0.1:47080 and 127.0.0.1:47081"

  # Nothing of the step's own platform reaches a tape: its token, its addresses, its agent.
  local name
  for name in $(compgen -e); do
    case "$name" in
      QITS_*) unset "$name" ;;
    esac
  done

  export PATH="$scratch/bin:$PATH"
  # The token home (QITS_TOKEN), as in every agent container, with a token that is no token: the
  # fake reads none, and `qits git-credential get` is the one command that prints it.
  export QITS_TOKEN=qits-tapes-not-a-token
  export QITS_TOKEN_SUBJECT=tapes
  export QITS_DOMAIN=example.test
  export QITS_AGENT_ID=00000000-0000-4000-8000-0000000000a1
  local app
  for app in PROJECTS CI DEPLOYMENTS MAINTENANCE IDP WORKSPACES EVENTS SYSTEM REGISTRY MIRROR GITHOST LANDING; do
    export "QITS_URL_$app=http://127.0.0.1:47080"
  done
  export QITS_URL_OBSERVABILITY=http://127.0.0.1:47081
  # No session file, no update state and no Git setup of the step's own.
  export XDG_CONFIG_HOME="$scratch/config"
  export XDG_CACHE_HOME="$scratch/cache"
  export GIT_CONFIG_GLOBAL="$scratch/gitconfig"
  git config --file "$GIT_CONFIG_GLOBAL" protocol.file.allow always
  export TZ=UTC
  export LANG=C.UTF-8
  # A pager would wait for a key in the middle of a tape (`git log`, say).
  export PAGER=cat GIT_PAGER=cat
  cd "$root"
}

qits_tapes_prepare
