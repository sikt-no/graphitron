package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField.SingleRecordIdFieldFromReturning;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.MutationField.MutationBulkDeletePayloadField;
import no.sikt.graphitron.rewrite.model.MutationField.MutationDeletePayloadField;
import no.sikt.graphitron.rewrite.model.MutationField.MutationDeleteTableField;
import no.sikt.graphitron.rewrite.model.MutationTableArgError;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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

    /**
     * The payload-returning DELETE cases carry an ID PK-echo data field, whose encoder resolves
     * against the write target's {@code @node}. The default Sakila catalog is plain jOOQ-generated and
     * carries no NodeId metadata, so those cases use the {@code nodeidfixture} catalog where {@code Baz}
     * (single {@code id} PK) and {@code Bar} (composite {@code id_1, id_2} PK) are hand-instrumented,
     * mirroring {@code MutationDmlNodeIdClassificationTest}.
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

    // ===== Payload-returning DELETE (the DmlEmitted @mutation(table:) grounding) =====
    //
    // The core of the item: a payload-returning DELETE that names its write target on
    // @mutation(table:) (with the deprecated @table removed from the input) must survive
    // classification, not reject for want of a producer binding. Before the fix the binding grounder read the write
    // target only off the input's @table, so a field-derived DELETE grounded no DmlEmitted, the payload
    // never registered as a producer-backed carrier, the return classified down the ScalarReturnType
    // arm, and the generic "return type not yet supported" rejection fired. Equivalence is asserted on
    // the classified-model verdict (the field variant, the resolved carriers), never a string diff of
    // generated bodies.

    @Test
    void payloadDelete_single_mutationTableArg_classifiesLikeTableOnInput() {
        String common = """
            type Baz implements Node @table(name: "baz") @node(keyColumns: ["id"]) { id: ID! @nodeId }
            type ValidationErr @error(handlers: [{handler: VALIDATION}]) { path: [String!]! message: String! }
            type DbErr @error(handlers: [{handler: DATABASE, sqlState: "23503"}]) { path: [String!]! message: String! }
            union DeleteBazError = ValidationErr | DbErr
            type DeletedBazPayload { deletedId: ID @nodeId(typeName: "Baz") errors: [DeleteBazError] }
            type Query { x: String }
            """;
        var tableOnInput = TestSchemaHelper.buildSchema(common + """
            input DeleteBazInput @table(name: "baz") { id: ID! @nodeId(typeName: "Baz") }
            type Mutation { deleteBaz(in: DeleteBazInput!): DeletedBazPayload @mutation(typeName: DELETE) }
            """, NODEID_CTX);
        var mutationTableArg = TestSchemaHelper.buildSchema(common + """
            input DeleteBazInput { id: ID! @nodeId(typeName: "Baz") }
            type Mutation { deleteBaz(in: DeleteBazInput!): DeletedBazPayload @mutation(typeName: DELETE, table: "baz") }
            """, NODEID_CTX);

        var a = (MutationDeletePayloadField) tableOnInput.field("Mutation", "deleteBaz");
        var b = (MutationDeletePayloadField) mutationTableArg.field("Mutation", "deleteBaz");
        assertThat(b.inputArg())
            .as("@mutation(table:) resolves the same InputArgRef (name, input type, jOOQ table, cardinality)")
            .isEqualTo(a.inputArg());
        assertThat(b.deleteRows())
            .as("@mutation(table:) resolves the same DeleteRows WHERE carrier")
            .isEqualTo(a.deleteRows());
        assertThat(b.errorChannel())
            .as("@mutation(table:) resolves the same DML error channel")
            .isEqualTo(a.errorChannel());

        var carrierA = (SingleRecordIdFieldFromReturning) tableOnInput.field("DeletedBazPayload", "deletedId");
        var carrierB = (SingleRecordIdFieldFromReturning) mutationTableArg.field("DeletedBazPayload", "deletedId");
        assertThat(carrierB.encode().encodeMethod())
            .as("@mutation(table:) resolves the same PK-echo encoder for the carrier data field")
            .isEqualTo(carrierA.encode().encodeMethod());
    }

    @Test
    void payloadDelete_bulk_mutationTableArg_classifiesLikeTableOnInput() {
        String common = """
            type Bar implements Node @table(name: "bar") @node(keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
                name: String
            }
            type DeletedBarsPayload { deletedIds: [ID!] @nodeId(typeName: "Bar") }
            type Query { x: String }
            """;
        var tableOnInput = TestSchemaHelper.buildSchema(common + """
            input DeleteBarInput @table(name: "bar") { id: ID! @nodeId(typeName: "Bar") }
            type Mutation { deleteBars(in: [DeleteBarInput!]!): DeletedBarsPayload @mutation(typeName: DELETE) }
            """, NODEID_CTX);
        var mutationTableArg = TestSchemaHelper.buildSchema(common + """
            input DeleteBarInput { id: ID! @nodeId(typeName: "Bar") }
            type Mutation { deleteBars(in: [DeleteBarInput!]!): DeletedBarsPayload @mutation(typeName: DELETE, table: "bar") }
            """, NODEID_CTX);

        var a = (MutationBulkDeletePayloadField) tableOnInput.field("Mutation", "deleteBars");
        var b = (MutationBulkDeletePayloadField) mutationTableArg.field("Mutation", "deleteBars");
        assertThat(b.inputArg())
            .as("@mutation(table:) resolves the same bulk InputArgRef")
            .isEqualTo(a.inputArg());
        assertThat(b.deleteRows())
            .as("@mutation(table:) resolves the same bulk DeleteRows WHERE carrier")
            .isEqualTo(a.deleteRows());

        var carrierA = (SingleRecordIdFieldFromReturning) tableOnInput.field("DeletedBarsPayload", "deletedIds");
        var carrierB = (SingleRecordIdFieldFromReturning) mutationTableArg.field("DeletedBarsPayload", "deletedIds");
        assertThat(carrierB.encode().encodeMethod())
            .as("@mutation(table:) resolves the same PK-echo encoder for the bulk carrier data field")
            .isEqualTo(carrierA.encode().encodeMethod());
    }

    @Test
    void payloadDelete_bothPresent_fieldTableWins() {
        // Both rungs present and disagreeing: input @table names shared_node, @mutation(table:) names
        // baz (both single "id" PK). The payload's PK-echo pins @nodeId(typeName: "Baz"), so the field
        // classifies only if the write target is baz (the field rung). Were the input @table to win,
        // the carrier's Baz-pinned encoder would mismatch shared_node and reject. This is the drift the
        // shared precedence helper forecloses: the grounder can no longer bind a DmlEmitted on the
        // input's table while the classifier resolves the field's.
        var schema = TestSchemaHelper.buildSchema("""
            type Baz implements Node @table(name: "baz") @node(keyColumns: ["id"]) { id: ID! @nodeId }
            input DeleteInput @table(name: "shared_node") { key: String! @field(name: "id") }
            type DeletedPayload { deletedId: ID @nodeId(typeName: "Baz") }
            type Query { x: String }
            type Mutation { del(in: DeleteInput!): DeletedPayload @mutation(typeName: DELETE, table: "baz") }
            """, NODEID_CTX);

        var mut = (MutationDeletePayloadField) schema.field("Mutation", "del");
        assertThat(mut.inputArg().table().tableName())
            .as("@mutation(table:) outranks the input's @table as the write target")
            .isEqualTo("baz");
        var carrier = (SingleRecordIdFieldFromReturning) schema.field("DeletedPayload", "deletedId");
        assertThat(carrier.encode().encodeMethod().methodName()).isEqualTo("encodeBaz");
        assertThat(schema.diagnostics()).isEmpty();
    }

    @Test
    void payloadDelete_unknownMutationTable_rejectsLoudlyOnPayloadArm() {
        // Position 4's dispatch pin: a payload-returning DELETE whose @mutation(table:) names an
        // unknown table must still land in classifyDeletePayloadField (which calls
        // resolveDeleteWriteTarget), so the loud unknown-table rejection fires rather than a silent
        // misground. The grounder's silent skip must not swallow the diagnostic.
        var schema = TestSchemaHelper.buildSchema("""
            type Baz implements Node @table(name: "baz") @node(keyColumns: ["id"]) { id: ID! @nodeId }
            input DeleteBazInput { id: ID! @nodeId(typeName: "Baz") }
            type DeletedBazPayload { deletedId: ID @nodeId(typeName: "Baz") }
            type Query { x: String }
            type Mutation { deleteBaz(in: DeleteBazInput!): DeletedBazPayload @mutation(typeName: DELETE, table: "no_such_table") }
            """, NODEID_CTX);

        assertThat(schema.field("Mutation", "deleteBaz"))
            .as("the payload-returning arm rejects rather than silently mis-grounding")
            .isInstanceOf(UnclassifiedField.class);
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("no_such_table") && m.contains("could not be resolved"));
    }
}
