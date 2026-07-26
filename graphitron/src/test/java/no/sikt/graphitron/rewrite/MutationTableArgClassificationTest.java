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
 * The field-relative DML write-target precedence. For DELETE it pins the {@code @mutation(table:)}
 * rung; a DELETE has no return-derived rung (it cannot return the deleted row's {@code @table}
 * type). For INSERT and UPDATE it pins the two-rung lattice: the return-derived rung (preferred),
 * then {@code @mutation(table:)} (the encoded-ID / scalar-return shape), with the must-agree
 * cross-check between the rungs. INSERT and UPDATE share one resolver, so the cross-check
 * semantics are identical across the two verbs.
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
    void mutationTableArg_namesTheDeleteWriteTarget() {
        // A DELETE with a bare ID return classifies because @mutation(table:) names the write
        // target; the input carries no table fact of its own.
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
        // No @mutation(table:): the rejection names the fix and explains that the return cannot
        // supply the table for a DELETE.
        var schema = TestSchemaHelper.buildSchema("""
            input FilmDeleteInput { filmId: Int! @field(name: "film_id") }
            type Query { x: String }
            type Mutation { deleteFilm(in: FilmDeleteInput!): ID @mutation(typeName: DELETE) }
            """);
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("has no write target")
                        && m.contains("@mutation(table:")
                        && m.contains("a @table return is not supported"));
    }

    @Test
    void mutationTableArg_onInsert_classifiesViaRung2() {
        // INSERT is in TABLE_ARG_SUPPORTED_VERBS: @mutation(table:) is the rung-2 write target for
        // the encoded-ID / scalar-return shape whose return names no table. No @table on the input.
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
        // UPDATE is in TABLE_ARG_SUPPORTED_VERBS: @mutation(table:) is the rung-2 write target for
        // the encoded-ID / scalar-return shape whose return names no table. No @table on the input.
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
        // UPSERT is the one verb outside TABLE_ARG_SUPPORTED_VERBS: @mutation(table:) on it
        // rejects loudly rather than being silently ignored.
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
    void validatorMirror_nonOverrideUnboundField_rejectsOnTheFieldDerivedPath() {
        // Validator-bypass pin: a @condition(override: false) field with no resolving column is a
        // validator-side input-field rejection. Input fields are resolved per consuming field
        // (never in a registry type walk), so without the call-site mirror
        // (GraphitronSchemaValidator.collectInputFieldRejections) the rule would slip through.
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

        assertThat(validate(TestSchemaHelper.buildSchema(fieldDerivedSdl)))
            .extracting(ValidationError::message)
            .as("field-derived path surfaces the @condition(override: false) rule")
            .anyMatch(m -> m.contains("syntheticName") && m.contains("@condition(override: false)"));
    }

    // ===== Payload-returning DELETE (the DmlEmitted @mutation(table:) grounding) =====
    //
    // A payload-returning DELETE that names its write target on @mutation(table:) (no @table on
    // the input) must survive classification, not reject for want of a producer binding: the
    // binding grounder must ground a DmlEmitted from the field-relative write target, or the
    // payload never registers as a producer-backed carrier and the return falls down the
    // ScalarReturnType arm to the generic "return type not yet supported" rejection. Equivalence
    // is asserted on the classified-model verdict (the field variant, the resolved carriers),
    // never a string diff of generated bodies.

    @Test
    void payloadDelete_single_mutationTableArg_groundsCarriersFromTheFieldTable() {
        var schema = TestSchemaHelper.buildSchema("""
            type Baz implements Node @table(name: "baz") @node(keyColumns: ["id"]) { id: ID! @nodeId }
            type ValidationErr @error(handlers: [{handler: VALIDATION}]) { path: [String!]! message: String! }
            type DbErr @error(handlers: [{handler: DATABASE, sqlState: "23503"}]) { path: [String!]! message: String! }
            union DeleteBazError = ValidationErr | DbErr
            type DeletedBazPayload { deletedId: ID @nodeId(typeName: "Baz") errors: [DeleteBazError] }
            type Query { x: String }
            input DeleteBazInput { id: ID! @nodeId(typeName: "Baz") }
            type Mutation { deleteBaz(in: DeleteBazInput!): DeletedBazPayload @mutation(typeName: DELETE, table: "baz") }
            """, NODEID_CTX);

        var mut = (MutationDeletePayloadField) schema.field("Mutation", "deleteBaz");
        assertThat(mut.inputArg().table().tableName())
            .as("@mutation(table:) grounds the write target")
            .isEqualTo("baz");
        assertThat(mut.errorChannel())
            .as("the structural DML error channel is detected on the field-relative path")
            .isPresent();
        var carrier = (SingleRecordIdFieldFromReturning) schema.field("DeletedBazPayload", "deletedId");
        assertThat(carrier.encode().encodeMethod().methodName())
            .as("the PK-echo encoder resolves against the field-named table's @node")
            .isEqualTo("encodeBaz");
        assertThat(schema.diagnostics()).isEmpty();
    }

    @Test
    void payloadDelete_bulk_mutationTableArg_groundsCarriersFromTheFieldTable() {
        var schema = TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "bar") @node(keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
                name: String
            }
            type DeletedBarsPayload { deletedIds: [ID!] @nodeId(typeName: "Bar") }
            type Query { x: String }
            input DeleteBarInput { id: ID! @nodeId(typeName: "Bar") }
            type Mutation { deleteBars(in: [DeleteBarInput!]!): DeletedBarsPayload @mutation(typeName: DELETE, table: "bar") }
            """, NODEID_CTX);

        var mut = (MutationBulkDeletePayloadField) schema.field("Mutation", "deleteBars");
        assertThat(mut.inputArg().table().tableName())
            .as("@mutation(table:) grounds the bulk write target")
            .isEqualTo("bar");
        assertThat(mut.inputArg().list())
            .as("the bulk shape carries the list cardinality")
            .isTrue();
        var carrier = (SingleRecordIdFieldFromReturning) schema.field("DeletedBarsPayload", "deletedIds");
        assertThat(carrier.encode().encodeMethod().methodName())
            .as("the PK-echo encoder resolves against the field-named table's @node")
            .isEqualTo("encodeBar");
        assertThat(schema.diagnostics()).isEmpty();
    }

    @Test
    void payloadDelete_unknownMutationTable_rejectsLoudlyOnPayloadArm() {
        // Dispatch pin: a payload-returning DELETE whose @mutation(table:) names an
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
    // with the must-agree cross-check. An INSERT whose return names the write target (a @table
    // return, or a carrier payload's @table-element data field) needs nothing else.

    @Test
    void insertPayload_bulk_returnDerived_classifies() {
        // A bulk payload-returning INSERT (list input, list @table-element data field): the
        // payload's data field derives the write target; the input needs no directive.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmsPayload { films: [Film!] }
            type Query { x: String }
            input FilmInput {
              title: String
            }
            type Mutation { createFilms(in: [FilmInput!]!): FilmsPayload @mutation(typeName: INSERT) }
            """);

        var mut = (MutationBulkDmlRecordField) schema.field("Mutation", "createFilms");
        assertThat(mut.tableInputArg().inputTable().tableName())
            .as("the write target derives from the payload's @table-element data field")
            .isEqualTo("film");
        assertThat(mut.tableInputArg().list()).isTrue();
        assertThat(mut.kind()).isEqualTo(no.sikt.graphitron.rewrite.model.DmlKind.INSERT);
        assertThat(schema.diagnostics()).isEmpty();
    }

    @Test
    void insertPayload_single_returnDerived_classifies() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmPayload { film: Film }
            type Query { x: String }
            input FilmInput {
              title: String
            }
            type Mutation { createFilm(in: FilmInput!): FilmPayload @mutation(typeName: INSERT) }
            """);

        var mut = (MutationDmlRecordField) schema.field("Mutation", "createFilm");
        assertThat(mut.tableInputArg().inputTable().tableName())
            .as("the write target derives from the payload's @table data field")
            .isEqualTo("film");
        assertThat(mut.kind()).isEqualTo(no.sikt.graphitron.rewrite.model.DmlKind.INSERT);
        assertThat(schema.diagnostics()).isEmpty();
    }

    @Test
    void insertDirectTableReturn_returnDerived_classifies() {
        // A direct @table return (createFilm(...): Film) names the write target on the return type.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type Query { x: String }
            input FilmInput {
              title: String
            }
            type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT) }
            """);

        var mut = (MutationInsertTableField) schema.field("Mutation", "createFilm");
        assertThat(mut.tableInputArg().inputTable().tableName())
            .as("the write target derives from the @table return type")
            .isEqualTo("film");
        assertThat(schema.diagnostics()).isEmpty();
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
    void insertNoWriteTarget_rejectionLeadsWithReturnDerivedFix() {
        // No rung resolves (a bare ID return, no @mutation(table:)): the rejection leads with
        // the preferred return-derived fix, then @mutation(table:).
        var schema = TestSchemaHelper.buildSchema("""
            input FilmInput { title: String }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): ID @mutation(typeName: INSERT) }
            """);
        assertThat(schema.field("Mutation", "createFilm")).isInstanceOf(UnclassifiedField.class);
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("has no write target")
                        && m.indexOf("return the") < m.indexOf("@mutation(table:")
                        && m.contains("@mutation(table:"));
    }

    @Test
    void insert_lookupKeyOnInputField_rejects() {
        // The INSERT admission set (rejectInputFieldDirectives) runs over the resolved fields.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type Query { x: String }
            input FilmInput { title: String @lookupKey }
            type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT) }
            """);
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("@lookupKey on a mutation input field is no longer supported"));
    }

    @Test
    void insert_plainColumnCollision_rejects() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type Query { x: String }
            input FilmInput { title: String @field(name: "title") altTitle: String @field(name: "title") }
            type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT) }
            """);
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("both resolve to column 'title'"));
    }

    @Test
    void insert_compositeNodeIdCarrier_rejects() {
        // The INSERT composite-@nodeId carve-out (admitMutationInputFields) fires over the
        // resolved fields on the return-derived path.
        assertThat(validate(TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "bar") @node(keyColumns: ["id_1", "id_2"]) { id: ID! @nodeId name: String }
            type Query { x: String }
            input BarInput { id: ID! @nodeId(typeName: "Bar") }
            type Mutation { createBar(in: BarInput!): Bar @mutation(typeName: INSERT) }
            """, NODEID_CTX)))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("composite-key") && m.contains("@mutation(typeName: INSERT)"));
    }

    // ===== UPDATE write target derived from the return type =====
    //
    // The UPDATE lattice is the INSERT lattice (they share resolveReturnCapableWriteTarget):
    // rung 1 (the return's own @table, preferred), rung 2 (@mutation(table:)), with the
    // must-agree cross-check. Assertions read the classified-model carriers (InputArgRef,
    // UpdateRows), never a string diff of emitted bodies.

    @Test
    void updateDirectTableReturn_returnDerived_classifies() {
        // A direct @table return (updateFilm(...): Film) names the write target on the return
        // type. The input covers the film PK (film_id) so the walker's PK-or-UK identification
        // succeeds.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") title: String }
            type Query { x: String }
            input FilmUpdateInput {
              filmId: Int! @field(name: "film_id")
              title: String @field(name: "title")
            }
            type Mutation { updateFilm(in: FilmUpdateInput!): Film @mutation(typeName: UPDATE) }
            """);

        var mut = (MutationUpdateTableField) schema.field("Mutation", "updateFilm");
        assertThat(mut.inputArg().table().tableName())
            .as("the write target derives from the @table return type")
            .isEqualTo("film");
        assertThat(mut.updateRows())
            .as("the walker produced the SET/WHERE carrier against the derived table")
            .isNotNull();
        assertThat(schema.diagnostics()).isEmpty();
    }

    @Test
    void updatePayload_single_returnDerived_classifies() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") title: String }
            type FilmPayload { film: Film }
            type Query { x: String }
            input FilmUpdateInput {
              filmId: Int! @field(name: "film_id")
              title: String @field(name: "title")
            }
            type Mutation { updateFilmPayload(in: FilmUpdateInput!): FilmPayload @mutation(typeName: UPDATE) }
            """);

        var mut = (MutationUpdatePayloadField) schema.field("Mutation", "updateFilmPayload");
        assertThat(mut.inputArg().table().tableName())
            .as("the write target derives from the payload's @table data field")
            .isEqualTo("film");
        assertThat(mut.updateRows()).isNotNull();
        assertThat(schema.diagnostics()).isEmpty();
    }

    @Test
    void updatePayload_bulk_returnDerived_classifies() {
        // A bulk payload-returning UPDATE (list input, list @table-element data field).
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") title: String }
            type FilmsPayload { films: [Film!] }
            type Query { x: String }
            input FilmUpdateInput {
              filmId: Int! @field(name: "film_id")
              title: String @field(name: "title")
            }
            type Mutation { updateFilms(in: [FilmUpdateInput!]!): FilmsPayload @mutation(typeName: UPDATE) }
            """);

        var mut = (MutationBulkUpdatePayloadField) schema.field("Mutation", "updateFilms");
        assertThat(mut.inputArg().table().tableName())
            .as("the write target derives from the payload's @table-element data field")
            .isEqualTo("film");
        assertThat(mut.inputArg().list()).isTrue();
        assertThat(mut.updateRows()).isNotNull();
        assertThat(schema.diagnostics()).isEmpty();
    }

    @Test
    void updateReturnTableVsMutationTableMismatch_rejects() {
        // Rung 1 vs rung 2 on UPDATE: the payload data field derives 'film', @mutation(table:)
        // names 'actor'. The cross-check wording is verb-parameterised and identical to INSERT's.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") title: String }
            type FilmPayload { film: Film }
            input FilmUpdateInput { filmId: Int! @field(name: "film_id") title: String @field(name: "title") }
            type Query { x: String }
            type Mutation { updateFilm(in: FilmUpdateInput!): FilmPayload @mutation(typeName: UPDATE, table: "actor") }
            """);
        assertThat(schema.field("Mutation", "updateFilm")).isInstanceOf(UnclassifiedField.class);
        assertThat(validate(schema))
            .extracting(ValidationError::message)
            .anyMatch(m -> m.contains("derives write target 'film'") && m.contains("different table 'actor'"));
    }

    @Test
    void updateNoWriteTarget_rejectionLeadsWithReturnDerivedFix() {
        // No rung resolves (a bare ID return, no @mutation(table:)): the rejection leads with
        // the preferred return-derived fix, then @mutation(table:).
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
                        && m.contains("@mutation(table:"));
    }
}
