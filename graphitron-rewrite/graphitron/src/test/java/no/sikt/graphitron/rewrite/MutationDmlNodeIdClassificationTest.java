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
    void nodeIdFieldInInput_withoutLookupKey_rejected() {
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
        // Bare @nodeId on an input infers typeName from the unique @table-matching object type
        // (Bar). Post-R130, the composite-PK same-table @nodeId carrier (CompositeColumnField)
        // is admitted on lookup-bearing verbs only as a @lookupKey field; without @lookupKey
        // the carrier is rejected because the SET-side / INSERT-arm dispatch for composite-PK
        // column writes is out of R130 scope.
        assertThat(f.reason())
            .contains("CompositeColumnField is admitted only as a @lookupKey field");
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
                id: ID! @nodeId @lookupKey
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
                id: ID! @nodeId @lookupKey
                name: String
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
    void compositePkNodeIdLookupKey_upsert_admitted() {
        var schema = TestSchemaHelper.buildSchema("""
            type Bar implements Node @table(name: "bar") @node(keyColumns: ["id_1", "id_2"]) {
                id: ID! @nodeId
                name: String
            }
            input UpsertBarInput @table(name: "bar") {
                id: ID! @nodeId @lookupKey
                name: String
            }
            type Query { x: String }
            type Mutation { upsertBar(in: UpsertBarInput!): ID @mutation(typeName: UPSERT) }
            """, NODEID_CTX);

        var f = (MutationField.MutationUpsertTableField) schema.field("Mutation", "upsertBar");
        // ON-CONFLICT key list comes from the decoded record's target columns.
        var conflictCols = f.tableInputArg().fieldBindings().stream()
            .flatMap(g -> g.targetColumns().stream())
            .map(c -> c.sqlName())
            .toList();
        assertThat(conflictCols).containsExactly("id_1", "id_2");
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
                id: ID! @nodeId @lookupKey
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
}
