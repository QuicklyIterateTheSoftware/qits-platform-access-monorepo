package eu.wohlben.qits.cli.tui;

import eu.wohlben.qits.cli.access.AccessCli;
import eu.wohlben.qits.cli.access.QitsCommandLine;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.model.CommandNode;
import eu.wohlben.qits.cli.tui.run.CommandRunner;
import eu.wohlben.qits.cli.tui.screen.Frame;
import eu.wohlben.qits.cli.tui.screen.Glyphs;
import eu.wohlben.qits.cli.tui.screen.Key;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The epic's "done when": a command that exists nowhere but this file appears in the TUI, with its
 * required option marked, and no line of TUI source names it.
 * <p>
 * The second test is what keeps that true later. A screen that starts treating one command
 * specially stops being a screen over the CLI and becomes a second place where commands are
 * described — which is exactly what this epic set out not to build.
 */
class FictionalCommandTest {

    @eu.wohlben.qits.cli.tui.api.TuiCommand(interaction = Interaction.PLAIN)
    @CommandLine.Command(name = "fictional", description = "Not a real command.")
    static class FictionalCommand {

        @CommandLine.Option(names = "--thing", required = true, description = "The thing it needs.")
        String thing;
    }

    /** The same, declared as only meaning something on the caller's machine: the screen does not care. */
    @eu.wohlben.qits.cli.tui.api.TuiCommand(interaction = Interaction.LOCAL)
    @CommandLine.Command(name = "nearby", description = "Not a real command either.")
    static class NearbyCommand {

        @CommandLine.Option(names = "--thing", required = true, description = "The thing it needs.")
        String thing;
    }

    @CommandLine.Command(name = "qits", description = "The root.",
            subcommands = {FictionalCommand.class, NearbyCommand.class})
    static class Root {
    }

    private final CommandNode root = CommandNode.of(new CommandLine(new Root()).getCommandSpec());
    private final TuiApp app = new TuiApp(root, "qits tui", new CommandRunner("/bin/true"));
    private final Frame frame = new Frame(Glyphs.UNICODE);

    private List<String> lines() {
        return frame.render(app.view(), 80, 24).stream().map(AttributedString::toString).toList();
    }

    @Test
    void aCommandThisTestInventedAppearsInTheTuiWithNoTuiChange() {
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("fictional")
                .contains("Not a real command."));

        app.key(Key.of(Key.Kind.ENTER));
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("* --thing"));
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("$ qits fictional"));

        app.key(Key.of(Key.Kind.ENTER));
        "a value".chars().forEach(ch -> app.key(Key.character((char) ch)));
        app.key(Key.of(Key.Kind.ENTER));
        assertThat(app.selection().commandLine()).isEqualTo("qits fictional --thing 'a value'");
        assertThat(app.selection().argv()).containsExactly("fictional", "--thing", "a value");
    }

    @Test
    void aLocalCommandThisTestInventedAppearsAndRunsLikeAnyOther() {
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("nearby")
                .contains("Not a real command either."));

        app.key(Key.of(Key.Kind.DOWN));
        app.key(Key.of(Key.Kind.ENTER));
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("* --thing"));
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("$ qits nearby"));

        app.key(Key.of(Key.Kind.ENTER));
        "here".chars().forEach(ch -> app.key(Key.character((char) ch)));
        app.key(Key.of(Key.Kind.ENTER));
        assertThat(app.selection().argv()).containsExactly("nearby", "--thing", "here");
        app.key(Key.of(Key.Kind.CTRL_R));
        assertThat(app.history().entries()).singleElement()
                .satisfies(entry -> assertThat(entry.commandLine()).isEqualTo("qits nearby --thing here"));
    }

    /**
     * Ordinary words that happen also to be command names. The screen's own prose uses them —
     * {@code ⌃R run}, {@code list}, {@code help}, {@code run outside CI?} — and forbidding them
     * would forbid English rather than command knowledge. {@code ci} is here for the same reason:
     * in {@code run outside CI?} it is continuous integration, not the command. {@code start},
     * {@code stop} and {@code history} joined with {@code qits workspaces} (qits-1261): the screen's
     * {@code ⌃C stop}, {@code cannot start} and its run history are English, not those commands.
     * {@code up} joined with {@code qits network-access} (epic qits-1977): the key hint {@code up/dn}
     * is the arrow key, not that command.
     */
    private static final Set<String> ORDINARY = Set.of(
            "qits", "tui", "run", "runs", "list", "new", "update", "create", "details", "comment",
            "plan", "submit", "join", "help", "exists", "docs", "daemon", "skill", "show", "ci", "move",
            "sources", "start", "stop", "history", "up");

    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z0-9-]*");

    @Test
    void noTuiSourceFileNamesAConcreteCommand() throws IOException {
        Set<String> forbidden = new TreeSet<>(commandNames());
        forbidden.removeAll(ORDINARY);
        assertThat(forbidden).contains("observe", "release-request", "git-login", "artifacts");

        List<String> offences = new ArrayList<>();
        for (Path source : tuiSources()) {
            // The `qits tui` command's own declaration names itself, which it has to.
            if (source.getFileName().toString().equals("TuiCommand.java")) {
                continue;
            }
            Matcher literals = LITERAL.matcher(Files.readString(source, StandardCharsets.UTF_8));
            while (literals.find()) {
                Matcher words = WORD.matcher(literals.group(1).toLowerCase(Locale.ROOT));
                while (words.find()) {
                    if (forbidden.contains(words.group())) {
                        offences.add(source.getFileName() + ": \"" + literals.group(1) + "\"");
                    }
                }
            }
        }
        assertThat(offences)
                .as("a TUI source naming a command is a TUI that is no longer generic")
                .isEmpty();
    }

    /**
     * The screen's sources in both modules: this one's, and tui/api and tui/model, which live in
     * qits-platform-access-commands beside the commands they describe. Surefire runs in the module's
     * basedir, so the library is one directory over.
     */
    private static List<Path> tuiSources() throws IOException {
        List<Path> sources = new ArrayList<>();
        for (String root : List.of("src/main/java/eu/wohlben/qits/cli/tui",
                "../platform-access-commands/src/main/java/eu/wohlben/qits/cli/tui")) {
            try (Stream<Path> tree = Files.walk(Path.of(root))) {
                tree.filter(path -> path.toString().endsWith(".java")).forEach(sources::add);
            }
        }
        return sources.stream().sorted().toList();
    }

    /** Every command name the CLI has, the root's own included. */
    private static Set<String> commandNames() {
        Set<String> names = new LinkedHashSet<>();
        collect(QitsCommandLine.withTui(new CommandLine(new AccessCli())).getCommandSpec(), names);
        return names;
    }

    private static void collect(CommandLine.Model.CommandSpec spec, Set<String> names) {
        names.add(spec.name());
        for (CommandLine child : spec.subcommands().values()) {
            collect(child.getCommandSpec(), names);
        }
    }
}
