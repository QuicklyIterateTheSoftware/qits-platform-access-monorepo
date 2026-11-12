package eu.wohlben.qits.cli.access.registry;

import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakePlatform;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.AccessCli;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import picocli.CommandLine;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code qits registry-login} as a person types it, against a fake qits-artifacts and qits-mirror. */
class RegistryLoginCommandTest {

    private static final Instant T0 = Instant.parse("2026-10-27T10:00:00Z");

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakePlatform platform;
    private FakeTime time;
    private final Map<String, String> env = new HashMap<>();

    record Result(int exit, String out, String err) {
    }

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        platform = new FakePlatform();
        time = new FakeTime(T0);
        env.put("HOME", home.toString());
        env.put("XDG_CONFIG_HOME", home.resolve(".config").toString());
        env.put("QITS_ARTIFACTS_URL", platform.url());
        env.put("QITS_MIRROR_URL", platform.url());
        new SessionFile(home.resolve(".config/qits")).write(new Session(idp.url(), "qits-cli",
                FakeIdp.SECRET + "access-0", T0.plus(Duration.ofMinutes(10)), idp.issueRefreshToken(),
                T0.plus(Duration.ofDays(30))));
        platform.answer("GET", "/artifacts/api/repositories", """
                {"repositories":[{"name":"npm","type":"npm-packages"},{"name":"maven","type":"maven-packages"},
                 {"name":"qits","type":"oci-images"}]}""");
        platform.answer("GET", "/mirror/api/repositories", """
                {"repositories":[{"name":"central","type":"maven-proxy"},{"name":"npmjs","type":"npm-proxy"}]}""");
    }

    @AfterEach
    void stop() {
        platform.close();
        idp.close();
    }

    /** The qits binary the command believes it runs from; empty, as in any JVM, unless a test sets it. */
    private Optional<Path> binary = Optional.empty();

    private Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext context = new CliContext(Map.copyOf(env), InputStream.nullInputStream(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, stop -> { });
        CommandLine cli = new CommandLine(new AccessCli(), new CommandLine.IFactory() {
            @Override
            public <K> K create(Class<K> type) throws Exception {
                K made = CommandLine.defaultFactory().create(type);
                if (made instanceof PlatformCommand command) {
                    command.useContext(context);
                }
                if (made instanceof RegistryLoginCommand login) {
                    Optional<Path> self = binary;
                    login.binary = () -> self;
                }
                return made;
            }
        });
        cli.setOut(new PrintWriter(context.out(), true, StandardCharsets.UTF_8));
        cli.setErr(new PrintWriter(context.err(), true, StandardCharsets.UTF_8));
        int exit = cli.execute(args);
        Result result = new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        assertThat(result.out() + result.err()).doesNotContain(FakeIdp.SECRET);
        return result;
    }

    private String key() {
        return "//" + platform.url().substring("http://".length());
    }

    @Test
    void configureWritesTheRegistriesThePlatformLists() throws Exception {
        Result result = run("registry-login", "--configure");

        assertThat(result.exit()).isZero();
        assertThat(result.out()).contains("Updated " + home.resolve(".npmrc"))
                .contains("Updated " + home.resolve(".m2/settings.xml"));
        assertThat(Files.readString(home.resolve(".npmrc")))
                .contains(key() + "/artifacts/npm/npm/:_authToken=" + FakeIdp.SECRET)
                .contains(key() + "/npm/npmjs/:_authToken=" + FakeIdp.SECRET)
                .doesNotContain("central").doesNotContain("qits/");
        assertThat(Files.readString(home.resolve(".m2/settings.xml")))
                .contains("<url>" + platform.url() + "/artifacts/maven/maven</url>")
                .contains("Bearer " + FakeIdp.SECRET);
        assertThat(home.resolve(".config/qits/registries.json")).exists();
        assertThat(platform.requests("GET", "/artifacts/api/repositories").getFirst().authorization())
                .startsWith("Bearer " + FakeIdp.SECRET);
        // Ten minutes left is more than the five a registry needs: no refresh.
        assertThat(idp.grants("refresh_token")).isZero();
    }

    @Test
    void aShortLivedTokenIsRefreshedFirst() throws Exception {
        time.jump(Duration.ofMinutes(7));

        assertThat(run("registry-login", "--configure").exit()).isZero();

        assertThat(idp.grants("refresh_token")).isEqualTo(1);
    }

    @Test
    void anUnreadableListFallsBackToTheDefaults() throws Exception {
        platform.answer("GET", "/mirror/api/repositories", 500, "{}");

        Result result = run("registry-login", "--configure");

        assertThat(result.exit()).isZero();
        assertThat(result.err()).contains("using the default registries");
        assertThat(Files.readString(home.resolve(".npmrc"))).contains(key() + "/npm/npmjs/:_authToken=");
    }

    @Test
    void withoutConfigureItRefreshesTheSetUpFilesOnce() throws Exception {
        assertThat(run("registry-login").exit()).isEqualTo(2);

        run("registry-login", "--configure");
        Files.writeString(home.resolve(".npmrc"), Files.readString(home.resolve(".npmrc"))
                .replace(FakeIdp.SECRET + "access-0", FakeIdp.SECRET + "stale"));
        Result again = run("registry-login");

        assertThat(again.exit()).isZero();
        assertThat(again.out()).contains("Updated " + home.resolve(".npmrc"))
                .contains("Up to date: " + home.resolve(".m2/settings.xml"));
        assertThat(Files.readString(home.resolve(".npmrc"))).doesNotContain("stale");
        // The list is read only by --configure.
        assertThat(platform.requests("GET", "/artifacts/api/repositories")).hasSize(1);
    }

    @Test
    void aDryRunPrintsARedactedDiffAndWritesNothing() throws Exception {
        Files.writeString(home.resolve(".npmrc"), "//registry.dev.localhost:8080/artifacts/npm/npm/:_auth="
                + FakeIdp.SECRET + "basic\n");

        Result result = run("registry-login", "--configure", "--remove-stale", "--dry-run");

        assertThat(result.exit()).isZero();
        assertThat(result.out()).contains("--- " + home.resolve(".npmrc") + "\n+++ " + home.resolve(".npmrc"))
                .contains("-//registry.dev.localhost:8080/artifacts/npm/npm/:_auth=<token>")
                .contains("+" + key() + "/artifacts/npm/npm/:_authToken=<token>")
                .contains("--- " + home.resolve(".m2/settings.xml"));
        assertThat(Files.readString(home.resolve(".npmrc"))).contains("dev.localhost");
        assertThat(home.resolve(".m2")).doesNotExist();
        assertThat(home.resolve(".config/qits/registries.json")).doesNotExist();
    }

    @Test
    void aDryRunWithNothingToDoSaysSo() {
        run("registry-login", "--configure");

        Result result = run("registry-login", "--dry-run");

        assertThat(result.exit()).isZero();
        assertThat(result.out()).isEqualTo("No changes\n");
    }

    @Test
    void itIsAWorkstationCommand() {
        env.put("QITS_TOKEN", FakeIdp.SECRET + "workspace");

        Result result = run("registry-login", "--configure");

        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.err()).contains("for a workstation");
        assertThat(home.resolve(".npmrc")).doesNotExist();
    }

    @Test
    void pathFlagsNeedConfigure() {
        assertThat(run("registry-login", "--npmrc", "/tmp/x").exit()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- Docker

    private String registryHost() {
        return platform.url().substring("http://".length());
    }

    private Path dockerConfig() {
        return home.resolve(".docker/config.json");
    }

    @Test
    void configureCreatesTheDockerConfigWithTheHelper() throws Exception {
        Result result = run("registry-login", "--configure");

        assertThat(result.exit()).isZero();
        assertThat(Files.readString(dockerConfig())).isEqualTo(
                "{\n\t\"credHelpers\": {\n\t\t\"" + registryHost() + "\": \"qits\"\n\t}\n}\n");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dockerConfig()))).isEqualTo("rw-------");
        assertThat(result.out()).contains("Updated " + dockerConfig())
                .contains("Docker: " + registryHost() + " via docker-credential-qits");
        assertThat(Files.readString(home.resolve(".config/qits/registries.json")))
                .contains("\"dockerConfig\" : \"" + dockerConfig() + "\"");
        // Not the binary: no shim, and the person is told what to do.
        assertThat(result.err()).contains("docker-credential-qits was not written").contains("install.sh");
    }

    @Test
    void dockerConfigFollowsDockerConfigVariableAndTheFlag() throws Exception {
        env.put("DOCKER_CONFIG", home.resolve("dc").toString());
        assertThat(run("registry-login", "--configure").exit()).isZero();
        assertThat(home.resolve("dc/config.json")).exists();

        Path flagged = home.resolve("elsewhere/docker.json");
        assertThat(run("registry-login", "--configure", "--docker-config", flagged.toString()).exit()).isZero();
        assertThat(Files.readString(flagged)).contains("\"" + registryHost() + "\": \"qits\"");
    }

    @Test
    void anExistingDockerConfigKeepsEveryOtherEntryInItsOrder() throws Exception {
        Files.createDirectories(dockerConfig().getParent());
        Files.writeString(dockerConfig(), """
                {
                	"auths": {
                		"ghcr.io": {
                			"auth": "%sghcr"
                		}
                	},
                	"credsStore": "desktop",
                	"credHelpers": {
                		"gcr.io": "gcloud"
                	},
                	"currentContext": "colima",
                	"plugins": {"x": {"enabled": true, "list": [1, 2]}}
                }
                """.formatted(FakeIdp.SECRET));

        Result result = run("registry-login", "--configure");

        assertThat(result.exit()).isZero();
        assertThat(Files.readString(dockerConfig())).isEqualTo("""
                {
                	"auths": {
                		"ghcr.io": {
                			"auth": "%sghcr"
                		}
                	},
                	"credsStore": "desktop",
                	"credHelpers": {
                		"gcr.io": "gcloud",
                		"%s": "qits"
                	},
                	"currentContext": "colima",
                	"plugins": {
                		"x": {
                			"enabled": true,
                			"list": [
                				1,
                				2
                			]
                		}
                	}
                }
                """.formatted(FakeIdp.SECRET, registryHost()));
        assertThat(result.err()).doesNotContain("auths");

        // Already there: the file is not written again.
        Files.writeString(dockerConfig(), Files.readString(dockerConfig()).replace("\t\"currentContext\"",
                "  \"currentContext\""));
        String touched = Files.readString(dockerConfig());
        Result again = run("registry-login", "--configure");
        assertThat(again.out()).contains("Up to date: " + dockerConfig());
        assertThat(Files.readString(dockerConfig())).isEqualTo(touched);
    }

    @Test
    void aStoredLoginForTheRegistryIsKeptWithAWarning() throws Exception {
        Files.createDirectories(dockerConfig().getParent());
        Files.writeString(dockerConfig(), "{\"auths\":{\"" + registryHost() + "\":{\"auth\":\"" + FakeIdp.SECRET
                + "basic\"}}}");

        Result result = run("registry-login", "--configure");

        assertThat(result.exit()).isZero();
        assertThat(result.err()).contains("credHelpers takes precedence")
                .contains("`docker logout " + registryHost() + "`").contains("--remove-stale drops it");
        assertThat(Files.readString(dockerConfig())).contains(FakeIdp.SECRET + "basic")
                .contains("\"" + registryHost() + "\": \"qits\"");
    }

    @Test
    void removeStaleDropsTheStoredLoginForTheRegistry() throws Exception {
        Files.createDirectories(dockerConfig().getParent());
        Files.writeString(dockerConfig(), "{\"auths\":{\"" + registryHost() + "\":{\"auth\":\"" + FakeIdp.SECRET
                + "basic\"},\"ghcr.io\":{\"auth\":\"" + FakeIdp.SECRET + "ghcr\"}}}");

        Result result = run("registry-login", "--configure", "--remove-stale");

        assertThat(result.exit()).isZero();
        assertThat(result.err()).doesNotContain("auths");
        assertThat(result.out()).contains("Removed the stored login for " + registryHost() + " from "
                + dockerConfig() + ".");
        assertThat(Files.readString(dockerConfig())).isEqualTo("""
                {
                	"auths": {
                		"ghcr.io": {
                			"auth": "%sghcr"
                		}
                	},
                	"credHelpers": {
                		"%s": "qits"
                	}
                }
                """.formatted(FakeIdp.SECRET, registryHost()));
    }

    /** auths for the old registries, beside entries that are not the CLI's. */
    private String staleDockerConfig() {
        return """
                {
                	"auths": {
                		"https://registry.dev.localhost:8080/v1/": {
                			"auth": "%1$sold"
                		},
                		"ghcr.io": {
                			"auth": "%1$sghcr"
                		},
                		"mirror.dev.localhost:8080": {
                			"auth": "%1$smirror"
                		}
                	},
                	"credsStore": "desktop",
                	"credHelpers": {
                		"gcr.io": "gcloud"
                	}
                }
                """.formatted(FakeIdp.SECRET);
    }

    @Test
    void staleDockerLoginsAreWarnedAboutAndKeptWithoutRemoveStale() throws Exception {
        Files.createDirectories(dockerConfig().getParent());
        Files.writeString(dockerConfig(), staleDockerConfig());

        Result result = run("registry-login", "--configure");

        assertThat(result.exit()).isZero();
        assertThat(result.err()).contains("Warning: " + dockerConfig() + " has a stored login for "
                        + "https://registry.dev.localhost:8080/v1/ under auths, an old *.dev.localhost registry; "
                        + "--remove-stale drops it.")
                .contains("stored login for mirror.dev.localhost:8080 under auths").doesNotContain("ghcr.io");
        assertThat(result.out()).doesNotContain("Removed");
        assertThat(Files.readString(dockerConfig())).contains("registry.dev.localhost:8080")
                .contains("mirror.dev.localhost:8080").contains(FakeIdp.SECRET + "old");
    }

    @Test
    void removeStaleDropsTheStaleDockerLoginsAndKeepsTheRest() throws Exception {
        Files.createDirectories(dockerConfig().getParent());
        Files.writeString(dockerConfig(), staleDockerConfig());

        Result result = run("registry-login", "--configure", "--remove-stale");

        assertThat(result.exit()).isZero();
        assertThat(result.err()).doesNotContain("auths");
        assertThat(result.out()).contains("Updated " + dockerConfig())
                .contains("Removed the stored login for https://registry.dev.localhost:8080/v1/ from "
                        + dockerConfig() + ".")
                .contains("Removed the stored login for mirror.dev.localhost:8080 from " + dockerConfig() + ".");
        assertThat(Files.readString(dockerConfig())).isEqualTo("""
                {
                	"auths": {
                		"ghcr.io": {
                			"auth": "%sghcr"
                		}
                	},
                	"credsStore": "desktop",
                	"credHelpers": {
                		"gcr.io": "gcloud",
                		"%s": "qits"
                	}
                }
                """.formatted(FakeIdp.SECRET, registryHost()));
    }

    @Test
    void removeStaleDropsStaleDockerLoginsWhenTheHelperIsAlreadyThere() throws Exception {
        Files.createDirectories(dockerConfig().getParent());
        Files.writeString(dockerConfig(), """
                {"auths": {"registry.dev.localhost:8080": {"auth": "%sold"}},
                 "credHelpers": {"%s": "qits"}}
                """.formatted(FakeIdp.SECRET, registryHost()));

        Result result = run("registry-login", "--configure", "--remove-stale");

        assertThat(result.exit()).isZero();
        assertThat(result.out()).contains("Updated " + dockerConfig())
                .contains("Removed the stored login for registry.dev.localhost:8080 from " + dockerConfig() + ".");
        // The last login gone, auths stays as {}, as docker leaves it after a logout.
        assertThat(Files.readString(dockerConfig())).isEqualTo(
                "{\n\t\"auths\": {},\n\t\"credHelpers\": {\n\t\t\"" + registryHost() + "\": \"qits\"\n\t}\n}\n");

        assertThat(run("registry-login", "--configure", "--remove-stale").out())
                .contains("Up to date: " + dockerConfig()).doesNotContain("Removed");
    }

    @Test
    void aDryRunShowsTheStaleDockerLoginsItWouldRemoveMasked() throws Exception {
        Files.createDirectories(dockerConfig().getParent());
        Files.writeString(dockerConfig(), staleDockerConfig());

        Result result = run("registry-login", "--configure", "--remove-stale", "--dry-run");

        assertThat(result.exit()).isZero();
        assertThat(result.out()).contains("--- " + dockerConfig() + "\n+++ " + dockerConfig())
                .contains("-\t\t\"https://registry.dev.localhost:8080/v1/\": {\n-\t\t\t\"auth\": \"<token>\"\n")
                .contains("-\t\t\"mirror.dev.localhost:8080\": {\n-\t\t\t\"auth\": \"<token>\"\n")
                .contains("+\t\t\"" + registryHost() + "\": \"qits\"")
                .doesNotContain("Removed");
        assertThat(Files.readString(dockerConfig())).isEqualTo(staleDockerConfig());
    }

    @Test
    void aDryRunShowsTheDockerChangesMaskedAndWritesNothing() throws Exception {
        Files.createDirectories(dockerConfig().getParent());
        String before = "{\"auths\":{\"ghcr.io\":{\"auth\":\"" + FakeIdp.SECRET + "ghcr\"}}}\n";
        Files.writeString(dockerConfig(), before);
        Path bin = Files.createDirectories(home.resolve("bin"));
        binary = Optional.of(bin.resolve("qits"));

        Result result = run("registry-login", "--configure", "--dry-run");

        assertThat(result.exit()).isZero();
        assertThat(result.out()).contains("--- " + dockerConfig() + "\n+++ " + dockerConfig())
                .contains("+\t\t\"" + registryHost() + "\": \"qits\"")
                .contains("\"auth\": \"<token>\"")
                .contains("--- " + bin.resolve("docker-credential-qits"))
                .contains("+exec '" + bin.resolve("qits") + "' docker-credential \"$@\"");
        assertThat(Files.readString(dockerConfig())).isEqualTo(before);
        assertThat(bin.resolve("docker-credential-qits")).doesNotExist();
    }

    @Test
    void theShimIsWrittenBesideTheBinaryAndExecutable() throws Exception {
        Path bin = Files.createDirectories(home.resolve("it's bin"));
        binary = Optional.of(bin.resolve("qits"));

        Result result = run("registry-login", "--configure");

        assertThat(result.exit()).isZero();
        Path shim = bin.resolve("docker-credential-qits");
        assertThat(Files.readString(shim)).isEqualTo("#!/bin/sh\nexec '" + home + "/it'\\''s bin/qits' "
                + "docker-credential \"$@\"\n");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(shim))).isEqualTo("rwxr-xr-x");
        assertThat(result.out()).contains("Updated " + shim);
        assertThat(result.err()).doesNotContain("was not written");

        assertThat(run("registry-login", "--configure").out()).contains("Up to date: " + shim);
    }

    @Test
    void aRegistriesFileFromBeforeDockerStillWorks() throws Exception {
        Path config = home.resolve(".config/qits");
        Files.createDirectories(config);
        Files.writeString(config.resolve("registries.json"), """
                {"npmrc":"%s","mavenSettings":"%s","npmRegistries":["%s/artifacts/npm/npm/"],"mavenUrl":"%s"}
                """.formatted(home.resolve(".npmrc"), home.resolve(".m2/settings.xml"), key(),
                platform.url() + "/artifacts/maven/maven"));

        Result result = run("registry-login");

        assertThat(result.exit()).isZero();
        assertThat(result.out()).contains("Updated " + home.resolve(".npmrc")).doesNotContain("Docker");
        assertThat(dockerConfig()).doesNotExist();
    }

    @Test
    void dockerConfigNeedsConfigure() {
        Result result = run("registry-login", "--docker-config", "/tmp/x");

        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.err()).contains("--docker-config");
    }
}
