package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.ParentCorrelation;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A split-delivered field whose return type is bound to its parent's table, with no
 * {@code @reference}, has an empty path and so no parent correlation: the batch the split
 * anchor keys on each parent's correlation to the child has nothing to key on. Classification
 * rejects it as an author error naming the field, the applied delivery marker, both types, the
 * table and the remedies, rather than letting it reach the emitter with a null correlation. The
 * remedies classify, and the inline twin (no {@code @splitQuery}) reads the parent's own row.
 */
@PipelineTier
class SplitQuerySameTableRejectionPipelineTest {

    private static final String KEY_DIRECTIVE =
        "directive @key(fields: String!, resolvable: Boolean = true) repeatable on OBJECT | INTERFACE\n";

    private static final String DETAILS = """
        type FilmDetails @table(name: "film") { description: String }
        """;

    private static void assertSameTableRejection(GraphitronSchema schema, String fieldName,
                                                 String marker) {
        assertThat(schema.field("Film", fieldName))
            .isInstanceOfSatisfying(UnclassifiedField.class, f -> {
                assertThat(f.rejection()).isInstanceOf(Rejection.AuthorError.Structural.class);
                assertThat(f.rejection().message())
                    .startsWith(marker + " fetches the child in a batch")
                    .contains("'FilmDetails' is bound to the same table as 'Film' ('film')")
                    .contains("has no @reference")
                    .contains("drop " + marker + " from a single-cardinality field with no arguments,"
                        + " which reads the parent's own row in the same statement")
                    .contains("for a field that must stay split, state the correlation with"
                        + " @reference(path: [{condition: ...}])")
                    .endsWith("drop " + marker + ".");
            });
    }

    @Test
    void singleCardinality_rejected() {
        var schema = TestSchemaHelper.buildSchema(DETAILS + """
            type Film @table(name: "film") {
                title: String
                details: FilmDetails @splitQuery
            }
            type Query { films: [Film!]! }
            """);

        assertSameTableRejection(schema, "details", "@splitQuery");
    }

    @Test
    void listCardinality_rejected() {
        var schema = TestSchemaHelper.buildSchema(DETAILS + """
            type Film @table(name: "film") {
                title: String
                details: [FilmDetails!]! @splitQuery
            }
            type Query { films: [Film!]! }
            """);

        assertSameTableRejection(schema, "details", "@splitQuery");
    }

    @Test
    void lookupKeyList_rejectedBySameTableGuardNotTheLookupGate() {
        var schema = TestSchemaHelper.buildSchema(DETAILS + """
            type Film @table(name: "film") {
                title: String
                details(film_id: [Int!]! @lookupKey): [FilmDetails!]! @splitQuery
            }
            type Query { films: [Film!]! }
            """);

        assertSameTableRejection(schema, "details", "@splitQuery");
    }

    @Test
    void keyedDefaultTenantParent_rejectedTheSameWay() {
        // The reported shape: a federation entity routed by the request's entity default tenant.
        // Tenancy feeds launch only, so classification takes the same arm and the same rejection.
        var schema = TestSchemaHelper.buildSchema(KEY_DIRECTIVE + DETAILS + """
            type Film @table(name: "film") @key(fields: "title") @defaultTenant {
                title: String
                details: FilmDetails @splitQuery
            }
            type Query { x: String }
            """, TestConfiguration.testContext().withTenantColumn("film_id"));

        assertSameTableRejection(schema, "details", "@splitQuery");
    }

    @Test
    void tenantFanOutAlone_namesTenantFanOut() {
        // @tenantFanOut forces split delivery on its own, so it reaches the same mint; the
        // diagnostic names the marker the author actually wrote.
        var schema = TestSchemaHelper.buildSchema(DETAILS + """
            type Film @table(name: "film") {
                title: String
                details: [FilmDetails!]! @tenantFanOut
            }
            type Query { films: [Film!]! }
            """, TestConfiguration.testContext().withTenantColumn("film_id"));

        assertSameTableRejection(schema, "details", "@tenantFanOut");
        assertThat(((UnclassifiedField) schema.field("Film", "details")).rejection().message())
            .doesNotContain("@splitQuery");
    }

    @Test
    void rejection_surfacesThroughValidatorOutput() {
        var schema = TestSchemaHelper.buildSchema(DETAILS + """
            type Film @table(name: "film") {
                title: String
                details: FilmDetails @splitQuery
            }
            type Query { films: [Film!]! }
            """);

        assertThat(new GraphitronSchemaValidator().validate(schema))
            .filteredOn(e -> "Film.details".equals(e.coordinate()))
            .extracting(ValidationError::message)
            .singleElement()
            .satisfies(m -> assertThat(m)
                .startsWith("Field 'Film.details': @splitQuery fetches the child in a batch")
                .contains("'FilmDetails'", "'Film'", "('film')"));
    }

    @Test
    void conditionReferenceRemedy_classifiesAsParentAnchoredBatch() {
        var schema = TestSchemaHelper.buildSchema(DETAILS + """
            type Film @table(name: "film") {
                title: String
                details: FilmDetails @splitQuery @reference(path: [
                    {condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "join"}}
                ])
            }
            type Query { films: [Film!]! }
            """);

        assertThat(schema.field("Film", "details"))
            .isInstanceOfSatisfying(ChildField.BatchedTableField.class, f ->
                assertThat(f.parentCorrelation()).isInstanceOf(ParentCorrelation.OnParentJoin.class));
    }

    @Test
    void nestingRemedy_classifiesAsNestingField() {
        var schema = TestSchemaHelper.buildSchema("""
            type FilmDetails { description: String }
            type Film @table(name: "film") {
                title: String
                details: FilmDetails
            }
            type Query { films: [Film!]! }
            """);

        assertThat(schema.field("Film", "details")).isInstanceOf(ChildField.NestingField.class);
    }

    @Test
    void inlineTwin_readsTheParentsOwnRow() {
        var schema = TestSchemaHelper.buildSchema(DETAILS + """
            type Film @table(name: "film") {
                title: String
                details: FilmDetails
            }
            type Query { films: [Film!]! }
            """);

        assertThat(schema.field("Film", "details"))
            .isInstanceOfSatisfying(ChildField.SameRowTableField.class, f ->
                assertThat(f.returnType().table().tableName()).isEqualTo("film"));
    }
}
