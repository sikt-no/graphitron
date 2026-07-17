package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.MutationField.MutationDeleteTableField;
import no.sikt.graphitron.rewrite.model.MutationTableArgError;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code @mutation(table:)} field-relative DELETE write target. Pins the override rung
 * (rung 2) and the migration bridge (rung 3): a DELETE names its write target on the consuming field
 * rather than on the input type's deprecated {@code @table}. There is deliberately no return-derived
 * rung (a DELETE cannot return the deleted row's {@code @table} type), so every DELETE without
 * {@code @table} on its input must carry {@code @mutation(table:)}.
 */
@PipelineTier
class MutationTableArgClassificationTest {

    @Test
    void mutationTableArg_classifiesIdenticallyToTableOnInput() {
        // The @table-on-input form (the deprecated migration bridge) and the @mutation(table:) form
        // (the preferred override) must land byte-identical carriers: same write target, same
        // DeleteRows WHERE partition, same encoded-ID return expression.
        var tableOnInput = TestSchemaHelper.buildSchema("""
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId filmId: Int! @field(name: "film_id") }
            input FilmDeleteInput @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { x: String }
            type Mutation { deleteFilm(in: FilmDeleteInput!): ID @mutation(typeName: DELETE) }
            """);
        var mutationTableArg = TestSchemaHelper.buildSchema("""
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId filmId: Int! @field(name: "film_id") }
            input FilmDeleteInput { filmId: Int! @field(name: "film_id") }
            type Query { x: String }
            type Mutation { deleteFilm(in: FilmDeleteInput!): ID @mutation(typeName: DELETE, table: "film") }
            """);

        var a = (MutationDeleteTableField) tableOnInput.field("Mutation", "deleteFilm");
        var b = (MutationDeleteTableField) mutationTableArg.field("Mutation", "deleteFilm");
        assertThat(b.inputArg())
            .as("@mutation(table:) resolves the same InputArgRef (name, input type, jOOQ table, cardinality)")
            .isEqualTo(a.inputArg());
        assertThat(b.deleteRows())
            .as("@mutation(table:) resolves the same DeleteRows WHERE carrier")
            .isEqualTo(a.deleteRows());
        assertThat(b.returnExpression())
            .as("@mutation(table:) resolves the same encoded-ID return expression")
            .isEqualTo(a.returnExpression());
    }

    @Test
    void mutationTableArg_withNoTableOnInput_needsNoDeprecatedDirective() {
        // The whole point of the override rung: a DELETE with a bare ID return and no @table anywhere
        // on the input still classifies, because @mutation(table:) names the write target.
        var schema = TestSchemaHelper.buildSchema("""
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId filmId: Int! @field(name: "film_id") }
            input FilmDeleteInput { filmId: Int! @field(name: "film_id") }
            type Query { x: String }
            type Mutation { deleteFilm(in: FilmDeleteInput!): ID @mutation(typeName: DELETE, table: "film") }
            """);
        assertThat(schema.field("Mutation", "deleteFilm"))
            .as("a DELETE with @mutation(table:) and no @table on the input classifies")
            .isInstanceOf(MutationDeleteTableField.class);
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .as("the field-relative DELETE write target raises no error against the mutation or its input")
            .noneMatch(m -> m.contains("deleteFilm") || m.contains("FilmDeleteInput") || m.contains("write target"));
    }

    @Test
    void mutationTableArg_unknownTable_rejects() {
        var schema = TestSchemaHelper.buildSchema("""
            input FilmDeleteInput { filmId: Int! @field(name: "film_id") }
            type Query { x: String }
            type Mutation { deleteFilm(in: FilmDeleteInput!): ID @mutation(typeName: DELETE, table: "no_such_table") }
            """);
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("no_such_table") && m.contains("could not be resolved"));
    }

    @Test
    void noWriteTarget_rejectionLeadsWithMutationTableArg() {
        // No @table on the input and no @mutation(table:): the rejection must steer the author to the
        // preferred replacement first, and explain that the return cannot supply the table.
        var schema = TestSchemaHelper.buildSchema("""
            input FilmDeleteInput { filmId: Int! @field(name: "film_id") }
            type Query { x: String }
            type Mutation { deleteFilm(in: FilmDeleteInput!): ID @mutation(typeName: DELETE) }
            """);
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("has no write target")
                        && m.contains("@mutation(table:")
                        && m.indexOf("@mutation(table:") < m.indexOf("@table (deprecated)")
                        && m.contains("a @table return is not supported"));
    }

    @Test
    void mutationTableArg_onInsert_rejectsUnsupportedVerb() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmInput { title: String }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): ID @mutation(typeName: INSERT, table: "film") }
            """);
        var field = schema.field("Mutation", "createFilm");
        assertThat(field)
            .isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField.class);
        var rejection = ((no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField) field).rejection();
        assertThat(rejection)
            .as("@mutation(table:) on INSERT is a typed, sealed rejection with a stable LSP code")
            .isInstanceOf(MutationTableArgError.UnsupportedVerb.class);
        assertThat(((MutationTableArgError.UnsupportedVerb) rejection).lspCode())
            .isEqualTo("graphitron.mutation-table-arg.unsupported-verb");
        assertThat(rejection.message())
            .contains("INSERT")
            .contains("DELETE");
    }

    @Test
    void mutationTableArg_onUpdate_rejectsUnsupportedVerb() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmInput { filmId: Int! @field(name: "film_id") title: String }
            type Query { x: String }
            type Mutation { updateFilm(in: FilmInput!): Film @mutation(typeName: UPDATE, table: "film") }
            """);
        var field = schema.field("Mutation", "updateFilm");
        assertThat(field)
            .isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField.class);
        assertThat(((no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField) field).rejection())
            .isInstanceOf(MutationTableArgError.UnsupportedVerb.class);
    }

    @Test
    void validatorMirrorParity_nonOverrideUnboundField_rejectsOnBothPaths() {
        // Validator-bypass pin: a @condition(override: false) field with no resolving column is a
        // validateTableInputType rejection. On the field-derived path the input never lands in that
        // registry walk, so without the call-site mirror the rule would slip through. Both paths must
        // surface the same rule.
        String tableOnInputSdl = """
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId filmId: Int! @field(name: "film_id") }
            input FilmDeleteInput @table(name: "film") {
              filmId: Int! @field(name: "film_id")
              syntheticName: String
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "syntheticNameCondition"}, override: false)
            }
            type Query { x: String }
            type Mutation { deleteFilm(in: FilmDeleteInput!): ID @mutation(typeName: DELETE) }
            """;
        String fieldDerivedSdl = """
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId filmId: Int! @field(name: "film_id") }
            input FilmDeleteInput {
              filmId: Int! @field(name: "film_id")
              syntheticName: String
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "syntheticNameCondition"}, override: false)
            }
            type Query { x: String }
            type Mutation { deleteFilm(in: FilmDeleteInput!): ID @mutation(typeName: DELETE, table: "film") }
            """;

        assertThat(validate(TestSchemaHelper.buildSchema(tableOnInputSdl)))
            .extracting(ValidationError::message)
            .as("@table-on-input path surfaces the @condition(override: false) rule")
            .anyMatch(m -> m.contains("syntheticName") && m.contains("@condition(override: false)"));
        assertThat(validate(TestSchemaHelper.buildSchema(fieldDerivedSdl)))
            .extracting(ValidationError::message)
            .as("field-derived path mirrors the identical @condition(override: false) rule")
            .anyMatch(m -> m.contains("syntheticName") && m.contains("@condition(override: false)"));
    }
}
