package no.sikt.graphitron.record;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.record.field.ChildField.ColumnField;
import no.sikt.graphitron.record.field.ChildField.ColumnReferenceField;
import no.sikt.graphitron.record.field.ChildField.NodeIdField;
import no.sikt.graphitron.record.field.ChildField.NodeIdReferenceField;
import no.sikt.graphitron.record.field.NodeTypeRef.ResolvedNodeType;
import no.sikt.graphitron.record.field.NodeTypeRef.UnresolvedNodeType;
import no.sikt.graphitron.record.type.NodeRef.NodeDirective;
import no.sikt.graphitron.record.type.NodeRef.NoNode;
import no.sikt.graphitron.record.field.ReferencePathElementRef.FkRef;
import no.sikt.graphitron.record.field.ChildField.MultitableReferenceField;
import no.sikt.graphitron.record.field.GraphitronField.NotGeneratedField;
import no.sikt.graphitron.record.field.ColumnRef.ResolvedColumn;
import no.sikt.graphitron.record.field.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.record.field.ColumnRef.UnresolvedColumn;
import no.sikt.graphitron.record.field.ReferencePathElementRef.UnresolvedKeyRef;
import no.sikt.graphitron.record.type.TableRef.ResolvedTable;
import no.sikt.graphitron.record.type.GraphitronType.TableType;
import no.sikt.graphitron.record.type.TableRef.UnresolvedTable;
import no.sikt.graphql.schema.SchemaReadingHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Level 2 classification tests: each test provides a minimal inline schema text block and asserts
 * that {@link FieldsSpecBuilder} produces the expected {@link no.sikt.graphitron.record.field.GraphitronField}
 * concrete type. No hand-crafted schema objects — classification logic is tested end-to-end from SDL.
 */
class FieldsSpecBuilderTest {

    /** Directives schema file on the classpath, provided by graphitron-common. */
    private static final String DIRECTIVES_PATH = "directives.graphqls";

    @BeforeEach
    void setup() {
        GeneratorConfig.setProperties(
            java.util.Set.of(), "", "fake.code.generated", DEFAULT_JOOQ_PACKAGE,
            java.util.List.of(), java.util.Set.of(), java.util.List.of());
    }

    @AfterEach
    void teardown() {
        GeneratorConfig.clear();
    }

    // ===== ColumnField =====

    @Test
    void columnField_implicitColumnName() {
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type Query { film: Film }
            """);
        var field = schema.field("Film", "title");
        assertThat(field).isInstanceOf(ColumnField.class);
        var col = (ColumnField) field;
        assertThat(col.columnName()).isEqualTo("title");
        assertThat(col.column()).isInstanceOf(ResolvedColumn.class);
        assertThat(col.javaNamePresent()).isFalse();
    }

    @Test
    void columnField_explicitFieldDirective() {
        var schema = build("""
            type Film @table(name: "film") { title: String @field(name: "film_title") }
            type Query { film: Film }
            """);
        var field = schema.field("Film", "title");
        assertThat(field).isInstanceOf(ColumnField.class);
        var col = (ColumnField) field;
        assertThat(col.columnName()).isEqualTo("film_title");
        assertThat(col.javaNamePresent()).isFalse();
    }

    @Test
    void columnField_javaNamePresent() {
        var schema = build("""
            type Film @table(name: "film") { title: String @field(javaName: "getTitle") }
            type Query { film: Film }
            """);
        var field = schema.field("Film", "title");
        assertThat(field).isInstanceOf(ColumnField.class);
        assertThat(((ColumnField) field).javaNamePresent()).isTrue();
    }

    @Test
    void columnField_unresolvedColumn() {
        var schema = build("""
            type Film @table(name: "film") { doesNotExist: String }
            type Query { film: Film }
            """);
        var field = schema.field("Film", "doesNotExist");
        assertThat(field).isInstanceOf(ColumnField.class);
        assertThat(((ColumnField) field).column()).isInstanceOf(UnresolvedColumn.class);
    }

    @Test
    void columnField_enumReturnType() {
        var schema = build("""
            enum Rating { G PG PG13 R NC17 }
            type Film @table(name: "film") { rating: Rating }
            type Query { film: Film }
            """);
        assertThat(schema.field("Film", "rating")).isInstanceOf(ColumnField.class);
    }

    @Test
    void columnField_unresolvedTable() {
        var schema = build("""
            type NoSuchTable @table(name: "no_such_table") { title: String }
            type Query { x: NoSuchTable }
            """);
        assertThat(schema.type("NoSuchTable")).isInstanceOf(TableType.class);
        assertThat(((TableType) schema.type("NoSuchTable")).table()).isInstanceOf(UnresolvedTable.class);
        assertThat(schema.field("NoSuchTable", "title")).isInstanceOf(ColumnField.class);
        assertThat(((ColumnField) schema.field("NoSuchTable", "title")).column()).isInstanceOf(UnresolvedColumn.class);
    }

    // ===== ColumnReferenceField =====

    @Test
    void columnReferenceField_withKnownFk() {
        var schema = build("""
            type Film @table(name: "film") {
              languageName: String @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { film: Film }
            """);
        var field = schema.field("Film", "languageName");
        assertThat(field).isInstanceOf(ColumnReferenceField.class);
        var ref = (ColumnReferenceField) field;
        assertThat(ref.referencePath()).hasSize(1);
        assertThat(ref.referencePath().get(0)).isInstanceOf(FkRef.class);
    }

    @Test
    void columnReferenceField_withUnknownFk() {
        var schema = build("""
            type Film @table(name: "film") {
              languageName: String @reference(path: [{key: "NO_SUCH_FK"}])
            }
            type Query { film: Film }
            """);
        var field = schema.field("Film", "languageName");
        assertThat(field).isInstanceOf(ColumnReferenceField.class);
        var ref = (ColumnReferenceField) field;
        assertThat(ref.referencePath()).hasSize(1);
        assertThat(ref.referencePath().get(0)).isInstanceOf(UnresolvedKeyRef.class);
    }

    @Test
    void columnReferenceField_implicitColumnName() {
        var schema = build("""
            type Film @table(name: "film") {
              languageName: String @reference(path: [{key: "NO_SUCH_FK"}])
            }
            type Query { film: Film }
            """);
        var ref = (ColumnReferenceField) schema.field("Film", "languageName");
        assertThat(ref.columnName()).isEqualTo("languageName");
    }

    @Test
    void columnReferenceField_explicitFieldDirective() {
        var schema = build("""
            type Film @table(name: "film") {
              languageName: String @field(name: "name")
                  @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { film: Film }
            """);
        var ref = (ColumnReferenceField) schema.field("Film", "languageName");
        assertThat(ref.columnName()).isEqualTo("name");
    }

    // ===== NotGeneratedField =====

    @Test
    void notGeneratedField() {
        var schema = build("""
            type Film @table(name: "film") { title: String @notGenerated }
            type Query { film: Film }
            """);
        assertThat(schema.field("Film", "title")).isInstanceOf(NotGeneratedField.class);
    }

    // ===== MultitableReferenceField =====

    @Test
    void multitableReferenceField() {
        var schema = build("""
            type Film @table(name: "film") {
              other: String @multitableReference(routes: [{typeName: "X", path: [{key: "K"}]}])
            }
            type Query { film: Film }
            """);
        assertThat(schema.field("Film", "other")).isInstanceOf(MultitableReferenceField.class);
    }

    // ===== NodeIdField =====

    @Test
    void nodeIdField_noTypeName() {
        var schema = build("""
            type Film @table(name: "film") @node(keyColumns: ["film_id"]) {
              id: ID! @nodeId
            }
            type Query { film: Film }
            """);
        var field = schema.field("Film", "id");
        assertThat(field).isInstanceOf(NodeIdField.class);
        assertThat(((NodeIdField) field).node()).isInstanceOf(NodeDirective.class);
    }

    @Test
    void nodeIdField_parentLacksNode_classifiedWithNoNode() {
        var schema = build("""
            type Film @table(name: "film") { id: ID! @nodeId }
            type Query { film: Film }
            """);
        var field = schema.field("Film", "id");
        assertThat(field).isInstanceOf(NodeIdField.class);
        assertThat(((NodeIdField) field).node()).isInstanceOf(NoNode.class);
    }

    // ===== NodeIdReferenceField =====

    @Test
    void nodeIdReferenceField_resolved() {
        var schema = build("""
            type Language @table(name: "language") @node(keyColumns: ["language_id"]) {
              id: ID! @nodeId
            }
            type Film @table(name: "film") {
              languageId: ID! @nodeId(typeName: "Language")
            }
            type Query { film: Film }
            """);
        var field = schema.field("Film", "languageId");
        assertThat(field).isInstanceOf(NodeIdReferenceField.class);
        var ref = (NodeIdReferenceField) field;
        assertThat(ref.typeName()).isEqualTo("Language");
        assertThat(ref.nodeType()).isInstanceOf(ResolvedNodeType.class);
        assertThat(ref.referencePath()).isEmpty();
    }

    @Test
    void nodeIdReferenceField_unresolved_typeHasNoNode() {
        var schema = build("""
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
              languageId: ID! @nodeId(typeName: "Language")
            }
            type Query { film: Film }
            """);
        var field = schema.field("Film", "languageId");
        assertThat(field).isInstanceOf(NodeIdReferenceField.class);
        assertThat(((NodeIdReferenceField) field).nodeType()).isInstanceOf(UnresolvedNodeType.class);
    }

    @Test
    void nodeIdReferenceField_unresolved_typeDoesNotExist() {
        var schema = build("""
            type Film @table(name: "film") {
              languageId: ID! @nodeId(typeName: "NoSuchType")
            }
            type Query { film: Film }
            """);
        var field = schema.field("Film", "languageId");
        assertThat(field).isInstanceOf(NodeIdReferenceField.class);
        assertThat(((NodeIdReferenceField) field).nodeType()).isInstanceOf(UnresolvedNodeType.class);
    }

    @Test
    void nodeIdReferenceField_withExplicitReferencePath() {
        var schema = build("""
            type Language @table(name: "language") @node(keyColumns: ["language_id"]) {
              id: ID! @nodeId
            }
            type Film @table(name: "film") {
              languageId: ID! @nodeId(typeName: "Language")
                  @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { film: Film }
            """);
        var field = schema.field("Film", "languageId");
        assertThat(field).isInstanceOf(NodeIdReferenceField.class);
        var ref = (NodeIdReferenceField) field;
        assertThat(ref.referencePath()).hasSize(1);
        assertThat(ref.referencePath().get(0)).isInstanceOf(FkRef.class);
    }

    // ===== Object-type fields are UnclassifiedField (P2+ territory) =====

    @Test
    void objectReturnType_producesUnclassifiedField() {
        var schema = build("""
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") { language: Language }
            type Query { film: Film }
            """);
        assertThat(schema.field("Film", "language")).isInstanceOf(UnclassifiedField.class);
    }

    // ===== Type classification =====

    @Test
    void tableType_resolvedTable() {
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type Query { film: Film }
            """);
        assertThat(schema.type("Film")).isInstanceOf(TableType.class);
        assertThat(((TableType) schema.type("Film")).table()).isInstanceOf(ResolvedTable.class);
    }

    @Test
    void tableType_tableName_defaultsToLowercasedTypeName() {
        var schema = build("""
            type Film @table { title: String }
            type Query { film: Film }
            """);
        assertThat(((TableType) schema.type("Film")).tableName()).isEqualTo("film");
    }

    // ===== Helper =====

    /**
     * Parses the given SDL text block together with the Graphitron directive definitions
     * and builds a {@link GraphitronSchema} via {@link FieldsSpecBuilder}.
     */
    private GraphitronSchema build(String schemaText) {
        String directivesContent = SchemaReadingHelper.fileAsString(
            java.nio.file.Paths.get("../../graphitron-common/src/main/resources/directives.graphqls"));
        TypeDefinitionRegistry registry = new SchemaParser().parse(directivesContent + "\n" + schemaText);
        return FieldsSpecBuilder.build(registry);
    }
}
