package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.code_action.SdlAction;
import no.sikt.graphitron.lsp.code_action.SdlActions;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.SchemaCoordinate;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bidirectional drift-protection seam between {@code directives.graphqls}
 * deprecation markers and the {@link SdlActions} registry. Mirrors the
 * pattern of {@code DeprecationsDocCoverageTest} (SDL ↔ docs index) one
 * layer down: SDL ↔ tooling.
 *
 * <p>Two invariants:
 *
 * <ul>
 *   <li>Every {@link SdlAction#targets()} entry must point at an
 *       existing deprecation marker. A stale action (target whose
 *       deprecation was removed, or whose directive / arg was renamed
 *       or deleted) breaks the build.</li>
 *   <li>Every deprecation marker must be covered by either an
 *       {@link SdlAction} or
 *       {@link SdlActions#MANUAL_MIGRATION_DEPRECATIONS}. An orphan
 *       deprecation (we said it's deprecated but offer no migration
 *       and no documented "manual" reason) breaks the build.</li>
 * </ul>
 *
 * <p>The at-landing-time canonical set is also pinned here; a hand-edit
 * that adds (or loses) a deprecation flips this assertion as well, so
 * the per-arm invariants don't silently keep passing through changes
 * the spec didn't anticipate.
 *
 * <p>Both sides of the seam now key on {@link SchemaCoordinate}: the
 * vocabulary's {@link LspVocabulary#deprecatedCoordinates()} and the
 * registry's {@link SdlAction#targets()}, plus
 * {@link SdlActions#MANUAL_MIGRATION_DEPRECATIONS}, all hold
 * {@code Set<SchemaCoordinate>}. The previous parallel
 * {@code SdlAction.DeprecationTarget} hierarchy collapsed into the
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
            // R398: @record's docstring now carries the @deprecated marker, so the
            // deprecation convention is uniform (hover / deprecatedCoordinates). Its
            // removal is covered by the MANUAL_MIGRATION_DEPRECATIONS allow-list, since
            // the contextual deletion rides the redundant-record advisory's build-side fix.
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

    @Test
    void everyMarkerIsCoveredByAnActionOrTheManualMigrationAllowList() {
        var actionTargets = new LinkedHashSet<SchemaCoordinate>();
        for (SdlAction action : SdlActions.all(CompletionData.empty())) {
            actionTargets.addAll(action.targets());
        }
        Set<SchemaCoordinate> covered = new LinkedHashSet<>(actionTargets);
        covered.addAll(SdlActions.MANUAL_MIGRATION_DEPRECATIONS);

        var orphans = VOCAB.deprecatedCoordinates().stream()
            .filter(m -> !covered.contains(m))
            .collect(Collectors.toSet());

        assertThat(orphans)
            .as("deprecation markers in directives.graphqls without an SdlAction or "
                + "MANUAL_MIGRATION_DEPRECATIONS allow-list entry; either add an "
                + "action / allow-list entry or drop the deprecation")
            .isEmpty();
    }

    @Test
    void manualMigrationAllowListEntriesAllPointAtRealMarkers() {
        var stale = SdlActions.MANUAL_MIGRATION_DEPRECATIONS.stream()
            .filter(t -> !VOCAB.deprecatedCoordinates().contains(t))
            .collect(Collectors.toSet());

        assertThat(stale)
            .as("MANUAL_MIGRATION_DEPRECATIONS entries without a corresponding "
                + "deprecation marker in directives.graphqls; remove the stale entries")
            .isEmpty();
    }
}
