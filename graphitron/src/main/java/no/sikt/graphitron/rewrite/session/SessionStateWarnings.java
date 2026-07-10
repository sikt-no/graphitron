package no.sikt.graphitron.rewrite.session;

import no.sikt.graphitron.rewrite.BuildWarning;
import no.sikt.graphitron.rewrite.lint.LintRule;

import java.util.List;

/**
 * R429 slice 6 - the two generation-time advisories about the owned-connection runtime's identity
 * posture, derived purely from the resolved {@link SessionStateConfig} and whether the schema uses
 * {@code @service}. Kept a pure decision (no schema, no {@code RewriteContext}) so it is unit-testable
 * directly; {@code GraphQLRewriteGenerator.withLintFindings} calls it with the config and a
 * {@code hasService} flag and folds the result into the build-warning channel, where R408 lint
 * suppression (by rule id) applies uniformly.
 *
 * <p>Both advisories are {@link BuildWarning.LintFinding}s (rule-tagged, hence suppressible by id via
 * {@code <lint><disabledRules>}) with a {@code null} location: a {@code <sessionState>} posture is a
 * {@code pom.xml} / whole-build fact with no SDL coordinate.
 */
public final class SessionStateWarnings {

    private SessionStateWarnings() {}

    /**
     * The advisories for this configuration:
     * <ul>
     *   <li>{@link SessionStateConfig.None}: graphitron owns the connection boundary but was told no way
     *       to mount identity, so the generated API is unsecured at the database. ({@code no-session-state})</li>
     *   <li>{@link SessionStateConfig.Variables} with {@code @service} fields present: the Postgres
     *       {@code <variables>} sugar is a convention fence (any SQL on the connection can overwrite the
     *       GUCs) and {@code @service} runs consumer code on that same pinned connection, so the mounted
     *       identity is forgeable. ({@code session-state-convention-fence})</li>
     * </ul>
     * Every other configuration (function hooks, or variables without {@code @service}) yields none.
     *
     * @param config     the resolved session-state configuration
     * @param hasService whether the schema classifies any {@code @service} field
     */
    public static List<BuildWarning> forConfig(SessionStateConfig config, boolean hasService) {
        return switch (config) {
            case SessionStateConfig.None ignored -> List.of(BuildWarning.LintFinding.of(
                "No <sessionState> is configured: the owned-connection runtime mounts no database identity "
                    + "on the connection, so the database cannot enforce per-caller row access for the "
                    + "generated API. Configure <sessionState> to mount identity, or silence this rule "
                    + "(no-session-state) if the API is intentionally unsecured or uses only the "
                    + "caller-owns-everything escape hatch.",
                null, LintRule.NO_SESSION_STATE));
            case SessionStateConfig.Variables ignored when hasService -> List.of(BuildWarning.LintFinding.of(
                "The <sessionState> <variables> sugar sets PostgreSQL session variables (a convention "
                    + "fence: any SQL on the connection can overwrite them), and this schema has @service "
                    + "methods that run consumer code on that same pinned connection. A @service issuing "
                    + "arbitrary SQL could forge or clear the mounted identity. For a tamper-resistant "
                    + "identity use the function-hook <connect>/<disconnect> form with a SECURITY DEFINER "
                    + "connect, or pass a signed token verified in-database (the cryptographic fence). "
                    + "Silence this rule (session-state-convention-fence) to accept the convention fence.",
                null, LintRule.SESSION_STATE_CONVENTION_FENCE));
            case SessionStateConfig.Variables ignored -> List.of();
            case SessionStateConfig.FunctionHooks ignored -> List.of();
        };
    }
}
