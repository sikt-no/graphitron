package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.code_action.SdlAction;
import no.sikt.graphitron.lsp.code_action.SdlActions;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.SchemaCoordinate;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drift-protection seam between {@code directives.graphqls} deprecation markers and
 * the {@link SdlActions} registry. Mirrors the pattern of
 * {@code DeprecationsDocCoverageTest} (SDL ↔ docs index) one layer down: SDL ↔ tooling.
 *
 * <p>Deprecation comments and quick-fix actions are independent (R398): a deprecation
 * may carry no action, and a quick fix is registered explicitly rather than divined
 * from the deprecation's prose. So there is no "every deprecation must have a
 * migration" invariant. The one coupling that remains is a stale-reference guard:
 *
 * <ul>
 *   <li>Every {@link SdlAction#targets()} entry that names a deprecation must point at
 *       an existing marker. A stale action (target whose deprecation was removed, or
 *       whose directive / arg was renamed or deleted) breaks the build.</li>
 * </ul>
 *
 * <p>The at-landing-time canonical set of deprecated coordinates is also pinned here;
 * a hand-edit that adds (or loses) a deprecation flips this assertion, so a marker
 * change is a deliberate edit rather than a silent one.
 *
 * <p>Both sides of the seam key on {@link SchemaCoordinate}: the vocabulary's
 * {@link LspVocabulary#deprecatedCoordinates()} and the registry's
 * {@link SdlAction#targets()} both hold {@code Set<SchemaCoordinate>}. The previous
 * parallel {@code SdlAction.DeprecationTarget} hierarchy collapsed into the
 * vocabulary type as part of R119 phase 3 cleanup.
 */
class SdlActionDriftTest {

    private static final LspVocabulary VOCAB = LspVocabulary.load();

    @Test
    void atLandingTimeCanonicalSetIsExact() {
        assertThat(VOCAB.deprecatedCoordinates()).containsExactlyInAnyOrder(
            new SchemaCoordinate.InputField("ExternalCodeReference", "name"),
            new SchemaCoordinate.DirectiveArg("asConnection", "connectionName"),
            new SchemaCoordinate.Directive("index"),
            // R398: @record's docstring carries the @deprecated marker, so the deprecation
            // convention is uniform (hover / deprecatedCoordinates). It carries no registered
            // migration action; its removal is offered contextually by the redundant-record
            // advisory's build-side fix, and a deprecation without an action is fine.
            new SchemaCoordinate.Directive("record")
        );
    }

    @Test
    void everyActionTargetPointsAtAnExistingMarker() {
        var allActionTargets = new LinkedHashSet<SchemaCoordinate>();
        for (SdlAction action : SdlActions.all(CompletionData.empty())) {
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
