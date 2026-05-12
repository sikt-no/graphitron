package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * R108 depth-recursion pin: the Stage-2 per-typename SELECT wraps the parent selection set
 * <em>once</em>, at the outer call site. Nested-projection recursion in
 * {@code TypeClassGenerator.emitSelectionSwitch} reads from {@code sf.getSelectionSet()}
 * on a {@code SelectedField} already filtered at the outer level, so depth&gt;0 levels do not
 * need (and do not get) a per-depth filter.
 *
 * <p>The shape claim from the R108 spec's "Nested polymorphic dispatch" section: the outer-level
 * filter has already restricted the grouped map to entries matching this participant, so the
 * nested recursion sees only the active fragment's nested selection. No further filter is
 * needed at depth; the restriction is per-call, not per-depth.
 *
 * <p>This pipeline-tier pin emits a Stage-2 helper for a polymorphic fixture and asserts that
 * the emitted method body references {@code PolymorphicSelectionSet} exactly once. A regression
 * that introduces a per-depth wrap (or a missing wrap at the outer level) trips the count.
 * The execution-tier {@code PolymorphicProjectionQueryTest} provides the SQL-shape proof; this
 * test is the cheap pipeline-layer signal.
 *
 * <p>The {@code toString()} occurrence count of a stable {@code ClassName} reference is a
 * structural fact (it identifies wrap sites by symbol), not an implementation-detail string
 * assertion; it stays stable across emit-body refactors that don't change the wrap topology.
 */
@PipelineTier
class PolymorphicNestingFilterTest {

    @Test
    void stage2HelperWrapsSelectionSetExactlyOncePerCall() {
        // Fixture: union of two table-bound participants. Each participant resolves through
        // the Stage-2 buildPerTypenameSelect path; the emitted method body should contain
        // exactly one PolymorphicSelectionSet.restrictTo reference (the outer wrap). The
        // recursion inside the emitted <Type>.$fields walks sf.getSelectionSet() at depth>0,
        // which is independent of the wrapper and adds no second restrictTo to the body.
        var schema = TestSchemaHelper.buildSchema("""
            type Inventory @table(name: "inventory") {
              inventoryId: Int! @field(name: "inventory_id")
              filmId: Int! @field(name: "film_id")
            }
            type Content @table(name: "content") {
              contentId: Int! @field(name: "content_id")
              filmId: Int @field(name: "film_id")
            }
            union FilmReferrer = Inventory | Content
            type Film @record(record: {className: "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord"}) {
              referrers: [FilmReferrer!]!
            }
            type Query { film: Film }
            """);

        var filmFetchers = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("FilmFetchers"))
            .findFirst().orElseThrow();

        var inventoryHelper = filmFetchers.methodSpecs().stream()
            .filter(m -> m.name().equals("selectInventoryForReferrers"))
            .findFirst().orElseThrow();
        var contentHelper = filmFetchers.methodSpecs().stream()
            .filter(m -> m.name().equals("selectContentForReferrers"))
            .findFirst().orElseThrow();

        assertThat(wrapCount(inventoryHelper))
            .as("selectInventoryForReferrers wraps env.getSelectionSet() through "
                + "PolymorphicSelectionSet.restrictTo exactly once; a depth>0 wrap would land "
                + "a second reference, and a missing outer wrap would land zero. The "
                + "'no further filter at depth' design claim is the structural invariant this "
                + "count encodes.")
            .isEqualTo(1);
        assertThat(wrapCount(contentHelper))
            .as("selectContentForReferrers wraps env.getSelectionSet() through "
                + "PolymorphicSelectionSet.restrictTo exactly once; same invariant as the "
                + "sibling participant.")
            .isEqualTo(1);
    }

    private static long wrapCount(MethodSpec m) {
        return Pattern.compile("PolymorphicSelectionSet\\b")
            .matcher(m.toString()).results().count();
    }
}
