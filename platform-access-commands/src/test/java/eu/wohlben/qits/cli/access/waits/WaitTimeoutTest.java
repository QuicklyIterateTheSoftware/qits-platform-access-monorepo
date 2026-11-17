package eu.wohlben.qits.cli.access.waits;

import eu.wohlben.qits.cli.access.platform.CliFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WaitTimeoutTest {

    @ParameterizedTest
    @CsvSource({"30m,PT30M", "2h,PT2H", "1h30m,PT1H30M", "1d,PT24H", "45s,PT45S", "PT2H,PT2H", "pt15m,PT15M",
            "P1D,PT24H", "2H,PT2H"})
    void readsShortAndIsoForms(String given, String iso) throws CliFailure {
        assertThat(WaitTimeout.iso(given)).isEqualTo(iso);
    }

    @Test
    void noneIsNull() throws CliFailure {
        assertThat(WaitTimeout.iso(null)).isNull();
        assertThat(WaitTimeout.iso(" ")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0m", "PT0S", "-PT1H", "2", "h", "2x", "2h 30m", "1.5h", "P", "soon", "m30"})
    void refusesWhatIsNotADuration(String given) {
        CliFailure refused = assertThrows(CliFailure.class, () -> WaitTimeout.iso(given));
        assertThat(refused.exitCode()).isEqualTo(CliFailure.USAGE);
        assertThat(refused.getMessage()).startsWith("--timeout");
    }

    @Test
    void aRefusalNamesTheOptionItWasGiven() throws CliFailure {
        assertThat(WaitTimeout.iso("2h", "--after")).isEqualTo("PT2H");
        CliFailure refused = assertThrows(CliFailure.class, () -> WaitTimeout.iso("0m", "--after"));
        assertThat(refused.exitCode()).isEqualTo(CliFailure.USAGE);
        assertThat(refused.getMessage()).startsWith("--after must be longer than nothing");
    }
}
