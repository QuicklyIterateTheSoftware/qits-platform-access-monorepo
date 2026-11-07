# AGENTS.md — qits-platform-access-monorepo

## What this repository is

The `qits` command: access to the qits platform from a Linux or WSL workstation. A Quarkus
command-mode CLI with picocli, built as a GraalVM native binary. Its commands: `qits login`
(browser sign-in, session stored in `$XDG_CONFIG_HOME/qits/t.json`), `qits session-daemon` (keeps
that session fresh), `qits projects|repositories|work|ideas|release-request` (the projects service;
`work` is every work item, of every archetype), `qits ci runs|run|retry|cancel|report` (qits-ci's runs, a
run's step logs, a retry, a cancel, a run's release reports), `qits database diagram` (a repository's entity
diagram, generated from its compiled classes), `qits contracts coverage` (the OpenAPI operations no
golden master records), `qits users` (the idp's users: invite, register, list,
delete), `qits remembered-devices` (the signed-in person's remembered browsers at the idp: list,
revoke; qits-1944), `qits events` (the live event
stream), `qits wait` (an agent's declared waits in qits-workspaces), `qits workspaces` (every action
of the landing app's workspace pages, on the same qits-workspaces doors), `qits targets` (execution
targets and their leases, qits-system-service's doors; epic qits-1481), `qits checkout-daemon` (a local checkout held at what a repository released, root and
submodules), `qits observe` (the live, server-filtered telemetry stream of qits-observability, over
a WebSocket), and `qits git-login` / `qits git-credential` (Git pushes to
`refs/heads/external/*`, sign-in stored in `$XDG_CONFIG_HOME/qits/git.json`), `qits agents` (the
platform's agents, one verb each, on qits-workspaces' agent doors; epic qits-1667), `qits agents claude …` (what
Claude Code runs: the work-links hook and the work-items status line), `qits update` (the binary
replacing itself with the newest release, also started in the background), and `qits tui` (an
interactive screen over all of them). The README says how each behaves.

It has **three homes**. A workstation, where a person signs in with a browser and the session lives
in a file; a workspace handed a token of its own (`QITS_TOKEN`: every agent container, a runner
node, an admin or editor workspace), which sends that token as it is to the public vhosts; and
in-platform (`QITS_PLATFORM=true`), which the MCP service sets for the commands it runs: services
are dialled at the same public vhosts of `QITS_DOMAIN`, with the caller's own bearer handed to each
command (no home dials a wire alias, qits-1436). The mode is
decided once at startup and the homes are never mixed.

## Four modules

Since 2026-09-17 this repository is a Maven reactor. The root `pom.xml` is an aggregator
(`eu.wohlben:qits`, packaging `pom`) holding the shared properties and the quarkus-bom import, the
shape qits-ci-daemon's root has, and it lists four modules, in this order:

    platform-access-cli-binary  the pin. One class, three strings, no bytes of the binary: the
                                daemons-store name, the command name (`qits`), and the version the
                                same release published the binary under, filtered in from
                                `${project.version}`. THE ONLY ARTIFACT THIS REPOSITORY DEPLOYS TO A
                                MAVEN REPOSITORY.
    platform-access-commands    the commands (`eu.wohlben.qits:qits-platform-access-commands`).
                                Everything under `access/` but `Main`, `session/`, `tui/api/` and
                                `tui/model/`, with their tests: the whole picocli tree except
                                `qits tui`. A plain jar on plain picocli — never quarkus-picocli,
                                which would make every consumer a command-mode application, and no
                                JLine. quarkus-arc is there for the annotations (the completion
                                sources are beans, the records Jackson binds carry @RegisterForReflection).
                                picocli-codegen writes the tree's native-image reflection config
                                into the jar, and a Jandex index lets Quarkus see its classes.
                                Deploys nothing (`maven.deploy.skip`); consumed in this reactor.
                                Also builds a test jar, so the MCP service's tests run against the
                                same fakes (`FakePlatform`, `FakeSocketServer`).
                                maven-model-builder (quarkus-bom's 3.9 line) is here for
                                `publish maven`'s effective models; its super pom and the model
                                classes its interpolator reflects on are declared under
                                META-INF/native-image/qits-platform-access-commands/maven-model-builder/.
    platform-access-mcp-service the MCP service (`eu.wohlben.qits:qits-platform-access-mcp-service`,
                                application `qits-platform-access-mcp-service`): the commands served
                                as MCP tools at `/mcp`. quarkus-mcp-server-http, quarkus-oidc (bearer
                                check only), health. Never quarkus-oidc-client, never quarkus-picocli.
                                Deploys nothing to Maven; the release ships it as an image. See "The
                                MCP service" below.
    platform-access-cli         the program. The sources moved from `src/` to
                                `platform-access-cli/src/` on 2026-09-17, and its published
                                coordinates (`eu.wohlben.qits:qits-platform-access-cli`) did not
                                move — they are the repository's identity. Since the commands
                                moved out, it holds `Main`, `QitsCommandLine`, `tui/` (minus api/
                                and model/), JLine, quarkus-picocli and the native profile. It
                                deploys nothing (`maven.deploy.skip`): what it produces is the
                                binary, published to the artifacts store as bytes.

**How the program finds its tree.** `AccessCli` carries no `@TopCommand` (a quarkus-picocli
annotation) and does not list `tui`. `application.properties` names it as
`quarkus.picocli.top-command`, and `QitsCommandLine` produces the `CommandLine` `Main` is handed:
quarkus-picocli's tree with `tui` put back before `help`, the order it was declared in, so `--help`
and `SKILL.md` did not change. A test that needs the binary's tree builds it the same way,
`QitsCommandLine.withTui(new CommandLine(new AccessCli()))`.

**Why a second module rather than a second pom beside one.** The release stamper walks a reactor by
`<module>` only, so a nested pom the root does not list is never version-stamped — and the pin is
worthless unless its version is the released version by construction.

**Why the pin exists at all.** qits-ci hands the `qits` CLI to every composed release step, and it
used to download whatever was latest in the daemons store at the moment the step started: a shared,
unversioned, unreviewed input to every release on the platform at once. On 2026-09-13 one bad CLI
release broke all of them, with nothing changed in any consumer's tree and no line to revert. Now
qits-ci's *pom* depends on this jar and injects the version it names — a bad CLI breaks one
repository's gate, and the fix is a revert of one line. `PlatformAccessCliBinary`'s javadoc carries
the whole reasoning; `.config/qits/ci-event-release.yml`'s last step publishes it.

`SKILL.md` stays at the repository root and did not follow the sources into the module; the test
that keeps it in step (`SkillDocumentTest`) resolves it one directory up and says why.

## The MCP service

`platform-access-mcp-service/src/main/java/eu/wohlben/qits/cli/mcp/`: three classes.

    ToolCatalog  the tools, read off the picocli tree: one per command that runs (a leaf, or a
                 runnable parent), named by its path joined with `_`. A command is NOT a tool when
                 the nearest `@TuiCommand(interaction)` from it upwards is BROWSER, STREAMING,
                 TERMINAL, CI_ONLY or LOCAL; those are listed, with their reason, in the server's
                 `instructions`. A flag that would attach to a terminal (`--attach`) cannot be
                 left out of a schema, so the command refuses it there (`CliContext.inMcpService`).
                 The schema is the command's options (inherited ones included) and
                 positionals, minus help/version and every `*-url` option (a caller-chosen address
                 would receive the caller's bearer), plus `payload` on `input = PAYLOAD` commands.
                 `--output` defaults to `json` where the command offers it.
    ToolRunner   one call: a fresh CommandLine, argv built from the arguments (never a shell), the
                 payload as stdin, buffered stdout/stderr, a CliContext on every PlatformCommand
                 with the caller's bearer (`RequestCredential`) and a fixed environment
                 (`QITS_PLATFORM=true`, `QITS_ENV`, the `QITS_<APP>_URL` overrides the service was
                 configured with, nothing else). One call adds `QITS_AGENT_ID` from the `agent`
                 claim of the caller's token, so `qits wait` finds the calling agent (qits-1213).
                 Exit 0 is the stdout (and stderr, when it says something); anything else is
                 `isError` with stderr, `exit <n>` and stdout.
    McpTools     registers the catalog with `ToolManager` under the server `qits`, runs each call on
                 a worker thread (the commands block), and answers `instructions`. It sets
                 `QITS_MCP_SERVICE` first, so `CliContext.system()` throws in this process.

A new command or option on the CLI is a tool or a schema property with no change here.
`McpToolCatalogTest` walks the real tree and fails when that stops being true; `McpServiceTest`
proves the 401 without a bearer, `tools/list`, the instructions and a call carrying the caller's
bearer; `McpWireJsonTest` reads a `tools/call` answer as bytes and fails on any `null` member
(`"_meta":null`, `"annotations":null`, `"structuredContent":null`), which MCP clients reject;
`BearerIssuerTest` proves the tenant pins the issuer the idp publishes (below). A plain `mvn verify`
runs all four; none needs docker or a real idp (the bearer tests sign with a key under
`src/test/resources`).

**The bearer check pins the idp's published issuer** (qits-708). The tenant runs with discovery on
and no issuer key at all: it reads `<auth-server-url>/.well-known/openid-configuration` and checks
every bearer's `iss` against the `issuer` that document publishes (`https://idp.qits.<domain>`),
besides the signature (keys from its `jwks_uri`, fetched when a bearer first needs one), `exp` and
the audience `qits-platform`. Never add `quarkus.oidc.token.issuer`: the issuer is not
configuration (qits-730), and `ClientCallsGuardTest.theMcpTenantsReadsArePacted` refuses one. The
two reads are rows of the idp's contract (`IdpContract`, framework rows triggered by `StartupEvent`
from `qits-platform-access-mcp-service`, the issuer bound by value), because this repository has
one pacticipant and the service is part of it. `BearerIssuerTest` runs the shipped tenant against
`IssuerIdp`, a discovery document and a JWKS in the test's process: a correctly signed token with
another `iss` is a 401. There is no `connection-delay`: one read at boot, and an idp that does not
answer is a WARN and a retry on the first bearer, so readiness never waits on the idp.

**The native runner has tests of its own**, because one failure exists only there (qits-1226):
Vert.x's JSON codec, which writes every tool result, may be initialised at native-image build time
with a bare ObjectMapper instead of the one carrying the MCP extension's NON_NULL mixins, and then
every result carries those nulls. Whether a build does it is not deterministic. `McpTools` resets
the codec at startup (`QuarkusJacksonJsonCodec.reset()`, a Quarkus-internal class: re-check it on a
Quarkus upgrade), and `McpWireJsonIT` — `McpWireJsonTest` as a `@QuarkusIntegrationTest` — holds it
against the runner. Failsafe is bound in the service pom's `native` profile only, so a plain `verify`
never looks for a runner; `./mvnw verify -Dnative -pl platform-access-mcp-service -am` builds one and
runs it, `-Dnative.image.path=<runner>` with the goals `test-compile failsafe:integration-test
failsafe:verify` tests any runner without compiling, and docker/Dockerfile's `service-it` stage does
exactly that to the runner the image ships, so a red IT fails the release request.

## Layout

The commands' sources, under `platform-access-commands/src/main/java/eu/wohlben/qits/cli/access/`
(`Main` and `QitsCommandLine`, in the same package, are in `platform-access-cli`):

    idp/       the idp's /token endpoint and which idp to use
    session/   the session file, its atomic write, the two locks, and the one refresh (SessionRefresh)
    login/     qits login: PKCE, the pasted code, the browser opener
    daemon/    qits session-daemon: the refresh loop, the sleeper seam, the stop signals
    platform/  what every platform command shares: the context, the token (with inline refresh),
               the HTTP client and its error messages, which address a service has (PlatformUrls),
               the aligned table (Table), and the --since/--until/--limit window both query
               commands share (QueryWindow, a picocli mixin)
    projects/  qits projects, repositories, work, ideas and release-request. `qits work` is one group for
               every archetype (WorkCommand; WorkEntities holds the transition's full-state merge
               and the tables). Its writes take a JSON payload on stdin and, with none, print the
               usage and the schema the service serves per archetype and door (entity doors) or
               its OpenAPI document (comment doors), through WorkPayload; the CLI keeps no copy of
               a schema and no route per archetype. `repositories remote-login` (RemoteLoginCommand,
               qits-1268) attaches to WS /projects/api/repositories/{id}/remote-login through
               terminal/'s TerminalAttach; without --repository it is the list's
               wrapper.repositoryId, as the page's Backups panel picks it, and a clean close is
               followed by the panel's project-wide backup-sync and one re-read 3 s later
               `repositories backup reset` (RepositoriesCommand.BackupCommand, qits-1885) is POST
               /projects/api/repositories/{id}/backup-reset with {reason}: a person's or the
               project's desk's door, which words its own 403, so it posts with no role hint
               `qits ideas` (IdeasCommand, epic qits-888) is a project's ideas on /projects/api/ideas, the
               same shape: --entity is the idea's id, the writes' schemas come from the OpenAPI
               document through WorkPayload, and `spawn` files work through createWork with the
               idea's project, title, description and inspiredBy filled in
               `qits ideas` (IdeasCommand, epic qits-888) is a project's ideas on /projects/api/ideas, the
               same shape: --entity is the idea's id, the writes' schemas come from the OpenAPI
               document through WorkPayload, and `spawn` files work through createWork with the
               idea's project, title, description and inspiredBy filled in
               What an item needs (epic qits-1899) is its `needs`, written whole by create and
               update; the NEEDS column and the details' needs lines read the work reads' `needs`
               and `unmetNeeds` (WorkEntities.needs, needsCell, needLine). Campaigns, their members
               and progress, milestones and dependsOn are gone (qits-1905). The word is "needs",
               never "waiting": that is an agent's wait
    ci/        qits ci: runs, run (with the step logs), retry and cancel, on qits-ci's run API
    report/    qits ci report: release reports. The framework (ReportKind, ReportParser,
               StepContext, Highlight, TestCaseLocator, ChangedLines), the one registry of kinds
               and parsers (ReportKinds: a new kind is one entry there plus its class), the
               test-results kind (TestResultsKind over SurefireXmlParser, FailsafeXmlParser and
               VitestJunitParser, all JUnit XML read with StAX), the coverage kind (CoverageKind
               over JacocoExecParser, org.jacoco.core pinned to the archetypes' agent version, and
               IstanbulJsonParser; diff coverage from GitChangedLines), the contracts kind in contracts/
               (ContractsReportKind over PactFileParser, PactJvmVerificationReportParser and
               GoldenMasterIndexParser; ContractChanges, the identity rules the ui-components view
               shares through the fixtures under report/contracts/diff/. A pact made on a service's
               behalf by a library carries a `via` (PactEntry.Via: repository, artifact, version),
               read off the pact's `qits-via` comment reference; it is part of a pact's identity
               (ContractChanges.Pair's `via`), so a direct pact and a library's pact for the same
               consumer and provider are two pacts, never merged, and a highlight that names such a
               pair shows the library and its version), the entity-changes kind
               (EntityChangesReportKind, fed by no parser: step 0's generated docs/database/*.md
               against the same files at the baseline's tag, parsed back with the database/
               package's MermaidEntityDiagramFormat), the screenshots kind in screenshots/
               (ScreenshotsReportKind, fed by no parser: the committed baseline images at HEAD and
               at the baseline's tag as `git ls-tree` path -> blob maps, never bytes; where they
               live is a ScreenshotConvention, one per storage convention, listed in
               ScreenshotConventions; VitestBrowserScreenshots is the one today), BaselineTag (the
               one shallow fetch of the baseline's tag, for every kind that reads the baseline's
               files: list and read; screenshots peels its ref), Git (public for the subpackages'
               kinds), and
               the commands: submit (CI-step
               only: the publish chain's bearer through PublishCredential.forCiStep, qits-ci at
               Store.publicOrigin("ci"), its own 120 s deadline) and show. Payload records carry
               @RegisterForReflection all the way down; Jackson writes and reads them back
               (ReportJson). The server never learns what a kind means.
    database/  qits database diagram: the entity diagram under docs/database/ (epic qits-760).
               EntityModelSource (one per language/ORM, listed in EntityModelSources) gives UnitModels
               of TableModel/ColumnModel/RelationModel; EntityDiagramFormat renders and parses them
               (MermaidEntityDiagramFormat, which the entity-changes report kind parses back).
               JpaEntityModelSource is the jpa one: JpaInputs (the reactor's target/classes indexed
               with Jandex, the classpath files' jars listed and indexed lazily), JpaUnits (the units
               the reactor's own config declares) and JpaMapping (the mapping, Hibernate's naming).
               JpaMappingConformanceTest holds JpaMapping against Hibernate's Metadata over the
               fixtures under database/fixtures; Hibernate is a test dependency only. The golden
               files are rewritten with -Dgolden.update=true, as the pact file is.
    contracts/ qits contracts coverage (qits-1940): the operations of a service's docs/openapi.yml
               (OpenApiDocument, YAML or JSON read as a tree) that golden-masters/index.json records no
               call for (GoldenMasterMatch; the index read by report/contracts/GoldenMasterIndexParser
               .operations). CI-step only; its --out TSV feeds the golden-master-coverage report kind
               (report/GoldenMasterCoverageReportKind). The test package of the same name is the
               consumer pacts' harness, a different thing
    events/    qits events: the SSE parser and the reconnecting stream; `query` under it pages the
               events log over a window (EventsQueryCommand)
    checkout/  qits checkout-daemon: the Git side of one checkout (Checkout) and the reconcile-and-
               watch loop over ReleaseRequestStateChanged to FINALIZED (ReleaseWatcher; never
               SCMRelease, qits-1470), a sibling of events/EventStream that shares its SSE parser
    observe/   qits observe: the --filter grammar, the reconnecting WebSocket stream, the line form,
               and SafeText (terminal control characters out of every streamed value); `query`
               under it asks the records search with the same filters (ObserveQueryCommand)
    waits/     qits wait: the --when grammar (WhenGrammar, a copy of qits-eventstream-javalib's
               selection grammar and limits, producing its wire JSON; keep the two in step), the
               --timeout reader, the presets and list/cancel on qits-workspaces' agent waits
    waits/     qits wait: the --when grammar (WhenGrammar, a copy of qits-eventstream-javalib's
               selection grammar and limits, producing its wire JSON; keep the two in step), the
               --timeout reader, the presets and list/cancel on qits-workspaces' agent waits
    workspaces/ qits workspaces: the landing app's workspace pages, one command per action (qits-1261).
               WorkspacesApi is every door, with the method, path, query and body the page sends;
               the daemon's doors go through the container proxy, /workspaces/container/{slot}/…;
               Doors is who may call each kind of door, in the help's words. LandingPactTest (test)
               runs a command for every call in the landing app's pacts (copied under
               src/test/resources/workspaces/landing-pacts/) and fails on a call no command makes;
               copy the pacts again when the pages change. Register runner and rotate token are
               left out: they answer a one-time token. `attach` is the Terminal tab, on terminal/'s
               TerminalAttach; `--attach` on `workspaces agents sign-in open` does the same for the
               sign-in terminal.
               `qits agents`' verbs live here too (epic qits-1667), because WorkspacesApi does:
               AgentCommands (the verbs, listed by agents/AgentsCommand), AgentRef (an agent's id or
               --work, the one ACTIVE agent on the item; none or several is exit 1), AgentOptions
               (each verb's -o and service URLs, a mixin, so `qits agents claude` carries none),
               WorkspacesAccess (what both trees take from their options) and AgentViews (the
               agents table, the draft, the chat, file and detection printing, shared with `qits
               workspaces show`, which prints a slot's agents the same way). The deprecated aliases
               of epic qits-1667 (`qits workspaces agents list|discard|prompt|draft|chat|terminate|
               harness|files`) lived for one release and are gone (qits-1749); `workspaces agents`
               keeps only sign-in, plugins and refine, the container's harness install. chat, attach
               and files go through the agent proxy, /workspaces/agent/{id}/…, never the container
               proxy, so no verb takes the slot
    terminal/  attaching this terminal to a remote one over a WebSocket, shared by `workspaces
               attach` and `repositories remote-login`: TerminalAttach is the reconnecting socket
               with the landing app's frames and backoff (what is attached to is only a label in
               its log lines, TerminalAttach.Subject; ended() tells a clean close from a detach);
               LocalTerminal is the caller's terminal, SttyTerminal the real one (stty on /dev/tty,
               SIGWINCH through sun.misc.Signal plus a size poll; no JLine here), READ_ONLY the
               answer when stdin is no terminal
    targets/   qits targets: execution targets and their leases, qits-system-service's /system/api
               (epic qits-1481, ticket qits-1530). TargetsApi is every door; the command group is
               modelled on workspaces/'s WorkspaceRunnersCommand. `create` and `rotate-token`
               (qits-1574) answer a one-time registration token inside an install line: they print
               the line alone on stdout, once, and are Interaction.LOCAL, as `users invite` is.
               `sign-in` prints the target's login command for a harness and polls `login-check`
               every 30 seconds until PRESENT or --timeout passes
    git/       qits git-login and git-credential: the loopback callback, git.json, Git's helper
               protocol, the per-host Git setup
    artifacts/ qits artifacts: the group, with publish (in publish/ below) and fetch. `fetch
               deployed-pacts` (qits-1765, Interaction.LOCAL: it writes files) asks qits-maintenance's
               MaintenanceApi.deployedPacts which pact package each deployed consumer version was
               built against and unpacks each jar's pacts/*_<provider>.json through PactJars (the
               hosted maven repository over PlatformClient, the command's own credential). Its rows:
               listDeployedPacts in MaintenanceContract, pending until a pinned
               qits-maintenance-golden-masters records `a provider whose deployed consumers publish
               pacts`; the jar GET in ArtifactsContract (FetchRows)
    changelog/ qits changelog bump-message, and the two grammars a changelog shares with its
               readers: CommitSubjects (the ids a `type(qits-9, qits-10):` subject names, and their
               order) and AssociatedTickets (the changelog's last line, written and read back).
               The changelog itself is written by `publish changelog` (publish/Changelog,
               ChangelogCommand).
    publish/   qits artifacts publish: the qits-publish client, folded in from the retired
               qits-artifacts-cli. Store, Publisher, Npm, DockerfileSbom, Http, Json, Sha256,
               VersionOrder, Env, Console, CliException and ExitCode were that repository's
               classes, kept as they were; the picocli commands and PublishArgs (the
               argument-grammar checks Args used to do) are new. Touches no session file — see
               Conventions.
               Since epic qits-620 it also owns maven and npm publishing: ContentHash (the
               `v1:sha256:` value; HashManifest, SbomCanonical, CanonicalJson, pinned by
               ContentHashGoldenTest's fixtures F1-F5: a rule change is `v2`, never an edit of a
               fixture), Decision (the if-changed flow over the store's content-hash reads,
               ContentHashes), MavenPublisher (flatten + bundle + link over maven-model-builder's
               effective models, MavenReactor), NpmPublisher (the tarball and publish document),
               ContractPackager and ContractDocs, and Archives (the deterministic jar and tar.gz).
    deployments/ qits deployments settings list|set|delete|preview, on qits-deployments' settings doors
               (DeploymentsApi; epic qits-765). Its rows are DeploymentsContract, pending until a pinned
               qits-deployments-golden-masters records their states
    agentmcp/  qits agent-mcp credentials list|set|delete, on qits-projects' agent MCP catalog
               (AgentMcpApi; epic qits-765). Its rows belong to ProjectsContract (AgentMcpContract)
    remembered/ qits remembered-devices list and revoke (qits-1944), on the idp's
               /idp/api/remembered-devices doors (IdpRememberedDevicesApi), addressed as users/
               addresses the idp. A person's doors: the idp admits a CLI bearer
               (credential_type=cli) or a browser session and 403s every other bearer; the
               list's GET carries no role hint (get(uri, false)), since an agent is refused a read.
               Its rows (IdpRememberedDevicesContract, part of idp/IdpContract) send
               `Authorization: {authorization}` in `a remembered device`, pending until a pinned
               qits-idp-golden-masters records that state with a CLI bearer as its authorization
               param.
    users/     qits users invite, register, list and delete, on the idp's user doors (IdpUsersApi).
               register sends no bearer: the register token is the credential, and the person has
               no session yet. The idp's address is the session's, the public vhost inside
               (PlatformEndpoints), or, for register with no session, IdpUrl's default
    agents/    qits agents: the group (AgentsCommand), whose verbs are workspaces/AgentCommands; only
               its `claude` group is Interaction.LOCAL.
               qits agents claude: what Claude Code runs. hook work-links (WorkLinks, the id matching;
               LinkProjects, the slug cache, refreshed by the same command's hidden --refresh started
               detached). The hook path never waits for the network and never fails: errors print nothing
               and exit 0
               statusline work-items (TranscriptIds, the transcript's tail; WorkStates, the per-id state
               cache, refreshed by a detached --refresh <ids>; StatusMarker, a copy of qits-coding-agents'
               session-name palette). Detached starts either refresh, and the background update
    update/    qits update and the automatic update (qits-1262). QitsVersion is the built-in version
               (`${project.version}` filtered into qits-version.properties, which
               META-INF/native-image/qits-platform-access-commands/version/ names for the native
               image) and `-V`'s provider, handed to every command by QitsVersion.provideTo;
               SelfUpdate the one check-download-verify-rename, with the store (Releases,
               ArtifactsReleases), the binary's path and the `-V` probe as seams; AutoUpdate what
               Main runs before every command: workstation only, QITS_AUTO_UPDATE, the hourly gate in
               update.json (UpdateState), the commands that never start a check, and the detached
               `qits update --background` (agents/Detached). Ordering is publish/VersionOrder
    help/      qits help skill (hidden): the commands' help arranged as SKILL.md
    complete/  the five platform sources behind the TUI's dropdowns (projects, repositories,
               release requests, runs, versions), over the credential the commands use

Two packages sit outside `access/`, because neither is about one command:

    ../tui/      `qits tui`: the screen over the whole command tree. api/ is its contract
                 (@TuiCommand, CompletionSource, @Completes), model/ the tree read from
                 CommandSpec, screen/ the lines, run/ the fork and the history, complete/ the
                 resolution and caching of sources. TuiApp is all of the behaviour, with no
                 terminal in it. api/ and model/ are in platform-access-commands, beside the
                 commands that use them; the rest, and JLine, are in platform-access-cli.
    ../session/  which of the CLI's three homes this is (Mode), where the services are in it
                 (PlatformEndpoints), the workspace's token (TokenCredential), a caller's bearer
                 handed in by the MCP service (RequestCredential), what a call is made with any
                 way (Credential), and BrowserGuard.

## Conventions

- **Plain Language everywhere**: comments, commit messages, documentation and every string the
  program prints. A comment says why, not what.
- **The help texts are the one source of SKILL.md.** A command's `description`, `footer` (examples
  are the lines with two spaces in front; the other lines are notes) and `exitCodeList` are
  picocli's own fields; no custom annotations, which would need reflection in the native binary.
  `SkillDocumentTest` fails when `SKILL.md` differs from the help: write it again with
  `./mvnw test -Dtest=SkillDocumentTest -Dqits.skill.update=true` and commit it with the change.
  Keep the help ASCII, and remember that picocli formats it (`%n` is a line break, `%%` a percent).
- **Never print or log a token** — not with a flag, not in an error, not in a test failure.
  `Session`, `TokenResponse` and `Pkce` override `toString` for that reason. An error from the idp
  names its status, `error` and `error_description`, never the request form. A Jackson error on a
  session or token body is replaced, because its message can quote the body.
  **The one exception is `qits git-credential get`**, which prints the git access token on
  stdout because that is Git's credential helper protocol. Nothing else prints one, and stderr and
  logs never carry one, that command's included.
  Four commands print a secret that is not an access token, because handing it to one person is
  their whole job: `qits users invite` prints a register token, `qits users register` prints the
  generated password, and `qits targets create` and `qits targets rotate-token` print the install
  line that embeds a target's registration token, each once. Each goes to stdout alone and nowhere
  else: never stderr, a log, a file or an error message (`UsersCommandsTest` and
  `TargetsCommandsTest` hold them). All four are `Interaction.LOCAL`, so the MCP service does not
  serve them and none lands in an agent's transcript.
- **Every refresh and every write of `t.json` happens under the write lock**, and the file is read
  again under it. Refresh tokens rotate; a spent one presented again revokes the whole session.
  `SessionRefresh` is the only code that spends a `t.json` refresh token; the daemon and the
  platform commands both call it. Do not copy it. `git.json` follows the same rules under
  `git.json.lock` (`GitAccess`); its tokens are another client's (`qits-git-workstation`), so the
  two files never share a token. Both are written through `PrivateFiles`.
- **One place decides a service's address**: `PlatformEndpoints`. `PlatformUrls` keeps the commands'
  own flags and `QITS_<APP>_URL` variables and hands the rest to it. The public vhosts
  in every home — from the session's idp on a workstation, from `QITS_DOMAIN` inside the platform —
  and `QITS_URL_<APP>` over either. The wire aliases (`http://<env>-qits-<app>:8080`) the
  in-platform home used to dial are gone with the internal address plane (qits-1436).
- **One credential interface**: `Credential`. `AccessTokens` is the workstation's, `TokenCredential`
  the token home's, and `CliContext.credential()` picks by mode. A command never asks which it has.
  They are never mixed: only a workstation opens the session file (`BothHomesTest`). The third,
  `RequestCredential`, is a caller's bearer handed in whole by the MCP service
  (`CliContext.withCredential`); it never mints or refreshes. In-platform has no credential of its
  own: a call there without one handed refuses (`CliContext.NOTHING_HANDED`). That service
  sets `QITS_MCP_SERVICE`, and `CliContext.system()` then throws: a command it forgot to hand a
  context must not call the platform as the service.
- **The TUI holds no command's name.** Everything it shows it read from picocli's model.
  `FictionalCommandTest` fails the build if a string literal under `eu.wohlben.qits.cli.tui`, in
  either module, names a command. What the model cannot say is said by
  `@TuiCommand(interaction, output, input)` on the command and `@Completes(SomeSource.class)` on the
  option — all optional, all with a working default. `Interaction.LOCAL` (only means something on the
  caller's machine) and `input = PAYLOAD` (reads a JSON payload on stdin; `PayloadMarkerTest`) are
  for whoever runs the commands somewhere else; the screen treats `LOCAL` like `PLAIN`.
  `input = TEXT` is a command that reads one plain value on stdin through `StdinValue.read` (a
  secret: `deployments settings set`, `agent-mcp credentials set`), which refuses a terminal or
  nothing with exit 2 and takes off one trailing line break; the MCP service gives it a string
  `payload`, sent as the command's stdin as it is. Such a command never prints the value back.
- **The CLI never sends `X-Qits-User` / `X-Qits-Roles`.** Those are what the gateway asserts about a
  caller; a client that writes them asserts a role it does not hold. An agent that needs a door its
  credential cannot open is granted the audience instead.
- **Platform answers are read as Jackson trees** (`JsonNode`), not records: the services add fields
  and grow their word lists, and a tree needs no reflection in the native binary.
- **Write first.** Once the idp answers a refresh, the old refresh token is spent: write the new
  pair before anything else.
- **Time comes from a `Clock` and a `Sleeper`.** Nothing in `daemon/` calls `Thread.sleep` or
  `Instant.now()` directly, so the tests move time instead of waiting.
- **Stopping never interrupts the daemon thread.** An interrupt during the file write closes the
  channel and loses a rotated token. `stop()` wakes the sleeper instead. `qits events` stops the
  same way, and aborts its open connection (`HttpClient.shutdownNow`), never the thread.
  `EventStream` waits through its `Sleeper`; only its idle watchdog reads `System.nanoTime`.
  `qits observe` does the same: `stop()` aborts the socket and puts a stop mark on the loop's queue.
- **A stream's `query` never touches the stream.** `qits events` and `qits observe` are runnable
  parents: bare, they stream; `query` under each reads a window of the past and returns. A parent's
  required options are checked even when a subcommand is named, so `qits observe` refuses a
  missing `--filter` in `execute`, in picocli's words, rather than with `required = true`. The
  `query` leaves say `@TuiCommand(interaction = PLAIN)` themselves, so the parents' `STREAMING`
  does not reach them (nearest declaration wins). `SkillDocument` documents the options of a parent
  that has its own, as it does a leaf's.
- **Streamed telemetry is untrusted text.** Ingest takes records without a sign-in, so anyone who
  reaches it writes what `qits observe` shows. Every streamed value goes through `SafeText.line`
  (the line form) or `SafeText.JSON` (the JSON form), notices and close reasons included. A new
  field in the output goes through them too. A CI step's output is untrusted in the same way (the
  code the run builds writes it), so `qits ci` puts every value through `SafeText` as well. So does
  `qits work`: people and agents write an item's title, description and comments. Its JSON form
  goes through `SafeJson`, never `ProjectsApi.printJson`, which escapes nothing.
- **The observe wire protocol is qits-observability-service's README, "The live stream"**, shared
  with qits-observability. Change it there first, and on both sides.
  The server sends no acknowledgement for a subscribe frame, so an `{"error": …}` before the first
  record is taken as its answer.
- The commands do not configure their idp client (`TokenClient`) from its discovery document: they
  address the idp they were given directly. The MCP service's bearer check is the one reader of
  discovery here, for the issuer it publishes (qits-708).
- The PKCE and token code, `GitOrigin` and `LoopbackCallback` were copied from qits-bootstrap-cli.
  Do not share a jar with it.
- **Git setup is per host.** `qits git-login` sets `credential.<git host>.helper` and never
  `credential.helper`: the person's global helper serves GitHub and must stay. A test that runs
  `git config --global` sets `GIT_CONFIG_GLOBAL` to a scratch file first.
- **`qits artifacts publish` never touches `t.json`, `git.json` or their locks.** It runs in a CI
  step container with no person signed in, so its commands read only `CliContext.env()`, `.out()`
  and `.err()` — never `.sessionFile()` or `.tokens()`. If a change to `publish/` needs
  either, that change belongs somewhere else.
- **Every publish request carries a bearer.** Only a CI run may write to qits-artifacts, and its
  reads want one too, so `Http` puts an `Authorization` header on every PUT, GET and HEAD.
  `PublishCredential` is the one place that decides where the token comes from, in this order:
  `QITS_PUBLISH_TOKEN_COMMAND` (an executable printing a fresh token, run again for every request,
  because a release step can publish an hour after it started), then `QITS_PUBLISH_TOKEN`. With
  neither of them the request is still **sent**, without the header: the store is the authority on who
  may write, and refusing locally would hide its 401 behind a message about a variable. A command
  builds its client with `AbstractPublishCommand.http()`, never `new Http(...)`, so an anonymous
  client cannot be had by forgetting an argument.
- **A `publish/` command that has its own `--version` flag must not use
  `mixinStandardHelpOptions`.** The mixin's `-V`/`--version` (the binary's own version) collides by
  name with qits-publish's `--version` (the artifact version), and picocli then recognises neither
  the mixin's `--version` nor `--help` on that command — proven the hard way, and guarded by
  `CommandSurfaceTest.helpWorksOnEveryCommandThatHasItsOwnVersionFlag`. Declare `-h`/`--help` alone
  instead (`usageHelp = true`, no `versionHelp`).

## Build forms

    sdk env && ./mvnw package -Dnative -DskipTests   the binary: platform-access-cli/target/qits,
                                                     and the service:
                                                     platform-access-mcp-service/target/
                                                     qits-platform-access-mcp-service
    ./mvnw clean verify                              the tests; packages the pin and commands jars
    docker build --target binary --output type=local,dest=out -f docker/Dockerfile .
                                                     the released form: static musl, out/qits

The first two are ROOT invocations and build the whole reactor. The pin module is one class with
no main-scope dependency, so it costs seconds next to a native compile and is never worth skipping.
The Dockerfile builds `-pl platform-access-cli -am`: the program and the commands it is built
from, without the pin, which its own release step deploys.

The application module builds no jar; its pom keeps it so the same way qits-bootstrap-cli's does.
The one jar this repository deploys is `platform-access-cli-binary`'s, and it carries three
strings rather than any of this code — see Three modules above. The commands jar is built and never
deployed. Run `clean verify` before a native
build, never after: `clean` removes the binary. `.sdkmanrc` pins 25.0.2-graalce.

The released binary is static (musl) and the host build is not: only `docker/Dockerfile` adds
`--static --libc=musl`, with `additional-build-args-append`, so the pom's own build args stay. The
two recipes in `.config/qits/` build that Dockerfile on the platform's BuildKit, and the release
recipe publishes `out/qits` as the daemon binary `qits-platform-access-cli` and then, in a second
step on a maven image, deploys the pin jar that names it (README, Releases). The export layout is
what keeps those recipes short: the binary lands in `platform-access-cli/target/` now, but the
Dockerfile's `binary` stage still exports `out/qits` and its `sbom` stage `/sbom.json`, so nothing
outside the Dockerfile moved when the reactor split.
The same release step builds the MCP service from the Dockerfile's `service` target (a second
native compile in its own `service-build` stage, never on the binary's RUN: two native-image
processes would not fit the 4g cap; then `service-it` runs the runner's ITs, and the image takes the
runner from there, so the QA step's `service` build is the gate) and pushes it as `qits/qits-platform-access-mcp-service`; its
SBOM comes from `service-sbom` into `out/service/`, and the composed postlude submits both declared
SBOMs from that one step, which is why the image is built there and not in a step of its own.
`.config/qits/deployments.yml` makes qits-deployments deploy it (application
`qits-platform-access-mcp-service`, `routes: /mcp`, `host: mcp`, `health_path: /q/health/ready`).
`binary` stays the Dockerfile's last stage, so a plain build still yields `qits`.
Keep their `buildctl` calls identical, argument for argument: the builder's cache is shared, and
that is what makes a release after a green fold cache hits. The musl toolchain is a stage of that
Dockerfile, copied from qits-ci-daemon's `docker/Dockerfile.musl-builder`: no registry tag, so a cold
platform needs no other repository's run. Keep the copy identical to that file. The recipes pass the
tarballs' in-network URLs; off the platform, pass the build args the Dockerfile's header names.

Native rules: HTTP and WebSocket with `java.net.http`, never `java.awt` (the browser is `wslview`/`xdg-open`). A
class with a `SecureRandom` in a static field is initialised at run time (`Pkce`, in the native
profile). Records Jackson reads or writes carry `@RegisterForReflection`. A change to any of these
is proven with the native binary, not with the tests.

JLine is asked for its `exec` provider by name and the pom depends on `jline-terminal`, never the
aggregate `jline`: the jni and ffm providers do not survive GraalVM's analysis. `org.jline.nativ` is
initialised at run time for the same reason. `NativeImageRulesTest` fails the build if a forbidden
provider reaches the classpath, and `docker/Dockerfile` runs `qits tui` in the built binary with no
terminal and checks it says so and exits 2 rather than waiting for a key. An enum used as an option
type needs `@RegisterForReflection` for the TUI to list its constants; without it the option is
typed rather than picked, never a crash.

## Tests

`./mvnw clean verify` must be green on a clone with **no docker and no platform**. No test reaches
a real idp: `FakeIdp` is an idp in the test's own process (`com.sun.net.httpserver`), and
`FakeTime` is the clock and the sleeper. The lock tests start a second JVM (`LockHolder`), because
an fcntl lock works between processes and a test in one JVM would only prove the in-process gate.
`FakePlatform` is the edge and the services: canned JSON by method and path, and an SSE route whose
connections follow scripts. `FakeSocketServer` is a WebSocket server on a plain `ServerSocket` (the
RFC 6455 handshake; text, ping, pong and close frames), one script per connection; it needs only the
JDK, so a native proof can run it from `target/test-classes`. `PlatformCommandsTest` runs picocli in the test's process with its own
`CliContext` (environment, stdout, stderr, clock), so a command test needs no second process.

**The consumer pacts** (epics qits-546 and qits-965, ticket qits-1149). Every REST call the
commands make to a qits service is a row of its provider's contract, and `pacts/qits-platform-access-monorepo_<provider>.json`
at the repository root is what those rows write: written, never hand-edited. Each provider has one
`CliPact` (test), the table of (command, state, operation) rows beside the client it calls
(`projects/ProjectsContract`, ...), listed in `contracts/Contracts`, and one `*ConsumerPactTest`
extending `contracts/CliPactTest`, which runs each row's real client against a pact-jvm mock server
answering that row alone, checks the client method the call went through (`Reached`, a credential
that reads the stack) and compares the committed file byte for byte (rewrite it with
`-Dgolden.update=true` and review the diff). Every answer comes from the provider's golden masters,
read through qits-integrations' `qits-pact-consumer` from the jar `eu.wohlben.qits:<app>-golden-masters`
pinned by `qits.<app>-golden-masters.version` in the root pom and bumped by qits-maintenance, so a
row names a provider state the provider records. A row binds only what the command reads of the
answer (`consumes`), and sends exactly the recorded request, body and query included, but for the
top-level body members it names with `sendsWithout`: a field the provider has deprecated and ignores
but still records its own request with (qits-1673: `create`'s `summary`). A row whose
state the provider has not recorded yet stays in the table, skipped with the state it needs and
its door, and out of the file until a newer pin records it. Every interaction carries `qits-call`
and a `qits-trigger` of kind `command` naming the command (`qits work details`), but the MCP
service's two reads of the idp (`CliRow.framework`, qits-708), whose trigger is the `StartupEvent`
of `qits-platform-access-mcp-service` and which `ClientCallsGuardTest` holds to that service's
`application.properties` instead of to a client method.

`contracts/ClientCallsGuardTest` holds the table to the code: every class whose bytecode calls a
request method of `PlatformClient`, the publish `Http` or the JDK's `HttpClient` is a client of
some contract or named in its `NOT_A_CLIENT` with the reason (the transports, the SSE and
WebSocket streams); every door of every client (each non-private instance method of an `*Api`
class, or the named methods of a command class) is reached by a row or named as an exclusion of
its contract with the reason (`openApi`, the served OpenAPI document; a WebSocket address); and
every contract with a recorded row has its committed file and its line in `release.yml`'s
`contracts.pacts`, which publishes each file; each provider verifies its own.

The qits-projects table (`projects/ProjectsContract`) is the `/work` doors by qualified id (epic
qits-965), the project and repository lookups of every command that names one, and the doors
outside `/work`: the release request's commits for `qits artifacts publish changelog`
(`listReleaseRequestCommits`, state `a released release request`); the repository's requests of
every state, where a command finds a request by its logical id (`listRepositoryReleaseRequests` with
`state=all`, state `a repository with a release not merged to main`; qits-1158); the git reads of
`qits repositories` (branches, commits, commit changes and diff, commit subjects); a refinement's
designs for `qits work design` (state `a ticket with a design`; qits-1174); the idea doors of `qits ideas`
(epic qits-888: list, get, create, patch, archive, unarchive in `a project with ideas`, create also
in `a project with no work`; pending until the pin reaches qits-projects-service 2026.1030.185452); the idea doors of `qits ideas`
(epic qits-888: list, get, create, patch, archive, unarchive in `a project with ideas`, create also
in `a project with no work`; pending until the pin reaches qits-projects-service 2026.1030.185452); the room itself for `qits
work refinement` (qits-1383): `open`'s `startWorkRefinement` then `ensureRefinementContainer`, both in
`a ticket with a design`, and its 409 in `a refined ticket`; `show`'s `getWorkRefinement` in `a
reported ticket` (null) and `a ticket with a design`; `discard`'s `getWorkRefinement` and
`discardRefinement` in `a ticket with a design`; and `qits
release-request approve` / `decline` (qits-1287): the project's door that finds a request by its
logical id (`getProjectReleaseRequest`, state `a project with release requests in every state`) and
the two decisions (`approveReleaseRequest` in `a release request awaiting approval while its build
runs`, `declineReleaseRequest` in `a release request awaiting approval`); the needs of `qits work list` / `details` (epic qits-1899): `listProjectWork` and `getWork` in `a ticket
that needs another`, recorded since the pin reached qits-projects-service 2026.1106.140436; `qits repositories
backup reset` (qits-1885): `backupResetRepository` in `a repository whose backup twin holds unrelated
history`, as the person that state names (its `authorization` bearer), pending until a golden-masters
pin records that state; and `qits projects desk prompt` /
`answer` (qits-1572): `promptFrontDesk` and `getFrontDeskLastAnswer` in `a project whose front desk
is running`, both pending until a golden-masters pin records that state. A path the recorder froze
under both `ids` and `strings` (`qualityGates[*].checks[*].runId`) holds a whole UUID in some
elements only, so no row consumes it: qits-pact-consumer would match it as a UUID.

The qits-workspaces table (`workspaces/WorkspacesContract`) has the `qits agents` rows of epic
qits-1667; the deprecated `qits workspaces agents` rows they replaced went with the aliases
(qits-1749). `getAgent` (`show`) is pinned and live; prompt, interrupt, stop, restart, yield,
sessions, transcript and `qits workspaces show`'s `listWorkspaceAgents` are recorded on the
provider's epic branch and pending until a pin carries them; `--work`'s lookup (`listAgents` with
`workId` and `state=ACTIVE`) is recorded in no state yet and waits in `an agent working on a
ticket`. The agent proxy's routes are exclusions, not rows.

**The tapes** (epic qits-881). `tapes/<integration>-cli.tape` and `-tui.tape` are VHS tapes that
play the released static binary in a real terminal against a fake platform; README, "The tapes",
says what each shows and how to play one by hand. `./mvnw verify` never plays them: QA does, in the
third `release-request:` step of `.config/qits/release.yml` (`check-outputs: [cli-tapes]`), which
runs qits-ci's cli-tapes generator and fails when a golden under `tapes/golden/` moved. Goldens and
GIFs come from `qits release-request regenerate --kind cli-tapes`, never by hand.
`TapesCoverageTest` (platform-access-cli) walks the binary's command tree: a top-level command with
no tapes fails it, unless `NOT_AN_INTEGRATION` or `CLI_ONLY` names it with the reason.
`tapes/prepare.sh` is SOURCED by the generator (bash, `set -eu`, no `exit`, background output to a
file): it builds the Dockerfile's `tapes-kit` target with the QA build's own arguments
(`docker/buildctl-args.sh`, which release.yml's build steps source too, so the native compile is a
cache hit), starts `FakePlatformMain` (commands module, test classes: `FakePlatform` on port 47080,
`FakeSocketServer` on 47081, answers from the golden masters and `src/test/resources/tapes/`,
every write a 403) and exports the `QITS_URL_<APP>` addresses and a fake token home. A tape shows
nothing that moves between plays: no clock, no random port, no temporary path, no version of this
build. Each command or key is typed while hidden and shown once its screen is finished, because vhs
writes a text frame after every command and a frame taken mid-output would make the golden flaky.
A golden-master state the fake names that a newer pin drops fails `prepare.sh`, not a tape.

What only a person can prove: a real sign-in in the browser, and a daemon that rotates the session
against the real idp for longer than one access token lives.

## Gotchas

- Java's file locks are fcntl record locks. The kernel drops a process's lock when ANY descriptor
  of that file closes, so nothing may open a lock file except `ExclusiveLock`. Inside one JVM an
  overlapping lock throws rather than waits; `ExclusiveLock`'s per-path semaphore covers that.
- An atomic rename gives `t.json` a new inode. Watch it by polling (`SessionFile.fingerprint`), not
  with a watch on the old inode.
