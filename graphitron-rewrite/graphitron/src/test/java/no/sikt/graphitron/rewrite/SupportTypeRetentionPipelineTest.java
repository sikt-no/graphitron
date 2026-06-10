package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.schema.DirectiveSupportTypes;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R291: classification-time retention of the support types declared in
 * {@code directives.graphqls}. The retention decision materialises as
 * {@link GraphitronSchema#types()} membership, the single decision both the runtime
 * registration ({@code GraphitronSchemaClassGenerator.planFor}) and the print seam
 * ({@code SchemaSdlEmitter}) consume.
 */
@PipelineTier
class SupportTypeRetentionPipelineTest {

    @Test
    void noSupportTypeClassifiesWhenNothingReferencesOne() {
        var schema = TestSchemaHelper.buildSchema("type Query { x: String }");
        assertThat(schema.types().keySet())
            .as("support types must not enter schema.types() without a consumer reference")
            .doesNotContainAnyElementsOf(DirectiveSupportTypes.all());
    }

    @Test
    void publishedSupportTypeRetainedWhenReferencedFromInputField() {
        var schema = TestSchemaHelper.buildSchema("""
            type Query { films(order: [FilmOrderBy]): String }
            input FilmOrderBy { direction: SortDirection }
            """);
        var sortDirection = schema.types().get("SortDirection");
        assertThat(sortDirection).isInstanceOf(GraphitronType.EnumType.class);
        var enumType = (GraphitronType.EnumType) sortDirection;
        assertThat(enumType.schemaType().getDescription())
            .as("retained SortDirection carries the directives.graphqls description")
            .isNotEmpty();
        assertThat(enumType.values())
            .as("ASC / DESC carry value descriptions for Apollo's description lint")
            .allSatisfy(value -> assertThat(value.description()).isNotEmpty());
    }

    @Test
    void publishedSupportTypeRetainedWhenReferencedFromArgument() {
        var schema = TestSchemaHelper.buildSchema("""
            type Query { films(direction: SortDirection): String }
            """);
        assertThat(schema.types().get("SortDirection")).isInstanceOf(GraphitronType.EnumType.class);
    }

    @Test
    void strictlyInternalReferenceFromInputFieldRejectsTheReferencingType() {
        var schema = TestSchemaHelper.buildSchema("""
            type Query { search(filter: Broken): String }
            input Broken { handler: ErrorHandler }
            """);
        var broken = schema.types().get("Broken");
        assertThat(broken).isInstanceOf(GraphitronType.UnclassifiedType.class);
        var rejection = ((GraphitronType.UnclassifiedType) broken).rejection();
        assertThat(rejection).isInstanceOf(Rejection.AuthorError.class);
        assertThat(rejection.message())
            .contains("Broken.handler")
            .contains("ErrorHandler")
            .contains("build-time directive arguments");

        var errors = new GraphitronSchemaValidator().validate(schema);
        assertThat(errors)
            .as("the classify-time rejection must fail at validate time")
            .anySatisfy(error -> {
                assertThat(error.coordinate()).isEqualTo("Broken");
                assertThat(error.message()).contains("ErrorHandler");
            });
    }

    @Test
    void strictlyInternalReferenceFromOutputFieldRejectsTheReferencingType() {
        var schema = TestSchemaHelper.buildSchema("""
            type Query { kind: MutationType }
            """);
        var query = schema.types().get("Query");
        assertThat(query).isInstanceOf(GraphitronType.UnclassifiedType.class);
        assertThat(((GraphitronType.UnclassifiedType) query).rejection().message())
            .contains("Query.kind")
            .contains("MutationType");
    }
}
