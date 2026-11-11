package eu.wohlben.qits.cli.tui;

import eu.wohlben.qits.cli.access.AccessCli;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.Output;
import eu.wohlben.qits.cli.tui.model.CommandNode;
import eu.wohlben.qits.cli.tui.run.CommandRunner;
import eu.wohlben.qits.cli.tui.screen.Frame;
import eu.wohlben.qits.cli.tui.screen.Glyphs;
import eu.wohlben.qits.cli.tui.screen.Key;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/** What {@code @TuiCommand} makes the screen do, and nothing more than that. */
class InteractionClassesTest {

    @eu.wohlben.qits.cli.tui.api.TuiCommand(interaction = Interaction.STREAMING)
    @CommandLine.Command(name = "flowing", description = "Runs until stopped.")
    static class Flowing {
    }

    @eu.wohlben.qits.cli.tui.api.TuiCommand(interaction = Interaction.BROWSER)
    @CommandLine.Command(name = "opener", description = "Opens a browser.")
    static class Opener {
    }

    @eu.wohlben.qits.cli.tui.api.TuiCommand(interaction = Interaction.CI_ONLY)
    @CommandLine.Command(name = "instep", description = "Only in a CI step.")
    static class InStep {
    }

    @eu.wohlben.qits.cli.tui.api.TuiCommand(interaction = Interaction.LOCAL)
    @CommandLine.Command(name = "beside", description = "Answers a program on this machine.")
    static class Beside {
    }

    @eu.wohlben.qits.cli.tui.api.TuiCommand(output = Output.JSON)
    @CommandLine.Command(name = "asjson", description = "Answers as JSON.")
    static class AsJson {

        @CommandLine.Option(names = "--output", description = "table or json.")
        String output;
    }

    @CommandLine.Command(name = "quiet", description = "Says nothing about itself.")
    static class Quiet {
    }

    @CommandLine.Command(name = "past", description = "Asks what already happened.")
    static class Past {

        @CommandLine.Option(names = "--since", description = "How far back.")
        String since;
    }

    /** Runs on its own, and has a command under it: a stream and its query. */
    @eu.wohlben.qits.cli.tui.api.TuiCommand(interaction = Interaction.STREAMING)
    @CommandLine.Command(name = "tap", description = "Streams, with its past below.", subcommands = Past.class)
    static class Tap implements Callable<Integer> {

        @CommandLine.Option(names = "--filter", description = "Which.")
        String filter;

        @Override
        public Integer call() {
            return 0;
        }
    }

    /** Only leads somewhere: run bare, a group refuses. */
    @CommandLine.Command(name = "bunch", description = "Holds commands.", subcommands = Quiet.class)
    static class Bunch implements Runnable {

        @Override
        public void run() {
            throw new IllegalStateException("Name a command.");
        }
    }

    @CommandLine.Command(name = "qits", description = "The root.",
            subcommands = {Flowing.class, Opener.class, InStep.class, AsJson.class, Quiet.class, Beside.class,
                    Tap.class, Bunch.class})
    static class Root {
    }

    private final CommandNode root = CommandNode.of(new CommandLine(new Root()).getCommandSpec());
    /**
     * The child waits until it is stopped. A child that exits at once (`/bin/true`) races the
     * title check: on a fast machine it has already finished, and the title reads its exit code.
     */
    private static final Path WAITING_CHILD = waitingChild();

    private final CommandRunner runner = new CommandRunner(WAITING_CHILD.toString());

    private static Path waitingChild() {
        try {
            Path script = Files.createTempFile("qits-tui-child", ".sh",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Files.writeString(script, "#!/bin/sh\nexec sleep 30\n");
            script.toFile().deleteOnExit();
            return script;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @AfterEach
    void stopTheChild() {
        runner.stop();
    }
    private final TuiApp app = new TuiApp(root, "qits tui", runner);
    private final Frame frame = new Frame(Glyphs.UNICODE);

    private List<String> lines() {
        return frame.render(app.view(), 80, 24).stream().map(AttributedString::toString).toList();
    }

    private void enter(String name) {
        while (app.selection().depth() > 0) {
            app.key(Key.of(Key.Kind.ESCAPE));
        }
        for (int i = 0; i < 20; i++) {
            if (lines().stream().anyMatch(line -> line.contains(name) && line.contains("▸"))) {
                app.key(Key.of(Key.Kind.ENTER));
                return;
            }
            app.key(Key.of(Key.Kind.DOWN));
        }
        throw new AssertionError("no row " + name);
    }

    /** Down to the row named {@code name} in the list now showing, without pressing anything else. */
    private void moveTo(String name) {
        for (int i = 0; i < 20; i++) {
            if (app.view().rows().get(app.view().selected()).name().equals(name)) {
                return;
            }
            app.key(Key.of(Key.Kind.DOWN));
        }
        throw new AssertionError("no row " + name);
    }

    /**
     * A parent that runs on its own still runs from the screen, as it did while it had no command
     * under it, and the command under it can still be walked into.
     */
    @Test
    void aRunnableParentRunsAndItsCommandsCanStillBeBrowsed() {
        assertThat(root.child("tap").leaf()).isFalse();
        assertThat(root.child("tap").runnable()).isTrue();
        enter("tap");
        List<String> names = app.view().rows().stream().map(row -> row.name()).toList();
        assertThat(names).containsExactly("past", "--filter");

        moveTo("--filter");
        app.key(Key.of(Key.Kind.ENTER));
        "x".chars().forEach(ch -> app.key(Key.character((char) ch)));
        app.key(Key.of(Key.Kind.ENTER));
        assertThat(app.selection().commandLine()).isEqualTo("qits tap --filter x");

        app.key(Key.of(Key.Kind.CTRL_R));
        assertThat(runner.title()).isEqualTo("streaming");
        assertThat(app.history().entries()).singleElement()
                .satisfies(entry -> assertThat(entry.commandLine()).isEqualTo("qits tap --filter x"));

        app.key(Key.of(Key.Kind.CTRL_C));
        moveTo("past");
        app.key(Key.of(Key.Kind.ENTER));
        assertThat(app.selection().pathLine("/")).isEqualTo("tap/past");
        assertThat(app.view().rows()).extracting(row -> row.name()).containsExactly("--since");
        app.key(Key.of(Key.Kind.CTRL_R));
        assertThat(runner.title()).isNotEqualTo("streaming");
        assertThat(app.history().entries().getFirst().commandLine()).isEqualTo("qits tap past");
    }

    /** A group shows only its commands, and ⌃R on it runs nothing. */
    @Test
    void aGroupThatOnlyLeadsSomewhereIsNotRun() {
        assertThat(root.child("bunch").runnable()).isFalse();
        assertThat(root.runnable()).isFalse();
        enter("bunch");
        assertThat(app.view().rows()).extracting(row -> row.name()).containsExactly("quiet");
        app.key(Key.of(Key.Kind.CTRL_R));
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("pick a command first"));
        assertThat(app.history().entries()).isEmpty();
    }

    @Test
    void anUnannotatedCommandIsPlainTextAndStillWorks() {
        assertThat(root.child("quiet").interaction()).isEqualTo(Interaction.PLAIN);
        assertThat(root.child("quiet").output()).isEqualTo(Output.TEXT);
        enter("quiet");
        app.key(Key.of(Key.Kind.CTRL_R));
        assertThat(app.history().entries()).singleElement()
                .satisfies(entry -> assertThat(entry.commandLine()).isEqualTo("qits quiet"));
    }

    /** The screen is on the caller's machine, so a command that only means something there is plain. */
    @Test
    void aLocalCommandIsTreatedExactlyLikeAPlainOne() {
        assertThat(root.child("beside").interaction()).isEqualTo(Interaction.LOCAL);
        List<String> names = app.view().rows().stream().map(row -> row.name()).toList();
        assertThat(names.indexOf("beside")).as("not sorted last like a CI-only command")
                .isLessThan(names.indexOf("instep"));
        assertThat(app.view().rows().stream().filter(row -> row.name().equals("beside")))
                .singleElement().satisfies(row -> assertThat(row.dim()).isFalse());

        enter("beside");
        app.key(Key.of(Key.Kind.CTRL_R));
        assertThat(runner.title()).isNotEqualTo("streaming");
        assertThat(lines()).noneSatisfy(line -> assertThat(line).contains("y/n"));
        assertThat(app.history().entries()).singleElement()
                .satisfies(entry -> assertThat(entry.commandLine()).isEqualTo("qits beside"));
    }

    @Test
    void aStreamingCommandSaysSoAndOffersTheStopKey() {
        enter("flowing");
        app.key(Key.of(Key.Kind.CTRL_R));
        assertThat(runner.title()).isEqualTo("streaming");
    }

    @Test
    void aBrowserCommandSaysItOpensOneAndStillRuns() {
        enter("opener");
        app.key(Key.of(Key.Kind.CTRL_R));
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("this opens a browser"));
        assertThat(app.history().entries()).hasSize(1);
    }

    @Test
    void aCiOnlyCommandIsSortedLastAndAsksBeforeItRuns() {
        List<String> names = app.view().rows().stream().map(row -> row.name()).toList();
        assertThat(names).endsWith("instep");
        assertThat(app.view().rows().getLast().dim()).isTrue();

        enter("instep");
        app.key(Key.of(Key.Kind.CTRL_R));
        assertThat(lines()).anySatisfy(line -> assertThat(line).contains("run outside CI? y/n"));
        assertThat(app.history().entries()).isEmpty();

        app.key(Key.character('n'));
        assertThat(app.history().entries()).isEmpty();

        app.key(Key.of(Key.Kind.CTRL_R));
        app.key(Key.character('y'));
        assertThat(app.history().entries()).singleElement()
                .satisfies(entry -> assertThat(entry.commandLine()).isEqualTo("qits instep"));
    }

    @Test
    void anOutputFormIsPutOnTheCommandsOwnOutputOption() {
        enter("asjson");
        assertThat(app.selection().commandLine()).isEqualTo("qits asjson --output json");
    }

    @Test
    void theRealCliDeclaresTheClassesTheEpicNames() {
        CommandNode qits = CommandNode.of(new CommandLine(new AccessCli()).getCommandSpec());
        assertThat(qits.child("events").interaction()).isEqualTo(Interaction.STREAMING);
        assertThat(qits.child("observe").interaction()).isEqualTo(Interaction.STREAMING);
        assertThat(qits.child("session-daemon").interaction()).isEqualTo(Interaction.STREAMING);
        assertThat(qits.child("login").interaction()).isEqualTo(Interaction.BROWSER);
        assertThat(qits.child("git-login").interaction()).isEqualTo(Interaction.BROWSER);
        assertThat(qits.child("artifacts").child("publish").interaction()).isEqualTo(Interaction.CI_ONLY);
        assertThat(qits.child("ci").interaction()).isEqualTo(Interaction.PLAIN);
        assertThat(qits.child("git-credential").interaction()).isEqualTo(Interaction.LOCAL);
        assertThat(qits.child("docker-credential").interaction()).isEqualTo(Interaction.LOCAL);
        // `help` is hidden, so the screen's model leaves it out; the declaration is still read.
        Object skill = new CommandLine(new AccessCli()).getSubcommands().get("help").getSubcommands().get("skill")
                .getCommandSpec().userObject();
        assertThat(eu.wohlben.qits.cli.tui.api.TuiCommands.interactionOf(skill)).isEqualTo(Interaction.LOCAL);

        // The live streams run on their own and hold their query; `artifacts publish npm` publishes
        // and keeps its npm subcommands (epic qits-620); `wait` declares a wait bare and holds its
        // presets, list and cancel (qits-1153); `targets leases` lists bare and holds show and end
        // (qits-1621); every other parent only leads.
        List<String> runnableParents = new ArrayList<>();
        collectRunnableParents(qits, "", runnableParents);
        assertThat(runnableParents).containsExactlyInAnyOrder(
                "events", "observe", "wait", "artifacts publish npm", "targets leases");
        assertThat(qits.child("events").child("query").interaction()).isEqualTo(Interaction.PLAIN);
        assertThat(qits.child("observe").child("query").interaction()).isEqualTo(Interaction.PLAIN);
    }

    private static void collectRunnableParents(CommandNode node, String path, List<String> found) {
        for (CommandNode child : node.children()) {
            String at = path.isEmpty() ? child.name() : path + " " + child.name();
            if (!child.leaf() && child.runnable()) {
                found.add(at);
            }
            collectRunnableParents(child, at, found);
        }
    }
}
