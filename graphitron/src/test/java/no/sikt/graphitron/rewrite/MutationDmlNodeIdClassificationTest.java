package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.rewrite.model.DmlReturnExpression;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.MutationField;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static no.sikt.graphitron.rewrite.DmlWriteReads.deleteRowsOf;
import static no.sikt.graphitron.rewrite.DmlWriteReads.updateRowsOf;
import static no.sikt.graphitron.rewrite.DmlWriteReads.insertInputOf;
import static no.sikt.graphitron.rewrite.DmlWriteReads.deleteArgOf;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import no.sikt.graphitron.model.diagnostics.DeleteRowsError;
import no.sikt.graphitron.model.diagnostics.MatchedKey;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.diagnostics.UpdateRowsError;

/**
 * Phase 1 mutation classifier coverage that depends on KjerneJooqGenerator-synthesised NodeId
 * metadata ({@code __NODE_TYPE_ID} / {@code __NODE_KEY_COLUMNS} on the table class). The default
 * Sakila test catalog is plain jOOQ-generated and does not carry these constants, so these cases
 * use the {@code nodeidfixture} catalog where {@code Bar} is hand-instrumented with both a
 * single-key path and a composite-key path (id_1, id_2) for the same fixture table.
 */
@PipelineTier
class MutationDmlNodeIdClassificationTest {

    private static final RunContext NODEID_CTX = new RunContext(
        List.of(),
        Path.of(""), "MutationDmlNodeIdClassificationTest",
        Path.of(""),
        "fake.code.generated",
        "no.sikt.graphitron.rewrite.nodeidfixture"
    );

    @Test
    void idReturnOnNodeTable_populatesEncodeReturn() {
        var schema = TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "bar") @node { id: ID! @nodeId name: String }
            input BarInput { name: String }
            type Query { x: String }
            type Mutation { createBar(in: BarInput!): ID @mutation(typeName: INSERT, table: "bar") }
            """, NODEID_CTX);

        var f = (MutationField.DmlTableField) schema.field("Mutation", "createBar");
        var rex = (DmlReturnExpression.EncodedSingle) f.returnExpression();
        assertThat(rex.encode().methodName()).isEqualTo("encodeBar");
        assertThat(rex.encode().paramSignature())
            .extracting(ColumnRef::sqlName)
            .containsExactly("id_1", "id_2");
    }

    @Test
    void idReturnWithoutNodeDeclaration_rejected() {
        // Bar's table has __NODE_TYPE_ID metadata but the SDL omits @node, so no NodeType is
        // registered for it. The mutation classifier requires a NodeType match by table SQL
        // name to wire encodeReturn; without one, returning ID has no per-type encoder to
        // delegate to and the field is rejected at validate time.
        var schema = TestSchemaHelper.buildSchema("""
            type Bar @table(name: "bar") { name: String }
            input BarInput { name: String }
            type Query { x: String }
            type Mutation { createBar(in: BarInput!): ID @mutation(typeName: INSERT, table: "bar") }
            """, NODEID_CTX);

        var f = (UnclassifiedField) schema.field("Mutation", "createBar");
        assertThat(f.reason()).contains("no @node type is declared for table 'bar'");
    }

    @Test
    void idReturnOnNonNodeTable_rejected() {
        var schema = TestSchemaHelper.buildSchema("""
            type Qux @table(name: "qux") { name: String }
            input QuxInput { name: String }
            type Query { x: String }
            type Mutation { createQux(in: QuxInput!): ID @mutation(typeName: INSERT, table: "qux") }
            """, NODEID_CTX);

        var f = (UnclassifiedField) schema.field("Mutation", "createQux");
        assertThat(f.reason())
            .contains("no @node type is declared for table 'qux'");
    }

    @Test
    void twoPlainFieldsOnOneColumn_rejected() {
        // Two plain @field leaves resolving to one column is a pure schema fact, so it rejects at
        // validate time instead of crashing in Postgres with "column specified more than once". The
        // ground is this path's own mechanism: the INSERT's SET map holds one value per column. An
        // overlap involving a @nodeId decode is admitted instead. (Not a general "one column takes one
        // field" rule: the @service jOOQ-record path admits a @deprecated-declared alias group, whose
        // runtime is a presence-guarded per-column load rather than a SET map.)
        var schema = TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "bar") @node(typeId: "Bar", keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
                name: String
            }
            input BarCollisionInput {
                name: String @field(name: "name")
                alias: String @field(name: "name")
            }
            type Query { bar: Bar }
            type Mutation { createBar(in: BarCollisionInput!): ID @mutation(typeName: INSERT, table: "bar") }
            """, NODEID_CTX);

        var f = (UnclassifiedField) schema.field("Mutation", "createBar");
        assertThat(f.reason())
            .contains("the INSERT's SET map holds one value per column")
            .contains("column 'name'")
            .contains("'alias'");
    }

    @Test
    void twoPlainFieldsOnOneUpdateSetColumn_rejected() {
        // The UPDATE path rejects on its own two mechanisms: two plain @field's on one SET column would
        // silently clobber in the single-row map, and the bulk VALUES-join cannot name one derived
        // column twice, which is what makes the @service path's @deprecated-alias relaxation unsound
        // here. id_1/id_2 cover the PK (the WHERE); name/alias are the colliding SET writers.
        var schema = TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "bar") @node(typeId: "Bar", keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
                name: String
            }
            input BarUpdateCollisionInput {
                idOne: Int! @field(name: "id_1")
                idTwo: Int! @field(name: "id_2")
                name: String @field(name: "name")
                alias: String @field(name: "name")
            }
            type Query { bar: Bar }
            type Mutation { updateBar(in: BarUpdateCollisionInput!): Bar @mutation(typeName: UPDATE) }
            """, NODEID_CTX);

        var f = (UnclassifiedField) schema.field("Mutation", "updateBar");
        assertThat(f.reason())
            .contains("the single-row SET map holds one value per column")
            .contains("VALUES join cannot name one derived column twice")
            .contains("column 'name'")
            .contains("'alias'");
    }

    @Test
    void idReturnOnMultiNodeTable_ambiguous_rejected() {
        // A table may legitimately back several @node types (distinct node ids), so
        // multiple-nodes-per-table is allowed at the type level. But a bare-ID mutation return uses
        // the implicit "encoder for this table" form, which has no single answer when the input
        // @table backs more than one node; the field is rejected at its use site with a
        // disambiguation hint (return the specific @node type), not by a global type-level guard.
        var schema = TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "bar") @node(typeId: "Bar", keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
                name: String
            }
            type BarTwo implements Node @table(name: "bar") @node(typeId: "BarTwo", keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
                name: String
            }
            input BarInput { name: String }
            type Query { bar: Bar barTwo: BarTwo }
            type Mutation { createBar(in: BarInput!): ID @mutation(typeName: INSERT, table: "bar") }
            """, NODEID_CTX);

        // Both node types survive (allowed); the mutation field is the only thing rejected.
        assertThat(schema.type("Bar")).isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronType.NodeType.class);
        assertThat(schema.type("BarTwo")).isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronType.NodeType.class);
        var f = (UnclassifiedField) schema.field("Mutation", "createBar");
        assertThat(f.reason())
            .contains("table 'bar'")
            .contains("Bar, BarTwo")
            .contains("ambiguous");
    }

    @Test
    void nodeIdFieldInInput_keyOnly_rejectedNoSetFields() {
        var schema = TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "bar") @node(keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
                name: String
            }
            input BarInput {
                id: ID! @nodeId
            }
            type Query { x: String }
            type Mutation { updateBar(in: BarInput!): ID @mutation(typeName: UPDATE, table: "bar") }
            """, NODEID_CTX);

        var f = (UnclassifiedField) schema.field("Mutation", "updateBar");
        // The composite-NodeId key covers the PK exactly, leaving nothing to SET; the walker
        // rejects with UpdateRowsError.NoSetFields.
        assertThat(f.rejection()).isInstanceOf(
            no.sikt.graphitron.model.diagnostics.UpdateRowsError.NoSetFields.class);
        assertThat(f.reason()).contains("nothing to set");
    }

    @Test
    void compositePkNodeIdLookupKey_delete_admitted() {
        // A composite-PK @nodeId-decoded carrier admits a DELETE: its projected whereColumns
        // share the SDL field name "id" and cover the composite PK.
        var schema = TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "bar") @node(keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
                name: String
            }
            input DeleteBarInput {
                id: ID! @nodeId
            }
            type Query { x: String }
            type Mutation { deleteBar(in: DeleteBarInput!): ID @mutation(typeName: DELETE, table: "bar") }
            """, NODEID_CTX);

        var f = (MutationField.DmlTableField) schema.field("Mutation", "deleteBar");
        var deleteRows = (no.sikt.graphitron.rewrite.model.DeleteRows.Identified) deleteRowsOf(f);
        assertThat(deleteRows.matchedKey()).isInstanceOf(
            no.sikt.graphitron.model.diagnostics.MatchedKey.PrimaryKey.class);
        assertThat(deleteRows.whereColumns()).hasSize(2);
        assertThat(deleteRows.whereColumns()).extracting(k -> k.sdlFieldName()).containsOnly("id");
        assertThat(deleteRows.whereColumns()).extracting(k -> k.targetColumn().sqlName())
            .containsExactly("id_1", "id_2");
    }

    @Test
    void ukCoveringDelete_admitsByUniqueKey_andMatchesUpdateKeyChoice() {
        // Shared-matcher parity: a DELETE whose input covers a UNIQUE key (not the PK) admits
        // as a single-row delete identified by that UK, and the DeleteRowsWalker / UpdateRowsWalker
        // pick the *same* key for equivalent inputs (both route through MatchedKeys.firstCovered).
        // parent_node has PK pk_id and a separate UNIQUE on alt_key.
        var deleteSchema = TestSchemaHelper.buildSchema("""
            type ParentNode implements Node @table(name: "parent_node") @node { id: ID! @nodeId pkId: String! @field(name: "pk_id") }
            input DeleteParentNodeInput { altKey: String! @field(name: "alt_key") }
            type Query { x: String }
            type Mutation { deleteParentNode(in: DeleteParentNodeInput!): ID @mutation(typeName: DELETE, table: "parent_node") }
            """, NODEID_CTX);
        var del = (MutationField.DmlTableField) deleteSchema.field("Mutation", "deleteParentNode");
        var deleteRows = (no.sikt.graphitron.rewrite.model.DeleteRows.Identified) deleteRowsOf(del);
        assertThat(deleteRows.matchedKey()).isInstanceOf(no.sikt.graphitron.model.diagnostics.MatchedKey.UniqueKey.class);
        assertThat(deleteRows.matchedKey().columns()).extracting(c -> c.sqlName()).containsExactly("alt_key");
        assertThat(deleteRows.whereColumns()).extracting(k -> k.targetColumn().sqlName()).containsExactly("alt_key");

        var updateSchema = TestSchemaHelper.buildSchema("""
            type ParentNode @table(name: "parent_node") { pkId: String! @field(name: "pk_id") }
            input UpdateParentNodeInput {
                altKey: String! @field(name: "alt_key")
                name: String @field(name: "name")
            }
            type Query { x: String }
            type Mutation { updateParentNode(in: UpdateParentNodeInput!): ParentNode @mutation(typeName: UPDATE) }
            """, NODEID_CTX);
        var upd = (MutationField.DmlTableField) updateSchema.field("Mutation", "updateParentNode");
        var updateRows = (no.sikt.graphitron.rewrite.model.UpdateRows.Identified) updateRowsOf(upd);
        assertThat(updateRows.matchedKey()).isInstanceOf(no.sikt.graphitron.model.diagnostics.MatchedKey.UniqueKey.class);
        assertThat(updateRows.matchedKey().columns()).extracting(c -> c.sqlName()).containsExactly("alt_key");

        // Parity: the equivalent UK-covering DELETE and UPDATE select the same catalog key.
        assertThat(deleteRows.matchedKey().keyName()).isEqualTo(updateRows.matchedKey().keyName());
    }

    @Test
    void compositePkNodeIdLookupKey_update_admitted() {
        var schema = TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "bar") @node(keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
                name: String
            }
            input UpdateBarInput {
                id: ID! @nodeId
                name: String
            }
            type Query { x: String }
            type Mutation { updateBar(in: UpdateBarInput!): ID @mutation(typeName: UPDATE, table: "bar") }
            """, NODEID_CTX);

        // The composite-NodeId key field projects to two KeyColumn entries sharing the SDL
        // field name "id"; name falls outside the matched key and partitions to SET.
        var f = (MutationField.DmlTableField) schema.field("Mutation", "updateBar");
        var updateRows = (no.sikt.graphitron.rewrite.model.UpdateRows.Identified) updateRowsOf(f);
        assertThat(updateRows.matchedKey()).isInstanceOf(
            no.sikt.graphitron.model.diagnostics.MatchedKey.PrimaryKey.class);
        assertThat(updateRows.keyColumns()).hasSize(2);
        assertThat(updateRows.keyColumns()).extracting(k -> k.sdlFieldName()).containsOnly("id");
        assertThat(updateRows.keyColumns()).extracting(k -> k.targetColumn().sqlName())
            .containsExactly("id_1", "id_2");
        assertThat(updateRows.setColumns()).hasSize(1);
        assertThat(updateRows.setColumns().get(0).sdlFieldName()).isEqualTo("name");
        assertThat(updateRows.setColumns().get(0).targetColumn().sqlName()).isEqualTo("name");
    }

    @Test
    void compositePkNodeId_upsert_rejected_underR144() {
        // UPSERT is refused outright: conflict-target uniqueness and bulk cardinality are
        // undesigned, so the classifier rejects it as Deferred.
        var schema = TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "bar") @node(keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
                name: String
            }
            input UpsertBarInput {
                id: ID! @nodeId
                name: String
            }
            type Query { x: String }
            type Mutation { upsertBar(in: UpsertBarInput!): ID @mutation(typeName: UPSERT) }
            """, NODEID_CTX);

        var f = (UnclassifiedField) schema.field("Mutation", "upsertBar");
        assertThat(f.reason())
            .contains("@mutation(typeName: UPSERT) is not yet supported");
    }

    @Test
    void compositePkNodeId_insert_rejected() {
        // The same-table carve-out: a composite @nodeId column carrier on
        // @mutation(typeName: INSERT) is not supported.
        var schema = TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "bar") @node(keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
                name: String
            }
            input CreateBarInput {
                id: ID! @nodeId
                name: String
            }
            type Query { x: String }
            type Mutation { createBar(in: CreateBarInput!): ID @mutation(typeName: INSERT, table: "bar") }
            """, NODEID_CTX);

        var f = (UnclassifiedField) schema.field("Mutation", "createBar");
        assertThat(f.reason())
            .contains("a composite-key (multi-column) @nodeId column carrier on @mutation(typeName: INSERT) is not supported");
    }

    @Test
    void singlePkNodeIdLookupKey_delete_admitted_extractionPropagates() {
        // Extraction-propagation: an arity-1 NodeId-decoded @lookupKey carrier keeps the
        // resolver-supplied NodeIdDecodeKeys extraction, not a re-derived generic one.
        var schema = TestSchemaHelper.buildSchema("""
            type Baz implements Node @table(name: "baz") @node(keyColumns: ["id"]) {
                id: ID! @nodeId
            }
            input DeleteBazInput {
                id: ID! @nodeId
            }
            type Query { x: String }
            type Mutation { deleteBaz(in: DeleteBazInput!): ID @mutation(typeName: DELETE, table: "baz") }
            """, NODEID_CTX);

        var f = (MutationField.DmlTableField) schema.field("Mutation", "deleteBaz");
        var deleteRows = (no.sikt.graphitron.rewrite.model.DeleteRows.Identified) deleteRowsOf(f);
        assertThat(deleteRows.whereColumns()).hasSize(1);
        var keyColumn = deleteRows.whereColumns().get(0);
        assertThat(keyColumn.sdlFieldName()).isEqualTo("id");
        assertThat(keyColumn.targetColumn().sqlName()).isEqualTo("id");
        // The column carries the carrier's NodeIdDecodeKeys extraction, not a re-derived JooqConvert.
        assertThat(keyColumn.extraction())
            .isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.NodeIdDecodeKeys.class);
    }

    @Test
    void tableReturnOnNonNodeTable_classifiedWithoutEncodeReturn() {
        var schema = TestSchemaHelper.buildSchema("""
            type Qux @table(name: "qux") { name: String }
            input QuxInput { name: String }
            type Query { x: String }
            type Mutation { createQux(in: QuxInput!): Qux @mutation(typeName: INSERT) }
            """, NODEID_CTX);

        var f = (MutationField.DmlTableField) schema.field("Mutation", "createQux");
        var rex = (DmlReturnExpression.ProjectedSingle) f.returnExpression();
        assertThat(rex.returnTypeName()).isEqualTo("Qux");
        assertThat(rex.reentryCorrelation().targetTable().tableName()).isEqualTo("qux");
    }

    // ===== DELETE-payload-carrier admission matrix (cardinality × element) =====
    //
    // Composite-PK cells use Bar (id_1, id_2); single-PK cells use Baz (id). Each cell asserts
    // the parent mutation classifies as a delete-payload field AND the payload's data field
    // classifies as SingleRecordIdFieldFromReturning carrying the resolved NodeIdEncodeKeys
    // encoder. Implicit and explicit @nodeId both admit.

    @Test
    void bulkDeleteIdCarrier_compositePk_implicit_admits() {
        var schema = TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "bar") @node(keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
                name: String
            }
            input DeleteBarInput { id: ID! @nodeId }
            type DeletedBarsPayload { deletedIds: [ID!] }
            type Query { x: String }
            type Mutation { deleteBars(in: [DeleteBarInput!]!): DeletedBarsPayload @mutation(typeName: DELETE, table: "bar") }
            """, NODEID_CTX);

        var mut = (MutationField.MutationBulkDmlRecordField) schema.field("Mutation", "deleteBars");
        var dataField = (no.sikt.graphitron.rewrite.model.ChildField.SingleRecordIdFieldFromReturning)
            schema.field("DeletedBarsPayload", "deletedIds");
        assertThat(dataField.encode().encodeMethod().methodName()).isEqualTo("encodeBar");
        assertThat(dataField.encode().encodeMethod().paramSignature())
            .extracting(ColumnRef::sqlName)
            .containsExactly("id_1", "id_2");
    }

    @Test
    void bulkDeleteIdCarrier_compositePk_explicit_admits() {
        var schema = TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "bar") @node(keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
                name: String
            }
            input DeleteBarInput { id: ID! @nodeId }
            type DeletedBarsPayload { deletedIds: [ID!] @nodeId(typeName: "Bar") }
            type Query { x: String }
            type Mutation { deleteBars(in: [DeleteBarInput!]!): DeletedBarsPayload @mutation(typeName: DELETE, table: "bar") }
            """, NODEID_CTX);

        var mut = (MutationField.MutationBulkDmlRecordField) schema.field("Mutation", "deleteBars");
        var dataField = (no.sikt.graphitron.rewrite.model.ChildField.SingleRecordIdFieldFromReturning)
            schema.field("DeletedBarsPayload", "deletedIds");
        assertThat(dataField.encode().encodeMethod().methodName()).isEqualTo("encodeBar");
    }

    @Test
    void bulkDeleteIdCarrier_explicitNodeId_caseMismatchedTable_admits() {
        // The @nodeId(typeName: "Bar") carrier resolves the Bar NodeType by name; Bar's
        // verbatim @table is the Oracle-style UPPERCASE "BAR" while the carrier's input @table
        // is the lowercase jOOQ name "bar". resolveCarrierIdEncoder compares the two
        // case-insensitively; a case-sensitive equals would read this as an @nodeId pinned to a
        // different table and reject the carrier. Pins the admission verdict (encodeBar wired,
        // no diagnostics), not the case-insensitivity mechanism.
        var schema = TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "BAR") @node(keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
                name: String
            }
            input DeleteBarInput { id: ID! @nodeId }
            type DeletedBarsPayload { deletedIds: [ID!] @nodeId(typeName: "Bar") }
            type Query { x: String }
            type Mutation { deleteBars(in: [DeleteBarInput!]!): DeletedBarsPayload @mutation(typeName: DELETE, table: "bar") }
            """, NODEID_CTX);

        var dataField = (no.sikt.graphitron.rewrite.model.ChildField.SingleRecordIdFieldFromReturning)
            schema.field("DeletedBarsPayload", "deletedIds");
        assertThat(dataField.encode().encodeMethod().methodName()).isEqualTo("encodeBar");
        assertThat(schema.diagnostics()).isEmpty();
    }

    @Test
    void singleDeleteIdCarrier_singlePk_implicit_admits() {
        var schema = TestSchemaHelper.buildSchema("""
            type Baz implements Node @table(name: "baz") @node(keyColumns: ["id"]) {
                id: ID! @nodeId
            }
            input DeleteBazInput { id: ID! @nodeId }
            type DeletedBazPayload { deletedId: ID }
            type Query { x: String }
            type Mutation { deleteBaz(in: DeleteBazInput!): DeletedBazPayload @mutation(typeName: DELETE, table: "baz") }
            """, NODEID_CTX);

        var mut = (MutationField.MutationDmlRecordField) schema.field("Mutation", "deleteBaz");
        var dataField = (no.sikt.graphitron.rewrite.model.ChildField.SingleRecordIdFieldFromReturning)
            schema.field("DeletedBazPayload", "deletedId");
        assertThat(dataField.encode().encodeMethod().methodName()).isEqualTo("encodeBaz");
        assertThat(dataField.encode().encodeMethod().paramSignature())
            .extracting(ColumnRef::sqlName)
            .containsExactly("id");
    }

    @Test
    void singleDeleteIdCarrier_singlePk_explicit_admits() {
        var schema = TestSchemaHelper.buildSchema("""
            type Baz implements Node @table(name: "baz") @node(keyColumns: ["id"]) {
                id: ID! @nodeId
            }
            input DeleteBazInput { id: ID! @nodeId }
            type DeletedBazPayload { deletedId: ID @nodeId(typeName: "Baz") }
            type Query { x: String }
            type Mutation { deleteBaz(in: DeleteBazInput!): DeletedBazPayload @mutation(typeName: DELETE, table: "baz") }
            """, NODEID_CTX);

        var mut = (MutationField.MutationDmlRecordField) schema.field("Mutation", "deleteBaz");
        var dataField = (no.sikt.graphitron.rewrite.model.ChildField.SingleRecordIdFieldFromReturning)
            schema.field("DeletedBazPayload", "deletedId");
        assertThat(dataField.encode().encodeMethod().methodName()).isEqualTo("encodeBaz");
    }

    @Test
    void bulkDeleteIdCarrier_explicitNodeIdToWrongTable_rejects() {
        // @nodeId(typeName: "Baz") on a deleteBars carrier whose input @table is "bar"
        // must reject: returning IDs of a different entity than the DML acted on would be a
        // silent contract break.
        var schema = TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "bar") @node(keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
                name: String
            }
            type Baz implements Node @table(name: "baz") @node(keyColumns: ["id"]) {
                id: ID! @nodeId
            }
            input DeleteBarInput { id: ID! @nodeId }
            type DeletedBarsPayload { deletedIds: [ID!] @nodeId(typeName: "Baz") }
            type Query { x: String }
            type Mutation { deleteBars(in: [DeleteBarInput!]!): DeletedBarsPayload @mutation(typeName: DELETE, table: "bar") }
            """, NODEID_CTX);

        var f = (UnclassifiedField) schema.field("Mutation", "deleteBars");
        assertThat(f.reason()).contains("@nodeId encoder pins to table", "baz", "does not match", "bar");
    }

    // ===== FK-target @nodeId input fields on @mutation (INSERT / UPDATE / DELETE) =====
    //
    // An ID input field carrying @nodeId(typeName:) for an FK-target NodeType is admitted
    // across INSERT, UPDATE, DELETE; UPSERT is refused outright. The carrier classifies as
    // InputField.ColumnBackedReferenceField whose FilterBinding.Local tuple is the FK child columns
    // (arity matches the target NodeType's key arity). EnumMappingResolver.buildLookupBindings
    // emits MapGroup / DecodedRecordGroup over that tuple so PK-coverage counts the reference
    // contribution.

    @Test
    void fkTargetNodeIdRef_arity1_insert_admitted() {
        // Arity-1 FK-target @nodeId on an INSERT input: bar.id_1 FKs to baz(id) and Baz is a
        // single-PK NodeType, so the carrier lifts bar.id_1. INSERT needs no binding
        // (tia.fieldBindings() is empty); the emitter walks tia.fields().
        var schema = TestSchemaHelper.buildSchema("""
            type Baz implements Node @table(name: "baz") @node(keyColumns: ["id"]) {
                id: ID! @nodeId
            }
            type Bar implements Node @table(name: "bar") @node(keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
            }
            input CreateBarInput {
                bazRef: ID! @nodeId(typeName: "Baz")
                id2: String! @field(name: "id_2")
            }
            type Query { x: String }
            type Mutation { createBar(in: CreateBarInput!): ID @mutation(typeName: INSERT, table: "bar") }
            """, NODEID_CTX);

        var f = (MutationField.DmlTableField) schema.field("Mutation", "createBar");
        var fields = insertInputOf(f).fields();
        assertThat(fields).hasSize(2);
        var ref = (no.sikt.graphitron.rewrite.model.InputField.ColumnBackedReferenceField)
            fields.stream().filter(x -> x.name().equals("bazRef")).findFirst().orElseThrow();
        assertThat(ownTableColumns(ref)).extracting(no.sikt.graphitron.model.jooq.ColumnRef::sqlName)
            .containsExactly("id_1");
        assertThat(ref.extraction())
            .isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.NodeIdDecodeKeys.class);
    }

    @Test
    void fkTargetNodeIdRef_arity1_delete_admitted_pkCoverage() {
        // DELETE on `bar` with PK (id_1, id_2): bazRef contributes id_1 via its Local binding
        // and id2 contributes id_2 directly, so together they cover the PK. Guards that the
        // validator counts reference contributions; dropping them fires a false
        // "missing PK column id_1" rejection on this exact shape.
        var schema = TestSchemaHelper.buildSchema("""
            type Baz implements Node @table(name: "baz") @node(keyColumns: ["id"]) {
                id: ID! @nodeId
            }
            type Bar implements Node @table(name: "bar") @node(keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
            }
            input DeleteBarInput {
                bazRef: ID! @nodeId(typeName: "Baz")
                id2: String! @field(name: "id_2")
            }
            type Query { x: String }
            type Mutation { deleteBar(in: DeleteBarInput!): ID @mutation(typeName: DELETE, table: "bar") }
            """, NODEID_CTX);

        var f = (MutationField.DmlTableField) schema.field("Mutation", "deleteBar");
        var deleteRows = (no.sikt.graphitron.rewrite.model.DeleteRows.Identified) deleteRowsOf(f);
        assertThat(deleteRows.matchedKey().columns()).extracting(c -> c.sqlName())
            .containsExactlyInAnyOrder("id_1", "id_2");
        assertThat(deleteRows.whereColumns()).extracting(k -> k.targetColumn().sqlName())
            .containsExactlyInAnyOrder("id_1", "id_2");
        // The reference carrier's column is on the input's own table (FK column id_1), not the
        // joined-table column baz.id, and carries the NodeId decode extraction.
        var refCol = deleteRows.whereColumns().stream()
            .filter(k -> k.sdlFieldName().equals("bazRef")).findFirst().orElseThrow();
        assertThat(refCol.targetColumn().sqlName()).isEqualTo("id_1");
        assertThat(refCol.extraction())
            .isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.NodeIdDecodeKeys.class);
    }

    @Test
    void fkTargetNodeIdRef_arity1_update_admitted() {
        // UPDATE on `bar`: bazRef (lifts id_1) and id2 cover the PK (id_1, id_2) as filters;
        // name falls outside the matched key and partitions to SET.
        var schema = TestSchemaHelper.buildSchema("""
            type Baz implements Node @table(name: "baz") @node(keyColumns: ["id"]) {
                id: ID! @nodeId
            }
            type Bar implements Node @table(name: "bar") @node(keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
                name: String
            }
            input UpdateBarInput {
                bazRef: ID! @nodeId(typeName: "Baz")
                id2: String! @field(name: "id_2")
                name: String
            }
            type Query { x: String }
            type Mutation { updateBar(in: UpdateBarInput!): ID @mutation(typeName: UPDATE, table: "bar") }
            """, NODEID_CTX);

        var f = (MutationField.DmlTableField) schema.field("Mutation", "updateBar");
        var updateRows = (no.sikt.graphitron.rewrite.model.UpdateRows.Identified) updateRowsOf(f);
        assertThat(updateRows.keyColumns()).extracting(k -> k.targetColumn().sqlName())
            .containsExactlyInAnyOrder("id_1", "id_2");
        assertThat(updateRows.setColumns()).hasSize(1);
        assertThat(updateRows.setColumns().get(0).sdlFieldName()).isEqualTo("name");
        assertThat(updateRows.setColumns().get(0).targetColumn().sqlName()).isEqualTo("name");
    }

    @Test
    void fkTargetNodeIdRef_compositeKey_delete_admitted() {
        // Composite-key FK-target arm: reordered_fk_child FKs into reordered_pk_parent, a
        // 3-column-PK NodeType. The carrier lifts (fk_a, fk_b, fk_c) permuted into
        // __NODE_KEY_COLUMNS order.
        var schema = TestSchemaHelper.buildSchema("""
            type ReorderedPkParent implements Node @table(name: "reordered_pk_parent") @node { id: ID! }
            type ReorderedChild implements Node @table(name: "reordered_fk_child") @node {
                id: ID! @nodeId
                childId: String! @field(name: "child_id")
            }
            input DeleteReorderedChildInput {
                childId: String! @field(name: "child_id")
                parentRef: ID! @nodeId(typeName: "ReorderedPkParent")
            }
            type Query { x: String }
            type Mutation {
                deleteReorderedChild(in: DeleteReorderedChildInput!): ID @mutation(typeName: DELETE, table: "reordered_fk_child")
            }
            """, NODEID_CTX);

        var f = (MutationField.DmlTableField) schema.field("Mutation", "deleteReorderedChild");
        // DELETE has no SET partition: childId covers the single-column PK (child_id), and
        // parentRef contributes its 3 lifted columns as extra ANDed predicates sharing the SDL
        // field name "parentRef".
        var deleteRows = (no.sikt.graphitron.rewrite.model.DeleteRows.Identified) deleteRowsOf(f);
        assertThat(deleteRows.matchedKey().columns()).extracting(c -> c.sqlName()).containsExactly("child_id");
        assertThat(deleteRows.whereColumns()).extracting(k -> k.targetColumn().sqlName())
            .containsExactlyInAnyOrder("child_id", "fk_a", "fk_b", "fk_c");
        var parentRefCols = deleteRows.whereColumns().stream()
            .filter(k -> k.sdlFieldName().equals("parentRef")).toList();
        assertThat(parentRefCols).extracting(k -> k.targetColumn().sqlName())
            .containsExactly("fk_a", "fk_b", "fk_c");
    }

    @Test
    void fkTargetNodeIdRef_compositeKey_insert_admitted() {
        // Composite-key INSERT through a composite ColumnBackedReferenceField: INSERT needs no
        // PK coverage; fields flow through tia.fields(), not fieldBindings.
        var schema = TestSchemaHelper.buildSchema("""
            type ReorderedPkParent implements Node @table(name: "reordered_pk_parent") @node { id: ID! }
            type ReorderedChild @table(name: "reordered_fk_child") {
                childId: String! @field(name: "child_id")
            }
            input CreateReorderedChildInput {
                childId: String! @field(name: "child_id")
                parentRef: ID! @nodeId(typeName: "ReorderedPkParent")
            }
            type Query { x: String }
            type Mutation {
                createReorderedChild(in: CreateReorderedChildInput!): ReorderedChild @mutation(typeName: INSERT)
            }
            """, NODEID_CTX);

        var f = (MutationField.DmlTableField) schema.field("Mutation", "createReorderedChild");
        var ref = (no.sikt.graphitron.rewrite.model.InputField.ColumnBackedReferenceField)
            insertInputOf(f).fields().stream()
                .filter(x -> x.name().equals("parentRef"))
                .findFirst().orElseThrow();
        assertThat(ownTableColumns(ref)).extracting(no.sikt.graphitron.model.jooq.ColumnRef::sqlName)
            .containsExactly("fk_a", "fk_b", "fk_c");
    }

    @Test
    void fkTargetNodeIdRef_pkCoverage_underCount_negativeRejectionFixture() {
        // Under-counting guard: bar's PK is (id_1, id_2); bazRef contributes id_1 via its Local
        // binding and id2 contributes id_2 directly. The schema is valid, so the
        // resolver must NOT fire a "missing PK column" rejection, which would surface as
        // UnclassifiedField.
        var schema = TestSchemaHelper.buildSchema("""
            type Baz implements Node @table(name: "baz") @node(keyColumns: ["id"]) {
                id: ID! @nodeId
            }
            type Bar implements Node @table(name: "bar") @node(keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
            }
            input DeleteBarPkCovInput {
                bazRef: ID! @nodeId(typeName: "Baz")
                id2: String! @field(name: "id_2")
            }
            type Query { x: String }
            type Mutation { deleteBarPkCov(in: DeleteBarPkCovInput!): ID @mutation(typeName: DELETE, table: "bar") }
            """, NODEID_CTX);

        var f = schema.field("Mutation", "deleteBarPkCov");
        assertThat(f)
            .as("FK-target nodeId reference must contribute its bound columns toward PK coverage")
            .isInstanceOf(MutationField.DmlTableField.class);
    }

    @Test
    void fkTargetNodeIdRef_pkCoverage_genuinelyMissing_rejected() {
        // Contrast fixture bracketing the under-count guard above: bazRef contributes id_1 but
        // nothing contributes id_2, so PK coverage legitimately fails and the DeleteRowsWalker
        // rejects with NoUniqueKeyCoverage.
        var schema = TestSchemaHelper.buildSchema("""
            type Baz implements Node @table(name: "baz") @node(keyColumns: ["id"]) {
                id: ID! @nodeId
            }
            type Bar implements Node @table(name: "bar") @node(keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
            }
            input DeleteBarMissingPkInput {
                bazRef: ID! @nodeId(typeName: "Baz")
            }
            type Query { x: String }
            type Mutation {
                deleteBarMissingPk(in: DeleteBarMissingPkInput!): ID @mutation(typeName: DELETE, table: "bar")
            }
            """, NODEID_CTX);

        var f = (UnclassifiedField) schema.field("Mutation", "deleteBarMissingPk");
        assertThat(f.rejection())
            .isInstanceOf(no.sikt.graphitron.model.diagnostics.DeleteRowsError.NoUniqueKeyCoverage.class);
        assertThat(f.reason())
            .contains("covers no primary key or unique key")
            .contains("id_2");
    }

    @Test
    void fkTargetNodeIdRef_upsert_stillRejected_underR144() {
        // The UPSERT refusal supersedes the FK-target @nodeId admission: UPSERT is rejected on
        // the kind gate at the top of resolveInput before any per-field admission runs.
        var schema = TestSchemaHelper.buildSchema("""
            type Baz implements Node @table(name: "baz") @node(keyColumns: ["id"]) {
                id: ID! @nodeId
            }
            type Bar implements Node @table(name: "bar") @node(keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
            }
            input UpsertBarRefInput {
                bazRef: ID! @nodeId(typeName: "Baz")
                id2: String! @field(name: "id_2")
            }
            type Query { x: String }
            type Mutation {
                upsertBarRef(in: UpsertBarRefInput!): ID @mutation(typeName: UPSERT)
            }
            """, NODEID_CTX);

        var f = (UnclassifiedField) schema.field("Mutation", "upsertBarRef");
        assertThat(f.reason())
            .contains("@mutation(typeName: UPSERT) is not yet supported");
    }

    // ===== self-FK @nodeId @reference on @mutation INSERT inputs =====

    @Test
    void selfFkNodeIdReference_insert_admitsAsCompositeColumnReference_surfacingSharedColumn() {
        // Self-FK on an INSERT: `email` has composite PK (mailbox_id, message_no). `inReplyTo`
        // is a same-table @nodeId @reference naming the self-FK email_in_reply_to_fk, admitted
        // as a composite ColumnBackedReferenceField whose bound tuple is the self-FK's
        // child columns (mailbox_id, in_reply_to_no), NOT the row's own PK. `mailboxRef`
        // (cross-table FK to mailbox) also writes mailbox_id; the shared-column overlap is
        // deduped and agreement-checked at runtime, not rejected at classify time, because both
        // writers carry a @nodeId decode. The composite-carrier INSERT carve-out gates only the
        // non-reference ColumnBackedField, never reference carriers.
        var schema = TestSchemaHelper.buildSchema("""
            type Mailbox implements Node @table(name: "mailbox") @node { id: ID! @nodeId }
            type Email implements Node @table(name: "email") @node { id: ID! @nodeId }
            input InsertEmailReplyInput {
                mailboxRef: ID! @nodeId(typeName: "Mailbox")
                messageNo: Int! @field(name: "message_no")
                inReplyTo: ID @nodeId(typeName: "Email") @reference(path: [{key: "email_in_reply_to_fk"}])
            }
            type Query { x: String }
            type Mutation { insertEmailReply(in: InsertEmailReplyInput!): ID @mutation(typeName: INSERT, table: "email") }
            """);

        var f = (MutationField.DmlTableField) schema.field("Mutation", "insertEmailReply");
        var fields = insertInputOf(f).fields();

        var selfRef = (no.sikt.graphitron.rewrite.model.InputField.ColumnBackedReferenceField)
            fields.stream().filter(x -> x.name().equals("inReplyTo")).findFirst().orElseThrow();
        assertThat(ownTableColumns(selfRef))
            .as("self-FK reference lifts the FK child columns on email's own table")
            .extracting(ColumnRef::sqlName)
            .containsExactly("mailbox_id", "in_reply_to_no");
        assertThat(selfRef.extraction())
            .isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.NodeIdDecodeKeys.class);

        var mailboxRef = (no.sikt.graphitron.rewrite.model.InputField.ColumnBackedReferenceField)
            fields.stream().filter(x -> x.name().equals("mailboxRef")).findFirst().orElseThrow();
        assertThat(ownTableColumns(mailboxRef))
            .as("cross-table FK reference shares the mailbox_id child column with the self-FK")
            .extracting(ColumnRef::sqlName)
            .containsExactly("mailbox_id");
        assertThat(selfRef.selfReference())
            .as("the same-table @nodeId @reference is marked as a self-FK on the carrier")
            .isTrue();
        assertThat(mailboxRef.selfReference())
            .as("the cross-table FK reference is NOT a self-FK")
            .isFalse();
    }

    @Test
    void selfFkNodeIdReference_update_routesSelfFkToSet_sharedColumnInBothPartitions() {
        // The UPDATE sibling of the INSERT self-FK case: `id` (own @nodeId) identifies the row,
        // giving WHERE (mailbox_id, message_no). `inReplyTo`'s lifted child columns (mailbox_id,
        // in_reply_to_no) route wholly to SET: a self-FK writes "who this row points at", never
        // identity, so the shared mailbox_id stays a SET write. mailbox_id thus appears in BOTH
        // keyColumns (from id) and setColumns (from inReplyTo); emit-side cross-partition
        // agreement reconciles it.
        var schema = TestSchemaHelper.buildSchema("""
            type Mailbox implements Node @table(name: "mailbox") @node { id: ID! @nodeId }
            type Email implements Node @table(name: "email") @node {
                id: ID! @nodeId
                inReplyToNo: Int @field(name: "in_reply_to_no")
                subject: String @field(name: "subject")
            }
            input UpdateEmailReplyInput {
                id: ID! @nodeId(typeName: "Email")
                subject: String @field(name: "subject")
                inReplyTo: ID @nodeId(typeName: "Email") @reference(path: [{key: "email_in_reply_to_fk"}])
            }
            type Query { x: String }
            type Mutation { updateEmailReply(in: UpdateEmailReplyInput!): Email @mutation(typeName: UPDATE) }
            """);

        var f = (MutationField.DmlTableField) schema.field("Mutation", "updateEmailReply");
        var updateRows = (no.sikt.graphitron.rewrite.model.UpdateRows.Identified) updateRowsOf(f);

        // WHERE: id's own PK columns.
        assertThat(updateRows.keyColumns()).extracting(k -> k.targetColumn().sqlName())
            .containsExactlyInAnyOrder("mailbox_id", "message_no");
        assertThat(updateRows.keyColumns()).extracting(k -> k.sdlFieldName()).containsOnly("id");
        // SET: inReplyTo's whole lifted tuple (mailbox_id, in_reply_to_no) plus subject.
        var inReplyToSet = updateRows.setColumns().stream()
            .filter(s -> s.sdlFieldName().equals("inReplyTo")).toList();
        assertThat(inReplyToSet).extracting(s -> s.targetColumn().sqlName())
            .containsExactly("mailbox_id", "in_reply_to_no");
        assertThat(updateRows.setColumns()).extracting(s -> s.targetColumn().sqlName())
            .contains("subject");
        // mailbox_id present in both partitions.
        assertThat(updateRows.keyColumns()).anyMatch(k -> k.targetColumn().sqlName().equals("mailbox_id"));
        assertThat(updateRows.setColumns()).anyMatch(s -> s.targetColumn().sqlName().equals("mailbox_id"));
    }

    // ===== straddling cross-table @nodeId reference on @mutation UPDATE inputs =====

    /**
     * The SDL every straddle case classifies from: {@code catalogue_item} keyed
     * {@code (tenant_id, item_no)} with a foreign key {@code (tenant_id, catalog_code)} to
     * {@code catalogue}, so {@code catalogueId} lifts one column into the matched key and one
     * outside it. {@code payloadTypes} and {@code mutations} are spliced in so all four
     * carrier-consuming emit shapes (direct-return and payload-returning, single-row and bulk)
     * classify from one input type.
     */
    private static String straddleSchema(String payloadTypes, String mutations) {
        return """
            type Catalogue implements Node @table(name: "catalogue") @node { id: ID! @nodeId }
            type CatalogueItem implements Node @table(name: "catalogue_item") @node {
                id: ID! @nodeId
                itemName: String @field(name: "item_name")
            }
            input UpdateCatalogueItemInput {
                id: ID! @nodeId(typeName: "CatalogueItem")
                itemName: String @field(name: "item_name")
                catalogueId: ID! @nodeId(typeName: "Catalogue")
            }
            %s
            type Query { x: String }
            type Mutation {
            %s
            }
            """.formatted(payloadTypes, mutations);
    }

    /** The partition and obligation facts every carrier-consuming emit shape must see. */
    private static void assertStraddlePartition(no.sikt.graphitron.rewrite.model.UpdateRows.Identified updateRows) {
        // WHERE: the item's own PK, both columns from `id`. The straddler pins neither.
        assertThat(updateRows.keyColumns()).extracting(k -> k.sdlFieldName() + ":" + k.targetColumn().sqlName())
            .containsExactlyInAnyOrder("id:tenant_id", "id:item_no");
        // SET: item_name plus the straddler's out-of-key column only. tenant_id is not written.
        assertThat(updateRows.setColumns()).extracting(s -> s.sdlFieldName() + ":" + s.targetColumn().sqlName())
            .containsExactlyInAnyOrder("itemName:item_name", "catalogueId:catalog_code");
        // The slot-hostile bit: catalog_code is the SECOND column of the Catalogue decode record,
        // so a positional read of the one-column SET partition would emit value1() and write the
        // decoded tenant id into catalog_code.
        assertThat(updateRows.setColumns()).filteredOn(s -> s.sdlFieldName().equals("catalogueId"))
            .singleElement().satisfies(s -> assertThat(s.decodeSlot()).isEqualTo(1));
        // tenant_id is decoded by both fields, so it carries an agreement obligation naming both.
        assertThat(updateRows.agreementObligations()).singleElement().satisfies(ob -> {
            assertThat(ob.column().sqlName()).isEqualTo("tenant_id");
            assertThat(ob.keySide().sdlFieldName()).isEqualTo("id");
            assertThat(ob.keySide().decodeSlot()).isEqualTo(0);
            assertThat(ob.referenceSide().sdlFieldName()).isEqualTo("catalogueId");
            assertThat(ob.referenceSide().decodeSlot()).isEqualTo(0);
        });
        // What an explicit null means is the second fact a consumer can silently drop, so it is
        // asserted on all four alongside the partition. Both carriers here are spelled non-null.
        assertThat(nullRuleOf(updateRows, "catalogueId"))
            .isInstanceOf(no.sikt.graphitron.rewrite.model.CarrierNullRule.OnExplicitNull.CannotArrive.class);
        assertThat(nullRuleOf(updateRows, "itemName"))
            .isInstanceOf(no.sikt.graphitron.rewrite.model.CarrierNullRule.OnExplicitNull.Clears.class);
    }

    /** The stated explicit-null rule for one SET carrier of an UPDATE carrier. */
    private static no.sikt.graphitron.rewrite.model.CarrierNullRule.OnExplicitNull nullRuleOf(
            no.sikt.graphitron.rewrite.model.UpdateRows.Identified updateRows, String sdlFieldName) {
        var rules = updateRows.nullRules().stream()
            .filter(r -> r.sdlFieldName().equals(sdlFieldName)).toList();
        assertThat(rules).as("one null rule for SET carrier '" + sdlFieldName + "'").hasSize(1);
        return rules.getFirst().rule();
    }

    @Test
    void straddlingReference_update_allFourCarrierConsumers_seeTheSamePartitionAndObligations() {
        // The carrier has four emit consumers, and only two of them used to intersect the partitions
        // for themselves; the other two shipped with no cross-partition agreement check at all. A
        // component a consumer can silently drop is exactly what this pins, so every shape is
        // classified and asserted against the same expectation.
        var schema = TestSchemaHelper.buildSchema(straddleSchema(
            """
            type CatalogueItemPayload { item: CatalogueItem }
            type CatalogueItemsPayload { items: [CatalogueItem!] }
            """,
            """
                updateCatalogueItem(in: UpdateCatalogueItemInput!): CatalogueItem @mutation(typeName: UPDATE)
                updateCatalogueItems(in: [UpdateCatalogueItemInput!]!): [CatalogueItem!]! @mutation(typeName: UPDATE)
                updateCatalogueItemPayload(in: UpdateCatalogueItemInput!): CatalogueItemPayload @mutation(typeName: UPDATE)
                updateCatalogueItemsPayload(in: [UpdateCatalogueItemInput!]!): CatalogueItemsPayload @mutation(typeName: UPDATE)
            """));

        assertThat(schema.diagnostics()).isEmpty();

        // Direct-return, single-row and bulk.
        assertStraddlePartition((no.sikt.graphitron.rewrite.model.UpdateRows.Identified)
            updateRowsOf((MutationField.DmlTableField) schema.field("Mutation", "updateCatalogueItem")));
        assertStraddlePartition((no.sikt.graphitron.rewrite.model.UpdateRows.Identified)
            updateRowsOf((MutationField.DmlTableField) schema.field("Mutation", "updateCatalogueItems")));

        // Payload-returning, single-row and bulk.
        var singlePayload = (MutationField.MutationDmlRecordField)
            schema.field("Mutation", "updateCatalogueItemPayload");
        assertStraddlePartition((no.sikt.graphitron.rewrite.model.UpdateRows.Identified)
            ((no.sikt.graphitron.rewrite.model.OperationMember.Write.Update) singlePayload.write()).updateRows());
        var bulkPayload = (MutationField.MutationBulkDmlRecordField)
            schema.field("Mutation", "updateCatalogueItemsPayload");
        assertStraddlePartition((no.sikt.graphitron.rewrite.model.UpdateRows.Identified)
            ((no.sikt.graphitron.rewrite.model.OperationMember.Write.Update) bulkPayload.write()).updateRows());
    }

    @Test
    void nullableStraddlingReference_pinnedByTheIdentityField_isAdmittedAndClears() {
        // The nullable spelling of the admitted shape, which used to reject. `id` is a whole-key
        // carrier, so tenant_id has an identity contributor and the reference neither filters nor
        // writes it; catalog_code is its only write, and an explicit null clears exactly that.
        var schema = TestSchemaHelper.buildSchema("""
            type Catalogue implements Node @table(name: "catalogue") @node { id: ID! @nodeId }
            type CatalogueItem implements Node @table(name: "catalogue_item") @node {
                id: ID! @nodeId
                itemName: String @field(name: "item_name")
            }
            input UpdateCatalogueItemInput {
                id: ID! @nodeId(typeName: "CatalogueItem")
                itemName: String @field(name: "item_name")
                catalogueId: ID @nodeId(typeName: "Catalogue")
            }
            type Query { x: String }
            type Mutation { updateCatalogueItem(in: UpdateCatalogueItemInput!): CatalogueItem @mutation(typeName: UPDATE) }
            """);

        assertThat(schema.diagnostics()).isEmpty();
        var updateRows = (no.sikt.graphitron.rewrite.model.UpdateRows.Identified)
            updateRowsOf((MutationField.DmlTableField) schema.field("Mutation", "updateCatalogueItem"));
        assertThat(updateRows.setColumns()).extracting(s -> s.sdlFieldName() + ":" + s.targetColumn().sqlName())
            .containsExactlyInAnyOrder("itemName:item_name", "catalogueId:catalog_code");
        assertThat(nullRuleOf(updateRows, "catalogueId"))
            .isInstanceOf(no.sikt.graphitron.rewrite.model.CarrierNullRule.OnExplicitNull.Clears.class);
    }

    @Test
    void nullableStraddlingReference_soleContributorOfAKeyColumn_rejectsAtBuildTime() {
        // The surviving reject, narrowed to the one shape that cannot work. Without `id` nothing but
        // the reference supplies tenant_id, so an omitted value would leave no way to find the row.
        var schema = TestSchemaHelper.buildSchema("""
            type Catalogue implements Node @table(name: "catalogue") @node { id: ID! @nodeId }
            type CatalogueItem implements Node @table(name: "catalogue_item") @node {
                id: ID! @nodeId
                itemName: String @field(name: "item_name")
            }
            input UpdateCatalogueItemInput {
                itemNo: Int! @field(name: "item_no")
                itemName: String @field(name: "item_name")
                catalogueId: ID @nodeId(typeName: "Catalogue")
            }
            type Query { x: String }
            type Mutation { updateCatalogueItem(in: UpdateCatalogueItemInput!): CatalogueItem @mutation(typeName: UPDATE) }
            """);

        var f = (UnclassifiedField) schema.field("Mutation", "updateCatalogueItem");
        assertThat(f.reason())
            .contains("optional cross-table @nodeId reference")
            .contains("catalogueId")
            .contains("tenant_id")
            .contains("ID!");
    }

    @Test
    void deleteIdCarrier_inputTableNotNodeBacked_rejects() {
        // Implicit Id recognition needs the input @table to be @node-backed. Qux carries no
        // @node SDL declaration, so the encoder lookup fails and the carrier rejects with the
        // same diagnostic family as the bare-ID DELETE return path.
        var schema = TestSchemaHelper.buildSchema("""
            type Qux @table(name: "qux") { name: String }
            input DeleteQuxInput { name: String! }
            type DeletedQuxPayload { deletedIds: [ID!] }
            type Query { x: String }
            type Mutation { deleteQux(in: [DeleteQuxInput!]!): DeletedQuxPayload @mutation(typeName: DELETE, table: "qux") }
            """, NODEID_CTX);

        var f = (UnclassifiedField) schema.field("Mutation", "deleteQux");
        assertThat(f.reason()).contains("no @node type is declared for table 'qux'");
    }

    /**
     * The own-table column tuple a reference carrier binds, read off its
     * {@link no.sikt.graphitron.rewrite.model.FilterBinding}. Every carrier in this class is a
     * direct FK-target (or self-FK) reference, so the binding is always {@code Local}; the
     * narrowing is itself the assertion that it is.
     */
    private static List<ColumnRef> ownTableColumns(
            no.sikt.graphitron.rewrite.model.InputField.ColumnBackedReferenceField ref) {
        assertThat(ref.binding())
            .as("a writable FK-target reference carrier binds its own table")
            .isInstanceOf(no.sikt.graphitron.rewrite.model.FilterBinding.Local.class);
        return ((no.sikt.graphitron.rewrite.model.FilterBinding.Local) ref.binding()).ownTableColumns();
    }

}
