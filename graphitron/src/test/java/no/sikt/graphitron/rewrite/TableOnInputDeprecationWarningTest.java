package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R332: the actionable tier of the {@code @table}-on-input deprecation signal. Pins the per-usage
 * build warning and its one carve-out at the classifier, so the load-bearing behavior (the
 * encoded-ID / scalar-return INSERT/UPSERT suppression) is verified without a compilation /
 * execution fixture.
 *
 * <p>This is the test that fails when R97 Phase 2b moves the INSERT/UPSERT write-target derivation
 * off the input's {@code @table}: at that point {@code encodedWriteTargetInputTypes} empties and the
 * two carve-out assertions here flip to expecting the warning, which is the intended signal that the
 * carve-out has been retired.
 */
@PipelineTier
class TableOnInputDeprecationWarningTest {

    private static final String DEPRECATION_FRAGMENT =
        "is deprecated and will be removed in a future release";

    /**
     * The default Sakila catalog is plain jOOQ-generated and carries no {@code __NODE_TYPE_ID}
     * metadata, so an encoded-ID INSERT/UPSERT return there rejects at classify (no {@code @node}
     * encoder to wire) and never produces the {@code MutationInsertTableField} the carve-out reads.
     * The {@code nodeidfixture} catalog hand-instruments {@code Bar} with the node metadata, mirroring
     * {@link MutationDmlNodeIdClassificationTest}; the encoded-return arms use it.
     */
    private static final RewriteContext NODEID_CTX = new RewriteContext(
        List.of(),
        Path.of(""),
        Path.of(""),
        "fake.code.generated",
        "no.sikt.graphitron.rewrite.nodeidfixture",
        Map.of()
    );

    @Test
    void projectedTableReturnInsert_warnsOnInput() {
        // The return type carries @table, so the field-relative derivation has a target to collapse
        // to; the input's @table is redundant and warns.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT) }
            """);

        assertThat(schema.warnings())
            .filteredOn(w -> w.message().contains("FilmInput") && w.message().contains(DEPRECATION_FRAGMENT))
            .as("projected @table-return INSERT input must earn the deprecation warning")
            .singleElement()
            .satisfies(w -> assertThat(w.location())
                .as("the warning carries the input type's source location")
                .isNotNull());
    }

    @Test
    void encodedIdReturnInsert_doesNotWarnOnInput() {
        // The return is an encoded ID: the return type carries no @table, so the input's @table is
        // currently the only signal naming the write target. Carved out until R97 Phase 2b lands.
        var schema = TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "bar") @node { id: ID! @nodeId name: String }
            input BarInput @table(name: "bar") { name: String }
            type Query { x: String }
            type Mutation { createBar(in: BarInput!): ID @mutation(typeName: INSERT) }
            """, NODEID_CTX);

        assertThat(schema.field("Mutation", "createBar"))
            .as("sanity: the encoded-ID INSERT classifies as a MutationInsertTableField")
            .isInstanceOf(no.sikt.graphitron.rewrite.model.MutationField.MutationInsertTableField.class);
        assertThat(schema.warnings())
            .extracting(BuildWarning::message)
            .as("encoded-ID INSERT input is carved out of the deprecation warning")
            .noneMatch(m -> m.contains("BarInput") && m.contains(DEPRECATION_FRAGMENT));
    }

    // No encoded-UPSERT case: @mutation(typeName: UPSERT) is refused upstream under the R144
    // cardinality-safety regime (lifts at R145), so no MutationUpsertTableField can be constructed
    // today. encodedWriteTargetInputTypes still reads that leaf so the carve-out follows the sealed
    // model the moment UPSERT lands, but there is nothing to pin here yet.

    @Test
    void deleteConsumedInput_isSuppressed() {
        // R457 commit 1: DELETE has no field-relative write-target path yet, so the input's @table is
        // the sole signal naming the write target. Carved out until the @mutation(table:) / return-
        // derived replacement lands (this assertion flips to expecting the warning in R457's cutover).
        var schema = TestSchemaHelper.buildSchema("""
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId filmId: Int! @field(name: "film_id") }
            input FilmDeleteInput @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { x: String }
            type Mutation { deleteFilm(in: FilmDeleteInput!): ID @mutation(typeName: DELETE) }
            """);

        assertThat(schema.field("Mutation", "deleteFilm"))
            .as("sanity: the ID-return DELETE classifies as a MutationDeleteTableField")
            .isInstanceOf(no.sikt.graphitron.rewrite.model.MutationField.MutationDeleteTableField.class);
        assertThat(schema.warnings())
            .extracting(BuildWarning::message)
            .as("DELETE-consumed input is carved out of the deprecation warning until the field-relative path lands")
            .noneMatch(m -> m.contains("FilmDeleteInput") && m.contains(DEPRECATION_FRAGMENT));
    }

    @Test
    void inputReusedByEncodedAndProjectedConsumers_isSuppressed() {
        // D3 conservative rule: one input feeds both an encoded INSERT and a projected consumer.
        // A false fire would tell the author to delete the only write-target signal the encoded arm
        // has, so the type-level warning is suppressed if any consumer is an encoded INSERT/UPSERT.
        var schema = TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "bar") @node { id: ID! @nodeId name: String }
            input BarInput @table(name: "bar") { name: String }
            type Query { x: String }
            type Mutation {
                createBarId(in: BarInput!): ID @mutation(typeName: INSERT)
                createBarProjected(in: BarInput!): Bar @mutation(typeName: INSERT)
            }
            """, NODEID_CTX);

        assertThat(schema.warnings())
            .extracting(BuildWarning::message)
            .as("an input reused by an encoded INSERT is suppressed even though a projected consumer also uses it")
            .noneMatch(m -> m.contains("BarInput") && m.contains(DEPRECATION_FRAGMENT));
    }
}
