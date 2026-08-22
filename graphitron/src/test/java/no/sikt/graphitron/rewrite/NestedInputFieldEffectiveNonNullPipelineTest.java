package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline-tier coverage: the implicit-predicate BodyParam.nonNull slot reflects
 * <em>effective</em> runtime nullability at the call site (the AND of the top-level argument's
 * declared nullability and every {@link no.sikt.graphitron.rewrite.model.InputField.NestingField}
 * on the path) rather than the inner field's own SDL-declared nullability. The conjunction lives
 * in {@link FieldBuilder#walkInputFieldConditions} ({@code effectiveNonNull}); a false result is
 * load-bearing because an unguarded {@code condition.and(table.col.in(null))} renders as the
 * jOOQ literal {@code false} and silently empties the result set. The three tests cover the
 * three transitions: nullable enclosing arg, both levels non-null, and a nullable intermediate
 * {@code NestingField} wrapper under a non-null arg (the recursive AND on the wrapper level,
 * which the first two cases cannot distinguish).
 *
 * <p>Asserts on the classified {@link BodyParam#nonNull()} slot, not on the rendered method
 * body (code-string assertions on emitted bodies are banned at every tier per
 * {@code docs/architecture/principles/development-principles.adoc}). Execution-tier coverage of
 * the runtime {@code .in(null)} rendering lives in the Sakila test
 * {@code GraphQLQueryTest.filmsByEffectiveNullability_omittedFilter_returnsUnfilteredBaseline}.
 */
@PipelineTier
class NestedInputFieldEffectiveNonNullPipelineTest {

    @Test
    void nullableEnclosingArg_nonNullInnerField_isNotEffectiveNonNull() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film implements Node @table(name: "film") @node(typeId: "Film", keyColumns: ["film_id"]) {
                id: ID! @field(name: "film_id")
            }
            input HentFilm {
                filmIds: [Int!]! @field(name: "film_id")
            }
            type Query {
                films(filter: HentFilm): [Film!]!
            }
            """);

        var bp = singleBodyParam(schema, "films");
        assertThat(bp).isInstanceOf(BodyParam.In.class);
        assertThat(bp.nonNull())
            .as("nullable enclosing arg defeats the inner field's own non-null declaration")
            .isFalse();
    }

    @Test
    void nonNullEnclosingArg_nonNullInnerField_stayEffectiveNonNull() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film implements Node @table(name: "film") @node(typeId: "Film", keyColumns: ["film_id"]) {
                id: ID! @field(name: "film_id")
            }
            input HentFilm {
                filmIds: [Int!]! @field(name: "film_id")
            }
            type Query {
                films(filter: HentFilm!): [Film!]!
            }
            """);

        var bp = singleBodyParam(schema, "films");
        assertThat(bp).isInstanceOf(BodyParam.In.class);
        assertThat(bp.nonNull())
            .as("both levels non-null: the unguarded condition.and(...) emission stays")
            .isTrue();
    }

    @Test
    void nonNullEnclosingArg_nullableNestingFieldWrapper_isNotEffectiveNonNull() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film implements Node @table(name: "film") @node(typeId: "Film", keyColumns: ["film_id"]) {
                id: ID! @field(name: "film_id")
            }
            input WrapperInput {
                filmIds: [Int!]! @field(name: "film_id")
            }
            input HentFilmWrapper {
                wrapper: WrapperInput
            }
            type Query {
                films(filter: HentFilmWrapper!): [Film!]!
            }
            """);

        var bp = singleBodyParam(schema, "films");
        assertThat(bp).isInstanceOf(BodyParam.In.class);
        assertThat(bp.nonNull())
            .as("nullable NestingField wrapper between non-null arg and non-null leaf: the AND"
                + " must still come out false. A buggy implementation that skips the wrapper-level"
                + " AND would leave this true and leak the unguarded .in(null) cascade.")
            .isFalse();
    }

    private static BodyParam singleBodyParam(GraphitronSchema schema, String fieldName) {
        var field = (QueryField.QueryTableField) schema.field("Query", fieldName);
        var gcf = (GeneratedConditionFilter) field.filters().stream()
            .filter(GeneratedConditionFilter.class::isInstance)
            .findFirst().orElseThrow();
        assertThat(gcf.bodyParams()).hasSize(1);
        return gcf.bodyParams().get(0);
    }
}
