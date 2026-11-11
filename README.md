# qits-platform-access-monorepo

The `qits` command: access to the qits platform from a workstation, with defaults that need no
setup. A static native binary for Linux and WSL.

Commands:

- `qits login` signs you in through the browser and stores the session.
- `qits session-daemon` keeps that session fresh for as long as it runs.
- `qits projects list`, `qits repositories … list`,
  `qits work list|details|create|update|transition|status|comment` (work items of every archetype)
  and `qits release-request … list|create|join|move|withdraw|approve|decline|regenerate|rerun` read from and ask the projects service;
  `qits repositories remote-login` attaches this terminal to a repository's backup-remote sign-in, and
  `qits repositories backup reset` makes a backup twin whose main holds unrelated history ours.
- `qits ci runs|run|retry|cancel` lists a repository's CI runs, shows a run with its steps and their logs,
  runs a finished run again, and stops a queued or running one. `qits ci report show` shows a run's release reports (which tests
  failed, and why; the line coverage of the tree and of the change; the pacts and provider states the
  change adds or removes; the entity diagram's tables and columns it changes); `qits ci report submit` is the
  CI step's side that collects and uploads them.
- `qits users invite|register|list|delete` manages the platform's users on the idp: an admin
  mints a register token, the new user spends it and gets a generated password, shown once.
- `qits remembered-devices list|revoke` shows the browsers the idp remembers for you (the ones
  that sign back in without a password) and forgets one, ending every session it opened.
- `qits events` prints the platform's domain events as they happen; `qits events query` prints the
  ones that already happened in a window of time.
- `qits observe` prints what qits-observability takes in (logs, spans, metrics) as it arrives,
  filtered by the service; `qits observe query` asks the same filters of what it still holds.
- `qits wait` declares what an agent, or a project's desk, waits on (a release request to end, a
  work item to reach a status, a CI run to end, or any domain event that fits a selection); the
  platform gives the holder a turn when it happens. `qits wait list|cancel` show and end the
  holder's waits.
- `qits workspaces` is every action the landing app's workspace pages offer, one command each, on
  the same qits-workspaces door: the slots and their containers (start, stop, recreate, the start's
  log), the agents (create, discard, prompt, the prompt draft, the chat, the files, the harness,
  sign-in, plugins), and a command's terminal (`qits workspaces attach`). The nodes slots are placed
  on are `qits targets`.
- `qits targets` lists qits-system's execution targets (connection, slots, login, quarantine),
  reads one in full, declares one or rotates its registration token (printing the install line
  once), updates its description or slot reservations, lifts a quarantine, asks for a
  health check or a login check, deletes one, lists leases, and `qits targets sign-in` runs a
  harness's sign-in on the target through the API and waits for it to report signed in.
  `qits targets list` shows slots per type as used/reserved and then used/total. A reservation
  holds slots for one type and is not a limit: `ws 5/1` is valid while shared slots are free. The
  total is the target's real slot count. It reads `reservations`.
- `qits git-login` signs this workstation in for Git pushes to the platform's git host, and
  `qits git-credential` is the Git credential helper that uses that sign-in — and, inside the
  platform, the workspace token or the credential the process was handed instead.
- `qits registry-login --configure` gives npm and Maven on this workstation a login for the
  platform's registries, in blocks of `~/.npmrc` and `~/.m2/settings.xml` that `qits
  session-daemon` keeps fresh — see below.
- `qits agents list|show|prompt|interrupt|stop|restart|yield|sessions|transcript|chat|attach|files|
  discard|draft` are the platform's agents, each named by its id or by `--work qits-123` — see below.
- `qits agents claude hook work-links` is a Claude Code hook that shows work ids such as `qits-111`
  as links to the landing app, and `qits agents claude statusline work-items` a Claude
  Code status line with the session's newest work items and their states — see below.
- `qits artifacts publish` publishes a release artifact to qits-artifacts from a CI step: an sbom,
  a docs bundle, a daemon binary, or an npm decision. It runs with no person signed in — see below.
- `qits update` updates `qits` itself to the newest released version; a signed-in `qits` on a
  workstation also does it by itself, at most once an hour, in the background. `qits -V` prints the
  version — see below.
- `qits tui` opens an interactive screen over all of the above: pick a command instead of
  remembering it, and see it run in the lower half — see below.

The binary is called `qits`. qits-bootstrap-cli's binary is `qits-bootstrap`. Started under the
name `qits-publish`, it behaves as `qits artifacts publish` — see below.

## Install

`install.sh`, at the root of this repository, puts `qits` on a stable path and points Git at it.
There is no public download of the script on its own: reading a raw file from the platform's git
host needs a credential, same as any other read, so get it from a checkout of this repository (one
you already have, or `git clone` the wrapper that holds it) and run it from there:

    ./install.sh

It does four things:

1. Signs you in through the idp — the same browser-and-pasted-code exchange as `qits login` — and
   holds the access token in a shell variable only. It is never written to disk or printed.
2. Finds the latest released version in qits-artifacts and downloads it with that token.
3. Installs it as `qits` in the install directory (`~/.local/bin` by default), replacing any file
   there of the same name, and checks that it runs.
4. Sets Git's credential helper for the platform's git host to the installed binary, the way
   `qits git-login --configure` does (see below): it replaces any earlier helper for that host,
   including one pointing at a build output such as `platform-access-cli/target/qits`, and leaves
   every other host's helper (GitHub's, for example) alone.

Run it once. After `qits login`, the installed `qits` keeps itself on the newest release (see
`qits update` below).

Environment overrides:

- `QITS_IDP_URL` — the idp's base URL. No built-in default: without it, set `QITS_DOMAIN` (your
  platform's public domain) and the idp URL is derived as `https://idp.qits.<QITS_DOMAIN>/idp`.
  With neither set, the script refuses (exit 2).
- `QITS_ARTIFACTS_URL` — the artifacts store's base URL. Default: derived from the idp URL, the
  same way `qits` derives every other service's address (swap the idp host's first label).
- `QITS_GIT_HOST_URL` — the git host's base URL. Default: derived the same way.
- `QITS_INSTALL_DIR` — where to install. Default `~/.local/bin`.

If the install directory is not on `PATH`, the script says so and prints the line to add:

    echo 'export PATH="$PATH:$HOME/.local/bin"' >> ~/.bashrc

(zsh users: the same line in `~/.zshrc`). Open a new shell afterwards.

It needs bash, curl, and one of `jq` or `python3` to read JSON answers (`jq` is used when present).

`scripts/test-install.sh` runs the script offline against stub servers standing in for the idp and
qits-artifacts, and checks the binary lands, the PATH hint appears, Git is set up correctly, and the
token never appears in what the script prints. Run it with `./scripts/test-install.sh`.

## Download

Each release publishes the static binary to the platform's artifacts store, under this repository's
name and the released version, the way qits-ci-daemon is published:

    https://registry.<env>.<domain>/artifacts/daemons/qits-platform-access-cli/<version>

It is one file with no dependencies, so it runs as it is on any x86-64 Linux, WSL and alpine
included:

    curl -fsSL -H "Authorization: Bearer <token>" -o qits \
      https://registry.<env>.<domain>/artifacts/daemons/qits-platform-access-cli/<version> \
      && chmod +x qits

**The download needs your own token.** The edge guards the registry host like every other host
and answers 401 without one. Use your own sign-in, never a machine client's id and secret.

- **The first time**, without `qits`: sign in to the platform in the browser, then open the address
  in the same browser. The edge session is your token, and the browser saves the file. Then
  `chmod +x qits`.
- **After that**, `qits` holds your token. `qits login` writes it to `t.json`, and
  `qits session-daemon` keeps it fresh (or run any `qits` command first: it refreshes a token that
  is about to expire). Use it as `<token>`:

      token=$(jq -r .accessToken "${XDG_CONFIG_HOME:-$HOME/.config}/qits/t.json")

- **There is no `latest` address.** A version is published once and never changes, and the store
  keeps no moving pointer. The newest version is `latestVersion` in the store's list of daemons (in
  the browser, open the same address):

      curl -fsSL -H "Authorization: Bearer <token>" \
        https://registry.<env>.<domain>/artifacts/api/repositories/daemons/daemons \
        | jq -r '.daemons[] | select(.name == "qits-platform-access-cli") | .latestVersion'

- Inside the platform network, the store's own address needs no token:
  `http://qits-platform-artifacts:8080/artifacts/daemons/qits-platform-access-cli/<version>`.

- The answer carries `Docker-Content-Digest: sha256:…`, the digest the release log prints.
- The store keeps the last two versions of every daemon. An older one goes after 90 days in which
  nobody downloaded it.

## qits update

`qits` keeps itself on the newest released version. Once installed and signed in, nobody has to
install it again.

    qits -V             # the version this binary is, e.g. 2026.1015.175513
    qits update         # update now, and say what was done
    qits update --check # only say whether a newer version is released

`qits update` asks qits-artifacts for the newest version (`latestVersion` in the daemon list, the
same list install.sh reads) with the session of `qits login`. When that is strictly newer than the
running version (each segment of `YYYY.MMDD.HHMMSS` compared as a number), it downloads the file
into the running binary's directory under a temporary name, and checks it: its length and its
sha256 digest have to be what the store's HEAD announced (`Content-Length`,
`Docker-Content-Digest`), and the file, made executable (mode 755), has to answer `-V` with the
version it was downloaded as. Only then is it renamed over the running binary, in one step. Any
failure deletes the temporary file and leaves the installed `qits` as it was. It prints one of:

    qits is up to date (2026.1015.175513).
    Updated qits from 2026.919.40352 to 2026.1015.175513.

or, on stderr with exit code 1, why it did not update.

**By itself.** Before a command runs, a `qits` on a workstation with a session file checks whether
an hour has passed since the last check (`update.json` in the config directory, beside `t.json`).
If it has, it records the time and starts `qits update --background` detached, without waiting for
it, so no command is delayed and none fails because of it. The background run has no terminal: a
failure is kept in `update.json`, and the next command prints it once, as one line on stderr.
No check is started by `qits update` itself, by `--help` or `-V`, or by the commands whose output or
timing another program reads: `session-daemon`, `checkout-daemon`, `git-credential`,
`agents …` (Claude's hook and status line) and `help`.

**Turning it off.** `QITS_AUTO_UPDATE=false` (or `0`, `off`, `no`) turns the automatic update off.
`qits update` still works.

**Never inside the platform.** In-platform (`QITS_PLATFORM=true`), in a workspace with
`QITS_TOKEN`, and in the MCP service the image decides which `qits` there is:
nothing is checked automatically there, and `qits update` refuses with exit code 2. Without a
session file nothing is checked automatically either; `qits update` then says to run `qits login`.
A build that does not know its version (one built without Maven's resource filtering) never updates.

**A directory you cannot write.** When `qits` is installed where you cannot write (for example
`/usr/local/bin`), `qits update` downloads and checks the new version into `$XDG_CACHE_HOME/qits/`
(default `~/.cache/qits/`) and prints the command that installs it:

    sudo install -m 755 ~/.cache/qits/qits-2026.1015.175513 /usr/local/bin/qits

The automatic run downloads nothing there and only says, once, to run `qits update`.

The store's address is `https://registry.qits.<domain>`, derived from the session's idp address
like every other service's; `--artifacts-url` or `QITS_ARTIFACTS_URL` sets it.

**Where the version comes from.** The release stamps every pom of the reactor with the version it
tags and publishes the binary under, and the build filters `${project.version}` into
`qits-version.properties` in the commands jar (named in that jar's native-image resource config, so
the binary carries it). A build from a working tree therefore names the release it was branched
from.

## Build

    sdk env && ./mvnw package -Dnative -DskipTests   the binary for this host:
                                                     platform-access-cli/target/qits
    ./mvnw clean verify                              the tests; packages the pin jar and the
                                                     commands jar
    docker build --target binary --output type=local,dest=out -f docker/Dockerfile .
                                                     the released form, static: out/qits

Run all three from the repository root: it is a four-module Maven reactor. A root native build
compiles two programs, the binary and the MCP service; `-pl platform-access-cli -am` (what the
Dockerfile builds) or `-pl platform-access-mcp-service -am` builds one.

    platform-access-commands    every command but `qits tui`, the session and the screen's model,
                                as a plain picocli jar: no quarkus-picocli, no JLine. One command
                                tree that more than one program is built from. It is deployed to
                                no Maven repository; the program below takes it from the reactor.
    platform-access-mcp-service the same commands served as MCP tools at /mcp
                                (`qits-platform-access-mcp-service`): one tool per command, named by
                                its path joined with `_` (`work_update`), its options as the
                                schema, each call run in-process as the caller, with the bearer on
                                the MCP request as its credential. A Quarkus service, native in
                                `target/qits-platform-access-mcp-service`; deployed to no Maven
                                repository. See "The MCP service" in AGENTS.md.
    platform-access-cli         the program — everything described above, and the native binary:
                                the commands, plus `qits tui`, as a Quarkus command-mode
                                application. It builds no jar and is deployed to no Maven
                                repository: what it produces is a file, published to the artifacts
                                store as bytes.
    platform-access-cli-binary  one small jar whose own version *is* the version of the binary the
                                same release published. It carries no bytes of the binary, only its
                                name, the command name and that version. This is the only artifact
                                this repository deploys to a Maven repository, and qits-ci is what
                                depends on it: its release steps run `qits`, and depending on a
                                coordinate is how its pom decides *which* `qits` instead of taking
                                whatever the store had latest when a step started. See
                                `PlatformAccessCliBinary`.

`.sdkmanrc` names the GraalVM (25.0.2-graalce), so `sdk env` sets `JAVA_HOME`. Without sdkman:
`JAVA_HOME=$HOME/.sdkman/candidates/java/25.0.2-graalce ./mvnw package -Dnative -DskipTests`.
Run `clean verify` before a native build, not after: `clean` removes the binary.

The tests need no docker and no platform. Copy `platform-access-cli/target/qits` to a directory on
your `PATH`, for example `~/.local/bin`.

The host build is glibc-linked. The released binary is static (musl), so it also runs on alpine.
`docker/Dockerfile` builds it on a musl toolchain it builds first, as a stage: a copy of
qits-ci-daemon's `docker/Dockerfile.musl-builder`. Off the platform, pass the upstream tarballs and
base image, or `--build-arg BUILDER_IMAGE=` a toolchain image built from that file; the Dockerfile's
header gives both commands. The build fails unless `ldd` finds the binary static;
`file out/qits` says `statically linked`. A second target, `--target sbom`, exports the release's
CycloneDX document from the same build.

## Releases

Only through a release request, like every repository here. `.config/qits/` holds the two recipes,
shaped like the retired qits-artifacts-cli's were:

- `ci-event-release-request.yml` gates a request's fold: `./mvnw verify`, then the static binary on
  the platform's BuildKit, and a `--help` run of it on an alpine image.
- `ci-event-release.yml` runs on the release tag: the same build and its SBOM, then a PUT of the
  binary to `/artifacts/daemons/qits-platform-access-cli/<version>` and of the SBOM to
  `/artifacts/sboms/daemon/qits-platform-access-cli/-/<version>`. A version that exists already
  (HTTP 409) fails the release: a version is never published twice. A second step, on a maven image
  and deliberately after the binary is published, deploys the pin jar
  `eu.wohlben.qits:qits-platform-access-cli-binary` under the same version — a jar resolvable before
  the binary it names would be a pin pointing at nothing. It re-runs as a no-op: a deploy of the
  same bytes is skipped when both the jar's pom and its parent's are already there.

  The file declares both artifacts — `{type: daemon, name: qits-platform-access-cli}` and
  `{type: maven, name: eu.wohlben.qits:qits-platform-access-cli-binary}` — so qits-ci announces one
  release for each.

The same release also ships the MCP service as an image, `qits/qits-platform-access-mcp-service`
(`docker build --target service -f docker/Dockerfile .`; its SBOM is `--target service-sbom`). The
release's first step builds the binary, then builds and pushes the image to
`registry.qits.$QITS_DOMAIN/$QITS_IMAGE_REPOSITORY/qits-platform-access-mcp-service:<version>`, then submits
the binary; the postlude submits both SBOMs from that step. The request's QA step builds the image
too, without pushing, so a native break in the service fails the request. `.config/qits/release.yml`
declares it as the third artifact, `{type: docker, name: qits/qits-platform-access-mcp-service}`.

qits-deployments deploys that image because `.config/qits/deployments.yml` exists: application
`qits-platform-access-mcp-service`, wire alias `<env>-qits-platform-access-mcp-service:8080`, route
`/mcp`, public host `mcp.<env>.<domain>`, readiness at `/q/health/ready`. Its environment
(`QUARKUS_OIDC_AUTH_SERVER_URL`, `QITS_ENV`, …) comes from qits-bootstrap-cli's `ComposeTemplate`.

The recipes need no toolchain image in the registry: `docker/Dockerfile` builds the musl toolchain
as a stage, from two tarballs in the platform's Maven store, which the bootstrap seeds.

## The tapes

`tapes/` holds a [VHS](https://github.com/charmbracelet/vhs) tape for every integration (epic
qits-881): `tapes/<integration>-cli.tape` types the integration's commands — its main read as a
table and with `-o json`, and one write the platform refuses, with `echo $?` — and
`tapes/<integration>-tui.tape` drives `qits tui` down to the same command, picks its values from
the lists the platform fills in, runs it with Ctrl+R and quits. `login`, `git-login`,
`registry-login`, `artifacts` and `changelog` have a CLI tape only; each tape's header says what it
shows, and where a run cannot be shown as it is (a stream's notes carry a wall-clock stamp, a
checkout needs a repository), what it shows instead and why. They are the one place the built
binary runs end to end: the static `qits` the release ships, in a real terminal, against a fake
platform. `TapesCoverageTest` reads the command tree and fails when a top-level command has no
tapes, or a tape no command.

    tapes/prepare.sh             the hook: builds the binary and the fake platform's class path,
                                 starts the fake, exports what the tapes read
    tapes/scenes/<name>.sh       what a tape needs on disk (a repository to hold, a binary to
                                 replace), sourced while the tape is hidden
    tapes/<name>.tape            the tapes
    tapes/golden/<name>.txt      each tape's text: the terminal after every command, written by vhs
    tapes/recordings/<name>.gif  each tape as a GIF

**The goldens and the GIFs are never written by hand.** QA plays every tape and fails when a golden
moved (a step in `.config/qits/release.yml` with `check-outputs: [cli-tapes]`); new ones come from
the platform's `cli-tapes` automation, which commits them to the release request:

    qits release-request --project qits --repository qits-platform-access-monorepo regenerate --kind cli-tapes

The generator is qits-ci's (`platform-pipelines/automations/cli-tapes.yml` in qits-ci-service): it
sources `tapes/prepare.sh`, plays each tape into its golden, and renders a GIF only for a golden that
changed or a GIF that is missing.

**The fake platform** is `FakePlatformMain`, in the commands module's test classes: `FakePlatform`
on `127.0.0.1:47080` for every service and `FakeSocketServer` on `127.0.0.1:47081` for
qits-observability's stream and search. Its answers are the providers' golden masters — the jars
the consumer pacts read — and, where no provider records one, fixtures under
`platform-access-commands/src/test/resources/tapes/`; a write answers `403`, as the platform answers
a `qits:agent` token. `tapes/prepare.sh` exports `QITS_URL_<APP>` at it for every service, a
token home with a token that is no token (`QITS_TOKEN=qits-tapes-not-a-token`,
`QITS_TOKEN_SUBJECT=tapes`, `QITS_DOMAIN=example.test`), `QITS_AGENT_ID`, `TZ=UTC` and its own
`XDG_CONFIG_HOME`, after dropping every `QITS_*` variable the shell had. Nothing a tape shows
depends on the clock, a random port or a temporary path, because a golden must be the same on
every play: each command is typed while the tape is hidden and shown once its prompt is back.
A TUI tape reaches a command with the screen's filter, `/<name> ` — a row's text is its name, a
space and its description, so that matches the name and only a description that says `<name> ` too
— and steps Down past such rows; it never counts its way down the list, so a new command shifts no
tape unless its description names another command that way. Each wait checks that the row it lands
on is the one named, so a shift fails loudly rather than running the wrong command.

**Playing a tape by hand.** `tapes/prepare.sh` builds the Dockerfile's `tapes-kit` target with
`buildctl`, the way QA does; off the platform, export the kit yourself and name it:

    docker build --target tapes-kit --output type=local,dest=out/tapes-kit -f docker/Dockerfile \
      --build-arg TOOLCHAIN_BASE=quay.io/quarkus/ubi10-quarkus-graalvmce-builder-image:jdk-25 \
      --build-arg MUSL_URL=https://more.musl.cc/11.2.1/x86_64-linux-musl/x86_64-linux-musl-native.tgz \
      --build-arg ZLIB_URL=https://github.com/madler/zlib/releases/download/v1.3.1/zlib-1.3.1.tar.gz .

Then play it with `qits/build-images/vhs-base` (vhs, ttyd, ffmpeg, a chromium and a JRE):

    docker run --rm -v "$PWD:/work" -w /work qits/build-images/vhs-base:latest bash -c \
      'set -eu; QITS_TAPES_KIT=out/tapes-kit . ./tapes/prepare.sh; vhs -o out/projects-cli.txt tapes/projects-cli.tape'

or with a local vhs the same way, in a bash of its own (the hook changes the shell it is sourced
into). `QITS_TAPES_BINARY=<path>` plays a `qits` of your own instead of the kit's (a host build, say),
and with `QITS_TAPES_CLASSPATH=<class path>` beside it no kit is needed at all: the class path is
`platform-access-commands/target/test-classes`, `target/classes` and the module's test-scope jars
(`./mvnw -pl platform-access-commands dependency:build-classpath -DincludeScope=test`). Write the
output anywhere but `tapes/golden/`. The tapes are written for the native binary: `qits tui` runs a
command by starting its own binary again (`/proc/self/exe`), which on a JVM is `java`, and `qits
update` replaces a binary, never a JVM.

## Using qits from an agent

`qits help skill` prints the help of every command as a SKILL.md: when to use qits, the platform
rules, and each command with its options, examples and exit codes. It is hidden from `qits --help`.
To give it to Claude Code:

    mkdir -p ~/.claude/skills/qits && qits help skill > ~/.claude/skills/qits/SKILL.md

The repository holds the same file as `SKILL.md`. The help texts are its one source: a test fails
when the file differs from them, and `./mvnw test -Dtest=SkillDocumentTest -Dqits.skill.update=true`
writes it again.

## qits tui

    qits tui

An interactive screen over every command above. The upper half is the picker — the commands, then
the options of the one you chose, required first and marked `*`; the line between the halves is the
command your choices have built, so the screen also teaches the command line; the lower half is what
the last run printed.

     qits tui · qits.<QITS_DOMAIN> · signed in as jan
     ┌ ci ▸ runs ──────────────────────────────────────────────────────────────────┐
     │ * --project        qits                                                     │
     │ * --repository     ▸ qits-ci-service                                        │
     │   --status         (any)            RUNNING FAILED SUCCESS                  │
     │   --limit          20                                                       │
     │ ↑↓ move · ⏎ choose · ␛ back · / search · ⌃R run · q quit                     │
     └─────────────────────────────────────────────────────────────────────────────┘
     $ qits ci runs --project qits --repository qits-ci-service
     ┌ output ─────────────────────────────────────────────────────────── exit 0 ──┐
     │ ID        STATUS   BRANCH   COMMIT    REQUEST   CREATED                     │
     │ 473131bb  SUCCESS  main     767cd203  -         2026-09-13 19:50:50 GMT     │
     └─────────────────────────────────────────────────────────────────────────────┘

| key | does |
|---|---|
| `↑` `↓` (also `k` `j`) | move in the list |
| `⏎` | drill into a command, or edit the selected option |
| `␛` / `←` | back one segment |
| `/` | filter the list by typing; `␛` clears it |
| `⌃R` | run the command as shown |
| `⌃C` | stop a running command; it does not quit the screen |
| `⌃L` | in an open dropdown: forget what the platform said and ask again |
| `⌃P` | history: the commands run this session; `⏎` re-runs one, `e` puts it back in the picker |
| `q` | quit |

`--project`, `--repository`, `--release-request` and a run's id are lists the platform
fills in: pick a project and the repository list is that project's. An option with nothing to offer
is a field to type into, and so is one whose source could not answer — the screen says why and stays
usable. Nothing it fetches is written to disk, and a value typed into a hidden field is never
echoed, never remembered and never put on the child's command line.

It needs an interactive terminal of at least 80×24; in a pipe or a CI step it says so and exits 2.
A command is run by starting this same binary again, so a run behaves exactly as it does when typed.

The screen holds no knowledge of any command: it reads picocli's own model, the same one that makes
`--help` and `SKILL.md`. A command says the little that model cannot with
`@TuiCommand(interaction, output)` — whether it streams, opens a browser, or only means anything in
a CI step — and an option says where its values come from with `@Completes(SomeSource.class)`. A
command that says neither still appears and still runs.

## qits from inside the platform

The same binary, in a container on the platform, is another home and not another program. There
is no browser and nobody to sign in, and there are two ways in, decided once at startup:

- **A workspace token** (`QITS_TOKEN`), which every agent container, runner node and admin or
  editor workspace carries. Set, it wins over everything else: every command sends that token as
  it is to the public vhosts `https://<app>.qits.<QITS_DOMAIN>`, nothing is minted, nothing is
  written to `t.json` or under the agent's config folder, and a `401` means the token was deleted
  with the workspace's container.
- **In-platform** (`QITS_PLATFORM=true`), which the MCP service sets for every command it runs.
  A service is dialled at its public vhost, `https://<app>.qits.<QITS_DOMAIN>`, exactly as in
  the token home — the CLI never dials a wire alias (qits-1436); the MCP service hands each command
  its own `QITS_DOMAIN`. The credential is the caller's own bearer, which the
  service hands to each command; this home has none of its own, and a call made there without one
  refuses with exit code 2.

`QITS_URL_<APP>` overrides an address in either. Both behave the same way at the door:

- an agent's token is `qits:agent`. Every read door answers and an operator write answers `403`,
  which is correct behaviour and is worded as such;
- **nothing is refused before the call.** The audience a command dials is not the CLI's to judge:
  every qits service accepts the one audience `qits-platform`, and an agent's token carries
  exactly that. A service that
  really does refuse the token answers `401`, and only then does the CLI add a sentence of its own.

This used to work the other way round, and it was wrong. The CLI read the token's `aud` claim and
refused any service the claim did not name — a false negative, because every service accepted the
fleet-wide `qits-platform` the token carried, whatever else the claim named. It cost
`qits observe` its entire reason for existing in a container: qits-observability grants `qits:agent`
read access **on purpose** (its `AgentReadAccessTest` asserts that an agent reads the telemetry API
and opens the live stream), and the CLI was the only thing saying no. Measured from a workspace
container on 2026-09-14, with the very bearer the CLI would not send:

    $ curl -H "Authorization: Bearer $(qits-token qits-platform)" \
        http://dev-qits-observability:8080/observability/api/telemetry/sources
    200
    $ ... /observability/api/telemetry/errors?limit=3
    200  {"groups":[],"total":0,…}

A workstation is untouched by any of this: without `QITS_TOKEN` or `QITS_PLATFORM=true` the session
file is the only credential there is.

The container is also quiet. The workspace image sets `QUARKUS_ANALYTICS_DISABLED=true` for the
Maven builds the agent runs there, and this binary is a Quarkus application as well, so that
variable used to make it print a configuration warning on stdout ahead of every answer — corrupt
data for `qits work list --project qits | head` and for anything else that reads the output. The build now
records the same value, the two agree, and nothing but the command's own answer is printed.

### The smoke run

Recorded on dev, 2026-09-13, from the released binary (`2026.913.220350`) inside a workspace
container that dialled the wire aliases. With `QITS_TOKEN` the addresses are the public vhosts and
the answers read the same:

    $ qits ci runs --project qits --repository qits-platform-access-monorepo --limit 3
    ID        STATUS   BRANCH                                        COMMIT    REQUEST   CREATED                  TOOK
    473131bb  SUCCESS  2026.913.195048                               767cd203  -         2026-09-13 19:50:50 GMT  2m40s
    f4f1ec8d  SUCCESS  release/33f56c22-24f8-42b9-9305-1d0d73d3a3b5  d47a2770  33f56c22  2026-09-13 19:46:08 GMT  3m52s
    b6824390  SUCCESS  2026.913.185314                               109ec668  -         2026-09-13 18:53:16 GMT  2m42s
    # exit 0 — no `qits login`, and no session file anywhere

    $ qits ci retry 00000000-0000-0000-0000-000000000000
    403 - this credential is qits:agent, which reads but does not write
    Your roles do not allow this (HTTP 403): POST http://dev-qits-ci:8080/ci/api/runs/00000000-0000-0000-0000-000000000000/retry
    # exit 1 — a write door, answered as what it is

    $ qits observe --filter=service=qits-ci
    # the stream opens and prints telemetry until stopped
    # the run recorded here on 2026-09-13 showed this exiting 2 with "the workspace credential is
    # not granted dev-qits-observability". That was the CLI's own false negative, not the
    # platform's answer: the service takes this credential and always did (see above).

    $ qits login
    no browser in the platform - the workspace credential is already in use
    # exit 2 — unchanged on a workstation

    $ qits tui < /dev/null
    qits tui needs an interactive terminal. (Unable to create a terminal)
    # exit 2 — it says so rather than waiting for a key nobody can press

On a terminal in-platform the same command paints, and its header says which home it is in:

    qits tui · in platform · dev · agent (qits:agent)

with `login` and `git-login` dim and sorted last, the way a CI-only command already is.

## qits login

    qits login [--idp-url <url>] [--no-browser]

1. Prints the sign-in address and tries to open it (`wslview` on WSL, then `xdg-open`).
2. You sign in. The page shows a code.
3. Paste the code at `Paste the code:`. A mistyped or used code asks again, while the codes of
   this sign-in are still valid (5 minutes). Ctrl-C ends with nothing written.
4. The session goes to `$XDG_CONFIG_HOME/qits/t.json` (default `~/.config/qits/t.json`), and the
   command prints when the access token and the session end.

Which platform: `--idp-url`, else `QITS_IDP_URL`, else `https://idp.qits.<QITS_DOMAIN>/idp`. There
is no built-in default domain: with none of the flag, `QITS_IDP_URL` or `QITS_DOMAIN` set, the
command refuses (exit 2) naming `QITS_DOMAIN` (`install.sh` refuses the same way). The platform is
the project `qits` and has no environments, so `QITS_ENV_NAME` is not read.

Naming the platform explicitly:

    qits login --idp-url https://idp.qits.<QITS_DOMAIN>/idp

`--no-browser` only prints the address. Use it over SSH.

A workstation signed in before the platform's names moved still has the old environment name,
`https://idp.dev.<domain>/idp`, as `idpUrl` in `t.json`, an address that no longer answers. Run
`qits login`, then `qits git-login --configure`, and both sign-ins point at the new names. The old
`https://githost.dev.<domain>` key in `git.json` and its `credential.<that address>.helper` entry
stay behind and are harmless: Git never asks that host again.

### The session file

    {
      "idpUrl" : "https://idp.qits.<QITS_DOMAIN>/idp",
      "clientId" : "qits-cli",
      "accessToken" : "…",
      "accessExpiresAt" : "2026-09-11T20:15:00Z",
      "refreshToken" : "…",
      "refreshExpiresAt" : "2026-10-11T20:00:00Z"
    }

The directory is 0700 and the file 0600. Each write goes to a temporary file in the same directory,
is synced, and is then renamed over `t.json`, so a reader never sees half a file. The times are
absolute. The session end moves forward with each refresh.

`qits` never prints or logs a token.

## qits session-daemon

    qits session-daemon [--margin <seconds>]

Refreshes the access token `--margin` seconds (default 30) before it expires, and writes the new
pair to `t.json`. It logs one line per event to stderr. It runs in the foreground; add `&`, or run
it as a systemd user service (below).

- With no session file, it says so and waits for `qits login`.
- A refresh the idp refuses with `invalid_grant` ends the session. The daemon logs
  `session ended (revoked or expired) — run `qits login``, keeps running, and carries on when a new
  session file appears.
- A connection error or a 5xx is retried, waiting 1, 2, 4 … up to 60 seconds between tries, until
  the session ends.
- It reads the wall clock after each short sleep, so after a suspend (or WSL's clock jump) it
  refreshes at once. An access token that has already expired is refreshed, not an error.
- SIGTERM or SIGINT stops it cleanly, with exit code 0.

### Two lock files

Refresh tokens rotate, and the idp revokes the whole session when a spent refresh token comes back,
even from an honest race. So only one process may refresh at a time. One lock cannot do both jobs
this needs, so there are two, beside `t.json`:

- `t.json.lock` — the write lock. The daemon holds it for a short time around each
  read-refresh-write, and `qits login` around its write. It reads the file again under the lock, so
  a new login is never overwritten.
- `session-daemon.lock` — held for a daemon's whole life. A second daemon that cannot take it says
  so and exits with code 1.

Both are exclusive fcntl locks (what Java takes on Linux), not flock(2). The shell's `flock` tool
does not see them.

### As a systemd user service

WSL here runs systemd. A sample unit, `~/.config/systemd/user/qits-session-daemon.service`:

    [Unit]
    Description=Keep the qits session fresh

    [Service]
    ExecStart=%h/.local/bin/qits session-daemon
    Restart=on-failure
    RestartSec=30

    [Install]
    WantedBy=default.target

Then:

    systemctl --user daemon-reload
    systemctl --user enable --now qits-session-daemon
    journalctl --user -u qits-session-daemon -f

Installing it is not part of this version.

## Platform commands

    qits projects list
    qits repositories --project <project> list
    qits repositories --project <project> create <name> [--component <component>]
    qits repositories --project <project> remote-login [--repository <repository>]
    qits repositories --project <project> backup reset --repository <repository> --reason <text>
    qits work list --project <project> [--archetype <A>] [--status <STATUS>] [--parent <entity>]
    qits work --entity <id or qualified id> details
    <payload> | qits work create --archetype <A>
    <merge patch> | qits work --entity <id or qualified id> update
    <payload> | qits work --entity <id or qualified id> transition --archetype <A>
    {"target":"<STATUS>"} | qits work --entity <id or qualified id> status
    <payload> | qits work --entity <id or qualified id> comment create
    <merge patch> | qits work --entity <id or qualified id> comment update --comment <id>
    qits ideas list --project <project> [--archived] [--status PLANNED|QUEUED|DONE]
    qits ideas --entity <idea id> details
    <payload> | qits ideas create
    <merge patch> | qits ideas --entity <idea id> update
    qits ideas --entity <idea id> archive --reason <text>
    qits ideas --entity <idea id> unarchive
    <payload> | qits ideas --entity <idea id> spawn --archetype EPIC|TICKET
    qits release-request --project <project> [--repository <repository>] list \
        [--state <STATE|all>] [--limit <n>]
    qits release-request --project <project> --repository <repository> create \
        --branch <branch> [--priority <priority>]
    qits release-request --project <project> --repository <repository> join \
        --request <id> --branch <branch> [--priority <priority>]
    qits release-request --project <project> --repository <repository> move \
        --request <id> --branch <branch> --reason <text>
    qits release-request --project <project> --repository <repository> withdraw \
        --request <id> [--reason <text>]
    qits release-request --project <project> approve|decline \
        --request <id> [--sha <merged sha>] [--note <text>]
    qits release-request --project <project> --repository <repository> regenerate \
        --kind <kind> [--request <id>]
    qits release-request --project <project> --repository <repository> rerun \
        --request <id> --phase QA|PUBLISH|DEPLOY
    qits ci runs --project <project> --repository <repository> [--branch <branch>] \
        [--status <STATUS>] [--release-request <id>] [--limit <n>]
    qits ci run <run id> [--logs] [--project <project> --repository <repository>]
    qits ci retry <run id> [--project <project> --repository <repository>]
    qits ci cancel <run id> [--reason <text>] [--project <project> --repository <repository>]
    qits ci report show <run id> [--kind <kind>] [--project <project> --repository <repository>]
    qits ci report submit --exit-code <n> [--root <dir>]          (in a CI step only)
    qits users invite
    qits users register --token <token> <username>
    qits users list
    qits users delete <username>
    qits remembered-devices list
    qits remembered-devices revoke --device <id>
    qits events [--filter=<names>]
    qits events query [--filter=<names>] [--since <time>] [--until <time>] [--limit <n>] [-o json]
    qits observe --filter <conditions> [--filter <conditions> …] [-o json]
    qits observe query --filter <conditions> [--filter <conditions> …] [--source <key>] \
        [--since <time>] [--until <time>] [--limit <n>] [-o json]
    qits checkout-daemon [--path <dir>] [--repository <name>] [--once] [--no-submodules]
    qits checkout sync [--path <dir>]
    qits wait --event <Name> [--when <conditions> …] [--timeout <duration>] [--label <text>]
    qits wait branch <branch> --until joined|released|live|failed [--repository <name>] \
        [--project <project>] [--timeout <duration>] [--label <text>]
    qits wait release-request <id> [--timeout <duration>] [--label <text>]
    qits wait work <qualified id> --status <STATUS> [--timeout <duration>] [--label <text>]
    qits wait ci-run <run id> [--timeout <duration>] [--label <text>]
    qits wait list [--state OPEN|MATCHED|TIMED_OUT|BROKEN|CANCELLED]
    qits wait cancel <wait id>
    qits workspaces list --repository <id|name> [--project <project>] | show <n> | active-process <n> | items | history <work>
    qits workspaces start|stop|recreate <n>
    qits workspaces log <process id>
    qits workspaces attach <command id> --workspace <n>
    qits workspaces agents refine --workspace <n> [--preamble <text>] <notes>
    qits workspaces agents sign-in status --workspace <n>
    qits workspaces agents sign-in open --workspace <n> [--harness CLAUDE|KIMI] [--attach]
    qits workspaces agents plugins list --workspace <n>
    qits workspaces agents plugins install <plugin> --workspace <n>
    qits agents list [--project <project>] [--work <work>] [--workspace <n>] [--state ACTIVE|REMOVED]
    qits agents show|discard [--force] <agent> | --work <work>
    qits agents prompt <agent> <text> [--compact-first] | --work <work> <text>
    qits agents interrupt|stop|restart|yield <agent> | --work <work>
    qits agents sessions <agent> | --work <work>
    qits agents transcript <agent> <session> | --work <work> <session>
    qits agents chat <agent> | --work <work>
    qits agents attach <agent> | --work <work>
    qits agents files list|read|detect <agent> [--path <dir>] | --work <work>
    qits agents draft show|save|discard <agent> | --work <work>
    qits targets list | show --target <name|id>
    qits targets create --name <name> [--description <text>] [--reserved-ci <n>] [--reserved-workspace <n>] \
        [--reserved-desk <n>]
    qits targets rotate-token --target <name|id>
    qits targets update --target <name|id> [--reserved-ci <n>] [--reserved-workspace <n>] [--reserved-desk <n>] \
        [--description <text>]
    qits targets greenlight|healthcheck|login-check|delete --target <name|id>
    qits targets leases [--target <name|id>] [--type ci|workspace|desk] [--state <state>]
    qits targets leases show <lease id>
    qits targets leases end <lease id> [--reason <text>]
    qits targets sign-in --target <name|id> [--harness claude|kimi] [--timeout <duration>]
      [--sign-in <id>] [--code <code>]

They call the platform through its edge over HTTPS, with the access token from `qits login` as a
bearer. The options of `projects`, `repositories`, `work` and `release-request` may come before or
after the subcommand: `qits repositories --project qits list` and `qits repositories list --project qits`
are the same.

### The session

Each command reads `t.json`. With no file it prints `Not signed in — run `qits login`.` and exits
with 2.

With `qits session-daemon` running, the commands only read the file. Without it, a command whose
access token has less than 30 seconds left refreshes it first, the way the daemon does: under
`t.json.lock`, after reading the file again. If the daemon or another command refreshed in the
meantime, the command uses that pair, so a refresh token is never spent twice. A refresh the idp
refuses prints `Session ended — run `qits login`.` and exits with 2.

### Which address

A service lives at `<app>.qits.<domain>`. The commands take the session's idp address and swap
its first label: `https://idp.qits.<QITS_DOMAIN>/idp` gives `https://projects.qits.<QITS_DOMAIN>`,
`https://ci.qits.<QITS_DOMAIN>`, `https://events.qits.<QITS_DOMAIN>` and
`https://observability.qits.<QITS_DOMAIN>`. To name the address yourself (a base URL, without
`/projects`, `/ci`, `/events` or `/observability`):

- projects: `--projects-url`, else `QITS_PROJECTS_URL`
- ci: `--ci-url`, else `QITS_CI_URL`
- events: `--events-url`, else `QITS_EVENTS_URL`
- observability: `--observability-url`, else `QITS_OBSERVABILITY_URL`. The stream is a WebSocket,
  so `https` becomes `wss` and `http` becomes `ws`.
- workspaces (`qits wait`, `qits workspaces`): `--workspaces-url`, else `QITS_WORKSPACES_URL`

`qits checkout-daemon` reads the events service the same way, with `--events-url` or
`QITS_EVENTS_URL`; the git host it fetches from is the checkout's own `origin`, never a flag.

Inside the platform the same vhosts are dialled, with the domain read from `QITS_DOMAIN` rather
than off a session: `https://<app>.qits.<QITS_DOMAIN>`, in the token home and in-platform alike. No
home dials a wire alias (qits-1436). The flags and variables above still come first in either home
(see *qits from inside the platform*).

### Output, errors and exit codes

`--output table` (the default) prints aligned columns. `--output json` (or `-o json`) prints the
service's answer, pretty-printed. The default `release-request list` of one repository leaves out
the finalized requests in both forms, and `--limit` cuts both forms (see below).

When the platform refuses:

- HTTP 401: `The platform refused the token (HTTP 401: <error>)`, with the error from the
  `WWW-Authenticate` header when there is one.
- HTTP 403: `Your roles do not allow this (HTTP 403)`. These calls need `qits:admin` or
  `qits:system`.
- Any other status: the method, the address, the status, and the service's own message.

Exit codes: 0 done; 1 the platform refused, or cannot be reached; 2 the command was used wrongly,
there is no session, or the session ended.

### qits projects list

Columns: slug, name, id.

### qits repositories --project \<project\> list

`--project` is the project's id, slug or name. The command lists the projects and finds the one
that matches; a value that matches none, or more than one, stops with a message. Columns: name,
archetype, component, id.

### qits repositories --project \<project\> create \<name\> [--component \<component\>] [--archetype \<archetype\>]

Creates a blank repository on the platform's git host, seeded with the repository template, and
writes the entry that mounts it in the project's wrapper. `--project` is as above.

`<name>`'s role suffix (`qits-payments-daemon` ends in `-daemon`) tells a reader what kind of
component the repository is; it decides nothing (owner ruling 2026-11-03). A kind is stated with
`--archetype` (`SERVICE`, `DAEMON`, `LIBRARY`, `FRONTEND`, `APP`, `CLI` or `IMAGE`, any case; sent
upper-cased, anything else left for the service to refuse), or by the repository's own
`.config/qits/repository.yml` (`repository: { archetype: DAEMON }`), which a wrapper reconcile
applies once it releases. With neither, the row is created with no archetype at all: the field is
simply omitted from the request rather than guessed from the name. Name the repository
`<component>[-<modifier>]-<role>` all the same, so a reader still sees the role: `-service`,
`-frontend`, `-app`, `-daemon`, `-oci`, `-cli`, `-javalib` and `-jslib`.

`--component` places the wrapper entry at `components/<component>/<name>`; without it the wrapper's
own layout decides.

The command prints what the service answered: the name, the archetype (if any was declared), the
component, the id and the wrapper path the entry was written at — the last being the proof the
wrapper entry exists. `-o json` prints the answer as it came.

A refusal gets a sentence that says what to do next, with exit code 1. A 403 says the door takes
`qits:admin` or `qits:agent`, so a refusal on an agent credential means the role has not reached the
deployed service yet. Any other refusal is left as the service worded it.

    qits repositories --project qits create qits-docs-app --component qits-docs --archetype app

### qits repositories --project \<project\> remote-login [--repository \<repository\>]

The project page's "Sign in to backup remote" (qits-1268): this terminal attached to a repository's
backup-remote sign-in, `WS /projects/api/repositories/{id}/remote-login` on qits-projects, with the
caller's own bearer. The server runs an interactive `git push` to the backup remote; git's username
and password prompts come here, and what is answered fills the credential store every backup of the
project pushes with, so one sign-in clears every `AUTH_REQUIRED`. The door takes `qits:admin` or
`qits:admin-agent`, a person's token; `qits:agent` is refused.

- Without `--repository` it is the **project repository**, the list's `wrapper.repositoryId`, as the
  page's Backups panel picks it: what the sign-in authenticates is the host, not the repository.
  `--repository` takes a name or an id. A repository with no `backupUrl` stops with exit 1 before
  anything is opened, since there is nothing to sign in to.
- The terminal is `qits workspaces attach`'s (the same client, `access/terminal/`): raw mode, the
  size on open and on every change, `{"type":"data"…}` and `{"type":"resize"…}` out, raw text in.
  **Ctrl-] detaches**; the server keeps the sign-in about a minute, and running the command again
  attaches to it and replays what the terminal held. Any close but a clean one attaches again after
  0.5, 1, 2, 4 and 4 s. Without a terminal on stdin it only streams, read-only.
- **After a clean close** (git exited, or the server wrote a refusal such as a busy repository and
  closed) it does what the panel does: a close says the terminal ended, not that the sign-in worked,
  so it asks for every backup of the project to run again (`POST
  /projects/api/projects/{id}/repositories/backup-sync`, which answers how many were scheduled),
  waits 3 s, reads the repositories once more and prints each one's last backup. The pushes run in
  the background, so a row may still read as before; `qits repositories list -o json` asks again.
  Nothing follows a detach or a stop.
- It is `Interaction.TERMINAL`, so the MCP service does not serve it.

    qits repositories --project qits remote-login
    qits repositories --project qits remote-login --repository qits-ci-service

### qits repositories --project \<project\> backup reset --repository \<repository\> --reason \<text\>

The fix for a backup twin the forge seeded with a commit of its own (qits-1885): its main shares no
history with ours, so every ordinary backup push is refused. `POST
/projects/api/repositories/{id}/backup-reset` on qits-projects, body `{"reason"}`, asks the service to
probe the twin's main and decide. A twin with no main, at ours or behind ours is fast-forwarded with
no force (`FAST_FORWARD`); one whose main shares no commit with ours has its main replaced
(`UNRELATED_RESET`; only main is ever forced); one ahead of ours or diverged from a common base is
refused with 409 naming both shas, and nothing is pushed. The reset is recorded with who decided
and the reason.

- Only a person (`qits:admin`, verified as a person) or the project's own delivery desk may call
  it: the decision is that the forge's copy of main may be discarded. The door words its own 403,
  so the command prints it as it came, with no role hint of its own.
- The table prints the repository, the kind, the twin's main before and after, who decided and the
  reason; `-o json` prints the service's answer.

    qits repositories --project qits backup reset --repository qits-ci-service --reason "Forge seed commit"

### qits work

`qits work` is every work item of a project, whatever its archetype: `EPIC`, `TICKET`, `FEATURE`,
`TASK` or `ISSUE`. One item is named once with `--entity`: its id or its qualified id
(`qits-100`), which the service resolves. The group carries no route per archetype and no field of
any: it calls the service's archetype-agnostic doors, and every payload schema it shows is served by
the service per archetype and door, built from the same table the service validates against.

Every archetype walks one lifecycle: `REPORTED` (somebody said what is wrong or could be
better), `REFINED` (it says what to do), `IMPLEMENTING` (an epic, ticket, feature or task is being
worked), `IMPLEMENTED` (released and deployed, not merely merged),
`VERIFYING` (the platform's own verify dispatch was pressed),
`VERIFIED` (somebody checked the platform) and `DONE` (a person's call), with `DROPPED` as the exit
for work a decision was taken not to do, carrying an `outcome` that says why (see `status` below). A SKIP transition lets `REFINED` move straight to
`IMPLEMENTED`, bypassing `IMPLEMENTING`, and `IMPLEMENTED` move straight to `VERIFIED`, bypassing
`VERIFYING`. Features and tasks walk the same eight words as epics and tickets. A ticket
is also blocked or not: the phase its status belongs to cannot proceed.

Reading takes `qits:admin` or `qits:agent`, and so does writing, an agent in its own project only.
An epic's status move takes `qits:admin`: an agent is answered HTTP 403, and the command prints the
service's sentence as it came.

**Every call is on the service's `/work` family, by qualified id.** `--entity` (and `--parent`, and
every id inside a payload) goes to the service as it was given, a qualified id like `qits-100` or an
id: the service resolves either, so the CLI never looks an item up to learn its UUID.

**Reads.** `list --project <p>` shows a project's items in the service's order (`GET
/projects/api/projects/{p}/work`): qualified id, archetype, status, a `BLOCKED` column when a
ticket is blocked, title and when it last changed; a dropped item's status reads `DROPPED
(DUPLICATE)`. `--archetype`, `--status`, `--parent` and `--outcome` narrow it; all but `--parent`
are case-insensitive, and `--parent` takes a qualified id or an id. `--outcome` is sent as
`?outcome=` and applied to the answer again, because a service older than qits-248 ignores the
parameter. `details` shows one item (`GET /projects/api/work/{entity}`): its fields (the outcome
beside a dropped item's status, and its `supersededBy` labelled `duplicate of`, `resolved by` or
`superseded by`), its description, its comment thread (`…/comments`), its children (`…/children`:
an epic's features, a feature's tasks) and what it closes: the items naming it as their
`supersededBy`, read from `GET /projects/api/projects/{projectId}/work?supersededBy={id}` and
filtered again for the same reason. When that read is refused, stderr says so and the item is still
printed. `-o json` prints `{"entity": …, "comments": […], "children": […], "supersedes": […]}`.

**What an item needs** (epic qits-1899). An epic, a ticket, a feature or a task can need other items
of its project, each at a status: `needs` on a work read is a list of `{id, qualifiedId, title,
archetype, status, currentStatus, met}`, and `unmetNeeds` counts the ones not met. A need is met once
the needed item is at or past the status on the walk; `DROPPED` meets only a need for `DROPPED`.
`list` adds a `NEEDS` column when some item has an unmet need, naming each as `<qualified
id>@<status>` (`qits-1897@VERIFIED`; `-` for an item whose needs are all met, only the count when an
answer names none), and `details` a `needs` line per unmet need, `qits-1897 at VERIFIED (now
REFINED)`, and a `needs met` line per met one. The word is "needs" on purpose: "waiting" and
"waiting on" are an agent's (the `AGENT_WAITING` block, a declared `qits wait`). `create` and
`update` write the whole list, `{"needs":[{"item":"qits-1897","status":"VERIFIED"}]}` (the item by
qualified id or id; `[]` or `null` clears it); `dependsOn` is retired, and the service answers a
write of it with HTTP 400. The service holds an item with an unmet need at `REFINED` and
`READY_FOR_DEV`: dispatch refuses it with HTTP 409.

**Writes read a JSON payload on stdin.** A payload that is not one JSON object is a usage error
(exit code 2), and nothing is sent. With nothing put in - stdin a terminal, or empty, as with
`</dev/null` or in `qits tui` - the command sends nothing, prints its usage and then the payload's
JSON schema with its required fields named and its source, and exits with 0. `-o json` prints the
bare schema.

- `create --archetype <A>` sets `archetype` on the payload and sends it to `POST
  /projects/api/work`. The schema is `GET /projects/api/work/archetypes/{A}/schemas/create`:
  a root item names its `project` (id or slug), a child its `parent`. A payload whose own
  `archetype` disagrees with `--archetype` is a usage error.
- `update` sends the payload unchanged as a JSON merge patch to `PATCH /projects/api/work/{entity}`
  (`application/merge-patch+json`): left out is unchanged, `null` clears. With nothing put in it
  looks the item up for its archetype and prints that archetype's `update` schema.
- `transition --archetype <B>` reshapes the item into another archetype. The door (`POST
  /projects/api/work/transition`) is full-state: what the request leaves out is cleared. So the
  command starts from the item as it stands (`title`, `description`, `status`, `outcome`, `ticketType`,
  `impetus`, `assignee`, `supersededBy`, `repositoryId`, `implementingAt`, `implementedAt`, and
  `membership {parent, position}` when it has a parent), merges the payload over it (RFC 7396),
  sets `archetype`, and drops every property B's `transition` schema has no slot for, naming them
  on stderr (`impetus, ticketType have no slot on EPIC and are not carried.`). `{}` carries the item
  over as it is. The request is keyed by `--entity` as it was given, and so is the service's answer;
  `membership.parent` and `supersededBy` take a qualified id as well; `needs`, like `tags`, is no
  part of this door. With nothing put in, it prints B's schema and names the required properties the
  item does not carry yet (`membership.parent` for a ticket becoming a feature) and the ones that
  would be dropped.
- `status` sends `{"target": "<STATUS>"}` unchanged to `POST /projects/api/work/{entity}/status`.
  With nothing put in, it prints a schema whose `target` enum is the moves the service's archetype
  registry (`GET /projects/api/work/archetypes`) opens from the item's current status. An item
  on a service whose registry has not yet given its archetype a lifecycle is a usage error. A move
  the lifecycle does not allow is HTTP 409.
  A move into `DROPPED` says why in `outcome` (qits-248): `DUPLICATE`, `RESOLVED_ELSEWHERE` or
  `SUPERSEDED`, each of which also names the other item in `supersededBy` (a qualified id or an
  id), or `OBSOLETE`, `INVALID` or `WONT_DO`, which take none; any other move takes neither. The
  service answers each breach with HTTP 400. When `DROPPED` is an open move, the printed schema
  carries both properties and the rules as `if`/`then` conditionals. Work that is hard or stale is
  blocked, not dropped.
- `comment create` sends the payload (`{"body": "..."}`, Markdown) to `POST
  /projects/api/work/{entity}/comments`. `comment update --comment <id>` sends the payload as a JSON
  merge patch to `PATCH /projects/api/work/{entity}/comments/{id}`: the path names the item and the
  comment together, so a comment that is not on the item's thread is the service's HTTP 404. The author is the caller and an edit
  leaves it as it was; deleting a comment takes `qits:admin` and has no command here. Their schemas
  come from the service's OpenAPI document (`/projects/q/openapi?format=json`), from the operation's
  request body with every `$ref` resolved.

`transition` and `status` read the item first (`GET /projects/api/work/{entity}`), for its current
state and its archetype's lifecycle; neither turns the qualified id into a UUID. The service's 400,
403, 404 and 409 answers are printed with the service's own sentence, exit code 1.

The table prints the item's qualified id, archetype, status, title and time; `-o json` prints the
service's answer. An item's text is written by people and agents, so, as for `qits ci`, the table
form takes terminal control characters out of every value, and `-o json` writes them as escapes.

    qits work list --project qits --archetype epic --status REFINED
    qits work --entity qits-100 details
    qits work create --archetype ticket </dev/null
    echo '{"project":"qits","title":"The log view stops at 64 KiB","ticketType":"BUG","impetus":"A long run is cut."}' \
        | qits work create --archetype ticket
    echo '{"title":"The log view stops at 64 KiB"}' | qits work --entity qits-100 update
    qits work --entity qits-100 transition --archetype epic </dev/null
    echo '{"target":"DROPPED","outcome":"DUPLICATE","supersededBy":"qits-90"}' | qits work --entity qits-100 status
    qits work list --project qits --status DROPPED --outcome obsolete
    echo '{"body":"I can reproduce it."}' | qits work --entity qits-100 comment create
    echo '{"body":"Fixed."}' | qits work --entity qits-100 comment update --comment <comment id>

### qits ideas

`qits ideas` is a project's ideas (epic qits-888), on the service's `/projects/api/ideas` doors: short,
titled notes that an epic or a ticket names as its `inspiredBy`. An idea is not a work
archetype: it has no lifecycle a caller moves, no thread, no children and no delete. Its `status`
(`PLANNED`, `QUEUED`, `DONE`) is derived by the service from the work naming it, and it is archived
with a reason instead of deleted (`archive --reason`, `unarchive`). `list` leaves the archived ones
out unless `--archived` adds them. `details` prints the idea, the idea enabling it (with a `hint:`
line when that one is archived), the ideas it enables and the work it inspired. `create` and
`update` take their payload on stdin as `qits work` does, and with none print the request body's
schema from the service's OpenAPI document. `spawn` reads the idea and files an epic or a ticket
through `POST /projects/api/work`, the door `qits work create` sends to, with `project`, `title`,
`description` and `inspiredBy` filled in from the idea and the JSON object on stdin merged over them
(a ticket's `ticketType` and `impetus` go there); with nothing on stdin it prints what the idea fills
in, what is still missing and the archetype's create schema. `qits work create` and `update` send an
`inspiredBy` in their payload as it is, and `qits work details` prints it as `inspired by`.

    qits ideas list --project qits
    echo '{"project":"qits","title":"Faster builds"}' | qits ideas create
    qits ideas --entity <idea id> details
    qits ideas --entity <idea id> archive --reason 'Folded into the cache epic.'
    echo '{}' | qits ideas --entity <idea id> spawn --archetype epic

### qits release-request

`--project` as above. `--repository` is the repository's id or name within that project. `list`,
`approve` and `decline` work without it.

A request folds main and its branches into one commit, and the builds of that commit are its gate.
States: PENDING (waiting for its builds), READY, RELEASED (the tag is cut, waiting on its remaining
gates), FINALIZED (the tag is merged into main, done), REJECTED (a gating build was red), FAILED
(the release itself failed), CONFLICTED (the branches do not merge), WITHDRAWN, OBSOLETE (a later
request for the repository superseded this one before it finished). RELEASED is open, not final:
the request still waits on its remaining gates, one of which (PUBLISH) is the tag's own release
pipeline.

`list` without `--repository` shows the requests of every repository of the project, as the landing
app does: the service's default answer, which is the open requests and the last 10 finalized ones,
most recently moved first. Columns: id, repository, state, approval, priority, items, version,
updated.

`list --repository <repository>` shows that repository's open requests: every state but FINALIZED,
WITHDRAWN and OBSOLETE. The service's default answer holds the open requests and the last 10
finalized ones; the command drops the finalized. Columns: id, state, priority, items, version,
updated.

The items column is what the request releases: its items' labels, joined with `; `. The platform
makes one item per branch somebody named (main never is one), labelled with the work item the
branch names where it names one. A request has no summary any more: the platform derives what used
to be one from the items, and the CLI no longer reads it (qits-1673). A request that holds only
main, or one from a qits-projects older than items, shows its branches instead.

In both forms `--state all` shows every request, and `--state PENDING` (or READY, RELEASED,
FINALIZED, REJECTED, FAILED, CONFLICTED, WITHDRAWN, OBSOLETE) shows one state. `--limit <n>` shows
the first n; the service has no limit, so the command cuts the list it got.

A request's id is `<repository>-rr-<n>` (`qits-ci-service-rr-12`), numbered per repository and fixed
when the request is made: a rename of the repository does not change it. The request also has a
uuid, its internal key, which `-o json` shows as `id` beside `qualifiedId`. Every command that takes
a request takes either, and the start of a uuid too. A request from a qits-projects older than this
has no logical id; the commands then show the uuid's first 8 characters, as before.

`create` asks for a branch to be released once its builds are green. `--branch` is required;
`--priority` is LOWEST, LOW, MEDIUM, HIGH, HIGHER or BLOCKING (the platform's default is MEDIUM).
There is no `--summary`: the platform names a request by its items, and a `--summary` given is
refused as an unknown option (exit code 2). The platform may answer with a new request, with the
open request that already holds the branch, or (on a project wrapper) with the open request the
branch joined. The command prints the request that came back: its participants (everyone who has
asked for it, never fewer as it goes), its items (one line each: the work item's id and title where
the branch names one, else the item's label), its gates and its sources.

`join` adds a branch to an open request. `--request` is the request's id as `list` shows it (any
case), its uuid, or enough of the uuid's start to name one. The command looks among all the
repository's requests, of every state; a value that fits none, or a uuid start that fits more than
one, stops with exit code 2 and names what it found. `--branch` is required; `--priority` is as for `create`. The platform folds the
request again with the branch and, if that makes a new commit, its builds run on that commit. The
command prints the request that came back, with its sources, like `create`.

- A branch already on the request adds nothing. With `--priority` it states that priority again;
  without, the branch keeps the priority it has.
- A RELEASED, FINALIZED, WITHDRAWN or OBSOLETE request takes no more branches (HTTP 409, exit code
  1). Open a new one with `create`.
- HTTP 404 means the platform has no such request (exit code 1). A branch the git host does not
  have is not a 404: the fold fails, and the request's detail says why.

`move` takes one branch off an open request and puts it on the request queued after it, opened
queued behind it if there is none (qits-1665). It is for a branch that alone holds a shared request
back: it conflicts with the others, its check is red, or it is not ready. Use it instead of
force-pushing the branch back to main or holding the other branches. `--request` is found as for
`join`; `--branch` and `--reason` are required, and both requests list the move with who made it
and why. The request folds again without the branch and goes back to PENDING; the branch keeps its
priority, author and feature stamp, and releases from the queued request once this one has released
or been withdrawn. The table form prints `<branch> moved from <id> to <id>` and then the request
that now holds the branch, like `create`; `-o json` prints both (`from`, `to`). A request's table
lists the moves into or out of it under `Moves:`.

- qits:admin, qits:admin-agent and the project's delivery desk may move any branch; an agent only
  its own (one its token may push). Another's is HTTP 403, exit code 1.
- HTTP 409 (exit code 1): the request is RELEASED, FINALIZED, WITHDRAWN or OBSOLETE, or itself
  queued; the branch is the default branch or a released tag; or it is the request's only branch,
  which `withdraw` is for. HTTP 404: no such request, or the branch is not on it. A blank reason is
  HTTP 400, exit code 2.

`withdraw` withdraws an open request, so it does not ship. `--request` is found as for `join`.
`--reason` is a sentence the request then shows as its detail; without it, the platform writes who
withdrew it. WITHDRAWN is final: the request is not built or released again, and the next `create`
for one of its branches opens a new request. The command prints the request that came back, like
`create`.

- A RELEASED, FINALIZED, WITHDRAWN or OBSOLETE request cannot be withdrawn (HTTP 409, exit code 1).
- HTTP 404 means the platform has no such request (exit code 1).

`approve` and `decline` are a person's decision on the fold a request is on: its merged sha. Both
find the request in the project (`--request` is its id, in any case, or its whole uuid; no
`--repository`), and both post the fold they judge: `--sha` when given, which is the strict form,
else the merged sha the command has just read, so without `--sha` the command judges whatever fold
the request is on at that moment. A request with no fold yet is refused before anything is sent
(exit code 1). `--note` goes on the record; a decline's note becomes the request's detail. The table
form prints `Approved <id> at <sha>.` (or `Declined …`) and then the request, like `create`; `-o
json` is the service's answer as it came.

- A decision names one fold. A push that folds the request again leaves it behind, and the new fold
  waits for a decision of its own.
- `decline` is not `withdraw`: a declined request goes REJECTED and comes back with the next push to
  one of its branches; WITHDRAWN is final.
- Only a person's own `qits` CLI sign-in holding qits:admin is accepted. Agents, CI and service
  clients are refused by the platform (HTTP 403). Every refusal (400, 403, 404, 409: a stale fold
  names the one the request is on now) is printed in the service's own words, exit code 1.

`regenerate` runs one release output again on an open request: `--kind` is `entity-diagram`,
`entity-exposure` (qits-1958), `screenshot-baselines`, `dependency-bump`, `estate-pins` (qits-1182), `golden-recordings-maven` or
`golden-recordings-npm` (qits-1516). QA checks the entity diagram
and the screenshot baselines and fails when they are stale, but does not regenerate them; this is
how a person or an agent asks for that. Without `--request` it takes the repository's one open
request (a REJECTED one counts), and refuses when there is none or more than one. The automation
commits the output to `maintenance/automations/<kind>/<request id>`, the request folds again with
it, and QA runs again. It prints the run's id; `qits maintenance bump <run id>` shows how it went.

- HTTP 409 means a run of that kind is already active for the request, the request is not open or
  has no fold, or bumping is off. HTTP 404 means no such kind or request. HTTP 503 means
  qits-maintenance cannot be reached and nothing ran. HTTP 504 means it was asked and did not answer
  in time; whether it ran is unknown, so check `qits maintenance automations --request <id>` before
  pressing again. Each is exit code 1, with a sentence.

`rerun` reruns one phase of a request's pipeline. `--request` is found as for `join`; `--phase` is
`QA`, `PUBLISH` or `DEPLOY`, in any case. QA and PUBLISH re-fire that phase's CI run; DEPLOY
redeploys the request's already-released version, the remedy for a deployment that got rolled back.
The request's own state does not change here: the rerun's result arrives later, on the event bus,
the same way the first run's did. The command prints the request as it stands now, which still
shows the previous run's outcome.

- HTTP 400 means the phase is none of QA, PUBLISH or DEPLOY. HTTP 404 means no such request. HTTP
  409 means the platform, or qits-ci for a QA or PUBLISH rerun, refuses to run that phase now; the
  message says why. HTTP 503 means qits-ci cannot be reached, or this repository is not set up with
  it, and nothing ran. Each is exit code 1, with a sentence.

    qits release-request --project qits list
    qits release-request --project qits list --state all --limit 20
    qits release-request --project qits --repository qits-ci-service list
    qits release-request --project qits --repository qits-ci-service list --state all -o json
    qits release-request --project qits --repository qits-ci-service create \
        --branch feature/log-view --priority HIGH
    qits release-request --project qits --repository qits-ci-service join \
        --request qits-ci-service-rr-12 --branch feature/log-search
    qits release-request --project qits --repository qits-ci-service withdraw \
        --request qits-ci-service-rr-12 --reason "The log view moves to qits-observability"
    qits release-request --project qits approve --request qits-ci-service-rr-221 \
        --sha 3f9c2e1a7b8d4c6e5f0a1b2c3d4e5f6a7b8c9d0e
    qits release-request --project qits decline --request qits-ci-service-rr-221 \
        --note "The README promises an approval policy nobody has signed off yet."
    qits release-request --project qits --repository qits-landing-app regenerate \
        --kind screenshot-baselines
    qits release-request --project qits --repository qits-ci-service rerun \
        --request qits-ci-service-rr-12 --phase QA

A red gating build that was the platform's fault and not the code's (a flaked container, a registry
that was down) is retried with `qits ci retry`, not with a new request and not with `withdraw`.
`withdraw` is only for a request that must not ship.

### qits ci

The builds of qits-ci. `--project` and `--repository` are as for `release-request`, and may come
before or after the command. Reading runs needs the role `qits:admin` or `qits:system`; `retry`
needs `qits:admin`.

`runs` lists a repository's runs, newest first. Columns: id (the first 8 characters), status,
branch, commit, release request, created, and how long the run took (so far, while it runs). The
service filters by repository and count only. So `--branch`, `--status` and `--release-request` are
applied here, over all of the repository's runs, and `--limit` (default 20) after them. Without a
filter, `--limit` goes to the service.

A release request's gating runs build its backing branch `release/<request id>` and carry the
request's id and uuid. `--release-request` takes the id as `qits release-request list` shows it
(`qits-ci-service-rr-12`), the uuid, or the uuid's start. The release request column shows the id
(the uuid's first 8 characters for a run from an older qits-ci).

`run` shows one run: what it built, its status, trigger and times, and its steps with their exit
codes. `--logs` adds each step's output. The service keeps the end of each step's output, and for a
running step, what it has printed so far; there is no live stream, so run the command again to
follow a run. The output is written by the code the run builds, so the command takes terminal
control characters out of every line, as `qits observe` does, and `-o json` writes them as escapes.
The exit code says whether the read worked, not whether the run passed.

`retry` runs a finished run again: the same commit, pipeline and release request. It prints the new
run's id and how to follow it. A run that has not finished yet answers HTTP 409, and an unknown run
HTTP 404; both exit with 1.

`run` and `retry` take the whole run id. With `--project` and `--repository` they also take the
start of it, looked up among that repository's runs.

    qits ci runs --project qits --repository qits-ci-service --release-request qits-ci-service-rr-12
    qits ci run 5f2c0a9e --project qits --repository qits-ci-service --logs
    qits ci retry 5f2c0a9e --project qits --repository qits-ci-service

`report show` lists a run's release reports: the structured results a release request's QA run
produces beside its verdict, one per kind and step, with their highlights ("3 tests failed").
`--kind` also prints that kind's whole report as JSON, and `-o json` prints the service's answer.
Today's kinds are `test-results`: the totals, a line per tool and module, and every failing test with
its language, tool, file, class, name, shape (`ASSERTION`, `ERROR`, `TIMEOUT`, `SETUP`) and message;
and `coverage`: the total line coverage and the baseline's, the diff coverage (the coverable lines the
change added or touched against the baseline's tag, how many of them are covered, and the uncovered
ones as line ranges per file), and a line per file; and `contracts`: the pacts the repository holds as
consumer (`pacts/*.json`), the pacts it verified as provider (Pact-JVM's JSON verification report), and
the provider states its golden-master index declares, each interaction without its bodies but with a
`contentHash`. Its highlights name the pacts, interactions and provider states added or removed since
the baseline; `ContractChanges` holds the rules, and the fixtures under
`platform-access-commands/src/test/resources/report/contracts/diff/` pin them for the view in
`@qits/ui-components`, which copies them verbatim. And `entity-changes`: the generated entity diagrams
under `docs/database/` (see `qits database diagram` below) compared with the same files at the
baseline's tag, read back with `MermaidEntityDiagramFormat`: per unit (file) its status (`ADDED`,
`REMOVED`, `CHANGED`, `UNCHANGED`, or `CURRENT` when there is no baseline or the baseline had no
diagram), the tables added, removed or changed with their columns (`"<type>, <null|not null>[, PK][,
FK][, UK][, <length>]"`, then `lob`, `version`, `generated`), the relations added and removed, and the
Mermaid text before (changed and removed units) and after. Above 1 MiB the `after` text of unchanged
units goes first, then that of current ones, and `truncated` says so. Its highlights count the tables
added, changed and removed, and the columns removed or narrowed (not null gained, another type, a
shorter length). And `stale-outputs` (qits-1920): the `check-outputs:` kinds the step found stale, read from
the file `QITS_CI_STALE_OUTPUTS` names (one `<kind>\t<regenerate command>` line each, as qits-ci's
output check writes it), with one `bad` highlight per kind (`stale outputs: <kind>`; the regenerate
command is in the payload, too long for a highlight); no file, or
no line in it, is not reported, and there is no baseline comparison. And `golden-master-coverage`
(qits-1940): the operations of a java service's OpenAPI document that no golden master records, read
from the file `QITS_CI_UNCOVERED_OPERATIONS` names (one `<name>\t<METHOD>\t<path>` line each, as `qits
contracts coverage --out` writes it); the payload is `{"uncovered": [{name, method, path}]}`. Unlike
stale-outputs, an empty file IS reported, with nothing uncovered: the command writes the file on every
run it completes, so empty is a clean pass. No variable or no file is not reported. qits-projects
reads only the highlights, so their texts are fixed: `uncovered operations: <n>` first (`bad`, or
`good` at 0), then `uncovered: <name>` (`bad`) for each of the first nine, ten in all at most.

`report submit` is what the hook qits-ci composes after every QA step runs, whatever the step's
script exited with (`--exit-code`). It reads the step's files (`**/target/surefire-reports`,
`**/target/failsafe-reports`, `.qits-reports/vitest-*.xml` for tests; `.qits-reports/jacoco.exec`
read against every `**/target/classes`, and `coverage/**/coverage-final.json` or
`.qits-reports/coverage/**/coverage-final.json` for coverage; `pacts/*.json`,
`.qits-reports/pact-verification/*.json` and `golden-masters/index.json` for contracts, only in a step
where test results were found; the generated `docs/database/*.md` for entity-changes, in step 0
only), compares with the baseline (the same
kind in the QA run of the release request that produced the newest released version), and PUTs one
report per kind to `https://ci.qits.$QITS_DOMAIN`, with the publish chain's bearer. It takes the run
from `QITS_CI_RUN_ID`, `QITS_CI_STEP_INDEX`, `QITS_CI_SHA`, `QITS_CI_REPO_NAME` and
`QITS_CI_PROJECT_ID`, gives up after 120 seconds, and never changes the step's verdict: the hook
ignores its exit code. A kind is one implementation in `report/`, listed in `ReportKinds`.
With a baseline, the coverage and entity-changes kinds fetch the baseline's tag into the step's shallow clone
(`git fetch --depth=1 "$QITS_CI_REPOSITORY_URL" refs/tags/<version>:refs/tags/<version>`, with the
step's own git credentials), once for both: coverage diffs it against `HEAD`, entity-changes reads
`docs/database/` there. When git cannot, the diff coverage is left out and the entity diagram reads as
new, with one warning, never wrong.

    qits ci report show 5f2c0a9e --project qits --repository qits-ci-service --kind test-results
    qits ci report show 5f2c0a9e --project qits --repository qits-ci-service --kind coverage
    qits ci report show 5f2c0a9e --project qits --repository qits-landing-app --kind contracts
    qits ci report show 5f2c0a9e --project qits --repository qits-ci-service --kind entity-changes

### qits database diagram

    qits database diagram [--root <dir>] [--out <dir>] [--check]

The entity diagram of a maven repository's JPA mapping (epic qits-760): one Markdown file with a
Mermaid `erDiagram` per persistence unit, under `docs/database/`, generated from the **compiled**
classes. The entity-diagram release-request automation runs it on every fold; a person runs it the
same way, after building the inputs:

    ./mvnw -q -Dmaven.test.skip=true test-compile dependency:build-classpath \
        -Dmdep.outputFile=target/qits-classpath.txt -DincludeScope=runtime
    qits database diagram

`test-compile` with `-Dmaven.test.skip=true` rather than `compile`: `dependency:build-classpath`
resolves the test scope, and a reactor whose module depends on another module's test jar
(qits-projects-service) cannot resolve it at `compile`. No test is compiled either way. The scope
flag is `-DincludeScope`, not `-Dmdep.includeScope`: maven-dependency-plugin reads no such property,
and with it the file silently lists every scope, test jars included (measured: 210 entries rather
than 58 for this repository's commands module).

What it reads. Every reactor module's `target/classes` (found by walking `<modules>` from the root
`pom.xml`), indexed with Jandex, and the jars each module's `target/qits-classpath.txt` names, of
which only the classes in a unit's packages and their superclasses are ever indexed. Nothing is
loaded or run. A unit is an unprofiled `quarkus.hibernate-orm.<unit>.packages` key
(`quarkus.hibernate-orm.packages` is `<default>`, written to `default.md`) in a reactor module's own
`application.properties` or `META-INF/microprofile-config.properties` — never a jar's, so
qits-eventstream-javalib's unit is drawn there and not in its consumers. A unit holds every
`@Entity` in its packages and sub-packages, the reactor's or a library's; each table's `%%` line
names its class and its origin (the module, or the library jar's artifactId). A reactor entity no
unit lists is drawn in `docs/database/<java.package>.md`.

Names are Hibernate's: JPA's implicit naming and, since Quarkus sets no physical naming strategy of
its own, every name as the mapping spells it (`AuditEntry` stays `AuditEntry`). A unit whose
`physical-naming-strategy` key names `CamelCaseToUnderscoresNamingStrategy` gets snake_case.
`JpaMappingConformanceTest` holds the reading against Hibernate's own `Metadata` under both, with
Hibernate in test scope only. Constructs it does not draw (`@OneToOne`, `@ManyToMany`,
`@EmbeddedId`, `@Inheritance`, `@SecondaryTable`, `@Formula`, ...) are listed under the diagram as
`Not drawn`, never refused.

It writes the full set and deletes only the `*.md` files under `--out` that start with its header
and that it did not write this time; a hand-written file there is left alone. One line per file:
`written`, `unchanged` or `deleted` (`would write` and `would delete` with `--check`, which writes
nothing and exits 1 when anything would change). Nothing compiled is exit 2 (`compile first: no
target/classes under <root>`); no entity at all is `no JPA entities found` and exit 0. The output
holds no version, time or absolute path, so two runs write the same bytes.

### qits contracts coverage

    qits contracts coverage [--openapi docs/openapi.yml] [--index golden-masters/index.json]
                            [--out <tsv file>] [--no-golden-masters]

The platform's rule for a java service (qits-1940): every HTTP operation its exported OpenAPI
document declares (`docs/openapi.yml`, which the service's suite rewrites) has at least one golden
master in `golden-masters/index.json`. qits-ci composes this command into the QA step of every
java-service release, after the repository's own script and in its checkout, with `--out` for the
`golden-master-coverage` release report and `--no-golden-masters` when `release.yml` declares no
`contracts.golden-masters`. It reports only; gating is a qits-ci switch for later.

An operation is every `paths.<path>.<method>` for the eight HTTP methods, in document order. It is
covered when a call the index records has its operationId, or its method (any case) and a path,
query left off, that is one of the document's `servers[].url` (absent, or `/`, is no prefix; a scheme
and host are dropped) followed by the operation's path. A `{name}` in the server url or the path
stands for one non-empty segment, or the part of one it takes up; everything else is literal,
including a template in the recorded path. One trailing `/` is ignored on either side. Only the
document's own servers are prefixes: a recorded `/foo` never reached `/api/foo`. `GoldenMasterMatch`
holds the rule. An uncovered operation is named by its operationId, else `METHOD /path`.

Stdout is one `uncovered: <name>` line per uncovered operation (with `(<METHOD> <path>)` after an
operationId), then `golden-master coverage: <covered>/<total> operations covered`, or `... all <n>
operations covered`. `--out` writes `<name>\t<METHOD>\t<path>` per uncovered operation, and an empty
file when there is none. Exit 0 when every operation is covered (a document with none included), 3
when one is not, 1 when an input is missing (`no exported OpenAPI at <path>`, `no golden-master index
at <path>`) or does not parse; on exit 1 `--out` is not written and one already there is removed, so
the report says "not reported" rather than a clean pass. The document is YAML (jackson-dataformat-yaml,
read as a tree) or, named `*.json`, JSON; only OpenAPI 3 and index `formatVersion` 1 are read. It
reads local files only: no sign-in, no service, and it is no MCP tool.

### qits maintenance

Jobs of qits-maintenance. `--project` and `--repository` are as for `release-request`.

`--request` takes the request's id as `qits release-request list` shows it, its uuid or the uuid's
start. qits-maintenance takes only the uuid, so the command looks the request up in qits-projects
first and sends the uuid.

`automations --request <id>` lists a release request's release-request automations and their
state: the regenerations that must land inside the request before it can proceed, such as
`estate-pins` (a wrapper's gitlink bumps) and `screenshot-baselines` (a repository whose
screenshots follow the `@qits/angular` vitest-browser convention). The request holds until every
automation that applies to the repository is fresh for its merged commit. `--fold` reads an older
fold; without it, the request's newest one. The table's columns are kind, state, fold, run (the
newest of that kind's runs) and detail (the hold reason, when there is one).

`automation run --request <id> --kind <kind>` re-runs one kind on the request's current fold. It
skips carry-over and applicability, so a repository's first screenshot references come from here;
the kind's own precondition (no `test:browser` script, for example) still refuses the run with a
sentence. `--work-item` is the commit subject's scope (default: the newest one named on the
request's own commits). 404 means the kind or the repository is not one the platform knows; 409
means one is already running for this (request, kind), the request takes no branch, or bumping is
off. Any of the three roles may ask, the agent credential included, as for every maintenance door.

`bump <id>` shows a job: SUCCEEDED (joined, or ff-pushed), NOTHING_TO_DO (unchanged) or FAILED,
with the reason.

    qits maintenance --project qits --repository qits-landing-app automations --request qits-landing-app-rr-7
    qits maintenance --project qits --repository qits-landing-app automation run --request qits-landing-app-rr-7 --kind screenshot-baselines
    qits maintenance bump 6f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f

### qits deployments settings

The values qits-deployments hands an application in an environment when it deploys it (epic
qits-765). `list --application <app> [--environment <env>]` prints APPLICATION, ENVIRONMENT, KEY,
VALUE and UPDATED BY, a secret's value as `********` (the service sends it as null). `set
--application <app> --environment <env> --key <KEY> [--secret]` reads the value on stdin, never
from a flag, takes off one trailing line break, and refuses a terminal or an empty stdin with exit 2
before sending anything; it prints the stored row with the value masked, plain or secret. `delete`
prints `deleted`, and a 404 is exit 1. `preview --application <app> --environment <env>
[--include-extras]` prints what the next deployment would change: `+ KEY=value`, `- KEY=value`,
`~ KEY from -> to`, `= KEY=value` for a kept extra, then the unchanged count. Reading takes
qits:admin, qits:admin-agent, qits:desk-admin, qits:system or qits:agent; writing qits:admin or
qits:system.

    qits deployments settings list --application qits-ci --environment dev
    qits deployments settings set --application qits-ci --environment dev --key API_TOKEN --secret < token.txt
    qits deployments settings preview --application qits-ci --environment dev

### qits agent-mcp credentials

The secrets the agent MCP catalog's servers are reached with, in qits-projects (epic qits-765).
`list` prints the stored keys with when and by whom each was set, never a value. `set --key
env.<VAR>` reads the value on stdin under the same rules as `deployments settings set` and prints
`set <key>`; `delete --key env.<VAR>` prints `deleted`, a 404 is exit 1. list takes qits:admin,
qits:admin-agent, qits:desk-admin or qits:agent; set and delete qits:admin or qits:admin-agent.

    printf '%s' "$TOKEN" | qits agent-mcp credentials set --key env.GITHUB_TOKEN

Both `set` commands are `@TuiCommand(input = TEXT)`: the MCP service serves them with a string
`payload`, which is the command's stdin as it is.

### qits users

The people who sign in to the platform, on the idp (`/idp/api`). `invite`, `list` and `delete`
send the caller's bearer, as every command does, and need `qits:admin`: anyone else gets
`Managing users needs qits:admin` above the idp's 403. `register` needs no sign-in and sends no
bearer; the register token is the credential.

`invite` mints a register token (`POST /idp/api/register-tokens`) and prints it alone on stdout,
with its id and what to do with it on stderr; `-o json` prints the idp's answer (`id`, `token`,
`createdAt`). The token registers one user, once. Hand it over privately.

`register --token <token> <username>` spends the token (`POST /idp/api/auth/register`) and prints
the password the idp generated, alone on stdout. **It is shown only this once**: the CLI writes it
nowhere else, not to stderr, a file or a log, and the idp cannot show it again. `-o json` prints
`{"username", "password"}` and nothing of the session the idp opens (its cookie is ignored). A
refused token (401: unknown or spent) says to ask for a new one; a refused username (400) names
the idp's reason and leaves the token unspent. Then sign in with `qits login`.

`list` prints USERNAME, CREATED and ROLES; `-o json` the idp's answer. `delete <username>` prints
`Deleted user <username>.`; an unknown user is `There is no user '<username>' (HTTP 404)`, and
deleting yourself is refused (409). Every refusal is exit code 1.

The idp's address: `--idp-url` (with `/idp`), else the session's idp, else inside the platform
`https://idp.qits.<QITS_DOMAIN>/idp` (`QITS_URL_IDP` over it, as for every service). `register` on a workstation with no
session takes the address `qits login` would: `QITS_IDP_URL`, else `https://idp.qits.<QITS_DOMAIN>/idp`.

The qits MCP server serves `list` and `delete` but not `invite` or `register`: their answer is a
secret for one person, and a tool's answer lands in an agent's transcript.

    qits users invite
    qits users register --token <token> jan
    qits users list
    qits users delete jan

### qits remembered-devices

Your remembered browsers at the idp (qits-1944): the browsers that sign back in without a
password, because you asked the sign-in page to remember them. Both commands send the caller's
bearer, as every command does, and the idp answers only a person: the CLI session `qits login`
stores (a token with `credential_type=cli`), or a browser session. Any other bearer, an agent's
`QITS_TOKEN` or a service client's, gets 403, printed under `Remembered devices are a person's own`
with the idp's own sentence. Each person sees and revokes only their own devices.

`list` (`GET /idp/api/remembered-devices`) prints ID, LABEL (the browser's user agent, cut at 40
characters), LAST USED, EXPIRES and CURRENT, most recently used first; `-o json` prints the idp's
answer, with `createdAt` and `absoluteExpiresAt` too. CURRENT marks the browser the calling
session was opened through, so it is never set for a CLI session.

`revoke --device <id>` (`DELETE /idp/api/remembered-devices/{id}`) forgets the device and ends
every session it opened, and prints `Revoked remembered device <id>: it signs in with a password
again.` The id is the ID column of `list`; anything that is not an id is a usage error (exit 2).
An unknown id, or one of somebody else's, is `You have no remembered device <id> (HTTP 404)`.
Every refusal is exit code 1.

The idp's address is found as for `qits users`: `--idp-url` (with `/idp`), else the session's idp,
else inside the platform `https://idp.qits.<QITS_DOMAIN>/idp` (`QITS_URL_IDP` over it).

The qits MCP server serves both as tools (`remembered-devices_list`, a read, and
`remembered-devices_revoke`), like every command that needs nothing of the caller's machine; the
idp refuses an agent's bearer there as it does here.

    qits remembered-devices list
    qits remembered-devices list -o json
    qits remembered-devices revoke --device 6f1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f

### qits events

    qits events [--filter=<names>] [--events-url <url>]

Prints the domain events of qits-events as they happen, one JSON object per line on stdout, each
line flushed at once, so `| jq` shows an event when it arrives:

    {"id":"…","name":"BuildSuccessful","occurredAt":"…","payload":{"repoName":"qits-ci-service",…},"description":null,"parentId":null,"environment":"dev","publisher":null}

`payload` travels as a JSON string; the command reads it into JSON. A payload that is not JSON
stays a string.

The filter is written as services write their subscriptions: exact event names, comma-separated,
or `*` for every event. `*` is the default. There are no patterns and no filter by source.

    qits events --filter=BuildSuccessful,BuildFailed
    qits events --filter=SCMPublishCommit,SCMDeleteBranch
    qits events --filter=ReleaseRequestStateChanged | jq -r '.payload'

The stream is live only: it has no replay. Notes go to stderr, one line each with a time.

- A dropped connection or a 5xx is followed by a reconnect, waiting 1, 2, 4 … up to 30 seconds.
  The note says that events in the gap are missed.
- A connection that sends nothing for 60 seconds (the service sends a keepalive every 20) counts as
  dropped. After a suspend a connection can look open on this side and be gone on the other.
- A 401 or 403 (or any other 4xx) stops the command with exit code 1.
- SIGINT (Ctrl-C) or SIGTERM stops it with exit code 0.
- When stdout is closed (`| head -3`), it stops, with exit code 0, at the next event it would
  print. Keepalives print nothing, so on a quiet stream that can take a while.

### The query window

`qits events query` and `qits observe query` answer with what already exists in a window of time,
and never wait for anything new: they are the live commands asked about the past, and what the
qits MCP server serves in place of the streams. Both take the same three options:

| option | meaning | default |
|---|---|---|
| `--since <time>` | where the window starts, inclusive | `1h` |
| `--until <time>` | where it ends, inclusive; a time after now is taken as now | now |
| `--limit <n>` | at most n records, 1 to 1000 | `100` |

A time is an ISO-8601 instant (`2026-10-01T18:00:00Z`) or a whole number of seconds, minutes, hours
or days back from now (`90s`, `15m`, `2h`, `7d`). Anything else, a `--limit` outside 1..1000, or a
`--since` after the `--until`, is exit code 2 naming the option.

When more than `--limit` match, the newest are kept (a tail wants the end), printed oldest first.
The text form's first line is the window as two absolute instants, so the same window can be asked
again or extended, and its last line says when the answer was cut:

    window: 2026-10-01T17:00:00Z .. 2026-10-01T18:00:00Z
    …one line per record, the line the live command prints…
    … truncated: 100 shown, more in the window

`-o json` prints one object rather than one per line, because a query has one answer:
`{"events"|"records": […], "truncated": true|false, "window": {"since": "…", "until": "…"}}`.

`qits events query` reads `GET /events/api/events`. That route has `?since=` and no upper bound but
its cursor, so the first page asks for `?cursor=<until, truncated to microseconds, plus one
microsecond>,0` — every row at or before `--until`, ties included — and the pages after it follow
`nextCursor`.

`qits observe query` sends its filters, in the live command's grammar, to `POST
/observability/api/telemetry/records/search`, which reads qits-observability's buffer with the
live feed's own matching. `--source <key>` narrows it to one source. A record is in the window by
its own time; a metric keeps only its latest point, so it is found only when that point is in the
window. The buffer is bounded: when the window starts before the oldest record it still holds,
stderr says so, because an empty answer there is not proof that nothing happened. A filter the
service refuses (HTTP 400) is exit code 2.

### qits checkout-daemon

    qits checkout-daemon [--path <dir>] [--repository <name>] [--once] [--[no-]submodules] \
        [--events-url <url>]

Holds a local checkout at what the repository released, and keeps it there. **It follows the
releases, not the tips of the branches**: the root ends detached at the release tag, and every
submodule detached at the gitlink that release recorded. That tree is the estate somebody reviewed
and released, which is not the same thing as `latest` — a wrapper's branch tips are whatever each
component pushed since.

    qits checkout-daemon --path /workspace
    qits checkout-daemon --path /workspace --once
    qits checkout-daemon --path /srv/qits --repository qits-qits --no-submodules

**It moves when a release request is finalized, not when the tag is cut.** At the start, and again
after every connect, it reads the newest `ReleaseRequestStateChanged` of the repository whose
`state` is `FINALIZED` from the events service (`attr=reponame=<repository>&attr=state=finalized`)
and brings the checkout to that release's `version`; in between it waits for
`ReleaseRequestStateChanged` on the live stream. That stream carries every release request's every
state change, estate-wide, and anything but this repository's finalization is ignored without a
note. The commit is always the tag's (`refs/tags/<version>^{commit}`), never the event's
`mergedSha`: that is the fold's tip, which is not the tagged commit when a release adds a
version-bump commit. The stream has no replay, so the reconcile after a connect is what closes the
gap a reconnect leaves — and the version last acted on is remembered, so the frame that follows a
reconcile does not run Git twice, and a finalization older than it (calver compared segment by
segment as numbers) never moves the checkout back.

Which repository it follows comes from the checkout's `origin`. An origin of the form
`https://<git host>/git/<project id>/<repository>` names it, and the project id narrows the events
it matches. `http://<git host>/git/<repository id>` does not: that is the git host's internal
storage scheme, and the command refuses with exit code 2 until `--repository <name>` says which
repository this is.

What it runs is what a person would run:

    git -C <path> fetch <origin> refs/tags/<version>:refs/tags/<version>
    git -C <path> checkout --detach <version>
    git -C <path> -c submodule.<name>.url=<the origin's parent>/<name> \
        submodule update --init --checkout --recursive -- <path of the submodule>

`--checkout` is the flag that matters. Every entry of the wrapper sets `update = merge`, and
`submodule update --init` copies that into the checkout: without the flag Git merges the recorded
commit into whatever branch the submodule sits on and leaves it there, at that branch's tip rather
than at the commit the release recorded. `--recursive` brings the submodules of a submodule along,
each detached at the commit its parent now records: the estate is two levels deep (wrapper entries
declare a `service/src/main/webui` submodule of their own), and moving one level only left a
nested webui at its old commit after every bump, which the guard then refused for good (qits-290).
Nested submodules take their URLs from their parent's `.gitmodules`. `--no-submodules` holds the root alone. A gitlink
`.gitmodules` declares but the released tree does not carry is skipped with a note, not a failure.

- **A checkout with local changes is never touched.** Before anything it asks the root for its
  tracked state alone (`git status --porcelain --untracked-files=no --ignore-submodules=all`, and
  the staged paths, which catches a staged gitlink), then asks every initialised submodule, at
  every depth (down to eight levels), by the same rule: a modified or staged file there, or a staged
  gitlink of a nested submodule (the same `--ignore-submodules=all` status and staged paths inside
  it), or a `HEAD` away from the gitlink its parent records that no branch, remote branch or tag
  holds, which the move would leave behind. A `HEAD` at the gitlink is held by the parent's own
  history, even when it was fetched by its id and nothing in the submodule names it. An untracked
  file, at any depth, is not a local change, and neither is a clean submodule at another commit than
  its gitlink — a clone whose submodules follow `main`, or a nested webui its parent has bumped —
  which is simply moved to the gitlink. When there is a local change it names every such path from
  the checkout's root and why, and leaves the checkout as it is. It never stashes, resets or
  merges away somebody's work. With `--once` that is exit code 1; while watching it keeps watching,
  and the next release tries again.
- **"Already at" means the submodules too.** When the root is at the release already, it says
  `already at <version>` only if every initialised submodule, nested ones included, is at the
  gitlink its parent recorded as well. Otherwise it runs the same check and moves the submodules, and the release counts as
  held (one line on stdout). With `--no-submodules` the root alone decides.
- **Git authentication stays the credential helper's.** No token is put in a URL or an
  `http.extraHeader`: the helper is pinned to one host on purpose, and a machine credential handed
  to a submodule remote somebody else authored is a way out for it. At the start it checks Git has
  a `credential.helper` for the origin — `qits git-login` on a workstation, the image's helper in a
  container — and refuses with exit code 2 rather than hanging on a password prompt at the first
  release. An origin that is a local path has no host and needs none.
- With `QITS_PLATFORM=true` and no `QITS_TOKEN` it refuses at the start with exit code 2, naming
  `QITS_TOKEN`, before any git work.
- Every note is one line on stderr with a time. Every release the checkout is moved to is one line
  on stdout, so `qits checkout-daemon --path . | while read v; do ...; done` works.
- A dropped connection or a 5xx is followed by a reconnect, waiting 1, 2, 4 … up to 30 seconds; a
  connection silent for 60 seconds counts as dropped. A 4xx stops the command with exit code 1, and
  SIGINT or SIGTERM with 0.

### qits checkout sync

    qits checkout sync [--path <dir>]

Brings a **workstation** clone of a wrapper up to date, the way the wrapper's `CLAUDE.md` wants one
("Submodules", "Fresh clone"): every repository on its own `main`, following `origin/main`. It is
the opposite of `qits checkout-daemon`, which detaches everything at a release, and it is pure git:
no sign-in, no `QITS_TOKEN`, no events address, no credential-helper check. Each repository is
fetched from its own `origin` with whatever helper git has (GitHub or the platform git host alike;
`GIT_TERMINAL_PROMPT=0` unless set, so a fetch that wants a password fails and is named rather than
waiting), and a failed fetch is one skipped line, not the end of the run.

    qits checkout sync
    qits checkout sync --path ~/src/qits-qits

`--path` defaults to `.`; the clone is the top level of the repository it is in. In order:

1. **A project agent container's checkout is refused** with exit code 2, nothing touched: a root
   detached at a commit `git tag --points-at HEAD` names, or one a running `qits checkout-daemon`
   holds (Linux: a `/proc/<pid>/cmdline` whose argv is `qits checkout-daemon` with a `--path` that
   resolves to this directory, or no `--path` and this directory as its cwd; the argv is compared
   word by word, never as a `pgrep -f` substring, which would find the asking shell). That checkout
   follows releases, and the daemon keeps it. A root detached at no tag is not refused, only
   skipped as `detached`.
2. **The root**: `git fetch origin`, then `git merge --ff-only origin/main` if it is on `main`, has no
   tracked changes and nothing `origin/main` lacks; otherwise it is skipped with its reason. The
   submodule steps then read the `.gitmodules` the root has now.
3. **New submodules**: an entry `.gitmodules` declares whose path has no `.git` yet gets
   `git submodule update --init --checkout -- <path>` and then `git switch main` inside (a local
   `main` tracking `origin/main` is created when there is none). That switch only ever runs on a
   fresh clone.
4. **Every initialised submodule, nested ones too** (a service's `service/src/main/webui`; parent
   first, then its own `.gitmodules`, down to eight levels): `git fetch origin`, then
   `git merge --ff-only origin/main` only when it is on `main` (`detached` or `on <branch>`
   otherwise), has no tracked changes (`tracked changes (<paths>)`) and is not ahead of
   `origin/main` (`<n> local commits ahead`, and `, <m> behind` when it has diverged). Untracked
   files never block; when the incoming tree would overwrite one, git refuses the fast-forward and
   its first line is the reason (`fast-forward refused: ...`).
5. **A broken gitdir pointer** — a `.git` file naming a `gitdir:` that is not there, left behind by
   the move from the role directories to `components/` — is skipped as
   `broken gitdir pointer -> <gitdir>`, and its submodules are not descended into. Nothing repairs
   it: the line names `git -C <parent> submodule absorbgitdirs`, or removing and initialising it
   again, for the person to choose.

"Tracked changes" is `qits checkout-daemon`'s rule, shared code: `git status --porcelain
--untracked-files=no --ignore-submodules=all` plus `git diff --cached --name-only
--ignore-submodules=none`, so a staged gitlink counts and a submodule that merely moved does not.

stdout, after the run:

    .  +2 commits
    components/qits-ci/qits-ci-service  +1 commit
    components/qits-ci/qits-ci-service/service/src/main/webui  +3 commits
    components/qits-network-access/qits-network-access-service  new
    components/qits-docs/qits-docs  skipped: tracked changes (README.md)
    3 moved, 1 new, 1 skipped, 31 up to date

the moved repositories (`+<n>` is `git rev-list --count <before>..<after>`), the new ones, the
skipped ones, and a summary. A repository already at `origin/main` prints nothing of its own. Git's
own chatter does not reach stdout, and everything of git's that is printed goes through
`SafeText.line`.

- **It only ever fast-forwards.** It never resets, stashes, runs `checkout --` or `switch` over local
  work, adds a path to any index, or lets git recurse (`--no-recurse-submodules` on every fetch,
  `-c submodule.recurse=false` on every command): each repository is visited and asked on its own.
  A gitlink a parent's index records and its submodule has moved past is the expected drift
  `ignore = all` hides; the bumps own it, and it is never staged here.
- Exit code 0 when nothing was skipped; 1 when anything was skipped or a fetch or fast-forward failed
  (every other repository is still synced); 2 when the path is not in a Git repository or it is a
  project agent container's checkout.
- `LOCAL`: the MCP service does not serve it.

### qits observe

    qits observe --filter <conditions> [--filter <conditions> …] [-o json] [--observability-url <url>]

Prints what qits-observability takes in, as it arrives: logs, spans (with their events, such as
`exception`) and metrics. Domain events stay on `qits events`. The command sends its filters over a
WebSocket, `wss://observability.<env>.<domain>/observability/stream`; the service checks each record
and sends only the ones that match. It needs the role `qits:admin`.

    qits observe --filter 'kind=log level>=ERROR' \
                 --filter 'trace=4bf92f3577b34da6a3ce929d0e0e4736' \
                 --filter 'kind=span event=exception'

One `--filter` is one group. The conditions of a group are separated by spaces, and all of them
must hold. A record that fits any group is printed. At least one `--filter` is required;
`--filter '*'` streams every record.

| written | means |
|---|---|
| `F=V` | the field is V (matches case: `status=ERROR`, not `status=error`) |
| `F^=V` | the field starts with V (matches case) |
| `F~V` | the field contains V (any case) |
| `F?` | the field is there and not empty |
| `!F` | the field is not there |
| `level>=V` | the severity is V or higher: TRACE, DEBUG, INFO, WARN (or WARNING), ERROR, FATAL, or a number 1-24 |

| field | applies to | is |
|---|---|---|
| `kind` | all | `log`, `span` or `metric` |
| `service` | all | the resource's `service.name` |
| `trace`, `span` | log, span | the trace id and the span id, lowercase hex (the command lowercases what you give) |
| `level` | log | the severity; takes `>=` only |
| `body` | log | the message |
| `name` | span, metric | the span's or the metric's name |
| `status` | span | `OK`, `ERROR` or `UNSET` |
| `event` | span | the name of any of the span's events, for example `exception` |
| `attr.<key>` | all | the record's own attribute `<key>` |
| `resource.<key>` | all | the resource attribute `<key>` |

Everything after `attr.` or `resource.` is the key, dots included: `attr.exception.type`. A span's
exception is an event of the span, not an attribute of it, so `attr.exception.type?` matches logs
and `event=exception` matches spans. A field that a record does not have fails every condition but
`!F`. Quote a value that holds spaces: `body~"connection refused"`; inside the quotes, `\"` is a
quote and `\\` a backslash. A condition the command cannot read stops it with exit code 2, and the
message names that condition. Nothing is sent before every condition reads.

    qits observe --filter '*'
    qits observe --filter 'service^=qits-ci kind=log body~"connection refused"'
    qits observe --filter 'resource.service.version=2026.912.1 status=ERROR'
    qits observe --filter 'kind=log level>=WARN' -o json | jq -r .record.body

The default output is one line per record, flushed at once:

    12:03:11.123 log    qits-ci ERROR connection refused  [4bf92f35]
    12:03:11.140 span   qits-ci ERROR GET /ci/api/builds  [4bf92f35]
    12:03:12.000 metric qits-ci 42 ms http.server.duration

That is the local time (the record's own, else when the service received it), the kind, the
service, the level (log), the status (span) or the value and its unit (metric), the body (log) or
the name (span, metric), and the first 8 characters of the trace id. `-o json` prints each frame as
the service sends it, one per line; `record` is what the service's query API returns for that kind:

    {"kind":"log","receivedAtMillis":1757685791123,"source":"_service/qits-ci","record":{…}}

Ingest takes records without a sign-in, so a record can say anything, escape sequences included.
The line form removes every terminal control character from every value: C0 and C1 characters, ESC
sequences, DEL and the bidirectional controls. A tab or a line break becomes a space. The JSON form
writes them as escapes (`\u001B`), so a reader such as `jq` still gets the same text.

Notes go to stderr, one line each with a time:

- `{"dropped": N}` from the service: it dropped N records, because this side read too slowly. The
  note also gives the total since the command started.
- `{"error": …}` before the first record is the service's answer to the filters (it sends no other
  answer): the command stops with exit code 2 and the service's reason. After a record, an error is
  only a note.
- A dropped socket or a 5xx is followed by a reconnect, waiting 1, 2, 4 … up to 30 seconds, and the
  filters are sent again. The note says that records in the gap are missed: the stream has no
  replay. An access token about to expire is refreshed before each connection.
- The command pings every 20 seconds, and the service every 30. A socket that sends nothing (no
  frame, ping or pong) for 60 seconds counts as dropped.
- A 401 (`The platform refused the token`), a 403 (`Your roles do not allow this`) or any other 4xx
  on the upgrade stops the command with exit code 1.
- SIGINT (Ctrl-C) or SIGTERM stops it with exit code 0. So does a closed stdout, at the next record.

### qits wait

    qits wait --event <Name> [--when <conditions> …] [--timeout <duration>] [--label <text>] [--agent <id>|--desk <project>]

An agent, or a project's desk, declares what it waits on; the platform gives the holder a turn when
that happens (qits-1153, qits-1618). The command sends the wait to qits-workspaces — `POST
/workspaces/api/agents/{agentId}/waits` for an agent, `POST /workspaces/api/desks/{projectId}/waits`
for a desk — prints it and returns at once. It never blocks. Declare the wait, then end the turn:
the next turn names what fired, the event or the timeout. Never poll for the same thing in a
background loop.

The holder is `--agent` or `--desk` (never both), else `QITS_AGENT_ID`, else `QITS_DESK_PROJECT`.
The harness sets `QITS_AGENT_ID` in an agent's environment and `QITS_DESK_PROJECT` in a project's
kept front-desk session; the MCP server runs the command without that environment, so it sets
`QITS_AGENT_ID` from the `agent` claim of the caller's token, or `QITS_DESK_PROJECT` from its
`project` claim when the token names no agent and is a desk's (the role `qits:desk-admin`, or a
`context_kind` of `desk-session` or `desk-delivery`). `--desk` takes the project's id, slug or name,
looked up in the projects service the way the other commands with a `--project` do; the environment
variable is always a project uuid already. Without any of the four the command exits with 2. An
agent may declare and cancel waits for itself only; a project's desk, for its own project's only.

`--agent` takes the agent's id as `qits agents list` shows it; `qits agents show --work qits-123` names
the one active agent on an item. A wait that fires for a STOPPED agent (`qits agents stop`) does not
start it: its turn is kept and heard once the agent is restarted (`qits agents restart`) or prompted
(`qits agents prompt`). One that fires for a YIELDED agent brings it back, as any turn does.

Each `--when` is one group of conditions that must all hold; an event that fits any group matches,
and with no `--when` every event of the name does. The conditions are those of `qits observe`, on
the event's payload:

    path=value     equal                  status=VERIFIED
    path^=value    starts with            branch^=release/
    path?          present                releaseRequestId?
    !path          absent                 !previousStatus

A path is field names joined by dots; `[]` after a field means some element of that array, and the
conditions of one group through the same `name[]` hold on the same element. Quote a value with
spaces, or an empty one: `title="two words"`, `note=""`. At most 8 groups of 8 conditions. The
grammar and its limits are qits-eventstream-javalib's (`eu.wohlben.qits.eventstream.selection`); the
CLI holds its own copy (`waits/WhenGrammar`) because the binary does not depend on that library, and
checks it before anything goes out. The wire form has lowercase ops, string values, and absent as
`{"op":"exists","value":false}`.

`--timeout` takes `30m`, `2h`, `1h30m`, `1d` or ISO-8601 (`PT2H`); it is sent as ISO-8601. Every wait
now carries an `expiresAt`, never null: the platform's own ceiling for an unstated timeout is 8h,
and the most any wait may run is 24h, which it refuses to extend.

The presets fill in the event and the groups:

    qits wait release-request <id>               ReleaseRequestStateChanged,
                                                 releaseRequestQualifiedId=<id> (or, for a uuid,
                                                 releaseRequestId=<uuid>) state=<S>, one group per
                                                 final state: FINALIZED, REJECTED, FAILED,
                                                 WITHDRAWN, OBSOLETE
    qits wait work qits-123 --status VERIFIED    EntityTransitioned, entities[].entityId=<uuid>
                                                 entities[].status=VERIFIED (the qualified id is
                                                 looked up in the projects service first)
    qits wait ci-run <id>                        BuildStatusChanged, runId=<id> status=<S>, one group
                                                 per terminal status: SUCCESS, FAILED, CANCELLED,
                                                 CONFIG_ERROR, TIMED_OUT

`release-request` and `ci-run` take the whole id only: a wait matches it exactly, and the start of
one would make a wait that never fires. `release-request` takes the request's id as `qits
release-request list` shows it (`qits-ci-service-rr-12`, in that case) or its whole uuid. Each
preset gives a label of its own (`release request qits-ci-service-rr-12 to end`); `--label`
replaces it.

#### qits wait branch

    qits wait branch <branch> --until joined|released|live|failed [--repository <name>] \
        [--project <project>] [--timeout <duration>] [--label <text>] [--agent <id> | --desk <project>]

Waits on your own work by its branch, not by the release request that carries it now (epic
qits-1676). Prefer it to `wait release-request` for your own work: a request id goes stale when the
request refolds or is superseded, and the branch carries on into the next request (MOVED). The event
is `BranchDeliveryChanged`, which qits-projects publishes for every step a branch takes. Every group
carries `branch=<branch>`, plus `repository=<name>` with `--repository` and `projectId=<uuid>` with
`--project` (an id, slug or name, looked up in the projects service). The groups are ORed:

    --until    with --repository (step)   without (everywhere)        early (step)
    joined     JOINED, MOVED              JOINED, RELEASED, LIVE      FAILED, CONFLICTED, WITHDRAWN
    released   RELEASED, LIVE             RELEASED, LIVE              FAILED, CONFLICTED, WITHDRAWN
    live       LIVE                       LIVE                        FAILED, CONFLICTED, WITHDRAWN
    failed     FAILED, CONFLICTED         FAILED, CONFLICTED (step)   none

`everywhere` is the least advanced step of the branch across the repositories of its project that
know it, so `--until released` without a repository fires when the last of them released. An early
step fires the wait as well, so a broken delivery wakes you instead of leaving you waiting. LIVE is a
request FINALIZED: deployed for a service, released for a library. The default label is `branch
<branch> <until> in <repository>`, or `branch <branch> <until> everywhere`.

Before declaring it reads `GET /projects/api/branch-deliveries?branch=…[&repository=…][&project=…]`
once:

- When the target already holds, it prints `branch <branch> already <STEP> in <repository>
  (<request id>)` or `branch <branch> already <STEP> everywhere`, declares nothing and exits 0.
- When only an early step (FAILED, CONFLICTED or WITHDRAWN) already holds, it prints the same line
  to stderr, with `: nothing was declared and nothing will wake you; fix and push, then wait again`
  appended, declares nothing and exits 1.
- Either way, with `-o json` it prints what it read, with `declared: false` and `already: "<the
  line>"` added.
- When no repository knows the branch yet, it warns `no repository knows <branch> yet; waiting
  anyway` on stderr and declares.
- When the read fails, it declares anyway.

An event that lands between the read and the declaration is caught by qits-workspaces' look-back.

`qits wait list [--state <STATE>]` shows the holder's waits, and `qits wait cancel <wait id>` ends
one. A wait is OPEN until it closes MATCHED, TIMED_OUT, BROKEN or CANCELLED: BROKEN is the platform
deciding a wait can never fire (qits-1961), with a `brokenReason`; `list` adds a REASON column for
it and the single-wait view (`qits wait cancel`, and every preset's own declaration) a `broken`
line, both empty otherwise.

### qits workspaces

Every action the landing app's workspace pages offer is a command here, and each calls the door the
page calls, with the same method, path, query and body, and the caller's own credential (qits-1261).
`workspaces/LandingPactTest` holds that against the landing app's own consumer pacts (copies under
`platform-access-commands/src/test/resources/workspaces/landing-pacts/`): every recorded call is made
by one command, the same way.

    qits workspaces list --repository <id|name>   GET  /workspaces/api/workspaces?repositoryId=
      [--project <project>]
    qits workspaces show <n>                      GET  /workspaces/api/workspaces/{n}, then …/{n}/agents
    qits workspaces active-process <n>            GET  /workspaces/api/workspaces/{n}/active-process
    qits workspaces start|stop|recreate <n>       POST /workspaces/api/workspaces/{n}/ensure-container,
                                                       stop-container, recreate-container (no body)
    qits workspaces log <process id>              GET  /workspaces/api/technical-processes/{id}/events (SSE)
    qits workspaces attach <command id>           WS   …/terminal/commands/{id}
    qits workspaces items                         GET  /workspaces/api/work/workspaces
    qits workspaces history <work>                GET  /workspaces/api/work/{work}/workspaces
    qits workspaces agents refine                 POST …/prompt-refinements
    qits workspaces agents sign-in status|open    GET  …/agents/available, POST …/agents/sign-in
    qits workspaces agents plugins list|install   GET  …/agent-plugins, POST …/agent-plugins/{id}/install {}

`…` is `/workspaces/container/{n}`: qits-workspaces' container proxy to the slot's daemon, so those
commands take the slot with `--workspace <n>`, as the page's base URL does.

The agents themselves are `qits agents` (below), which names an agent by its id or `--work` and
never needs the slot; `qits workspaces agents` keeps only `sign-in`, `plugins` and `refine`, the
container's harness install (its `list|discard|prompt|draft|chat|terminate|harness|files` aliases
of epic qits-1667 lived for one release and are gone, qits-1749). `qits workspaces show <n>` lists
the agents placed in the slot after its status, from `GET /workspaces/api/workspaces/{n}/agents`, as
`qits agents list` prints them; its `-o json` is the slot's answer alone, as before, and a service
without that door (404) leaves the agents out. A work item is its qualified id (`qits-123`), looked
up in the projects service, or its id; `history` sends either as it is, since the service takes both.

Who may: every read is open to every credential, `qits:agent` included. The JSON API's writes (start,
stop, recreate) need `qits:admin` or `qits:admin-agent`, which a person's token carries; `qits:agent`
is refused with 403 by design. The container proxy checks the caller instead: a person, `qits:system`,
`qits:admin` or `qits:admin-agent` may reach any slot; `qits:agent` only its own workspace's.
Each command's help says which applies.

Not here, on purpose: the live chat socket (`WS …/chat/commands/{id}`), whose transcript `agents chat`
reads and whose prompts `agents prompt` sends; and the slot's hint stream
(`/workspaces/api/workspaces/{id}/events`), which only tells a page to read again. `qits workspaces
log` is a stream and therefore not an MCP tool. The nodes slots are placed on are no longer here
either: `qits workspaces runners` is retired (qits-1534, epic qits-1481); see `qits targets` below.

`qits workspaces attach <command id> --workspace <n>` is a slot's container terminal (qits-1265): this
terminal attached to a command's terminal on the slot's daemon, `WS …/terminal/commands/{id}` through
the container proxy, with the caller's own bearer. The command id comes from `agents sign-in open`
(the sign-in terminal), which also takes `--attach` to open and attach at once. An agent's own
harness is `qits agents attach`, which takes the agent rather than a command.

- The terminal goes raw (`stty raw -echo` on `/dev/tty`) and every key goes to the command as
  `{"type":"data","data":…}`, Ctrl-C included. The window's size goes as
  `{"type":"resize","cols":…,"rows":…}` on every open and on every change (SIGWINCH, and a read of
  the size every 2 s besides). The daemon's frames are raw terminal text, written as they come.
- **Ctrl-] detaches** and leaves the command running, as closing the page's tab does. A clean close
  from the daemon (1000) ends it with 0: the command ended, or had ended before the attach. Any
  other close attaches again after 0.5, 1, 2, 4 and 4 s and then gives up (exit 1); the screen is
  cleared first, because the daemon replays up to 256 KB of what the terminal held. SIGINT and
  SIGTERM detach too. The terminal is restored on every way out, a shutdown hook included.
- With stdin not a terminal it only streams what the command prints, read-only: no raw mode, no key
  and no size is sent, and it still ends when the command does.
- It is `Interaction.TERMINAL`, so the MCP service does not serve it, and `--attach` on the other two
  is refused there (exit 2) before anything is opened.

### qits targets

Execution targets and their leases, qits-system-service's `/system/api` (epic qits-1481): one
command per door. This replaces the per-slot-type runner groups the old placement model had, such as
`qits workspaces runners` (retired by qits-1534). A target is named by its name or its id (`qits
targets list` shows both); `--target` resolves a name by reading `listTargets`.

    qits targets list                             GET  /system/api/targets
    qits targets show --target <name|id>          GET  /system/api/targets/{id}
    qits targets create --name <name>             POST /system/api/targets
      [--description <text>] [--reserved-ci <n>] [--reserved-workspace <n>] [--reserved-desk <n>] [--slots <n>]
    qits targets rotate-token --target <name|id>  POST /system/api/targets/{id}/registration-token
      [--slots <n>]
    qits targets update --target <name|id>        PATCH /system/api/targets/{id}
      [--reserved-ci <n>] [--reserved-workspace <n>] [--reserved-desk <n>] [--description <text>]
    qits targets greenlight|healthcheck|login-check
                                                   POST /system/api/targets/{id}/<verb> (no body)
    qits targets delete --target <name|id>        DELETE /system/api/targets/{id}
    qits targets leases [--target <name|id>]      GET  /system/api/leases?targetId=&slotType=&state=
      [--type ci|workspace|desk] [--state <state>]
    qits targets leases show <lease id>           GET  /system/api/leases/{id}
    qits targets leases end <lease id>            DELETE /system/api/leases/{id}?reason=
      [--reason <text>]
    qits targets sign-in --target <name|id>       GET  /system/api/targets/{id}; POST …/sign-ins
      [--harness claude|kimi] [--timeout <duration>]  {harness}, then GET …/sign-ins/{id} every 2s
      [--sign-in <id>] [--code <code>]                until it ends, then GET the target every 2s

Who may: reading, and asking for a health check, is open to every credential, `qits:agent` included.
Declaring (`create`), rotating a registration token (`rotate-token`), updating and deleting need
`qits:admin`, `qits:admin-agent`, `qits:desk-admin` or `qits:system`; greenlight and
login-check need `qits:admin`, `qits:admin-agent` or `qits:desk-admin` only; `qits:agent` alone is refused
with 403 by design wherever it is not named. `qits targets leases` (and `leases show`) reads every lease for
`qits:admin`, `qits:admin-agent`, `qits:desk-admin` and `qits:agent`, and only the caller's own for
`qits:system`. `qits targets leases end` takes `qits:admin`, `qits:admin-agent` or `qits:desk-admin`, which
end any lease and are audited (`target_audit`, `LEASE_END`), or `qits:system`, which ends only its own; a
lease already ENDING that an operator ends goes ENDED at once (epic qits-1621). Each command's help names its door and
roles.

`qits targets create` declares a target (`{name, description?, reservations?: {ci, workspace, desk},
slots?}`; a reservation left out is not sent, and with none given `reservations` is left out) and
`qits targets rotate-token` mints a new registration token and its install line. On a target whose
runner has not registered yet this is the first token's replacement; on a REGISTERED target
running the new install line reinstalls its runner under the same target — the id is kept, and the
old runner's client is decommissioned once the new one registers. Both commands take an optional
`--slots <n>`, the install line's `QITS_SYSTEM_RUNNER_SLOTS`; left out, it is max(the reported
total, the reservations' sum). Both answer `{target, registrationToken, installLine}`, and the install
line embeds the one-time token, so it is handled the way `qits users invite` handles a register
token: **the install line alone on stdout, shown only this once**, and on stderr which target it is
for (name and id, never the token) and that it is run on the target's own machine. `-o json` prints
the service's answer as it is. The line is never written to stderr, a file, a log or an error
message; an answer without one fails without quoting it. A taken name is HTTP 409
`TARGET_NAME_TAKEN`, `--slots` below the reservations' sum or outside 0..1024 is HTTP 400, commissioning
off or no runner version pinned HTTP 503. Both are `Interaction.LOCAL`: the qits MCP server does not
serve them, because a tool's answer lands in an agent's transcript.

`qits targets sign-in` runs the harness's own sign-in on the target (the system runner there runs
it in its credential volume and relays the URL and code over its socket) through the API, for
`--harness` (default `claude`). A target already `PRESENT` for that harness exits 0 at once;
otherwise it `POST`s `/system/api/targets/{id}/sign-ins {harness}` (or, with `--sign-in`, resumes
that one instead of starting a new one) and polls `GET .../sign-ins/{id}` every 2 seconds, printing
the URL and, for `kimi`, the user code the first time each shows up. At `AWAITING_CODE` (`claude`)
it asks for the code on stdin and posts it with `POST .../sign-ins/{id}/code`; with none given
(stdin closed or empty) it fails (exit 1) naming the command that resumes the same sign-in with
`--sign-in` and `--code` — the sign-in itself keeps running on the target until it expires.
`FAILED`, `EXPIRED` or `CANCELLED` fails naming the state and its detail; `--timeout` (default
`10m`) reached deletes the sign-in (best effort) and fails naming the last state. Once the sign-in
`SUCCEEDED`, it polls `GET /system/api/targets/{id}` the same way until `login.<harness>` is
`PRESENT` (exit 0) or `--timeout` passes. `--code` without `--sign-in` is a usage error (exit 2).

## Git pushes from this workstation

    qits git-login [--idp-url <url>] [--git-host <url>] [--timeout <seconds>] [--no-browser]
                   [--configure]
    qits git-credential <get|store|erase>      Git runs this; a person does not

`qits git-login` signs this workstation in for Git pushes to the platform's git host. The sign-in
may push branches under `refs/heads/external/` and nothing else; the git host checks that. It needs
no keyring: the sign-in is a file, like the session of `qits login`.

1. It prints the sign-in address and tries to open it (`wslview` on WSL, then `xdg-open`).
   `--no-browser` only prints it.
2. You sign in. The browser comes back to a one-time address on this machine
   (`http://127.0.0.1:<port>/callback`), and the command takes the answer from there. It waits
   `--timeout` seconds (default 300). An answer for another sign-in (its `state` differs) is
   refused.
3. The sign-in goes to `$XDG_CONFIG_HOME/qits/git.json` (0600, in the 0700 directory), under the
   git host's address. The command prints how long it lasts and the Git setup.

Which platform:

- idp: `--idp-url`, else `QITS_IDP_URL`, else the idp of the `qits login` session, else as
  `qits login` finds it.
- git host: `--git-host`, else `QITS_GIT_HOST_URL`, else the idp's host with `idp` swapped for
  `githost` (`https://githost.qits.<QITS_DOMAIN>`).
- audience: always `qits-platform`, the one audience every platform service accepts, the git host
  included. Sign-in and every refresh ask for it; an `audience` an older `git.json` still holds is
  ignored.

The OAuth client is `qits-git-workstation` (PKCE, no secret), not the `qits-cli` of `qits login`.

### Git setup, for this host only

On a workstation Git asks `qits git-credential` for the git host and for no other host, so a global
helper (for example Git Credential Manager for GitHub) stays as it is. The empty value first clears
the helper list for this host:

    git config --global --replace-all credential.https://githost.qits.<QITS_DOMAIN>.helper ''
    git config --global --add credential.https://githost.qits.<QITS_DOMAIN>.helper '!/home/you/.local/bin/qits git-credential'

`qits git-login` prints these two lines with the git host and its own path. `--configure` runs them.
Running them again changes nothing.

An agent container does not run this command at all: its image points a *global*
`credential.helper` at its own shell helper, which answers the one host in `QITS_GIT_AUTH_HOST`
with the container's `QITS_TOKEN`.

### qits git-credential

Git sends the token as HTTP Basic `oauth2:<access token>`; the edge checks it and forwards it to the
git host. It answers differently in each of the CLI's homes.

On a workstation, the sign-in of `qits git-login` in `git.json` is what answers:

- `get`: for a git host with a sign-in, prints `username=oauth2` and the access token as
  `password`. An access token with more than 60 seconds left is used as it is. Otherwise it is
  refreshed first, under `git.json.lock` and after reading the file again (another Git process may
  have refreshed it), and the new pair is written before the token is printed. A host without a
  sign-in gets no answer, and Git asks its other helpers.
- `store`: ignored. Only `qits git-login` stores a sign-in.
- `erase`: Git erases after any refused request. Only the cached access token goes; the sign-in
  stays, so a passing 401 does not cost a new browser sign-in.
- A refresh the idp refuses prints `Git sign-in ended — run `qits git-login`.` on stderr and
  nothing on stdout.

With a workspace token (`QITS_TOKEN`), `get` answers the public git host
`githost.qits.<QITS_DOMAIN>` with that token and no other host; `store` and `erase` do nothing and
no file is written.

With `QITS_PLATFORM=true` and no `QITS_TOKEN`, every action prints nothing, reads and writes no
file and exits `0`, so Git asks its other helpers.

`git-credential get` is the one place `qits` prints a token, because that is how Git's
helper protocol works. stderr never carries one.

## npm and Maven registries on this workstation

    qits registry-login [--configure] [--remove-stale] [--dry-run]
                        [--npmrc <file>] [--maven-settings <file>]
                        [--artifacts-url <url>] [--mirror-url <url>]

npm and Maven have no credential helper, so they read a token from a file. `qits registry-login`
writes the access token of `qits login` into blocks of those files that the CLI owns, and `qits
session-daemon` replaces it each time it refreshes the session. No long-lived secret sits on disk.

- `--configure` reads the registries the platform lists (qits-artifacts' `GET
  /artifacts/api/repositories`, type `npm-packages`, and qits-mirror's `GET
  /mirror/api/repositories`, type `npm-proxy`), writes the blocks, and records the setup in
  `$XDG_CONFIG_HOME/qits/registries.json` (no token in it). A list it cannot read falls back to
  `//registry.qits.<domain>/artifacts/npm/npm/` and `//mirror.qits.<domain>/npm/npmjs/`, with a
  warning.
- Without `--configure` it writes the current token into the recorded files once.
- `~/.npmrc` (or `NPM_CONFIG_USERCONFIG`) gets one `//<host>/<path>/:_authToken=<token>` line per
  registry. `~/.m2/settings.xml` gets the mirror `qits-maven-host` (mirrorOf `qits-maven`, the id
  the poms declare) at `https://registry.qits.<domain>/artifacts/maven/maven`, and servers
  `qits-maven-host` and `qits-maven` with an `Authorization: Bearer` header.
- Only the blocks change. Each file is replaced atomically with mode 0600; a symbolic link is
  followed. `--remove-stale` also drops the `*.dev.localhost` registries and entries outside the
  blocks that use the same registries or ids; without it they stay, with a warning.
- `--dry-run` writes nothing and prints a unified diff per file, or `No changes`. It masks by
  structure: in settings.xml the content of every `<password>`, `<passphrase>`, `<privateKey>`,
  `<username>` and httpHeaders `<value>`, across lines; in .npmrc the value of every key that ends
  in `_auth` or contains token, secret, password, username, cert or key. Context lines come only
  from the CLI's blocks: a foreign line shows only when it changes, and then masked.
- While `registries.json` exists, the daemon refreshes at least 5 minutes before expiry (access
  tokens last 15 minutes), so a build that starts always has 5 minutes of token. Delete the file
  to stop.
- A workstation command: inside the platform or with `QITS_TOKEN` it refuses (exit 2).

## The private edge from this workstation

    qits network-access up [--name <label>]
    qits network-access down [--revoke]
    qits network-access status

Some apps answer only behind the platform's private edge. `qits network-access up` (epic
qits-1977) runs the network-access companion in Docker: a userspace WireGuard tunnel to the edge
with an HTTP CONNECT proxy in front, from the image `GET /network-access/api/companion` names
(`image`, `version`, and the `entrypoint` and `user` every `docker run` of it takes). It makes the
companion's key in the volume `qits-network-access-companion` (the private key never leaves it),
registers the public key as a device peer of the signed-in person (`POST /peers`, qits:admin; a
later `up` keeps a peer that is still ACTIVE with that key), pipes `GET /peers/{id}/tunnel` to the
companion's `configure`, and starts it as `qits-network-access-companion` with `--restart
unless-stopped`, **published on `127.0.0.1:47813` only**: its proxy is unauthenticated, so it must
never be reachable from another machine. After `/healthz` answers 200 (it waits 20 s) it writes
`$XDG_CONFIG_HOME/qits/access.json` (`peerId`, `proxy`, `image`, `version`; no secret). Running
`up` again upgrades the companion. Docker always runs as a subprocess with an argv list.

From then on every command dials the platform's hosts through it (`CompanionProxy`, the
`ProxySelector` of every `HttpClient` the commands build): https and wss to a host under the
session's `.qits.<domain>` go through the companion as CONNECT, so `qits events` and the
WebSockets follow too. Loopback, the hosts of `QITS_URL_*` overrides, plain http and every other
host go direct. `QITS_NETWORK_ACCESS_PROXY=<url>` names the companion instead of access.json, and
`QITS_NETWORK_ACCESS_PROXY=off` turns it off. When the companion does not answer a TCP connect,
calls go direct and the process says so once on stderr ("the network-access companion does not
answer; run `qits network-access up`"): the public apps keep working. `qits network-access` itself
always dials direct, since it sets up the companion it would otherwise depend on. Git
subprocesses are out of scope: the git host stays public.

- `down` removes the container and drops `proxy` from access.json, keeping the peer, so the next
  `up` reuses the device the key is registered as. `down --revoke` revokes it (`POST
  /peers/{id}/revoke`), removes the volume and deletes access.json.
- `status` prints the peer, the container state (`docker inspect`) and the `/healthz` answer;
  exit 0 only when healthy.
- Exit 3 means Docker is missing or not running, or the registry refused the pull (log Docker in
  to the registry first). A workstation command: inside the platform or with `QITS_TOKEN` it
  refuses (exit 2), because host containers reach the private edge over the overlay.

## qits agents

The platform's agents, one verb per thing that can be done to one (epic qits-1667), and what an
agent harness runs on this machine (`qits agents claude …`, below).

    qits agents list [--project] [--state] [--work] [--workspace]
                                                  GET  /workspaces/api/agents[?workId=&workspaceId=&state=&projectId=]
    qits agents show <agent>                      GET  /workspaces/api/agents/{id}
    qits agents prompt <agent> <text> [--compact-first]
                                                  POST /workspaces/api/agents/{id}/prompt {text, compactFirst}
    qits agents interrupt <agent>                 POST /workspaces/api/agents/{id}/interrupt (no body)
    qits agents stop|restart|yield <agent>        POST /workspaces/api/agents/{id}/stop, restart, yield (no body)
    qits agents sessions <agent>                  GET  /workspaces/api/agents/{id}/sessions
    qits agents transcript <agent> <session>      GET  /workspaces/api/agents/{id}/sessions/{sid}/transcript
    qits agents chat <agent>                      GET  /workspaces/agent/{id}/log?channel=TRANSCRIPT
    qits agents attach <agent>                    WS   /workspaces/agent/{id}/terminal
    qits agents files list|read|detect <agent>    GET  /workspaces/agent/{id}/files[?path=], files/content?path=,
                                                       detection
    qits agents discard <agent> [--force]         POST /workspaces/api/agents/{id}/discard {"force":…}
    qits agents draft show|save|discard <agent>   GET|PUT|DELETE /workspaces/api/agents/{id}/prompt-draft

**Naming the agent.** Every verb but `list` takes `<agent>`, the agent's id, **or** `--work
<qits-123|uuid>`, never both. `--work` looks a qualified id up in the projects service, then asks
`GET /workspaces/api/agents?workId=<uuid>&state=ACTIVE`: no active agent is exit 1, `No active agent
works on qits-123.`; more than one is exit 1 too, listing each with its slot and run state, and the
CLI never picks one (`AgentRef`). With `--work`, a verb's second positional moves up: `qits agents
prompt --work qits-123 'Go on.'`, `qits agents transcript --work qits-123 <session>`.

**Run states.** `list` and `show` print how each agent runs: QUEUED, RUNNING, YIELDED, and now STOPPED,
which `stop` puts it in. Nothing places, resumes or drives a stopped agent; `restart` hands it back to
the scheduler as YIELDED (a running agent's harness is restarted on its session instead), and
`prompt` starts it again with that turn. `yield` frees the slot of a running agent and keeps its
harness. `interrupt` presses ESC in a running harness and changes no state.

**Which doors.** The JSON API's agent doors for the agent itself; for what its daemon holds (`chat`,
`attach`, `files`), the agent proxy `/workspaces/agent/{id}/…`, which finds the slot the agent is
placed in now, so none of these takes `--workspace`. An agent waiting for a slot has no daemon: the
proxy answers 409. `attach` is the agent's Terminal tab: `qits workspaces attach`'s terminal code
(Ctrl-] detaches, the backoff, read-only without a terminal) on the agent proxy's socket; it is
`Interaction.TERMINAL`, so not an MCP tool. Every other verb is one, `agents_<verb>`, once the MCP
service carries this CLI.

Who may: every read is open to every credential, `qits:agent` included. `prompt`, `interrupt`, `stop`
and `restart` need `qits:admin`, `qits:admin-agent`, `qits:desk-admin`, `qits:desk` (a desk within
its own project) or `qits:system`; `yield` takes those and `qits:agent` for its own agent; `discard`
and `draft save|discard` need `qits:admin`, `qits:admin-agent` or `qits:desk-admin`. The agent proxy
admits `qits:system`, `qits:admin` and `qits:admin-agent` to every agent, `qits:agent` to its own,
and the desk roles to every agent's reads and no socket. `-o json` prints the service's answer, as
every `qits workspaces` command does; `--workspaces-url`, `--projects-url` and `-o` come after the
verb.

The consumer pact holds the JSON API's doors (`WorkspacesContract`); the agent proxy is forwarded raw
to the daemon and, like the container proxy, is no row of qits-workspaces' contract.

`qits agents claude …` is what an agent harness runs on this machine: `qits agents <harness> …`.
Claude Code is the one harness today. It alone is `Interaction.LOCAL`, so the MCP service does not
serve it.

### qits agents claude hook work-links

Claude Code's `MessageDisplay` hook. It shows work ids in Claude's messages, such as `qits-111`, as
links to the work item in the landing app (`<landing>/projects/<slug>/work/detail/<id>`). Only the
screen changes; the transcript and the model keep the plain id. Register it in
`~/.claude/settings.json`:

    {"hooks": {"MessageDisplay": [{"hooks": [{"type": "command",
      "command": "qits agents claude hook work-links", "timeout": 5}]}]}}

Claude holds each batch of text until the hook returns, so the hook never waits for the network
and never fails: any error prints nothing and exits `0`, and Claude shows the original. An id
inside a branch (`ticket/qits-1152`), a longer name, a link, inline code or a code block stays as it
is. A code fence can span batches, so whether one is open is kept per message in
`$XDG_RUNTIME_DIR/qits-work-links/`, and removed on the last batch.

The project slugs are cached for an hour in `$XDG_CACHE_HOME/qits/work-links.json` (default
`~/.cache/qits/`). A stale cache is used as it is, and the hook starts the same command with a
hidden `--refresh` in a session of its own, which asks the projects service with this home's
credential. Until the first refresh is done only `qits` ids are linked.

The landing app is the platform's bare domain: on a workstation the session's idp address without
`idp.` (`https://qits.<QITS_DOMAIN>`), elsewhere `https://qits.<QITS_DOMAIN>`. `--landing-url` or
`QITS_LANDING_URL` sets it. `--style osc8` or `QITS_LINK_STYLE=osc8` writes a terminal hyperlink
instead of a markdown link, and `QITS_LINK_PROJECTS` (comma-separated slugs) replaces the cache.

### qits agents claude statusline work-items

Claude Code's status line: the newest work items this session spoke of, newest first, each with
its status square and linked to the landing app (OSC 8). Register it in `~/.claude/settings.json`:

    "statusLine": {"type": "command", "command": "qits agents claude statusline work-items",
      "refreshInterval": 30}

A status line script of your own can run it too: pass on the JSON Claude gave it on stdin, and print
the line it prints.

It reads the last 200 KB of the transcript Claude names (`transcript_path`); ids count from Claude's
text and from tool results, by the work-links rules, and the newest five distinct ones are shown
(`--limit`). The marks are the session names' palette from qits-coding-agents' `EntityStatusSquare`,
copied (`StatusMarker`): a square per status, with `❗` against it when the item is blocked and `⁉️`
when the block is only its agent waiting on a person.

The states are cached for 60 seconds in `$XDG_CACHE_HOME/qits/work-states.json`. A run shows what is
cached at once (an id with nothing cached has no mark) and starts one detached `--refresh <ids>` for
the stale ids, which asks the projects service with this home's credential. Errors show the ids
without marks, or nothing; the exit is always `0`.

## qits artifacts publish

`qits artifacts` is the platform's artifacts store, qits-artifacts. `qits artifacts publish` is its
publish client: what a CI release step calls to put an sbom, a docs bundle, a daemon binary, or an
npm decision where it belongs. It is `qits-publish`, the client of the retired qits-artifacts-cli,
folded into `qits`.

**It never signs in, and it never touches the session `qits login` keeps.** It runs inside a CI step
container, with no person, on a bare alpine image (the binary is static). `qits login`,
`qits session-daemon` and `qits git-login` do not apply to it, and it reads none of their files.

Commands:

    qits artifacts publish sbom submit --type <npm|maven|docker|daemon> --name <n> --version <v> --file <path>
    qits artifacts publish sbom from-dockerfile --root-name <n> --root-version <v> \
                      [--dockerfile <path>]... [--build-arg NAME=value]... -o <out.json>
    qits artifacts publish docs submit --site <name> --version <v> (--archive <tgz> | --openapi <file> | --dir <directory>) \
                      [--if-changed] [--meta key=value]...
    qits artifacts publish changelog --version <v> [--meta key=value]...
    qits artifacts publish daemon submit --name <n> --version <v> --file <bin>
    qits artifacts publish exists <daemon|docs|npm|sbom> <name> <version>
    qits artifacts publish npm plan --package <n> --version <v>
    qits artifacts publish npm dist-tag --package <n> --version <v> --tag <t>

`qits artifacts publish <command> --help` shows a command's options, examples and exit codes;
`qits help skill` includes them all.

**`docs submit`** publishes a docs bundle from exactly one source: a ready `--archive`, one
`--openapi` document (packed as `openapi.yml`/`openapi.json`, the `@apidocs` publish), or a `--dir`
packed here (qits-1948): every regular file under it, named relative to it so `manifest.json` sits at
the bundle root, in a deterministic `.tar.gz` (sorted entries, fixed modes, owners and mtimes).
Symbolic links are skipped, never followed, as the store skips them in an uploaded archive; a
missing directory, or one with no regular file, is exit 1. `--if-changed` computes the bundle's
content hash, `sha256:` over `<path>\0<sha256 hex of its bytes>\n` per file sorted by path (so it
depends on paths and bytes only), and reads the site's listing (`GET /artifacts/docs/docs/<site>`):
when the newest version records the same `content.hash` it prints `unchanged since <version>` and
uploads nothing; otherwise (no such site, no hash recorded, a different one) it publishes with
`X-Artifacts-Meta-content.hash` and prints `published <version>`. A listing that is not an answer
(a 5xx, a 401, an unreadable body) is exit 2, never read as either; `--meta content.hash=` is
refused beside `--if-changed`. Without `--if-changed` nothing is hashed and an occupied version is
skipped with its WARN, as before.

**`changelog`** is run by every release's publish step (qits-893): `qits artifacts publish changelog
--version "$QITS_VERSION" --meta git.commit.hash="$QITS_CI_SHA" --meta
git.repository.name="$QITS_CI_REPO_NAME"`. It writes the release's `CHANGELOG.md` and publishes it as
the docs site `@changelog/<repositoryName>` at that version, a `.tar.gz` with that one entry, with
`release.request.id=<uuid>` added to the metadata, and `release.request.qualified-id=<id>` when the
event names the request's logical id. The release is the step's `QITS_EVENT_PAYLOAD` (the
SCMRelease: `repository`, `repositoryName` and `releaseRequestId` required,
`releaseRequestQualifiedId` and `occurredAt` optional; the document names the request by its logical
id when it has one); the commits are the release request's (qits-projects, oldest first, the request's own
fold commits left out); the tickets are every qualified id the commits' subjects name in their scope
(`feat(qits-9, qits-10): ...`), titled by qits-projects and linked to
`https://qits.$QITS_DOMAIN/projects/<slug>/work/detail/<id>`; and the report highlights are the gate
run's (`QITS_CI_RUN_ID`, qits-ci's `/runs/<id>/gate/reports`), a section left out when there are none.
The document always ends in `associated tickets:` and the resolved ids, each in backticks; that line
is what `qits changelog bump-message` reads back. A ticket qits-projects does not know is listed as
`(no such work item)` and left out of that line. Exit codes as for `docs submit`: an occupied version
is skipped with a warning (0), a missing or incomplete payload or an unknown release request is 1, a
service that cannot be reached or answers 5xx is 2. Changelogs are kept for good for now; deleting
them with their release during GC is not implemented.

**Addresses are code, not configuration.** The one input is `QITS_DOMAIN`, the platform's bare public
domain. There is no default: qits-ci injects it into every step, and its absence fails loudly. The
store is `https://registry.qits.$QITS_DOMAIN` — hosted npm under
`/artifacts/npm/npm`, hosted maven under `/artifacts/maven/maven`, docs under `/artifacts/docs/docs`,
and the sbom, daemon and content-hash stores beside them — and a pom outside the reactor is read from
there, then from qits-mirror's Maven Central cache at
`https://mirror.qits.$QITS_DOMAIN/mirror/maven/central`. No `QITS_*_URL` variable is read, and a
lockfile is never rewritten: it names the public hosts, which answer inside the platform too.

**Credentials**: a bearer on every request. Only a CI run may publish to qits-artifacts — the store
refuses an anonymous publish — and its reads want a bearer too, so every PUT, GET and HEAD this
client makes carries an `Authorization` header. The token comes from the first of these the
environment has:

1. `QITS_PUBLISH_TOKEN_COMMAND` — an executable that prints a fresh token on stdout. Preferred, and
   run again for **every request** rather than once per process: a release step can publish an hour
   after it started, and a token minted at the top of the step would be expired by then. qits-ci
   writes `/tmp/qits-publish-token` and points this at it.
2. `QITS_PUBLISH_TOKEN` — a token, used verbatim.

With neither of them the request is still **sent**, without the header, and the store answers `401`.
That is deliberate: the store is the authority on who may write, and refusing here would replace its
refusal — which names the real reason — with a client-side error naming a variable. No token is ever
printed, to stdout, to stderr or into an error message.

**The one idempotency policy**, unchanged from `qits-publish` and identical across every surface:
absent, PUT it and say what landed; occupied with the same bytes, say so and succeed (a retried or
replayed step must go green); occupied with different bytes, fail naming both digests (a coordinate
must never come to mean two things); occupied and not comparable (docs: the store keeps no archive
digest), warn and skip.

**Exit codes**: `0` published, or already published with the same bytes. `1` refused, and re-running
will not help: invalid arguments, a 4xx, or an occupied coordinate holding different bytes (`exists`
also uses it for "absent"). `2` could not ask, or could not be answered: no store configured, an I/O
failure, or a 5xx. A step may retry a `2` and must not retry a `1`.

### Started as `qits-publish`

A binary or a symlink started under the name `qits-publish` — its own name, before it folded into
`qits` — behaves exactly as `qits artifacts publish`: `qits-publish sbom submit ...` is
`qits artifacts publish sbom submit ...`, argument for argument. `qits artifacts publish`'s own
release still publishes the binary as `qits-platform-access-cli`; a step that still says
`qits-publish` needs only that name on its `PATH`, as a copy or a symlink of `qits`. New pipelines
should call `qits artifacts publish` directly.

## qits artifacts fetch deployed-pacts

    qits artifacts fetch deployed-pacts --provider <provider repository> --out <dir> [-o json]

The consumer pacts a provider has to keep honouring (qits-1765): for every consumer version that is
deployed in some environment, or kept there as the rollback target, the pact package it was built
against. A provider's release gate runs it before `./mvnw verify` and points its pact loader at
`<dir>`, so each deployed consumer version is verified on its own rather than only the version the
pom pins.

qits-maintenance makes the join (`GET /maintenance/api/deployed-pacts?provider=<repository>`: what
qits-deployments says serves, which repository built each application, and the newest pact package
not above each deployed version), and the command only downloads. Each package's jar,
`<groupId>:<artifactId>:<pactVersion>`, comes from qits-artifacts' hosted maven repository
(`https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven`), and its top-level
`pacts/*_<provider>.json` entries are written to `<dir>/<consumer repository>/<pact version>/`.
That directory is replaced as a whole; `<dir>` is created when missing.

    fetched qits-maintenance-service pact 2026.1028.235307 for deployed 2026.1030.204903, 2026.1030.192107
    fetched 1 pact for qits-projects-service into target/deployed-pacts

No deployed consumer prints `no deployed consumer pacts for <provider>` and exits `0`. `-o json`
prints qits-maintenance's answer with `files` (the paths written) on each pact and `out`.

**It fails closed.** A gate that verified fewer pacts than are deployed would pass for the wrong
reason, so a refusal from qits-maintenance (`503` when qits-deployments cannot say what serves), a
jar that cannot be downloaded and a jar without `pacts/*_<provider>.json` are each exit `1`, with
a sentence naming it. A name in the answer that is not a plain path segment is refused before
anything is written.

Both reads go out with the command's credential like every other platform command's: the run's own
`QITS_TOKEN` in a CI step, the workspace's token, or the session on a workstation. It writes files
on the caller's disk, so it is `Interaction.LOCAL` and the MCP service does not serve it.

## qits changelog

What reads the changelogs every release publishes (`qits artifacts publish changelog`, above). Like
`qits artifacts publish` it runs in a CI step, never signs in, and presents the same bearer chain
(`QITS_PUBLISH_TOKEN_COMMAND`, then `QITS_PUBLISH_TOKEN`).

    qits changelog bump-message --group <group> --applied <file> --body <file>

`bump-message` prints the commit message of one dependency-bump step (qits-1144). `--applied` holds
one `<ecosystem><TAB><name>` line per change the step applied, and N is their count; `--body` is the
step's own body (`- maven g:a 1 -> 2 (pom.xml)` lines), printed as it is with its trailing newlines
trimmed to one. The step's `QITS_EVENT_PAYLOAD` lists the requested changes in `changes[]`, each with
an optional `changelog: {repository, versions: [...]}`. For every applied change that has one, each
version's changelog is read from
`https://registry.qits.$QITS_DOMAIN/artifacts/docs/docs/@changelog/<repository>/-/<version>/CHANGELOG.md`;
a version two coordinates name is read once. The output:

    chore(qits-9, qits-10): bump(<group>): <N> dependencies

    <body>

    ## <repository>
    # <version>
    <that version's CHANGELOG.md>

    # <version>
    <...>

Repositories are sorted by name, versions in version order (numerically, dot segment by dot
segment). The scope is every ticket the changelogs' `associated tickets:` lines name, sorted by
project and then number; with none the subject is plain `bump(<group>): <N> dependencies`, and with no
changelog to read the message is the subject and the body alone. A changelog that is not published
(404) or whose last line is not `associated tickets: ...` is exit `1`; a store that cannot be reached
or answers 5xx is `2`, which a step may retry.
