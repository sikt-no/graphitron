package no.sikt.graphitron.rewrite;

import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.UpdateRows;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.rewrite.DmlWriteReads.updateRowsOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A {@code @nodeId(typeName: T)} leaf on a DML input, a filter input or a filter argument, where
 * {@code T} is a {@code @table} type without {@code implements Node} over a table carrying catalog
 * node metadata. The leaf decodes against that metadata, through a {@code decode<T>} helper the
 * walk mints under the type's own name and {@code NodeIdEncoder} emits; no {@code encode<T>} is
 * emitted, because nothing encodes such a type's id.
 *
 * <p>Fixture: {@code idreffixture.studieprogram} carries metadata (type id {@code Studieprogram},
 * key {@code studieprogram_id}), and {@code studierett} has two non-key foreign keys to it, the
 * shape of a child table pointing twice at a code table owned by another subgraph.
 */
@PipelineTier
class TableOnlyNodeIdLeafPipelineTest {

    private static final String STUDIEPROGRAM = """
        type Studieprogram @table(name: "studieprogram") { kode: String @field(name: "studieprogram_id") }
        type Studierett @table(name: "studierett") { studierettId: Int @field(name: "studierett_id") }
        """;

    @Test
    void dmlUpdate_twoReferencesToOneCodeTable_setBothForeignKeysFromTheCatalogDecode() {
        var schema = TestSchemaHelper.buildSchema(STUDIEPROGRAM + """
            input OppdaterStudierettInput {
                studierettId: Int! @field(name: "studierett_id")
                programId: ID @nodeId(typeName: "Studieprogram")
                    @reference(path: [{key: "studierett_studieprogram_id_fkey"}])
                registrarId: ID @nodeId(typeName: "Studieprogram")
                    @reference(path: [{key: "studierett_registrar_studieprogram_fkey"}])
            }
            type Query { x: String }
            type Mutation {
                oppdaterStudierett(in: OppdaterStudierettInput!): Studierett @mutation(typeName: UPDATE)
            }
            """);

        var field = schema.field("Mutation", "oppdaterStudierett");
        assertThat(field).isInstanceOf(MutationField.DmlTableField.class);
        var updateRows = (UpdateRows.Identified) updateRowsOf((MutationField.DmlTableField) field);
        assertThat(updateRows.keyColumns()).extracting(k -> k.targetColumn().sqlName())
            .containsExactly("studierett_id");
        assertThat(updateRows.setColumns())
            .extracting(s -> s.sdlFieldName() + ":" + s.targetColumn().sqlName())
            .containsExactlyInAnyOrder("programId:studieprogram_id", "registrarId:registrar_studieprogram");
        assertThat(updateRows.setColumns()).allSatisfy(set -> assertThat(set.extraction())
            .isInstanceOfSatisfying(CallSiteExtraction.NodeIdDecodeKeys.class,
                keys -> assertThat(keys.decodeMethod().methodName()).isEqualTo("decodeStudieprogram")));

        assertDecodeOnly(schema, "Studieprogram");
    }

    @Test
    void dmlUpdate_autoDiscoveredCompositeForeignKeyInPermutedOrder() {
        // studierett's two foreign keys make discovery ambiguous; reordered_fk_child has one, to a
        // composite key it declares in a different column order than the metadata's key.
        var schema = TestSchemaHelper.buildSchema("""
            type ReorderedPkParent @table(name: "reordered_pk_parent") { pkB: String @field(name: "pk_b") }
            type ReorderedChild @table(name: "reordered_fk_child") { childId: String! @field(name: "child_id") }
            input UpdateReorderedChildInput {
                childId: String! @field(name: "child_id")
                parentId: ID! @nodeId(typeName: "ReorderedPkParent")
            }
            type Query { x: String }
            type Mutation {
                updateReorderedChild(in: UpdateReorderedChildInput!): ReorderedChild @mutation(typeName: UPDATE)
            }
            """);

        var field = schema.field("Mutation", "updateReorderedChild");
        assertThat(field).isInstanceOf(MutationField.DmlTableField.class);
        var updateRows = (UpdateRows.Identified) updateRowsOf((MutationField.DmlTableField) field);
        assertThat(updateRows.keyColumns()).extracting(k -> k.targetColumn().sqlName())
            .containsExactly("child_id");
        assertThat(updateRows.setColumns()).extracting(s -> s.targetColumn().sqlName())
            .containsExactly("fk_a", "fk_b", "fk_c");
        assertDecodeOnly(schema, "ReorderedPkParent");
    }

    @Test
    void filterInputField_scalarId() {
        var schema = TestSchemaHelper.buildSchema(STUDIEPROGRAM + """
            input StudierettFilter { studieprogram: ID @nodeId(typeName: "Studieprogram")
                @reference(path: [{key: "studierett_studieprogram_id_fkey"}]) }
            type Query { studieretter(filter: StudierettFilter): [Studierett!]! }
            """);

        assertClassifies(schema, "Query", "studieretter");
        assertDecodeOnly(schema, "Studieprogram");
    }

    @Test
    void filterInputField_idList() {
        var schema = TestSchemaHelper.buildSchema(STUDIEPROGRAM + """
            input StudierettFilter { studieprogrammer: [ID!] @nodeId(typeName: "Studieprogram")
                @reference(path: [{key: "studierett_studieprogram_id_fkey"}]) }
            type Query { studieretter(filter: StudierettFilter): [Studierett!]! }
            """);

        assertClassifies(schema, "Query", "studieretter");
        assertDecodeOnly(schema, "Studieprogram");
    }

    @Test
    void topLevelFilterArgument_idList() {
        var schema = TestSchemaHelper.buildSchema(STUDIEPROGRAM + """
            type Query {
                studieretter(studieprogrammer: [ID!] @nodeId(typeName: "Studieprogram")
                    @reference(path: [{key: "studierett_registrar_studieprogram_fkey"}])): [Studierett!]!
            }
            """);

        assertClassifies(schema, "Query", "studieretter");
        assertDecodeOnly(schema, "Studieprogram");
    }

    @Test
    void aTableTypeNamedByNoLeafGetsNoHelper() {
        // Studieprogram's table carries metadata, but no @nodeId names the type, so nothing decodes
        // it and nothing is emitted for it.
        var schema = TestSchemaHelper.buildSchema(STUDIEPROGRAM + """
            type Query { studieprogrammer: [Studieprogram!]! }
            """);

        assertThat(schema.catalogNodeDecodes()).isEmpty();
        assertThat(encoderMethodNames(schema))
            .noneMatch(name -> name.endsWith("Studieprogram"));
    }

    private static void assertClassifies(GraphitronSchema schema, String type, String field) {
        assertThat(schema.field(type, field)).as(type + "." + field)
            .isNotNull()
            .isNotInstanceOf(UnclassifiedField.class);
        assertThat(TestSchemaHelper.diagnosticMessages(schema)).doesNotContain("is not a node type");
    }

    private static void assertDecodeOnly(GraphitronSchema schema, String typeName) {
        assertThat(encoderMethodNames(schema))
            .contains("decode" + typeName)
            .doesNotContain("encode" + typeName);
    }

    private static java.util.List<String> encoderMethodNames(GraphitronSchema schema) {
        TypeSpec encoder = NodeIdEncoderClassGenerator.generate(schema, "fake.code.generated", List.of()).getFirst();
        return encoder.methodSpecs().stream().map(MethodSpec::name).toList();
    }
}
