package no.sikt.graphitron.rewrite.session;

import no.sikt.graphitron.rewrite.BuildWarning;
import no.sikt.graphitron.rewrite.lint.LintRule;
import no.sikt.graphitron.rewrite.session.SessionStateConfig.RawHook;
import no.sikt.graphitron.rewrite.session.SessionStateConfig.Variable;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier coverage of the two R429 codegen-config advisories about the owned-connection runtime's
 * identity posture. The decision is a pure function of the resolved {@link SessionStateConfig} and
 * whether the schema uses {@code @service}, so it is asserted directly here rather than through a
 * pipeline run; {@code GraphQLRewriteGenerator.withLintFindings} only supplies the two inputs.
 */
@UnitTier
class SessionStateWarningsTest {

    private static final SessionStateConfig VARIABLES =
        SessionStateConfig.from(null, null, List.of(new Variable("app.user_id", "sub")));
    private static final SessionStateConfig FUNCTION_HOOKS =
        SessionStateConfig.from(new RawHook("Pk.Connect", false), new RawHook("Pk.Disconnect", false), List.of());

    @Test
    void noSessionState_warnsUnsecured_regardlessOfService() {
        assertThat(SessionStateWarnings.forConfig(SessionStateConfig.none(), false))
            .singleElement()
            .isInstanceOfSatisfying(BuildWarning.LintFinding.class, lf -> {
                assertThat(lf.rule()).isEqualTo(LintRule.NO_SESSION_STATE);
                assertThat(lf.message()).contains("No <sessionState>").contains("mounts no database identity");
                assertThat(lf.location()).as("a whole-build posture has no SDL coordinate").isNull();
            });
        // The no-session-state exposure is independent of @service: still fires with services present.
        assertThat(SessionStateWarnings.forConfig(SessionStateConfig.none(), true))
            .singleElement()
            .satisfies(w -> assertThat(((BuildWarning.LintFinding) w).rule()).isEqualTo(LintRule.NO_SESSION_STATE));
    }

    @Test
    void variablesSugarWithService_warnsConventionFence() {
        assertThat(SessionStateWarnings.forConfig(VARIABLES, true))
            .singleElement()
            .isInstanceOfSatisfying(BuildWarning.LintFinding.class, lf -> {
                assertThat(lf.rule()).isEqualTo(LintRule.SESSION_STATE_CONVENTION_FENCE);
                assertThat(lf.message()).contains("convention fence").contains("@service");
            });
    }

    @Test
    void variablesSugarWithoutService_isSilent() {
        // The convention fence only bites when consumer code (@service) runs on the pinned connection.
        assertThat(SessionStateWarnings.forConfig(VARIABLES, false)).isEmpty();
    }

    @Test
    void functionHooks_areSilent_regardlessOfService() {
        // The function-hook form is the tamper-resistant path; no advisory either way.
        assertThat(SessionStateWarnings.forConfig(FUNCTION_HOOKS, true)).isEmpty();
        assertThat(SessionStateWarnings.forConfig(FUNCTION_HOOKS, false)).isEmpty();
    }
}
