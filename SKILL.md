---
name: qits
description: "Use for any work on the qits platform from a terminal: signing in, projects and repositories, work items of every archetype (epics, tickets, features, tasks, issues) and their comment threads, ideas, UI pitches and what the UI builder reads, release requests, CI runs and their logs, domain events, live telemetry, waiting on the platform, workspaces and their agents, execution targets and their leases, Git pushes to the platform's git host, and publishing release artifacts from a CI step."
---

# qits

Each command calls the platform through its edge, with the session of `qits login`, except `qits artifacts publish`, which runs in a CI step container with no person and never touches that session. `qits <command> --help` shows a command's options, examples and exit codes.

A workspace is the token home: on a runner node, or an admin or editor workspace placed directly on qits-containers, with QITS_TOKEN set every command sends that token as it is, to the public vhosts https://<app>.qits.<QITS_DOMAIN>. It wins over the session, nothing is minted or written to disk, and a 401 means the token was deleted with the workspace's container. QITS_URL_<APP> still overrides an address.

## Platform rules

- Sign in once with `qits login`, and keep `qits session-daemon` running so the session stays fresh.
- Release only through a release request: `qits release-request create`, or `join` to add a branch to an open one. A push releases nothing.
- Push only branches under refs/heads/external/, after `qits git-login`. Git gets the token from `qits git-credential`.
- On a workstation, npm, Maven and Docker get the platform's registries from `qits registry-login --configure`: npm and Maven a token kept fresh by `qits session-daemon`, Docker its helper `qits docker-credential`. Do not write registry tokens by hand.
- qits never prints a token. The two exceptions are `qits git-credential get`, which Git runs, and `qits docker-credential get`, which Docker runs. Two commands print a secret meant for one person: `qits users invite` (a register token) and `qits users register` (the new password, once).
- To wait for something on the platform, declare it with `qits wait` and end your turn: you get a new turn when it happens. Never poll for it in a background loop.
- A command that says `Not signed in` or `Session ended` exits with 2: run `qits login`.
- For how to develop on the platform, run `qits help guide`: it prints the platform's current guide for where it runs (a workstation, or an agent, desk or refinement room inside). It leads with `guide-hash: <hash>` when the service sends one; `--hash-only` prints just that line, to check cheaply whether the guide changed.

## Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits login

Sign in through the browser and store the session. Run it first, and again when a command says `Not signed in` or `Session ended`.

The session goes to $XDG_CONFIG_HOME/qits/t.json (default ~/.config/qits/t.json). The page shows a code: paste it at `Paste the code:`. The codes of one sign-in last 5 minutes.

```
qits login [--idp-url <url>] [--no-browser]
```

| Name | What it does |
|---|---|
| `--idp-url <url>` | The idp's public base URL. Default: QITS_IDP_URL, else https://idp.qits.<QITS_DOMAIN>/idp; with neither, the command refuses. |
| `--no-browser` | Only print the sign-in address; do not start a browser. |

### Examples

```
qits login
qits login --idp-url https://idp.qits.<QITS_DOMAIN>/idp
qits login --no-browser
```

Over SSH, use --no-browser and open the printed address on a machine with a browser.

### Exit codes

- `0` Signed in; the session is stored.
- `1` The sign-in did not complete.
- `2` Used wrongly, or no platform is named (no --idp-url, QITS_IDP_URL or QITS_DOMAIN).

## qits session-daemon

Keep the session from `qits login` fresh for as long as this runs. Start it once per workstation and leave it running.

It refreshes the access token shortly before it expires and writes the new pair to $XDG_CONFIG_HOME/qits/t.json. It logs one line per event to stderr, and stops on SIGTERM or SIGINT.

```
qits session-daemon [--margin <seconds>]
```

| Name | What it does |
|---|---|
| `--margin <seconds>` | Refresh this many seconds before the access token expires, 0 to 300. Default: 30. |

### Examples

```
qits session-daemon &
systemctl --user enable --now qits-session-daemon
```

- `qits registry-login --configure --install-daemon` installs and starts the systemd user unit; the README has it too. Only one daemon runs at a time; a second one exits with 1.
- When the session ends (revoked or expired), it says so, keeps running, and carries on after the next `qits login`.
- Without it, a command refreshes an access token that is about to expire before it calls the platform.
- After `qits registry-login --configure` it also writes each new token into the registry blocks of ~/.npmrc and ~/.m2/settings.xml, and refreshes at least 5 minutes before expiry.

### Exit codes

- `0` Stopped by SIGTERM or SIGINT.
- `1` Another qits session-daemon is running.
- `2` --margin is not between 0 and 300.

## qits checkout-daemon

Hold a local checkout at what a repository released, and keep it there. It follows the releases, never the tips of the branches: the root ends detached at the release tag, and every submodule detached at the gitlink that release recorded. That tree is the estate somebody reviewed and released, which is not the same thing as `latest`.

It brings the checkout to the newest release at the start, then waits on the live event stream for a release request of the repository to be finalized (ReleaseRequestStateChanged to FINALIZED), never for the tag alone. After every connect it reads the newest one back, so a release finalized while it was stopped or reconnecting is not missed, and a release older than the one it holds never moves it back. Notes go to stderr, and every release it moves the checkout to is one line on stdout. Stops on SIGINT or SIGTERM.

```
qits checkout-daemon [--events-url <url>] [--once] [--path <dir>] [--repository <name>] [--submodules]
```

| Name | What it does |
|---|---|
| `--events-url <url>` | The events service's base URL, without /events. Default: QITS_EVENTS_URL, else the session's idp address with `idp` swapped for `events`. |
| `--once` | Bring the checkout to the newest release and exit; do not open the stream. |
| `--path <dir>` | The checkout to hold. Default: the directory the command runs in. |
| `--repository <name>` | Whose releases to follow. Default: the repository the checkout's origin names. |
| `--submodules` | Hold the submodules at the gitlinks the release recorded. --no-submodules holds the root alone. Default: true. |

### Examples

```
qits checkout-daemon --path /workspace
qits checkout-daemon --path /workspace --once
qits checkout-daemon --path /srv/qits --repository qits-qits --no-submodules
```

- A checkout with local changes is never touched: it is somebody's work. It says so and keeps watching; with --once that is exit code 1. It never stashes, resets or merges.
- Git authentication stays the credential helper's: run `qits git-login` on a workstation; in a container the image's helper answers. No token is ever put in a URL.
- With QITS_PLATFORM set and no QITS_TOKEN it refuses at the start, before any git work.
- --repository is needed when the origin addresses the repository by its id (/git/<repository id>), which is the git host's internal storage scheme and names nothing.

### Exit codes

- `0` Stopped by SIGINT or SIGTERM, --once is done, or stdout was closed.
- `1` The platform refused the stream (401, 403 or another 4xx), or with --once the checkout has local changes or a git command failed.
- `2` Used wrongly (the origin names no repository and --repository was not given), Git has no credential for the origin's host, not signed in, the session ended, or QITS_PLATFORM is set without QITS_TOKEN.

## qits checkout

A workstation clone of a wrapper, every submodule on its own main: `sync` fast-forwards it to origin/main. A checkout that follows releases is `qits checkout-daemon`'s.

## qits checkout sync

Bring a workstation clone of a wrapper up to date: the root and every submodule, nested ones (a service's webui) included, fast-forwarded on its own main to origin/main, and every submodule .gitmodules newly declares initialised and put on main, tracking origin/main.

Pure git: no sign-in, no platform. Each repository is fetched from its own origin with whatever credential helper git has, and moved only by `git merge --ff-only`. A repository that is detached, on another branch, has tracked changes, has commits origin/main lacks, or whose fast-forward git refuses is left exactly as it was and named with the reason. Untracked files never block.

stdout has one line per repository that moved (`<path> +<n> commits`), then the new ones (`<path> new`), then the skipped ones (`<path> skipped: <reason>`), then a summary line. A repository already at origin/main prints nothing of its own.

```
qits checkout sync [--path <dir>]
```

| Name | What it does |
|---|---|
| `--path <dir>` | The clone to sync. Default: the directory the command runs in. |

### Examples

```
qits checkout sync
qits checkout sync --path ~/src/qits-qits
```

- It never resets, stashes, switches over local work, stages a path or recurses through git (--recurse-submodules, submodule.recurse): a moved gitlink in a parent's index is the expected drift `ignore = all` hides, and the bumps own it.
- A submodule whose .git file points at a gitdir that is not there (left by a move of the tree) is skipped and not descended into; the line says how to repair it.
- It refuses a project agent container's checkout, a root detached at a release tag or one a `qits checkout-daemon` holds: that checkout follows releases, and the daemon keeps it.

### Exit codes

- `0` Every repository is at origin/main, moved there, or new.
- `1` A repository was skipped or a fetch or fast-forward failed; every other one was still synced.
- `2` Used wrongly: the path is not in a Git repository, or it is a checkout that follows releases (a project agent container's).

## qits projects

The platform's projects: list shows them, delivery shows one's delivery desk, desk talks to one's front desk, agents talks to one's named agents. Other commands take a project's id, slug or name as --project.

## qits projects list

List the projects: slug, name and id.

```
qits projects list [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits projects list
qits projects list -o json
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits projects delivery

A project's delivery desk: whether its front desk holds the delivery rights, how many items it keeps running at once, whether it may move a REFINED item to READY_FOR_DEV itself, and the approvals it may give. The desk runs only when a person prompts it.

The project's wrapper declares it in .config/qits/project.yml under front_desk.delivery. A setting it does not declare prints as the default, marked (default).

```
qits projects delivery [--output table|json] [--project <project>] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project (required): its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits projects delivery --project qits
qits projects delivery --project qits -o json
```

- -o json prints the project's delivery object as the service sent it, null when it sent none.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly (for example no --project, or one the platform does not know), not signed in, or the session ended.

## qits projects desk

A project's front desk: prompt types a turn into its kept front desk session, answer shows that session's last answer, triggers shows the messages its desk triggers sent it.

The session is the one the project's front desk keeps running in its agent container. It runs only when a person prompts it; nothing prompts it on a schedule. It is not a session a person started from the desk panel.

### Notes

- prompt and answer take qits:admin, qits:admin-agent or qits:desk-admin; an agent with qits:agent alone is refused (HTTP 403). triggers is a plain read, open to qits:agent too.

## qits projects desk prompt

Type a turn into the project's front desk session. The words after the options are joined with spaces into the turn's text.

Sends POST /projects/api/projects/{projectId}/front-desk/prompt. When the session is idle the turn is typed at once (delivered). While it is busy or waiting on a permission prompt, the turn waits in the desk's queue and is typed in order once the session is idle (queued, with its position). Either way it prints the command id; `qits projects desk answer` shows what the desk answered.

```
qits projects desk prompt [--output table|json] [--project <project>] [--projects-url <url>] [<text>]
```

| Name | What it does |
|---|---|
| `<text>` | The turn's text (required). The words are joined with spaces. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project (required): its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits projects desk prompt --project qits What is in flight right now?
qits projects desk prompt --project qits -o json Summarise the open release requests
qits projects desk prompt --project qits Run one pass of the delivery loop.
```

- Takes qits:admin, qits:admin-agent or qits:desk-admin; an agent with qits:agent alone is refused (HTTP 403).
- The turn goes to the session the front desk keeps. While the turn runs the session is busy, and a later turn waits in the queue until it is idle again.
- A delivery pass runs only when prompted, as in the last example; nothing starts one on a schedule (qits-1597).
- -o json prints the service's answer: commandId, state, delivered, queued, position.

### Exit codes

- `0` The desk took the turn: delivered, or queued.
- `1` The platform refused (for example your roles, HTTP 403, a project it does not know, HTTP 404, a front desk that is not running, HTTP 409, a full queue, HTTP 429, or no projects daemon to reach the desk, HTTP 503), or cannot be reached.
- `2` Used wrongly (for example no --project, one the platform does not know, or no text), not signed in, or the session ended.

## qits projects desk answer

Show the last answer of the project's front desk session: its text, when it was given, the command and session it came from, and the session's state.

Reads GET /projects/api/projects/{projectId}/front-desk/last-answer. A desk that has not answered yet says so and exits with 0.

```
qits projects desk answer [--output table|json] [--project <project>] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project (required): its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits projects desk answer --project qits
qits projects desk answer --project qits -o json
```

- Takes qits:admin, qits:admin-agent or qits:desk-admin; an agent with qits:agent alone is refused (HTTP 403), although it changes nothing.
- The last answer is the session's, whatever turn it answered: a prompt of yours or somebody else's.
- -o json prints the service's answer: commandId, sessionId, state, text, at.

### Exit codes

- `0` Done, also when the desk has not answered yet.
- `1` The platform refused (for example your roles, HTTP 403, a project it does not know, HTTP 404, or no projects daemon to reach the desk, HTTP 503), or cannot be reached.
- `2` Used wrongly (for example no --project, or one the platform does not know), not signed in, or the session ended.

## qits projects desk triggers

The messages the project's desk triggers sent its front desk: one row per window, newest first by when it opened.

Reads GET /projects/api/projects/{projectId}/desk-triggers. A window is OPEN (still collecting), OWED (closed, not yet delivered), DELIVERED or QUEUED (handed to the desk, with the command it became) or FAILED (never to be delivered).

```
qits projects desk triggers [--limit <n>] [--output table|json] [--project <project>] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--limit <n>` | At most this many messages, the newest. 1 to 100. Default: 20. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project (required): its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits projects desk triggers --project qits
qits projects desk triggers --project qits --limit 50
qits projects desk triggers --project qits -o json
```

- Open to qits:agent as well as a person or admin agent: it reads but does not write.
- --limit is 1 to 100; the service defaults to 20 when it is not given.
- The table shows one row per message: when it opened, its state, how many lines it collected, their sources, how many deliveries were tried and when the desk accepted it. -o json prints the service's answer in full, each message's events included.

### Exit codes

- `0` Done, also when there are no messages.
- `1` The platform refused (for example a --limit outside 1..100, HTTP 400, or a project it does not know, HTTP 404), or cannot be reached.
- `2` Used wrongly (for example no --project, or one the platform does not know), not signed in, or the session ended.

## qits projects agents

A project's named agents: list shows them, prompt types a turn into one's kept session, answer shows that session's last answer.

A named agent is a kept Claude session in the project's desk container (qits-2154): `desk` is the front desk, `ui-builder` the UI builder's agent. Each runs only when prompted and stops when idle; prompting one starts the desk container when it is stopped.

### Notes

- Every subcommand takes qits:admin, qits:admin-agent or qits:desk-admin; an agent with qits:agent alone is refused (HTTP 403), list and answer included, although neither writes.

## qits projects agents list

List the project's named agents: their surface, state, session and terminal command id.

Sends GET /projects/api/projects/{projectId}/agents. state is one of STOPPED, STARTING, IDLE, BUSY or FAILED; the desk is listed first.

```
qits projects agents list [--output table|json] [--project <project>] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project (required): its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits projects agents list --project qits
qits projects agents list --project qits -o json
```

- -o json prints the service's answer: agents[].{name,surface,state,sessionId,commandId}.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits projects agents prompt

Type a turn into a named agent's kept session. The words after the options are joined with spaces into the turn's text.

Sends POST /projects/api/projects/{projectId}/agents/{name}/prompt. When the agent's session is idle the turn is typed at once (delivered). While it is busy, the turn waits in the agent's own queue and is typed once it is idle (queued, with its position). When the desk container is stopped, prompting it starts the container; the turn then waits and is delivered once the agent's session first comes up. Either way it prints the command id; `qits projects agents answer` shows what the agent answered.

A named agent is a kept Claude session in the project's desk container (qits-2154): `desk` is the front desk, `ui-builder` the UI builder's agent.

```
qits projects agents prompt [--name <name>] [--output table|json] [--project <project>] [--projects-url <url>] [<text>]
```

| Name | What it does |
|---|---|
| `<text>` | The turn's text (required). The words are joined with spaces. |
| `--name <name>` | The named agent (required): desk, ui-builder, or another the project declares. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project (required): its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits projects agents prompt --project qits --name ui-builder Pitch a calmer header.
qits projects agents prompt --project qits --name ui-builder -o json Mock the empty state.
```

- Takes qits:admin, qits:admin-agent or qits:desk-admin; an agent with qits:agent alone is refused (HTTP 403).
- The turn goes to the named agent's own session, beside the desk's; prompting it never disturbs the front desk's session or queue.
- -o json prints the service's answer: commandId, state, delivered, queued, position.

### Exit codes

- `0` The agent took the turn: delivered, queued, or the desk container is starting and the turn waits.
- `1` The platform refused (for example your roles, HTTP 403, a project it does not know, or an agent the project does not name, HTTP 404, a prompt already waiting or the agent not running, HTTP 409, a full inbox, HTTP 429, or no projects daemon to reach the desk container, HTTP 503), or cannot be reached.
- `2` Used wrongly (for example no --project, one the platform does not know, no --name, or no text), not signed in, or the session ended.

## qits projects agents answer

Show a named agent's last answer: its text, when it was given, the command and session it came from, and the session's state.

Sends GET /projects/api/projects/{projectId}/agents/{name}/last-answer. An agent that has not answered yet says so and exits with 0.

```
qits projects agents answer [--name <name>] [--output table|json] [--project <project>] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--name <name>` | The named agent (required): desk, ui-builder, or another the project declares. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project (required): its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits projects agents answer --project qits --name ui-builder
qits projects agents answer --project qits --name ui-builder -o json
```

- Takes qits:admin, qits:admin-agent or qits:desk-admin; an agent with qits:agent alone is refused (HTTP 403), although it changes nothing.
- The last answer is that agent's own session's, whatever turn it answered: a prompt of yours or somebody else's.
- -o json prints the service's answer: commandId, sessionId, state, text, at.

### Exit codes

- `0` Done, also when the agent has not answered yet.
- `1` The platform refused (for example your roles, HTTP 403, a project it does not know, or an agent the project does not name, HTTP 404, or no projects daemon to reach the desk container, HTTP 503), or cannot be reached.
- `2` Used wrongly (for example no --project, one the platform does not know, or no --name), not signed in, or the session ended.

## qits repositories

The repositories of one project: list shows them, create stands a new one up. Release requests take a repository's id or name as --repository.

branches, commits, commit-changes, commit-diff and commit-subjects read one repository's git history; each takes the repository as --repository <id or name>.

archive and unarchive ask for repository.archived in the repository's .config/qits/repository.yml, released through its own release request.

remote-login attaches this terminal to a repository's backup-remote sign-in, where git asks for the credentials the project's backups push with.

backup reset makes a repository's backup twin ours when its main shares no history with ours (a forge's seed commit).

A repository's name says the role it plays to a reader, but it decides nothing (owner ruling 2026-11-03): state the kind with create's --archetype, or declare it in the repository's own .config/qits/repository.yml (repository.archetype), which the wrapper reconcile applies. With neither, the repository simply has no archetype yet.

## qits repositories list

List the project's repositories: name, archetype, component and id. --project may come before or after list.

```
qits repositories list [--output table|json] [--project <project>] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits repositories --project qits list
qits repositories list --project qits -o json
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits repositories create

Create a repository in the project: a blank one on the platform's git host, seeded with the repository template, and mounted in the project's wrapper.

The NAME says what kind of component it is to a reader; it decides nothing. State the kind with --archetype, or leave it to the repository's own .config/qits/repository.yml (repository.archetype), which a wrapper reconcile applies. With neither, the repository has no archetype until one is declared. Name the repository <component>[-<modifier>]-<role> all the same, so a reader still sees the role.

--component places the wrapper entry at components/<component>/<name>. Without it the wrapper's own layout decides.

```
qits repositories create [--archetype <archetype>] [--component <component>] [--output table|json] [--project <project>] [--projects-url <url>] <name>
```

| Name | What it does |
|---|---|
| `<name>` | The repository's name. Its role suffix tells a reader what kind of component it is, but decides nothing: state the kind with --archetype. It is also what ../<name>.git resolves to. |
| `--archetype <archetype>` | The kind of component this is: SERVICE, DAEMON, LIBRARY, FRONTEND, APP, CLI or IMAGE, any case. Default: none: the repository has no archetype until this, or its .config/qits/repository.yml, declares one. |
| `--component <component>` | The technical component to mount the entry under: components/<component>/<name>. Default: the wrapper's own layout decides. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits repositories --project qits create qits-docs-app --component qits-docs --archetype app
qits repositories create qits-payments-service --project qits --component qits-payments --archetype service
qits repositories --project qits create qits-docs-app -o json
```

- --archetype: SERVICE, DAEMON, LIBRARY, FRONTEND, APP, CLI or IMAGE, any case. Anything else is left for the service to refuse.
- Creating a repository needs the role qits:admin or qits:agent.
- The command prints what the service answered: the name, the archetype (if any was declared), the component, the id, and the wrapper path the entry was written at.

### Exit codes

- `0` The repository exists and the wrapper names it.
- `1` The platform refused (the message names the status and what to do next), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits repositories archive

Archive a repository: ask for repository.archived: true in its .config/qits/repository.yml, on the branch archive/<repository name> (an absent file is created). Never a push to main.

Reads the file at main first: when it already says archived: true nothing is opened and the outcome is unchanged (HTTP 200). Otherwise the edit is committed onto archive/<name> (the branch is created at main, or brought up to date with it) and the branch is asked for through POST .../release-requests, which creates or joins the repository's open request (HTTP 202, outcome requested).

qits-maintenance stops bumping an archived repository's dependencies unless its maintenance.yml sets dependency-bumps: true.

POST /projects/api/repositories/{id}/archive.

```
qits repositories archive [--output table|json] [--project <project>] [--projects-url <url>] [--repository <repository>]
```

| Name | What it does |
|---|---|
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |
| `--repository <repository>` | The repository (required): its id or name. |

### Examples

```
qits repositories --project qits archive --repository qits-ci-service
```

- Needs qits:admin, qits:admin-agent, qits:desk-admin, or qits:desk bound to its own project; qits:agent alone is refused (HTTP 403).

### Exit codes

- `0` The repository already said archived: true (outcome unchanged), or the edit landed and a release request was asked for (outcome requested).
- `1` The platform refused (for example a conflicting branch or a repository with no name, HTTP 409, or the file cannot be edited safely, HTTP 422), or cannot be reached (HTTP 502 or 503).
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits repositories unarchive

Take a repository out of the archive: ask for repository.archived to be removed from its .config/qits/repository.yml, on the branch unarchive/<repository name>.

Reads the file at main first: when it does not say archived: true nothing is opened and the outcome is unchanged (HTTP 200); a file that is absent is already unarchived. Otherwise the edit is committed onto unarchive/<name> and the branch is asked for through POST .../release-requests, which creates or joins the repository's open request (HTTP 202, outcome requested).

POST /projects/api/repositories/{id}/unarchive.

```
qits repositories unarchive [--output table|json] [--project <project>] [--projects-url <url>] [--repository <repository>]
```

| Name | What it does |
|---|---|
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |
| `--repository <repository>` | The repository (required): its id or name. |

### Examples

```
qits repositories --project qits unarchive --repository qits-ci-service
```

- Needs qits:admin, qits:admin-agent, qits:desk-admin, or qits:desk bound to its own project; qits:agent alone is refused (HTTP 403).

### Exit codes

- `0` The repository already did not say archived: true (outcome unchanged), or the edit landed and a release request was asked for (outcome requested).
- `1` The platform refused (for example a conflicting branch or a repository with no name, HTTP 409, or the file cannot be edited safely, HTTP 422), or cannot be reached (HTTP 502 or 503).
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits repositories branches

List a repository's branches: each with its parent, how far it is ahead of and behind that parent, and whether it can be cleaned up (fully merged, nothing depends on it).

Reads GET /projects/api/repositories/{id}/branches.

```
qits repositories branches [--output table|json] [--project <project>] [--projects-url <url>] [--repository <repository>]
```

| Name | What it does |
|---|---|
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |
| `--repository <repository>` | The repository (required): its id or name. |

### Examples

```
qits repositories --project qits branches --repository qits-ci-service
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits repositories commits

List the commits on a branch that are not on its parent, newest first, each with the files it changed.

Reads GET /projects/api/repositories/{id}/commits?branch=<branch>. The table shows hash, date, author, file count and subject; -o json adds the full message, the files and the parents.

```
qits repositories commits [--branch <branch>] [--output table|json] [--project <project>] [--projects-url <url>] [--repository <repository>]
```

| Name | What it does |
|---|---|
| `--branch <branch>` | The branch whose commits to list (required). |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |
| `--repository <repository>` | The repository (required): its id or name. |

### Examples

```
qits repositories --project qits commits --repository qits-ci-service --branch ticket/x
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits repositories commit-changes

List the files one commit changed against its first parent, or against --parent: path, old path for a rename, and change type.

Reads GET /projects/api/repositories/{id}/commits/{hash}/changes. Use commit-diff for one file's diff.

```
qits repositories commit-changes [--commit <hash>] [--output table|json] [--parent <hash>] [--project <project>] [--projects-url <url>] [--repository <repository>]
```

| Name | What it does |
|---|---|
| `--commit <hash>` | The commit (required): its full or short hash. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--parent <hash>` | The commit to compare against. Default: the commit's first parent. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |
| `--repository <repository>` | The repository (required): its id or name. |

### Examples

```
qits repositories --project qits commit-changes --repository qits-ci-service --commit 1a2b3c4
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits repositories commit-diff

Print the unified diff of one file in one commit, against the same base as commit-changes. A binary file or a pure rename has an empty diff.

Reads GET /projects/api/repositories/{id}/commits/{hash}/diff?path=<path>.

```
qits repositories commit-diff [--commit <hash>] [--output table|json] [--parent <hash>] [--path <path>] [--project <project>] [--projects-url <url>] [--repository <repository>]
```

| Name | What it does |
|---|---|
| `--commit <hash>` | The commit (required): its full or short hash. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--parent <hash>` | The commit to compare against. Default: the commit's first parent. |
| `--path <path>` | The file (required): its path in the repository. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |
| `--repository <repository>` | The repository (required): its id or name. |

### Examples

```
qits repositories --project qits commit-diff --repository qits-ci-service --commit 1a2b3c4 --path README.md
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits repositories commit-subjects

Count how many of a branch's newest commits fit one of qits-githost's accepted commit-message variants, and list the ones that fit none of them.

The variants: MERGE (any merge commit), BUMP (bump(<group>): N dependencies), RELEASE (release(<version>): ...), PLATFORM (chore(platform): ...), and SUBJECT (term(<project>-<n>[, <project>-<n>]): message, for example feat(qits-1337): add the export). qits-githost refuses a new commit that fits none of them, on every repository; a commit made before the rule took effect may still show as non-complying.

Reads GET /projects/api/repositories/{id}/commit-subjects.

```
qits repositories commit-subjects [--branch <branch>] [--limit <n>] [--output table|json] [--project <project>] [--projects-url <url>] [--repository <repository>]
```

| Name | What it does |
|---|---|
| `--branch <branch>` | The branch to read. Default: the repository's main branch. |
| `--limit <n>` | How many of the newest commits to read. Default 100, at most 1000. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |
| `--repository <repository>` | The repository (required): its id or name. |

### Examples

```
qits repositories --project qits commit-subjects --repository qits-ci-service
qits repositories --project qits commit-subjects --repository qits-ci-service --branch ticket/x --limit 20
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits repositories remote-login

Attach this terminal to a repository's backup-remote sign-in: the server runs an interactive git push to the backup remote, and git's username and password prompts come here. What you answer fills the credential store every backup of the project pushes with, so one sign-in fixes every repository whose last backup says AUTH_REQUIRED.

Without --repository it is the project repository (the one whose .gitmodules names the others), as the project page's Backups panel uses: what the sign-in authenticates is the host, not the repository. The repository needs a backup remote.

Ctrl-] detaches; the sign-in waits about a minute for you to attach again (run the same command), and the server replays what the terminal held. Every other key goes to git. A dropped connection attaches again after 0.5, 1, 2, 4 and 4 s.

When the sign-in ends (git exits, or the server refuses in the terminal, for example because a push is already running), it asks for every backup of the project to run again (POST /projects/api/projects/{id}/repositories/backup-sync), waits 3 s and prints each repository's last backup. The pushes run in the background, so a result still says AUTH_REQUIRED until its push has run: ask again with `qits repositories list -o json`.

With stdin not a terminal it only streams what the terminal prints: nothing is typed.

WS /projects/api/repositories/{id}/remote-login. Needs qits:admin or qits:admin-agent; qits:agent alone is refused, and so is qits:desk-admin: the socket runs an interactive push to the forge, and the delivery desk never pushes (epic qits-1621).

```
qits repositories remote-login [--output table|json] [--project <project>] [--projects-url <url>] [--repository <repository>]
```

| Name | What it does |
|---|---|
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |
| `--repository <repository>` | The repository whose backup remote to sign in to: its id or name. Default: the project repository. |

### Examples

```
qits repositories remote-login --project qits
qits repositories remote-login --project qits --repository qits-ci-service
```

- git asks for a username and a password: give a scoped personal access token, not the account's password. Nothing is echoed at the password prompt.
- Not an MCP tool: it needs a terminal and runs until the sign-in ends.

### Exit codes

- `0` The sign-in ended and the backups were asked to run again, you detached (Ctrl-]), or it was stopped (SIGINT, SIGTERM). A 0 does not say the sign-in worked: the backups say that.
- `1` The platform refused (the socket or the backups), the repository has no backup remote, or the connection dropped and five attempts to attach again failed.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits repositories backup

A repository's backup twin, the copy on the forge the project's backups push to: reset makes the twin's main ours when it shares no history with ours (a forge's seed commit).

## qits repositories backup reset

Reset a repository's backup twin whose main shares no history with ours, such as a forge's seed commit (a README the forge created): ordinary backups are refused on such a twin, and this makes its main ours.

The service probes the twin's main and decides. A twin with no main, at ours or behind ours is fast-forwarded, with no force (kind FAST_FORWARD). A twin whose main shares no commit with ours has its main replaced by ours (kind UNRELATED_RESET); only main is ever forced. A twin that is ahead of ours or diverged from a common base holds work the platform does not have: it is refused (HTTP 409, naming both shas) and nothing is pushed.

The reset is recorded with who decided it and --reason.

POST /projects/api/repositories/{id}/backup-reset.

```
qits repositories backup reset [--output table|json] [--project <project>] [--projects-url <url>] [--reason <text>] [--repository <repository>]
```

| Name | What it does |
|---|---|
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |
| `--reason <text>` | Why the twin's main may be replaced (required). It is recorded with the reset. |
| `--repository <repository>` | The repository (required): its id or name. |

### Examples

```
qits repositories --project qits backup reset --repository qits-ci-service --reason "The forge seeded the twin with a README commit."
qits repositories backup reset --project qits --repository qits-ci-service --reason "Seed commit" -o json
```

- Only a person (qits:admin, signed in as a person) or the project's own delivery desk may reset a twin: the decision is that the forge's copy of main may be discarded. Agents, CI and another project's desk are refused (HTTP 403).
- The table prints the repository, the kind, the twin's main before and after, who decided and the reason. -o json prints the service's answer.

### Exit codes

- `0` The twin's main is ours now: fast-forwarded, or reset from unrelated history.
- `1` The platform refused (for example your roles, HTTP 403, a repository it does not know, HTTP 404, or a twin that is ahead, diverged, moved meanwhile or missing, HTTP 409), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits work

Work items of every archetype - epics, tickets, features, tasks and issues - and their comment threads. list shows a project's items, details shows one with its comments and children, create files one, update edits its fields, transition reshapes it into another archetype, status moves it along its lifecycle, block and unblock flag that its phase cannot go on, delete removes it, dispatch puts an agent on it, and comment writes to its thread.

An epic's or a ticket's dossier (dossier), its refinement room itself (refinement: open, show, discard) and the HTML designs of a refinement (design) are here too.

An item can need other items: "needs" names each one and the status it must reach, such as qits-1897 at VERIFIED. An item whose needs are not all met is not dispatched from REFINED or READY_FOR_DEV. create and update write the whole list, like tags.

A write reads its payload, a JSON document, on stdin. With nothing on stdin (a terminal, or empty) it sends nothing and prints its usage and the payload's JSON schema instead, served by the service for that archetype.

Every archetype has a lifecycle: REPORTED, REFINED, READY_FOR_DEV, IMPLEMENTING, IMPLEMENTED, VERIFYING, VERIFIED, DONE, and DROPPED for work a decision was taken not to do, with an outcome saying why (status names them). Features and tasks walk the same nine words as epics and tickets. A SKIP transition lets READY_FOR_DEV move straight to IMPLEMENTED, bypassing IMPLEMENTING, and IMPLEMENTED move straight to VERIFIED, bypassing VERIFYING. IMPLEMENTING only moves forward or drops; it never moves back. REFINED to READY_FOR_DEV needs a person - a person's own `qits` CLI sign-in, or the browser; an agent credential is refused. `status` names the moves open from where an item stands.

### Notes

- --entity, --output and --projects-url may come before or after the command.
- --entity is the item's qualified id (qits-100) or its id, sent to the service as it is; the service resolves either. So is every id inside a payload (parent, membership.parent, supersededBy, and needs[].item, as in {"needs":[{"item":"qits-90","status":"VERIFIED"}]}).
- Reading takes qits:admin or qits:agent; so does writing, except an epic's status move, which takes qits:admin. An agent writes only in its own project.
- REFINED to READY_FOR_DEV takes a person signed in with their own `qits` CLI or the browser; an agent credential is refused (HTTP 409, or HTTP 403 for an epic).
- An issue is one thing the delivery desk cannot resolve. Only the desk creates one; a person resolves it (see create and status). Open ones: qits work list --project <project> --archetype ISSUE --status OPEN. Comment with qits work comment create.

## qits work list

A project's work items, every archetype, in the service's order: id (qualified), archetype, status, title and when it last changed. BLOCKED appears when a ticket is: yes for an explicit block, waiting when the only reason is an agent session sitting idle, and - when it is not blocked. NEEDS appears when some item needs another item that is not at its status yet, naming each as <id>@<status> (qits-1897@VERIFIED), and - for an item whose needs are all met. TAGS appears when some item carries one, as its display text (RESERVED_FOR_HUMAN shows as reserved for human), and - for an item with none. A DROPPED item shows its outcome beside the status: DROPPED (DUPLICATE).

Each filter narrows the list; without one it shows everything.

```
qits work list [--archetype <archetype>] [--entity <entity>] [--outcome <outcome>] [--output table|json] [--parent <entity>] [--project <project>] [--projects-url <url>] [--status <status>] [--tag <tag>]
```

| Name | What it does |
|---|---|
| `--archetype <archetype>` | Only items of this archetype: EPIC, TICKET, FEATURE, TASK or ISSUE. |
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `--outcome <outcome>` | Only DROPPED items dropped with this outcome: DUPLICATE, RESOLVED_ELSEWHERE, SUPERSEDED, OBSOLETE, INVALID or WONT_DO. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--parent <entity>` | Only the children of this item: its id or its qualified id. |
| `--project <project>` | The project (required): its id or its slug. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |
| `--status <status>` | Only items in this status, such as REFINED or OPEN. |
| `--tag <tag>` | Only items carrying this tag: reserved-for-human (RESERVED_FOR_HUMAN). |

### Examples

```
qits work list --project qits
qits work list --project qits --archetype epic --status REFINED
qits work list --project qits --parent qits-120 -o json
qits work list --project qits --status DROPPED --outcome duplicate
qits work list --project qits --tag reserved-for-human
```

- --archetype, --status and --outcome are case-insensitive. --parent lists the children of one item. --tag takes the tag's display form (reserved-for-human) or its enum name (RESERVED_FOR_HUMAN), either case.

### Exit codes

- `0` Done.
- `1` The platform refused (for example an unknown archetype or status, HTTP 400, or a project it does not know, HTTP 404), or cannot be reached.
- `2` Used wrongly (for example no --project), not signed in, or the session ended.

## qits work details

One work item: its fields, its description, its comment thread, its children, and what it closes: the items naming it as their supersededBy (dropped as its duplicate, resolved by it, or superseded by it). A DROPPED item shows its outcome beside the status, and its supersededBy as duplicate of, resolved by or superseded by. needs names, one a line, each item it needs that is not at its status yet, with where that item stands now (qits-1897 at VERIFIED (now REFINED)), and needs met the ones that are.

```
qits work details [--entity <entity>] [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits work --entity qits-100 details
qits work details --entity 45a14f8e-f550-45bb-a117-6b34d8c472e3 -o json
```

- -o json prints one object: the item as the service answers it, its comments, its children and supersedes, the items it closes. Control characters are written as escapes.

### Exit codes

- `0` Done.
- `1` The platform refused (for example an item it does not know, HTTP 404), or cannot be reached.
- `2` Used wrongly (for example no --entity), not signed in, or the session ended.

## qits work create

File a new work item of an archetype.

Reads the payload, a JSON object, on stdin, sets its "archetype" from --archetype, and sends it to POST /projects/api/work. A root item names its "project" (id or slug), a child its "parent" (qualified id or id). The item starts REPORTED if its archetype has a lifecycle, and an issue OPEN. The command prints the new item, its qualified id first.

With nothing on stdin (a terminal, or empty) it sends nothing, prints this usage and the payload's JSON schema, served by the service at GET /projects/api/work/archetypes/{archetype}/schemas/create, and exits with 0.

```
qits work create [--archetype <archetype>] [--entity <entity>] [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--archetype <archetype>` | The new item's archetype (required): EPIC, TICKET, FEATURE, TASK or ISSUE. Case-insensitive. |
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits work create --archetype ticket </dev/null
echo '{"project":"qits","title":"The log view stops at 64 KiB","ticketType":"BUG","impetus":"A long run's log is cut."}' | qits work create --archetype ticket
jq -n --rawfile d plan.md '{parent:"qits-120",title:"Retry",description:$d}' | qits work create --archetype feature -o json
```

```
echo '{"project":"qits","title":"CI retry refused","description":"qits-ci refuses the retry. A person must lift the lease."}' | qits work create --archetype issue
```

- The first one prints the schema: nothing is sent.
- An issue takes qits:desk or qits:desk-admin; everyone else, qits:admin included, is answered HTTP 403. Its description holds at most 280 characters (HTTP 400). It may name one related item in "relatedTo" (qualified id or id).

### Exit codes

- `0` The item is filed, or nothing was put in and the schema is printed.
- `1` The platform refused (for example a payload it does not take, HTTP 400, your roles or another project, HTTP 403, or an archetype it does not know, HTTP 404), or cannot be reached.
- `2` Used wrongly (for example no --archetype, or a payload that is not a JSON object), not signed in, or the session ended.

## qits work update

Edit a work item's fields.

Reads a JSON merge patch on stdin and sends it unchanged to PATCH /projects/api/work/{entity} as application/merge-patch+json: a property left out stays as it is, null clears it. Status and archetype are not edited here; see status and transition.

With nothing on stdin (a terminal, or empty) it sends nothing, looks the item up for its archetype, prints this usage and the patch's JSON schema for that archetype, served by the service at GET /projects/api/work/archetypes/{archetype}/schemas/update, and exits with 0.

```
qits work update [--entity <entity>] [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits work --entity qits-100 update </dev/null
echo '{"title":"The log view stops at 64 KiB"}' | qits work --entity qits-100 update
echo '{"assignee":null}' | qits work update --entity qits-100 -o json
echo '{"needs":[{"item":"qits-90","status":"VERIFIED"}]}' | qits work --entity qits-100 update
```

- needs is the whole list: it replaces what the item needed, and [] or null clears it.

### Exit codes

- `0` The item is edited, or nothing was put in and the schema is printed.
- `1` The platform refused (for example a patch it does not take, HTTP 400, your roles or another project, HTTP 403, or an item it does not know, HTTP 404), or cannot be reached.
- `2` Used wrongly (for example no --entity, or a patch that is not a JSON object), not signed in, or the session ended.

## qits work transition

Reshape a work item into another archetype (a ticket into an epic, a feature into a task, ...), keeping its id, its number and its thread.

The door is full-state: what the request leaves out is cleared. So the command starts from the item as it stands (title, description, status, outcome, ticketType, impetus, assignee, supersededBy, repositoryId, implementingAt, implementedAt, and membership {parent, position} if it has a parent), merges the JSON object on stdin over it as a merge patch (null clears), sets "archetype", and drops every property the target archetype's schema has no slot for, naming them on stderr. It sends the result to POST /projects/api/work/transition, keyed by --entity as it was given.

With nothing on stdin (a terminal, or empty) it sends nothing, prints this usage and the target's JSON schema, served at GET /projects/api/work/archetypes/{archetype}/schemas/transition, names the required properties the item does not carry yet and the ones that would be dropped, and exits with 0.

With --map it restates several items at once: the payload is the whole request, {"<entity>": <full state>, ...}, sent unchanged. Each key is a qualified id or an id, and each state names its "archetype" and every property the item is to keep, because a property left out is cleared. The service judges the map as one post-state and writes all of it or nothing, so a move with no legal order (a feature becomes an epic while its tasks become features under it) is one call. Nothing is created or deleted: every id must already exist. A status change to or from READY_FOR_DEV, or into started work or REFINED, is refused here; use status. --map takes no --entity and no --archetype.

```
qits work transition [--archetype <archetype>] [--entity <entity>] [--map] [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--archetype <archetype>` | The archetype to turn the item into (required without --map): EPIC, TICKET, FEATURE or TASK. Case-insensitive. |
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `--map` | Send the payload as it is: a map of entity to full state, for several items at once. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits work --entity qits-100 transition --archetype epic </dev/null
echo '{}' | qits work --entity qits-100 transition --archetype epic
echo '{"membership":{"parent":"qits-120"}}' | qits work --entity qits-100 transition --archetype feature
jq -n '{"qits-101":{archetype:"EPIC",title:"Export",status:"REPORTED"},"qits-102":{archetype:"FEATURE",title:"CSV",status:"REPORTED",membership:{parent:"qits-101"}}}' | qits work transition --map
```

- {} carries the item over as it stands. membership.parent and supersededBy take a qualified id or an id. needs is no part of this door, as tags are not: write it with update, as in echo '{"needs":[{"item":"qits-90","status":"VERIFIED"}]}' | qits work --entity qits-100 update.
- --map with nothing on stdin prints the request's schema from the service's OpenAPI document.

### Exit codes

- `0` The items are reshaped, or nothing was put in and the schema is printed.
- `1` The platform refused (for example a state it does not take, HTTP 400, your roles or another project, HTTP 403, an item or archetype it does not know, HTTP 404, or a conflict, HTTP 409), or cannot be reached.
- `2` Used wrongly (for example no --entity or --archetype, --map with either, or a payload that is not a JSON object), not signed in, or the session ended.

## qits work status

Move a work item along its lifecycle: REPORTED, REFINED, READY_FOR_DEV, IMPLEMENTING, IMPLEMENTED, VERIFYING, VERIFIED, DONE, or DROPPED. Epics, tickets, features and tasks walk it by hand. A SKIP transition lets READY_FOR_DEV move straight to IMPLEMENTED, bypassing IMPLEMENTING, and IMPLEMENTED move straight to VERIFIED, bypassing VERIFYING. IMPLEMENTING only moves forward or drops; it never moves back. REFINED to READY_FOR_DEV needs a person - a person's own `qits` CLI sign-in, or the browser; an agent credential is refused.

Reads {"target":"<STATUS>"} on stdin and sends it unchanged to POST /projects/api/work/{entity}/status. The service refuses a move its lifecycle does not allow (HTTP 409).

A move into DROPPED says why in "outcome": DUPLICATE (another item asks for the same thing), RESOLVED_ELSEWHERE (another item's change already resolved it), SUPERSEDED (a successor replaced it), OBSOLETE (the subject went away by itself), INVALID (it never was a problem) or WONT_DO (a decision was taken not to do it; scratch and probe items too). The first three name that other item in "supersededBy" (qualified id or id); the last three take none; any other move takes neither. Each breach is HTTP 400. Do not drop an item merely because it is hard or stale: block it instead. DROPPED moves back only to REPORTED, which clears both.

With nothing on stdin (a terminal, or empty) it sends nothing, prints this usage and the payload's schema, whose target enum is the moves open from the item's current status as the service's archetype registry (GET /projects/api/work/archetypes) states them, and exits with 0.

An issue has its own lifecycle: OPEN, then RESOLVED or OBSOLETE, and both are final. A person (qits:admin, signed in) moves it to RESOLVED with the answer in an optional "reason"; the delivery desk does too, with a one-line "reason" saying why: answered, or made redundant by other work (HTTP 400 without one). Anyone else gets HTTP 403. The delivery desk or a person moves it to OBSOLETE, with a one-line "reason" (HTTP 400 without one). The reason lands on its thread. An issue is never dispatched and is not reshaped by transition.

An epic or a feature whose children are all DONE or DROPPED can be closed straight to DONE by a person or the delivery desk (a CLOSE move).

```
qits work status [--entity <entity>] [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits work --entity qits-100 status </dev/null
echo '{"target":"REFINED"}' | qits work --entity qits-100 status
echo '{"target":"DROPPED","outcome":"DUPLICATE","supersededBy":"qits-90"}' | qits work --entity qits-100 status
echo '{"target":"DROPPED","outcome":"OBSOLETE"}' | qits work --entity qits-100 status
echo '{"target":"RESOLVED","reason":"Lease lifted."}' | qits work --entity qits-100 status
echo '{"target":"OBSOLETE","reason":"The lease ended."}' | qits work --entity qits-100 status
```

- An epic's status move takes qits:admin; an agent is answered HTTP 403.
- REFINED to READY_FOR_DEV takes a person signed in with their own `qits` CLI or the browser; an agent credential is refused (HTTP 409, or HTTP 403 for an epic).

### Exit codes

- `0` The item moved, or nothing was put in and the legal targets are printed.
- `1` The platform refused (for example a drop without its outcome or reference, HTTP 400, a move the lifecycle does not allow, HTTP 409, your roles or another project, HTTP 403, or an item it does not know, HTTP 404), or cannot be reached.
- `2` Used wrongly (for example no --entity, an item on a service whose registry has not yet given its archetype a lifecycle, or a payload that is not a JSON object), not signed in, or the session ended.

## qits work block

Block a ticket or an epic: say that the phase its status started cannot go on, and why. Use it when you stop and cannot take the work further.

Sends {"blocked":true,"reason":"..."} to POST /projects/api/work/{entity}/blocked. The status does not move: it is the phase to resume, and the next status move clears the block. The reason lands on the item's comment thread, so write what is in the way concretely enough for somebody else to act on it.

```
qits work block [--entity <entity>] [--output table|json] [--projects-url <url>] [--reason <text>]
```

| Name | What it does |
|---|---|
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |
| `--reason <text>` | What is in the way (required). It lands on the item's thread. |

### Examples

```
qits work --entity qits-100 block --reason 'The database team must confirm the new limit.'
```

- A feature or a task runs no phase of its own: block its epic. An item that is VERIFIED, DONE or DROPPED cannot be blocked (HTTP 409).

### Exit codes

- `0` The item is blocked.
- `1` The platform refused (for example a feature, a task or a status that starts no phase, HTTP 409, your roles or another project, HTTP 403, or an item it does not know, HTTP 404), or cannot be reached.
- `2` Used wrongly (for example no --entity or no --reason), not signed in, or the session ended.

## qits work unblock

Unblock a ticket or an epic: say that what was in the way is gone and its phase can go on. Do it as soon as you know, whoever blocked it.

Sends {"blocked":false} to POST /projects/api/work/{entity}/blocked, with the reason when one is given. No status moves.

```
qits work unblock [--entity <entity>] [--output table|json] [--projects-url <url>] [--reason <text>]
```

| Name | What it does |
|---|---|
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |
| `--reason <text>` | Why it can go on again. Optional. |

### Examples

```
qits work --entity qits-100 unblock
qits work --entity qits-100 unblock --reason 'The limit is confirmed.' -o json
```

### Exit codes

- `0` The item is unblocked.
- `1` The platform refused (for example your roles or another project, HTTP 403, or an item it does not know, HTTP 404), or cannot be reached.
- `2` Used wrongly (for example no --entity), not signed in, or the session ended.

## qits work delete

Delete a work item, its children and their comment threads. The audit log keeps them.

Sends DELETE /projects/api/work/{entity}. An agent may delete a feature or a task of its own project while the epic is REPORTED; deleting an epic or a ticket takes qits:admin.

```
qits work delete [--entity <entity>] [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits work --entity qits-104 delete
```

### Exit codes

- `0` The item is gone.
- `1` The platform refused (for example an epic or a ticket for an agent, or another project, HTTP 403, an item it does not know, HTTP 404, or an epic past REPORTED, HTTP 409), or cannot be reached.
- `2` Used wrongly (for example no --entity), not signed in, or the session ended.

## qits work dispatch

Put an agent on an epic or a ticket: the Dispatch and Run the next phase buttons of the item's page.

--mode FLOW runs the phase the item's status starts and every phase after it, until a status starts none. --mode PHASE runs that one phase, then stops. A machine at REFINED waits for a person to move it to READY_FOR_DEV, unless the project's delivery desk may start refined items (qits projects delivery shows whether it may).

Sends {"mode":"FLOW"} or {"mode":"PHASE"}, with "rule" when --rule is given, to POST /projects/api/work/{entity}/dispatch, and prints the item, the mode, the phase started, the agent's branch and the assignee.

```
qits work dispatch [--entity <entity>] [--mode <mode>] [--output table|json] [--projects-url <url>] [--rule <rule>]
```

| Name | What it does |
|---|---|
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `--mode <mode>` | FLOW (every phase from here on) or PHASE (the next one only); required. Case-insensitive. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |
| `--rule <rule>` | The delivery desk's rule the press is made under (backlog-order, start-refined). Optional; the service ignores it for anyone but the desk. |

### Examples

```
qits work --entity qits-100 dispatch --mode flow
qits work dispatch --entity qits-100 --mode PHASE -o json
qits work --entity qits-100 dispatch --mode FLOW --rule backlog-order
```

- Pressing takes qits:admin, or the project's delivery desk, which names the rule it presses under; a plain agent is answered HTTP 403.
- The service refuses a status that starts no phase, a feature or a task, a blocked item, an item at REFINED or READY_FOR_DEV whose needs are not all met, or a project with no wrapper (HTTP 409), and the command prints its sentence as it came.

### Exit codes

- `0` The agent is dispatched.
- `1` The platform refused (for example a mode it does not know, HTTP 400, your roles, HTTP 403, an item it does not know, HTTP 404, or an item no phase can start from, HTTP 409), or cannot be reached.
- `2` Used wrongly (for example no --entity, or a --mode that is neither FLOW nor PHASE), not signed in, or the session ended.

## qits work comment

The comment thread of a work entity: create adds a comment, update edits one.

Both read a JSON document on stdin and send it as it is. With nothing on stdin they send nothing and print the payload's JSON schema, with its required fields named.

### Notes

- The author is the signed-in caller (an agent's comment is its agent's), and an edit leaves it as it is. Only a comment's author may edit it (HTTP 403 otherwise).
- Deleting a comment takes qits:admin, and qits has no command for it.

## qits work comment create

Add a comment to a work entity's thread.

Reads the payload, a JSON object such as {"body":"..."}, on stdin and sends it unchanged to POST /projects/api/work/{entity}/comments. The body is Markdown. You are its author. The command prints the comment once filed.

With nothing on stdin (a terminal, or empty) it sends nothing, prints the payload's JSON schema from the service's OpenAPI document (/projects/q/openapi) with the required fields named, and exits with 0.

```
qits work comment create [--entity <entity>] [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
echo '{"body":"I can reproduce it."}' | qits work --entity qits-100 comment create
jq -n --rawfile b note.md '{body: $b}' | qits work comment create --entity qits-100 -o json
qits work --entity qits-100 comment create </dev/null
```

- The last one prints the schema: nothing is sent.
- -o json prints the service's answer; the table prints the comment's id, author and time.

### Exit codes

- `0` The comment is filed, or nothing was put in and the schema is printed.
- `1` The platform refused (for example your roles or another project's entity, HTTP 403, an entity it does not know, HTTP 404, or a payload it does not take, HTTP 400), cannot be reached, or its OpenAPI document does not describe the payload.
- `2` Used wrongly (for example a payload that is not a JSON object, or no --entity), not signed in, or the session ended.

## qits work comment update

Edit a comment on a work entity's thread.

Reads a JSON merge patch, such as {"body":"..."}, on stdin and sends it unchanged to PATCH /projects/api/work/{entity}/comments/{commentId} as application/merge-patch+json. The path names the entity and the comment together, so the service answers a comment that is not on the entity's thread with HTTP 404. The author stays who it was. Only the comment's author may edit it; anybody else gets HTTP 403.

With nothing on stdin (a terminal, or empty) it sends nothing, prints the patch's JSON schema from the service's OpenAPI document (/projects/q/openapi) with the required fields named, and exits with 0.

```
qits work comment update [--comment <comment>] [--entity <entity>] [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--comment <comment>` | The comment to edit (required unless nothing is put in): its id. |
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
echo '{"body":"Fixed in 2026.929.1."}' | qits work --entity qits-100 comment update --comment 8de195f1-631d-420a-893e-7baca2229be4
qits work --entity qits-100 comment update </dev/null
```

- --comment is the comment's full id, as `create` printed it.
- -o json prints the service's answer; the table prints the comment's id, author and time.

### Exit codes

- `0` The comment is edited, or nothing was put in and the schema is printed.
- `1` The platform refused (for example your roles, HTTP 403, an entity it does not know or a comment that is not on its thread, HTTP 404, or a patch it does not take, HTTP 400), cannot be reached, or its OpenAPI document does not describe the patch.
- `2` Used wrongly (for example a patch that is not a JSON object, or no --entity or --comment), not signed in, or the session ended.

## qits work dossier

The dossier of an epic or a ticket: Markdown pages in reading order, for what the item's own description cannot hold. list shows the pages, get reads one, put adds or rewrites one, move reorders, delete removes one, and inline puts a sketch or a design into an epic's dossier.

The item's description is the short form: an epic's is the pitch, a ticket's is what refinement concluded. The dossier is what the implementer builds from, a page per part: what changes and how it works, with paths, names, exact values, examples and figures.

### Notes

- --page is a page's slug (as list shows it) or its id.
- Nobody accepts a write: a page is live the moment it lands. An epic's dossier is writable while the epic is REPORTED; a ticket's at every status.
- Reading and writing take qits:admin or qits:agent; an agent writes only in its own project.

## qits work dossier list

List the pages of an epic's or a ticket's dossier in reading order: position, slug, title, version and when it last changed. Read this before you add a page, so the new page does not repeat one that is there.

Reads GET /projects/api/work/{entity}/dossier. -o json includes each page's Markdown body.

```
qits work dossier list [--entity <entity>] [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits work --entity qits-100 dossier list
qits work dossier list --entity qits-100 -o json
```

### Exit codes

- `0` Done.
- `1` The platform refused (for example an item it does not know or one with no dossier, HTTP 404), or cannot be reached.
- `2` Used wrongly (for example no --entity), not signed in, or the session ended.

## qits work dossier get

Read one dossier page in full: its title, its Markdown body and its version.

Reads GET /projects/api/work/{entity}/dossier/{page}. Read a page before you rewrite it: put needs the version this answers, and a page is somebody's prose.

```
qits work dossier get [--entity <entity>] [--output table|json] [--page <page>] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--page <page>` | The page (required): its slug or its id. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits work --entity qits-100 dossier get --page data-flow
qits work dossier get --entity qits-100 --page data-flow -o json
```

### Exit codes

- `0` Done.
- `1` The platform refused (for example no such item or no such page, HTTP 404), or cannot be reached.
- `2` Used wrongly (for example no --entity or no --page), not signed in, or the session ended.

## qits work dossier put

Add a page to an epic's or a ticket's dossier, or rewrite one.

Without --page it adds a page at the end: the payload is {"title":"...","body":"..."}, sent to POST /projects/api/work/{entity}/dossier. The slug is made from the title and never changes.

With --page it rewrites that page: the payload is {"title"?:"...","body"?:"...","version":<n>}, sent to PUT /projects/api/work/{entity}/dossier/{page}. A property left out keeps its value. version is the one get gave you; when somebody wrote to the page after you read it, the service refuses (HTTP 409). Read the page again, fold your change into it and write again; never write without a version.

The body is Markdown. Write what changes and how it works: paths, names, commands, exact values. To show a sketch or a design on an epic's page, get its Markdown line from inline; never write a figure URL by hand. A ticket's dossier has no figures.

With nothing on stdin (a terminal, or empty) it sends nothing, prints the payload's JSON schema from the service's OpenAPI document (/projects/q/openapi) and exits with 0.

```
qits work dossier put [--entity <entity>] [--output table|json] [--page <page>] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--page <page>` | The page to rewrite: its slug or its id. Leave it out to add a page. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
jq -n --rawfile b plan.md '{title:"Data flow", body:$b}' | qits work --entity qits-100 dossier put
jq -n --rawfile b plan.md '{body:$b, version:3}' | qits work --entity qits-100 dossier put --page data-flow
qits work --entity qits-100 dossier put --page data-flow </dev/null
```

### Exit codes

- `0` The page is written, or nothing was put in and the schema is printed.
- `1` The platform refused (for example a blank title or no version, HTTP 400, another project, HTTP 403, no such item or page, HTTP 404, or a stale version or an epic past REPORTED, HTTP 409), or cannot be reached.
- `2` Used wrongly (for example no --entity, or a payload that is not a JSON object), not signed in, or the session ended.

## qits work dossier move

Move a dossier page to a position in the reading order, counted from 0. The pages between its old and new place shift to close the gap.

Sends {"position":<n>} to POST /projects/api/work/{entity}/dossier/{page}/move. Reading order is the only structure a dossier has: there is no nesting, and a page's own headings are its second level.

```
qits work dossier move [--entity <entity>] [--output table|json] [--page <page>] [--position <n>] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--page <page>` | The page to move (required): its slug or its id. |
| `--position <n>` | Where it goes (required), counted from 0. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits work --entity qits-100 dossier move --page data-flow --position 0
```

### Exit codes

- `0` The page is moved.
- `1` The platform refused (for example another project, HTTP 403, no such item or page, HTTP 404, or an epic past REPORTED, HTTP 409), or cannot be reached.
- `2` Used wrongly (for example no --entity, --page or --position), not signed in, or the session ended.

## qits work dossier delete

Delete a dossier page. The pages after it close the gap.

Sends DELETE /projects/api/work/{entity}/dossier/{page}. Deleting somebody's page is not a way to disagree with it: rewrite the part that is wrong, and delete only what you added and no longer mean. On an epic, a figure no other page shows goes with it.

```
qits work dossier delete [--entity <entity>] [--output table|json] [--page <page>] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--page <page>` | The page to delete (required): its slug or its id. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits work --entity qits-100 dossier delete --page old-notes
```

### Exit codes

- `0` The page is gone.
- `1` The platform refused (for example another project, HTTP 403, no such item or page, HTTP 404, or an epic past REPORTED, HTTP 409), or cannot be reached.
- `2` Used wrongly (for example no --entity or no --page), not signed in, or the session ended.

## qits work dossier inline

Copy a sketch or a design of an epic's refinement into the epic's dossier, and print the exact Markdown line to paste into a page.

Sends {"sourceId":"...","kind":"IMAGE|DESIGN"} to POST /projects/api/work/{entity}/dossier-assets. IMAGE is a sketch from the refinement's Sketch tab, DESIGN a design (`qits work design list`). The line renders as a picture or as a framed document. The copy keeps rendering after the refinement is gone, and inlining the same figure again gives the same line, not a second copy.

Never write a figure URL by hand: it ends up naming something that does not exist or belongs to another epic, and the page shows it broken.

```
qits work dossier inline [--entity <entity>] [--kind <kind>] [--output table|json] [--projects-url <url>] [--source <id>]
```

| Name | What it does |
|---|---|
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `--kind <kind>` | IMAGE for a sketch, DESIGN for a design (required). Case-insensitive. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |
| `--source <id>` | The sketch's or the design's id (required). |

### Examples

```
qits work --entity qits-100 dossier inline --source 6f1c0e2a-... --kind design
```

- The table prints the Markdown line alone; -o json prints the figure's id, kind, label and URL too.

### Exit codes

- `0` The figure is in the dossier and its line is printed.
- `1` The platform refused (for example another project, HTTP 403, or an item that is not an epic or no such figure in its refinement, HTTP 404), or cannot be reached.
- `2` Used wrongly (for example no --entity, --source or --kind), not signed in, or the session ended.

## qits work refinement

An epic's or a ticket's refinement room: the workspace with its own container and its own refining/<slug> branch where the item's scope is worked out, and where its designs live. open opens the room and brings its container up, show shows it, discard tears it down.

An item has at most one room, opened only while the item is at REPORTED and never for a feature or a task. The move from REPORTED to REFINED is the gate on the plan; the room stays until it is discarded or the item reaches a final status.

### Notes

- --entity is the epic or the ticket, not the room. The room's own id is in show's ID column.
- show takes qits:admin, qits:admin-agent, qits:desk-admin or qits:agent. open and discard take qits:admin, qits:admin-agent or qits:desk-admin; an agent with qits:agent alone is refused (HTTP 403).

## qits work refinement open

Open an epic's or a ticket's refinement room and bring its container up, as the Refine button does. An item that already has a room gets that room back, and its container is brought up again: a stopped one is woken, a running one is left running.

Sends POST /projects/api/work/{entity}/refinement, then POST /projects/api/refinements/{id}/ensure-container with the room's id. The container comes up in the background; the technical process it prints narrates that. Shows the room's id, its item, its label and its status, which is PROVISIONING while the container comes up.

```
qits work refinement open [--entity <entity>] [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits work --entity qits-100 refinement open
qits work --entity qits-100 refinement open -o json
```

### Exit codes

- `0` The room is open and its container is coming up.
- `1` The platform refused (for example your roles, HTTP 403, an item it does not know, HTTP 404, an item that cannot be refined now, such as one not at REPORTED, a feature or a task, HTTP 409, or a branch the git host would not cut, HTTP 502), or the platform cannot be reached.
- `2` Used wrongly (for example no --entity), not signed in, or the session ended.

## qits work refinement show

Show an epic's or a ticket's refinement room: its id, its item, its label and its status, such as PROVISIONING, RUNNING or STOPPED. An item with no room says so and exits with 0.

Reads GET /projects/api/work/{entity}/refinement; it never opens a room.

```
qits work refinement show [--entity <entity>] [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits work --entity qits-100 refinement show
qits work --entity qits-100 refinement show -o json
```

### Exit codes

- `0` Done, also when the item has no room.
- `1` The platform refused (for example an item it does not know, HTTP 404), or cannot be reached.
- `2` Used wrongly (for example no --entity), not signed in, or the session ended.

## qits work refinement discard

Discard an epic's or a ticket's refinement room. This deletes the room's container, its refining/<slug> branch, and its designs, prompt draft and prompt images with it; none of it can be brought back. The work item itself is untouched, and a later open starts a fresh room.

Reads GET /projects/api/work/{entity}/refinement for the room, then sends POST /projects/api/refinements/{id}/discard.

```
qits work refinement discard [--entity <entity>] [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits work --entity qits-100 refinement discard
qits work --entity qits-100 refinement discard -o json
```

### Exit codes

- `0` The room is discarded.
- `1` The platform refused (for example your roles, HTTP 403, or an item or room it does not know, HTTP 404), the item has no room, or the platform cannot be reached.
- `2` Used wrongly (for example no --entity), not signed in, or the session ended.

## qits work design

The HTML designs of an epic's or a ticket's refinement: the documents a person sees in the Design tab. list shows them, get reads one with its document, put adds or rewrites one.

There is no draft: a design is live the moment it is written, and a person may rename or delete it. The gate on the plan is the item's move from REPORTED to REFINED.

### Notes

- --entity is the epic or the ticket, not the refinement. An item with no open refinement has no designs.
- Reading takes qits:admin or qits:agent. Writing takes qits:admin, qits:admin-agent or qits:desk-admin; an agent with qits:agent alone is refused (HTTP 403).

## qits work design list

List the designs of an epic's or a ticket's refinement, oldest first, without their documents: id, title, version, size, whether a dossier page shows it, and when it changed.

Reads GET /projects/api/work/{entity}/refinement for the refinement, then GET /projects/api/refinements/{id}/designs. Read a design with get before you rewrite it.

```
qits work design list [--entity <entity>] [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits work --entity qits-100 design list
qits work --entity qits-100 design list -o json
```

### Exit codes

- `0` Done.
- `1` The platform refused (for example an item it does not know, HTTP 404), the item has no open refinement, or the platform cannot be reached.
- `2` Used wrongly (for example no --entity), not signed in, or the session ended.

## qits work design get

Read one design in full: its title, its version and the whole HTML document, styles inline and no scripts. Send the same shape back when you rewrite it, with this version.

Reads GET /projects/api/work/{entity}/refinement for the refinement, then GET /projects/api/refinements/{id}/designs/{designId}.

```
qits work design get [--design <design>] [--entity <entity>] [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--design <design>` | The design's id (required). |
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits work --entity qits-100 design get --design 6f1c0e2a-...
qits work --entity qits-100 design get --design 6f1c0e2a-... -o json | jq -r .html
```

### Exit codes

- `0` Done.
- `1` The platform refused (for example no such item or design, HTTP 404), the item has no open refinement, or the platform cannot be reached.
- `2` Used wrongly (for example no --entity or no --design), not signed in, or the session ended.

## qits work design put

Add a design to an epic's or a ticket's refinement, or rewrite one.

Without --design it adds one: the payload is {"title":"...","html":"..."}, sent to POST /projects/api/refinements/{id}/designs. With --design it rewrites that design in place: the payload is {"title":"...","html"?:"...","version":<n>}, sent to PUT /projects/api/refinements/{id}/designs/{designId}; the title is required, and leaving html out only renames it.

html is a COMPLETE HTML document with inline styles and no scripts, the shape get answers. version is the one get gave you. When somebody wrote to the design after you read it, the service refuses (HTTP 409): read it again, fold your change in and write again; never write without a version.

The refinement comes from GET /projects/api/work/{entity}/refinement. With nothing on stdin (a terminal, or empty) it sends nothing, prints the payload's JSON schema from the service's OpenAPI document (/projects/q/openapi) and exits with 0.

```
qits work design put [--design <design>] [--entity <entity>] [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--design <design>` | The design to rewrite: its id. Leave it out to add a design. |
| `--entity <entity>` | The work entity: its id or its qualified id (qits-100), passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
jq -n --rawfile h board.html '{title:"Board", html:$h}' | qits work --entity qits-100 design put
jq -n --rawfile h board.html '{title:"Board", html:$h, version:2}' | qits work --entity qits-100 design put --design 6f1c0e2a-...
qits work --entity qits-100 design put </dev/null
```

### Exit codes

- `0` The design is written, or nothing was put in and the schema is printed.
- `1` The platform refused (for example no title, html or version, HTTP 400, your roles, HTTP 403, no such item or design, HTTP 404, or a stale version, HTTP 409), the item has no open refinement, or the platform cannot be reached.
- `2` Used wrongly (for example no --entity, or a payload that is not a JSON object), not signed in, or the session ended.

## qits ideas

A project's ideas: short, titled notes that an epic or a ticket names as its inspiration ("inspiredBy"). list shows a project's ideas, details shows one with the work it inspired and the ideas it enables, create writes one, update edits it, archive and unarchive hide it from the list and bring it back, rank moves it above or below another, and spawn files an epic or a ticket from it.

An idea is not a work item: it has no lifecycle you move, no comment thread, no children and no delete. Its status is derived from the work naming it: PLANNED (no counted work names it), QUEUED (some does, not all DONE) or DONE (every counted item DONE); DROPPED work is not counted. One idea may name another of its project as "enabledBy".

A write reads its payload, a JSON document, on stdin. With nothing on stdin (a terminal, or empty) it sends nothing and prints its usage and the payload's JSON schema instead, from the service's OpenAPI document.

### Notes

- --entity, --output and --projects-url may come before or after the command.
- --entity is the idea's id, as list and create print it.
- Reading takes qits:admin or qits:agent. Writing (create, update, archive, unarchive, rank, spawn) takes the project's delivery desk, in its own project, or an admin.
- An agent does not write ideas: it files a ticket or an epic with qits work create, and asks the desk with an @desk comment to file or link an idea.

## qits ideas list

A project's ideas in rank order: the highest priority first, the unranked after them, and oldest first among equals. Each row is the id, status, whether archived, priority (empty when unranked), title and when it last changed. The archived ones are left out unless --archived is given, which adds them beside the rest.

```
qits ideas list [--archived] [--entity <idea>] [--output table|json] [--project <project>] [--projects-url <url>] [--status <status>]
```

| Name | What it does |
|---|---|
| `--archived` | Show the archived ideas too, beside the others. |
| `--entity <idea>` | The idea: its id, passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project (required): its id or its slug. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |
| `--status <status>` | Only ideas at this status: PLANNED, QUEUED or DONE. |

### Examples

```
qits ideas list --project qits
qits ideas list --project qits --archived --status planned -o json
```

- --status is PLANNED, QUEUED or DONE, case-insensitive.

### Exit codes

- `0` Done.
- `1` The platform refused (for example a status it does not know, HTTP 400, or a project it does not know, HTTP 404), or cannot be reached.
- `2` Used wrongly (for example no --project), not signed in, or the session ended.

## qits ideas details

One idea: its title, status, whether it is archived and why, the idea enabling it, its description, the ideas it enables, and the work it inspired (every work item naming it as inspiredBy, DROPPED ones included, with their status). When the idea enabling it is archived, a hint line says so.

```
qits ideas details [--entity <idea>] [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--entity <idea>` | The idea: its id, passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits ideas details --entity 45a14f8e-f550-45bb-a117-6b34d8c472e3
qits ideas --entity 45a14f8e-f550-45bb-a117-6b34d8c472e3 details -o json
```

- -o json prints the service's answer: the idea with inspired, enables and enabledByIdea. Control characters are written as escapes.

### Exit codes

- `0` Done.
- `1` The platform refused (for example an idea it does not know, HTTP 404), or cannot be reached.
- `2` Used wrongly (for example no --entity), not signed in, or the session ended.

## qits ideas create

Write a new idea of a project.

Reads the payload, a JSON object such as {"project":"qits","title":"..."}, on stdin and sends it unchanged to POST /projects/api/ideas. "project" is the project's id or slug. The title is one line (no line break, at most one '.', fewer than 20 whitespace characters); the description at most 3 paragraphs and 600 characters; "enabledBy" names one idea of the same project that is not itself enabled by another. The idea starts PLANNED. The command prints it, its id first.

With nothing on stdin (a terminal, or empty) it sends nothing, prints this usage and the payload's JSON schema from the service's OpenAPI document (/projects/q/openapi), and exits with 0.

```
qits ideas create [--entity <idea>] [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--entity <idea>` | The idea: its id, passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits ideas create </dev/null
echo '{"project":"qits","title":"Faster builds"}' | qits ideas create
jq -n --rawfile d idea.md '{project:"qits",title:"Share the build cache",description:$d}' | qits ideas create -o json
```

- The first one prints the schema: nothing is sent.

### Exit codes

- `0` The idea is written, or nothing was put in and the schema is printed.
- `1` The platform refused (for example a title, description or enabledBy breaking its rule, HTTP 400, an agent, or the desk in another project, HTTP 403, or a project it does not know, HTTP 404), or cannot be reached.
- `2` Used wrongly (for example a payload that is not a JSON object), not signed in, or the session ended.

## qits ideas update

Edit an idea's title, description, enabledBy or priority.

Reads a JSON merge patch on stdin and sends it unchanged to PATCH /projects/api/ideas/{idea} as application/merge-patch+json: a property left out stays as it is, null clears description, enabledBy or priority. The rules are the create's; a priority is an integer, and an archived or DONE idea takes none (HTTP 409). To move an idea among the others, rank is simpler. The status is derived and the archive has its own commands.

With nothing on stdin (a terminal, or empty) it sends nothing, prints this usage and the patch's JSON schema from the service's OpenAPI document (/projects/q/openapi), and exits with 0.

```
qits ideas update [--entity <idea>] [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--entity <idea>` | The idea: its id, passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits ideas update </dev/null
echo '{"title":"Share the build cache across runners"}' | qits ideas --entity 45a14f8e-f550-45bb-a117-6b34d8c472e3 update
echo '{"enabledBy":null}' | qits ideas update --entity 45a14f8e-f550-45bb-a117-6b34d8c472e3
```

### Exit codes

- `0` The idea is edited, or nothing was put in and the schema is printed.
- `1` The platform refused (for example a patch it does not take, HTTP 400, an agent, or the desk in another project, HTTP 403, or an idea it does not know, HTTP 404), or cannot be reached.
- `2` Used wrongly (for example no --entity, or a patch that is not a JSON object), not signed in, or the session ended.

## qits ideas archive

Archive an idea: hide it from the list, and say why. Ideas are never deleted.

Sends {"reason":"..."} to POST /projects/api/ideas/{idea}/archive. Allowed at every status, and the status does not change. Archiving an archived idea states the reason again.

```
qits ideas archive [--entity <idea>] [--output table|json] [--projects-url <url>] [--reason <text>]
```

| Name | What it does |
|---|---|
| `--entity <idea>` | The idea: its id, passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |
| `--reason <text>` | Why the idea is archived (required). |

### Examples

```
qits ideas --entity 45a14f8e-f550-45bb-a117-6b34d8c472e3 archive --reason 'Folded into the cache epic.'
```

### Exit codes

- `0` The idea is archived.
- `1` The platform refused (for example an agent, or the desk in another project, HTTP 403, or an idea it does not know, HTTP 404), or cannot be reached.
- `2` Used wrongly (for example no --entity or no --reason), not signed in, or the session ended.

## qits ideas unarchive

Take an idea out of the archive, so the list shows it again.

Sends POST /projects/api/ideas/{idea}/unarchive, which clears the archive and its reason. An idea that is not archived is answered as it is.

```
qits ideas unarchive [--entity <idea>] [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--entity <idea>` | The idea: its id, passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits ideas --entity 45a14f8e-f550-45bb-a117-6b34d8c472e3 unarchive
```

### Exit codes

- `0` The idea is not archived.
- `1` The platform refused (for example an agent, or the desk in another project, HTTP 403, or an idea it does not know, HTTP 404), or cannot be reached.
- `2` Used wrongly (for example no --entity), not signed in, or the session ended.

## qits ideas rank

Move an idea in its project's rank order: directly above or directly below another.

Sends {"above":"<idea>"} or {"below":"<idea>"} to PUT /projects/api/ideas/{idea}/priority, and prints each idea whose priority the move wrote, highest first: its id and its new priority. list then answers in the new order.

Only rankable ideas move or anchor a move: not archived and not DONE. Give exactly one of --above and --below.

```
qits ideas rank [--above <idea>] [--below <idea>] [--entity <idea>] [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--above <idea>` | The idea this one then sits directly above: its id. |
| `--below <idea>` | The idea this one then sits directly below: its id. |
| `--entity <idea>` | The idea: its id, passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits ideas --entity 45a14f8e-f550-45bb-a117-6b34d8c472e3 rank --above 6c0d1e2f-3a4b-4c5d-8e6f-7a8b9c0d1e2f
qits ideas rank --entity 45a14f8e-f550-45bb-a117-6b34d8c472e3 --below 6c0d1e2f-3a4b-4c5d-8e6f-7a8b9c0d1e2f -o json
```

- -o json prints the service's answer: {"items":[{"id","priority"},...]}.

### Exit codes

- `0` The idea is moved.
- `1` The platform refused (for example an anchor that is the idea itself, unknown, of another project, archived or DONE, HTTP 400, an agent, or the desk in another project, HTTP 403, an idea it does not know, HTTP 404, or an idea that is archived or DONE, HTTP 409), or cannot be reached.
- `2` Used wrongly (for example no --entity, or not exactly one of --above and --below), not signed in, or the session ended.

## qits ideas spawn

File an epic or a ticket inspired by an idea.

Reads the idea, and starts the new item from it: "project" its project, "title" its title, "description" its description when it has one, and "inspiredBy" its id. The JSON object on stdin is merged over that as a merge patch (null removes a property), "archetype" is set from --archetype, and the result goes to POST /projects/api/work, the door `qits work create` sends to. The command prints the new item, its qualified id first. A ticket also needs its "ticketType" and "impetus" on stdin.

With nothing on stdin (a terminal, or empty) it sends nothing, prints this usage, what the idea fills in, the required properties still missing, and the archetype's create schema served at GET /projects/api/work/archetypes/{archetype}/schemas/create, and exits with 0. Pipe in {} to file the item as the idea fills it.

```
qits ideas spawn [--archetype <archetype>] [--entity <idea>] [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--archetype <archetype>` | The new item's archetype (required): EPIC or TICKET. Case-insensitive. |
| `--entity <idea>` | The idea: its id, passed to the service as it is. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits ideas --entity 45a14f8e-f550-45bb-a117-6b34d8c472e3 spawn --archetype epic </dev/null
echo '{}' | qits ideas --entity 45a14f8e-f550-45bb-a117-6b34d8c472e3 spawn --archetype epic
echo '{"ticketType":"IMPROVEMENT","impetus":"Builds wait on downloads."}' | qits ideas spawn --entity 45a14f8e-f550-45bb-a117-6b34d8c472e3 --archetype ticket
```

- The first one prints the schema: nothing is sent.

### Exit codes

- `0` The item is filed, or nothing was put in and the schema is printed.
- `1` The platform refused (for example an idea it does not know, HTTP 404, a payload it does not take, HTTP 400, or an agent, or the desk in another project, HTTP 403), or cannot be reached.
- `2` Used wrongly (for example no --entity, an --archetype that is neither EPIC nor TICKET, or a payload that is not a JSON object), not signed in, or the session ended.

## qits pitches

A project's pitches: mock HTML snapshots a named agent (such as ui-builder) draws over one of the UI builder's screenshots, kept per agent with every version. list shows a project's pitches, show one with its versions or one version's HTML, create pitches a new one, add-version adds a version to one, and archive hides one from the list.

A pitch names the repository, route and screenshot of the freeze it was drawn over, its kind (DATA, NO_DATA, PENDING or ERROR), viewport (PHONE, TABLET or DESKTOP), color scheme (LIGHT, DARK, or none) and the freeze's manifest version. Versions are never rewritten: each change is a new one.

A write reads its payload, a JSON document, on stdin. With nothing on stdin (a terminal, or empty) it sends nothing and prints its usage and the payload's JSON schema instead, from the service's OpenAPI document.

### Notes

- --pitch, --output and --projects-url may come before or after the command.
- --pitch is the pitch's id, as list and create print it.
- Reading takes qits:admin, qits:admin-agent, qits:desk-admin, qits:desk, qits:agent or qits:system, in the pitch's project.
- Only a named agent creates a pitch, in its own project, and only the pitch's agent adds a version; everybody else is refused (HTTP 403), an admin included. The pitch's agent, qits:admin or qits:desk-admin may archive it.
- The HTML is not cleaned by the service: show it only in a sandboxed frame with no scripts.

## qits pitches list

A project's pitches, the most recently changed first, without their HTML. Each row is the id, the agent, the repository, the route, the screenshot, the latest version, whether archived, the title and when it last changed. The archived ones are left out unless --archived is given, which adds them beside the rest.

Reads GET /projects/api/pitches?project=<project>, with each other option as a filter of the same name.

```
qits pitches list [--agent <agent>] [--archived] [--output table|json] [--pitch <pitch>] [--project <project>] [--projects-url <url>] [--repository <repository>] [--route <route>] [--screenshot <file>]
```

| Name | What it does |
|---|---|
| `--agent <agent>` | Only this named agent's pitches, such as ui-builder. |
| `--archived` | Show the archived pitches too, beside the others. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--pitch <pitch>` | The pitch: its id, passed to the service as it is. |
| `--project <project>` | The project (required): its id or its slug. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |
| `--repository <repository>` | Only the pitches over this repository's screenshots: its id or its name. |
| `--route <route>` | Only the pitches over this route, as the freeze names it (for example /work). |
| `--screenshot <file>` | Only the pitches over this screenshot: its file in the freeze's manifest. |

### Examples

```
qits pitches list --project qits
qits pitches list --project qits --agent ui-builder --repository qits-landing-app --route /work
qits pitches list --project qits --screenshot work/board-desktop-dark.html --archived -o json
```

### Exit codes

- `0` Done.
- `1` The platform refused (for example a project or repository it does not know, HTTP 404), or cannot be reached.
- `2` Used wrongly (for example no --project), not signed in, or the session ended.

## qits pitches show

One pitch. Without --number: its fields and its versions (number, size, note, when), without their HTML, from GET /projects/api/pitches/{pitch}. With --number: that version's HTML alone, from GET /projects/api/pitches/{pitch}/versions/{n}, so it can go straight to a file; -o json then prints the version's number, html, htmlBytes, note and createdAt.

```
qits pitches show [--number <n|latest>] [--output table|json] [--pitch <pitch>] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--number <n\|latest>` | Print the HTML of the version with this number instead of the pitch: a number from 1, or latest. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--pitch <pitch>` | The pitch: its id, passed to the service as it is. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits pitches show --pitch 6f1c0e2a-...
qits pitches show --pitch 6f1c0e2a-... --number latest > pitch.html
qits pitches --pitch 6f1c0e2a-... show --number 1 -o json | jq -r .note
```

- --number is a version's number, from 1, or latest.

### Exit codes

- `0` Done.
- `1` The platform refused (for example a pitch or version it does not know, HTTP 404), or cannot be reached.
- `2` Used wrongly (for example no --pitch, or a --number that is neither a number nor latest), not signed in, or the session ended.

## qits pitches create

Pitch a new mock of one of the UI builder's screenshots, as its first version.

Reads the payload, a JSON object, on stdin and sends it unchanged to POST /projects/api/pitches: {"project", "repository", "route", "screenshot", "kind", "viewport", "colorScheme"?, "manifestVersion", "title", "note"?, "html", "versionNote"?}. "project" is the project's id or slug and "repository" the repository's id or name; "route", "screenshot" (the file in the freeze's manifest), "kind", "viewport", "colorScheme" and "manifestVersion" are the freeze's. "html" is a complete HTML document with inline styles and no scripts; "versionNote" says what version 1 is. The command prints the pitch, its id first.

Only a named agent may create a pitch, in its own project; it becomes the pitch's owner.

With nothing on stdin (a terminal, or empty) it sends nothing, prints this usage and the payload's JSON schema from the service's OpenAPI document (/projects/q/openapi), and exits with 0.

```
qits pitches create [--output table|json] [--pitch <pitch>] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--pitch <pitch>` | The pitch: its id, passed to the service as it is. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits pitches create </dev/null
jq -n --rawfile h board.html '{project:"qits", repository:"qits-landing-app", route:"/work", screenshot:"work/board-desktop-dark.html", kind:"DATA", viewport:"DESKTOP", colorScheme:"DARK", manifestVersion:"2026.1113.1", title:"A darker board header", html:$h}' | qits pitches create
```

- The first one prints the schema: nothing is sent.

### Exit codes

- `0` The pitch is written, or nothing was put in and the schema is printed.
- `1` The platform refused (for example a field it does not take or an empty html, HTTP 400, a caller that is not a named agent of the project, HTTP 403, a project or repository it does not know, HTTP 404, or html too large, HTTP 413), or cannot be reached.
- `2` Used wrongly (for example a payload that is not a JSON object), not signed in, or the session ended.

## qits pitches add-version

Add a version to a pitch: the next number after its latest. Earlier versions stay as they are.

Reads the payload, {"html":"...","note"?:"..."}, on stdin and sends it unchanged to POST /projects/api/pitches/{pitch}/versions. "html" is a complete HTML document with inline styles and no scripts; "note" says what changed. The command prints the new version without its HTML.

Only the pitch's own agent may add a version, and an archived pitch takes none.

With nothing on stdin (a terminal, or empty) it sends nothing, prints this usage and the payload's JSON schema from the service's OpenAPI document (/projects/q/openapi), and exits with 0.

```
qits pitches add-version [--output table|json] [--pitch <pitch>] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--pitch <pitch>` | The pitch: its id, passed to the service as it is. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits pitches add-version --pitch 6f1c0e2a-... </dev/null
jq -n --rawfile h board.html '{html:$h, note:"Saved views under the filters"}' | qits pitches add-version --pitch 6f1c0e2a-...
```

### Exit codes

- `0` The version is written, or nothing was put in and the schema is printed.
- `1` The platform refused (for example an empty html, HTTP 400, a caller that is not the pitch's agent, HTTP 403, a pitch it does not know, HTTP 404, an archived pitch, HTTP 409, or html too large, HTTP 413), or cannot be reached.
- `2` Used wrongly (for example no --pitch, or a payload that is not a JSON object), not signed in, or the session ended.

## qits pitches archive

Archive a pitch: hide it from the list. Nothing is deleted, and archiving an archived pitch changes nothing.

Sends POST /projects/api/pitches/{pitch}/archive and prints the pitch. The pitch's agent, qits:admin or qits:desk-admin may archive it.

```
qits pitches archive [--output table|json] [--pitch <pitch>] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--pitch <pitch>` | The pitch: its id, passed to the service as it is. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |

### Examples

```
qits pitches archive --pitch 6f1c0e2a-...
```

### Exit codes

- `0` The pitch is archived.
- `1` The platform refused (for example a caller that is neither the pitch's agent nor an admin, HTTP 403, or a pitch it does not know, HTTP 404), or cannot be reached.
- `2` Used wrongly (for example no --pitch), not signed in, or the session ended.

## qits ui-builder

The reads for the project's ui-builder named agent (qits-2156): what the UI builder page shows, read from the same place. routes prints a frontend's frozen routes (its manifest.json), freeze one screenshot's entry in it or its frozen HTML, golden-masters a service's recorded provider states or one recorded response, and entities a service's entity map.

Everything comes from qits-artifacts' docs store, repository docs, under /artifacts/docs/docs: a frontend's release publishes @routes/<application> and a service's @contracts/<application>. Which site belongs to a repository is read from the git.repository.name its newest version records, as the page does. Without --bundle-version each command reads that newest version.

### Notes

- --output, --bundle-version and --artifacts-url may come before or after the command.
- Reads only. The command sends the caller's own credential, as the UI builder page sends the person's.
- A repository, screenshot, provider, state or operation the bundle does not have is exit 1, with one line naming it.

## qits ui-builder routes

A frontend repository's frozen routes: the manifest.json of the @routes/<application> site whose newest version was released from the repository. It holds the routes, every freeze (a screenshot's frozen HTML, its route, kind, viewport, color scheme and the provider states it recorded), the freezes by route, and the color schemes and outcomes.

Reads GET /artifacts/docs/docs, then GET /artifacts/docs/docs/{site} of each @routes/ site until one names the repository, then GET /artifacts/docs/docs/{site}/-/{version}/manifest.json. The table shows the site, the version, and one line per freeze; -o json prints the site, the version and the manifest as it was published.

```
qits ui-builder routes [--artifacts-url <url>] [--bundle-version <version>] [--output table|json] [--repository <repository>]
```

| Name | What it does |
|---|---|
| `--artifacts-url <url>` | qits-artifacts' base URL, without /artifacts. Default: QITS_ARTIFACTS_URL, else https://registry.qits.<domain>. |
| `--bundle-version <version>` | Read this version of the bundle instead of its newest: a version the site published, such as 2026.1113.154308. |
| `-o, --output table\|json` | table (the default): a readable summary. json: the published document as it was read. |
| `--repository <repository>` | The frontend repository (required), by name, such as qits-landing-app. |

### Examples

```
qits ui-builder routes --repository qits-landing-app
qits ui-builder routes --repository qits-landing-app --bundle-version 2026.1113.154308 -o json
```

### Exit codes

- `0` Done.
- `1` No routes bundle names the repository, the version holds no manifest, or the platform refused or cannot be reached.
- `2` Used wrongly (for example no --repository), not signed in, or the session ended.

## qits ui-builder freeze

One screenshot of a frontend's frozen routes: its entry in the manifest.json of the repository's @routes/<application> site (file, name, route, the provider states it recorded, triggers, kind, viewport, color scheme, data marks, connectivity), or with --html its frozen HTML.

Finds the site and reads the manifest as routes does, then, with --html, GET /artifacts/docs/docs/{site}/-/{version}/{file}. The HTML is printed as it was published, so it can go straight to a file; -o has no effect on it.

```
qits ui-builder freeze [--artifacts-url <url>] [--bundle-version <version>] [--html] [--output table|json] [--repository <repository>] [--screenshot <file>]
```

| Name | What it does |
|---|---|
| `--artifacts-url <url>` | qits-artifacts' base URL, without /artifacts. Default: QITS_ARTIFACTS_URL, else https://registry.qits.<domain>. |
| `--bundle-version <version>` | Read this version of the bundle instead of its newest: a version the site published, such as 2026.1113.154308. |
| `--html` | Print the freeze's frozen HTML instead of its entry. |
| `-o, --output table\|json` | table (the default): a readable summary. json: the published document as it was read. |
| `--repository <repository>` | The frontend repository (required), by name, such as qits-landing-app. |
| `--screenshot <file>` | The screenshot (required): the freeze's file in the manifest. |

### Examples

```
qits ui-builder freeze --repository qits-landing-app --screenshot src/app/routes/work/work.page.browser.spec.ts/board-desktop.html
qits ui-builder freeze --repository qits-landing-app --screenshot src/app/routes/work/work.page.browser.spec.ts/board-desktop.html --html > board.html
```

- --screenshot is a freeze's file as routes lists it.

### Exit codes

- `0` Done.
- `1` No routes bundle names the repository, its manifest has no such screenshot, or the platform refused or cannot be reached.
- `2` Used wrongly (for example no --screenshot), not signed in, or the session ended.

## qits ui-builder golden-masters

A service's golden masters: the golden-masters/index.json of its @contracts/<application> site (formatVersion, provider, and each provider state with its slug, params and recorded operations). With --state, that state alone; with --state and --operation, the response the state recorded for that operation.

Reads GET /artifacts/docs/docs, then (when no @contracts/<provider> site is listed) GET /artifacts/docs/docs/{site} of each @contracts/ site until one names the provider as its repository, then GET /artifacts/docs/docs/{site}/-/{version}/golden-masters/index.json and, for a recorded response, golden-masters/<file> as the index names it. A recorded response is JSON in both forms.

```
qits ui-builder golden-masters [--artifacts-url <url>] [--bundle-version <version>] [--operation <operationId>] [--output table|json] [--provider <application>] [--state <slug>]
```

| Name | What it does |
|---|---|
| `--artifacts-url <url>` | qits-artifacts' base URL, without /artifacts. Default: QITS_ARTIFACTS_URL, else https://registry.qits.<domain>. |
| `--bundle-version <version>` | Read this version of the bundle instead of its newest: a version the site published, such as 2026.1113.154308. |
| `--operation <operationId>` | With --state: print the response the state recorded for this operation. |
| `-o, --output table\|json` | table (the default): a readable summary. json: the published document as it was read. |
| `--provider <application>` | The service (required): its application, such as qits-projects, or its repository, such as qits-projects-service. |
| `--state <slug>` | Only this provider state: its slug, or its name. |

### Examples

```
qits ui-builder golden-masters --provider qits-projects
qits ui-builder golden-masters --provider qits-projects-service --state a-project-exists
qits ui-builder golden-masters --provider qits-projects --state a-project-exists --operation listProjects
```

- --provider is the application (qits-projects) or the repository (qits-projects-service), as a freeze's records name it.
- --state is a state's slug or its name.

### Exit codes

- `0` Done.
- `1` No contracts bundle for the provider, the index has no such state or the state no such operation, it recorded no response file, or the platform refused or cannot be reached.
- `2` Used wrongly (for example --operation without --state), not signed in, or the session ended.

## qits ui-builder entities

A service's entity map: the entities.json of its @contracts/<application> site (formatVersion, application, and each persisted entity with its table, its marking (domain, technical or projection), the DTOs it is exposed through and the operations that return or take them).

Finds the site as golden-masters does, then reads GET /artifacts/docs/docs/{site}/-/{version}/entities.json. The table shows one line per entity; -o json prints the map as it was published.

```
qits ui-builder entities [--artifacts-url <url>] [--bundle-version <version>] [--output table|json] [--provider <application>]
```

| Name | What it does |
|---|---|
| `--artifacts-url <url>` | qits-artifacts' base URL, without /artifacts. Default: QITS_ARTIFACTS_URL, else https://registry.qits.<domain>. |
| `--bundle-version <version>` | Read this version of the bundle instead of its newest: a version the site published, such as 2026.1113.154308. |
| `-o, --output table\|json` | table (the default): a readable summary. json: the published document as it was read. |
| `--provider <application>` | The service (required): its application, such as qits-projects, or its repository, such as qits-projects-service. |

### Examples

```
qits ui-builder entities --provider qits-projects
qits ui-builder entities --provider qits-projects-service -o json
```

### Exit codes

- `0` Done.
- `1` No contracts bundle for the provider, its version publishes no entity map, or the platform refused or cannot be reached.
- `2` Used wrongly (for example no --provider), not signed in, or the session ended.

## qits release-request

Release requests: the one way to release a repository. list shows them (of the whole project, or of one repository), create asks for a branch to be released, join adds a branch to an open request, move takes one branch off it onto the request queued after it, withdraw ends a request that must not ship, approve lets the fold a request is on go ahead, decline sends it back, regenerate runs one release output again, override lifts the upstream-in-flight gate for one upstream request that is itself still building, and rerun fires one pipeline phase again: QA or PUBLISH re-fire that phase's CI run, DEPLOY redeploys the already-released version.

A request folds main and its branches into one commit, and the builds of that commit are its gate. States: PENDING (waiting for its builds), READY, RELEASED (the tag is cut, waiting on its remaining gates), FINALIZED (the tag is merged into main, done), REJECTED (a gating build was red), FAILED (the release itself failed), CONFLICTED (the branches do not merge), WITHDRAWN, OBSOLETE (a later request for the repository superseded this one before it finished).

create, join, withdraw, approve and decline print the request's quality gates as the platform sends them: one row per gate (kind, position, state, detail), and, under a gate that is waiting on something specific, one indented line per check it is waiting for (such as the upstream request an upstream-in-flight gate names). An older platform with no qualityGates[] yet gets the deprecated gates table instead (kind, state, reason).

### Notes

- A REJECTED or CONFLICTED request comes back by itself when one of its branches gets a new push. Fix the branch and push; do not open a new request.
- When a red build was the platform's fault and not the code's (a flaked container, a registry that was down), retry that run with `qits ci retry <run id>`: it builds the same commit again. `qits ci runs --release-request <id>` finds the request's runs. A red automation's run (a dependency bump, golden recordings, the entity diagram, screenshot baselines, cli-tapes, estate pins) is re-run with `qits release-request regenerate --kind <kind>` instead, not `qits ci retry`. Do not open a new request, and do not withdraw this one.
- When one branch alone holds a shared request back (it conflicts, its check is red, it is not ready), move it with `move`: do not force-push it back to main, and do not hold the others. An agent whose own branch blocks a shared request moves it itself.
- `withdraw` is only for a request that must not ship: the change is wrong, or nobody wants it any more. WITHDRAWN is final.
- approve and decline judge one fold: the request's merged sha. A push that folds the request again leaves the decision behind, and the new fold waits for a decision of its own.
- decline is not withdraw. A declined request goes REJECTED and comes back with the next push to one of its branches; WITHDRAWN is final.
- A release request carries a list of approval requirements, each naming the roles that may satisfy it: person, agent (an agent of the request's own project) or agent@<project slug>. approve and decline print them, with whether each is satisfied and by whom, before reading the request and again after deciding. A person holding qits:admin may satisfy a requirement admitting person; an agent may satisfy one admitting agent or agent@<its project>, including a requirement on the very request it asked for, unless that requirement excludes the request's own authors. Asserted identity headers, service clients and qits:system are refused (HTTP 403) either way.
- --project and --repository may come before or after the command. list, approve and decline work without --repository; approve and decline find the request in the project.

## qits release-request list

List release requests: of the whole project, or of one repository with --repository.

Without --repository it shows every repository's requests, as the landing app does: the open ones and the last 10 FINALIZED, most recently moved first, with a REPOSITORY and an APPROVAL column.

With --repository it shows that repository's open requests only. Open means every state but FINALIZED, WITHDRAWN and OBSOLETE.

--state asks for other ones in both forms. The ID column shows the request's id, <repository>-rr-<n>, which `join` and `withdraw` take. -o json also has its uuid (id); the commands take that too.

The FREEZE column is the soft feature freeze: counted branches of the threshold in force (1/3), 3/3 frozen once the request takes no more new branches, off when the threshold is 0, and queued for the request that waits behind a frozen one. A dash means the platform sent no freeze.

```
qits release-request list [--limit <n>] [--output table|json] [--project <project>] [--projects-url <url>] [--repository <repository>] [--state <STATE|all>]
```

| Name | What it does |
|---|---|
| `--limit <n>` | Show at most this many requests, the most recent first. Default: all that came back. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |
| `--repository <repository>` | The repository: its id or name. |
| `--state <STATE\|all>` | Only this state (PENDING, READY, RELEASED, FINALIZED, REJECTED, FAILED, CONFLICTED, WITHDRAWN, OBSOLETE), or all for every request. Default: the open ones (and, for the whole project, the last 10 FINALIZED). |

### Examples

```
qits release-request --project qits list
qits release-request --project qits list --state all --limit 20
qits release-request --project qits --repository qits-ci-service list
qits release-request --project qits --repository qits-ci-service list --state all -o json
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits release-request create

Ask for a branch to be released once its builds are green.

The platform puts the branch where it belongs: in the repository's active request (opened if there is none), or, while that request is frozen, in the one request queued behind it. The first line printed says where the branch went; the request follows.

The soft feature freeze: an active request is frozen once it holds as many counted branches as the threshold. Counted are branches that name a work item (epics, tasks, tickets of any type, bug fixes included); main, maintenance/* and release/* never count. The threshold is the repository's .config/qits/release-requests.yml feature_freeze, else the project's .config/qits/project.yml release_requests.feature_freeze, else 3; 0 turns the freeze off.

```
qits release-request create --branch <branch> [--output table|json] [--priority <priority>] [--project <project>] [--projects-url <url>] [--repository <repository>]
```

| Name | What it does |
|---|---|
| `--branch <branch>` | Required. The branch to release. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--priority <priority>` | LOWEST, LOW, MEDIUM, HIGH, HIGHER or BLOCKING. Default: the platform's (MEDIUM). |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |
| `--repository <repository>` | The repository: its id or name. |

### Examples

```
qits release-request --project qits --repository qits-ci-service create --branch feature/log-view
qits release-request --project qits --repository qits-ci-service create --branch main --priority HIGH
```

- While the active request is frozen, ANY new branch (a feature, a bug fix, a maintenance bump) goes into the request queued behind it, opened if there is none. The queued request folds nothing and runs no gate until the active one has released or been withdrawn.
- To put a branch into the frozen request anyway, name it: `join --request <its id>`.
- A branch that is already on a request that has not released answers that request; it opens no second one. Check the id that comes back.
- On a project wrapper the answer can be a request somebody else opened. A red gate holds every branch on it.
- There is no summary to give: the request lists its items, one per branch somebody named, with the work item the branch names, and the people who asked for it.
- To add another branch to a request you have, use `join`.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits release-request join

Add a branch to an open release request.

The platform folds the request again with the branch and, if that makes a new commit, builds that commit. The first line printed says where the branch went; the request that came back follows.

Naming the request is how a branch gets past the soft feature freeze: a frozen request takes any branch you join to it by name, and the request marks that source as an explicit join. `create` would have queued it behind the frozen request instead.

```
qits release-request join --branch <branch> --request <id> [--output table|json] [--priority <priority>] [--project <project>] [--projects-url <url>] [--repository <repository>]
```

| Name | What it does |
|---|---|
| `--branch <branch>` | Required. The branch to add. |
| `--request <id>` | Required. The request: its id as list shows it (qits-ci-service-rr-12), its uuid, or the start of the uuid. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--priority <priority>` | LOWEST, LOW, MEDIUM, HIGH, HIGHER or BLOCKING. Default: MEDIUM for a new branch; a branch already on the request keeps its priority. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |
| `--repository <repository>` | The repository: its id or name. |

### Examples

```
qits release-request --project qits --repository qits-ci-service join --request qits-ci-service-rr-12 --branch feature/log-search
qits release-request --project qits --repository qits-ci-service join --request qits-ci-service-rr-12 --branch feature/log-search --priority BLOCKING
```

- Safe to repeat: a branch already on a request that has not released adds nothing and answers that request. With --priority it states that priority again; without, the branch keeps its priority.
- From RELEASED on a request takes no more branches (HTTP 409, and the message names the next request): join that one, or use `create`.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly (for example a --request that fits no request, or more than one), not signed in, or the session ended.

## qits release-request move

Move one branch off an open release request, onto the request queued after it.

For a source that alone holds a shared request back: it conflicts with the others, its check is red, or it is not ready. The request folds again without it and goes back to PENDING; the branch joins the request queued after this one (opened queued behind it if there is none), keeping its priority, author and feature stamp, and releases from there once this request has released or been withdrawn.

This replaces force-pushing the branch back to main, withdrawing every other branch with it, or holding them all. The branch's owner is told, and both requests list the move with who made it and why.

The first line printed says where the branch went; the request that now holds it follows. -o json prints both requests (from, to).

```
qits release-request move --branch <branch> --reason <text> --request <id> [--output table|json] [--project <project>] [--projects-url <url>] [--repository <repository>]
```

| Name | What it does |
|---|---|
| `--branch <branch>` | Required. The branch to move. |
| `--reason <text>` | Required. Why it moves, in a sentence: both requests list it, and the branch's owner reads it. |
| `--request <id>` | Required. The request the branch is on: its id as list shows it (qits-ci-service-rr-12), its uuid, or the start of the uuid. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |
| `--repository <repository>` | The repository: its id or name. |

### Examples

```
qits release-request --project qits --repository qits-ci-service move --request qits-ci-service-rr-12 --branch ticket/qits-1660 --reason "It conflicts with ticket/qits-1658."
```

- Who may: qits:admin and qits:admin-agent, and the project's delivery desk, any branch; an agent only its own branch, one its token may push (HTTP 403 for another's).
- Refused (HTTP 409): a request that is RELEASED, FINALIZED, WITHDRAWN or OBSOLETE; a request that is itself queued; the default branch and a released tag, which every request folds; and the request's only branch, which `withdraw` is for.
- A branch that is not on the request is HTTP 404.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly (for example a --request that fits no request, or more than one, or an empty --reason), not signed in, or the session ended.

## qits release-request withdraw

Withdraw an open release request, so it does not ship.

WITHDRAWN is final: the request is not built or released again, and its branches are free. The next `create` for one of them opens a new request. It prints the request that came back.

```
qits release-request withdraw --request <id> [--output table|json] [--project <project>] [--projects-url <url>] [--reason <text>] [--repository <repository>]
```

| Name | What it does |
|---|---|
| `--request <id>` | Required. The request: its id as list shows it (qits-ci-service-rr-12), its uuid, or the start of the uuid. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |
| `--reason <text>` | Why it must not ship, in a sentence. The request shows it as its detail. Default: the platform writes who withdrew it. |
| `--repository <repository>` | The repository: its id or name. |

### Examples

```
qits release-request --project qits --repository qits-ci-service withdraw --request qits-ci-service-rr-12
qits release-request --project qits --repository qits-ci-service withdraw --request qits-ci-service-rr-12 --reason "The log view moves to qits-observability"
```

- Only for a request that must not ship. A gating build that was red because of the platform, not the code, runs again with `qits ci retry <run id>`. A REJECTED or CONFLICTED request comes back by itself when one of its branches gets a new push.
- Without --reason the platform writes who withdrew it.
- A RELEASED, FINALIZED, WITHDRAWN or OBSOLETE request cannot be withdrawn (HTTP 409).

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly (for example a --request that fits no request, or more than one), not signed in, or the session ended.

## qits release-request approve

Approve the fold a release request is on, so it may release once its gates are green.

The approval is for one fold, the request's merged sha. A push that folds the request again leaves it behind, and the new fold needs an approval of its own. It prints the request's approval requirements, with their admitted roles and whether each is satisfied, before the decision and again after, then the request that came back.

```
qits release-request approve --request <id> [--note <text>] [--output table|json] [--project <project>] [--projects-url <url>] [--repository <repository>] [--sha <merged sha>]
```

| Name | What it does |
|---|---|
| `--request <id>` | Required. The request: its id as list shows it (qits-ci-service-rr-221), in any case, or its whole uuid. It is found in the project, so --repository is not needed. |
| `--note <text>` | Why, in a sentence, for the record. A decline's note becomes the request's detail, the first thing whoever answers the decline reads. Default: none. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |
| `--repository <repository>` | The repository: its id or name. |
| `--sha <merged sha>` | The fold you judged: the request's merged sha, as the landing app or -o json shows it. This is the strict form: the platform refuses the decision (HTTP 409) if the request is on another fold by then. Without it the command judges whatever fold the request is on at the moment it is read. |

### Examples

```
qits release-request --project qits approve --request qits-ci-service-rr-221 --sha 3f9c2e1a7b8d4c6e5f0a1b2c3d4e5f6a7b8c9d0e
qits release-request --project qits approve --request qits-ci-service-rr-221 --note "Read the diff: the pin moves to the export release."
```

- Give --sha to approve exactly the fold you read. Without it the command approves whatever fold the request is on when it reads it.
- A person holding qits:admin may satisfy a requirement that admits person. An agent may satisfy a requirement that admits agent or agent@<its project>, including one on the very request it asked for, unless that requirement excludes the request's own authors. Asserted identity headers, service clients and qits:system are refused (HTTP 403) either way; the message names each open requirement and the roles it admits.
- HTTP 409 means the request is on another fold than the one sent (the message names the one it is on now), has no fold, has concluded or is being released, or every requirement at the fold is already satisfied. Read it again and judge the fold it is on.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status and says why), cannot be reached, or the request has no fold yet, so there is nothing to approve.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits release-request decline

Decline the fold a release request is on, so it does not release.

The request goes REJECTED and comes back by itself when one of its branches gets a new push, which makes a new fold to judge. It is not withdraw: WITHDRAWN is final. It prints the request's approval requirements, with their admitted roles and whether each is satisfied, before the decision and again after, then the request that came back.

```
qits release-request decline --request <id> [--note <text>] [--output table|json] [--project <project>] [--projects-url <url>] [--repository <repository>] [--sha <merged sha>]
```

| Name | What it does |
|---|---|
| `--request <id>` | Required. The request: its id as list shows it (qits-ci-service-rr-221), in any case, or its whole uuid. It is found in the project, so --repository is not needed. |
| `--note <text>` | Why, in a sentence, for the record. A decline's note becomes the request's detail, the first thing whoever answers the decline reads. Default: none. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |
| `--repository <repository>` | The repository: its id or name. |
| `--sha <merged sha>` | The fold you judged: the request's merged sha, as the landing app or -o json shows it. This is the strict form: the platform refuses the decision (HTTP 409) if the request is on another fold by then. Without it the command judges whatever fold the request is on at the moment it is read. |

### Examples

```
qits release-request --project qits decline --request qits-ci-service-rr-221 --sha 3f9c2e1a7b8d4c6e5f0a1b2c3d4e5f6a7b8c9d0e --note "The README promises an approval policy nobody has signed off yet."
```

- Give --sha to decline exactly the fold you read. Without it the command declines whatever fold the request is on when it reads it.
- To stop a request for good, use `withdraw` instead.
- A person holding qits:admin may satisfy a requirement that admits person. An agent may satisfy a requirement that admits agent or agent@<its project>, including one on the very request it asked for, unless that requirement excludes the request's own authors. Asserted identity headers, service clients and qits:system are refused (HTTP 403) either way; the message names each requirement it concerns and the roles it admits.
- HTTP 409 means the request is on another fold than the one sent (the message names the one it is on now), has no fold, has concluded or is being released, or its fold has no approval requirement at all. Read it again and judge the fold it is on.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status and says why), cannot be reached, or the request has no fold yet, so there is nothing to decline.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits release-request regenerate

Generate one release output again on an open release request: the entity diagram, the screenshot baselines, a CLI's tapes, the dependency bump, the estate pins, or the golden recordings (maven or npm).

QA checks the entity diagram, the screenshot baselines and a CLI's tapes and fails when they are stale; it does not regenerate them. This command asks for the run. The automation commits the output to maintenance/automations/<kind>/<request id>, the request folds again with it, and QA then runs again. It prints the run's id.

```
qits release-request regenerate --kind <kind> [--output table|json] [--project <project>] [--projects-url <url>] [--repository <repository>] [--request <id>]
```

| Name | What it does |
|---|---|
| `--kind <kind>` | Required. What to generate: entity-diagram, entity-exposure, screenshot-baselines, cli-tapes, dependency-bump, estate-pins, golden-recordings-maven or golden-recordings-npm. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |
| `--repository <repository>` | The repository: its id or name. |
| `--request <id>` | The request: its id as list shows it (qits-ci-service-rr-12), its uuid, or the start of the uuid. Default: the repository's one open request. |

### Examples

```
qits release-request --project qits --repository qits-landing-app regenerate --kind screenshot-baselines
qits release-request --project qits --repository qits-projects-service regenerate --request qits-projects-service-rr-12 --kind entity-diagram
qits release-request --project qits --repository qits-platform-access-monorepo regenerate --kind cli-tapes
```

- Without --request it takes the repository's one open request. A REJECTED request is open: a red QA is the usual reason to regenerate.
- 409 means a run of this kind is already active for the request, the request is not open or has no fold, or bumping is off. 404 means the platform knows no such kind or request. 503 means qits-maintenance cannot be reached; nothing ran. 504 means it was asked and did not answer in time; whether it ran is unknown, so check `qits maintenance automations --request <id>` before pressing again.
- `qits maintenance automations --request <id>` shows each kind's state, and `qits maintenance bump <run id>` how the run went.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly (for example no --request when the repository has no open request or more than one), not signed in, or the session ended.

## qits release-request override

Lift the upstream-in-flight gate (epic qits-897) for one upstream request that is still building, so this request's QA may start without waiting for it.

The gate holds a fold while an open request upstream of this one, in another repository of the same project, is still building its own QA, publishing or releasing. Override tells the platform to stop waiting on exactly the named upstream request; any other upstream request the gate still names keeps holding the fold. It prints the request that came back.

Only a person's own CLI sign-in counts for this decision. An agent, a workspace credential or a service client is refused (HTTP 403), in the door's own words.

```
qits release-request override --request <id> --upstream <id> [--note <text>] [--output table|json] [--project <project>] [--projects-url <url>] [--repository <repository>]
```

| Name | What it does |
|---|---|
| `--request <id>` | Required. The held request: its id as list shows it (qits-ci-frontend-rr-12), its uuid, or the start of the uuid. |
| `--upstream <id>` | Required. The upstream request the gate is waiting for: its id (qits-ci-service-rr-7), in any case, or its whole uuid. |
| `--note <text>` | Why, in a sentence, for the record. Default: none. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |
| `--repository <repository>` | The repository: its id or name. |

### Examples

```
qits release-request --project qits --repository qits-ci-frontend override --request qits-ci-frontend-rr-12 --upstream qits-ci-service-rr-7
qits release-request --project qits --repository qits-ci-frontend override --request qits-ci-frontend-rr-12 --upstream qits-ci-service-rr-7 --note "qits-ci-service-rr-7 is a doc-only change; safe to go ahead."
```

- --upstream is the upstream request exactly as the gate's check names it: its id (qits-ci-service-rr-7) or its whole uuid.
- HTTP 409 means the held request is not pending a verdict of its own to override (it has already got one, or it is not open), or the override is moot because the named upstream request is not building any more. Read the request again.
- HTTP 404 means no such held request or no such upstream request.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly (for example a --request that fits no request, or more than one), not signed in, or the session ended.

## qits release-request rerun

Rerun one phase of a release request's pipeline.

QA and PUBLISH re-fire that phase's CI run. DEPLOY redeploys the request's already-released version, the remedy for a deployment that got rolled back.

The request's own state does not change here: the result of the rerun arrives later, on the event bus, the same way the first run did. This command only asks for it and prints the request as it stands now, which still shows the previous run's outcome.

```
qits release-request rerun --phase <phase> --request <id> [--output table|json] [--project <project>] [--projects-url <url>] [--repository <repository>]
```

| Name | What it does |
|---|---|
| `--phase <phase>` | Required. The phase to rerun: QA, PUBLISH or DEPLOY, in any case. |
| `--request <id>` | Required. The request: its id as list shows it (qits-ci-service-rr-12), its uuid, or the start of the uuid. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects` (https://idp.qits.<QITS_DOMAIN>/idp gives https://projects.qits.<QITS_DOMAIN>). |
| `--repository <repository>` | The repository: its id or name. |

### Examples

```
qits release-request --project qits --repository qits-ci-service rerun --request qits-ci-service-rr-12 --phase QA
qits release-request --project qits --repository qits-ci-service rerun --request qits-ci-service-rr-12 --phase deploy
```

- --phase is QA, PUBLISH or DEPLOY, in any case; the platform refuses anything else (HTTP 400).
- 404 means the platform knows no such release request.
- 409 means the platform, or qits-ci for a QA or PUBLISH rerun, refuses to run that phase now; the message says why.
- 503 means qits-ci cannot be reached, or this repository is not set up with it; nothing ran.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly (for example a --request that fits no request, or more than one, or a --phase that is none of QA, PUBLISH or DEPLOY), not signed in, or the session ended.

## qits ci

The builds of qits-ci: runs lists a repository's runs, run shows one run with its steps and their logs, retry runs a finished run again, cancel stops a queued or running one, and report shows a run's release reports (report submit is the CI step's side, which collects and uploads them).

A release request's gating runs build its backing branch release/<request id> and carry the request's id. Statuses: QUEUED and RUNNING (not finished), SUCCESS, FAILED (the code's verdict), and CANCELLED, TIMED_OUT, CONFIG_ERROR (the run's end, not a verdict on the code).

### Notes

- Reading runs takes qits:admin, qits:admin-agent, qits:desk-admin, qits:desk, qits:system or qits:agent. retry takes qits:admin, qits:admin-agent, qits:desk-admin, qits:desk or qits:agent. cancel takes the same, qits:desk and qits:agent bound to the run's own project.
- To follow a run, run `qits ci run <run id>` again. There is no live log stream. A build's verdict also comes as an event: `qits events --filter=BuildSuccessful,BuildFailed`.
- --project, --repository, --output and the two -url options may come before or after the command.
- A release request's QA run carries release reports: which tests failed, with their class, name and message, and the line coverage of the tree and of the change, with more kinds to come. `qits ci report show <run id>` lists them.

## qits ci runs

List a repository's CI runs, newest first.

Columns: the run's id (its first 8 characters, enough for `run` and `retry` with --project and --repository), status, branch, commit, the release request, when it was created, and how long it took (so far, while it runs).

```
qits ci runs [--branch <branch>] [--ci-url <url>] [--limit <n>] [--output table|json] [--project <project>] [--projects-url <url>] [--release-request <id>] [--repository <repository>] [--status <STATUS>]
```

| Name | What it does |
|---|---|
| `--branch <branch>` | Only the runs of this branch: main, release/<request id>, or a version for a release run. |
| `--ci-url <url>` | The ci service's base URL, without /ci. Default: QITS_CI_URL, else the session's idp address with `idp` swapped for `ci` (https://idp.qits.<QITS_DOMAIN>/idp gives https://ci.qits.<QITS_DOMAIN>). |
| `--limit <n>` | At most this many runs, the newest. Default: 20. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where --project and --repository are looked up. Default: QITS_PROJECTS_URL, else derived from the idp address like --ci-url. |
| `--release-request <id>` | Only the runs of this release request: its id (qits-ci-service-rr-12), its uuid, or the start of the uuid. |
| `--repository <repository>` | The repository: its id or name. |
| `--status <STATUS>` | Only the runs in this status: QUEUED, RUNNING, SUCCESS, FAILED, CANCELLED, TIMED_OUT or CONFIG_ERROR. |

### Examples

```
qits ci runs --project qits --repository qits-ci-service
qits ci runs --project qits --repository qits-ci-service --status FAILED --limit 5
qits ci runs --project qits --repository qits-ci-service --release-request qits-ci-service-rr-12
qits ci runs --project qits --repository qits-ci-service --branch main -o json
```

- A release request's gating runs: --release-request with the request's id as `qits release-request list` shows it (qits-ci-service-rr-12), its uuid or the start of the uuid. They build the request's branch, release/<request id>.
- The service filters by repository and count only. --branch, --status and --release-request are applied here, over all of the repository's runs, and --limit after them.
- The listing carries no step output (each step's output is null here): read it with `qits ci run <run id> --logs`.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits ci run

Show one CI run: what it built, its status, and its steps with their exit codes.

Without --logs, a failed step's last lines are shown too, enough to see why it failed. --logs prints every step's whole output instead, the first step first. The service keeps the end of each step's output. A running step shows what it has printed so far. Terminal control characters are taken out.

```
qits ci run [--ci-url <url>] [--logs] [--output table|json] [--project <project>] [--projects-url <url>] [--repository <repository>] <run id>
```

| Name | What it does |
|---|---|
| `<run id>` | The run: its id, or its start when --project and --repository name its repository. |
| `--ci-url <url>` | The ci service's base URL, without /ci. Default: QITS_CI_URL, else the session's idp address with `idp` swapped for `ci` (https://idp.qits.<QITS_DOMAIN>/idp gives https://ci.qits.<QITS_DOMAIN>). |
| `--logs` | Also print each step's output, with terminal control characters taken out. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where --project and --repository are looked up. Default: QITS_PROJECTS_URL, else derived from the idp address like --ci-url. |
| `--repository <repository>` | The repository: its id or name. |

### Examples

```
qits ci run 5f2c0a9e-1b7d-4c2e-9a41-3d8e6f0b2c17 --logs
qits ci run 5f2c0a9e --project qits --repository qits-ci-service
qits ci run 5f2c0a9e-1b7d-4c2e-9a41-3d8e6f0b2c17 -o json | jq -r .status
```

- <run id> is the run's whole id, or its start when --project and --repository name the repository.
- The exit code says whether the read worked, not whether the run passed. Read the status.
- -o json prints the service's answer, the logs included.

### Exit codes

- `0` Done, whatever the run's status.
- `1` The platform refused (for example no such run, HTTP 404), or cannot be reached.
- `2` Used wrongly (for example an id start that fits no run of the repository, or more than one), not signed in, or the session ended.

## qits ci retry

Run a finished CI run again: the same commit, the same pipeline, the same release request.

For a red run that was the platform's fault, not the code's: a flaked container, a registry that was down, a step that ran out of time on a busy host. The new run is queued; the command prints its id and how to follow it. Its verdict counts for the release request like the first run's would have. A release-request automation's run (a dependency bump, golden recordings, the entity diagram, screenshot baselines, cli-tapes, estate pins) is refused instead (HTTP 409): re-run it with `qits release-request regenerate --kind <kind>`.

```
qits ci retry [--ci-url <url>] [--output table|json] [--project <project>] [--projects-url <url>] [--repository <repository>] <run id>
```

| Name | What it does |
|---|---|
| `<run id>` | The run to retry: its id, or its start when --project and --repository name its repository. |
| `--ci-url <url>` | The ci service's base URL, without /ci. Default: QITS_CI_URL, else the session's idp address with `idp` swapped for `ci` (https://idp.qits.<QITS_DOMAIN>/idp gives https://ci.qits.<QITS_DOMAIN>). |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where --project and --repository are looked up. Default: QITS_PROJECTS_URL, else derived from the idp address like --ci-url. |
| `--repository <repository>` | The repository: its id or name. |

### Examples

```
qits ci retry 5f2c0a9e-1b7d-4c2e-9a41-3d8e6f0b2c17
qits ci retry 5f2c0a9e --project qits --repository qits-ci-service
```

- Only a finished run can be retried. One that is queued or running answers HTTP 409: wait for it.
- A retry builds the same commit. To fix the code, push the branch instead: the release request folds again and builds the new commit.
- Takes qits:admin, qits:admin-agent, qits:desk-admin, qits:desk or qits:agent.

### Exit codes

- `0` The new run is queued.
- `1` The platform refused: the run has not finished yet (HTTP 409), the run is an automation's run (HTTP 409), there is no such run (HTTP 404), or your roles do not allow it (HTTP 403). Or it cannot be reached.
- `2` Used wrongly (for example an id start that fits no run of the repository, or more than one), not signed in, or the session ended.

## qits ci cancel

Stop a queued or running CI run, then show it.

A queued run is cancelled at once and never starts. A running one is asked to stop: its step in flight is stopped and the rest are skipped, which takes a moment, so the run shown may still say RUNNING. Run `qits ci run <run id>` again to see it end. A cancelled run ends CANCELLED, which is not a verdict on the code: it publishes neither BuildSuccessful nor BuildFailed.

Sends POST /ci/api/runs/{runId}/cancel with {reason} (HTTP 202), then GET /ci/api/runs/{runId}.

```
qits ci cancel [--ci-url <url>] [--output table|json] [--project <project>] [--projects-url <url>] [--reason <text>] [--repository <repository>] <run id>
```

| Name | What it does |
|---|---|
| `<run id>` | The run to cancel: its id, or its start when --project and --repository name its repository. |
| `--ci-url <url>` | The ci service's base URL, without /ci. Default: QITS_CI_URL, else the session's idp address with `idp` swapped for `ci` (https://idp.qits.<QITS_DOMAIN>/idp gives https://ci.qits.<QITS_DOMAIN>). |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where --project and --repository are looked up. Default: QITS_PROJECTS_URL, else derived from the idp address like --ci-url. |
| `--reason <text>` | Why it is cancelled, kept on the run. |
| `--repository <repository>` | The repository: its id or name. |

### Examples

```
qits ci cancel 5f2c0a9e-1b7d-4c2e-9a41-3d8e6f0b2c17
qits ci cancel 5f2c0a9e --project qits --repository qits-ci-service --reason 'superseded by a fix'
```

- Only a queued or running run can be cancelled. One that has finished answers HTTP 409.
- --reason is kept on the run as its cancellation reason, and `qits ci run` shows it.
- Takes qits:admin, qits:admin-agent, qits:desk-admin, qits:desk or qits:agent; a desk or an agent only for a run in its own project, and another project's run answers HTTP 403.
- -o json prints the run as the service answers it after the cancel.

### Exit codes

- `0` The run is cancelled, or asked to stop; it is printed.
- `1` The platform refused: the run has already finished (HTTP 409), there is no such run (HTTP 404), or your roles do not allow it (HTTP 403). Or it cannot be reached.
- `2` Used wrongly (for example an id start that fits no run of the repository, or more than one), not signed in, or the session ended.

## qits ci report

Release reports: what a release request's QA run found, beside its verdict. show lists a run's reports with their highlights, and prints one kind's whole report. submit is what every QA step runs after its script: it collects the reports from the step's files and uploads them.

Kinds today: test-results (every test run, and each failing test with its class, name, file and message), coverage (line coverage of the whole tree, its change against the baseline, and the coverage of the lines the change touched), contracts (the pacts the repository holds as consumer or verifies as provider, and the provider states it declares, with the pacts, interactions and states added or removed since the baseline), entity-changes (the generated entity diagrams under docs/database/, with the tables, columns and relations added, removed or changed since the baseline), screenshots (the committed screenshot baselines added, changed or removed since the baseline, by path and blob, for the release request to show before and after), stale-outputs (the check-outputs kinds the step found stale, each with its regenerate command) and golden-master-coverage (the operations of a java service's OpenAPI document that no golden master records). A kind or a run with nothing to report shows nothing; a report never fails or holds a release.

### Notes

- Each kind and its highlights are computed by the CLI that submitted them; qits-ci keeps them as they came, keyed by run, step and kind.
- A report compares with its baseline: the same kind in the QA run of the release request that produced the repository's newest released version. A first release has none.

## qits ci report submit

Collect this CI step's release reports from its files and upload them to qits-ci. Run in a QA step, after the step's own script, with that script's exit code.

For each kind: find its inputs under --root, parse them, compare with the baseline's report of the same kind, and PUT the result to the run's step. A kind with no inputs is not reported, and nothing is sent for it. One line per kind on stdout: `test-results: submitted (412 tests, 3 failed)`, `coverage: submitted (10031 lines, 80.9% covered)` or `coverage: not reported (no inputs)`.

```
qits ci report submit --exit-code <n> [--root <dir>]
```

| Name | What it does |
|---|---|
| `--exit-code <n>` | Required. What the step's own script exited with. Kept with the report; a non-zero code with no failing test is a highlight of its own. |
| `--root <dir>` | Where the step's tree is: the reports are looked for, and file paths are relative to, this directory. Default: the working directory. |

### Examples

```
qits ci report submit --exit-code "$qits_step_exit"
qits ci report submit --exit-code 1 --root /workspace/checkout
```

- test-results reads **/target/surefire-reports/TEST-*.xml, **/target/failsafe-reports/TEST-*.xml and .qits-reports/vitest-*.xml. A file that does not parse is skipped with a warning.
- A failing test gets the lines it sits on in its file for Java with surefire or failsafe (the JUnit test method, its annotations included) and for TypeScript or JavaScript with vitest (the it/test block); any other failure has none.
- coverage reads .qits-reports/jacoco.exec (JaCoCo 0.8.14's format) against every **/target/classes and against the classes the agent dumped to .qits-reports/jacoco-classes (a class Quarkus rewrote as it loaded it counts on its original's lines), and coverage/**/coverage-final.json or .qits-reports/coverage/**/coverage-final.json (istanbul's json, as vitest writes it). With a baseline it fetches the baseline's tag (git fetch --depth=1 "$QITS_CI_REPOSITORY_URL" refs/tags/<version>) and measures the lines `git diff -U0 <version> HEAD` names; when git cannot, the diff coverage is left out, with one warning.
- contracts reads pacts/*.json (the committed consumer pacts, Pact v2 to v4), .qits-reports/pact-verification/*.json (Pact-JVM's JSON verification report: the pacts the provider verified) and golden-masters/index.json (the provider states), and only in a step where test-results found a file, so a run reports its tree once.
- entity-changes reads the generated docs/database/*.md (the files `qits database diagram` wrote; a hand-written file there is ignored), only in step 0, and compares them with the same files at the baseline's tag, fetched as for coverage. Without a baseline, or at a baseline from before the diagrams, every unit is CURRENT: the diagram is new.
- screenshots reads the committed screenshot baselines (vitest browser mode's **/__screenshots__/<spec file>/<name>-<browser>-<platform>.png) from `git ls-tree` at HEAD and at the baseline's tag, fetched as for coverage, and lists the NEW, CHANGED and REMOVED ones by path and blob id, never their bytes, with the keys of **/testing/browser/renderer.txt that changed. Only in a step that rendered them: one on the renderer image (/etc/qits-renderer-provenance) or with @qits/angular's run record (node_modules/.cache/@qits/angular/screenshot-references.json); otherwise `screenshots: not reported (not rendered in this step)`, and `(no screenshot baselines)` when neither side holds one. QITS_CI_REPO_ID names the repository for the images. Without a baseline every image is NEW.
- stale-outputs reads the file QITS_CI_STALE_OUTPUTS names (relative to --root), which qits-ci's output check writes: one line per stale check-outputs kind, `<kind>\t<regenerate command>`; a line without a tab is skipped with a warning. Without the variable, the file or a line in it, `stale-outputs: not reported (no stale outputs)`. Never compared with the baseline.
- golden-master-coverage reads the file QITS_CI_UNCOVERED_OPERATIONS names (relative to --root), which `qits contracts coverage --out` writes: one line per uncovered operation, `<name>\t<METHOD>\t<path>`; a line that is not three fields is skipped with a warning. An empty file is a report with none uncovered. Its highlights are `uncovered operations: <n>`, then `uncovered: <name>` for the first nine. Without the variable or the file, `golden-master-coverage: not reported (no coverage check in this step)`. Never compared with the baseline.
- The step's environment says which run and step: QITS_CI_RUN_ID, QITS_CI_STEP_INDEX, QITS_CI_SHA, QITS_CI_REPO_NAME and QITS_CI_PROJECT_ID, all required. qits-ci is https://ci.qits.$QITS_DOMAIN, and without QITS_DOMAIN the command refuses (exit 2); no variable and no option names another address.
- The bearer comes from QITS_PUBLISH_TOKEN_COMMAND or QITS_PUBLISH_TOKEN, the first that is set, as for `qits artifacts publish`. qits-ci takes only the run's own ci-run token, while the run is running.
- No baseline (a first release, or qits-ci cannot say) is not an error: the report is sent without the comparison.
- A non-zero --exit-code means the step may have stopped part way, so its test count and coverage are partial: they are sent and say so, and never compared with the baseline's.
- Gives up after 120 seconds in all, whatever is still waiting.
- The exit code says whether the reports were stored, never whether the tests passed. The hook that runs this ignores it, so a report never changes a step's verdict.

### Exit codes

- `0` Every kind that found inputs was stored, or no kind found any.
- `1` qits-ci refused a report (the message names the status: 403 for another run's token, 409 for a run that is not running, 404 for a qits-ci without the doors), could not be reached, or did not answer within 120 seconds.
- `2` Used wrongly: --exit-code missing, a QITS_CI_* variable missing, --root not a directory, or no credential in the environment.

## qits ci report show

Show a CI run's release reports: each kind, its version, the step that submitted it, and its highlights ("3 tests failed").

--kind also prints that kind's whole report as JSON: for test-results, the totals, the suites, and each failing test with its file, class, name, shape and message; for coverage, the total, the baseline's total, the changed lines' coverage with the uncovered ones, and a line per file; for contracts, the pacts by role (CONSUMER, PROVIDER) and pair with their interactions, and the provider states with their operations; for entity-changes, each diagram's unit with its status, the tables, columns and relations that changed, and its Mermaid text before and after; for screenshots, the payload: the githost repository id, the fold's and the baseline's commits, the totals, the renderer record's changed keys, and each NEW, CHANGED or REMOVED baseline image with its path, spec, name, browser, blob ids, sizes and any exact move; for stale-outputs, each stale check-outputs kind with the command that regenerates it; for golden-master-coverage, each uncovered operation with its name, method and path.

```
qits ci report show [--ci-url <url>] [--kind <kind>] [--output table|json] [--project <project>] [--projects-url <url>] [--repository <repository>] <run id>
```

| Name | What it does |
|---|---|
| `<run id>` | The run: its id, or its start when --project and --repository name its repository. |
| `--ci-url <url>` | The ci service's base URL, without /ci. Default: QITS_CI_URL, else the session's idp address with `idp` swapped for `ci` (https://idp.qits.<QITS_DOMAIN>/idp gives https://ci.qits.<QITS_DOMAIN>). |
| `--kind <kind>` | Only this kind of report (test-results, coverage, contracts, entity-changes, screenshots, stale-outputs, golden-master-coverage), and print the whole of it. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where --project and --repository are looked up. Default: QITS_PROJECTS_URL, else derived from the idp address like --ci-url. |
| `--repository <repository>` | The repository: its id or name. |

### Examples

```
qits ci report show 5f2c0a9e-1b7d-4c2e-9a41-3d8e6f0b2c17
qits ci report show 5f2c0a9e --project qits --repository qits-ci-service --kind test-results
qits ci report show 5f2c0a9e --project qits --repository qits-ci-service --kind coverage
qits ci report show 5f2c0a9e --project qits --repository qits-landing-app --kind contracts
qits ci report show 5f2c0a9e --project qits --repository qits-ci-service --kind entity-changes
qits ci report show 5f2c0a9e --project qits --repository qits-landing-app --kind screenshots
qits ci report show 5f2c0a9e-1b7d-4c2e-9a41-3d8e6f0b2c17 -o json
```

- A release request's QA run is the one with reports: `qits ci runs --release-request <id>` finds it.
- <run id> is the run's whole id, or its start when --project and --repository name the repository.
- -o json prints the service's answer: the summaries, or with --kind the whole reports of that kind.

### Exit codes

- `0` Done, whatever the reports say, and also when the run has none.
- `1` The platform refused (for example no such run, HTTP 404), or cannot be reached.
- `2` Used wrongly (for example an id start that fits no run of the repository, or more than one), not signed in, or the session ended.

## qits database

A repository's database, read from its compiled code: `qits database diagram` writes the diagram of its entities under docs/database/, one Mermaid file per persistence unit.

## qits database diagram

Write the entity diagram of a maven repository's JPA mapping: one Markdown file with a Mermaid erDiagram per persistence unit it declares, read from its compiled classes.

Compile first: the classes come from every reactor module's target/classes and the libraries from each module's target/qits-classpath.txt, which `dependency:build-classpath -Dmdep.outputFile=target/qits-classpath.txt -DincludeScope=runtime` writes. One line per file on stdout: `written docs/database/ci.md`, `unchanged ...` or `deleted ...`.

```
qits database diagram [--check] [--out <dir>] [--root <dir>]
```

| Name | What it does |
|---|---|
| `--check` | Write and delete nothing; exit 1 when a file would change. Lines say `would write` and `would delete`. |
| `--out <dir>` | Where the files go. Default: docs/database under --root. |
| `--root <dir>` | The repository: the directory of its root pom.xml. Default: the working directory. |

### Examples

```
./mvnw -q -Dmaven.test.skip=true test-compile dependency:build-classpath -Dmdep.outputFile=target/qits-classpath.txt -DincludeScope=runtime
qits database diagram
qits database diagram --root . --out docs/database --check
```

- test-compile rather than compile: dependency:build-classpath resolves the test scope too, and in a reactor where one module depends on another's test jar that resolution fails at compile. -Dmaven.test.skip=true still compiles no test. The scope flag is -DincludeScope: the plugin reads no -Dmdep.includeScope, and with it the file lists every scope, the test jars too.
- A unit is an unprofiled quarkus.hibernate-orm.<unit>.packages key (quarkus.hibernate-orm.packages is <default>, written to default.md) in a reactor module's own application.properties or META-INF/microprofile-config.properties, never a library's. A profiled key or a ${...} value is ignored, with a warning on stderr.
- A unit draws every @Entity in its packages and their sub-packages, compiled here or in a library jar; each table names its origin, the reactor module or the library's artifactId. An entity of the repository that no unit lists is drawn in a file named after its Java package.
- Names are Hibernate's under Quarkus' defaults: JPA's implicit naming, and every name as the mapping spells it, unless the unit's physical-naming-strategy key names CamelCaseToUnderscoresNamingStrategy (then snake_case). A construct the diagram does not draw (@OneToOne, @ManyToMany, @EmbeddedId, @Inheritance, @SecondaryTable, @Formula and others) is listed under it as `Not drawn`, never refused.
- Deletes only the *.md files under --out that start with the generator's header and were not written this time. A file without the header is never touched.
- The files hold no version, time or absolute path: two runs over the same classes write the same bytes.
- Reads local files only: it needs no sign-in and calls no service.

### Exit codes

- `0` Done, or nothing to draw (`no JPA entities found`).
- `1` With --check: a file would be written or deleted. Otherwise: a file could not be read or written.
- `2` Used wrongly: --root is not a directory, or nothing is compiled under it (`compile first: no target/classes under <root>`).

## qits contracts

A repository's contracts, read from its own files: `qits contracts coverage` names every operation of its OpenAPI document that no golden master records.

## qits contracts coverage

Name every operation of a service's exported OpenAPI document that no golden master records: the platform's rule is at least one golden master per operation.

An operation is covered when a call in the golden-master index has its operationId, or its method and a path that is one of the document's server urls followed by the operation's path. A {name} in either stands for one non-empty segment; the call's query and one trailing slash are ignored. Prints one `uncovered:` line per operation that is not, in document order, then `golden-master coverage: <covered>/<total> operations covered`.

```
qits contracts coverage [--index <file>] [--no-golden-masters] [--openapi <file>] [--out <file>]
```

| Name | What it does |
|---|---|
| `--index <file>` | The golden-master index, relative to the working directory. Default: golden-masters/index.json. |
| `--no-golden-masters` | The repository declares no golden masters: every operation is uncovered, and no index is read. |
| `--openapi <file>` | The service's exported OpenAPI document, relative to the working directory. Default: docs/openapi.yml. |
| `--out <file>` | Also write the uncovered operations here as TSV, replacing the file. |

### Examples

```
qits contracts coverage
qits contracts coverage --out .qits-ci/uncovered-operations.tsv
qits contracts coverage --no-golden-masters
```

- An operation is named by its operationId, or `METHOD /path` (the document's path template) when it has none.
- --out writes one line per uncovered operation, `<name>\t<METHOD>\t<path>`, and an empty file when every operation is covered. The golden-master-coverage release report reads it through QITS_CI_UNCOVERED_OPERATIONS.
- The document is YAML, or JSON when its name ends in .json; only OpenAPI 3 is read. Only formatVersion 1 of the index is read.
- Reads local files only: it needs no sign-in and calls no service.

### Exit codes

- `0` Every operation is covered, or the document declares none.
- `1` An input is missing (`no exported OpenAPI at <path>`, `no golden-master index at <path>`) or does not parse, or --out could not be written. --out is then not written at all.
- `2` Used wrongly.
- `3` At least one operation has no golden master; each is named on stdout.

## qits deployments

The deployer, qits-deployments. `settings` reads and writes the values it hands each application in each environment. `database` moves an application's database to another server or name, and lists every move.

## qits deployments settings

The settings qits-deployments hands an application in an environment: one value per key, plain or secret. A change reaches the application on its next deployment; `preview` shows what that deployment would change.

A secret's value is never shown: the service does not send it back, and the table prints ******** in its place.

### Notes

- --output and --deployments-url may come before or after the command.
- Reading (list, preview) takes qits:admin, qits:admin-agent, qits:desk-admin, qits:system or qits:agent. Writing (set, delete) takes qits:admin or qits:system.

## qits deployments settings list

List an application's settings, of every environment or of the one --environment names.

A secret's value shows as ********; -o json gives the service's answer, where it is null.

```
qits deployments settings list --application <application> [--deployments-url <url>] [--environment <environment>] [--output table|json]
```

| Name | What it does |
|---|---|
| `--application <application>` | Required. The application, as qits-deployments names it: qits-ci. |
| `--deployments-url <url>` | The deployments service's base URL, without /deployments. Default: QITS_DEPLOYMENTS_URL, else the session's idp address with `idp` swapped for `deployments`. |
| `--environment <environment>` | Only this environment's settings: dev. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |

### Examples

```
qits deployments settings list --application qits-ci
qits deployments settings list --application qits-ci --environment dev -o json
```

- Takes qits:admin, qits:admin-agent, qits:desk-admin, qits:system or qits:agent.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits deployments settings set

Store one setting of an application in an environment. The value is read on stdin, never from a flag, so it stays out of the shell's history; one trailing line break is taken off.

--secret stores it as a secret: no answer sends its value back again. It prints the stored row with the value shown as ********, whether secret or not: the command never repeats what it was given.

```
qits deployments settings set --application <application> --environment <environment> --key <KEY> [--deployments-url <url>] [--output table|json] [--secret]
```

| Name | What it does |
|---|---|
| `--application <application>` | Required. The application, as qits-deployments names it: qits-ci. |
| `--environment <environment>` | Required. The environment: dev. |
| `--key <KEY>` | Required. The setting's key: API_TOKEN. |
| `--deployments-url <url>` | The deployments service's base URL, without /deployments. Default: QITS_DEPLOYMENTS_URL, else the session's idp address with `idp` swapped for `deployments`. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--secret` | Store the value as a secret, never sent back by any answer. |

### Examples

```
echo 'https://hooks.example.org' | qits deployments settings set --application qits-ci --environment dev --key WEBHOOK_URL
qits deployments settings set --application qits-ci --environment dev --key API_TOKEN --secret < token.txt
```

- A terminal on stdin, or nothing on it, is refused with exit 2 and nothing is sent.
- The new value reaches the application on its next deployment: `qits deployments settings preview` shows what that would change.
- Takes qits:admin or qits:system.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits deployments settings delete

Delete one setting of an application in an environment. The application loses it on its next deployment.

```
qits deployments settings delete --application <application> --environment <environment> --key <KEY> [--deployments-url <url>] [--output table|json]
```

| Name | What it does |
|---|---|
| `--application <application>` | Required. The application, as qits-deployments names it: qits-ci. |
| `--environment <environment>` | Required. The environment: dev. |
| `--key <KEY>` | Required. The setting's key: WEBHOOK_URL. |
| `--deployments-url <url>` | The deployments service's base URL, without /deployments. Default: QITS_DEPLOYMENTS_URL, else the session's idp address with `idp` swapped for `deployments`. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |

### Examples

```
qits deployments settings delete --application qits-ci --environment dev --key WEBHOOK_URL
```

- 404 (exit 1) means no such setting is stored.
- Takes qits:admin or qits:system.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits deployments settings preview

Show what the next deployment of an application in an environment would change in its settings, against what the running deployment was given.

One line each: `+ KEY=value` added, `- KEY=value` removed, `~ KEY from -> to` changed, `= KEY=value` kept (with --include-extras), a secret's value as ********; then how many are unchanged.

```
qits deployments settings preview --application <application> --environment <environment> [--deployments-url <url>] [--include-extras] [--output table|json]
```

| Name | What it does |
|---|---|
| `--application <application>` | Required. The application, as qits-deployments names it: qits-ci. |
| `--environment <environment>` | Required. The environment: dev. |
| `--deployments-url <url>` | The deployments service's base URL, without /deployments. Default: QITS_DEPLOYMENTS_URL, else the session's idp address with `idp` swapped for `deployments`. |
| `--include-extras` | Also list the values the running deployment has that no setting holds, which the next deployment keeps (`= KEY=value`). |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |

### Examples

```
qits deployments settings preview --application qits-ci --environment dev
qits deployments settings preview --application qits-ci --environment dev --include-extras
```

- Takes qits:admin, qits:admin-agent, qits:desk-admin, qits:system or qits:agent.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits deployments database

An application's database, moved by qits-deployments to the other postgres server (public or private) or to another name, and the record of every move: a deployment's and an operator's.

A move stops the application's service for its whole duration: it is a redeploy of the live version that copies the database, compares both sides' per-table row counts, and only then switches. The source is kept, closed, until its drop-after time.

### Notes

- --output and --deployments-url may come before or after the command.
- Every door but `abort` takes qits:admin, qits:admin-agent, qits:desk-admin, qits:desk or qits:agent; qits:system is refused, move included. `abort` refuses qits:agent too (HTTP 403).

## qits deployments database move

Move one database of an application to the other postgres server or to another name. It prints the queued move: its id, its state, source -> target, and the service's note when the target overrides the application's spec.

POST /deployments/api/database-moves with {application, resource?, server?, database?} (HTTP 202 {move, note}). A member left out defaults to what the live version's spec resolves.

THE APPLICATION'S SERVICE IS STOPPED FOR THE WHOLE MOVE: the move is a redeploy of its live version, whose provisioning copies the database, compares both sides' per-table counts and only then switches. Anything short of a match is undone, the source untouched. `move-status` follows it.

A target that differs from the spec is moved back by the next deployment of the application, which provisions what its spec resolves to: change the spec to keep it.

```
qits deployments database move --application <application> [--database <name>] [--deployments-url <url>] [--output table|json] [--resource <name>] [--server public|private]
```

| Name | What it does |
|---|---|
| `--application <application>` | Required. The application, as qits-deployments names it: qits-ci. |
| `--database <name>` | The database name to move it to. Default: the one the spec resolves. |
| `--deployments-url <url>` | The deployments service's base URL, without /deployments. Default: QITS_DEPLOYMENTS_URL, else the session's idp address with `idp` swapped for `deployments`. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--resource <name>` | Which of its databases, by the resource name its spec gives it: db. Required when it holds more than one. |
| `--server public\|private` | The server to move it to. Default: the one the spec resolves. |

### Examples

```
qits deployments database move --application qits-ci --server private
qits deployments database move --application qits-ci --resource db --database qits_ci_new
```

- --resource is required when the application holds more than one database.
- HTTP 400: a malformed name or an unknown server, or no --resource where several databases are held. HTTP 404: no such application, or it holds no such database. HTTP 409: refused (already there, the deployer's own database, a target another application holds, nothing serving to redeploy) or deferred (the target server is not deployed in the environment). Each is exit 1, and nothing was stopped.
- Takes qits:admin, qits:admin-agent, qits:desk-admin, qits:desk or qits:agent.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits deployments database moves

List the database moves, newest first (at most 200): of one application, or of every one. A deployment's own moves are listed with an operator's.

GET /deployments/api/database-moves[?application=]. Columns: ID, APPLICATION, RESOURCE, SOURCE, TARGET (server/database), STATE, STARTED, FINISHED, DROP AFTER (when the kept source copy is dropped).

```
qits deployments database moves [--application <application>] [--deployments-url <url>] [--output table|json]
```

| Name | What it does |
|---|---|
| `--application <application>` | Only this application's moves: qits-ci. |
| `--deployments-url <url>` | The deployments service's base URL, without /deployments. Default: QITS_DEPLOYMENTS_URL, else the session's idp address with `idp` swapped for `deployments`. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |

### Examples

```
qits deployments database moves
qits deployments database moves --application qits-ci -o json
```

- Takes qits:admin, qits:admin-agent, qits:desk-admin, qits:desk or qits:agent.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits deployments database move-status

One database move in full: where from and where to, its state, who asked, what the spec resolves to, the kept source copy and when it is dropped, the reason it stopped when it did, and both sides' per-table row counts once they were read.

GET /deployments/api/database-moves/{id}. States: QUEUED, STOPPING, EXTRACTING, INGESTING, VERIFIED, SWITCHING, then MOVED, ABORTED or REFUSED (detail says why).

```
qits deployments database move-status --id <id> [--deployments-url <url>] [--output table|json]
```

| Name | What it does |
|---|---|
| `--id <id>` | Required. The move's id, as `move` and `moves` print it. |
| `--deployments-url <url>` | The deployments service's base URL, without /deployments. Default: QITS_DEPLOYMENTS_URL, else the session's idp address with `idp` swapped for `deployments`. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |

### Examples

```
qits deployments database move-status --id 6f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f
qits deployments database move-status --id 6f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f -o json
```

- In the counts, a table both sides counted differently is marked with !; a dash is a side not read yet.
- HTTP 404 (exit 1): no move of that id.
- Takes qits:admin, qits:admin-agent, qits:desk-admin, qits:desk or qits:agent.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits deployments database abort

Stop a running database move before its switch: the extract or ingest container is stopped, the move ends ABORTED, and the application's service is scaled back up on the source. Prints the stopped move, the same way `move-status` does.

POST /deployments/api/database-moves/{id}/abort with no body (HTTP 202, the same move shape `move-status` reads).

```
qits deployments database abort --id <id> [--deployments-url <url>] [--output table|json]
```

| Name | What it does |
|---|---|
| `--id <id>` | Required. The move's id, as `move` and `moves` print it. |
| `--deployments-url <url>` | The deployments service's base URL, without /deployments. Default: QITS_DEPLOYMENTS_URL, else the session's idp address with `idp` swapped for `deployments`. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |

### Examples

```
qits deployments database abort --id 6f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f
```

- HTTP 404: no move of that id. HTTP 409: the move is QUEUED, already finished, or at SWITCHING or later; there is nothing left to stop.
- Takes qits:admin, qits:admin-agent, qits:desk-admin or qits:desk; qits:agent is refused (HTTP 403).

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits maintenance

Jobs of qits-maintenance: automations lists a release request's release-request automations and their state, automation run re-runs one kind, and bump shows how one job went.

### Notes

- --project, --repository, --output and the two -url options may come before or after the command.

## qits maintenance automations

List a release request's release-request automations and their state: the regenerations that must be fresh before the request can proceed, each one a kind the platform decided applies to this repository.

The request holds until every automation that applies to the repository is fresh for its merged commit. --fold reads an older fold; without it, the request's newest one.

```
qits maintenance automations --request <id> [--fold <sha>] [--maintenance-url <url>] [--output table|json] [--project <project>] [--projects-url <url>] [--repository <repository>]
```

| Name | What it does |
|---|---|
| `--request <id>` | Required. The release request: its id as `qits release-request list` shows it (qits-landing-app-rr-7), its uuid, or the start of the uuid. |
| `--fold <sha>` | The fold to read; without it, the request's newest. |
| `--maintenance-url <url>` | The maintenance service's base URL, without /maintenance. Default: QITS_MAINTENANCE_URL, else the session's idp address with `idp` swapped for `maintenance`. |
| `-o, --output table\|json` | table (the default): one line per field. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where --project, --repository and --request are looked up. Default: QITS_PROJECTS_URL, else derived like --maintenance-url. |
| `--repository <repository>` | The repository: its id or name. |

### Examples

```
qits maintenance --project qits --repository qits-landing-app automations --request qits-landing-app-rr-7
```

- RUN is the newest run of the kind's current attempt. `qits maintenance automation run` re-runs one; `qits ci run <id> --logs` shows a run.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits maintenance automation

One release-request automation of a release request. `run` re-runs a kind on the request's current fold.

## qits maintenance automation run

Re-run one release-request automation on a release request's current fold.

It skips carry-over and applicability, so a repository's first screenshot references come from here. The kind's own precondition still refuses the run with a sentence (no `test:browser` script, for example). It prints the job's id; `qits maintenance bump <id>` shows how it went.

```
qits maintenance automation run --kind <kind> --request <id> [--maintenance-url <url>] [--output table|json] [--project <project>] [--projects-url <url>] [--repository <repository>] [--work-item <id>]
```

| Name | What it does |
|---|---|
| `--kind <kind>` | Required. The automation's kind, as `qits maintenance automations --request <id>` names it. |
| `--request <id>` | Required. The release request: its id as `qits release-request list` shows it (qits-landing-app-rr-7), its uuid, or the start of the uuid. |
| `--maintenance-url <url>` | The maintenance service's base URL, without /maintenance. Default: QITS_MAINTENANCE_URL, else the session's idp address with `idp` swapped for `maintenance`. |
| `-o, --output table\|json` | table (the default): one line per field. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where --project, --repository and --request are looked up. Default: QITS_PROJECTS_URL, else derived like --maintenance-url. |
| `--repository <repository>` | The repository: its id or name. |
| `--work-item <id>` | The work item the commit names, for example qits-112. |

### Examples

```
qits maintenance --project qits --repository qits-landing-app automation run --request qits-landing-app-rr-7 --kind estate-pins
qits maintenance --project qits --repository qits-landing-app automation run --request qits-landing-app-rr-7 --kind estate-pins --work-item qits-112
qits maintenance --project qits --repository qits-platform-access-monorepo automation run --request qits-platform-access-monorepo-rr-3 --kind cli-tapes
```

- `qits maintenance automations --request <id>` names the kinds; `kind` is the first column. cli-tapes plays a CLI's VHS tapes (tapes/*.tape) and commits their text goldens and GIFs.
- 404 means the kind or the repository is not one the platform knows. 409 means one is already running for this (request, kind), the request takes no branch, or bumping is off.
- --work-item is the commit subject's scope (chore(<work item>): update <label>). Without it, the newest one named on the request's own commits is used.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits maintenance bump

Show one qits-maintenance job: its mode, status, the sentence about how it went, and the commit it left.

```
qits maintenance bump [--maintenance-url <url>] [--output table|json] [--project <project>] [--projects-url <url>] [--repository <repository>] <id>
```

| Name | What it does |
|---|---|
| `<id>` | The job's id, as the request printed it. |
| `--maintenance-url <url>` | The maintenance service's base URL, without /maintenance. Default: QITS_MAINTENANCE_URL, else the session's idp address with `idp` swapped for `maintenance`. |
| `-o, --output table\|json` | table (the default): one line per field. json: the service's answer, pretty-printed. |
| `--project <project>` | The project: its id, slug or name. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where --project, --repository and --request are looked up. Default: QITS_PROJECTS_URL, else derived like --maintenance-url. |
| `--repository <repository>` | The repository: its id or name. |

### Examples

```
qits maintenance bump 6f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f
```

- Run it again to follow a job: REQUESTED and RUNNING are not finished.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits users

The people who sign in to the platform, on the idp: invite mints a register token, register spends one on a new username and shows the generated password once, list shows the users and their roles, and delete removes one.

invite, list and delete need qits:admin; register needs only the token, and no sign-in.

### Notes

- --output and --idp-url may come before or after the command.

## qits users invite

Mint a register token for one new user, and print it.

The token registers one user, once, with the platform's bootstrap roles. It is printed alone on stdout and nowhere else: hand it to the person privately, and they run `qits users register`. Lost, it is simply never spent; mint another. Needs qits:admin.

```
qits users invite [--idp-url <url>] [--output table|json]
```

| Name | What it does |
|---|---|
| `--idp-url <url>` | The idp's base URL, with /idp. Default: the session's idp; inside the platform https://idp.qits.<QITS_DOMAIN>/idp; for register with no session, QITS_IDP_URL, else the address `qits login` uses. |
| `-o, --output table\|json` | table (the default): for people. json: the answer as JSON, pretty-printed. |

### Examples

```
qits users invite
qits users invite -o json
```

- -o json prints the idp's answer: the token's id, the token and when it was minted.
- Not served by the qits MCP server: the token would land in a transcript.

### Exit codes

- `0` Done.
- `1` The idp refused (HTTP 403: the caller is not qits:admin), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits users register

Spend a register token on a new username, and print the password the idp generated for it.

THE PASSWORD IS SHOWN ONLY THIS ONCE: it is printed alone on stdout, never written to a file or a log, and nobody can show it again. Store it before you do anything else. Needs no sign-in: the token is the credential. A refused username leaves the token unspent.

```
qits users register --token <token> [--idp-url <url>] [--output table|json] <username>
```

| Name | What it does |
|---|---|
| `<username>` | The new user's name. |
| `--token <token>` | Required. The register token `qits users invite` printed. This call spends it. |
| `--idp-url <url>` | The idp's base URL, with /idp. Default: the session's idp; inside the platform https://idp.qits.<QITS_DOMAIN>/idp; for register with no session, QITS_IDP_URL, else the address `qits login` uses. |
| `-o, --output table\|json` | table (the default): for people. json: the answer as JSON, pretty-printed. |

### Examples

```
qits users register --token <token> jan
qits users register --token <token> jan -o json
```

- -o json prints {"username": ..., "password": ...} and nothing of the session the idp opens.
- Then sign in with `qits login`.
- Not served by the qits MCP server: the password would land in a transcript.

### Exit codes

- `0` Done.
- `1` The idp refused the token (unknown or spent) or the username (taken, or not a valid one), or cannot be reached.
- `2` Used wrongly.

## qits users list

List the users: name, when each registered, and their roles. Needs qits:admin.

```
qits users list [--idp-url <url>] [--output table|json]
```

| Name | What it does |
|---|---|
| `--idp-url <url>` | The idp's base URL, with /idp. Default: the session's idp; inside the platform https://idp.qits.<QITS_DOMAIN>/idp; for register with no session, QITS_IDP_URL, else the address `qits login` uses. |
| `-o, --output table\|json` | table (the default): for people. json: the answer as JSON, pretty-printed. |

### Examples

```
qits users list
qits users list -o json
```

### Exit codes

- `0` Done.
- `1` The idp refused (HTTP 403: the caller is not qits:admin), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits users delete

Delete a user: their sign-ins and sessions go with them. Needs qits:admin, and nobody can delete themselves.

```
qits users delete [--idp-url <url>] [--output table|json] <username>
```

| Name | What it does |
|---|---|
| `<username>` | The user to delete. |
| `--idp-url <url>` | The idp's base URL, with /idp. Default: the session's idp; inside the platform https://idp.qits.<QITS_DOMAIN>/idp; for register with no session, QITS_IDP_URL, else the address `qits login` uses. |
| `-o, --output table\|json` | table (the default): for people. json: the answer as JSON, pretty-printed. |

### Examples

```
qits users delete jan
```

### Exit codes

- `0` Done.
- `1` The idp refused: no such user (404), it is you (409), the caller is not qits:admin (403), or it cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits remembered-devices

Your remembered browsers at the idp: the browsers that sign back in without a password. list shows them, and revoke forgets one and ends every session it opened.

Only your own: the idp answers a person signed in with `qits login` (or a browser), and refuses any other credential, an agent's QITS_TOKEN among them, with 403.

### Notes

- --output and --idp-url may come before or after the command.

## qits remembered-devices list

List your remembered browsers: each one's id, its label (the browser it was remembered from), when it last signed in, when it expires, and which one this is.

CURRENT marks the browser the calling session was opened through; a CLI session has none.

```
qits remembered-devices list [--idp-url <url>] [--output table|json]
```

| Name | What it does |
|---|---|
| `--idp-url <url>` | The idp's base URL, with /idp. Default: the session's idp; inside the platform https://idp.qits.<QITS_DOMAIN>/idp. |
| `-o, --output table\|json` | table (the default): for people. json: the answer as JSON, pretty-printed. |

### Examples

```
qits remembered-devices list
qits remembered-devices list -o json
```

- -o json prints the idp's answer, with createdAt and absoluteExpiresAt too.
- Revoke one with `qits remembered-devices revoke --device <id>`.

### Exit codes

- `0` Done.
- `1` The idp refused (HTTP 403: the credential is not a person's `qits login` session), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits remembered-devices revoke

Forget one remembered browser: it signs in with a password again, and every session it opened ends now.

```
qits remembered-devices revoke --device <id> [--idp-url <url>] [--output table|json]
```

| Name | What it does |
|---|---|
| `--device <id>` | Required. The device to forget: its id, from `qits remembered-devices list`. |
| `--idp-url <url>` | The idp's base URL, with /idp. Default: the session's idp; inside the platform https://idp.qits.<QITS_DOMAIN>/idp. |
| `-o, --output table\|json` | table (the default): for people. json: the answer as JSON, pretty-printed. |

### Examples

```
qits remembered-devices revoke --device 6f1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f
```

- The id is the ID column of `qits remembered-devices list`.

### Exit codes

- `0` Done.
- `1` The idp refused: no such device of yours (404), the credential is not a person's `qits login` session (403), or it cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits events

Print qits-events domain events as they happen, one JSON object per line on stdout. Use it to watch the platform: builds (BuildStatusChanged), release requests (ReleaseRequestStateChanged). To wait for something, do not keep this running: declare it with `qits wait` and end your turn.

Each line is the event with its payload read as JSON. Live only: the stream has no replay, so start it before the thing you wait for; events that happen while it reconnects are missed. Notes go to stderr. Stops on SIGINT or SIGTERM.

```
qits events [--events-url <url>] [--filter <names>]
```

| Name | What it does |
|---|---|
| `--events-url <url>` | The events service's base URL, without /events. Default: QITS_EVENTS_URL, else the session's idp address with `idp` swapped for `events`. |
| `--filter <names>` | Which events, as services subscribe to them: exact event names, comma-separated (for example BuildSuccessful,BuildFailed), or * for every event. No patterns. Default: *. |

### Examples

```
qits events --filter=BuildSuccessful,BuildFailed
qits events --filter=ReleaseRequestStateChanged | jq -r '.payload'
```

--filter takes exact event names. A pattern such as Build* is refused.

### Exit codes

- `0` Stopped by SIGINT or SIGTERM, or stdout was closed.
- `1` The platform refused the stream (401, 403 or another 4xx).
- `2` Used wrongly (for example a pattern in --filter), not signed in, or the session ended.

## qits events query

Print the qits-events domain events that already happened in a window of time, oldest first, from the events log. It answers with what exists when it is called and never waits for a new event; `qits events` is the live form.

The window is --since to --until, both inclusive; --since is an hour ago and --until is now unless given. When more than --limit events fall in it, the newest --limit are kept. The text form starts with a line giving the window as two absolute instants, then prints one JSON object per event, the same line `qits events` prints, and ends with a line saying so when the answer was cut. -o json prints one object: {"events": [...], "truncated": true|false, "window": {"since": ..., "until": ...}}.

```
qits events query [--events-url <url>] [--filter <names>] [--limit <n>] [--output <text|json>] [--since <time>] [--until <time>]
```

| Name | What it does |
|---|---|
| `--events-url <url>` | The events service's base URL, without /events. Default: QITS_EVENTS_URL, else the session's idp address with `idp` swapped for `events`. |
| `--filter <names>` | Which events: exact event names, comma-separated (for example BuildSuccessful,BuildFailed), or * for every event. No patterns. Default: *. |
| `--limit <n>` | At most this many, 1 to 1000. When more match, the newest are kept. Default: 100. |
| `-o, --output <text\|json>` | text: the window, then one line per event (the default). json: one object with the events, whether the answer was cut, and the window. |
| `--since <time>` | Where the window starts, inclusive: an ISO-8601 instant (2026-10-01T18:00:00Z) or a time back from now, a whole number with s, m, h or d (90s, 15m, 2h, 7d). Default: 1h. |
| `--until <time>` | Where the window ends, inclusive, in the same two forms. A time after now is taken as now. Default: now. |

### Examples

```
qits events query --filter=BuildFailed --since 1d
qits events query --filter=ReleaseRequestChanged --since 2026-10-01T18:00:00Z --until 2026-10-01T19:00:00Z
qits events query --since 15m -o json | jq -r '.events[].name'
```

--filter takes exact event names, as `qits events` does. A pattern such as Build* is refused.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly (a pattern in --filter, a time that cannot be read, --since after --until, a --limit outside 1..1000), not signed in, or the session ended.

## qits observe

Print what qits-observability takes in (logs, spans with their events, metrics) as it arrives. Use it to watch a service's errors or to follow one trace live. The service applies the filters and sends only the records that match.

Each --filter is one group of conditions, separated by spaces, that must all hold. A record that fits any group is printed. Live only: records that arrive while it reconnects are missed. Notes go to stderr. Stops on SIGINT or SIGTERM.

Conditions: F=V equal and F^=V starts with (both match case: status=ERROR, not status=error), F~V contains (any case), F? present, !F absent, level>=V severity at or above V (TRACE, DEBUG, INFO, WARN, ERROR, FATAL or 1-24).

Fields: kind service trace span level body name status event attr.<key> resource.<key>. attr.<key> is the record's own attribute, resource.<key> its resource's. Quote a value that holds spaces: body~"connection refused".

Subcommands read what the buffer holds now and do not wait: query searches it with these filters, sources lists its buckets, and errors, slow-spans and metrics sum up one bucket.

```
qits observe [--filter <conditions>...] [--observability-url <url>] [--output <text|json>]
```

| Name | What it does |
|---|---|
| `--filter <conditions>...` | Required. One group of conditions, for example 'kind=log level>=ERROR'. Give it again for another group. '*' streams every record. |
| `--observability-url <url>` | The observability service's base URL, without /observability. Default: QITS_OBSERVABILITY_URL, else the session's idp address with `idp` swapped for `observability`. |
| `-o, --output <text\|json>` | text: one line per record (the default). json: each record's frame as the service sends it, one per line. |

### Examples

```
qits observe --filter 'kind=log level>=ERROR' --filter 'kind=span event=exception'
qits observe --filter 'service^=qits-ci kind=log body~"connection refused"'
qits observe --filter 'trace=4bf92f3577b34da6a3ce929d0e0e4736'
qits observe --filter 'kind=log level>=WARN' -o json | jq -r .record.body
qits observe --filter 'service=qits-ci-daemon'
qits observe --filter 'service=qits-ci-daemon resource.qits.ci.run_id=5f2c0a9e-1b7d-4c2e-9a41-3d8e6f0b2c17'
```

- A span's exception is an event of the span: event=exception finds it. attr.exception.type? matches logs only.
- F=V and F^=V match case: status=ERROR, not status=error. F~V ignores case.
- level takes >= only (level>=WARN), and only logs have a level.
- --filter '*' streams every record, which can be a lot.
- The stream and the subcommands take qits:admin, qits:admin-agent, qits:desk-admin or qits:agent.

### Exit codes

- `0` Stopped by SIGINT or SIGTERM, or stdout was closed.
- `1` The platform refused the socket (401, 403 or another 4xx).
- `2` A --filter cannot be read or the service refused the filters, not signed in, or the session ended.

## qits observe query

Print the logs, spans and metrics qits-observability still holds for a window of time, oldest first, filtered as `qits observe` filters. It answers with what the service holds when it is called and never waits for a new record; `qits observe` is the live form.

The window is --since to --until, both inclusive; --since is an hour ago and --until is now unless given. A record is in the window by its own time: a log's time, a span's start. A metric keeps only its latest point, so a metric is found only when that point is in the window. When more than --limit records match, the newest --limit are kept.

The service keeps a bounded buffer, not a history. When the window starts before the oldest record it still holds, a note on stderr says so: an empty answer there is not proof that nothing happened.

The text form starts with a line giving the window as two absolute instants, then prints one line per record, the line `qits observe` prints, and ends with a line saying so when the answer was cut. -o json prints one object: {"records": [...], "truncated": true|false, "window": {"since": ..., "until": ...}}, each record the frame the live stream sends.

--filter takes the conditions and fields `qits observe` takes; see `qits observe --help`.

```
qits observe query --filter <conditions>... [--limit <n>] [--observability-url <url>] [--output <text|json>] [--since <time>] [--source <key>] [--until <time>]
```

| Name | What it does |
|---|---|
| `--filter <conditions>...` | Required. One group of conditions, for example 'kind=log level>=ERROR'. Give it again for another group. '*' matches every record. |
| `--limit <n>` | At most this many, 1 to 1000. When more match, the newest are kept. Default: 100. |
| `--observability-url <url>` | The observability service's base URL, without /observability. Default: QITS_OBSERVABILITY_URL, else the session's idp address with `idp` swapped for `observability`. |
| `-o, --output <text\|json>` | text: the window, then one line per record (the default). json: one object with the records, whether the answer was cut, and the window. |
| `--since <time>` | Where the window starts, inclusive: an ISO-8601 instant (2026-10-01T18:00:00Z) or a time back from now, a whole number with s, m, h or d (90s, 15m, 2h, 7d). Default: 1h. |
| `--source <key>` | Only this source's records: a key such as _service/qits-ci. Default: every source. |
| `--until <time>` | Where the window ends, inclusive, in the same two forms. A time after now is taken as now. Default: now. |

### Examples

```
qits observe query --filter 'kind=log level>=ERROR' --since 2h
qits observe query --filter 'trace=4bf92f3577b34da6a3ce929d0e0e4736' --since 1d --limit 1000
qits observe query --filter 'kind=span status=ERROR' --source _service/qits-ci -o json
```

- --source takes a key as the service's telemetry sources list it (_service/<name>, a repository or a workspace). Without it every source is searched.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly (a --filter that cannot be read or that the service refused, a time that cannot be read, --since after --until, a --limit outside 1..1000), not signed in, or the session ended.

## qits observe sources

List the buckets qits-observability holds telemetry in: each one's key, kind (SERVICE, WORKSPACE or UNSCOPED), label, repository and workspace ids, and how many spans, logs and metric series it holds.

Reads GET /observability/api/telemetry/sources. The key is what --source takes; pass it back as it is.

```
qits observe sources [--observability-url <url>] [--output <text|json>]
```

| Name | What it does |
|---|---|
| `--observability-url <url>` | The observability service's base URL, without /observability. Default: QITS_OBSERVABILITY_URL, else the session's idp address with `idp` swapped for `observability`. |
| `-o, --output <text\|json>` | text: aligned columns (the default). json: the service's answer. |

### Examples

```
qits observe sources
qits observe sources -o json
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits observe errors

Recent errors in one bucket, grouped by trace: spans with an error status, their exception events with stack traces, and ERROR logs. Start here when a failure needs explaining; follow a trace with `qits observe query --filter 'trace=<id>'`.

Reads GET /observability/api/telemetry/errors. The table shows each trace, its service, how many error spans and logs it has, and the first message; -o json has every span, event and log in full.

```
qits observe errors [--limit <n>] [--observability-url <url>] [--output <text|json>] [--repository-id <id>] [--service <name>] [--since-minutes <n>] [--source <key>] [--workspace-id <id>]
```

| Name | What it does |
|---|---|
| `--limit <n>` | At most n traces, 1 to 1000. Default: 200. |
| `--observability-url <url>` | The observability service's base URL, without /observability. Default: QITS_OBSERVABILITY_URL, else the session's idp address with `idp` swapped for `observability`. |
| `-o, --output <text\|json>` | text: aligned columns (the default). json: the service's answer. |
| `--repository-id <id>` | With --workspace-id, the bucket of one workspace: its repository's id. |
| `--service <name>` | Only records of this service.name. |
| `--since-minutes <n>` | Only errors received in the last n minutes. Default: everything buffered. |
| `--source <key>` | The bucket: a key as `qits observe sources` lists it, such as _service/qits-ci. Wins over --repository-id and --workspace-id. |
| `--workspace-id <id>` | With --repository-id, the bucket of one workspace: its id. |

### Examples

```
qits observe errors --source _service/qits-ci --since-minutes 30
qits observe errors --repository-id <repo> --workspace-id <workspace> -o json
```

### Exit codes

- `0` Done.
- `1` The platform refused (for example a --limit above 1000, HTTP 400), or cannot be reached.
- `2` Used wrongly (for example no bucket named), not signed in, or the session ended.

## qits observe slow-spans

Spans in one bucket that took at least --threshold-ms, slowest first: duration, service, span name, status and trace.

Reads GET /observability/api/telemetry/slow-spans. -o json has each span's attributes and events too.

```
qits observe slow-spans [--limit <n>] [--observability-url <url>] [--output <text|json>] [--repository-id <id>] [--service <name>] [--since-minutes <n>] [--source <key>] [--threshold-ms <ms>] [--workspace-id <id>]
```

| Name | What it does |
|---|---|
| `--limit <n>` | At most n spans, 1 to 1000. Default: 200. |
| `--observability-url <url>` | The observability service's base URL, without /observability. Default: QITS_OBSERVABILITY_URL, else the session's idp address with `idp` swapped for `observability`. |
| `-o, --output <text\|json>` | text: aligned columns (the default). json: the service's answer. |
| `--repository-id <id>` | With --workspace-id, the bucket of one workspace: its repository's id. |
| `--service <name>` | Only records of this service.name. |
| `--since-minutes <n>` | Only spans received in the last n minutes. Default: everything buffered. |
| `--source <key>` | The bucket: a key as `qits observe sources` lists it, such as _service/qits-ci. Wins over --repository-id and --workspace-id. |
| `--threshold-ms <ms>` | The shortest duration to list, in milliseconds. Default: 500. |
| `--workspace-id <id>` | With --repository-id, the bucket of one workspace: its id. |

### Examples

```
qits observe slow-spans --source _service/qits-ci --threshold-ms 1000
qits observe slow-spans --repository-id <repo> --workspace-id <workspace> --since-minutes 10
```

### Exit codes

- `0` Done.
- `1` The platform refused (for example a --limit above 1000, HTTP 400), or cannot be reached.
- `2` Used wrongly (for example no bucket named), not signed in, or the session ended.

## qits observe metrics

The latest value of every metric series in one bucket: name, value, unit, service and the series' attributes. Gauges and counters alike, with no maths over time.

Reads GET /observability/api/telemetry/metrics.

```
qits observe metrics [--name <metric>] [--observability-url <url>] [--output <text|json>] [--repository-id <id>] [--service <name>] [--source <key>] [--workspace-id <id>]
```

| Name | What it does |
|---|---|
| `--name <metric>` | Only the series of this metric, by its exact name. |
| `--observability-url <url>` | The observability service's base URL, without /observability. Default: QITS_OBSERVABILITY_URL, else the session's idp address with `idp` swapped for `observability`. |
| `-o, --output <text\|json>` | text: aligned columns (the default). json: the service's answer. |
| `--repository-id <id>` | With --workspace-id, the bucket of one workspace: its repository's id. |
| `--service <name>` | Only records of this service.name. |
| `--source <key>` | The bucket: a key as `qits observe sources` lists it, such as _service/qits-ci. Wins over --repository-id and --workspace-id. |
| `--workspace-id <id>` | With --repository-id, the bucket of one workspace: its id. |

### Examples

```
qits observe metrics --source _service/qits-ci
qits observe metrics --source _service/qits-ci --name http.server.active_requests -o json
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly (for example no bucket named), not signed in, or the session ended.

## qits wait

Declare what this holder waits on: a domain event that fits a selection. The platform gives the holder a turn when one arrives, or when the timeout passes. The command returns at once; it never blocks.

Bare, it takes the event's name and its conditions. branch, release-request, work and ci-run are presets for the usual waits; list shows the holder's waits and cancel ends one.

Prefer `wait branch` for your own work: a request id goes stale when it refolds or is superseded.

Each --when is one group of conditions, separated by spaces, that must all hold. An event that fits any group matches; with no --when, every event of the name does. Conditions: path=value equal, path^=value starts with, path? present, !path absent. A path is the payload's fields joined by dots; [] after a field means some element of that array, and the conditions of one group that go through the same name[] must hold on the same element (entities[].entityId=<id> entities[].status=VERIFIED). Quote a value that holds spaces or is empty: label="two words", note="". At most 8 groups of 8 conditions.

Two --when are OR, not AND: --when 'repoName=a' --when 'status=b' fires on either; --when 'repoName=a status=b' needs both.

The holder is --agent or --desk (give one, not both), else QITS_AGENT_ID, else QITS_DESK_PROJECT: the harness sets QITS_AGENT_ID in an agent's environment and QITS_DESK_PROJECT in a project's kept front-desk session, and the MCP server sets QITS_AGENT_ID from the caller's token's agent claim, or QITS_DESK_PROJECT from its project claim when the token names no agent and is a desk's (the role qits:desk-admin, or a context_kind of desk-session or desk-delivery). An agent may declare and cancel waits for itself only; a project's desk, for its own project's only.

```
qits wait [--agent <id>] [--desk <project>] [--event <Name>] [--label <text>] [--output table|json] [--timeout <duration>] [--when <conditions>...] [--workspaces-url <url>]
```

| Name | What it does |
|---|---|
| `--agent <id>` | The agent whose waits these are. Mutually exclusive with --desk. Default: QITS_AGENT_ID (on the MCP server, from the caller's token's agent claim). |
| `--desk <project>` | The project whose desk's waits these are: its id, slug or name. Mutually exclusive with --agent. Default: QITS_DESK_PROJECT (on the MCP server, from the caller's token's project claim, when the token names no agent and is a desk's). |
| `--event <Name>` | Required, unless a preset is named. The event's name, as the bus carries it: BuildFailed, EntityTransitioned. |
| `--label <text>` | What the agent waits on, in a few words. The platform shows the agent as `waiting on <label>`. A preset gives one of its own. |
| `-o, --output table\|json` | table (the default): the wait for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--timeout <duration>` | Close the wait and wake the agent after this long, if nothing fitted: 30m, 2h, 1h30m, 1d or ISO-8601 (PT2H). Default: 8h, the platform's ceiling for an unstated timeout; the most any wait may run is 24h, and the platform refuses a longer one. |
| `--when <conditions>...` | One group of conditions, for example 'runId=<id> status=SUCCESS'. Give it again for another group, up to 8: that is OR, not AND, so an event fitting either fires the wait. Conditions that must all hold go in the one quoted --when. Without one, every event of the name fits. |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Waiting on the platform

- Declare the wait, then end your turn. You get a new turn that names what fired: the event, or the timeout.
- Never poll in a background loop (sleep, watch, `qits events`) for something a wait can say.
- While a wait is open, the platform shows the holder as waiting on its label, not as blocked.
- A wait closes when it fires, times out, is cancelled, or breaks: the platform decided it can never fire (BROKEN, with a reason). Declare a new one to wait again.
- Waits take qits:admin, qits:admin-agent, qits:desk-admin, qits:system or qits:agent. Every agent, admin-agent included, declares, lists and cancels its own waits only; qits:desk-admin acts on the agents of its own project and names one with --agent; qits:admin and qits:system act on any agent.

```
qits wait branch ticket/qits-123 --until released
qits wait release-request qits-ci-service-rr-12
qits wait work qits-123 --status VERIFIED --timeout 2h
qits wait ci-run 0d9e8f7a-6b5c-4d3e-8f2a-1b0c9d8e7f6a
qits wait --event BuildFailed --when 'repoName=qits-ci-service branch=main' --label 'main to break'
qits wait --event EntityTransitioned --when 'entities[].status=VERIFIED' --timeout 30m
qits wait list --state OPEN
qits wait cancel 7c1e2d3f-0000-4000-8000-000000000001
qits wait release-request qits-ci-service-rr-12 --desk qits-ci
```

### Exit codes

- `0` The wait is declared; it is printed.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly (no holder, both --agent and --desk, no --event, a --when or --timeout that cannot be read or is over a limit), not signed in, or the session ended.

## qits wait branch

Wait until a branch is joined, released or live, or fails, in one repository or in every repository that knows it. Prefer this to `wait release-request` for your own work: a request id goes stale when the request refolds or is superseded, the branch does not.

The same as --event BranchDeliveryChanged with branch=<branch> in every group, repository=<name> with --repository and projectId=<uuid> with --project. With --repository the target is the repository's step: joined is JOINED or MOVED, released is RELEASED or LIVE, live is LIVE. Without it the target is everywhere, the least advanced step across the branch's repositories: joined is JOINED, RELEASED or LIVE, released is RELEASED or LIVE, live is LIVE. Every target also fires early on a step FAILED, CONFLICTED or WITHDRAWN, so a broken delivery wakes you. failed waits for a step FAILED or CONFLICTED.

It reads the branch's deliveries first. When the target already holds it says so, declares nothing and exits 0; when only an early step (FAILED, CONFLICTED or WITHDRAWN) holds it says so, declares nothing and exits 1. With -o json either way it prints what it read, with declared added as false. When no repository knows the branch yet it warns and declares anyway, and so it does when the read fails.

```
qits wait branch --until joined|released|live|failed [--agent <id>] [--desk <project>] [--label <text>] [--output table|json] [--project <project>] [--projects-url <url>] [--repository <name>] [--timeout <duration>] [--workspaces-url <url>] <branch>
```

| Name | What it does |
|---|---|
| `<branch>` | The branch, by its whole name: ticket/qits-123. |
| `--until joined\|released\|live\|failed` | Required. What to wait for: joined (in a release request), released (tagged), live (deployed, or released for a library) or failed (a red fold or a conflict). |
| `--agent <id>` | The agent whose waits these are. Mutually exclusive with --desk. Default: QITS_AGENT_ID (on the MCP server, from the caller's token's agent claim). |
| `--desk <project>` | The project whose desk's waits these are: its id, slug or name. Mutually exclusive with --agent. Default: QITS_DESK_PROJECT (on the MCP server, from the caller's token's project claim, when the token names no agent and is a desk's). |
| `--label <text>` | What the agent waits on, in a few words. The platform shows the agent as `waiting on <label>`. A preset gives one of its own. |
| `-o, --output table\|json` | table (the default): the wait for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--project <project>` | Only this project's deliveries: its id, slug or name. Needed when several projects know the branch. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where the deliveries and the project are read. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--repository <name>` | Only this repository's delivery, by name. Default: every repository that knows the branch, by their least advanced step. |
| `--timeout <duration>` | Close the wait and wake the agent after this long, if nothing fitted: 30m, 2h, 1h30m, 1d or ISO-8601 (PT2H). Default: 8h, the platform's ceiling for an unstated timeout; the most any wait may run is 24h, and the platform refuses a longer one. |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits wait branch ticket/qits-123 --until released
qits wait branch ticket/qits-123 --until joined --repository qits-ci-service
qits wait branch ticket/qits-123 --until live --project qits --timeout 1d
qits wait branch ticket/qits-123 --until failed --repository qits-ci-service
```

- Give the branch's whole name, as you pushed it: the wait matches it exactly.
- --repository takes the repository's name, as the event carries it.
- A branch can carry on into a new request when its request releases without it or is superseded (MOVED): the wait does not care which request it is on.

### Exit codes

- `0` The wait is declared and printed, or the branch already reached the target; it says which.
- `1` An early step (FAILED, CONFLICTED or WITHDRAWN) already holds, so nothing was declared; or the platform refused, or cannot be reached.
- `2` Used wrongly (no holder, no branch, an --until that is not joined, released, live or failed, a project qits does not know), not signed in, or the session ended.

## qits wait release-request

Wait until a release request ends: FINALIZED, REJECTED, FAILED, WITHDRAWN or OBSOLETE.

A request already in one of these states fires the wait at once; after a rejection, wait on --event ReleaseRequestStateChanged --when 'releaseRequestQualifiedId=<id> state=PENDING', or on your branch, to see it re-armed.

The same as --event ReleaseRequestStateChanged with one group per final state: releaseRequestQualifiedId=<id> state=<STATE>, or releaseRequestId=<uuid> state=<STATE> for a uuid.

Prefer `wait branch` for your own work: a request id goes stale when it refolds or is superseded.

```
qits wait release-request [--agent <id>] [--desk <project>] [--label <text>] [--output table|json] [--timeout <duration>] [--workspaces-url <url>] <id>
```

| Name | What it does |
|---|---|
| `<id>` | The release request: its id (qits-ci-service-rr-12) or its whole uuid. |
| `--agent <id>` | The agent whose waits these are. Mutually exclusive with --desk. Default: QITS_AGENT_ID (on the MCP server, from the caller's token's agent claim). |
| `--desk <project>` | The project whose desk's waits these are: its id, slug or name. Mutually exclusive with --agent. Default: QITS_DESK_PROJECT (on the MCP server, from the caller's token's project claim, when the token names no agent and is a desk's). |
| `--label <text>` | What the agent waits on, in a few words. The platform shows the agent as `waiting on <label>`. A preset gives one of its own. |
| `-o, --output table\|json` | table (the default): the wait for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--timeout <duration>` | Close the wait and wake the agent after this long, if nothing fitted: 30m, 2h, 1h30m, 1d or ISO-8601 (PT2H). Default: 8h, the platform's ceiling for an unstated timeout; the most any wait may run is 24h, and the platform refuses a longer one. |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits wait release-request qits-ci-service-rr-12
qits wait release-request qits-ci-service-rr-12 --timeout 3h
qits wait release-request 4f2a91c0-1d2e-4c3b-9a8f-0123456789ab
```

- Give the whole id, as `qits release-request list` shows it, or the whole uuid. The wait matches it exactly, so the start of a uuid is refused, and the id must be in the case the list shows.

### Exit codes

- `0` The wait is declared; it is printed.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly (no holder, not a whole id or uuid), not signed in, or the session ended.

## qits wait work

Wait until a work item reaches a status.

The item is looked up in the projects service first, so its qualified id (qits-123) works. The same as --event EntityTransitioned --when 'entities[].entityId=<its id> entities[].status=<STATUS>'.

```
qits wait work --status <STATUS> [--agent <id>] [--desk <project>] [--label <text>] [--output table|json] [--projects-url <url>] [--timeout <duration>] [--workspaces-url <url>] <entity>
```

| Name | What it does |
|---|---|
| `<entity>` | The work item: its qualified id (qits-123) or its id. |
| `--status <STATUS>` | Required. The status to wait for: REFINED, READY_FOR_DEV, IMPLEMENTED, VERIFIED, DONE, DROPPED or another word of the item's lifecycle. |
| `--agent <id>` | The agent whose waits these are. Mutually exclusive with --desk. Default: QITS_AGENT_ID (on the MCP server, from the caller's token's agent claim). |
| `--desk <project>` | The project whose desk's waits these are: its id, slug or name. Mutually exclusive with --agent. Default: QITS_DESK_PROJECT (on the MCP server, from the caller's token's project claim, when the token names no agent and is a desk's). |
| `--label <text>` | What the agent waits on, in a few words. The platform shows the agent as `waiting on <label>`. A preset gives one of its own. |
| `-o, --output table\|json` | table (the default): the wait for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where the item is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--timeout <duration>` | Close the wait and wake the agent after this long, if nothing fitted: 30m, 2h, 1h30m, 1d or ISO-8601 (PT2H). Default: 8h, the platform's ceiling for an unstated timeout; the most any wait may run is 24h, and the platform refuses a longer one. |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits wait work qits-123 --status VERIFIED
qits wait work qits-123 --status DONE --timeout 1d --label 'qits-123 to be done'
```

### Exit codes

- `0` The wait is declared; it is printed.
- `1` The platform refused (for example an item it does not know, HTTP 404), or cannot be reached.
- `2` Used wrongly (no holder, no --status), not signed in, or the session ended.

## qits wait ci-run

Wait until a CI run ends: SUCCESS, FAILED, CANCELLED, CONFIG_ERROR or TIMED_OUT.

The same as --event BuildStatusChanged with one group per final status: runId=<id> status=<STATUS>.

```
qits wait ci-run [--agent <id>] [--desk <project>] [--label <text>] [--output table|json] [--timeout <duration>] [--workspaces-url <url>] <run id>
```

| Name | What it does |
|---|---|
| `<run id>` | The run's whole id. |
| `--agent <id>` | The agent whose waits these are. Mutually exclusive with --desk. Default: QITS_AGENT_ID (on the MCP server, from the caller's token's agent claim). |
| `--desk <project>` | The project whose desk's waits these are: its id, slug or name. Mutually exclusive with --agent. Default: QITS_DESK_PROJECT (on the MCP server, from the caller's token's project claim, when the token names no agent and is a desk's). |
| `--label <text>` | What the agent waits on, in a few words. The platform shows the agent as `waiting on <label>`. A preset gives one of its own. |
| `-o, --output table\|json` | table (the default): the wait for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--timeout <duration>` | Close the wait and wake the agent after this long, if nothing fitted: 30m, 2h, 1h30m, 1d or ISO-8601 (PT2H). Default: 8h, the platform's ceiling for an unstated timeout; the most any wait may run is 24h, and the platform refuses a longer one. |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits wait ci-run 0d9e8f7a-6b5c-4d3e-8f2a-1b0c9d8e7f6a
```

- Give the whole id: `qits ci runs ... -o json` shows it.

### Exit codes

- `0` The wait is declared; it is printed.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly (no holder, not a whole id), not signed in, or the session ended.

## qits wait list

The holder's waits: id, state, event, label, when it was declared, when it times out and, for a BROKEN wait, why.

```
qits wait list [--agent <id>] [--desk <project>] [--output table|json] [--state <STATE>] [--workspaces-url <url>]
```

| Name | What it does |
|---|---|
| `--agent <id>` | The agent whose waits these are. Mutually exclusive with --desk. Default: QITS_AGENT_ID (on the MCP server, from the caller's token's agent claim). |
| `--desk <project>` | The project whose desk's waits these are: its id, slug or name. Mutually exclusive with --agent. Default: QITS_DESK_PROJECT (on the MCP server, from the caller's token's project claim, when the token names no agent and is a desk's). |
| `-o, --output table\|json` | table (the default): the wait for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--state <STATE>` | Only the waits in this state: OPEN, MATCHED, TIMED_OUT, BROKEN or CANCELLED. Default: all. |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits wait list
qits wait list --state OPEN -o json
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly (no holder, a state qits does not know), not signed in, or the session ended.

## qits wait cancel

End one of the holder's open waits. An agent holder gets no turn for it.

```
qits wait cancel [--agent <id>] [--desk <project>] [--output table|json] [--workspaces-url <url>] <wait id>
```

| Name | What it does |
|---|---|
| `<wait id>` | The wait's id, as `qits wait list` shows it. |
| `--agent <id>` | The agent whose waits these are. Mutually exclusive with --desk. Default: QITS_AGENT_ID (on the MCP server, from the caller's token's agent claim). |
| `--desk <project>` | The project whose desk's waits these are: its id, slug or name. Mutually exclusive with --agent. Default: QITS_DESK_PROJECT (on the MCP server, from the caller's token's project claim, when the token names no agent and is a desk's). |
| `-o, --output table\|json` | table (the default): the wait for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits wait cancel 7c1e2d3f-0000-4000-8000-000000000001
```

### Exit codes

- `0` Done.
- `1` The platform refused (for example a wait it does not know, HTTP 404), or cannot be reached.
- `2` Used wrongly (no holder), not signed in, or the session ended.

## qits workspaces

The workspaces of qits-workspaces, with every action the landing app's workspace pages offer, each on the same door the page calls: the slots and their containers, and a container's sign-in and plugins (agents). The agents themselves are `qits agents`. The nodes slots are placed on are `qits targets`.

A slot is a workspace row, named by its number (`qits workspaces list` shows it). It hosts agents; an agent works on one work item. Starting a container is a technical process, whose log `qits workspaces log` follows.

### Notes

- Reads are open to every credential, qits:agent included. Writes on the JSON API need qits:admin, qits:admin-agent or qits:desk-admin (the delivery desk, within its own project); qits:agent alone is refused (HTTP 403) by design. Each command's help names its door.
- A container's sign-in and plugins (agents sign-in, agents plugins) come from the slot's daemon, through qits-workspaces' container proxy, which checks the caller: a person, qits:system, qits:admin or qits:admin-agent may reach any slot, qits:agent only its own workspace's. qits:desk-admin is judged as qits:agent there: the slot is a shell that can push.
- `qits workspaces attach` is the agent's Terminal tab: it attaches this terminal to a command's terminal on the slot's daemon. The live chat socket stays the landing app's: the CLI reads the chat's transcript and sends prompts.
- --workspaces-url, --projects-url and --output may come before or after the command.

## qits workspaces list

The ACTIVE workspace slots of one repository, named by --repository: each with its container and placement. The project's Workspaces page.

GET /workspaces/api/workspaces?repositoryId=. Any credential may read it, qits:agent included.

```
qits workspaces list --repository <repository> [--output table|json] [--project <project>] [--projects-url <url>] [--workspaces-url <url>]
```

| Name | What it does |
|---|---|
| `--repository <repository>` | Required. The wrapper repository whose slots to list: its uuid (as `qits repositories list -o json` shows it), or, with --project, its id or name. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--project <project>` | The project: its id, slug or name. Needed only when --repository names the repository rather than giving its uuid. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits workspaces list --repository 0d9e8f7a-6b5c-4d3e-8f2a-1b0c9d8e7f6a
qits workspaces list --project qits --repository qits-ci-service
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits workspaces show

One workspace slot: its container (and its error), placement and daemon, then the live agents placed in it, as `qits agents list` prints them. The status strip of a slot's page.

GET /workspaces/api/workspaces/{id}, then GET /workspaces/api/workspaces/{id}/agents. With -o json, only the first answer, as it always was; the agents' own JSON is `qits agents list --workspace <n> -o json`. A service too old to list a slot's agents (HTTP 404) leaves them out. Any credential may read it, qits:agent included.

```
qits workspaces show [--output table|json] [--projects-url <url>] [--workspaces-url <url>] <workspace>
```

| Name | What it does |
|---|---|
| `<workspace>` | The slot's number. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits workspaces show 1
qits workspaces show 1 -o json
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits workspaces active-process

The technical process starting a slot's container, if one is live. Its log is `qits workspaces log <process id>`.

GET /workspaces/api/workspaces/{id}/active-process. Any credential may read it, qits:agent included.

```
qits workspaces active-process [--output table|json] [--projects-url <url>] [--workspaces-url <url>] <workspace>
```

| Name | What it does |
|---|---|
| `<workspace>` | The slot's number. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits workspaces active-process 1
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits workspaces start

Bring a slot's container up, or join the start already running. Start on a slot's page.

POST /workspaces/api/workspaces/{id}/ensure-container, no body. It needs qits:admin, qits:admin-agent or qits:desk-admin (the delivery desk, within its own project), which a person's token or the desk's carries. qits:agent alone is refused (HTTP 403) by design: an agent keeps every read and loses the writes.

```
qits workspaces start [--output table|json] [--projects-url <url>] [--workspaces-url <url>] <workspace>
```

| Name | What it does |
|---|---|
| `<workspace>` | The slot's number. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits workspaces start 1
```

- A slot on a target that is not available is refused: HTTP 409.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits workspaces stop

Stop a slot's container. Stop on a slot's page.

POST /workspaces/api/workspaces/{id}/stop-container, no body. It needs qits:admin, qits:admin-agent or qits:desk-admin (the delivery desk, within its own project), which a person's token or the desk's carries. qits:agent alone is refused (HTTP 403) by design: an agent keeps every read and loses the writes.

```
qits workspaces stop [--output table|json] [--projects-url <url>] [--workspaces-url <url>] <workspace>
```

| Name | What it does |
|---|---|
| `<workspace>` | The slot's number. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits workspaces stop 1
```

- A slot on a target that is not available is refused: HTTP 409.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits workspaces recreate

Replace a slot's container with a new one. Recreate on a slot's page.

POST /workspaces/api/workspaces/{id}/recreate-container, no body. It needs qits:admin, qits:admin-agent or qits:desk-admin (the delivery desk, within its own project), which a person's token or the desk's carries. qits:agent alone is refused (HTTP 403) by design: an agent keeps every read and loses the writes.

```
qits workspaces recreate [--output table|json] [--projects-url <url>] [--workspaces-url <url>] <workspace>
```

| Name | What it does |
|---|---|
| `<workspace>` | The slot's number. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits workspaces recreate 1
```

- Refused (HTTP 400) unless the service can prove the working tree clean: commit or discard the changes, and have the daemon connected, first. A target that is not available is HTTP 409.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits workspaces log

Follow a technical process's log, such as a container's start, until the process ends: its segments, their lines and how each settled. The log of a slot's page.

Each connection replays what the process logged so far, then streams. With -o json, one frame per line as the service sends it; pings are left out. Stops on SIGINT or SIGTERM too.

GET /workspaces/api/technical-processes/{id}/events (Server-Sent Events). Any credential may read it, qits:agent included.

```
qits workspaces log [--output table|json] [--projects-url <url>] [--workspaces-url <url>] <process>
```

| Name | What it does |
|---|---|
| `<process>` | The technical process's id. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits workspaces log 00000000-0000-4000-8000-000000000100
```

- `qits workspaces start` and `qits workspaces active-process` name the process.

### Exit codes

- `0` The process ended, or the command was stopped.
- `1` The platform refused (for example a process it does not know, HTTP 404), or the stream dropped.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits workspaces attach

Attach this terminal to a command's terminal on a slot's daemon: what you type goes to the command, what it prints comes here. The agent's Terminal tab in the landing app.

Command ids come from `qits workspaces agents sign-in open` (the sign-in terminal), which also takes --attach, opening and attaching to it at once. An agent's own harness is `qits agents attach`, which takes the agent rather than a command.

Ctrl-] detaches and leaves the command running; Ctrl-C and every other key go to the command. The window's size goes along and follows the window. The command ending ends this too. A dropped connection attaches again after 0.5, 1, 2, 4 and 4 s, and the screen is cleared before the daemon replays up to 256 KB of what the terminal held.

With stdin not a terminal (a pipe, a script, `qits tui`) it only streams what the command prints: nothing is typed and the size is left alone. It still ends when the command does.

WS /workspaces/container/{slot}/terminal/commands/{commandId}. It goes through qits-workspaces' container proxy to the slot's daemon. A person, qits:system, qits:admin or qits:admin-agent may reach any slot; qits:agent only its own workspace's. qits:desk-admin is judged as qits:agent there, because the slot is a shell that can push.

```
qits workspaces attach --workspace <n> [--output table|json] [--projects-url <url>] [--workspaces-url <url>] <command>
```

| Name | What it does |
|---|---|
| `<command>` | The command's id. |
| `--workspace <n>` | Required. The slot whose daemon runs the command: its number, as `qits workspaces list` shows it. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits workspaces attach 00000000-0000-4000-8000-000000000100 --workspace 1
qits workspaces attach 00000000-0000-4000-8000-000000000100 --workspace 1 > terminal.log
```

- Detaching never ends the command.
- Not an MCP tool: it needs a terminal and runs until the command ends.

### Exit codes

- `0` The command ended, you detached (Ctrl-]), or it was stopped (SIGINT, SIGTERM); only the first ends the command.
- `1` The platform refused the socket, or the connection dropped and five attempts to attach again failed.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits workspaces items

The work items that have an ACTIVE workspace: the Workspace links on the work pages.

GET /workspaces/api/work/workspaces. Any credential may read it, qits:agent included.

```
qits workspaces items [--output table|json] [--projects-url <url>] [--workspaces-url <url>]
```

| Name | What it does |
|---|---|
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits workspaces items
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits workspaces history

One work item's workspaces in every state, newest first: state, branch, opened and closed. The work item page's list.

GET /workspaces/api/work/{workRef}/workspaces. Any credential may read it, qits:agent included.

```
qits workspaces history [--output table|json] [--projects-url <url>] [--workspaces-url <url>] <work>
```

| Name | What it does |
|---|---|
| `<work>` | The work item: its qualified id (qits-123) or its id. The service takes either. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits workspaces history qits-123
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits workspaces agents

The workspace slots' container: a harness's sign-in and the plugins on its shared agent home, and rewriting rough notes into a prompt. The agents themselves are `qits agents`.

The commands here take the slot with --workspace, as the page does.

### Notes

- The reads are open to every credential, qits:agent included, and so is everything that goes through the container proxy (refine, sign-in, plugins).

## qits workspaces agents refine

Rewrite rough notes into a prompt, in the slot's container: Refine in the prompt box. Prints the rewrite; nothing is sent to an agent.

POST /workspaces/container/{slot}/prompt-refinements with {transcript, preamble?}. It goes through qits-workspaces' container proxy to the slot's daemon. A person, qits:system, qits:admin or qits:admin-agent may reach any slot; qits:agent only its own workspace's. qits:desk-admin is judged as qits:agent there, because the slot is a shell that can push.

```
qits workspaces agents refine --workspace <n> [--output table|json] [--preamble <text>] [--projects-url <url>] [--workspaces-url <url>] <notes>
```

| Name | What it does |
|---|---|
| `<notes>` | The rough notes. |
| `--workspace <n>` | Required. The slot: its number, as `qits workspaces list` shows it. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--preamble <text>` | What the prompt is about, which the rewrite keeps in front. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits workspaces agents refine --workspace 1 --preamble 'Fix the login' 'uh so the login fails when the name has spaces'
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits workspaces agents sign-in

Whether anybody signed the slot's harnesses in, on the credential volume every container shares, and the sign-in terminal that does it: the notice on an agent's Terminal tab.

## qits workspaces agents sign-in status

Each harness of the slot's daemon, whether it is signed in, and the daemon's hint when it is not.

GET /workspaces/container/{slot}/agents/available. It goes through qits-workspaces' container proxy to the slot's daemon. A person, qits:system, qits:admin or qits:admin-agent may reach any slot; qits:agent only its own workspace's. qits:desk-admin is judged as qits:agent there, because the slot is a shell that can push.

```
qits workspaces agents sign-in status --workspace <n> [--output table|json] [--projects-url <url>] [--workspaces-url <url>]
```

| Name | What it does |
|---|---|
| `--workspace <n>` | Required. The slot: its number, as `qits workspaces list` shows it. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits workspaces agents sign-in status --workspace 1
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits workspaces agents sign-in open

Start a harness's sign-in in the slot's container: Open the sign-in terminal. It is an interactive terminal: --attach attaches this one to it at once, or `qits workspaces attach <command> --workspace <n>` later, with the command id this prints. The agent's Terminal tab in the landing app shows the same terminal.

POST /workspaces/container/{slot}/agents/sign-in with {agentType} for --harness, else {}; the answer's command.id is the terminal's command. It goes through qits-workspaces' container proxy to the slot's daemon. A person, qits:system, qits:admin or qits:admin-agent may reach any slot; qits:agent only its own workspace's. qits:desk-admin is judged as qits:agent there, because the slot is a shell that can push.

```
qits workspaces agents sign-in open --workspace <n> [--attach] [--harness CLAUDE|KIMI] [--output table|json] [--projects-url <url>] [--workspaces-url <url>]
```

| Name | What it does |
|---|---|
| `--workspace <n>` | Required. The slot: its number, as `qits workspaces list` shows it. |
| `--attach` | Attach to the sign-in terminal once it is open, until it ends or Ctrl-] detaches. |
| `--harness CLAUDE\|KIMI` | The harness to sign in. Default: the daemon's. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits workspaces agents sign-in open --workspace 1 --harness CLAUDE --attach
qits workspaces agents sign-in open --workspace 1 --harness CLAUDE
```

- With --attach, Ctrl-] detaches and leaves the sign-in running. --attach needs a terminal for typing, and the MCP service refuses it; without a terminal it streams, read-only.

### Exit codes

- `0` Done; with --attach, the sign-in ended or you detached.
- `1` The platform refused or cannot be reached; with --attach, the connection could not be kept.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits workspaces agents plugins

The plugins on the agent home every container of the slot's daemon shares: the plugins section of an agent's Terminal tab.

## qits workspaces agents plugins list

The plugins installed, each marketplace-qualified, and whether it is switched on.

GET /workspaces/container/{slot}/agent-plugins. It goes through qits-workspaces' container proxy to the slot's daemon. A person, qits:system, qits:admin or qits:admin-agent may reach any slot; qits:agent only its own workspace's. qits:desk-admin is judged as qits:agent there, because the slot is a shell that can push.

```
qits workspaces agents plugins list --workspace <n> [--output table|json] [--projects-url <url>] [--workspaces-url <url>]
```

| Name | What it does |
|---|---|
| `--workspace <n>` | Required. The slot: its number, as `qits workspaces list` shows it. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits workspaces agents plugins list --workspace 1
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits workspaces agents plugins install

Install a plugin on the shared agent home: Install on a plugin not installed yet. Prints the installed set as it now is.

POST /workspaces/container/{slot}/agent-plugins/{pluginId}/install with {}. It goes through qits-workspaces' container proxy to the slot's daemon. A person, qits:system, qits:admin or qits:admin-agent may reach any slot; qits:agent only its own workspace's. qits:desk-admin is judged as qits:agent there, because the slot is a shell that can push.

```
qits workspaces agents plugins install --workspace <n> [--output table|json] [--projects-url <url>] [--workspaces-url <url>] <plugin>
```

| Name | What it does |
|---|---|
| `<plugin>` | The plugin's id, as the catalogue names it. |
| `--workspace <n>` | Required. The slot: its number, as `qits workspaces list` shows it. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits workspaces agents plugins install pyright-lsp --workspace 1
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits targets

The execution targets qits-system-runner runs on, and their leases: list them with their connection, slots, login and quarantine; read one in full; declare one, or rotate its registration token, and print the line that installs its runner; change its slot reservations, slot maximums or description; lift a quarantine; ask for a health check or a login check; delete one; list the leases a target or a slot type holds, show one or end one; sign a target in; and read or handle a target's own docker containers and volumes without a shell on it.

A target is named by its name or its id (`qits targets list` shows both).

### Notes

- Reading, and asking for a health check, are open to qits:agent. Declaring, rotating a registration token, updating, deleting and asking for a login check need qits:admin, qits:admin-agent, qits:desk-admin or (for all but login-check) qits:system; qits:agent alone is refused (HTTP 403) by design. Each command's help names its door.
- Ending a lease takes qits:admin, qits:admin-agent or qits:desk-admin, which end any lease and are audited, or qits:system, which ends its own.
- `qits targets docker` needs qits:admin, qits:admin-agent or qits:desk-admin; qits:agent and qits:system are refused (HTTP 403). Every call is audited; see its own help.
- create and rotate-token print an install line that carries a one-time token: alone on stdout, shown once, and not served by the qits MCP server.
- --system-url and --output may come before or after the command.

## qits targets list

Every target, by name: connected or not, version, slots, login and quarantine. Slots show ci, workspace and desk as used over reserved (with a maximum where set), then all used over the target's slot total. A reservation is a floor, not a limit: a type may use more while shared slots are free. A maximum is a limit: a type never runs more leases than its maximum.

GET /system/api/targets. Any credential may call it, qits:agent included.

```
qits targets list [--output table|json] [--system-url <url>]
```

| Name | What it does |
|---|---|
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--system-url <url>` | qits-system-service's base URL, without /system. Default: QITS_SYSTEM_URL, else derived from the idp address, like the other services. |

### Examples

```
qits targets list
qits targets list -o json
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits targets show

One target in full: its slots, reservations and maximums, its login state for claude and kimi, its login command for each (the docker run that signs its credential volume in), its quarantine, its newest connection, and its node and kernel OOM kill counters with its last reported memory limit.

GET /system/api/targets/{id}. Any credential may call it, qits:agent included.

```
qits targets show --target <name|id> [--output table|json] [--system-url <url>]
```

| Name | What it does |
|---|---|
| `--target <name\|id>` | Required. The target's name or id. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--system-url <url>` | qits-system-service's base URL, without /system. Default: QITS_SYSTEM_URL, else derived from the idp address, like the other services. |

### Examples

```
qits targets show --target localhost
qits targets show --target localhost -o json
```

- loginCommand and kimiLoginCommand are null until a login state has arrived on the target's current connection: `qits targets sign-in` names that.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits targets create

Declare a target and print the line that installs its runner, with a one-time registration token in it.

POST /system/api/targets with {name, description?, reservations?: {ci, workspace, desk}, slots?} (HTTP 201 {target, registrationToken, installLine}). It needs qits:admin, qits:admin-agent, qits:desk-admin or qits:system, which a person's token, the delivery desk's or qits-system's own carries. qits:agent alone is refused (HTTP 403) by design.

THE INSTALL LINE IS SHOWN ONLY THIS ONCE: it is printed alone on stdout and nowhere else. Run it on the target's own machine. Lost, `qits targets rotate-token` makes a new one.

```
qits targets create --name <name> [--description <text>] [--output table|json] [--reserved-ci <n>] [--reserved-desk <n>] [--reserved-workspace <n>] [--slots <n>] [--system-url <url>]
```

| Name | What it does |
|---|---|
| `--name <name>` | Required. The new target's name. |
| `--description <text>` | Free text. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--reserved-ci <n>` | Slots reserved for CI. |
| `--reserved-desk <n>` | Slots reserved for the desk. |
| `--reserved-workspace <n>` | Slots reserved for workspaces. |
| `--slots <n>` | The slots total the install line asks the runner for; default: the reported total, or the reservations' sum. |
| `--system-url <url>` | qits-system-service's base URL, without /system. Default: QITS_SYSTEM_URL, else derived from the idp address, like the other services. |

### Examples

```
qits targets create --name buildbox
qits targets create --name buildbox --description 'The build machine under the desk' --reserved-ci 2 --reserved-workspace 2 --reserved-desk 1
```

- A reservation left out is not sent. HTTP 400: --slots is below the reservations' sum, or outside 0..1024. HTTP 409 TARGET_NAME_TAKEN: the name is in use. HTTP 503: commissioning is off, or the runner plane is not configured or pins no runner version.
- -o json prints the service's answer: the target, the registration token and the install line.
- Not served by the qits MCP server: the token would land in a transcript.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits targets rotate-token

Mint a new registration token and print the line that installs its runner with it. On a target whose runner has not registered yet, this is the first token's replacement; on a REGISTERED target, running the new line reinstalls its runner under the same target (its id is kept) and the old runner's client is decommissioned once the new one registers.

POST /system/api/targets/{id}/registration-token with an optional {slots?} (HTTP 200 {target, registrationToken, installLine}). It needs qits:admin, qits:admin-agent, qits:desk-admin or qits:system, which a person's token, the delivery desk's or qits-system's own carries. qits:agent alone is refused (HTTP 403) by design.

THE INSTALL LINE IS SHOWN ONLY THIS ONCE: it is printed alone on stdout and nowhere else. Run it on the target's own machine.

```
qits targets rotate-token --target <name|id> [--output table|json] [--slots <n>] [--system-url <url>]
```

| Name | What it does |
|---|---|
| `--target <name\|id>` | Required. The target's name or id. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--slots <n>` | The slots total the install line asks the runner for; default: the reported total, or the reservations' sum. |
| `--system-url <url>` | qits-system-service's base URL, without /system. Default: QITS_SYSTEM_URL, else derived from the idp address, like the other services. |

### Examples

```
qits targets rotate-token --target buildbox
qits targets rotate-token --target buildbox -o json
qits targets rotate-token --target buildbox --slots 8
```

- With no --slots, the request carries no body and the service picks max(reported total, the reservations' sum). HTTP 400: --slots is below the reservations' sum, or outside 0..1024. HTTP 503: commissioning is off, or the runner plane is not configured or pins no runner version.
- -o json prints the service's answer: the target, the registration token and the install line.
- Not served by the qits MCP server: the token would land in a transcript.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits targets update

Change a target's description, slot reservations or slot maximums. Only what is given is sent; a reservation or maximum left out keeps its value. A reservation is a floor, not a limit: a type may use more while shared slots are free. A maximum is the limit: a type never runs more leases than its maximum on that target. `none` clears a maximum.

PATCH /system/api/targets/{id} with {description?, reservations?: {ci?, workspace?, desk?}, maximums?: {ci?, workspace?, desk?}}. It needs qits:admin, qits:admin-agent, qits:desk-admin or qits:system, which a person's token, the delivery desk's or qits-system's own carries. qits:agent alone is refused (HTTP 403) by design.

```
qits targets update --target <name|id> [--description <text>] [--max-ci <n>|none] [--max-desk <n>|none] [--max-workspace <n>|none] [--output table|json] [--reserved-ci <n>] [--reserved-desk <n>] [--reserved-workspace <n>] [--system-url <url>]
```

| Name | What it does |
|---|---|
| `--target <name\|id>` | Required. The target's name or id. |
| `--description <text>` | Free text. |
| `--max-ci <n>\|none` | Maximum slots this target ever runs for CI leases; `none` clears it, leaving no ceiling. |
| `--max-desk <n>\|none` | Maximum slots this target ever runs for desk leases; `none` clears it, leaving no ceiling. |
| `--max-workspace <n>\|none` | Maximum slots this target ever runs for workspace leases; `none` clears it, leaving no ceiling. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--reserved-ci <n>` | Slots reserved for CI. |
| `--reserved-desk <n>` | Slots reserved for the desk. |
| `--reserved-workspace <n>` | Slots reserved for workspaces. |
| `--system-url <url>` | qits-system-service's base URL, without /system. Default: QITS_SYSTEM_URL, else derived from the idp address, like the other services. |

### Examples

```
qits targets update --target localhost --reserved-ci 2 --reserved-workspace 2 --reserved-desk 1
qits targets update --target localhost --description 'The build machine under the desk'
qits targets update --target ws-proof --max-ci 2
```

- A blank --description clears it. HTTP 409 RESERVATIONS_EXCEED_TOTAL: their sum is above the target's slots total. HTTP 409 MAXIMUM_BELOW_RESERVATION: a maximum would be below that type's reservation.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits targets greenlight

Lift a target's quarantine. The service quarantines a target itself, after a failed health check, or when it registers.

POST /system/api/targets/{id}/greenlight, no body. It needs qits:admin, qits:admin-agent or qits:desk-admin, which a person's token or the delivery desk's carries. qits:agent alone and qits:system are refused (HTTP 403) by design.

```
qits targets greenlight --target <name|id> [--output table|json] [--system-url <url>]
```

| Name | What it does |
|---|---|
| `--target <name\|id>` | Required. The target's name or id. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--system-url <url>` | qits-system-service's base URL, without /system. Default: QITS_SYSTEM_URL, else derived from the idp address, like the other services. |

### Examples

```
qits targets greenlight --target localhost
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits targets healthcheck

Ask a connected target for a health check now. The result arrives with a later `qits targets show`.

POST /system/api/targets/{id}/healthcheck, no body (HTTP 202 {requestId}). Any credential may call it, qits:agent included.

```
qits targets healthcheck --target <name|id> [--output table|json] [--system-url <url>]
```

| Name | What it does |
|---|---|
| `--target <name\|id>` | Required. The target's name or id. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--system-url <url>` | qits-system-service's base URL, without /system. Default: QITS_SYSTEM_URL, else derived from the idp address, like the other services. |

### Examples

```
qits targets healthcheck --target localhost
```

- A disconnected target is refused: HTTP 409 TARGET_UNAVAILABLE.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits targets login-check

Ask a connected target to probe its credential volume's sign-in again. The result shows in a later `qits targets show` or `qits targets list`.

POST /system/api/targets/{id}/login-check, no body (HTTP 202). It needs qits:admin, qits:admin-agent or qits:desk-admin, which a person's token or the delivery desk's carries. qits:agent alone and qits:system are refused (HTTP 403) by design.

```
qits targets login-check --target <name|id> [--output table|json] [--system-url <url>]
```

| Name | What it does |
|---|---|
| `--target <name\|id>` | Required. The target's name or id. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--system-url <url>` | qits-system-service's base URL, without /system. Default: QITS_SYSTEM_URL, else derived from the idp address, like the other services. |

### Examples

```
qits targets login-check --target localhost
```

- A disconnected target is refused: HTTP 409 TARGET_UNAVAILABLE.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits targets delete

Decommission a target: retire its runner and give its credentials back.

DELETE /system/api/targets/{id}. It needs qits:admin, qits:admin-agent, qits:desk-admin or qits:system, which a person's token, the delivery desk's or qits-system's own carries. qits:agent alone is refused (HTTP 403) by design.

```
qits targets delete --target <name|id> [--output table|json] [--system-url <url>]
```

| Name | What it does |
|---|---|
| `--target <name\|id>` | Required. The target's name or id. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--system-url <url>` | qits-system-service's base URL, without /system. Default: QITS_SYSTEM_URL, else derived from the idp address, like the other services. |

### Examples

```
qits targets delete --target localhost
```

- A target that still holds a lease that has not ended is refused: HTTP 409 TARGET_BUSY.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits targets leases

Leases, oldest first: filter by target, slot type (ci, workspace or desk) or state. show reads one lease; end ends one.

GET /system/api/leases?targetId=&slotType=&state=. qits:admin, qits:admin-agent, qits:desk-admin and qits:agent read every lease; qits:system reads only its own.

```
qits targets leases [--output table|json] [--state <state>] [--system-url <url>] [--target <name|id>] [--type ci|workspace|desk]
```

| Name | What it does |
|---|---|
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--state <state>` | Only this state: QUEUED, GRANTED, RUNNING, EXITED, FAILED, ENDING or ENDED. |
| `--system-url <url>` | qits-system-service's base URL, without /system. Default: QITS_SYSTEM_URL, else derived from the idp address, like the other services. |
| `--target <name\|id>` | Only this target's leases. |
| `--type ci\|workspace\|desk` | Only this slot type. |

### Examples

```
qits targets leases
qits targets leases --target localhost
qits targets leases --type workspace --state RUNNING
qits targets leases show 3f2a9c1e-0000-4000-8000-000000000001
qits targets leases end 3f2a9c1e-0000-4000-8000-000000000001 --reason 'stuck in ENDING'
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits targets leases show

Show one lease: its slot type, owner, state and detail, its resolvers and whether they are stale, its target, its OOM kills and peak memory, and when it was requested, granted, started and ended. A lease the ENDING deadline or an operator ended says so in its detail.

GET /system/api/leases/{id}. qits:admin, qits:admin-agent, qits:desk-admin and qits:agent read any lease; qits:system reads only its own.

```
qits targets leases show [--output table|json] [--system-url <url>] <lease id>
```

| Name | What it does |
|---|---|
| `<lease id>` | The lease's id. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--system-url <url>` | qits-system-service's base URL, without /system. Default: QITS_SYSTEM_URL, else derived from the idp address, like the other services. |

### Examples

```
qits targets leases show 3f2a9c1e-0000-4000-8000-000000000001
qits targets leases show 3f2a9c1e-0000-4000-8000-000000000001 -o json
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits targets leases end

End a lease, then show it. A queued lease, or one with no target, is ENDED at once. A granted or running one goes ENDING: its runner removes the container and reports it gone, and then it is ENDED. A lease already ENDING that an operator ends goes ENDED at once: that is the door for a lease stuck in ENDING because its runner never reported.

The owning service (qits-ci, qits-workspaces) hears of it from the lease's change event and lets go of its run or slot.

DELETE /system/api/leases/{id}?reason=. It needs qits:admin, qits:admin-agent or qits:desk-admin, which end any lease, or qits:system, which ends only its own (HTTP 403 NOT_YOUR_LEASE). An operator's end is audited (target_audit, LEASE_END). qits:agent alone is refused (HTTP 403).

```
qits targets leases end [--output table|json] [--reason <text>] [--system-url <url>] <lease id>
```

| Name | What it does |
|---|---|
| `<lease id>` | The lease's id. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--reason <text>` | Why it is ended: recorded in the audit row, and in the detail of an ENDING lease. |
| `--system-url <url>` | qits-system-service's base URL, without /system. Default: QITS_SYSTEM_URL, else derived from the idp address, like the other services. |

### Examples

```
qits targets leases end 3f2a9c1e-0000-4000-8000-000000000001
qits targets leases end 3f2a9c1e-0000-4000-8000-000000000001 --reason 'runner gone'
```

- --reason is recorded in the audit row, and in the detail of an ENDING lease it ends.
- Ending a lease stops what runs in it: a CI run's step, or an agent's workspace container.

### Exit codes

- `0` The lease is ENDING or ENDED; it is printed.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits targets sign-in

Sign a target's credential volume in for a harness: starts a sign-in on the target (or resumes one with --sign-in), prints its URL and code as the runner reports them, pastes a claude code typed on stdin back to it, and waits for the harness to report signed in.

GET /system/api/targets/{id}; POST .../sign-ins {harness} (or POST .../sign-ins/{id}/code {code} to resume with --sign-in and --code); then GET .../sign-ins/{id} every 2s until the sign-in ends, then GET /system/api/targets/{id} every 2s until login.<harness> is PRESENT, or --timeout passes. It needs qits:admin, qits:admin-agent or qits:desk-admin, which a person's token or the delivery desk's carries. qits:agent alone and qits:system are refused (HTTP 403) by design.

Already PRESENT: prints that and exits at once, nothing is started.

```
qits targets sign-in --target <name|id> [--code <code>] [--harness claude|kimi] [--output table|json] [--sign-in <id>] [--system-url <url>] [--timeout <duration>]
```

| Name | What it does |
|---|---|
| `--target <name\|id>` | Required. The target's name or id. |
| `--code <code>` | The code the browser showed, posted to --sign-in instead of reading one from stdin. |
| `--harness claude\|kimi` | The harness to sign in. Default: claude. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--sign-in <id>` | Resume this sign-in instead of starting a new one. |
| `--system-url <url>` | qits-system-service's base URL, without /system. Default: QITS_SYSTEM_URL, else derived from the idp address, like the other services. |
| `--timeout <duration>` | How long to wait: 30m, 2h, 1h30m, 1d or ISO-8601 (PT2H). Default: 10m. |

### Examples

```
qits targets sign-in --target localhost
qits targets sign-in --target localhost --harness kimi --timeout 2h
qits targets sign-in --target localhost --sign-in <id> --code 123456
```

- A claude sign-in pauses at AWAITING_CODE and asks for the code on stdin. With none given (stdin closed or empty), it prints the command that resumes the same sign-in with --sign-in and --code, and exits 1: the sign-in itself keeps running on the target until it expires.
- --sign-in resumes a sign-in already running, whose URL or code this command already showed; --code needs --sign-in and is a usage error alone.
- FAILED, EXPIRED or CANCELLED fails naming the state and its detail. --timeout reached deletes the sign-in (best effort) and fails naming the last state.
- --output has no effect here: this command prints notices, never the service's answer.

### Exit codes

- `0` The harness reports PRESENT (already, or once the sign-in succeeds).
- `1` The platform refused or cannot be reached, the sign-in failed, expired or was cancelled, no code was given at AWAITING_CODE, or --timeout passed (the message names the last state, or the command that resumes).
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits targets docker

A target's own docker containers and volumes: list and inspect them, read a container's log (or a lease's own, by its id), run a command in one, stop or remove one, and read the target's audit trail.

The runner answers only for resources it owns (named qits-system-runner-*, or labelled qits.system.runner=<targetId>); its own process container refuses exec, stop and rm.

### Notes

- It needs qits:admin, qits:admin-agent or qits:desk-admin, which a person's token or the delivery desk's carries. qits:agent alone and qits:system are refused (HTTP 403) by design. Every call is a target_audit row (DOCKER_<op>), written before it answers, refusals and timeouts included; `qits targets docker audit` reads them back. An inspect's environment and label values, and a log's or an exec's output, whose key looks like a secret are ***; the runner redacts its own answer and the service redacts inspect and listing JSON again. The runner only touches its own target's containers and volumes.

## qits targets docker ps

The target runner's own containers (docker ps -a).

GET /system/api/targets/{id}/docker/containers. It needs qits:admin, qits:admin-agent or qits:desk-admin, which a person's token or the delivery desk's carries. qits:agent alone and qits:system are refused (HTTP 403) by design. Every call is a target_audit row (DOCKER_<op>), written before it answers, refusals and timeouts included; `qits targets docker audit` reads them back. An inspect's environment and label values, and a log's or an exec's output, whose key looks like a secret are ***; the runner redacts its own answer and the service redacts inspect and listing JSON again. The runner only touches its own target's containers and volumes.

```
qits targets docker ps --target <name|id> [--output table|json] [--system-url <url>]
```

| Name | What it does |
|---|---|
| `--target <name\|id>` | Required. The target's name or id. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--system-url <url>` | qits-system-service's base URL, without /system. Default: QITS_SYSTEM_URL, else derived from the idp address, like the other services. |

### Examples

```
qits targets docker ps --target localhost
qits targets docker ps --target localhost -o json
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits targets docker inspect

docker inspect of one of the runner's containers, secrets redacted.

GET /system/api/targets/{id}/docker/containers/{name}. It needs qits:admin, qits:admin-agent or qits:desk-admin, which a person's token or the delivery desk's carries. qits:agent alone and qits:system are refused (HTTP 403) by design. Every call is a target_audit row (DOCKER_<op>), written before it answers, refusals and timeouts included; `qits targets docker audit` reads them back. An inspect's environment and label values, and a log's or an exec's output, whose key looks like a secret are ***; the runner redacts its own answer and the service redacts inspect and listing JSON again. The runner only touches its own target's containers and volumes.

```
qits targets docker inspect --target <name|id> [--output table|json] [--system-url <url>] <name>
```

| Name | What it does |
|---|---|
| `<name>` | The container's name. |
| `--target <name\|id>` | Required. The target's name or id. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--system-url <url>` | qits-system-service's base URL, without /system. Default: QITS_SYSTEM_URL, else derived from the idp address, like the other services. |

### Examples

```
qits targets docker inspect --target localhost qits-system-runner-work-ws-1-a1b2c3d4
```

- HTTP 404: no such target, or no such container of its runner.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits targets docker logs

A container's log: either --target and its name, or --lease and the lease ends up on its own container.

GET /system/api/targets/{id}/docker/containers/{name}/logs?tail=&since=, or GET /system/api/leases/{id}/logs?tail=&since= for --lease. text/plain. It needs qits:admin, qits:admin-agent or qits:desk-admin, which a person's token or the delivery desk's carries. qits:agent alone and qits:system are refused (HTTP 403) by design. Every call is a target_audit row (DOCKER_<op>), written before it answers, refusals and timeouts included; `qits targets docker audit` reads them back. An inspect's environment and label values, and a log's or an exec's output, whose key looks like a secret are ***; the runner redacts its own answer and the service redacts inspect and listing JSON again. The runner only touches its own target's containers and volumes.

```
qits targets docker logs [--lease <id>] [--output table|json] [--since <s>] [--system-url <url>] [--tail <n>] [--target <name|id>] [<name>]
```

| Name | What it does |
|---|---|
| `<name>` | The container's name; required with --target, and not given with --lease. |
| `--lease <id>` | A lease's id: reads its own container's log, on its target. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--since <s>` | Only lines at or after this time. |
| `--system-url <url>` | qits-system-service's base URL, without /system. Default: QITS_SYSTEM_URL, else derived from the idp address, like the other services. |
| `--tail <n>` | The newest lines to read. |
| `--target <name\|id>` | The target's name or id; needs the container's name too. |

### Examples

```
qits targets docker logs --target localhost qits-system-runner-work-ws-1-a1b2c3d4
qits targets docker logs --target localhost qits-system-runner-work-ws-1-a1b2c3d4 --tail 50
qits targets docker logs --lease 3f2a9c1e-0000-4000-8000-000000000001
```

- --tail defaults to 200 and is capped at 5000 by the service; the output is capped at 1 MiB, and a notice on stderr says so when the service cut it.
- --lease reads the lease's own container, resolved by its target and the qits.system.runner.lease label; HTTP 404: no such lease, it is on no target, or its container is gone. HTTP 409 TARGET_UNAVAILABLE: its target is not connected.
- -o json has no effect here: the log is always printed as text.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits targets docker exec

docker exec in one of the runner's containers: no TTY, no stdin.

POST /system/api/targets/{id}/docker/containers/{name}/exec {argv, timeoutSeconds?}. It needs qits:admin, qits:admin-agent or qits:desk-admin, which a person's token or the delivery desk's carries. qits:agent alone and qits:system are refused (HTTP 403) by design. Every call is a target_audit row (DOCKER_<op>), written before it answers, refusals and timeouts included; `qits targets docker audit` reads them back. An inspect's environment and label values, and a log's or an exec's output, whose key looks like a secret are ***; the runner redacts its own answer and the service redacts inspect and listing JSON again. The runner only touches its own target's containers and volumes.

```
qits targets docker exec --target <name|id> [--output table|json] [--system-url <url>] [--timeout <seconds>] <name> [<argv>]
```

| Name | What it does |
|---|---|
| `<name>` | The container's name. |
| `<argv>` | The command and its arguments, after --; no shell is put in front. |
| `--target <name\|id>` | Required. The target's name or id. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--system-url <url>` | qits-system-service's base URL, without /system. Default: QITS_SYSTEM_URL, else derived from the idp address, like the other services. |
| `--timeout <seconds>` | How long it may run; default 30, at most 120. |

### Examples

```
qits targets docker exec --target localhost qits-system-runner-work-ws-1-a1b2c3d4 -- ls -la
qits targets docker exec --target localhost qits-system-runner-work-ws-1-a1b2c3d4 --timeout 60 -- cat /etc/hostname
```

- --timeout defaults to 30 and is capped at 120 by the service; stdout and stderr are capped at 1 MiB each, each with its own truncation notice on stderr.
- The exit code is the command run in the container's, printed and also this command's own exit code when it is 0..125 (a shell's reserved range above that prints but is not repeated as this command's exit, to keep it inside this CLI's own codes).
- HTTP 403 DOCKER_REFUSED: the runner's own process container.

### Exit codes

- `0-125` The command ran, with this exit code.
- `126,127` The command ran; its own exit code is printed, but not repeated here.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits targets docker stop

docker stop -t 10 of one of the runner's containers.

POST /system/api/targets/{id}/docker/containers/{name}/stop, no body. It needs qits:admin, qits:admin-agent or qits:desk-admin, which a person's token or the delivery desk's carries. qits:agent alone and qits:system are refused (HTTP 403) by design. Every call is a target_audit row (DOCKER_<op>), written before it answers, refusals and timeouts included; `qits targets docker audit` reads them back. An inspect's environment and label values, and a log's or an exec's output, whose key looks like a secret are ***; the runner redacts its own answer and the service redacts inspect and listing JSON again. The runner only touches its own target's containers and volumes.

```
qits targets docker stop --target <name|id> [--output table|json] [--system-url <url>] <name>
```

| Name | What it does |
|---|---|
| `<name>` | The container's name. |
| `--target <name\|id>` | Required. The target's name or id. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--system-url <url>` | qits-system-service's base URL, without /system. Default: QITS_SYSTEM_URL, else derived from the idp address, like the other services. |

### Examples

```
qits targets docker stop --target localhost qits-system-runner-work-ws-1-a1b2c3d4
```

- HTTP 403 DOCKER_REFUSED: the runner's own process container.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits targets docker rm

docker rm -f of one of the runner's containers.

DELETE /system/api/targets/{id}/docker/containers/{name}. It needs qits:admin, qits:admin-agent or qits:desk-admin, which a person's token or the delivery desk's carries. qits:agent alone and qits:system are refused (HTTP 403) by design. Every call is a target_audit row (DOCKER_<op>), written before it answers, refusals and timeouts included; `qits targets docker audit` reads them back. An inspect's environment and label values, and a log's or an exec's output, whose key looks like a secret are ***; the runner redacts its own answer and the service redacts inspect and listing JSON again. The runner only touches its own target's containers and volumes.

```
qits targets docker rm --target <name|id> [--output table|json] [--system-url <url>] <name>
```

| Name | What it does |
|---|---|
| `<name>` | The container's name. |
| `--target <name\|id>` | Required. The target's name or id. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--system-url <url>` | qits-system-service's base URL, without /system. Default: QITS_SYSTEM_URL, else derived from the idp address, like the other services. |

### Examples

```
qits targets docker rm --target localhost qits-system-runner-work-ws-1-a1b2c3d4
```

- HTTP 403 DOCKER_REFUSED: the runner's own process container.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits targets docker volumes

The target runner's own volumes (docker volume ls): those carrying its owner label, and those a container it owns mounts.

GET /system/api/targets/{id}/docker/volumes. It needs qits:admin, qits:admin-agent or qits:desk-admin, which a person's token or the delivery desk's carries. qits:agent alone and qits:system are refused (HTTP 403) by design. Every call is a target_audit row (DOCKER_<op>), written before it answers, refusals and timeouts included; `qits targets docker audit` reads them back. An inspect's environment and label values, and a log's or an exec's output, whose key looks like a secret are ***; the runner redacts its own answer and the service redacts inspect and listing JSON again. The runner only touches its own target's containers and volumes.

```
qits targets docker volumes --target <name|id> [--output table|json] [--system-url <url>]
```

| Name | What it does |
|---|---|
| `--target <name\|id>` | Required. The target's name or id. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--system-url <url>` | qits-system-service's base URL, without /system. Default: QITS_SYSTEM_URL, else derived from the idp address, like the other services. |

### Examples

```
qits targets docker volumes --target localhost
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits targets docker volume

docker volume inspect of one of the runner's volumes (labelled, or mounted by a container it owns), secrets redacted. No size: docker system df walks every volume.

GET /system/api/targets/{id}/docker/volumes/{name}. It needs qits:admin, qits:admin-agent or qits:desk-admin, which a person's token or the delivery desk's carries. qits:agent alone and qits:system are refused (HTTP 403) by design. Every call is a target_audit row (DOCKER_<op>), written before it answers, refusals and timeouts included; `qits targets docker audit` reads them back. An inspect's environment and label values, and a log's or an exec's output, whose key looks like a secret are ***; the runner redacts its own answer and the service redacts inspect and listing JSON again. The runner only touches its own target's containers and volumes.

```
qits targets docker volume --target <name|id> [--output table|json] [--system-url <url>] <name>
```

| Name | What it does |
|---|---|
| `<name>` | The volume's name. |
| `--target <name\|id>` | Required. The target's name or id. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--system-url <url>` | qits-system-service's base URL, without /system. Default: QITS_SYSTEM_URL, else derived from the idp address, like the other services. |

### Examples

```
qits targets docker volume --target localhost qits-system-runner-work-ws-1-a1b2c3d4
```

- HTTP 404: no such target, or no such volume of its runner.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits targets docker audit

The target's docker audit rows, newest first: every call this group made against it, refusals and timeouts included.

GET /system/api/targets/{id}/docker/audit?limit=. It needs qits:admin, qits:admin-agent or qits:desk-admin, which a person's token or the delivery desk's carries. qits:agent alone and qits:system are refused (HTTP 403) by design. Every call is a target_audit row (DOCKER_<op>), written before it answers, refusals and timeouts included; `qits targets docker audit` reads them back. An inspect's environment and label values, and a log's or an exec's output, whose key looks like a secret are ***; the runner redacts its own answer and the service redacts inspect and listing JSON again. The runner only touches its own target's containers and volumes.

```
qits targets docker audit --target <name|id> [--limit <n>] [--output table|json] [--system-url <url>]
```

| Name | What it does |
|---|---|
| `--target <name\|id>` | Required. The target's name or id. |
| `--limit <n>` | At most this many rows. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--system-url <url>` | qits-system-service's base URL, without /system. Default: QITS_SYSTEM_URL, else derived from the idp address, like the other services. |

### Examples

```
qits targets docker audit --target localhost
qits targets docker audit --target localhost --limit 20
```

- --limit defaults to 100 and is capped at 500 by the service.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits git-login

Sign this workstation in for Git pushes to the platform's git host, through the browser. Run it once; afterwards Git gets its token from `qits git-credential`.

The sign-in may push branches under refs/heads/external/ and nothing else. It is stored in $XDG_CONFIG_HOME/qits/git.json, apart from the session of `qits login`.

```
qits git-login [--configure] [--git-host <url>] [--idp-url <url>] [--no-browser] [--timeout <seconds>]
```

| Name | What it does |
|---|---|
| `--configure` | Also run the two `git config --global` commands that make Git ask `qits git-credential` for this git host (and no other). |
| `--git-host <url>` | The git host's address. Default: QITS_GIT_HOST_URL, else the idp's host with `idp` swapped for `githost` (https://githost.qits.<QITS_DOMAIN>). |
| `--idp-url <url>` | The idp's public base URL. Default: QITS_IDP_URL, else the idp of the `qits login` session, else https://idp.qits.<QITS_DOMAIN>/idp; with none of them, the command refuses. |
| `--no-browser` | Only print the sign-in address; do not start a browser. |
| `--timeout <seconds>` | How long to wait for the browser to come back. Default: 300. |

### Examples

```
qits git-login --configure
git push <remote> HEAD:refs/heads/external/log-view
```

- The git host refuses a push to any other ref, main included. A push releases nothing: ask for a release with `qits release-request`.
- --configure sets the credential helper for the git host only; a global helper (for example for GitHub) stays.

### Exit codes

- `0` Signed in.
- `1` The sign-in did not complete (the browser did not come back in time, or the idp refused).
- `2` Used wrongly, or the git host cannot be worked out.

## qits git-credential

Git's credential helper for the platform's git host. Git runs it; a person does not.

On a workstation `qits git-login` sets it up: get prints that sign-in's access token, refreshing it first when needed. store is ignored. erase drops the cached access token and keeps the sign-in.

With a workspace token (QITS_TOKEN) get answers https://githost.qits.<QITS_DOMAIN> with that token, and answers no other host; store and erase do nothing and no file is written.

With QITS_PLATFORM set and no QITS_TOKEN it answers nothing: every action prints nothing, writes no file and exits 0, so Git asks its other helpers.

```
qits git-credential <get|store|erase>
```

| Name | What it does |
|---|---|
| `<get\|store\|erase>` | What Git asks for. |

### Examples

```
git config --global --get-all credential.https://githost.qits.<QITS_DOMAIN>.helper
```

The example shows the workstation setup. Do not run `get` yourself: it prints a token on stdout, because that is Git's helper protocol.

### Exit codes

- `0` Done. For a host without a sign-in, and with a workspace token for any host but the public git host, it prints nothing and Git asks its other helpers. A credential that cannot be had says why on stderr and still exits 0, so Git carries on.
- `1` The sign-in file cannot be read or written.
- `2` No action named.

## qits registry-login

Give npm, Maven and Docker on this workstation a login for the platform's registries: the access token of `qits login`, in a block of ~/.npmrc and two of ~/.m2/settings.xml that the CLI owns, and for Docker a credential helper that hands it the same token.

--configure sets it up: it asks the platform which npm registries there are, writes the blocks, and records the setup in $XDG_CONFIG_HOME/qits/registries.json. From then on `qits session-daemon` writes each new token into the blocks, and refreshes at least 5 minutes before expiry. Without --configure it writes the current token once.

Only the blocks change; the rest of each file stays as it is. Each file is replaced atomically with mode 0600. The Maven blocks are the mirror `qits-maven-host` for the repository `qits-maven`, and servers for both ids with an `Authorization: Bearer` header.

For Docker, --configure sets credHelpers["registry.qits.<domain>"] to "qits" in ~/.docker/config.json (or $DOCKER_CONFIG/config.json), keeping every other entry, and writes docker-credential-qits beside the qits binary: a two-line script that runs `qits docker-credential`. Docker runs it for each pull and push, so no token is stored for Docker and nothing needs renewing.

--install-daemon also installs `qits session-daemon` as a systemd user service, so the npm and Maven token keeps being renewed. Without a running daemon the command warns when that token stops working, and says how to start one.

```
qits registry-login [--artifacts-url <url>] [--configure] [--docker-config <file>] [--dry-run] [--install-daemon] [--maven-settings <file>] [--mirror-url <url>] [--npmrc <file>] [--remove-stale]
```

| Name | What it does |
|---|---|
| `--artifacts-url <url>` | With --configure: qits-artifacts. Default: QITS_ARTIFACTS_URL, else https://registry.qits.<domain of the session's idp>. |
| `--configure` | Set up the blocks (again), with the registries the platform lists now. |
| `--docker-config <file>` | With --configure: the Docker client config. Default: $DOCKER_CONFIG/config.json, else ~/.docker/config.json. |
| `--dry-run` | Write nothing; print the changes as a redacted unified diff. |
| `--install-daemon` | With --configure: install and start `qits session-daemon` as a systemd user service, unless its unit file exists. |
| `--maven-settings <file>` | With --configure: the Maven user settings. Default: ~/.m2/settings.xml. |
| `--mirror-url <url>` | With --configure: qits-mirror. Default: QITS_MIRROR_URL, else https://mirror.qits.<domain of the session's idp>. |
| `--npmrc <file>` | With --configure: the npm user config. Default: NPM_CONFIG_USERCONFIG, else ~/.npmrc. |
| `--remove-stale` | Also drop *.dev.localhost registries and duplicates of the blocks' entries, and with --configure the Docker config's auths logins for those and the platform's registry. |

### Examples

```
qits registry-login --configure --remove-stale --dry-run
qits registry-login --configure --remove-stale
qits registry-login
qits registry-login --configure --install-daemon --dry-run
```

- --dry-run writes nothing. It prints a unified diff per file, or "No changes". Every secret shows as <token>, and lines outside the blocks show only when they change.
- --remove-stale also drops lines for the old *.dev.localhost registries, and entries outside the blocks that use the same registries or ids. In the Docker config, read only with --configure, it drops the auths logins for those registries and for the platform's. Without it they stay, with a warning.
- --install-daemon writes $XDG_CONFIG_HOME/systemd/user/qits-session-daemon.service (else under ~/.config) with the qits binary's path, then runs `systemctl --user daemon-reload` and `systemctl --user enable --now qits-session-daemon`. An existing unit file is left as it is. Without a systemd user manager it writes nothing and says how to start the daemon by hand.
- A workstation command: inside the platform, or with QITS_TOKEN, the container has its own registry setup.
- To stop: delete $XDG_CONFIG_HOME/qits/registries.json and the blocks, and the qits entry of the Docker config's credHelpers.

### Exit codes

- `0` Done, or nothing to change.
- `1` A file cannot be read or written, the session cannot be refreshed, or systemctl failed.
- `2` Used wrongly, not signed in, not set up, or not on a workstation.

## qits docker-credential

Docker's credential helper for the platform's registry. Docker runs it, through the docker-credential-qits script that `qits registry-login --configure` writes; a person does not.

get reads the registry's address on stdin and, for https://registry.qits.<domain of the session's idp> (or QITS_ARTIFACTS_URL), prints the access token of `qits login` as the password of `oauth2`, refreshing the session first when it has less than 5 minutes left. store and erase do nothing: the token comes from the session. list prints {}.

For any other registry, without a session, and with QITS_TOKEN or QITS_PLATFORM, get prints `credentials not found in native keychain` and exits 1, which Docker reads as no login, so it pulls without one.

```
qits docker-credential <get|store|erase|list>
```

| Name | What it does |
|---|---|
| `<get\|store\|erase\|list>` | What Docker asks for. |

### Examples

```
qits registry-login --configure
printf 'registry.qits.<domain>' | qits docker-credential get
```

Do not run `get` yourself: it prints a token on stdout, because that is Docker's helper protocol.

### Exit codes

- `0` Done. get printed the login; store, erase and list always end here.
- `1` get has no login for that registry. A session that cannot be refreshed says why on stderr.
- `2` No action named, or one Docker does not have.

## qits network-access

Reach the platform's private apps from this workstation: `up` runs the network-access companion in Docker, a WireGuard tunnel to the private edge with a proxy on 127.0.0.1:47813, and from then on every qits command, and a browser too, dials the platform's hosts through it.

The companion's state is the Docker volume qits-network-access-companion (its private key never leaves it) and $XDG_CONFIG_HOME/qits/access.json (the peer and the proxy).

A browser reaches the same tunnel through the companion's proxy auto-config file: set its proxy auto-config URL to http://127.0.0.1:47813/proxy.pac.

### Notes

- A workstation command, signed in as a person: with QITS_TOKEN or inside the platform it refuses, because host containers reach the private edge over the overlay.
- Registering a device takes qits:admin.
- QITS_NETWORK_ACCESS_PROXY=<url> names the companion's address instead of access.json's, and QITS_NETWORK_ACCESS_PROXY=off sends every call direct.
- Only https and wss calls to hosts under the platform's domain go through the companion; loopback and every QITS_URL_* address go direct. When the companion does not answer, calls go direct with one warning.

## qits network-access up

Run the network-access companion and send this workstation's platform calls, and a browser's, through it. Pulls the image the platform names, makes the companion's key, registers it as a device of yours (once: a later `up` reuses it), hands it its tunnel and starts it, published on 127.0.0.1:47813 only.

Running it again is how the companion is upgraded to the image the platform names now.

qits commands dial the companion's CONNECT proxy directly; a browser instead needs its proxy auto-config URL set to http://127.0.0.1:47813/proxy.pac, which this command prints once the companion is up.

```
qits network-access up [--name <label>] [--network-access-url <url>]
```

| Name | What it does |
|---|---|
| `--name <label>` | The device's name, a DNS label. Default: this machine's short host name. |
| `--network-access-url <url>` | The network-access service's base URL, without /network-access. Default: QITS_NETWORK_ACCESS_URL, else the session's idp address with `idp` swapped for `network-access`. |

### Examples

```
qits network-access up
qits network-access up --name laptop
```

- Needs Docker. Docker gets its login for the platform's registry from `qits registry-login --configure`, which points it at `qits docker-credential`.
- Registering the device takes qits:admin.

### Exit codes

- `0` The companion is up and healthy.
- `1` The platform refused, docker failed, or the companion did not become healthy in 20 s.
- `2` Used wrongly, not signed in, or not on a workstation.
- `3` Docker is missing or not running, or the registry refused the pull: run `qits registry-login --configure`.

## qits network-access down

Stop the network-access companion: platform calls go direct again. The device and the companion's key stay, so the next `up` reuses them.

--revoke also revokes the device at the platform and deletes the key's volume and access.json.

```
qits network-access down [--network-access-url <url>] [--revoke]
```

| Name | What it does |
|---|---|
| `--network-access-url <url>` | The network-access service's base URL, without /network-access. Default: QITS_NETWORK_ACCESS_URL, else the session's idp address with `idp` swapped for `network-access`. |
| `--revoke` | Also revoke the device and delete its key. |

### Examples

```
qits network-access down
qits network-access down --revoke
```

- Revoking takes qits:admin.

### Exit codes

- `0` Done.
- `1` The platform refused the revoke, or docker or a file failed.
- `2` Used wrongly, not signed in, or not on a workstation.
- `3` Docker is missing or not running.

## qits network-access status

Show the device from access.json, the companion container's state and its health check.

```
qits network-access status [--network-access-url <url>]
```

| Name | What it does |
|---|---|
| `--network-access-url <url>` | The network-access service's base URL, without /network-access. Default: QITS_NETWORK_ACCESS_URL, else the session's idp address with `idp` swapped for `network-access`. |

### Examples

```
qits network-access status
```

### Exit codes

- `0` The companion is up and healthy.
- `1` It is down, not set up, or not healthy.
- `2` Used wrongly, or not on a workstation.

## qits artifacts

The platform's artifacts store. `qits artifacts publish` is a CI release step's publish client: a maven module, an npm package, a contract package, an sbom, a docs bundle or a daemon binary. `qits artifacts fetch` writes published artifacts to a local directory: the consumer pacts a provider's deployed consumers were built against.

## qits artifacts publish

Publish to qits-artifacts from a CI release step: a maven module or an npm package (built, hashed and uploaded here, optionally only if its content changed), a contract package, an sbom, a docs bundle, a release's changelog, or a daemon binary. This is the qits-publish client.

Every publish follows one rule, for every surface: absent, PUT it and say what landed; occupied with the same bytes, say so and succeed (a retried or replayed step must go green); occupied with different bytes, fail naming both digests (a coordinate must never come to mean two things); occupied and not comparable, warn and skip.

### Notes

- This command never signs in and never reads or writes what `qits login` keeps: it runs in a CI step container with no person. `qits login` and `qits git-login` do not apply to it.
- Only a CI run may publish to qits-artifacts, so every request carries a bearer. The token comes from the first of these the environment has: QITS_PUBLISH_TOKEN_COMMAND (an executable that prints a fresh token, re-run for every request), else QITS_PUBLISH_TOKEN (a token). With neither of them the request still goes out, unauthenticated, and the store answers 401: the store decides who may write, not this client.
- Started under the name `qits-publish` (its own file, or a symlink to `qits`), any command runs exactly as `qits artifacts publish <command>`: `qits-publish sbom submit ...` behaves as `qits artifacts publish sbom submit ...`. A hand-written pipeline may still call it that way.
- The store is https://registry.qits.$QITS_DOMAIN and the Maven Central cache https://mirror.qits.$QITS_DOMAIN; without QITS_DOMAIN a command refuses (exit 2). No other variable names an address: the hosted npm and maven repositories, the docs, sbom and daemon stores are fixed paths under the store's host.

### Exit codes

- `0` Published, or already published with the same bytes.
- `1` Refused, and re-running will not help: invalid arguments, a 4xx, or the coordinate already holds different bytes.
- `2` Could not ask, or could not be answered: the store unreachable, an I/O failure, or a 5xx. A step may retry a 2 and must not retry a 1.

## qits artifacts publish maven

Publish one maven module of the reactor at the working directory: its jar and a flattened pom, with the content hash. Prints exactly one line on stdout: `published <version>` or `unchanged since <version>`; the reasoning goes to stderr.

The pom uploaded is generated from the module's effective model: no parent, every version resolved, and only external dependencies. Every dependency on another module of the same reactor is bundled into the jar (classes, resources, META-INF/services merged; any other path present twice with different bytes is refused), unless --link names it: a linked sibling stays a pom dependency at the version decided for it in this release. A pom-packaging module is a product (a parent or BOM another repository consumes) and keeps its dependencyManagement.

With --if-changed the module is uploaded only when its content hash differs from the newest published version's. No published version, no stored hash, or another algorithm version all count as changed. A re-run at a version already published answers `published <version>` without uploading.

With --dry-run (needs --if-changed) nothing is uploaded: the line is `changed` where an upload would happen, else as above. qits-ci asks every member of a link: group this way first, so the group publishes together or not at all.

```
qits artifacts publish maven [--dry-run] [--if-changed] [--include <glob>...] [--link <groupId:artifactId>...] [--name <groupId:artifactId>...] [--path <dir>...] [--root <dir>...] [--sbom <file>...] [--version <version>...]
```

| Name | What it does |
|---|---|
| `--dry-run` | Decide as --if-changed and upload nothing: print `changed` or `unchanged since <version>`. |
| `--if-changed` | Upload only when the content differs from the newest published version. |
| `--include <glob>...` | Hash only the jar entries matching one of these globs. Narrows the hash, never what is uploaded. Repeatable. |
| `--link <groupId:artifactId>...` | A reactor sibling that stays a pom dependency instead of being bundled. Repeatable. |
| `--name <groupId:artifactId>...` | The coordinate the entry declares. |
| `--path <dir>...` | The module's directory, relative to the reactor root. Default: `.`. |
| `--root <dir>...` | The reactor root, whose pom.xml lists the modules. Default: the working directory. |
| `--sbom <file>...` | The module's CycloneDX document, hashed with the content. Required with --if-changed. |
| `--version <version>...` | The release version. |

### Examples

```
qits artifacts publish maven --name eu.wohlben.qits:qits-registries-npm --path npm --sbom npm/target/sbom.json --link eu.wohlben.qits:qits-blobstore --if-changed --version 2026.1002.1
```

### Exit codes

- `0` Published, already published at this version with the same content, or unchanged.
- `1` Refused: bad arguments, a module that is not --name at --version, a bundling conflict, a 4xx, or this version already holds other content.
- `2` Could not ask: the store unreachable, an I/O failure, a 5xx, or an unreadable answer; never read as unchanged.

## qits artifacts publish npm

Publish one npm package from its built directory: the tarball is packed here (deterministic: sorted entries, fixed mode, owner and mtime) and PUT with the registry's publish document. Prints exactly one line on stdout: `published <version>` or `unchanged since <version>`; the reasoning goes to stderr.

package.json in --path must name --name at --version and must not be private. Every regular file is packed except node_modules/, .git/, .npmrc and the lockfiles, narrowed by "files" when the manifest has it (package.json, README* and LICENSE* always go in), else by a root .npmignore (ng-packagr writes one): its patterns drop files, package.json, README* and LICENSE* at the root still go in, and the .npmignore itself is never packed. With "files" the .npmignore is not read. A negation (!pattern) or a .npmignore below the root is refused.

A version below the registry's latest is a replay: it is published under the tag `replay` and leaves the real tags alone. Otherwise the `main` dist-tag is moved onto the version after the publish, on a re-run too.

With --if-changed the package is uploaded only when its content hash differs from the newest published version's (by version order, never the latest tag). The manifest's version field is not part of the hash.

```
qits artifacts publish npm [--if-changed] [--include <glob>...] [--name <package>...] [--path <dir>...] [--sbom <file>...] [--version <version>...]
```

| Name | What it does |
|---|---|
| `--if-changed` | Upload only when the content differs from the newest published version. |
| `--include <glob>...` | Hash only the files matching one of these globs (paths inside the package). Narrows the hash, never what is uploaded. Repeatable. |
| `--name <package>...` | The npm package name. |
| `--path <dir>...` | The built package's directory, holding its package.json. Default: `.`. |
| `--sbom <file>...` | The package's CycloneDX document, hashed with the content. Required with --if-changed. |
| `--version <version>...` | The release version. |

### Examples

```
qits artifacts publish npm --name @qits/ui-components --path dist/qits-spa-ui-components --sbom sbom.json --if-changed --version 2026.1002.1
```

### Exit codes

- `0` Published, already published at this version with the same content, or unchanged.
- `1` Refused: bad arguments, a package.json that is not --name at --version, an unsupported .npmignore, a 4xx, or this version already holds other content.
- `2` Could not ask: the registry unreachable, an I/O failure, a 5xx, or an unreadable answer; never read as unchanged.

## qits artifacts publish npm plan

Retired by `qits artifacts publish npm`, which decides, builds and uploads in one call; kept until the build-only npm-library archetype is live everywhere.

Decide, in one word on stdout, what to do with package@version: publish (the ordinary case), skip (this exact version is already there; versions are immutable), or publish-replay (this version is below the registry's latest, so it must take a throwaway tag rather than move latest backwards).

Only the word goes to stdout; the reasoning goes to stderr, so `plan=$(qits artifacts publish npm plan ...)` captures just the word.

```
qits artifacts publish npm plan [--package <name>...] [--version <version>...]
```

| Name | What it does |
|---|---|
| `--package <name>...` | The npm package name. |
| `--version <version>...` | The version. |

### Examples

```
plan=$(qits artifacts publish npm plan --package @qits/ui-components --version 2026.906.1)
```

### Exit codes

- `0` Decided (the word is on stdout).
- `1` Refused: bad arguments, or a 4xx.
- `2` Could not ask: the registry unreachable, an I/O failure, or a 5xx; never read as "publish".

## qits artifacts publish npm dist-tag

Point a dist-tag at a version. Always run, never guarded behind whether this run's publish happened: moving a tag onto the version it already names costs one request and succeeds.

```
qits artifacts publish npm dist-tag [--package <name>...] [--tag <tag>...] [--version <version>...]
```

| Name | What it does |
|---|---|
| `--package <name>...` | The npm package name. |
| `--tag <tag>...` | The dist-tag to move. |
| `--version <version>...` | The version. |

### Examples

```
qits artifacts publish npm dist-tag --package @qits/ui-components --version 2026.906.1 --tag main
```

### Exit codes

- `0` The tag now names that version.
- `1` Refused: bad arguments, or a 4xx (for example a backwards move of latest).
- `2` Could not ask: the registry unreachable, an I/O failure, or a 5xx.

## qits artifacts publish contract

Pack a provider's golden masters or a consumer's pacts from a directory into one package (a maven jar or an npm tarball), and publish it if its content changed. Prints exactly one line on stdout: `published <version>` or `unchanged since <version>`.

The packages are built deterministically: the same tree gives the same bytes. Inside the package the files sit under golden-masters/ or pacts/, by --kind, whatever --from is called; the jar carries directory entries, because a class-path pact loader asks for the directory. Every contract package is if-changed, and decides on its own: packages built from the same tree agree without any link between them.

Golden masters pack the whole --from tree. Pacts pack only the top-level files of --from named <consumer>_<provider>.json for the --provider given (both repository names), so one flat pacts/ directory serves every provider.

--name is the coordinate qits-ci derived from the contracts: section; this command does not derive coordinates.

```
qits artifacts publish contract [--application <application>...] [--ecosystem <maven|npm>...] [--from <dir>...] [--kind <golden-masters|pacts>...] [--name <coordinate>...] [--provider <repository>...] [--version <version>...]
```

| Name | What it does |
|---|---|
| `--application <application>...` | The application whose contracts these are; named in the package's description. |
| `--ecosystem <maven\|npm>...` | Which package to build. |
| `--from <dir>...` | The tree to pack. |
| `--kind <golden-masters\|pacts>...` | What the tree is. |
| `--name <coordinate>...` | groupId:artifactId for maven, the package name for npm. |
| `--provider <repository>...` | The provider a pact is with, by repository name: the pacts packed are --from's *_<provider>.json. Required for pacts, refused for golden masters. |
| `--version <version>...` | The release version. |

### Examples

```
qits artifacts publish contract --kind golden-masters --ecosystem maven --name eu.wohlben.qits:qits-projects-golden-masters --application qits-projects --from golden-masters/ --version 2026.1002.1
qits artifacts publish contract --kind pacts --ecosystem maven --name eu.wohlben.qits:qits-workspaces-service-pacts-qits-projects-service --application qits-workspaces --provider qits-projects-service --from pacts/ --version 2026.1002.1
```

### Exit codes

- `0` Published, already published at this version with the same content, or unchanged.
- `1` Refused: bad arguments, an empty --from (for pacts: no *_<provider>.json in it), a 4xx, or this version already holds other content.
- `2` Could not ask: the store unreachable, an I/O failure, a 5xx, or an unreadable answer; never read as unchanged.

## qits artifacts publish contract-docs

Publish the @contracts/<application> docs bundle when at least one golden-masters package is at --version: the tree under golden-masters/, plus contracts.json listing every --package with its newest version and whether that is this release. Prints exactly one line on stdout: `published <version>` or `unchanged` (no package moved; the docs store keeps the previous bundle).

--entities adds the entity exposure map as entities.json, beside contracts.json, and makes the bundle publish on every release even when no golden-masters package moved: the map can change on its own, e.g. marking an entity @Technical.

--database adds the generated entity diagrams (every `qits database diagram` file in the directory) under database/, with database/index.json naming each file's unit and the entities it draws, and likewise makes the bundle publish on every release: the schema can change with no golden master moving.

Run it after the contract packages are published: a package with no version at all is a refusal, not a state.

```
qits artifacts publish contract-docs [--application <application>...] [--database <dir>...] [--entities <file>...] [--from <dir>...] [--meta <key=value>...] [--package <ecosystem=coordinate>...] [--version <version>...]
```

| Name | What it does |
|---|---|
| `--application <application>...` | The provider application; the site is @contracts/<application>. |
| `--database <dir>...` | The entity diagrams (docs/database/); its generated *.md files are bundled under database/ with an index.json, and the bundle is then published on every release. |
| `--entities <file>...` | The entity exposure map (docs/entities.json); bundled as entities.json, and the bundle is then published on every release. |
| `--from <dir>...` | The golden-masters tree. |
| `--meta <key=value>...` | A metadata header, sent as X-Artifacts-Meta-<key>. Repeatable. |
| `--package <ecosystem=coordinate>...` | A golden-masters package, e.g. npm=@qits/projects-golden-masters. Repeatable. |
| `--version <version>...` | The release version. |

### Examples

```
qits artifacts publish contract-docs --application qits-projects --from golden-masters/ --package maven=eu.wohlben.qits:qits-projects-golden-masters --package npm=@qits/projects-golden-masters --version 2026.1002.1 --meta git.commit.hash=deadbeef
```

### Exit codes

- `0` Published, already published, or unchanged.
- `1` Refused: bad arguments, a package with no published version, or a 4xx.
- `2` Could not ask: the store unreachable, an I/O failure, or a 5xx.

## qits artifacts publish sbom

An SBOM: submit a document that already exists, or build one from a Dockerfile's FROM lines.

## qits artifacts publish sbom submit

Publish a CycloneDX document at (packageType, name, version).

```
qits artifacts publish sbom submit [--file <path>...] [--name <name>...] [--type <npm|maven|docker|daemon>...] [--version <version>...]
```

| Name | What it does |
|---|---|
| `--file <path>...` | The CycloneDX document to publish. |
| `--name <name>...` | The package name. |
| `--type <npm\|maven\|docker\|daemon>...` | The package type the sbom store files this under. |
| `--version <version>...` | The version. |

### Examples

```
qits artifacts publish sbom submit --type docker --name qits/qits-ci --version 2026.906.1 --file sbom.json
```

### Exit codes

- `0` Published, or already published with the same bytes.
- `1` Refused: bad arguments, a 4xx, or the coordinate already holds different bytes.
- `2` Could not ask: the store unreachable, an I/O failure, or a 5xx.

## qits artifacts publish sbom from-dockerfile

Build a CycloneDX document from a Dockerfile's FROM lines: one component per distinct upstream image. Publishes nothing itself: write the file with -o, then `sbom submit` it.

null-style variables in a FROM resolve from the file's own ARG default, or from --build-arg, in the precedence a real build has.

```
qits artifacts publish sbom from-dockerfile [--build-arg <NAME=value>...] [--dockerfile <path>...] [--output <path>...] [--root-name <name>...] [--root-version <version>...]
```

| Name | What it does |
|---|---|
| `--build-arg <NAME=value>...` | A build argument, for a FROM that names one. Repeatable. |
| `--dockerfile <path>...` | A Dockerfile to read FROM lines from. Repeatable. Default: Dockerfile. |
| `-o, --output <path>...` | Where to write the document. Required, exactly once. |
| `--root-name <name>...` | The image's own name, for the document's root component. |
| `--root-version <version>...` | The image's own version. |

### Examples

```
qits artifacts publish sbom from-dockerfile --root-name qits/qits-ci --root-version 2026.906.1 -o sbom.json
qits artifacts publish sbom from-dockerfile --root-name x --root-version 1 --dockerfile a.Dockerfile --dockerfile b.Dockerfile --build-arg BASE=alpine:3.20 -o sbom.json
```

### Exit codes

- `0` Wrote the document.
- `1` Refused: bad arguments, a Dockerfile with no FROM line, or a variable nothing resolves.

## qits artifacts publish docs

A documentation bundle, at (site, version).

## qits artifacts publish docs submit

Publish a documentation bundle at (site, version).

--openapi publishes one OpenAPI document instead of an archive: it is packed as the bundle's only entry, openapi.yml (or openapi.json for a .json file). That is the platform's @apidocs publish.

--dir packs a directory instead: every regular file under it, named relative to it, so its own files sit at the bundle root. Symbolic links are skipped, never followed, as the store skips them in an archive. The pack is deterministic: sorted entries, fixed modes, owners and mtimes.

With --if-changed the bundle is published only when its content hash differs from the one recorded on the site's newest version, and the hash is recorded on the new version as content.hash. The hash covers each file's path and bytes, never its order or mtime. No such site, or a newest version with no hash, counts as changed. It prints one line on stdout: `published <version>` or `unchanged since <version>`.

The store explodes the archive into per-file blobs and keeps no archive digest, so an occupied version cannot be verified: it is skipped, with a WARN naming the degradation, rather than reported as a plain success.

```
qits artifacts publish docs submit [--archive <tgz>...] [--dir <directory>...] [--if-changed] [--meta <key=value>...] [--openapi <file>...] [--site <name>...] [--version <version>...]
```

| Name | What it does |
|---|---|
| `--archive <tgz>...` | The gzipped tar archive to publish. Exclusive with --openapi and --dir. |
| `--dir <directory>...` | A directory to pack as the bundle, its files at the bundle root. Exclusive with --archive and --openapi. |
| `--if-changed` | Publish only when the content differs from the site's newest version, and record its hash as content.hash. |
| `--meta <key=value>...` | A metadata header, sent as X-Artifacts-Meta-<key>. Repeatable. |
| `--openapi <file>...` | An OpenAPI document (.yml, .yaml or .json) to publish as the whole bundle. Exclusive with --archive and --dir. |
| `--site <name>...` | The docs site's name. |
| `--version <version>...` | The version. |

### Examples

```
qits artifacts publish docs submit --site @apidocs/qits-ci --version 2026.906.1 --archive apidocs.tgz --meta git.commit.hash=deadbeef
qits artifacts publish docs submit --site @apidocs/qits-projects --version 2026.1002.1 --openapi docs/openapi.yml
qits artifacts publish docs submit --site @routes/qits-landing --version 2026.1109.1 --dir src/testing/browser/freezes --if-changed
```

### Exit codes

- `0` Published, already published (see above: not verified in that case), or unchanged.
- `1` Refused: bad arguments, a missing or empty --dir, or a 4xx that is not the store's "already there".
- `2` Could not ask: the store unreachable, an I/O failure, a 5xx, or (with --if-changed) a listing that is not an answer; never read as unchanged.

## qits artifacts publish changelog

Write this release's CHANGELOG.md and publish it as the docs site @changelog/<repository> at --version. Run in a release's publish step.

The release comes from QITS_EVENT_PAYLOAD (its repository, repositoryName, releaseRequestId, releaseRequestQualifiedId and occurredAt); the commits from the release request in qits-projects, oldest first and without the request's own merge commits, after those of every earlier release cut since the newest one that published a changelog (a release whose publish never completed has none of its own); the tickets from the subjects of the commits other than dependency bumps (`feat(qits-9, qits-10): ...`), titled by qits-projects; and the report highlights from the gate run's reports in qits-ci (QITS_CI_RUN_ID), when it has any.

The second line's time is the payload's occurredAt, or QITS_EVENT_OCCURRED_AT when the payload has none; both are optional, and a value that does not parse as an ISO-8601 time is dropped rather than refused, so the line is left without a time instead.

The last line is always `associated tickets:` followed by every ticket that exists, as `qits-9`; `qits changelog bump-message` reads it back.

The bundle is a .tar.gz with one entry, CHANGELOG.md, published like `docs submit` with the given --meta, release.request.id=<uuid> and, when the event names it, release.request.qualified-id=<repository>-rr-<n>. An occupied version is skipped, as there.

```
qits artifacts publish changelog [--meta <key=value>...] [--version <version>...]
```

| Name | What it does |
|---|---|
| `--meta <key=value>...` | A metadata header, sent as X-Artifacts-Meta-<key>. Repeatable. |
| `--version <version>...` | The release's version. |

### Examples

```
qits artifacts publish changelog --version "$QITS_VERSION" --meta git.commit.hash="$QITS_CI_SHA" --meta git.repository.name="$QITS_CI_REPO_NAME"
```

- qits-projects is https://projects.qits.$QITS_DOMAIN and qits-ci https://ci.qits.$QITS_DOMAIN; a ticket links to https://qits.$QITS_DOMAIN/projects/<slug>/work/detail/<id>.
- A ticket qits-projects does not know is listed as (no such work item), without a link, and left out of the last line.
- Changelogs are kept for good for now; nothing deletes one with its release.

### Exit codes

- `0` Published, or already published (not verified, as for docs submit).
- `1` Refused: bad arguments, QITS_EVENT_PAYLOAD or QITS_CI_RUN_ID missing or incomplete, the release request unknown, or a 4xx from the store.
- `2` Could not ask: a service unreachable, an I/O failure, or a 5xx.

## qits artifacts publish daemon

A daemon binary, at (name, version).

## qits artifacts publish daemon submit

Publish a daemon binary at (name, version).

Daemon versions are immutable: a re-publish always answers 409, even for identical bytes. The stored digest decides whether that is a re-fire of a run that already succeeded, or two builds claiming one version.

```
qits artifacts publish daemon submit [--file <bin>...] [--name <name>...] [--version <version>...]
```

| Name | What it does |
|---|---|
| `--file <bin>...` | The binary to publish. |
| `--name <name>...` | The daemon's name. |
| `--version <version>...` | The version. |

### Examples

```
qits artifacts publish daemon submit --name qits-platform-access-cli --version 2026.906.1 --file target/qits
```

### Exit codes

- `0` Published, or already published with the same bytes.
- `1` Refused: bad arguments, a 4xx, or the coordinate already holds different bytes.
- `2` Could not ask: the store unreachable, an I/O failure, or a 5xx.

## qits artifacts publish exists

Ask whether a coordinate is already published: daemon, docs, npm or sbom.

An sbom coordinate has two name parts, written <packageType>/<packageName>, for example docker/qits/qits-ci. A step that read an unreachable store as "absent" would republish on every outage, and one that read it as "present" would skip a publish that never happened, so a third exit code says "could not ask" instead.

```
qits artifacts publish exists [<type> <name> <version>]
```

| Name | What it does |
|---|---|
| `<type> <name> <version>` | The type (daemon, docs, npm or sbom), the name, and the version, in that order. |

### Examples

```
qits artifacts publish exists daemon qits-platform-access-cli 2026.906.1
qits artifacts publish exists sbom docker/qits/qits-ci 2026.906.1
qits artifacts publish exists npm @qits/ui-components 2026.906.1
```

### Exit codes

- `0` Published.
- `1` Not published, or the arguments are wrong.
- `2` Could not ask: the store unreachable, an I/O failure, or a 5xx.

## qits artifacts fetch

Write published artifacts to a local directory. deployed-pacts: the consumer pacts a provider has to keep honouring, one per consumer version that is deployed or kept for a rollback.

## qits artifacts fetch deployed-pacts

Download the consumer pacts a provider has to keep honouring: for each consumer version that is deployed or kept as a rollback target, the pact package it was built against.

qits-maintenance names the packages (GET /maintenance/api/deployed-pacts); each one's jar comes from qits-artifacts' hosted maven repository, and its pacts/*_<provider>.json files are written to <out>/<consumer repository>/<pact version>/. One line per pact: `fetched <consumer> pact <version> for deployed <v1>, <v2>`, then the count.

```
qits artifacts fetch deployed-pacts --out <dir> --provider <repository> [--artifacts-url <url>] [--maintenance-url <url>] [--output table|json]
```

| Name | What it does |
|---|---|
| `--out <dir>` | Required. Where the pacts are written, created when missing. |
| `--provider <repository>` | Required. The provider's repository name, as its pact packages name it: qits-projects-service. |
| `--artifacts-url <url>` | qits-artifacts' base URL, without /artifacts. Default: QITS_ARTIFACTS_URL, else https://registry.qits.<domain>. |
| `--maintenance-url <url>` | The maintenance service's base URL, without /maintenance. Default: QITS_MAINTENANCE_URL, else derived from the idp address or QITS_DOMAIN. |
| `-o, --output table\|json` | table (the default): one line per pact and a count. json: the service's answer with the files written. |

### Examples

```
qits artifacts fetch deployed-pacts --provider qits-projects-service --out target/deployed-pacts
qits artifacts fetch deployed-pacts --provider qits-projects-service --out /tmp/pacts -o json
```

- --out is created when it is missing. A consumer version's directory is replaced as a whole; one the platform no longer names is left as it is, so give a fresh --out to get exactly what serves now.
- No deployed consumer is not an error: --out is created and the command says so.
- In a CI step the run's token (QITS_TOKEN) reads both services; elsewhere the session or the workspace's token does.
- -o json prints qits-maintenance's answer with `files` (the paths written) on each pact and `out`.

### Exit codes

- `0` Every named pact was written, or no consumer of the provider is deployed.
- `1` qits-maintenance refused (503: qits-deployments cannot say what serves), a jar could not be downloaded, a jar holds no pacts/*_<provider>.json, or --out could not be written.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits changelog

Release changelogs, as the docs store keeps them (@changelog/<repository>, one CHANGELOG.md per version). bump-message writes a dependency bump's commit message from them, in a CI step.

## qits changelog bump-message

Print the commit message of a dependency-bump step: `bump(<group>): <N> dependencies`, the step's body, and the changelogs of every version the applied changes upgrade to, with the tickets those changelogs name in the subject's scope.

N is the number of lines in --applied. The changelogs come from the docs store (@changelog/<repository> at each version, CHANGELOG.md), for the changes in QITS_EVENT_PAYLOAD's changes[] that carry `changelog: {repository, versions}` and are listed in --applied; repositories by name, versions in version order. With no changelog to read the message is the subject and the body alone.

```
qits changelog bump-message [--applied <file>] [--body <file>] [--group <group>]
```

| Name | What it does |
|---|---|
| `--applied <file>` | The changes this step applied, one `<ecosystem><TAB><name>` per line. |
| `--body <file>` | The step's body, printed as it is. |
| `--group <group>` | The bump's group, as in `bump(<group>)`. |

### Examples

```
qits changelog bump-message --group targeted --applied applied.tsv --body body.txt > message.txt
```

- --applied holds one `<ecosystem><TAB><name>` line per change the step applied; --body the step's own lines (`- maven g:a 1 -> 2 (pom.xml)`), printed as they are.
- With tickets the subject is `chore(qits-9, qits-10): bump(<group>): <N> dependencies`, the ids sorted by project and number.
- The store is https://registry.qits.$QITS_DOMAIN; the bearer comes from QITS_PUBLISH_TOKEN_COMMAND or QITS_PUBLISH_TOKEN, as for `qits artifacts publish`.

### Exit codes

- `0` The message is on stdout.
- `1` Refused: an option or QITS_EVENT_PAYLOAD missing or unreadable, no credential in the environment, a changelog that is not published (404) or whose last line is not `associated tickets: ...`, or another 4xx.
- `2` Could not ask: the store unreachable, a 5xx, or a token command that failed. A step may retry a 2.

## qits agents

The platform's agents: list them, show one, prompt, interrupt, stop, restart or yield it, read its sessions, its chat and its worktree, attach to its terminal, keep its prompt draft, and discard it. Each on qits-workspaces' agent doors.

An agent is named by its id (`qits agents list` shows it), or by --work <qits-123>: the one active agent working on that item; none, or several, is refused (exit 1).

`qits agents claude ...` is what Claude Code runs on this machine; a person does not run those.

### Notes

- The reads are open to every credential, qits:agent included. prompt, interrupt, stop and restart need qits:admin, qits:admin-agent, qits:desk-admin, qits:desk (within its own project) or qits:system; yield also takes qits:agent for its own agent; discard and draft save|discard need qits:admin, qits:admin-agent or qits:desk-admin. A refused credential is HTTP 403 by design.
- chat, attach and files go through the agent proxy to the daemon of the slot the agent is placed in now, so none of them takes the slot. qits:agent reaches only its own agent there.
- --workspaces-url, --projects-url and --output are each verb's own: they come after the verb.

## qits agents list

Agents, newest first: what each works on, its slot, how it runs (RUNNING, YIELDED, STOPPED, ...), what it waits on and what it needs a person for. Bare, every agent; --work, one item's; --workspace, one slot's; --state ACTIVE, the live ones; --project, one project's.

WAITING ON is built from each open wait's subject: a release request or work item shows its qualified id and state, a CI run the first 8 characters of its id and state, a comments wait "comments on <item>", anything else its label; several are joined with "; ". With no waits array (an older service), it falls back to waitingOn. ATTENTION is attention.reason, a dash when there is none.

GET /workspaces/api/agents[?workId=&workspaceId=&state=&projectId=]. Any credential may read it, qits:agent included.

```
qits agents list [--output table|json] [--project <project>] [--projects-url <url>] [--state <STATE>] [--work <work>] [--workspace <n>] [--workspaces-url <url>]
```

| Name | What it does |
|---|---|
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--project <project>` | Only the agents whose repository is this project's: its id, slug or name. Sent as projectId. Without it, every project's. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--state <STATE>` | Only the agents in this state: ACTIVE or REMOVED. Default: all. How each runs is the RUN column, not a filter. |
| `--work <work>` | Only this work item's agents: its qualified id (qits-123) or its id. |
| `--workspace <n>` | Only the agents this slot hosts. |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits agents list --state ACTIVE
qits agents list --work qits-123
qits agents list --project qits --state ACTIVE
qits agents list --workspace 1 -o json
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits agents show

One agent, in any state: what it works on, how it runs, its slot, branch and session, what it waits on and what it needs a person for.

GET /workspaces/api/agents/{id}. Any credential may read it, qits:agent included.

```
qits agents show [--output table|json] [--projects-url <url>] [--work <work>] [--workspaces-url <url>] [<agent>]
```

| Name | What it does |
|---|---|
| `<agent>` | The agent's id, as `qits agents list` shows it. Leave it out with --work. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--work <work>` | Instead of <agent>: the work item whose one active agent is meant, by its qualified id (qits-123) or its id. No active agent, or more than one, is refused (exit 1). |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits agents show 00000000-0000-4000-8000-0000000000a1
qits agents show --work qits-123 -o json
```

### Exit codes

- `0` Done.
- `1` The platform refused (HTTP 404: no such agent), cannot be reached, or --work names no active agent or several.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits agents prompt

Say a turn to one agent: it hears it once its current turn ends (at once if it is idle), as its first turn, or, yielded or stopped, it starts again to hear it.

POST /workspaces/api/agents/{id}/prompt with {text, compactFirst}. It needs qits:admin, qits:admin-agent, qits:desk-admin or qits:desk (a desk, within its own project), or qits:system. qits:agent alone is refused (HTTP 403) by design.

```
qits agents prompt [--compact-first] [--output table|json] [--projects-url <url>] [--work <work>] [--workspaces-url <url>] [<agent>] [<text>]
```

| Name | What it does |
|---|---|
| `<agent>` | The agent's id, as `qits agents list` shows it. Leave it out with --work. |
| `<text>` | The prompt. |
| `--compact-first` | Have the harness compact its context before it hears the prompt. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--work <work>` | Instead of <agent>: the work item whose one active agent is meant, by its qualified id (qits-123) or its id. No active agent, or more than one, is refused (exit 1). |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits agents prompt 00000000-0000-4000-8000-0000000000a1 'Check the rounding first.'
qits agents prompt --work qits-123 'The ticket moved to REVIEW.'
qits agents prompt --work qits-123 --compact-first 'Start on the second half.'
```

- A blank text is refused before anything goes out; a removed agent is HTTP 409 AGENT_REMOVED.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), cannot be reached, or --work names no active agent or several.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits agents interrupt

Interrupt the agent's current turn: ESC into its running harness, as a person at its terminal would press it. How it runs does not change.

POST /workspaces/api/agents/{id}/interrupt, no body. It needs qits:admin, qits:admin-agent, qits:desk-admin or qits:desk (a desk, within its own project), or qits:system. qits:agent alone is refused (HTTP 403) by design.

```
qits agents interrupt [--output table|json] [--projects-url <url>] [--work <work>] [--workspaces-url <url>] [<agent>]
```

| Name | What it does |
|---|---|
| `<agent>` | The agent's id, as `qits agents list` shows it. Leave it out with --work. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--work <work>` | Instead of <agent>: the work item whose one active agent is meant, by its qualified id (qits-123) or its id. No active agent, or more than one, is refused (exit 1). |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits agents interrupt --work qits-123
```

- HTTP 409 AGENT_NOT_RUNNING (no running session) or AGENT_NOT_INTERRUPTIBLE (no harness, not a terminal, not started yet); HTTP 503 AGENT_DAEMON_UNREACHABLE. Nothing was sent.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), cannot be reached, or --work names no active agent or several.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits agents stop

Stop the agent: STOPPED. A placed agent's harness is stopped on its daemon, and its worktree and slot stay; a queued one gives up its place. Nothing places, resumes or drives a stopped agent; a wait that fires for it is heard once it is restarted or prompted.

POST /workspaces/api/agents/{id}/stop, no body. It needs qits:admin, qits:admin-agent, qits:desk-admin or qits:desk (a desk, within its own project), or qits:system. qits:agent alone is refused (HTTP 403) by design.

```
qits agents stop [--output table|json] [--projects-url <url>] [--work <work>] [--workspaces-url <url>] [<agent>]
```

| Name | What it does |
|---|---|
| `<agent>` | The agent's id, as `qits agents list` shows it. Leave it out with --work. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--work <work>` | Instead of <agent>: the work item whose one active agent is meant, by its qualified id (qits-123) or its id. No active agent, or more than one, is refused (exit 1). |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits agents stop 00000000-0000-4000-8000-0000000000a1
```

- An agent already STOPPED stays so. A removed agent is HTTP 409 AGENT_REMOVED.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), cannot be reached, or --work names no active agent or several.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits agents restart

Restart the agent's harness: a RUNNING agent's is stopped and started again on its session; a STOPPED or YIELDED agent goes back to the scheduler as YIELDED.

POST /workspaces/api/agents/{id}/restart, no body. It needs qits:admin, qits:admin-agent, qits:desk-admin or qits:desk (a desk, within its own project), or qits:system. qits:agent alone is refused (HTTP 403) by design.

```
qits agents restart [--output table|json] [--projects-url <url>] [--work <work>] [--workspaces-url <url>] [<agent>]
```

| Name | What it does |
|---|---|
| `<agent>` | The agent's id, as `qits agents list` shows it. Leave it out with --work. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--work <work>` | Instead of <agent>: the work item whose one active agent is meant, by its qualified id (qits-123) or its id. No active agent, or more than one, is refused (exit 1). |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits agents restart --work qits-123
```

- HTTP 409 AGENT_REMOVED, or AGENT_NOT_STARTED for a queued agent; HTTP 503 AGENT_DAEMON_UNREACHABLE when a running agent's daemon does not answer.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), cannot be reached, or --work names no active agent or several.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits agents yield

Give up the agent's slot: YIELDED. The slot is free at once and the harness keeps running; anything the agent should hear brings it back.

POST /workspaces/api/agents/{id}/yield, no body. It needs qits:admin, qits:admin-agent, qits:desk-admin or qits:desk (a desk, within its own project), or qits:system; qits:agent may yield only its own agent.

```
qits agents yield [--output table|json] [--projects-url <url>] [--work <work>] [--workspaces-url <url>] [<agent>]
```

| Name | What it does |
|---|---|
| `<agent>` | The agent's id, as `qits agents list` shows it. Leave it out with --work. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--work <work>` | Instead of <agent>: the work item whose one active agent is meant, by its qualified id (qits-123) or its id. No active agent, or more than one, is refused (exit 1). |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits agents yield 00000000-0000-4000-8000-0000000000a1
```

- Only a running agent yields: HTTP 409 AGENT_NOT_RUNNING otherwise.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), cannot be reached, or --work names no active agent or several.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits agents sessions

The agent's harness sessions, oldest first, read off the shared harness volume: a removed agent's too. Each with when it started and ended, its messages and its subagents.

GET /workspaces/api/agents/{id}/sessions. Any credential may read it, qits:agent included.

```
qits agents sessions [--output table|json] [--projects-url <url>] [--work <work>] [--workspaces-url <url>] [<agent>]
```

| Name | What it does |
|---|---|
| `<agent>` | The agent's id, as `qits agents list` shows it. Leave it out with --work. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--work <work>` | Instead of <agent>: the work item whose one active agent is meant, by its qualified id (qits-123) or its id. No active agent, or more than one, is refused (exit 1). |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits agents sessions 00000000-0000-4000-8000-0000000000a1
```

- `qits agents transcript <agent> <session>` reads one of them.

### Exit codes

- `0` Done.
- `1` The platform refused (HTTP 404: no such agent), cannot be reached, or --work names no active agent or several.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits agents transcript

One of the agent's sessions, turn by turn: who said what, the tools it used named. A removed agent's sessions answer too.

GET /workspaces/api/agents/{id}/sessions/{sessionId}/transcript. With -o json, the session's raw JSON lines as the service answers them. Any credential may read it, qits:agent included.

```
qits agents transcript [--output table|json] [--projects-url <url>] [--work <work>] [--workspaces-url <url>] [<agent>] [<session>]
```

| Name | What it does |
|---|---|
| `<agent>` | The agent's id, as `qits agents list` shows it. Leave it out with --work. |
| `<session>` | The session's id, as `qits agents sessions` shows it. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--work <work>` | Instead of <agent>: the work item whose one active agent is meant, by its qualified id (qits-123) or its id. No active agent, or more than one, is refused (exit 1). |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits agents transcript 00000000-0000-4000-8000-0000000000a1 00000000-0000-4000-8000-0000000000d1
qits agents transcript --work qits-123 00000000-0000-4000-8000-0000000000d1 -o json
```

### Exit codes

- `0` Done.
- `1` The platform refused (HTTP 404: no such agent, or a session that is not its), cannot be reached, or --work names no active agent or several.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits agents chat

Read the agent's conversation: its harness command's transcript, in order. The agent's Chat tab, without the live socket.

GET /workspaces/agent/{id}/log?channel=TRANSCRIPT: the agent's last command, found by the service. With -o json, the transcript as the daemon answers it. It goes through qits-workspaces' agent proxy to the daemon of the slot the agent is placed in now. qits:system, qits:admin and qits:admin-agent reach every agent; qits:agent only its own; qits:desk and qits:desk-admin read every agent's and open no terminal.

```
qits agents chat [--output table|json] [--projects-url <url>] [--work <work>] [--workspaces-url <url>] [<agent>]
```

| Name | What it does |
|---|---|
| `<agent>` | The agent's id, as `qits agents list` shows it. Leave it out with --work. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--work <work>` | Instead of <agent>: the work item whose one active agent is meant, by its qualified id (qits-123) or its id. No active agent, or more than one, is refused (exit 1). |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits agents chat 00000000-0000-4000-8000-0000000000a1
qits agents chat --work qits-123 -o json
```

- An agent waiting for a slot is HTTP 409: it has no daemon to ask.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), cannot be reached, or --work names no active agent or several.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits agents attach

Attach this terminal to the agent's harness terminal: what you type goes to the harness, what it prints comes here. The agent's Terminal tab in the landing app.

Ctrl-] detaches and leaves the harness running; Ctrl-C and every other key go to it. The window's size goes along. A dropped connection attaches again after 0.5, 1, 2, 4 and 4 s. With stdin not a terminal it only streams what the harness prints.

WS /workspaces/agent/{id}/terminal: the agent's running command on the daemon of the slot it is placed in now. It goes through qits-workspaces' agent proxy to the daemon of the slot the agent is placed in now. qits:system, qits:admin and qits:admin-agent reach every agent; qits:agent only its own; qits:desk and qits:desk-admin read every agent's and open no terminal.

```
qits agents attach [--output table|json] [--projects-url <url>] [--work <work>] [--workspaces-url <url>] [<agent>]
```

| Name | What it does |
|---|---|
| `<agent>` | The agent's id, as `qits agents list` shows it. Leave it out with --work. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--work <work>` | Instead of <agent>: the work item whose one active agent is meant, by its qualified id (qits-123) or its id. No active agent, or more than one, is refused (exit 1). |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits agents attach 00000000-0000-4000-8000-0000000000a1
qits agents attach --work qits-123
```

- An agent that is not RUNNING is refused (HTTP 409): it has no terminal.
- Not an MCP tool: it needs a terminal and runs until the harness ends.

### Exit codes

- `0` The harness ended, you detached (Ctrl-]), or it was stopped (SIGINT, SIGTERM); only the first ends the harness.
- `1` The platform refused the socket, the connection dropped and five attempts to attach again failed, or --work names no active agent or several.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits agents files

The agent's worktree, as its Files tab shows it: the tree, one file, and the frameworks and test links the tab draws.

## qits agents files list

The agent's worktree: without --path the whole tree, every gitignored directory a stub with its entry count; with --path that directory, one level deep.

GET /workspaces/agent/{id}/files[?path=]. It goes through qits-workspaces' agent proxy to the daemon of the slot the agent is placed in now. qits:system, qits:admin and qits:admin-agent reach every agent; qits:agent only its own; qits:desk and qits:desk-admin read every agent's and open no terminal.

```
qits agents files list [--output table|json] [--path <dir>] [--projects-url <url>] [--work <work>] [--workspaces-url <url>] [<agent>]
```

| Name | What it does |
|---|---|
| `<agent>` | The agent's id, as `qits agents list` shows it. Leave it out with --work. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--path <dir>` | A directory of the worktree, relative to its root. Default: the whole tree. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--work <work>` | Instead of <agent>: the work item whose one active agent is meant, by its qualified id (qits-123) or its id. No active agent, or more than one, is refused (exit 1). |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits agents files list 00000000-0000-4000-8000-0000000000a1
qits agents files list --work qits-123 --path node_modules
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), cannot be reached, or --work names no active agent or several.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits agents files read

One file of the agent's worktree, its text. A binary file, or one over the daemon's 2 MB cap, is not shown.

GET /workspaces/agent/{id}/files/content?path=. In the table form each line is made safe for a terminal (a tab becomes a space); -o json keeps the text exact. It goes through qits-workspaces' agent proxy to the daemon of the slot the agent is placed in now. qits:system, qits:admin and qits:admin-agent reach every agent; qits:agent only its own; qits:desk and qits:desk-admin read every agent's and open no terminal.

```
qits agents files read [--output table|json] [--projects-url <url>] [--work <work>] [--workspaces-url <url>] [<agent>] [<path>]
```

| Name | What it does |
|---|---|
| `<agent>` | The agent's id, as `qits agents list` shows it. Leave it out with --work. |
| `<path>` | The file, relative to the worktree's root. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--work <work>` | Instead of <agent>: the work item whose one active agent is meant, by its qualified id (qits-123) or its id. No active agent, or more than one, is refused (exit 1). |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits agents files read 00000000-0000-4000-8000-0000000000a1 src/main/java/app/Login.java
qits agents files read --work qits-123 README.md
```

### Exit codes

- `0` Done.
- `1` The platform refused (HTTP 404: the path names nothing), cannot be reached, or --work names no active agent or several.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits agents files detect

The frameworks the agent's worktree holds (each with its member paths) and which tests reach each source file: the Files tab's footer and its test links.

GET /workspaces/agent/{id}/detection. It goes through qits-workspaces' agent proxy to the daemon of the slot the agent is placed in now. qits:system, qits:admin and qits:admin-agent reach every agent; qits:agent only its own; qits:desk and qits:desk-admin read every agent's and open no terminal.

```
qits agents files detect [--output table|json] [--projects-url <url>] [--work <work>] [--workspaces-url <url>] [<agent>]
```

| Name | What it does |
|---|---|
| `<agent>` | The agent's id, as `qits agents list` shows it. Leave it out with --work. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--work <work>` | Instead of <agent>: the work item whose one active agent is meant, by its qualified id (qits-123) or its id. No active agent, or more than one, is refused (exit 1). |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits agents files detect 00000000-0000-4000-8000-0000000000a1
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), cannot be reached, or --work names no active agent or several.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits agents discard

Remove an agent: its worktrees, credential and draft go. --force removes it anyway.

POST /workspaces/api/agents/{id}/discard with {"force": false|true}. It needs qits:admin, qits:admin-agent or qits:desk-admin (the delivery desk, within its own project), which a person's token or the desk's carries. qits:agent alone is refused (HTTP 403) by design: an agent keeps every read and loses the writes.

```
qits agents discard [--force] [--output table|json] [--projects-url <url>] [--work <work>] [--workspaces-url <url>] [<agent>]
```

| Name | What it does |
|---|---|
| `<agent>` | The agent's id, as `qits agents list` shows it. Leave it out with --work. |
| `--force` | Remove it despite leftover work or a daemon that does not answer. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--work <work>` | Instead of <agent>: the work item whose one active agent is meant, by its qualified id (qits-123) or its id. No active agent, or more than one, is refused (exit 1). |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits agents discard 00000000-0000-4000-8000-0000000000a1
qits agents discard --work qits-123 --force
```

- Without --force the service refuses (HTTP 409) an agent whose worktrees hold work that never reached the git host (AGENT_HAS_LEFTOVER_WORK), whose daemon does not answer (AGENT_DAEMON_UNREACHABLE) or that is still busy (AGENT_BUSY). --force removes it anyway and throws the leftover work away.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), cannot be reached, or --work names no active agent or several.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits agents draft

An agent's saved prompt draft: what the prompt box on its Chat tab holds, kept on the service so it survives a reload and another device.

## qits agents draft show

The agent's saved prompt draft: its text and when it was written. An agent with none says so.

GET /workspaces/api/agents/{id}/prompt-draft (HTTP 404: nothing was composed). Any credential may read it, qits:agent included.

```
qits agents draft show [--output table|json] [--projects-url <url>] [--work <work>] [--workspaces-url <url>] [<agent>]
```

| Name | What it does |
|---|---|
| `<agent>` | The agent's id, as `qits agents list` shows it. Leave it out with --work. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--work <work>` | Instead of <agent>: the work item whose one active agent is meant, by its qualified id (qits-123) or its id. No active agent, or more than one, is refused (exit 1). |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits agents draft show 00000000-0000-4000-8000-0000000000a1
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits agents draft save

Save the agent's prompt draft, as typing in its prompt box does. The draft holds the text and no picked file ranges.

PUT /workspaces/api/agents/{id}/prompt-draft with {content: '{"text":<text>,"picked":[]}', serializedPrompt: <text, trimmed>}. It needs qits:admin, qits:admin-agent or qits:desk-admin (the delivery desk, within its own project), which a person's token or the desk's carries. qits:agent alone is refused (HTTP 403) by design: an agent keeps every read and loses the writes.

```
qits agents draft save [--output table|json] [--projects-url <url>] [--work <work>] [--workspaces-url <url>] [<agent>] [<text>]
```

| Name | What it does |
|---|---|
| `<agent>` | The agent's id, as `qits agents list` shows it. Leave it out with --work. |
| `<text>` | The draft's text. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--work <work>` | Instead of <agent>: the work item whose one active agent is meant, by its qualified id (qits-123) or its id. No active agent, or more than one, is refused (exit 1). |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits agents draft save 00000000-0000-4000-8000-0000000000a1 'Check the rounding.'
qits agents draft save --work qits-123 'Check the rounding.'
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits agents draft discard

Delete the agent's saved prompt draft: Discard on a restored draft.

DELETE /workspaces/api/agents/{id}/prompt-draft. It needs qits:admin, qits:admin-agent or qits:desk-admin (the delivery desk, within its own project), which a person's token or the desk's carries. qits:agent alone is refused (HTTP 403) by design: an agent keeps every read and loses the writes.

```
qits agents draft discard [--output table|json] [--projects-url <url>] [--work <work>] [--workspaces-url <url>] [<agent>]
```

| Name | What it does |
|---|---|
| `<agent>` | The agent's id, as `qits agents list` shows it. Leave it out with --work. |
| `-o, --output table\|json` | table (the default): for a person. json: the service's answer, pretty-printed, with control characters written as escapes. |
| `--projects-url <url>` | The projects service's base URL, without /projects, where a work item's qualified id (qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address. |
| `--work <work>` | Instead of <agent>: the work item whose one active agent is meant, by its qualified id (qits-123) or its id. No active agent, or more than one, is refused (exit 1). |
| `--workspaces-url <url>` | The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, else derived from the idp address, like the other services. |

### Examples

```
qits agents draft discard 00000000-0000-4000-8000-0000000000a1
```

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits agents claude

What Claude Code runs on this machine. A person does not run these.

## qits agents claude hook

Claude Code's hooks. Each is registered in Claude's settings.json; the help of each shows how.

## qits agents claude hook work-links

Claude Code's MessageDisplay hook: shows work ids such as qits-111 in Claude's messages as links to the landing app's work item page. Claude runs it; a person does not.

It reads one batch of the message on stdin and prints the text to show, or nothing when no id was linked. Only the screen changes: the transcript and the model keep the plain id. An id inside a word, a branch (ticket/qits-1152), a longer name (qits-1152-x), a link, inline code or a code block stays as it is. Any error prints nothing and exits 0, so Claude shows the original text.

The project slugs are cached for an hour in $XDG_CACHE_HOME/qits/work-links.json (default ~/.cache/qits/). A stale cache is used as it is while a refresh runs in the background, with this home's credential; until the first one is done only `qits` ids are linked.

```
qits agents claude hook work-links [--landing-url <url>] [--style <style>]
```

| Name | What it does |
|---|---|
| `--landing-url <url>` | The landing app's base URL. Default: QITS_LANDING_URL, else the platform's bare domain. |
| `--style <style>` | markdown ([qits-111](url)) or osc8 (a terminal hyperlink). Default: QITS_LINK_STYLE, else markdown. |

### Examples

```
{"hooks": {"MessageDisplay": [{"hooks": [{"type": "command", "command": "qits agents claude hook work-links", "timeout": 5}]}]}}
```

The example is the entry in Claude's settings.json (~/.claude/settings.json).

- The landing app is the platform's bare domain: on a workstation the session's idp address without `idp.`, elsewhere https://qits.<QITS_DOMAIN>. --landing-url or QITS_LANDING_URL sets it.
- QITS_LINK_STYLE=osc8 is the same as --style osc8. QITS_LINK_PROJECTS (comma-separated slugs) replaces the cache.

### Exit codes

- `0` Always, also when nothing was printed.

## qits agents claude statusline

Claude Code's status lines. Each is registered in Claude's settings.json; the help of each shows how.

## qits agents claude statusline work-items

Claude Code's status line: the work items this session spoke of most recently, newest first, each with its status square and block glyph and linked to the landing app (OSC 8). Claude runs it; a person does not.

It reads Claude's status line JSON on stdin and the end of the transcript it names, where ids count from Claude's text and from tool results, by the same rules as `qits agents claude hook work-links`. It prints one line, or nothing when there is no id. The squares are the session names' palette; a red mark in front means blocked, and an interrobang that the item's agent is waiting on a person.

Each id's status is cached for 60 seconds in $XDG_CACHE_HOME/qits/work-states.json (default ~/.cache/qits/). What is cached shows at once, an id with nothing cached shows without a mark, and one background refresh asks the projects service for the stale ids with this home's credential. Any error prints the ids without marks, or nothing; the exit is 0.

```
qits agents claude statusline work-items [--landing-url <url>] [--limit <n>]
```

| Name | What it does |
|---|---|
| `--landing-url <url>` | The landing app's base URL. Default: QITS_LANDING_URL, else the platform's bare domain. |
| `--limit <n>` | How many ids to show. Default: 5. |

### Examples

```
"statusLine": {"type": "command", "command": "qits agents claude statusline work-items", "refreshInterval": 30}
```

The example is the entry in Claude's settings.json (~/.claude/settings.json).

- Your own status line script can run it too: pass the JSON it was given on stdin and print the line it prints.
- The landing app and the project slugs are found as `qits agents claude hook work-links` finds them, with the same --landing-url, QITS_LANDING_URL and QITS_LINK_PROJECTS.

### Exit codes

- `0` Always, also when nothing was printed.

## qits agent-mcp

The catalog of MCP servers the platform gives its agents, in qits-projects. `credentials` stores the secrets those servers are reached with.

## qits agent-mcp credentials

The credentials of the agent MCP catalog: secrets a catalog entry names by key, stored once in qits-projects and handed to the MCP servers that need them. Only the keys are ever shown.

### Notes

- --output and --projects-url may come before or after the command.
- list takes the catalog's read roles: qits:admin, qits:admin-agent, qits:desk-admin or qits:agent. set and delete take qits:admin or qits:admin-agent.

## qits agent-mcp credentials list

List the stored credentials' keys, with when and by whom each was last set. Never a value.

```
qits agent-mcp credentials list [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects`. |

### Examples

```
qits agent-mcp credentials list
```

- Takes qits:admin, qits:admin-agent, qits:desk-admin or qits:agent.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits agent-mcp credentials set

Store one credential under a key, replacing any stored before. The value is read on stdin, never from a flag, so it stays out of the shell's history; one trailing line break is taken off. It is never printed or sent back.

```
qits agent-mcp credentials set --key <KEY> [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--key <KEY>` | Required. The credential's key, as catalog entries name it: env.<VAR>, such as env.GITHUB_TOKEN. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects`. |

### Examples

```
qits agent-mcp credentials set --key env.GITHUB_TOKEN < token.txt
printf '%s' "$TOKEN" | qits agent-mcp credentials set --key env.GITHUB_TOKEN
```

- A terminal on stdin, or nothing on it, is refused with exit 2 and nothing is sent.
- 400 (exit 1) means the key is not env.<VAR>, or the value is blank or larger than 16 KiB.
- Takes qits:admin or qits:admin-agent.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits agent-mcp credentials delete

Delete the credential stored under a key.

```
qits agent-mcp credentials delete --key <KEY> [--output table|json] [--projects-url <url>]
```

| Name | What it does |
|---|---|
| `--key <KEY>` | Required. The credential's key: env.GITHUB_TOKEN. |
| `-o, --output table\|json` | table (the default): aligned columns. json: the service's answer, pretty-printed. |
| `--projects-url <url>` | The projects service's base URL, without /projects. Default: QITS_PROJECTS_URL, else the session's idp address with `idp` swapped for `projects`. |

### Examples

```
qits agent-mcp credentials delete --key env.GITHUB_TOKEN
```

- 404 (exit 1) means no credential is stored under the key; 400 that the key is not env.<VAR>.
- Takes qits:admin or qits:admin-agent.

### Exit codes

- `0` Done.
- `1` The platform refused (the message names the status), or cannot be reached.
- `2` Used wrongly, not signed in, or the session ended (run `qits login`).

## qits update

Updates qits on this workstation to the newest released version, and says what it did.

It asks qits-artifacts for the newest version with the session of `qits login`, downloads it next to the running binary, checks its length, its sha256 digest and that it answers `-V` with that version, and then puts it in place of the running binary in one rename. Any failure leaves the installed qits as it was.

qits also does this by itself: a signed-in qits on a workstation checks at most once an hour, in the background, so no command waits for it. A failure there is one line on stderr of the next command. `qits -V` prints the version this binary is.

Only on a workstation: inside the platform, in a workspace with QITS_TOKEN and in the MCP service the image decides which qits there is, and this refuses.

```
qits update [--artifacts-url <url>] [--check]
```

| Name | What it does |
|---|---|
| `--artifacts-url <url>` | qits-artifacts' base URL. Default: QITS_ARTIFACTS_URL, else from the session's idp address. |
| `--check` | Only say whether a newer version is released; download nothing. |

### Examples

```
qits update
qits update --check
QITS_AUTO_UPDATE=false qits projects list
```

- QITS_AUTO_UPDATE=false (or 0, off, no) turns the automatic update off; `qits update` still works.
- Where the binary's directory is not writable, the new version is downloaded and checked into $XDG_CACHE_HOME/qits/ (default ~/.cache/qits/), and the command to install it (`sudo install -m 755 ...`) is printed.
- The store's address is https://registry.qits.<domain>, from the session's idp address; --artifacts-url or QITS_ARTIFACTS_URL sets it.

### Exit codes

- `0` Up to date, updated, or (with --check) a newer version reported.
- `1` Not updated: the store refused or cannot be reached, a download failed its checks, the directory is not writable, or this build cannot update itself. The message says which.
- `2` Used wrongly, not on a workstation, not signed in, or the session ended (run `qits login`).

## qits tui

Pick a command instead of remembering it: an interactive screen over every qits command.

The upper half is the picker: the commands, then the options of the one chosen, with the values the platform can offer. The command being built is shown between the halves, so the screen also teaches the command line. The lower half is the output of what was run.

```
qits tui
```

### Examples

```
qits tui
```

- Keys: arrows or k/j move, Enter chooses, Esc goes back, / filters, Ctrl-R runs, Ctrl-C stops a run, Ctrl-P shows this session's history, q quits.
- Needs an interactive terminal of at least 80x24. In a pipe or a CI step it says so and exits 2.
- It runs a command by starting this same binary again, so a run behaves exactly as it does when typed.

### Exit codes

- `0` Done.
- `1` Unused.
- `2` There is no interactive terminal, or it is smaller than 80x24.
