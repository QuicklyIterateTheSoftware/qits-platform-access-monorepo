package eu.wohlben.qits.cli.access.update;

import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.session.Mode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The automatic update's foreground half: when it starts {@code qits update --background}, and
 * that it otherwise does nothing at all.
 */
class AutoUpdateTest {

    private static final Instant T0 = Instant.parse("2026-10-15T10:00:00Z");

    @TempDir
    Path config;

    private FakeTime time;
    private final Map<String, String> env = new HashMap<>();
    private final List<List<String>> started = new ArrayList<>();
    private Optional<Path> binary;
    private ByteArrayOutputStream err;

    @BeforeEach
    void setUp() throws IOException {
        time = new FakeTime(T0);
        binary = Optional.of(Path.of("/home/someone/.local/bin/qits"));
        Files.writeString(config.resolve("t.json"), "{}");
    }

    private String before(String... args) {
        err = new ByteArrayOutputStream();
        new AutoUpdate(Map.copyOf(env), time, config, () -> binary, started::add)
                .before(args, new PrintStream(err, true, StandardCharsets.UTF_8));
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aSignedInWorkstationStartsOneBackgroundCheckAnHour() {
        assertThat(before("projects", "list")).isEmpty();
        assertThat(started).containsExactly(List.of("update", "--background"));
        assertThat(UpdateState.read(config).checkedAt()).isEqualTo(T0);

        time.jump(Duration.ofMinutes(59));
        before("work", "list");
        assertThat(started).as("not again within the hour").hasSize(1);

        time.jump(Duration.ofMinutes(1));
        before("work", "list");
        assertThat(started).hasSize(2);
        assertThat(UpdateState.read(config).checkedAt()).isEqualTo(T0.plus(Duration.ofHours(1)));
    }

    @Test
    void aCheckTimeInTheFutureIsDueSoASetBackClockDoesNotStopIt() {
        assertThat(AutoUpdate.due(null, T0)).isTrue();
        assertThat(AutoUpdate.due(T0, T0.plus(Duration.ofMinutes(30)))).isFalse();
        assertThat(AutoUpdate.due(T0, T0.plus(Duration.ofHours(1)))).isTrue();
        assertThat(AutoUpdate.due(T0.plus(Duration.ofDays(1)), T0)).isTrue();
    }

    @Test
    void theProtocolCommandsHelpAndVersionNeverStartOne() {
        for (String[] args : List.of(new String[] {"update"}, new String[] {"update", "--background"},
                new String[] {"session-daemon"}, new String[] {"checkout-daemon", "--path", "/x"},
                new String[] {"git-credential", "get"}, new String[] {"docker-credential", "get"},
                new String[] {"agents", "claude", "hook", "work-links"},
                new String[] {"agents", "claude", "statusline", "work-items"}, new String[] {"help", "skill"},
                new String[] {"--help"}, new String[] {"-V"}, new String[] {"--version"},
                new String[] {"projects", "list", "--help"}, new String[] {"work", "-h"}, new String[] {})) {
            before(args);
        }
        assertThat(started).isEmpty();
        assertThat(config.resolve(UpdateState.FILE_NAME)).as("not even the file is written").doesNotExist();
    }

    @Test
    void outsideAWorkstationNothingStarts() {
        env.put(Mode.OVERRIDE, "true");
        before("projects", "list");
        env.clear();
        env.put(Mode.TOKEN, "a workspace token");
        before("projects", "list");
        env.clear();
        env.put(CliContext.MCP_SERVICE, "true");
        before("projects", "list");

        assertThat(started).isEmpty();
    }

    @Test
    void theVariableTurnsItOff() {
        for (String off : List.of("false", "0", "off", "no", "FALSE", " Off ")) {
            env.put(AutoUpdate.DISABLE, off);
            before("projects", "list");
        }
        assertThat(started).isEmpty();

        env.put(AutoUpdate.DISABLE, "true");
        before("projects", "list");
        assertThat(started).hasSize(1);
    }

    @Test
    void withoutASessionNothingStarts() throws IOException {
        Files.delete(config.resolve("t.json"));

        before("projects", "list");

        assertThat(started).isEmpty();
    }

    @Test
    void aJvmIsNotTheBinaryAndStartsNothing() {
        binary = Optional.empty();

        before("projects", "list");

        assertThat(started).isEmpty();
    }

    @Test
    void theLastBackgroundFailureIsPrintedOnceOnOneLine() throws IOException {
        new UpdateState(T0, "qits could not update itself: the store refused. Run `qits update` to try again.")
                .write(config);

        assertThat(before("projects", "list"))
                .isEqualTo("qits could not update itself: the store refused. Run `qits update` to try again."
                        + System.lineSeparator());
        assertThat(started).as("checked at T0, so not due").isEmpty();
        assertThat(before("projects", "list")).as("once").isEmpty();
        assertThat(UpdateState.read(config)).isEqualTo(new UpdateState(T0, null));
    }

    @Test
    void aProtocolCommandDoesNotPrintTheFailure() throws IOException {
        new UpdateState(T0, "qits could not update itself.").write(config);

        assertThat(before("git-credential", "get")).isEmpty();
        assertThat(UpdateState.read(config).failure()).isNotNull();
    }

    @Test
    void anUnreadableStateFileIsNoStateAndBreaksNothing() throws IOException {
        Files.writeString(config.resolve(UpdateState.FILE_NAME), "not json");

        assertThat(before("projects", "list")).isEmpty();
        assertThat(started).hasSize(1);
    }

    @Test
    void theStateFileIsPrivate() throws IOException {
        before("projects", "list");

        assertThat(java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(config.resolve(UpdateState.FILE_NAME)))).isEqualTo("rw-------");
    }
}
