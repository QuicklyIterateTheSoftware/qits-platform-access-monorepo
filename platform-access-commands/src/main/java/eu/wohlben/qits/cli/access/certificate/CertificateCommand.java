package eu.wohlben.qits.cli.access.certificate;

import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@code qits certificate} (epic qits-2626, qits-2761): this workload's client certificate from
 * qits-certificates, for the principal of the caller's own bearer, written to files. The key is made
 * here and never leaves the machine. Interaction.LOCAL: the files are the caller's, so the MCP
 * service does not serve it.
 */
@TuiCommand(interaction = Interaction.LOCAL)
@CommandLine.Command(name = "certificate", mixinStandardHelpOptions = true,
        subcommands = {CertificateCommand.IssueCommand.class, CertificateCommand.KeepCommand.class},
        description = {"This workload's client certificate from qits-certificates (mTLS): issue one into a "
                + "directory, or keep one there, renewed, for as long as this runs.",
                "The key is an EC P-256 key made on this machine; only a certificate request (CSR) leaves it, to "
                        + "POST /certificates/api/workload-certificates, with the caller's bearer (QITS_TOKEN in a "
                        + "workspace, the session on a workstation)."},
        footerHeading = "%nNotes:%n",
        footer = {"- Who the certificate names comes from the bearer alone, never from the CSR: "
                + "urn:qits:principal:application:<app> for a service client, urn:qits:principal:agent:<id> (and "
                + "urn:qits:project:<id>) for a workspace agent, urn:qits:principal:project:<id> for a project "
                + "agent or desk token. A person's token and an exchanged token get none (HTTP 403).",
                "- The files: key.pem (0600), cert.pem and chain.pem (the certificate, then the CA that signed it) "
                        + "in --dir, which is 0700. Each is written to a temporary file and renamed into place, the "
                        + "key first.",
                "- A certificate lives one day. It is never printed, and neither is the key."})
public class CertificateCommand implements Runnable {

    /** Where the files go when neither --dir nor the variable says. */
    static final String DEFAULT_DIR = "/run/qits/workload";

    static final String DIR_VARIABLE = "QITS_WORKLOAD_CERT_DIR";

    @CommandLine.Option(names = "--certificates-url", paramLabel = "<url>", scope = CommandLine.ScopeType.INHERIT,
            description = "The certificates service's base URL, without /certificates. Default: "
                    + "QITS_CERTIFICATES_URL, else the session's idp address with `idp` swapped for `certificates`.")
    String certificatesUrl;

    @CommandLine.Option(names = "--dir", paramLabel = "<dir>", scope = CommandLine.ScopeType.INHERIT,
            description = "Where key.pem, cert.pem and chain.pem go. Default: $" + DIR_VARIABLE + ", else "
                    + DEFAULT_DIR + ".")
    Path dir;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    CertificateKeeper keeper(CliContext context) throws CliFailure, InterruptedException {
        CertificatesApi api = new CertificatesApi(new PlatformClient(context.credential()),
                PlatformUrls.certificates(certificatesUrl, context.env(), context.idpUrl()));
        return new CertificateKeeper(api::issue, directory(context), context.clock(), context.sleeper(),
                context.err(), () -> ThreadLocalRandom.current().nextDouble(), Duration.ofSeconds(30),
                Duration.ofMinutes(10));
    }

    Path directory(CliContext context) {
        if (dir != null) {
            return dir;
        }
        String named = context.env().get(DIR_VARIABLE);
        return Path.of(named == null || named.isBlank() ? DEFAULT_DIR : named.strip());
    }

    @CommandLine.Command(name = "issue", mixinStandardHelpOptions = true,
            description = {"Issue one workload certificate for the caller's principal into --dir, and stop."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits certificate issue",
                    "  qits certificate issue --dir /tmp/workload",
                    "",
                    "- It prints the principal, the serial and notAfter, never the key."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class IssueCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        CertificateCommand parent;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            CertificateKeeper.Issued issued = parent.keeper(context).issue();
            context.out().println("principal  " + issued.principal());
            context.out().println("serial     " + issued.serial());
            context.out().println("notAfter   " + issued.notAfter());
            context.out().println("files      " + parent.directory(context).resolve(CertificateKeeper.KEY) + ", "
                    + CertificateKeeper.CERT + ", " + CertificateKeeper.CHAIN);
            return 0;
        }
    }

    @CommandLine.Command(name = "keep", mixinStandardHelpOptions = true,
            description = {"Issue a workload certificate into --dir, then renew it at 2/3 of its life (about every "
                    + "16 hours, give or take 10%) until SIGTERM or SIGINT. Each renewal makes a new key.",
                    "A failed renewal keeps the files and tries again after 30 s, doubling up to 10 min. It logs one "
                            + "line per certificate and one per failure streak to stderr."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits certificate keep &",
                    "  qits certificate keep --dir /run/qits/workload",
                    "",
                    "- A refusal the service would repeat every time (400, 401, 403: a person's or an exchanged "
                            + "token, say) stops it with exit 1; the files already written stay."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:Stopped by SIGTERM or SIGINT.",
                    "1:The certificates service refuses this token, or a certificate cannot be written.",
                    HelpText.USAGE})
    public static class KeepCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        CertificateCommand parent;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            CertificateKeeper keeper = parent.keeper(context);
            context.onStop().accept(keeper::stop);
            return keeper.keep();
        }
    }
}
