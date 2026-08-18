package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.code_action.SdlAction;
import no.sikt.graphitron.lsp.code_action.SdlActions;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.SchemaCoordinate;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drift-protection seam between {@code directives.graphqls} deprecation markers and
 * the {@link SdlActions} registry. Mirrors {@code DeprecationsDocCoverageTest}
 * (SDL ↔ docs index) one layer down: SDL ↔ tooling.
 *
 * <p>Deprecation comments and quick-fix actions are independent: a deprecation may
 * carry no action, and a quick fix is registered explicitly rather than divined from
 * the deprecation's prose, so there is no "every deprecation must have a migration"
 * invariant. The enforced coupling is a stale-reference guard: every
 * {@link SdlAction#targets()} entry must point at an existing marker, so an action
 * whose deprecation was removed, or whose directive or arg was renamed or deleted,
 * breaks the build.
 *
 * <p>The canonical set of deprecated coordinates is also pinned exactly; a hand-edit
 * that adds (or loses) a deprecation flips that assertion, making a marker change a
 * deliberate edit rather than a silent one.
 */
class SdlActionDriftTest {

    private static final LspVocabulary VOCAB = LspVocabulary.load();

    @Test
    void atLandingTimeCanonicalSetIsExact() {
        assertThat(VOCAB.deprecatedCoordinates()).containsExactlyInAnyOrder(
            new SchemaCoordinate.DirectiveArg("asConnection", "connectionName"),
            new SchemaCoordinate.Directive("index"),
            // @record's docstring carries the @deprecated marker, keeping the deprecation
            // convention uniform (hover / deprecatedCoordinates). No migration action is
            // registered: its removal is offered by the redundant-record advisory's
            // build-side fix, and a deprecation without an action is fine.
            new SchemaCoordinate.Directive("record")
        );
    }

    @Test
    void everyActionTargetPointsAtAnExistingMarker() {
        var allActionTargets = new LinkedHashSet<SchemaCoordinate>();
        for (SdlAction action : SdlActions.all()) {
            allActionTargets.addAll(action.targets());
        }

        var stale = allActionTargets.stream()
            .filter(t -> !VOCAB.deprecatedCoordinates().contains(t))
            .collect(Collectors.toSet());

        assertThat(stale)
            .as("SdlAction targets without a corresponding deprecation marker in "
                + "directives.graphqls; either add the deprecation or drop the action")
            .isEmpty();
    }
}
