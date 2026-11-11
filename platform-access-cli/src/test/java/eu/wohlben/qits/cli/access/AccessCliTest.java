package eu.wohlben.qits.cli.access;

import eu.wohlben.qits.cli.access.update.QitsVersion;
import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The command tree, through Quarkus. This is also the test that augments the application, so a
 * build-time break fails `clean verify` although the JVM build skips Quarkus' build goal.
 */
@QuarkusMainTest
class AccessCliTest {

    @Test
    @Launch("--help")
    void theHelpNamesEveryCommand(LaunchResult result) {
        assertThat(result.getOutput()).contains("qits").contains("login").contains("session-daemon")
                .contains("projects").contains("repositories").contains("release-request").contains("events")
                .contains("observe").contains("git-login").contains("git-credential").contains("docker-credential")
                .doesNotContain("mcp-credential").contains("users")
                .contains("The two exceptions are `qits git-credential get`,")
                .contains("`qits docker-credential get`, which Docker runs")
                .contains("Platform rules:").contains("Exit codes:");
        assertThat(result.getOutputStream()).as("help is for agents and stays hidden")
                .noneMatch(line -> line.strip().startsWith("help "));
    }

    @Test
    @Launch({"-V"})
    void dashVPrintsTheBuiltInVersion(LaunchResult result) {
        assertThat(result.getOutput().strip()).isEqualTo(QitsVersion.current().orElseThrow());
    }

    @Test
    @Launch({"projects", "list", "-V"})
    void aSubcommandPrintsTheSameVersion(LaunchResult result) {
        assertThat(result.getOutput().strip()).isEqualTo(QitsVersion.current().orElseThrow());
    }

    @Test
    @Launch({"help", "skill"})
    void helpSkillPrintsTheSkill(LaunchResult result) {
        assertThat(result.getOutput()).startsWith("---\nname: qits\n").contains("## qits observe")
                .contains("## qits release-request join");
    }

    @Test
    @Launch({"observe", "--help"})
    void observeHasItsOptions(LaunchResult result) {
        assertThat(result.getOutput()).contains("--filter").contains("--observability-url").contains("--output")
                .contains("level>=V").contains("attr.<key>");
    }

    @Test
    @Launch(value = {"observe"}, exitCode = 2)
    void observeWithoutAFilterIsAUsageError(LaunchResult result) {
        // The command refuses it now, not picocli (a parent's required option would bind `observe
        // query` too), and a command writes to file descriptor 2, which this harness does not
        // capture. ObserveCommandTest pins the message, word for word; here the exit code is the proof.
        assertThat(result.exitCode()).isEqualTo(2);
    }

    @Test
    @Launch({"checkout-daemon", "--help"})
    void checkoutDaemonHasItsOptionsAndSaysWhatItFollows(LaunchResult result) {
        assertThat(result.getOutput()).contains("--path").contains("--repository").contains("--once")
                .contains("--[no-]submodules").contains("--events-url")
                .contains("detached").contains("gitlink");
    }

    @Test
    @Launch({"git-login", "--help"})
    void gitLoginHasItsOptions(LaunchResult result) {
        assertThat(result.getOutput()).contains("--idp-url").contains("--git-host")
                .contains("--timeout").contains("--no-browser").contains("--configure")
                .doesNotContain("--audience");
    }

    @Test
    @Launch({"release-request", "create", "--help"})
    void createHasItsOptionsAndTheInheritedOnes(LaunchResult result) {
        assertThat(result.getOutput()).contains("--branch").contains("--priority")
                .contains("--project").contains("--repository").contains("--output");
        assertThat(result.getOutput()).as("the platform names a request by its items (qits-1673)")
                .doesNotContain("--summary");
    }

    @Test
    @Launch({"repositories", "create", "--help"})
    void repositoriesCreateHasItsOptionsIncludingArchetype(LaunchResult result) {
        assertThat(result.getOutput()).contains("<name>").contains("--component").contains("--project")
                .contains("--output").contains("--archetype");
        assertThat(result.getOutput()).as("the name no longer decides the kind")
                .doesNotContain("the service reads the archetype off the name");
    }

    @Test
    @Launch({"ci", "--help"})
    void ciNamesItsCommands(LaunchResult result) {
        assertThat(result.getOutput()).contains("runs").contains("run").contains("retry")
                .contains("release/<request id>");
    }

    @Test
    @Launch({"ci", "runs", "--help"})
    void ciRunsHasItsOptionsAndTheInheritedOnes(LaunchResult result) {
        assertThat(result.getOutput()).contains("--branch").contains("--status").contains("--release-request")
                .contains("--limit").contains("--project").contains("--repository").contains("--ci-url")
                .contains("--projects-url").contains("--output");
    }

    @Test
    @Launch(value = {"projects"}, exitCode = 2)
    void aGroupWithoutASubcommandIsAUsageError(LaunchResult result) {
        assertThat(result.getErrorOutput()).contains("Name a command");
    }

    @Test
    @Launch(value = {}, exitCode = 2)
    void noCommandIsAUsageError(LaunchResult result) {
        assertThat(result.getErrorOutput()).contains("Name a command");
    }

    @Test
    @Launch(value = {"session-daemon", "--margin", "900"}, exitCode = 2)
    void aMarginNearTheTokenLifetimeIsRefused(LaunchResult result) {
        assertThat(result.getErrorOutput()).contains("--margin");
    }

    @Test
    @Launch({"login", "--help"})
    void loginHasItsOptions(LaunchResult result) {
        assertThat(result.getOutput()).contains("--idp-url").contains("--no-browser");
    }
}
