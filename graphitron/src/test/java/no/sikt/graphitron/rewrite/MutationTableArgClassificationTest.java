package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField.SingleRecordIdFieldFromReturning;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.MutationField.MutationBulkDeletePayloadField;
import no.sikt.graphitron.rewrite.model.MutationField.MutationBulkDmlRecordField;
import no.sikt.graphitron.rewrite.model.MutationField.MutationDeletePayloadField;
import no.sikt.graphitron.rewrite.model.MutationField.MutationDeleteTableField;
import no.sikt.graphitron.rewrite.model.MutationField.MutationDmlRecordField;
import no.sikt.graphitron.rewrite.model.MutationField.MutationInsertTableField;
import no.sikt.graphitron.rewrite.model.MutationField.MutationBulkUpdatePayloadField;
import no.sikt.graphitron.rewrite.model.MutationField.MutationUpdatePayloadField;
import no.sikt.graphitron.rewrite.model.MutationField.MutationUpdateTableField;
import no.sikt.graphitron.rewrite.model.MutationTableArgError;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.rewrite.validation.FieldValidationTestHelper.validate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The field-relative DML write-target precedence. For DELETE it pins the override rung
 * ({@code @mutation(table:)}) and the migration bridge (input {@code @table}); a DELETE has no
 * return-derived rung (it cannot return the deleted row's {@code @table} type). For INSERT and UPDATE it
 * pins the full lattice: the return-derived rung (preferred), then {@code @mutation(table:)} (the
 * encoded-ID / scalar-return shape), then the deprecated input {@code @table} bridge, with the must-agree
 * cross-checks between rungs. INSERT and UPDATE share one resolver, so the cross-check semantics are
 * identical across the two verbs.
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
    void mutationTableArg_onInsert_classifiesViaRung2() {
        // INSERT joined TABLE_ARG_SUPPORTED_VERBS: @mutation(table:) is the rung-2 write target for the
        // encoded-ID / scalar-return shape whose return names no table. No @table on the input.
        var schema = TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "bar") @node { id: ID! @nodeId name: String }
            input BarInput { name: String }
            type Query { x: String }
            type Mutation { createBar(in: BarInput!): ID @mutation(typeName: INSERT, table: "bar") }
            """, NODEID_CTX);
        assertThat(schema.field("Mutation", "createBar"))
            .as("@mutation(table:) on INSERT names the rung-2 write target and classifies")
            .isInstanceOf(no.sikt.graphitron.rewrite.model.MutationField.MutationInsertTableField.class);
        assertThat(schema.diagnostics()).isEmpty();
    }

    @Test
    void mutationTableArg_onUpdate_classifiesViaRung2() {
        // UPDATE joined TABLE_ARG_SUPPORTED_VERBS: @mutation(table:) is the rung-2 write target for the
        // encoded-ID / scalar-return shape whose return names no table. No @table on the input.
        var schema = TestSchemaHelper.buildSchema("""
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId filmId: Int! @field(name: "film_id") title: String }
            input FilmInput { filmId: Int! @field(name: "film_id") title: String }
            type Query { x: String }
            type Mutation { updateFilm(in: FilmInput!): ID @mutation(typeName: UPDATE, table: "film") }
            """);
        assertThat(schema.field("Mutation", "updateFilm"))
            .as("@mutation(table:) on UPDATE names the rung-2 write target and classifies")
            .isInstanceOf(no.sikt.graphitron.rewrite.model.MutationField.MutationUpdateTableField.class);
        assertThat(schema.diagnostics()).isEmpty();
    }

    @Test
    void mutationTableArg_onUpsert_rejectsUnsupportedVerb() {
        // The unsupported-verb guard narrowed to {UPSERT}: @mutation(table:) on the one remaining
        // unwired verb still rejects loudly rather than being silently ignored.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmInput { filmId: Int! @field(name: "film_id") title: String }
            type Query { x: String }
            type Mutation { upsertFilm(in: FilmInput!): Film @mutation(typeName: UPSERT, table: "film") }
            """);
        var field = schema.field("Mutation", "upsertFilm");
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

    // ===== INSERT write target derived from the return type =====
    //
    // The INSERT lattice: rung 1 (the return's own @table, preferred), rung 2 (@mutation(table:)),
    // rung 3 (the deprecated input @table bridge), with must-agree cross-checks. An INSERT whose return
    // names the write target (a @table return, or a carrier payload's @table-element data field) needs
    // no @table on the input; dropping it must land a byte-identical carrier.

    @Test
    void insertPayload_bulk_returnDerived_classifiesLikeTableOnInput() {
        // The motivating case: a bulk payload-returning INSERT (list input, list @table-element data
        // field). The @table-on-input form and the return-derived form (no @table on the input) must
        // land byte-identical MutationBulkDmlRecordField carriers.
        // The input field is on its own line at identical indentation in both variants, so the only
        // textual difference (@table on the input's declaration line) does not shift the field's source
        // location; the carriers are then byte-identical, TableInputArg included.
        String common = """
            type Film @table(name: "film") { title: String }
            type FilmsPayload { films: [Film!] }
            type Query { x: String }
            """;
        var tableOnInput = TestSchemaHelper.buildSchema(common + """
            input FilmInput @table(name: "film") {
              title: String
            }
            type Mutation { createFilms(in: [FilmInput!]!): FilmsPayload @mutation(typeName: INSERT) }
            """);
        var returnDerived = TestSchemaHelper.buildSchema(common + """
            input FilmInput {
              title: String
            }
            type Mutation { createFilms(in: [FilmInput!]!): FilmsPayload @mutation(typeName: INSERT) }
            """);

        var a = (MutationBulkDmlRecordField) tableOnInput.field("Mutation", "createFilms");
        var b = (MutationBulkDmlRecordField) returnDerived.field("Mutation", "createFilms");
        assertThat(b.tableInputArg())
            .as("the return-derived INSERT resolves the same TableInputArg (table, cardinality, fields)")
            .isEqualTo(a.tableInputArg());
        assertThat(b.kind()).isEqualTo(a.kind());
        assertThat(b.errorChannel()).isEqualTo(a.errorChannel());
        assertThat(returnDerived.diagnostics()).isEmpty();
    }

    @Test
    void insertPayload_single_returnDerived_classifiesLikeTableOnInput() {
        String common = """
            type Film @table(name: "film") { title: String }
            type FilmPayload { film: Film }
            type Query { x: String }
            """;
        var tableOnInput = TestSchemaHelper.buildSchema(common + """
            input FilmInput @table(name: "film") {
              title: String
            }
            type Mutation { createFilm(in: FilmInput!): FilmPayload @mutation(typeName: INSERT) }
            """);
        var returnDerived = TestSchemaHelper.buildSchema(common + """
            input FilmInput {
              title: String
            }
            type Mutation { createFilm(in: FilmInput!): FilmPayload @mutation(typeName: INSERT) }
            """);

        var a = (MutationDmlRecordField) tableOnInput.field("Mutation", "createFilm");
        var b = (MutationDmlRecordField) returnDerived.field("Mutation", "createFilm");
        assertThat(b.tableInputArg())
            .as("the return-derived single-payload INSERT resolves the same TableInputArg")
            .isEqualTo(a.tableInputArg());
        assertThat(b.kind()).isEqualTo(a.kind());
        assertThat(returnDerived.diagnostics()).isEmpty();
    }

    @Test
    void insertDirectTableReturn_returnDerived_classifiesLikeTableOnInput() {
        // A direct @table return (createFilm(...): Film) names the write target on the return type;
        // dropping @table from the input lands the same MutationInsertTableField carrier.
        String common = """
            type Film @table(name: "film") { title: String }
            type Query { x: String }
            """;
        var tableOnInput = TestSchemaHelper.buildSchema(common + """
            input FilmInput @table(name: "film") {
              title: String
            }
            type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT) }
            """);
        var returnDerived = TestSchemaHelper.buildSchema(common + """
            input FilmInput {
              title: String
            }
            type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT) }
            """);

        var a = (MutationInsertTableField) tableOnInput.field("Mutation", "createFilm");
        var b = (MutationInsertTableField) returnDerived.field("Mutation", "createFilm");
        assertThat(b.tableInputArg())
            .as("the return-derived direct-@table-return INSERT resolves the same TableInputArg")
            .isEqualTo(a.tableInputArg());
        assertThat(b.returnExpression())
            .as("and the same projected return expression")
            .isEqualTo(a.returnExpression());
        assertThat(b.dialectRequirement()).isEqualTo(a.dialectRequirement());
        assertThat(returnDerived.diagnostics()).isEmpty();
    }

    @Test
    void insertPayload_returnTableVsInputTableMismatch_rejectsWithTableMatchWording() {
        // Rung 1 vs rung 3: the payload data field's @table (film) disagrees with the input's @table
        // (actor). The pre-existing requireDmlDataTableMatchesInputTable wording is kept byte-identical.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmPayload { film: Film }
            input FilmInput @table(name: "actor") { firstName: String @field(name: "first_name") }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): FilmPayload @mutation(typeName: INSERT) }
            """);
        assertThat(schema.field("Mutation", "createFilm")).isInstanceOf(UnclassifiedField.class);
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("does not match @table input table 'actor'"));
    }

    @Test
    void insertPayload_returnTableVsMutationTableMismatch_rejects() {
        // Rung 1 vs rung 2: the return derives 'film', @mutation(table:) names 'actor'. The RETURNING
        // projection reads from the write target, so the two cannot emit a coherent statement.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmPayload { film: Film }
            input FilmInput { title: String }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): FilmPayload @mutation(typeName: INSERT, table: "actor") }
            """);
        assertThat(schema.field("Mutation", "createFilm")).isInstanceOf(UnclassifiedField.class);
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("derives write target 'film'") && m.contains("different table 'actor'"));
    }

    @Test
    void insertPayload_returnDerivedWithUnresolvableMutationTable_rejects() {
        // Rung 1 present (return derives 'film') and @mutation(table:) names a table that does not
        // resolve. The single-producer helper short-circuits at rung 1 and never validates the table:
        // name, so this is the only site that catches it; an unresolvable table: rejects rather than
        // being silently ignored (a typo must not slip through where a real-but-different table rejects).
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmPayload { film: Film }
            input FilmInput { title: String }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): FilmPayload @mutation(typeName: INSERT, table: "no_such_table") }
            """);
        assertThat(schema.field("Mutation", "createFilm")).isInstanceOf(UnclassifiedField.class);
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("no_such_table") && m.contains("could not be resolved"));
    }

    @Test
    void insertEncoded_mutationTableOutranksInputTable() {
        // Rung 2 vs rung 3 (rung 1 absent, ID return): @mutation(table:) silently outranks the input's
        // deprecated @table, byte-matching the DELETE treatment of the same directive pair.
        var schema = TestSchemaHelper.buildSchema("""
            type Baz implements Node @table(name: "baz") @node(keyColumns: ["id"]) { id: ID! @nodeId }
            input BazInput @table(name: "shared_node") { key: String! @field(name: "id") }
            type Query { x: String }
            type Mutation { createBaz(in: BazInput!): ID @mutation(typeName: INSERT, table: "baz") }
            """, NODEID_CTX);

        var mut = (MutationInsertTableField) schema.field("Mutation", "createBaz");
        assertThat(mut.tableInputArg().inputTable().tableName())
            .as("@mutation(table:) outranks the input's @table as the INSERT write target")
            .isEqualTo("baz");
        assertThat(schema.diagnostics()).isEmpty();
    }

    @Test
    void insertNoWriteTarget_rejectionLeadsWithReturnDerivedFix() {
        // No rung resolves (a bare ID return, a plain input, no @mutation(table:)): the rejection leads
        // with the preferred return-derived fix, then @mutation(table:), then the deprecated @table.
        var schema = TestSchemaHelper.buildSchema("""
            input FilmInput { title: String }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): ID @mutation(typeName: INSERT) }
            """);
        assertThat(schema.field("Mutation", "createFilm")).isInstanceOf(UnclassifiedField.class);
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("has no write target")
                        && m.indexOf("return type") < m.indexOf("@mutation(table:")
                        && m.indexOf("@mutation(table:") < m.indexOf("@table (the deprecated bridge)"));
    }

    @Test
    void insert_lookupKeyOnInputField_rejectsOnBothPaths() {
        // The INSERT admission set (rejectInputFieldDirectives) runs over the resolved fields regardless
        // of whether the write target came from the input's @table or the field's return.
        String common = """
            type Film @table(name: "film") { title: String }
            type Query { x: String }
            """;
        String tableOnInput = common + """
            input FilmInput @table(name: "film") { title: String @lookupKey }
            type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT) }
            """;
        String returnDerived = common + """
            input FilmInput { title: String @lookupKey }
            type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT) }
            """;
        assertThat(validate(TestSchemaHelper.buildSchema(tableOnInput)))
            .extracting(ValidationError::message)
            .as("@table-on-input path rejects @lookupKey on a mutation input field")
            .anyMatch(m -> m.contains("@lookupKey on a mutation input field is no longer supported"));
        assertThat(validate(TestSchemaHelper.buildSchema(returnDerived)))
            .extracting(ValidationError::message)
            .as("return-derived path mirrors the identical @lookupKey rejection")
            .anyMatch(m -> m.contains("@lookupKey on a mutation input field is no longer supported"));
    }

    @Test
    void insert_plainColumnCollision_rejectsOnBothPaths() {
        String common = """
            type Film @table(name: "film") { title: String }
            type Query { x: String }
            """;
        String tableOnInput = common + """
            input FilmInput @table(name: "film") { title: String @field(name: "title") altTitle: String @field(name: "title") }
            type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT) }
            """;
        String returnDerived = common + """
            input FilmInput { title: String @field(name: "title") altTitle: String @field(name: "title") }
            type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT) }
            """;
        assertThat(validate(TestSchemaHelper.buildSchema(tableOnInput)))
            .extracting(ValidationError::message)
            .as("@table-on-input path rejects the plain-column collision")
            .anyMatch(m -> m.contains("both resolve to column 'title'"));
        assertThat(validate(TestSchemaHelper.buildSchema(returnDerived)))
            .extracting(ValidationError::message)
            .as("return-derived path mirrors the identical plain-column collision")
            .anyMatch(m -> m.contains("both resolve to column 'title'"));
    }

    @Test
    void insert_compositeNodeIdCarrier_rejectsOnBothPaths() {
        // The INSERT composite-@nodeId carve-out (admitMutationInputFields) fires identically whether
        // the write target came from the input's @table or the field's @table return.
        String common = """
            type Bar implements Node @table(name: "bar") @node(keyColumns: ["id_1", "id_2"]) { id: ID! @nodeId name: String }
            type Query { x: String }
            """;
        String tableOnInput = common + """
            input BarInput @table(name: "bar") { id: ID! @nodeId(typeName: "Bar") }
            type Mutation { createBar(in: BarInput!): Bar @mutation(typeName: INSERT) }
            """;
        String returnDerived = common + """
            input BarInput { id: ID! @nodeId(typeName: "Bar") }
            type Mutation { createBar(in: BarInput!): Bar @mutation(typeName: INSERT) }
            """;
        assertThat(validate(TestSchemaHelper.buildSchema(tableOnInput, NODEID_CTX)))
            .extracting(ValidationError::message)
            .as("@table-on-input path rejects the composite-@nodeId INSERT carrier")
            .anyMatch(m -> m.contains("composite-key") && m.contains("@mutation(typeName: INSERT)"));
        assertThat(validate(TestSchemaHelper.buildSchema(returnDerived, NODEID_CTX)))
            .extracting(ValidationError::message)
            .as("return-derived path mirrors the identical composite-@nodeId rejection")
            .anyMatch(m -> m.contains("composite-key") && m.contains("@mutation(typeName: INSERT)"));
    }

    // ===== UPDATE write target derived from the return type =====
    //
    // The UPDATE lattice is the INSERT lattice (they share resolveReturnCapableWriteTarget): rung 1 (the
    // return's own @table, preferred), rung 2 (@mutation(table:)), rung 3 (the deprecated input @table
    // bridge), with must-agree cross-checks. An UPDATE whose return names the write target (a @table
    // return, or a carrier payload's @table-element data field) needs no @table on the input; dropping it
    // must land a byte-identical carrier. Equivalence is asserted on the classified-model carriers
    // (InputArgRef, UpdateRows), never a string diff of emitted bodies.

    @Test
    void updateDirectTableReturn_returnDerived_classifiesLikeTableOnInput() {
        // A direct @table return (updateFilm(...): Film) names the write target on the return type;
        // dropping @table from the input lands the same MutationUpdateTableField carrier. The input
        // covers the film PK (film_id) so the walker's PK-or-UK identification succeeds.
        String common = """
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") title: String }
            type Query { x: String }
            """;
        var tableOnInput = TestSchemaHelper.buildSchema(common + """
            input FilmUpdateInput @table(name: "film") {
              filmId: Int! @field(name: "film_id")
              title: String @field(name: "title")
            }
            type Mutation { updateFilm(in: FilmUpdateInput!): Film @mutation(typeName: UPDATE) }
            """);
        var returnDerived = TestSchemaHelper.buildSchema(common + """
            input FilmUpdateInput {
              filmId: Int! @field(name: "film_id")
              title: String @field(name: "title")
            }
            type Mutation { updateFilm(in: FilmUpdateInput!): Film @mutation(typeName: UPDATE) }
            """);

        var a = (MutationUpdateTableField) tableOnInput.field("Mutation", "updateFilm");
        var b = (MutationUpdateTableField) returnDerived.field("Mutation", "updateFilm");
        assertThat(b.inputArg())
            .as("the return-derived direct-@table-return UPDATE resolves the same InputArgRef")
            .isEqualTo(a.inputArg());
        assertThat(b.updateRows())
            .as("and the same UpdateRows SET/WHERE carrier")
            .isEqualTo(a.updateRows());
        assertThat(b.returnExpression())
            .as("and the same projected return expression")
            .isEqualTo(a.returnExpression());
        assertThat(b.dialectRequirement()).isEqualTo(a.dialectRequirement());
        assertThat(returnDerived.diagnostics()).isEmpty();
    }

    @Test
    void updatePayload_single_returnDerived_classifiesLikeTableOnInput() {
        String common = """
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") title: String }
            type FilmPayload { film: Film }
            type Query { x: String }
            """;
        var tableOnInput = TestSchemaHelper.buildSchema(common + """
            input FilmUpdateInput @table(name: "film") {
              filmId: Int! @field(name: "film_id")
              title: String @field(name: "title")
            }
            type Mutation { updateFilmPayload(in: FilmUpdateInput!): FilmPayload @mutation(typeName: UPDATE) }
            """);
        var returnDerived = TestSchemaHelper.buildSchema(common + """
            input FilmUpdateInput {
              filmId: Int! @field(name: "film_id")
              title: String @field(name: "title")
            }
            type Mutation { updateFilmPayload(in: FilmUpdateInput!): FilmPayload @mutation(typeName: UPDATE) }
            """);

        var a = (MutationUpdatePayloadField) tableOnInput.field("Mutation", "updateFilmPayload");
        var b = (MutationUpdatePayloadField) returnDerived.field("Mutation", "updateFilmPayload");
        assertThat(b.inputArg())
            .as("the return-derived single-payload UPDATE resolves the same InputArgRef")
            .isEqualTo(a.inputArg());
        assertThat(b.updateRows())
            .as("and the same UpdateRows SET/WHERE carrier")
            .isEqualTo(a.updateRows());
        assertThat(b.errorChannel()).isEqualTo(a.errorChannel());
        assertThat(returnDerived.diagnostics()).isEmpty();
    }

    @Test
    void updatePayload_bulk_returnDerived_classifiesLikeTableOnInput() {
        // A bulk payload-returning UPDATE (list input, list @table-element data field). The
        // @table-on-input form and the return-derived form must land byte-identical
        // MutationBulkUpdatePayloadField carriers.
        String common = """
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") title: String }
            type FilmsPayload { films: [Film!] }
            type Query { x: String }
            """;
        var tableOnInput = TestSchemaHelper.buildSchema(common + """
            input FilmUpdateInput @table(name: "film") {
              filmId: Int! @field(name: "film_id")
              title: String @field(name: "title")
            }
            type Mutation { updateFilms(in: [FilmUpdateInput!]!): FilmsPayload @mutation(typeName: UPDATE) }
            """);
        var returnDerived = TestSchemaHelper.buildSchema(common + """
            input FilmUpdateInput {
              filmId: Int! @field(name: "film_id")
              title: String @field(name: "title")
            }
            type Mutation { updateFilms(in: [FilmUpdateInput!]!): FilmsPayload @mutation(typeName: UPDATE) }
            """);

        var a = (MutationBulkUpdatePayloadField) tableOnInput.field("Mutation", "updateFilms");
        var b = (MutationBulkUpdatePayloadField) returnDerived.field("Mutation", "updateFilms");
        assertThat(b.inputArg())
            .as("the return-derived bulk-payload UPDATE resolves the same bulk InputArgRef")
            .isEqualTo(a.inputArg());
        assertThat(b.updateRows())
            .as("and the same bulk UpdateRows SET/WHERE carrier")
            .isEqualTo(a.updateRows());
        assertThat(returnDerived.diagnostics()).isEmpty();
    }

    @Test
    void updateReturnTableVsInputTableMismatch_rejectsWithTableMatchWording() {
        // Rung 1 vs rung 3: the payload data field's @table (film) disagrees with the input's @table
        // (actor). The pre-existing requireDmlDataTableMatchesInputTable wording is verb-parameterised
        // and identical to INSERT's.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") title: String }
            type FilmPayload { film: Film }
            input FilmUpdateInput @table(name: "actor") { firstName: String @field(name: "first_name") }
            type Query { x: String }
            type Mutation { updateFilm(in: FilmUpdateInput!): FilmPayload @mutation(typeName: UPDATE) }
            """);
        assertThat(schema.field("Mutation", "updateFilm")).isInstanceOf(UnclassifiedField.class);
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("does not match @table input table 'actor'"));
    }

    @Test
    void updateNoWriteTarget_rejectionLeadsWithReturnDerivedFix() {
        // No rung resolves (a bare ID return, a plain input, no @mutation(table:)): the rejection leads
        // with the preferred return-derived fix, then @mutation(table:), then the deprecated @table.
        var schema = TestSchemaHelper.buildSchema("""
            input FilmInput { title: String }
            type Query { x: String }
            type Mutation { updateFilm(in: FilmInput!): ID @mutation(typeName: UPDATE) }
            """);
        assertThat(schema.field("Mutation", "updateFilm")).isInstanceOf(UnclassifiedField.class);
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("has no write target")
                        && m.indexOf("return the row's @table type") < m.indexOf("@mutation(table:")
                        && m.indexOf("@mutation(table:") < m.indexOf("@table (the deprecated bridge)"));
    }
}
