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
    void nodeIdFieldInInput_withoutValueMarker_rejected() {
        var schema = TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "bar") @node(keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
                name: String
            }
            input BarInput @table(name: "bar") {
                id: ID! @nodeId
                name: String
            }
            type Query { x: String }
            type Mutation { updateBar(in: BarInput!): ID @mutation(typeName: UPDATE) }
            """, NODEID_CTX);

        var f = (UnclassifiedField) schema.field("Mutation", "updateBar");
        // R144: every input field is a WHERE filter by default. UPDATE requires at least one
        // @value field to define the SET clause; this input has none, so the classifier rejects
        // with the new "no @value fields" diagnostic.
        assertThat(f.reason())
            .contains("@mutation(typeName: UPDATE) has no @value fields to set");
    }

    @Test
    void compositePkNodeIdLookupKey_delete_admitted() {
        // R130 forcing function: composite-PK @lookupKey via @nodeId-decoded carrier admits a
        // DELETE. The carrier classifies as InputField.CompositeColumnField; buildLookupBindings
        // produces one InputColumnBindingGroup.DecodedRecordGroup with N RecordBinding slots.
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
        assertThat(f.tableInputArg().fieldBindings()).hasSize(1);
        var group = f.tableInputArg().fieldBindings().get(0);
        assertThat(group).isInstanceOf(
            no.sikt.graphitron.rewrite.model.InputColumnBindingGroup.DecodedRecordGroup.class);
        var drg = (no.sikt.graphitron.rewrite.model.InputColumnBindingGroup.DecodedRecordGroup) group;
        assertThat(drg.sourceFieldName()).isEqualTo("id");
        assertThat(drg.bindings()).hasSize(2);
        assertThat(drg.bindings().get(0).targetColumn().sqlName()).isEqualTo("id_1");
        assertThat(drg.bindings().get(1).targetColumn().sqlName()).isEqualTo("id_2");
        // The LookupKeyField partition admits the CompositeColumnField carrier.
        assertThat(f.tableInputArg().lookupKeyFields()).hasSize(1);
        assertThat(f.tableInputArg().lookupKeyFields().get(0))
            .isInstanceOf(no.sikt.graphitron.rewrite.model.InputField.CompositeColumnField.class);
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
                name: String @value
            }
            type Query { x: String }
            type Mutation { updateBar(in: UpdateBarInput!): ID @mutation(typeName: UPDATE) }
            """, NODEID_CTX);

        var f = (MutationField.MutationUpdateTableField) schema.field("Mutation", "updateBar");
        var group = f.tableInputArg().fieldBindings().get(0);
        assertThat(group).isInstanceOf(
            no.sikt.graphitron.rewrite.model.InputColumnBindingGroup.DecodedRecordGroup.class);
        // The set side is the plain name ColumnField, untouched by the NodeId admission path.
        assertThat(f.tableInputArg().setFields()).hasSize(1);
        var set0 = (no.sikt.graphitron.rewrite.model.InputField.ColumnField) f.tableInputArg().setFields().get(0);
        assertThat(set0.name()).isEqualTo("name");
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
                name: String @value
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
        var group = f.tableInputArg().fieldBindings().get(0);
        assertThat(group).isInstanceOf(
            no.sikt.graphitron.rewrite.model.InputColumnBindingGroup.MapGroup.class);
        var mg = (no.sikt.graphitron.rewrite.model.InputColumnBindingGroup.MapGroup) group;
        assertThat(mg.bindings()).hasSize(1);
        var binding = mg.bindings().get(0);
        assertThat(binding.fieldName()).isEqualTo("id");
        assertThat(binding.targetColumn().sqlName()).isEqualTo("id");
        // The load-bearing fix: the binding carries the carrier's NodeIdDecodeKeys, not a
        // re-derived JooqConvert (which the pre-R130 path produced from the raw column metadata).
        assertThat(binding.extraction())
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

        var mut = (MutationField.MutationBulkDmlRecordField) schema.field("Mutation", "deleteBars");
        assertThat(mut.kind()).isEqualTo(no.sikt.graphitron.rewrite.model.DmlKind.DELETE);
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

        var mut = (MutationField.MutationBulkDmlRecordField) schema.field("Mutation", "deleteBars");
        assertThat(mut.kind()).isEqualTo(no.sikt.graphitron.rewrite.model.DmlKind.DELETE);
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

        var mut = (MutationField.MutationDmlRecordField) schema.field("Mutation", "deleteBaz");
        assertThat(mut.kind()).isEqualTo(no.sikt.graphitron.rewrite.model.DmlKind.DELETE);
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

        var mut = (MutationField.MutationDmlRecordField) schema.field("Mutation", "deleteBaz");
        assertThat(mut.kind()).isEqualTo(no.sikt.graphitron.rewrite.model.DmlKind.DELETE);
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
