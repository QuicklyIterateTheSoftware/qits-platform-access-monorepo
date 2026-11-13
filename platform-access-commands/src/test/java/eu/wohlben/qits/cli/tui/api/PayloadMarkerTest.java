package eu.wohlben.qits.cli.tui.api;

import eu.wohlben.qits.cli.access.AccessCli;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @TuiCommand(input = PAYLOAD)} is on exactly the commands that read a payload on stdin, so
 * something that runs the commands without a terminal can trust the marker instead of a list of
 * names. A command that starts reading one and forgets the marker fails here, and so does a marker
 * left on a command that stopped. {@code input = TEXT} is held the same way, to the commands that
 * read a plain value through {@code StdinValue.read}.
 * <p>
 * Read from the sources, as the TUI's own guard is: Surefire runs in the module's basedir, so each
 * leaf's class is found under {@code src/main/java} and its body cut out by its braces.
 */
class PayloadMarkerTest {

    private static final Pattern READS_A_PAYLOAD = Pattern.compile("\\bWorkPayload\\.read\\(");
    private static final Pattern READS_A_VALUE = Pattern.compile("\\bStdinValue\\.read\\(");

    @Test
    void exactlyTheLeavesThatReadAPayloadAreMarked() throws IOException {
        Map<String, Boolean> marked = new TreeMap<>();
        Map<String, Boolean> reads = new TreeMap<>();
        walk(new CommandLine(new AccessCli()).getCommandSpec(), "", marked, reads);

        assertThat(marked).as("every leaf was read").hasSameSizeAs(reads).hasSizeGreaterThan(20);
        assertThat(marked).isEqualTo(reads);
        assertThat(reads.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey)).containsExactlyInAnyOrder(
                "work create", "work update", "work transition", "work status",
                "work comment create", "work comment update", "work dossier put", "work design put",
                "ideas create", "ideas update", "ideas spawn", "pitches create", "pitches add-version");
    }

    @Test
    void exactlyTheLeavesThatReadAValueAreMarkedText() throws IOException {
        Map<String, Input> marked = new TreeMap<>();
        Map<String, Boolean> reads = new TreeMap<>();
        walkText(new CommandLine(new AccessCli()).getCommandSpec(), "", marked, reads);

        Map<String, Boolean> markedText = new TreeMap<>();
        marked.forEach((path, input) -> markedText.put(path, input == Input.TEXT));
        assertThat(markedText).isEqualTo(reads);
        assertThat(reads.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey)).containsExactlyInAnyOrder(
                "deployments settings set", "agent-mcp credentials set");
    }

    private static void walkText(CommandLine.Model.CommandSpec spec, String path,
                                 Map<String, Input> marked, Map<String, Boolean> reads) throws IOException {
        if (spec.subcommands().isEmpty()) {
            Object command = spec.userObject();
            marked.put(path, TuiCommands.inputOf(command));
            reads.put(path, READS_A_VALUE.matcher(body(command.getClass())).find());
            return;
        }
        for (CommandLine child : spec.subcommands().values().stream().distinct().toList()) {
            String name = child.getCommandSpec().name();
            walkText(child.getCommandSpec(), path.isEmpty() ? name : path + " " + name, marked, reads);
        }
    }

    private static void walk(CommandLine.Model.CommandSpec spec, String path,
                             Map<String, Boolean> marked, Map<String, Boolean> reads) throws IOException {
        if (spec.subcommands().isEmpty()) {
            Object command = spec.userObject();
            marked.put(path, TuiCommands.inputOf(command) == Input.PAYLOAD);
            reads.put(path, READS_A_PAYLOAD.matcher(body(command.getClass())).find());
            return;
        }
        for (CommandLine child : spec.subcommands().values().stream().distinct().toList()) {
            String name = child.getCommandSpec().name();
            walk(child.getCommandSpec(), path.isEmpty() ? name : path + " " + name, marked, reads);
        }
    }

    /** The source of one class, nested ones included: from its declaration to its closing brace. */
    static String body(Class<?> type) throws IOException {
        Class<?> top = type;
        while (top.getEnclosingClass() != null) {
            top = top.getEnclosingClass();
        }
        Path file = Path.of("src/main/java", top.getName().replace('.', '/') + ".java");
        String source = Files.readString(file, StandardCharsets.UTF_8);
        Matcher declaration = Pattern.compile("\\bclass\\s+" + type.getSimpleName() + "\\b").matcher(source);
        assertThat(declaration.find()).as("the declaration of " + type.getName() + " in " + file).isTrue();
        int open = source.indexOf('{', declaration.end());
        return source.substring(open, closing(source, open) + 1);
    }

    /** The brace that closes the one at {@code open}, skipping strings, text blocks, chars and comments. */
    private static int closing(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (source.startsWith("\"\"\"", i)) {
                i = source.indexOf("\"\"\"", i + 3) + 2;
            } else if (c == '"' || c == '\'') {
                for (i++; source.charAt(i) != c; i++) {
                    if (source.charAt(i) == '\\') {
                        i++;
                    }
                }
            } else if (source.startsWith("//", i)) {
                i = source.indexOf('\n', i);
            } else if (source.startsWith("/*", i)) {
                i = source.indexOf("*/", i) + 1;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        throw new AssertionError("unbalanced braces");
    }
}
