package no.sikt.graphitron.rewrite.session;

import no.sikt.graphitron.model.diagnostics.BuildWarning;
import no.sikt.graphitron.model.lint.LintRule;

import java.util.List;
import no.sikt.graphitron.model.config.SessionStateConfig;

/**
 * The generation-time advisory about the owned-connection runtime's identity posture, derived
 * purely from the authored {@link SessionStateConfig}. Kept a pure decision (no schema, no
 * {@code RunContext}) so it is unit-testable directly; {@code GraphQLRewriteGenerator}
 * calls it with the config and folds the result into the build-warning channel, where lint
 * suppression (by rule id) applies uniformly.
 *
 * <p>One rule, {@code no-session-state}, because registering a hook is the only thing
 * graphitron can actually observe about identity: against a reflected method reference the
 * build cannot tell a definer-rights routine call from a plain {@code set_config}, so no
 * warning grades a configured mount's integrity. Being the single build-time signal about
 * identity, the message teaches the mount shapes rather than merely firing; the integrity
 * gradient itself is explained in the security guide.
 *
 * <p>The advisory is a {@link BuildWarning.LintFinding} (rule-tagged, hence suppressible by id
 * via {@code <lint><disabledRules>}) with a {@code null} location: a {@code <sessionState>}
 * posture is a {@code pom.xml} / whole-build fact with no SDL coordinate.
 */
public final class SessionStateWarnings {

    private SessionStateWarnings() {}

    /**
     * The advisories for this configuration: {@link SessionStateConfig.None} yields
     * {@code no-session-state} (graphitron owns the connection boundary but was told no way to
     * mount identity, so the generated API is unsecured at the database); a configured
     * {@link SessionStateConfig.MethodHooks} yields none.
     *
     * @param config the authored session-state configuration
     */
    public static List<BuildWarning> forConfig(SessionStateConfig config) {
        return switch (config) {
            case SessionStateConfig.None ignored -> List.of(BuildWarning.LintFinding.of(
                "No <sessionState> is configured: the owned-connection runtime mounts no database identity "
                    + "on the connection, so the database cannot enforce per-caller row access for the "
                    + "generated API. Configure <sessionState> with a <mount> method to mount identity — "
                    + "a definer-rights routine, a signed token verified in-database, or a session "
                    + "variable set by convention (see the security guide for the integrity gradient "
                    + "between them) — or silence this rule (no-session-state) if the API is intentionally "
                    + "unsecured or uses only the caller-owns-everything escape hatch.",
                null, LintRule.NO_SESSION_STATE));
            case SessionStateConfig.MethodHooks ignored -> List.of();
        };
    }
}
