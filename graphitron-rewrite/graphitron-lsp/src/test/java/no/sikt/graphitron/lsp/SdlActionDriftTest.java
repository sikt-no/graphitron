package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.code_action.SdlAction;
import no.sikt.graphitron.lsp.code_action.SdlAction.DeprecationTarget;
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
 * pattern of {@code DeprecationsDocCoverageTest} (SDL ↔ docs index)
 * one layer down: SDL ↔ tooling.
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
 * <p>Markers are derived from {@link LspVocabulary#deprecatedCoordinates()},
 * which walks the parsed {@code TypeDefinitionRegistry}. The test bridges
 * {@link SchemaCoordinate} (vocabulary's keying) and {@link DeprecationTarget}
 * (SdlActions' keying) via {@link #toDeprecationTarget}; the two carriers
 * collapse into one in a follow-up R119 cleanup.
 */
class SdlActionDriftTest {

    private static final LspVocabulary VOCAB = LspVocabulary.load();

    @Test
    void atLandingTimeCanonicalSetIsExact() {
        assertThat(markers()).containsExactlyInAnyOrder(
            new DeprecationTarget.Member("ExternalCodeReference", "name"),
            new DeprecationTarget.Member("@asConnection", "connectionName"),
            new DeprecationTarget.WholeDirective("index")
        );
    }

    @Test
    void everyActionTargetPointsAtAnExistingMarker() {
        var allActionTargets = new LinkedHashSet<DeprecationTarget>();
        for (SdlAction action : SdlActions.all(CompletionData.empty())) {
            allActionTargets.addAll(action.targets());
        }

        var stale = allActionTargets.stream()
            .filter(t -> !markers().contains(t))
            .collect(Collectors.toSet());

        assertThat(stale)
            .as("SdlAction targets without a corresponding deprecation marker in "
                + "directives.graphqls; either add the deprecation or drop the action")
            .isEmpty();
    }

    @Test
    void everyMarkerIsCoveredByAnActionOrTheManualMigrationAllowList() {
        var actionTargets = new LinkedHashSet<DeprecationTarget>();
        for (SdlAction action : SdlActions.all(CompletionData.empty())) {
            actionTargets.addAll(action.targets());
        }
        Set<DeprecationTarget> covered = new LinkedHashSet<>(actionTargets);
        covered.addAll(SdlActions.MANUAL_MIGRATION_DEPRECATIONS);

        var orphans = markers().stream()
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
            .filter(t -> !markers().contains(t))
            .collect(Collectors.toSet());

        assertThat(stale)
            .as("MANUAL_MIGRATION_DEPRECATIONS entries without a corresponding "
                + "deprecation marker in directives.graphqls; remove the stale entries")
            .isEmpty();
    }

    private static Set<DeprecationTarget> markers() {
        return VOCAB.deprecatedCoordinates().stream()
            .map(SdlActionDriftTest::toDeprecationTarget)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static DeprecationTarget toDeprecationTarget(SchemaCoordinate coord) {
        return switch (coord) {
            case SchemaCoordinate.Directive d -> new DeprecationTarget.WholeDirective(d.name());
            case SchemaCoordinate.DirectiveArg da ->
                new DeprecationTarget.Member("@" + da.directive(), da.arg());
            case SchemaCoordinate.InputField f -> new DeprecationTarget.Member(f.type(), f.field());
            case SchemaCoordinate.InputType t -> throw new IllegalStateException(
                "input-type coordinate cannot be deprecated: " + t);
        };
    }
}
