package no.sikt.graphitron.rewrite.session;

import no.sikt.graphitron.model.diagnostics.BuildWarning;
import no.sikt.graphitron.model.lint.LintRule;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.config.SessionStateConfig;

/**
 * Unit-tier coverage of the one codegen-config advisory about the owned-connection runtime's
 * identity posture. The decision is a pure function of the authored {@link SessionStateConfig},
 * so it is asserted directly here rather than through a pipeline run;
 * {@code GraphQLRewriteGenerator} only supplies the input.
 *
 * <p>Exactly one rule survives: {@code no-session-state}. The retired
 * {@code session-state-convention-fence} graded a fence level graphitron could see only while it
 * generated the GUC set itself; against a reflected method reference the build cannot tell a
 * definer-rights routine call from a plain {@code set_config}, so a configured mount raises
 * nothing and the integrity gradient lives in the security guide.
 */
@UnitTier
class SessionStateWarningsTest {

    private static final SessionStateConfig METHOD_HOOKS =
        SessionStateConfig.from("com.example.db.Routines#connect", "com.example.db.Routines#disconnect");

    @Test
    void noSessionState_warnsUnsecured() {
        assertThat(SessionStateWarnings.forConfig(SessionStateConfig.none()))
            .singleElement()
            .isInstanceOfSatisfying(BuildWarning.LintFinding.class, lf -> {
                assertThat(lf.rule()).isEqualTo(LintRule.NO_SESSION_STATE);
                assertThat(lf.message()).contains("No <sessionState>").contains("mounts no database identity");
                assertThat(lf.location()).as("a whole-build posture has no SDL coordinate").isNull();
            });
    }

    @Test
    void noSessionState_messageTeachesTheMountShapes() {
        // Being the single build-time signal about identity, the message names the three mount
        // shapes a consumer chooses between and points at the security guide, instead of only
        // saying to configure something.
        assertThat(SessionStateWarnings.forConfig(SessionStateConfig.none()))
            .singleElement()
            .isInstanceOfSatisfying(BuildWarning.LintFinding.class, lf -> assertThat(lf.message())
                .contains("<mount>")
                .contains("definer-rights routine")
                .contains("token verified in-database")
                .contains("session variable set by convention")
                .contains("security guide"));
    }

    @Test
    void configuredMethodHooks_raiseNothing() {
        // Graphitron cannot see the fence level of a reflected mount, so a schema with a
        // configured mount raises nothing rather than asserting an exposure it cannot ground.
        assertThat(SessionStateWarnings.forConfig(METHOD_HOOKS)).isEmpty();
    }
}
