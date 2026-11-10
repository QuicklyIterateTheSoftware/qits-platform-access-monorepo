package eu.wohlben.qits.cli.access;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every integration has its VHS tapes (epic qits-881): {@code tapes/<integration>-cli.tape}, and
 * {@code tapes/<integration>-tui.tape} unless it is CLI-only. The integrations are read off the
 * binary's command tree — its top-level commands, minus the ones {@link #NOT_AN_INTEGRATION} names
 * with the reason — so a new command on the platform fails this test until it has its tapes, and a
 * removed one fails it until its tapes go too. The tapes themselves are played by qits-ci's
 * cli-tapes generator, never here: a plain {@code mvn verify} needs no vhs, no docker and no
 * platform.
 */
class TapesCoverageTest {

    /**
     * Top-level commands that are not a platform integration a person looks at, and so have no tape.
     */
    static final Map<String, String> NOT_AN_INTEGRATION = Map.of(
            "tui", "the screen itself: every integration's -tui tape plays it",
            "session-daemon", "keeps a workstation's session fresh against the idp; nothing to show but a sleep",
            "agents", "the platform's agents are driven by agents; `agents claude` is Claude Code's hook and "
                    + "status line, run by Claude Code rather than typed",
            "database", "a CI step's entity diagram, generated from a repository's compiled classes",
            "contracts", "a CI step's golden-master coverage, read from a repository's own files",
            "help", "the help, which every tape's commands already show; `help guide` is the platform's guide text",
            "network-access", "runs Docker on a workstation (the companion container), and the tapes' kit has no "
                    + "docker; NetworkAccessCommandsTest drives it against a recording fake docker instead");

    /** Integrations with a CLI tape only, and why there is no screen to show. */
    static final Map<String, String> CLI_ONLY = Map.of(
            "login", "a browser sign-in: the tape shows the refusal where no browser can be",
            "git-login", "a browser sign-in for Git: the tape shows its refusals",
            "registry-login", "writes a workstation's npm and Maven setup: the tape shows the token home's refusal",
            "artifacts", "a CI step's publishing: the tape shows its argument checks",
            "changelog", "a CI step's commit subject: the tape runs it on local files");

    @Test
    void everyIntegrationHasItsTapes() {
        Path tapes = repositoryRoot().resolve("tapes");
        List<String> missing = new ArrayList<>();
        for (String integration : integrations()) {
            for (String tape : tapesOf(integration)) {
                if (!Files.isRegularFile(tapes.resolve(tape))) {
                    missing.add("tapes/" + tape);
                }
            }
        }
        assertThat(missing).as("integrations without their tapes (a new command needs tapes/<name>-cli.tape and "
                + "-tui.tape, or an entry in NOT_AN_INTEGRATION or CLI_ONLY with the reason)").isEmpty();
    }

    @Test
    void everyTapeBelongsToAnIntegration() throws Exception {
        Set<String> expected = new TreeSet<>();
        integrations().forEach(integration -> expected.addAll(tapesOf(integration)));
        Set<String> present = new TreeSet<>();
        try (var files = Files.list(repositoryRoot().resolve("tapes"))) {
            files.map(file -> file.getFileName().toString()).filter(name -> name.endsWith(".tape")).forEach(present::add);
        }
        assertThat(present).as("tapes whose command is gone or excluded: delete them, and the generator deletes "
                + "their goldens and GIFs").isSubsetOf(expected);
    }

    @Test
    void theExclusionsNameRealCommands() {
        Set<String> commands = topLevelCommands();
        assertThat(commands).as("an exclusion that names no command any more").containsAll(NOT_AN_INTEGRATION.keySet());
        assertThat(commands).as("a CLI-only integration that names no command any more").containsAll(CLI_ONLY.keySet());
        assertThat(NOT_AN_INTEGRATION.keySet()).doesNotContainAnyElementsOf(CLI_ONLY.keySet());
    }

    @Test
    void theHookTheGeneratorSourcesIsThere() {
        assertThat(repositoryRoot().resolve("tapes/prepare.sh")).isRegularFile();
    }

    static Set<String> integrations() {
        Set<String> integrations = new TreeSet<>(topLevelCommands());
        integrations.removeAll(NOT_AN_INTEGRATION.keySet());
        return integrations;
    }

    static List<String> tapesOf(String integration) {
        return CLI_ONLY.containsKey(integration)
                ? List.of(integration + "-cli.tape")
                : List.of(integration + "-cli.tape", integration + "-tui.tape");
    }

    /**
     * The binary's tree: the library's commands with {@code qits tui} in its place, hidden ones
     * ({@code help}) included, so a hidden command is excluded by name like any other, not by
     * accident.
     */
    static Set<String> topLevelCommands() {
        CommandLine tree = QitsCommandLine.withTui(new CommandLine(new AccessCli()));
        Set<String> names = new TreeSet<>();
        tree.getSubcommands().forEach((name, command) -> {
            if (name.equals(command.getCommandName())) {
                names.add(name);
            }
        });
        return names;
    }

    /**
     * The repository root: the nearest directory upwards from the working directory that holds
     * {@code .config/qits/release.yml}. Surefire runs in this module's basedir, but the tapes belong
     * to the repository, as SKILL.md does, and a search does not care which module runs it.
     */
    static Path repositoryRoot() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve(".config/qits/release.yml")) && Files.isRegularFile(dir.resolve("mvnw"))) {
                return dir;
            }
        }
        throw new IllegalStateException("no repository root above " + Path.of("").toAbsolutePath());
    }
}
