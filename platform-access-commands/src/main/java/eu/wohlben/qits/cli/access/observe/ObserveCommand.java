package eu.wohlben.qits.cli.access.observe;

import eu.wohlben.qits.cli.tui.api.Effect;
import eu.wohlben.qits.cli.session.Credential;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.net.URI;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@TuiCommand(interaction = Interaction.STREAMING, effect = Effect.READ)
@CommandLine.Command(name = "observe", mixinStandardHelpOptions = true, subcommands = {ObserveQueryCommand.class,
        ObserveTelemetryCommands.SourcesCommand.class, ObserveTelemetryCommands.ErrorsCommand.class,
        ObserveTelemetryCommands.SlowSpansCommand.class, ObserveTelemetryCommands.MetricsCommand.class,
        ObserveIssuesCommand.class},
        description = {
                "Print what qits-observability takes in (logs, spans with their events, metrics) as it arrives. "
                        + "Use it to watch a service's errors or to follow one trace live. The service applies the "
                        + "filters and sends only the records that match.",
                "Each --filter is one group of conditions, separated by spaces, that must all hold. A record that fits "
                        + "any group is printed. Live only: records that arrive while it reconnects are missed. "
                        + "Notes go to stderr. Stops on SIGINT or SIGTERM.",
                "",
                "Conditions: F=V equal and F^=V starts with (both match case: status=ERROR, not status=error), "
                        + "F~V contains (any case), F? present, !F absent, level>=V severity at or above V "
                        + "(TRACE, DEBUG, INFO, WARN, ERROR, FATAL or 1-24).",
                "Fields: kind service trace span level body name status event attr.<key> resource.<key>. "
                        + "attr.<key> is the record's own attribute, resource.<key> its resource's. "
                        + "Quote a value that holds spaces: body~\"connection refused\".",
                "",
                "Subcommands read what the buffer holds now and do not wait: query searches it with these filters, "
                        + "sources lists its buckets, and errors, slow-spans and metrics sum up one bucket. issues "
                        + "reads the stored error issues, which outlive the buffer."},
        footerHeading = HelpText.EXAMPLES,
        footer = {
                "  qits observe --filter 'kind=log level>=ERROR' --filter 'kind=span event=exception'",
                "  qits observe --filter 'service^=qits-ci kind=log body~\"connection refused\"'",
                "  qits observe --filter 'trace=4bf92f3577b34da6a3ce929d0e0e4736'",
                "  qits observe --filter 'kind=log level>=WARN' -o json | jq -r .record.body",
                "  qits observe --filter 'service=qits-ci-daemon'",
                "  qits observe --filter 'service=qits-ci-daemon resource.qits.ci.run_id=5f2c0a9e-1b7d-4c2e-9a41-3d8e6f0b2c17'",
                "",
                "- A span's exception is an event of the span: event=exception finds it. attr.exception.type? "
                        + "matches logs only.",
                "- F=V and F^=V match case: status=ERROR, not status=error. F~V ignores case.",
                "- level takes >= only (level>=WARN), and only logs have a level.",
                "- --filter '*' streams every record, which can be a lot.",
                "- The stream and the subcommands take qits:admin, qits:admin-agent, qits:desk-admin or "
                        + "qits:agent, but for issues dismiss, which qits:agent may not."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {"0:Stopped by SIGINT or SIGTERM, or stdout was closed.",
                "1:The platform refused the socket (401, 403 or another 4xx).",
                "2:A --filter cannot be read or the service refused the filters, not signed in, or the session "
                        + "ended."})
public class ObserveCommand extends PlatformCommand {

    /** What picocli said when it still checked --filter itself; scripts and people know this line. */
    static final String MISSING_FILTER = "Missing required option: '--filter=<conditions>'";

    /**
     * Required, but not at picocli's level: picocli checks a parent's required options even when a
     * subcommand is named, so {@code qits observe query} would need a --filter it never reads.
     * {@link #execute} refuses its absence with picocli's own words instead.
     */
    @CommandLine.Option(names = "--filter", paramLabel = "<conditions>",
            description = "Required. One group of conditions, for example 'kind=log level>=ERROR'. Give it again for another "
                    + "group. '*' streams every record.")
    List<String> filters;

    @CommandLine.Option(names = "--observability-url", paramLabel = "<url>",
            description = "The observability service's base URL, without /observability. Default: "
                    + "QITS_OBSERVABILITY_URL, else the session's idp address with `idp` swapped for `observability`.")
    String observabilityUrl;

    @CommandLine.Option(names = {"-o", "--output"}, paramLabel = "<text|json>", defaultValue = "text",
            description = "text: one line per record (the default). json: each record's frame as the service sends "
                    + "it, one per line.")
    String output;

    @Override
    protected int execute(CliContext context) throws CliFailure, InterruptedException {
        boolean json = switch (output == null ? "text" : output) {
            case "text" -> false;
            case "json" -> true;
            default -> throw new CliFailure("--output must be text or json, not '" + output + "'.", CliFailure.USAGE);
        };
        if (filters == null || filters.isEmpty()) {
            throw new CliFailure(MISSING_FILTER, CliFailure.USAGE);
        }
        List<List<FilterGrammar.Condition>> groups = new ArrayList<>();
        for (String filter : filters) {
            groups.add(FilterGrammar.parse(filter));
        }
        String frame = FilterGrammar.subscribeFrame(groups);
        // Fails at once without a credential, rather than in the reconnect loop.
        Credential credential = context.credential();
        credential.bearer();
        URI uri = socketUri(PlatformUrls.observability(observabilityUrl, context.env(), context.idpUrl()));
        ObserveStream stream = new ObserveStream(new PlatformClient(credential), uri, frame, json, ZoneId.systemDefault(),
                context.out(), context.err(), context.clock(), context.sleeper(), ObserveStream.IDLE_LIMIT,
                ObserveStream.PING_EVERY);
        context.onStop().accept(stream::stop);
        return stream.run();
    }

    /** The socket's address: the base URL with {@code ws} for http and {@code wss} for https. */
    static URI socketUri(String base) throws CliFailure {
        String refused = "'" + base + "' is not a usable observability address. Give an http or https base URL.";
        int colon = base.indexOf("://");
        String scheme = colon < 0 ? "" : base.substring(0, colon).toLowerCase(Locale.ROOT);
        String socketScheme = switch (scheme) {
            case "https", "wss" -> "wss";
            case "http", "ws" -> "ws";
            default -> throw new CliFailure(refused, CliFailure.USAGE);
        };
        try {
            URI uri = URI.create(socketScheme + base.substring(colon) + "/observability/stream");
            if (uri.getHost() == null) {
                throw new CliFailure(refused, CliFailure.USAGE);
            }
            return uri;
        } catch (IllegalArgumentException notAUri) {
            throw new CliFailure(refused, CliFailure.USAGE);
        }
    }
}
