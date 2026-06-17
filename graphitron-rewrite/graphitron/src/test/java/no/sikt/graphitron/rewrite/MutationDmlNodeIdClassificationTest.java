package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.DmlReturnExpression;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.MutationField;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;

/**
 * Phase 1 mutation classifier coverage that depends on KjerneJooqGenerator-synthesised NodeId
 * metadata ({@code __NODE_TYPE_ID} / {@code __NODE_KEY_COLUMNS} on the table class). The default
 * Sakila test catalog is plain jOOQ-generated and does not carry these constants, so these cases
 * use the {@code nodeidfixture} catalog where {@code Bar} is hand-instrumented with both a
 * single-key path and a composite-key path (id_1, id_2) for the same fixture table.
 */
@PipelineTier
class MutationDmlNodeIdClassificationTest {

    private static final RewriteContext NODEID_CTX = new RewriteContext(
        List.of(),
        Path.of(""),
        Path.of(""),
        "fake.code.generated",
        "no.sikt.graphitron.rewrite.nodeidfixture",
        Map.of()
    );

    @Test
    void idReturnOnNodeTable_populatesEncodeReturn() {
        var schema = TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "bar") @node { id: ID! @nodeId name: String }
            input BarInput @table(name: "bar") { name: String }
            type Query { x: String }
            type Mutation { createBar(in: BarInput!): ID @mutation(typeName: INSERT) }
            """, NODEID_CTX);

        var f = (MutationField.MutationInsertTableField) schema.field("Mutation", "createBar");
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
            input BarInput @table(name: "bar") { name: String }
            type Query { x: String }
            type Mutation { createBar(in: BarInput!): ID @mutation(typeName: INSERT) }
            """, NODEID_CTX);

        var f = (UnclassifiedField) schema.field("Mutation", "createBar");
        assertThat(f.reason()).contains("no @node type is declared for table 'bar'");
    }

    @Test
    void idReturnOnNonNodeTable_rejected() {
        var schema = TestSchemaHelper.buildSchema("""
            type Qux @table(name: "qux") { name: String }
            input QuxInput @table(name: "qux") { name: String }
            type Query { x: String }
            type Mutation { createQux(in: QuxInput!): ID @mutation(typeName: INSERT) }
            """, NODEID_CTX);

        var f = (UnclassifiedField) schema.field("Mutation", "createQux");
        assertThat(f.reason())
            .contains("no @node type is declared for table 'qux'");
    }

    @Test
    void idReturnOnMultiNodeTable_ambiguous_rejected() {
        // R317 slice 2: a table may legitimately back several @node types (distinct node ids), so
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
            input BarInput @table(name: "bar") { name: String }
            type Query { bar: Bar barTwo: BarTwo }
            type Mutation { createBar(in: BarInput!): ID @mutation(typeName: INSERT) }
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
            input BarInput @table(name: "bar") {
                id: ID! @nodeId
            }
            type Query { x: String }
            type Mutation { updateBar(in: BarInput!): ID @mutation(typeName: UPDATE) }
            """, NODEID_CTX);

        var f = (UnclassifiedField) schema.field("Mutation", "updateBar");
        // R246: the composite-NodeId key covers the PK exactly, leaving nothing to SET; the walker
        // rejects with UpdateRowsError.NoSetFields (the @value marker concept is retired).
        assertThat(f.rejection()).isInstanceOf(
            no.sikt.graphitron.rewrite.model.UpdateRowsError.NoSetFields.class);
        assertThat(f.reason()).contains("nothing to set");
    }

    @Test
    void compositePkNodeIdLookupKey_delete_admitted() {
        // R130 forcing function: composite-PK via @nodeId-decoded carrier admits a DELETE. R266:
        // the carrier classifies as InputField.CompositeColumnField, and the DeleteRowsWalker
        // projects it to two whereColumns (id_1, id_2) sharing the SDL field name "id"; since they
        // cover the composite PK, the walker yields a DeleteRows.Identified.
        var schema = TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "bar") @node(keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
                name: String
            }
            input DeleteBarInput @table(name: "bar") {
                id: ID! @nodeId
            }
            type Query { x: String }
            type Mutation { deleteBar(in: DeleteBarInput!): ID @mutation(typeName: DELETE) }
            """, NODEID_CTX);

        var f = (MutationField.MutationDeleteTableField) schema.field("Mutation", "deleteBar");
        var deleteRows = (no.sikt.graphitron.rewrite.model.DeleteRows.Identified) f.deleteRows();
        assertThat(deleteRows.matchedKey()).isInstanceOf(
            no.sikt.graphitron.rewrite.model.MatchedKey.PrimaryKey.class);
        assertThat(deleteRows.whereColumns()).hasSize(2);
        assertThat(deleteRows.whereColumns()).extracting(k -> k.sdlFieldName()).containsOnly("id");
        assertThat(deleteRows.whereColumns()).extracting(k -> k.targetColumn().sqlName())
            .containsExactly("id_1", "id_2");
    }

    @Test
    void ukCoveringDelete_admitsByUniqueKey_andMatchesUpdateKeyChoice() {
        // R266 + shared-matcher parity: a DELETE whose input covers a UNIQUE key (not the PK) admits
        // as a single-row delete identified by that UK, and the DeleteRowsWalker / UpdateRowsWalker
        // pick the *same* key for the equivalent inputs (both route through MatchedKeys.firstCovered).
        // parent_node has PK pk_id and a separate UNIQUE on alt_key. This is the UK execution case
        // R246 deferred for UPDATE, proven here at the classification tier for DELETE.
        var deleteSchema = TestSchemaHelper.buildSchema("""
            type ParentNode implements Node @table(name: "parent_node") @node { id: ID! @nodeId pkId: String! @field(name: "pk_id") }
            input DeleteParentNodeInput @table(name: "parent_node") { altKey: String! @field(name: "alt_key") }
            type Query { x: String }
            type Mutation { deleteParentNode(in: DeleteParentNodeInput!): ID @mutation(typeName: DELETE) }
            """, NODEID_CTX);
        var del = (MutationField.MutationDeleteTableField) deleteSchema.field("Mutation", "deleteParentNode");
        var deleteRows = (no.sikt.graphitron.rewrite.model.DeleteRows.Identified) del.deleteRows();
        assertThat(deleteRows.matchedKey()).isInstanceOf(no.sikt.graphitron.rewrite.model.MatchedKey.UniqueKey.class);
        assertThat(deleteRows.matchedKey().columns()).extracting(c -> c.sqlName()).containsExactly("alt_key");
        assertThat(deleteRows.whereColumns()).extracting(k -> k.targetColumn().sqlName()).containsExactly("alt_key");

        var updateSchema = TestSchemaHelper.buildSchema("""
            type ParentNode @table(name: "parent_node") { pkId: String! @field(name: "pk_id") }
            input UpdateParentNodeInput @table(name: "parent_node") {
                altKey: String! @field(name: "alt_key")
                name: String @field(name: "name")
            }
            type Query { x: String }
            type Mutation { updateParentNode(in: UpdateParentNodeInput!): ParentNode @mutation(typeName: UPDATE) }
            """, NODEID_CTX);
        var upd = (MutationField.MutationUpdateTableField) updateSchema.field("Mutation", "updateParentNode");
        var updateRows = (no.sikt.graphitron.rewrite.model.UpdateRows.Identified) upd.updateRows();
        assertThat(updateRows.matchedKey()).isInstanceOf(no.sikt.graphitron.rewrite.model.MatchedKey.UniqueKey.class);
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
            input UpdateBarInput @table(name: "bar") {
                id: ID! @nodeId
                name: String
            }
            type Query { x: String }
            type Mutation { updateBar(in: UpdateBarInput!): ID @mutation(typeName: UPDATE) }
            """, NODEID_CTX);

        // R246: the composite-NodeId key field projects to two KeyColumn entries sharing the SDL
        // field name "id"; name partitions to SET as a non-key column (PK-or-UK membership, R266
        // retired the @value marker).
        var f = (MutationField.MutationUpdateTableField) schema.field("Mutation", "updateBar");
        var updateRows = (no.sikt.graphitron.rewrite.model.UpdateRows.Identified) f.updateRows();
        assertThat(updateRows.matchedKey()).isInstanceOf(
            no.sikt.graphitron.rewrite.model.MatchedKey.PrimaryKey.class);
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
        // R144 refuses UPSERT outright. The Deferred rejection carries R145's slug
        // (mutation-cardinality-safety-upsert), which designs the conflict-target uniqueness
        // and bulk-cardinality story before re-admitting UPSERT.
        var schema = TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "bar") @node(keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
                name: String
            }
            input UpsertBarInput @table(name: "bar") {
                id: ID! @nodeId
                name: String
            }
            type Query { x: String }
            type Mutation { upsertBar(in: UpsertBarInput!): ID @mutation(typeName: UPSERT) }
            """, NODEID_CTX);

        var f = (UnclassifiedField) schema.field("Mutation", "upsertBar");
        assertThat(f.reason())
            .contains("@mutation(typeName: UPSERT) is not supported under the R144");
    }

    @Test
    void compositePkNodeId_insert_rejected() {
        // R130 carve-out: CompositeColumnField on @mutation(typeName: INSERT) is not supported.
        // The Deferred rejection carries an empty planSlug; no roadmap item exists today.
        var schema = TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "bar") @node(keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
                name: String
            }
            input CreateBarInput @table(name: "bar") {
                id: ID! @nodeId
                name: String
            }
            type Query { x: String }
            type Mutation { createBar(in: CreateBarInput!): ID @mutation(typeName: INSERT) }
            """, NODEID_CTX);

        var f = (UnclassifiedField) schema.field("Mutation", "createBar");
        assertThat(f.reason())
            .contains("CompositeColumnField on @mutation(typeName: INSERT) is not supported");
    }

    @Test
    void singlePkNodeIdLookupKey_delete_admitted_extractionPropagates() {
        // R130 extraction-propagation fix: arity-1 NodeId-decoded @lookupKey on a same-table
        // ColumnField produces a MapGroup whose MapBinding's extraction is the resolver-supplied
        // NodeIdDecodeKeys (not the pre-R130 re-derived generic extraction).
        var schema = TestSchemaHelper.buildSchema("""
            type Baz implements Node @table(name: "baz") @node(keyColumns: ["id"]) {
                id: ID! @nodeId
            }
            input DeleteBazInput @table(name: "baz") {
                id: ID! @nodeId
            }
            type Query { x: String }
            type Mutation { deleteBaz(in: DeleteBazInput!): ID @mutation(typeName: DELETE) }
            """, NODEID_CTX);

        var f = (MutationField.MutationDeleteTableField) schema.field("Mutation", "deleteBaz");
        // R266: the arity-1 NodeId-decoded carrier projects to one whereColumn on baz.id (the PK),
        // so the walker yields Identified; keyGroupsOf later regroups it into a MapGroup at emit.
        var deleteRows = (no.sikt.graphitron.rewrite.model.DeleteRows.Identified) f.deleteRows();
        assertThat(deleteRows.whereColumns()).hasSize(1);
        var keyColumn = deleteRows.whereColumns().get(0);
        assertThat(keyColumn.sdlFieldName()).isEqualTo("id");
        assertThat(keyColumn.targetColumn().sqlName()).isEqualTo("id");
        // The load-bearing fix: the column carries the carrier's NodeIdDecodeKeys extraction, not a
        // re-derived JooqConvert (which the pre-R130 path produced from the raw column metadata).
        assertThat(keyColumn.extraction())
            .isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.NodeIdDecodeKeys.class);
    }

    @Test
    void tableReturnOnNonNodeTable_classifiedWithoutEncodeReturn() {
        var schema = TestSchemaHelper.buildSchema("""
            type Qux @table(name: "qux") { name: String }
            input QuxInput @table(name: "qux") { name: String }
            type Query { x: String }
            type Mutation { createQux(in: QuxInput!): Qux @mutation(typeName: INSERT) }
            """, NODEID_CTX);

        var f = (MutationField.MutationInsertTableField) schema.field("Mutation", "createQux");
        assertThat(f.returnExpression())
            .isEqualTo(new DmlReturnExpression.ProjectedSingle("Qux"));
    }

    // ===== R156 DELETE-payload-carrier admission matrix (cardinality × element) =====
    //
    // The four admission cells of §Tests L4 (R156). The composite-PK cells use Bar
    // (id_1, id_2); the R130 reproducer fixture the spec named as the motivating shape for
    // the ID-typed PK-echo carrier shape. The single-PK cells use Baz (id) to round out the
    // cardinality axis without composite-PK noise. Each cell asserts the parent mutation
    // classifies as
    // MutationDmlRecordField / MutationBulkDmlRecordField with kind=DELETE, AND the per-field
    // carrier on the data field classifies as SingleRecordIdFieldFromReturning carrying the
    // resolved NodeIdEncodeKeys encoder. Implicit and explicit @nodeId both admit.

    @Test
    void bulkDeleteIdCarrier_compositePk_implicit_admits() {
        var schema = TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "bar") @node(keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
                name: String
            }
            input DeleteBarInput @table(name: "bar") { id: ID! @nodeId }
            type DeletedBarsPayload { deletedIds: [ID!] }
            type Query { x: String }
            type Mutation { deleteBars(in: [DeleteBarInput!]!): DeletedBarsPayload @mutation(typeName: DELETE) }
            """, NODEID_CTX);

        var mut = (MutationField.MutationBulkDeletePayloadField) schema.field("Mutation", "deleteBars");
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
            input DeleteBarInput @table(name: "bar") { id: ID! @nodeId }
            type DeletedBarsPayload { deletedIds: [ID!] @nodeId(typeName: "Bar") }
            type Query { x: String }
            type Mutation { deleteBars(in: [DeleteBarInput!]!): DeletedBarsPayload @mutation(typeName: DELETE) }
            """, NODEID_CTX);

        var mut = (MutationField.MutationBulkDeletePayloadField) schema.field("Mutation", "deleteBars");
        var dataField = (no.sikt.graphitron.rewrite.model.ChildField.SingleRecordIdFieldFromReturning)
            schema.field("DeletedBarsPayload", "deletedIds");
        assertThat(dataField.encode().encodeMethod().methodName()).isEqualTo("encodeBar");
    }

    @Test
    void singleDeleteIdCarrier_singlePk_implicit_admits() {
        var schema = TestSchemaHelper.buildSchema("""
            type Baz implements Node @table(name: "baz") @node(keyColumns: ["id"]) {
                id: ID! @nodeId
            }
            input DeleteBazInput @table(name: "baz") { id: ID! @nodeId }
            type DeletedBazPayload { deletedId: ID }
            type Query { x: String }
            type Mutation { deleteBaz(in: DeleteBazInput!): DeletedBazPayload @mutation(typeName: DELETE) }
            """, NODEID_CTX);

        var mut = (MutationField.MutationDeletePayloadField) schema.field("Mutation", "deleteBaz");
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
            input DeleteBazInput @table(name: "baz") { id: ID! @nodeId }
            type DeletedBazPayload { deletedId: ID @nodeId(typeName: "Baz") }
            type Query { x: String }
            type Mutation { deleteBaz(in: DeleteBazInput!): DeletedBazPayload @mutation(typeName: DELETE) }
            """, NODEID_CTX);

        var mut = (MutationField.MutationDeletePayloadField) schema.field("Mutation", "deleteBaz");
        var dataField = (no.sikt.graphitron.rewrite.model.ChildField.SingleRecordIdFieldFromReturning)
            schema.field("DeletedBazPayload", "deletedId");
        assertThat(dataField.encode().encodeMethod().methodName()).isEqualTo("encodeBaz");
    }

    @Test
    void bulkDeleteIdCarrier_explicitNodeIdToWrongTable_rejects() {
        // R156: @nodeId(typeName: "Baz") on a deleteBars carrier whose input @table is "bar"
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
            input DeleteBarInput @table(name: "bar") { id: ID! @nodeId }
            type DeletedBarsPayload { deletedIds: [ID!] @nodeId(typeName: "Baz") }
            type Query { x: String }
            type Mutation { deleteBars(in: [DeleteBarInput!]!): DeletedBarsPayload @mutation(typeName: DELETE) }
            """, NODEID_CTX);

        var f = (UnclassifiedField) schema.field("Mutation", "deleteBars");
        assertThat(f.reason()).contains("@nodeId encoder pins to table", "baz", "does not match", "bar");
    }

    // ===== R189: FK-target @nodeId input fields on @mutation (INSERT / UPDATE / DELETE) =====
    //
    // Headline shape (the user's `OpprettCampusInput`):
    //   input OpprettCampusInput @table(name: "CAMPUS") {
    //       larestedId: ID! @nodeId(typeName: "Larested")
    //       ...
    //   }
    // is admitted across INSERT, UPDATE, DELETE. UPSERT remains gated by R144's outright
    // refusal (R145). The classifier produces:
    //   arity-1 NodeType key → InputField.ColumnReferenceField (liftedSourceColumns.size() == 1)
    //   arity ≥ 2 NodeType key → InputField.CompositeColumnReferenceField (.size() == N)
    // Validator-side walker (EnumMappingResolver.buildLookupBindings) emits MapGroup / DecodedRecordGroup
    // over liftedSourceColumns() so PK-coverage counts the reference contribution.

    @Test
    void fkTargetNodeIdRef_arity1_insert_admitted() {
        // Arity-1 FK-target @nodeId on an INSERT input. The `bar.id_1` column FKs to baz(id),
        // and Baz is a single-PK NodeType. Carrier: ColumnReferenceField; lifted source column:
        // bar.id_1. INSERT does not need a binding (tia.fieldBindings() is empty); the emitter
        // walks tia.fields() for the column list and per-cell value reads.
        var schema = TestSchemaHelper.buildSchema("""
            type Baz implements Node @table(name: "baz") @node(keyColumns: ["id"]) {
                id: ID! @nodeId
            }
            type Bar implements Node @table(name: "bar") @node(keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
            }
            input CreateBarInput @table(name: "bar") {
                bazRef: ID! @nodeId(typeName: "Baz")
                id2: String! @field(name: "id_2")
            }
            type Query { x: String }
            type Mutation { createBar(in: CreateBarInput!): ID @mutation(typeName: INSERT) }
            """, NODEID_CTX);

        var f = (MutationField.MutationInsertTableField) schema.field("Mutation", "createBar");
        var fields = f.tableInputArg().fields();
        assertThat(fields).hasSize(2);
        var ref = (no.sikt.graphitron.rewrite.model.InputField.ColumnReferenceField)
            fields.stream().filter(x -> x.name().equals("bazRef")).findFirst().orElseThrow();
        assertThat(ref.liftedSourceColumns()).extracting(no.sikt.graphitron.rewrite.model.ColumnRef::sqlName)
            .containsExactly("id_1");
        assertThat(ref.extraction())
            .isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.NodeIdDecodeKeys.class);
    }

    @Test
    void fkTargetNodeIdRef_arity1_delete_admitted_pkCoverage() {
        // DELETE on `bar` with PK (id_1, id_2). The bazRef carrier contributes id_1 via
        // liftedSourceColumns(); id_2 is contributed directly by an InputField.ColumnField.
        // Together they cover the PK, so the PK-coverage check passes. This is the load-bearing
        // assertion: pre-R189, the validator dropped reference contributions on the floor and
        // this exact shape would have hit a false "missing PK column id_1" rejection.
        var schema = TestSchemaHelper.buildSchema("""
            type Baz implements Node @table(name: "baz") @node(keyColumns: ["id"]) {
                id: ID! @nodeId
            }
            type Bar implements Node @table(name: "bar") @node(keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
            }
            input DeleteBarInput @table(name: "bar") {
                bazRef: ID! @nodeId(typeName: "Baz")
                id2: String! @field(name: "id_2")
            }
            type Query { x: String }
            type Mutation { deleteBar(in: DeleteBarInput!): ID @mutation(typeName: DELETE) }
            """, NODEID_CTX);

        var f = (MutationField.MutationDeleteTableField) schema.field("Mutation", "deleteBar");
        // R266: every admitted column is a WHERE filter. bazRef lifts id_1 and id2 contributes id_2;
        // together they cover the PK (id_1, id_2) → Identified. (Pre-R189 the validator dropped the
        // reference contribution and this exact shape hit a false "missing PK column id_1".)
        var deleteRows = (no.sikt.graphitron.rewrite.model.DeleteRows.Identified) f.deleteRows();
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
        // UPDATE on `bar`: bazRef is a filter; id2 is also a filter; name is the non-key SET column.
        // The UpdateRowsWalker covers the PK because id_1 (from bazRef.liftedSourceColumns) + id_2
        // cover (id_1, id_2); name falls outside the matched key, so it partitions to SET.
        var schema = TestSchemaHelper.buildSchema("""
            type Baz implements Node @table(name: "baz") @node(keyColumns: ["id"]) {
                id: ID! @nodeId
            }
            type Bar implements Node @table(name: "bar") @node(keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
                name: String
            }
            input UpdateBarInput @table(name: "bar") {
                bazRef: ID! @nodeId(typeName: "Baz")
                id2: String! @field(name: "id_2")
                name: String
            }
            type Query { x: String }
            type Mutation { updateBar(in: UpdateBarInput!): ID @mutation(typeName: UPDATE) }
            """, NODEID_CTX);

        // R246: bazRef (FK-target NodeId ref, lifts id_1) and id2 both partition to the matched PK
        // (id_1, id_2); name partitions to SET (PK-or-UK membership, R266 retired @value).
        var f = (MutationField.MutationUpdateTableField) schema.field("Mutation", "updateBar");
        var updateRows = (no.sikt.graphitron.rewrite.model.UpdateRows.Identified) f.updateRows();
        assertThat(updateRows.keyColumns()).extracting(k -> k.targetColumn().sqlName())
            .containsExactlyInAnyOrder("id_1", "id_2");
        assertThat(updateRows.setColumns()).hasSize(1);
        assertThat(updateRows.setColumns().get(0).sdlFieldName()).isEqualTo("name");
        assertThat(updateRows.setColumns().get(0).targetColumn().sqlName()).isEqualTo("name");
    }

    @Test
    void fkTargetNodeIdRef_compositeKey_delete_admitted() {
        // Composite-key FK-target arm: reordered_fk_child FKs into reordered_pk_parent which is
        // a 3-column-PK NodeType. Carrier: CompositeColumnReferenceField with
        // liftedSourceColumns = (fk_a, fk_b, fk_c) permuted into __NODE_KEY_COLUMNS order. The
        // resolver builds one DecodedRecordGroup with 3 RecordBinding slots.
        var schema = TestSchemaHelper.buildSchema("""
            type ReorderedPkParent implements Node @table(name: "reordered_pk_parent") @node { id: ID! }
            type ReorderedChild implements Node @table(name: "reordered_fk_child") @node {
                id: ID! @nodeId
                childId: String! @field(name: "child_id")
            }
            input DeleteReorderedChildInput @table(name: "reordered_fk_child") {
                childId: String! @field(name: "child_id")
                parentRef: ID! @nodeId(typeName: "ReorderedPkParent")
            }
            type Query { x: String }
            type Mutation {
                deleteReorderedChild(in: DeleteReorderedChildInput!): ID @mutation(typeName: DELETE)
            }
            """, NODEID_CTX);

        var f = (MutationField.MutationDeleteTableField) schema.field("Mutation", "deleteReorderedChild");
        // R266: every admitted input column is a WHERE filter (DELETE has no SET partition). childId
        // covers the single-column PK (child_id) → Identified; parentRef (composite FK-target NodeId
        // ref) contributes its 3 lifted source columns fk_a/fk_b/fk_c as extra ANDed predicates,
        // sharing the SDL field name "parentRef".
        var deleteRows = (no.sikt.graphitron.rewrite.model.DeleteRows.Identified) f.deleteRows();
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
        // Composite-key INSERT through CompositeColumnReferenceField. INSERT does not need PK
        // coverage (the carve-out spans both same-table and FK-target composite arms); fields
        // flow into the column list / values walk on tia.fields(), not fieldBindings. Verify
        // the carrier classifies and INSERT admits.
        var schema = TestSchemaHelper.buildSchema("""
            type ReorderedPkParent implements Node @table(name: "reordered_pk_parent") @node { id: ID! }
            type ReorderedChild @table(name: "reordered_fk_child") {
                childId: String! @field(name: "child_id")
            }
            input CreateReorderedChildInput @table(name: "reordered_fk_child") {
                childId: String! @field(name: "child_id")
                parentRef: ID! @nodeId(typeName: "ReorderedPkParent")
            }
            type Query { x: String }
            type Mutation {
                createReorderedChild(in: CreateReorderedChildInput!): ReorderedChild @mutation(typeName: INSERT)
            }
            """, NODEID_CTX);

        var f = (MutationField.MutationInsertTableField) schema.field("Mutation", "createReorderedChild");
        var ref = (no.sikt.graphitron.rewrite.model.InputField.CompositeColumnReferenceField)
            f.tableInputArg().fields().stream()
                .filter(x -> x.name().equals("parentRef"))
                .findFirst().orElseThrow();
        assertThat(ref.liftedSourceColumns()).extracting(no.sikt.graphitron.rewrite.model.ColumnRef::sqlName)
            .containsExactly("fk_a", "fk_b", "fk_c");
    }

    @Test
    void fkTargetNodeIdRef_pkCoverage_underCount_negativeRejectionFixture() {
        // Load-bearing under-counting guard: bar's PK is (id_1, id_2). bazRef contributes id_1
        // (via liftedSourceColumns) and id2 contributes id_2 directly. The schema is valid; the
        // resolver must NOT produce a "missing PK column" rejection. Without R189's step 4
        // validator widening, the reference carrier's contribution would be silently dropped
        // and this exact shape would fire a false "missing: id_1" rejection. The check is on
        // the classified field (no UnclassifiedField).
        var schema = TestSchemaHelper.buildSchema("""
            type Baz implements Node @table(name: "baz") @node(keyColumns: ["id"]) {
                id: ID! @nodeId
            }
            type Bar implements Node @table(name: "bar") @node(keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
            }
            input DeleteBarPkCovInput @table(name: "bar") {
                bazRef: ID! @nodeId(typeName: "Baz")
                id2: String! @field(name: "id_2")
            }
            type Query { x: String }
            type Mutation { deleteBarPkCov(in: DeleteBarPkCovInput!): ID @mutation(typeName: DELETE) }
            """, NODEID_CTX);

        var f = schema.field("Mutation", "deleteBarPkCov");
        // The classifier admits the shape. A false "missing PK column" rejection would surface
        // as UnclassifiedField; assert it does not.
        assertThat(f)
            .as("FK-target nodeId reference must contribute liftedSourceColumns toward PK coverage")
            .isInstanceOf(MutationField.MutationDeleteTableField.class);
    }

    @Test
    void fkTargetNodeIdRef_pkCoverage_genuinelyMissing_rejected() {
        // Contrast fixture: bazRef contributes id_1 but no field contributes id_2. PK coverage
        // legitimately fails; R266's DeleteRowsWalker produces the NoUniqueKeyCoverage rejection.
        // Pairs with the under-count fixture above to bracket the load-bearing widening: with
        // the widening, the under-count case admits and this case still rejects.
        var schema = TestSchemaHelper.buildSchema("""
            type Baz implements Node @table(name: "baz") @node(keyColumns: ["id"]) {
                id: ID! @nodeId
            }
            type Bar implements Node @table(name: "bar") @node(keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
            }
            input DeleteBarMissingPkInput @table(name: "bar") {
                bazRef: ID! @nodeId(typeName: "Baz")
            }
            type Query { x: String }
            type Mutation {
                deleteBarMissingPk(in: DeleteBarMissingPkInput!): ID @mutation(typeName: DELETE)
            }
            """, NODEID_CTX);

        var f = (UnclassifiedField) schema.field("Mutation", "deleteBarMissingPk");
        assertThat(f.rejection())
            .isInstanceOf(no.sikt.graphitron.rewrite.model.DeleteRowsError.NoUniqueKeyCoverage.class);
        assertThat(f.reason())
            .contains("covers no primary key or unique key")
            .contains("id_2");
    }

    @Test
    void fkTargetNodeIdRef_upsert_stillRejected_underR144() {
        // R144's UPSERT refusal supersedes R189's admission: UPSERT is rejected on the kind
        // gate at the top of resolveInput before any per-field admission runs.
        var schema = TestSchemaHelper.buildSchema("""
            type Baz implements Node @table(name: "baz") @node(keyColumns: ["id"]) {
                id: ID! @nodeId
            }
            type Bar implements Node @table(name: "bar") @node(keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
            }
            input UpsertBarRefInput @table(name: "bar") {
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
            .contains("@mutation(typeName: UPSERT) is not supported under the R144");
    }

    @Test
    void deleteIdCarrier_inputTableNotNodeBacked_rejects() {
        // R156: implicit Id recognition needs the input @table to be @node-backed. Qux has no
        // @node SDL declaration in this fixture, so the encoder lookup fails and the carrier
        // rejects with the same diagnostic family as today's bare-ID DELETE return path.
        var schema = TestSchemaHelper.buildSchema("""
            type Qux @table(name: "qux") { name: String }
            input DeleteQuxInput @table(name: "qux") { name: String! }
            type DeletedQuxPayload { deletedIds: [ID!] }
            type Query { x: String }
            type Mutation { deleteQux(in: [DeleteQuxInput!]!): DeletedQuxPayload @mutation(typeName: DELETE) }
            """, NODEID_CTX);

        var f = (UnclassifiedField) schema.field("Mutation", "deleteQux");
        assertThat(f.reason()).contains("no @node type is declared for table 'qux'");
    }
}
