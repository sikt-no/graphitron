package no.sikt.graphitron.record;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.ErrorHandlerType;
import no.sikt.graphitron.record.field.ChildField.ColumnField;
import no.sikt.graphitron.record.field.ChildField.NestingField;
import no.sikt.graphitron.record.field.ChildField.TableField;
import no.sikt.graphitron.record.field.ChildField.TableMethodField;
import no.sikt.graphitron.record.field.DefaultOrderSpec;
import no.sikt.graphitron.record.field.FieldCardinality;
import no.sikt.graphitron.record.field.FieldConditionRef;
import no.sikt.graphitron.record.field.OrderSpec;
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
import no.sikt.graphitron.record.type.GraphitronType.ErrorType;
import no.sikt.graphitron.record.type.TableRef.ResolvedTable;
import no.sikt.graphitron.record.type.GraphitronType.TableType;
import no.sikt.graphitron.record.type.TableRef.UnresolvedTable;
import no.sikt.graphql.schema.SchemaReadingHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Level 2 classification tests: each test provides a minimal inline schema text block and asserts
 * that {@link GraphitronSchemaBuilder} produces the expected {@link no.sikt.graphitron.record.field.GraphitronField}
 * concrete type. No hand-crafted schema objects — classification logic is tested end-to-end from SDL.
 */
class GraphitronSchemaBuilderTest {

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
    void columnReferenceField_withJavaConstantFkName() {
        var schema = build("""
            type Film @table(name: "film") {
              languageName: String @reference(path: [{key: "FILM__FILM_LANGUAGE_ID_FKEY"}])
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

    // ===== TableField =====

    @Test
    void tableField_singleReturnType() {
        var schema = build("""
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") { language: Language }
            type Query { film: Film }
            """);
        var field = schema.field("Film", "language");
        assertThat(field).isInstanceOf(TableField.class);
        var tf = (TableField) field;
        assertThat(tf.cardinality()).isInstanceOf(FieldCardinality.Single.class);
        assertThat(tf.splitQuery()).isFalse();
        assertThat(tf.condition()).isInstanceOf(FieldConditionRef.NoFieldCondition.class);
        assertThat(tf.referencePath()).isEmpty();
    }

    @Test
    void tableField_listReturnType() {
        var schema = build("""
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") { actors: [Actor!]! }
            type Query { film: Film }
            """);
        assertThat(schema.field("Film", "actors")).isInstanceOf(TableField.class);
        assertThat(((TableField) schema.field("Film", "actors")).cardinality())
            .isInstanceOf(FieldCardinality.List.class);
    }

    @Test
    void tableField_connectionReturnType() {
        var schema = build("""
            type ActorConnection @table(name: "actor") { name: String }
            type Film @table(name: "film") { actors: ActorConnection }
            type Query { film: Film }
            """);
        assertThat(schema.field("Film", "actors")).isInstanceOf(TableField.class);
        assertThat(((TableField) schema.field("Film", "actors")).cardinality())
            .isInstanceOf(FieldCardinality.Connection.class);
    }

    @Test
    void tableField_splitQuery() {
        var schema = build("""
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") { actors: [Actor!]! @splitQuery }
            type Query { film: Film }
            """);
        assertThat(((TableField) schema.field("Film", "actors")).splitQuery()).isTrue();
    }

    @Test
    void tableField_withReferencePath() {
        var schema = build("""
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                language: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { film: Film }
            """);
        var tf = (TableField) schema.field("Film", "language");
        assertThat(tf.referencePath()).hasSize(1);
        assertThat(tf.referencePath().get(0)).isInstanceOf(FkRef.class);
    }

    @Test
    void tableField_defaultOrder_index() {
        var schema = build("""
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") {
                actors: [Actor!]! @defaultOrder(index: "idx_actor_name")
            }
            type Query { film: Film }
            """);
        var cardinality = (FieldCardinality.List) ((TableField) schema.field("Film", "actors")).cardinality();
        assertThat(cardinality.defaultOrder()).isNotNull();
        assertThat(cardinality.defaultOrder().spec()).isInstanceOf(OrderSpec.IndexOrder.class);
        assertThat(((OrderSpec.IndexOrder) cardinality.defaultOrder().spec()).indexName()).isEqualTo("idx_actor_name");
    }

    @Test
    void tableField_defaultOrder_primaryKey() {
        var schema = build("""
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") {
                actors: [Actor!]! @defaultOrder(primaryKey: true)
            }
            type Query { film: Film }
            """);
        var cardinality = (FieldCardinality.List) ((TableField) schema.field("Film", "actors")).cardinality();
        assertThat(cardinality.defaultOrder().spec()).isInstanceOf(OrderSpec.PrimaryKeyOrder.class);
    }

    @Test
    void tableField_defaultOrder_fields() {
        var schema = build("""
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") {
                actors: [Actor!]! @defaultOrder(fields: [{field: "last_name", collation: "C"}, {field: "first_name"}])
            }
            type Query { film: Film }
            """);
        var cardinality = (FieldCardinality.List) ((TableField) schema.field("Film", "actors")).cardinality();
        var fieldsOrder = (OrderSpec.FieldsOrder) cardinality.defaultOrder().spec();
        assertThat(fieldsOrder.fields()).hasSize(2);
        assertThat(fieldsOrder.fields().get(0).columnName()).isEqualTo("last_name");
        assertThat(fieldsOrder.fields().get(0).collation()).isEqualTo("C");
        assertThat(fieldsOrder.fields().get(1).columnName()).isEqualTo("first_name");
        assertThat(fieldsOrder.fields().get(1).collation()).isNull();
    }

    @Test
    void tableField_defaultOrder_direction_desc() {
        var schema = build("""
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") {
                actors: [Actor!]! @defaultOrder(primaryKey: true, direction: DESC)
            }
            type Query { film: Film }
            """);
        var cardinality = (FieldCardinality.List) ((TableField) schema.field("Film", "actors")).cardinality();
        assertThat(cardinality.defaultOrder().direction()).isEqualTo("DESC");
    }

    // ===== TableMethodField =====

    @Test
    void tableMethodField_singleReturn() {
        var schema = build("""
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                language: Language @tableMethod(tableMethodReference: {className: "com.example.Foo", methodName: "get"})
            }
            type Query { film: Film }
            """);
        var field = schema.field("Film", "language");
        assertThat(field).isInstanceOf(TableMethodField.class);
        assertThat(((TableMethodField) field).cardinality()).isInstanceOf(FieldCardinality.Single.class);
    }

    @Test
    void tableMethodField_listReturn() {
        var schema = build("""
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") {
                actors: [Actor!]! @tableMethod(tableMethodReference: {className: "com.example.Foo", methodName: "get"})
            }
            type Query { film: Film }
            """);
        assertThat(((TableMethodField) schema.field("Film", "actors")).cardinality())
            .isInstanceOf(FieldCardinality.List.class);
    }

    // ===== NestingField =====

    @Test
    void nestingField_plainObjectType() {
        var schema = build("""
            type FilmDetails { title: String description: String }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """);
        assertThat(schema.field("Film", "details")).isInstanceOf(NestingField.class);
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
        assertThat(((TableType) schema.type("Film")).table().tableName()).isEqualTo("film");
    }

    // ===== ErrorType =====

    @Test
    void errorType_generic_capturesHandlerType_and_className() {
        var schema = build("""
            type MyError @error(handlers: [{handler: GENERIC, className: "com.example.MyException"}]) {
                message: String
            }
            type Query { x: String }
            """);
        assertThat(schema.type("MyError")).isInstanceOf(ErrorType.class);
        var errorType = (ErrorType) schema.type("MyError");
        assertThat(errorType.handlers()).hasSize(1);
        var handler = errorType.handlers().get(0);
        assertThat(handler.handlerType()).isEqualTo(ErrorHandlerType.GENERIC);
        assertThat(handler.className()).isEqualTo("com.example.MyException");
    }

    @Test
    void errorType_database_capturesOptionalFields() {
        var schema = build("""
            type DbError @error(handlers: [{handler: DATABASE, sqlState: "23503", code: "1234", description: "FK violation"}]) {
                message: String
            }
            type Query { x: String }
            """);
        var errorType = (ErrorType) schema.type("DbError");
        var handler = errorType.handlers().get(0);
        assertThat(handler.handlerType()).isEqualTo(ErrorHandlerType.DATABASE);
        assertThat(handler.className()).isNull();
        assertThat(handler.sqlState()).isEqualTo("23503");
        assertThat(handler.code()).isEqualTo("1234");
        assertThat(handler.description()).isEqualTo("FK violation");
    }

    @Test
    void errorType_capturesMatchesField() {
        var schema = build("""
            type MatchError @error(handlers: [{handler: GENERIC, className: "com.example.Ex", matches: "duplicate"}]) {
                message: String
            }
            type Query { x: String }
            """);
        var handler = ((ErrorType) schema.type("MatchError")).handlers().get(0);
        assertThat(handler.matches()).isEqualTo("duplicate");
    }

    @Test
    void errorType_capturesMultipleHandlers() {
        var schema = build("""
            type MultiError @error(handlers: [
                {handler: GENERIC, className: "com.example.Ex1"},
                {handler: DATABASE, sqlState: "23505"}
            ]) {
                message: String
            }
            type Query { x: String }
            """);
        var errorType = (ErrorType) schema.type("MultiError");
        assertThat(errorType.handlers()).hasSize(2);
        assertThat(errorType.handlers().get(0).handlerType()).isEqualTo(ErrorHandlerType.GENERIC);
        assertThat(errorType.handlers().get(1).handlerType()).isEqualTo(ErrorHandlerType.DATABASE);
    }

    // ===== Registry validation =====

    @Test
    void build_throwsWhenDirectiveMissingFromRegistry() {
        // Build a registry that has no Graphitron directive definitions at all.
        TypeDefinitionRegistry registry = new SchemaParser().parse("type Query { x: String }");
        assertThatThrownBy(() -> GraphitronSchemaBuilder.build(registry))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("@");
    }

    // ===== Helper =====

    /**
     * Parses the given SDL text block together with the Graphitron directive definitions
     * and builds a {@link GraphitronSchema} via {@link GraphitronSchemaBuilder}.
     */
    private GraphitronSchema build(String schemaText) {
        String directivesContent = SchemaReadingHelper.fileAsString(
            java.nio.file.Paths.get("../../graphitron-common/src/main/resources/directives.graphqls"));
        TypeDefinitionRegistry registry = new SchemaParser().parse(directivesContent + "\n" + schemaText);
        return GraphitronSchemaBuilder.build(registry);
    }
}
