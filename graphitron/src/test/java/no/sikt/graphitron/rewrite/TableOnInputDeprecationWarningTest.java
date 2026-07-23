package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The actionable tier of the {@code @table}-on-input deprecation signal. Pins the per-usage build
 * warning and the per-verb replacement wording at the classifier, without a compilation / execution
 * fixture.
 *
 * <p>No input is carved out any longer: DELETE ({@code @mutation(table:)}) and INSERT (return-derived,
 * or {@code @mutation(table:)} for an encoded return) both have field-relative write-target paths, so
 * the warning fires on every author-written {@code @table} input. The encoded-ID INSERT carve-out that
 * once suppressed the warning retired when the INSERT write target became field-relative, so those
 * inputs warn too (the flip this test's earlier revision anticipated).
 */
@PipelineTier
class TableOnInputDeprecationWarningTest {

    private static final String DEPRECATION_FRAGMENT =
        "is deprecated and will be removed in a future release";

    /**
     * The default Sakila catalog is plain jOOQ-generated and carries no {@code __NODE_TYPE_ID}
     * metadata, so an encoded-ID INSERT return there rejects at classify (no {@code @node} encoder to
     * wire). The {@code nodeidfixture} catalog hand-instruments {@code Bar} with the node metadata,
     * mirroring {@link MutationDmlNodeIdClassificationTest}; the encoded-return arms use it.
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
    void projectedTableReturnInsert_warnsOnInput_namingReturnDerivation() {
        // The return type carries @table, so the write target is derived from it; the input's @table is
        // redundant and warns, naming the return-derived replacement.
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
            .satisfies(w -> {
                assertThat(w.message())
                    .as("the INSERT-consumed replacement clause names the return-derived fix")
                    .contains("@mutation(typeName: INSERT)")
                    .contains("derived from the field's return type");
                assertThat(w.location())
                    .as("the warning carries the input type's source location")
                    .isNotNull();
            });
    }

    @Test
    void encodedIdReturnInsert_warnsOnInput_namingMutationTableArg() {
        // The return is an encoded ID (no @table on the return), so the input's @table is the deprecated
        // bridge and @mutation(table:) is the field-relative replacement. The carve-out retirement:
        // this input, once suppressed, now warns.
        var schema = TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "bar") @node { id: ID! @nodeId name: String }
            input BarInput @table(name: "bar") { name: String }
            type Query { x: String }
            type Mutation { createBar(in: BarInput!): ID @mutation(typeName: INSERT) }
            """, NODEID_CTX);

        assertThat(schema.field("Mutation", "createBar"))
            .as("sanity: the encoded-ID INSERT still classifies as a MutationInsertTableField")
            .isInstanceOf(no.sikt.graphitron.rewrite.model.MutationField.MutationInsertTableField.class);
        assertThat(schema.warnings())
            .filteredOn(w -> w.message().contains("BarInput") && w.message().contains(DEPRECATION_FRAGMENT))
            .as("encoded-ID INSERT input now warns (carve-out retired), naming @mutation(table:)")
            .singleElement()
            .satisfies(w -> assertThat(w.message())
                .contains("@mutation(typeName: INSERT)")
                .contains("@mutation(table:"));
    }

    @Test
    void deleteConsumedInput_warnsNamingMutationTableArg() {
        // DELETE has a field-relative write-target path (@mutation(table:)), so its inputs warn and the
        // warning names the replacement explicitly.
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
            .filteredOn(w -> w.message().contains("FilmDeleteInput") && w.message().contains(DEPRECATION_FRAGMENT))
            .as("DELETE-consumed input warns, naming @mutation(table:) as the replacement")
            .singleElement()
            .satisfies(w -> assertThat(w.message())
                .contains("@mutation(typeName: DELETE)")
                .contains("@mutation(table:"));
    }

    @Test
    void inputReusedByEncodedAndProjectedInsertConsumers_warns() {
        // One input feeds both an encoded-ID INSERT and a projected (@table-return) INSERT. Both derive
        // their write target field-relatively now, so the input's @table is redundant for both and the
        // warning fires (the carve-out that once suppressed this is gone).
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
            .filteredOn(w -> w.message().contains("BarInput") && w.message().contains(DEPRECATION_FRAGMENT))
            .as("an input reused across INSERT consumers warns once, naming the INSERT replacement")
            .singleElement()
            .satisfies(w -> assertThat(w.message()).contains("@mutation(typeName: INSERT)"));
    }
}
