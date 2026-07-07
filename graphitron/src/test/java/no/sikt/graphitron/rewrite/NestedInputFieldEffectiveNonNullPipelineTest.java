package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R230 pipeline-tier coverage: the implicit-predicate BodyParam.nonNull slot reflects
 * <em>effective</em> runtime nullability at the call site (the AND of the top-level argument's
 * declared nullability and every {@link no.sikt.graphitron.rewrite.model.InputField.NestingField}
 * on the path) rather than the inner field's own SDL-declared nullability.
 *
 * <p>The three cases together exercise the three transitions of the conjunction lifted into
 * {@link FieldBuilder#walkInputFieldConditions} (signature {@code effectiveNonNull}):
 * <ol>
 *   <li>nullable enclosing arg + non-null inner field → false (the primary bug; without the fix
 *       the generator emits an unguarded {@code condition.and(table.col.in(null))} that jOOQ
 *       renders as the literal {@code false}, silently producing an empty result set);</li>
 *   <li>non-null enclosing arg + non-null inner field → true (pins the fix against
 *       over-correction; the unguarded emission stays);</li>
 *   <li>non-null enclosing arg + nullable intermediate {@code NestingField} + non-null inner
 *       field → false (exercises the recursive AND on the wrapper level; without it cases 1 and
 *       2 still pass while a buggy implementation that skips the wrapper-level AND leaves the
 *       inner field unguarded under a present-but-unset intermediate Map).</li>
 * </ol>
 *
 * <p>Asserts on the classified {@link BodyParam#nonNull()} slot, not on the rendered method
 * body (code-string assertions on emitted bodies are banned at every tier per
 * {@code docs/architecture/explanation/development-principles.adoc}). Execution-tier coverage of
 * the runtime {@code .in(null) -> false} rendering rides on the Sakila execution test that
 * exercises a list-shaped optional filter through real Postgres.
 */
@PipelineTier
class NestedInputFieldEffectiveNonNullPipelineTest {

    @Test
    void nullableEnclosingArg_nonNullInnerField_isNotEffectiveNonNull() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film implements Node @table(name: "film") @node(typeId: "Film", keyColumns: ["film_id"]) {
                id: ID! @field(name: "film_id")
            }
            input HentFilm @table(name: "film") {
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
            input HentFilm @table(name: "film") {
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
            input HentFilmWrapper @table(name: "film") {
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
