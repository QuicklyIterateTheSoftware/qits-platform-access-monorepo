package eu.wohlben.qits.cli.access;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * vhs runs {@code SaveOutput} right after every recorded command, so a hidden {@code Wait} /
 * {@code Wait+Screen} / {@code Wait+Line} that matches mid-paint (the TUI paints top to bottom in
 * several chunks) is not proof the screen has finished: a {@code Wait+Screen} regex proves only
 * the row it matched, and vhs writes a text frame the instant {@code Show} runs. Every
 * {@code *-tui.tape} must therefore let the screen settle, still hidden, before it shows it: a
 * {@code Sleep} directly before every {@code Show}, with the tape still hidden at that point
 * (qits-2221). This test needs no vhs, no docker and no platform; it only reads the tape files.
 */
class TapesSettleTest {

    @Test
    void everyShowFollowsAHiddenSleep() throws IOException {
        Path tapes = TapesCoverageTest.repositoryRoot().resolve("tapes");
        List<String> tuiTapes = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        try (var files = Files.list(tapes)) {
            files.filter(file -> file.getFileName().toString().endsWith("-tui.tape"))
                    .sorted()
                    .forEach(tape -> {
                        tuiTapes.add(tape.getFileName().toString());
                        violations.addAll(violationsIn(tape));
                    });
        }
        assertThat(tuiTapes).as("no *-tui.tape found under tapes/").isNotEmpty();
        assertThat(violations).as("a Show must be directly preceded (ignoring blank/# lines) by a "
                + "Sleep while the tape is still hidden, so vhs never writes a frame mid-paint "
                + "(qits-2221)").isEmpty();
    }

    private static List<String> violationsIn(Path tape) {
        List<String> violations = new ArrayList<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(tape);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        boolean hidden = false;
        String lastMeaningful = null;
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.equals("Hide")) {
                hidden = true;
            } else if (trimmed.equals("Show")) {
                boolean settled = hidden && lastMeaningful != null && lastMeaningful.startsWith("Sleep");
                if (!settled) {
                    violations.add(tape.getFileName() + ":" + (i + 1));
                }
                hidden = false;
            }
            lastMeaningful = trimmed;
        }
        return violations;
    }
}
