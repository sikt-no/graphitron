package no.sikt.graphitron.rewrite;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.configuration.ErrorHandlerType;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.rewrite.model.ChildField.ColumnField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.ChildField.ColumnReferenceField;
import no.sikt.graphitron.rewrite.model.ChildField.ComputedField;
import no.sikt.graphitron.rewrite.model.ChildField.InterfaceField;
import no.sikt.graphitron.rewrite.model.ChildField.MultitableReferenceField;
import no.sikt.graphitron.rewrite.model.ChildField.NestingField;
import no.sikt.graphitron.rewrite.model.ChildField.NodeIdField;
import no.sikt.graphitron.rewrite.model.ChildField.NodeIdReferenceField;
import no.sikt.graphitron.rewrite.model.ChildField.PropertyField;
import no.sikt.graphitron.rewrite.model.ChildField.ServiceTableField;
import no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField;
import no.sikt.graphitron.rewrite.model.ChildField.LookupTableField;
import no.sikt.graphitron.rewrite.model.ChildField.SplitTableField;
import no.sikt.graphitron.rewrite.model.ChildField.TableField;
import no.sikt.graphitron.rewrite.model.ChildField.TableInterfaceField;
import no.sikt.graphitron.rewrite.model.ChildField.TableMethodField;
import no.sikt.graphitron.rewrite.model.ChildField.UnionField;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField.NotGeneratedField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType;
import no.sikt.graphitron.rewrite.model.GraphitronType.InputType;
import no.sikt.graphitron.rewrite.model.GraphitronType.InterfaceType;
import no.sikt.graphitron.rewrite.model.GraphitronType.JavaRecordInputType;
import no.sikt.graphitron.rewrite.model.GraphitronType.JavaRecordType;
import no.sikt.graphitron.rewrite.model.GraphitronType.JooqTableRecordInputType;
import no.sikt.graphitron.rewrite.model.GraphitronType.JooqTableRecordType;
import no.sikt.graphitron.rewrite.model.GraphitronType.PojoInputType;
import no.sikt.graphitron.rewrite.model.GraphitronType.PojoResultType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ResultType;
import no.sikt.graphitron.rewrite.model.GraphitronType.NodeType;
import no.sikt.graphitron.rewrite.model.GraphitronType.RootType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnionType;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphql.schema.SchemaReadingHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.function.Consumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Level 2 classification tests. Each section has an enum where every constant is one (sdl, assertion)
 * case; the parameterised test method iterates the whole truth table automatically.
 */
class GraphitronSchemaBuilderTest {

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

    enum ColumnFieldCase {
        IMPLICIT_COLUMN_NAME(
            "scalar field without @field uses the GraphQL field name as the column name",
            """
            type Film @table(name: "film") { title: String }
            type Query { film: Film }
            """,
            schema -> {
                var col = (ColumnField) schema.field("Film", "title");
                assertThat(col).isInstanceOf(ColumnField.class);
                assertThat(col.columnName()).isEqualTo("title");
                assertThat(col.column()).isInstanceOf(ColumnRef.class);
                assertThat(col.javaNamePresent()).isFalse();
            }),

        EXPLICIT_FIELD_DIRECTIVE(
            "@field(name:) overrides the column name used for the DB lookup",
            """
            type Film @table(name: "film") { movieTitle: String @field(name: "title") }
            type Query { film: Film }
            """,
            schema -> {
                var col = (ColumnField) schema.field("Film", "movieTitle");
                assertThat(col.columnName()).isEqualTo("title");
                assertThat(col.javaNamePresent()).isFalse();
            }),

        JAVA_NAME_PRESENT(
            "@field(javaName:) marks javaNamePresent true",
            """
            type Film @table(name: "film") { title: String @field(name: "title", javaName: "getTitle") }
            type Query { film: Film }
            """,
            schema -> assertThat(((ColumnField) schema.field("Film", "title")).javaNamePresent()).isTrue()),

        UNRESOLVED_COLUMN(
            "field not present in the DB table produces an UnclassifiedField",
            """
            type Film @table(name: "film") { doesNotExist: String }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.field("Film", "doesNotExist")).isInstanceOf(UnclassifiedField.class)),

        ENUM_RETURN_TYPE(
            "a field whose return type is a GraphQL enum is still classified as a ColumnField",
            """
            enum Rating { G PG PG13 R NC17 }
            type Film @table(name: "film") { rating: Rating }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.field("Film", "rating")).isInstanceOf(ColumnField.class)),

        UNRESOLVED_TABLE(
            "when the parent table does not exist in the DB, the type becomes UnclassifiedType",
            """
            type NoSuchTable @table(name: "no_such_table") { title: String }
            type Query { x: NoSuchTable }
            """,
            schema -> assertThat(schema.type("NoSuchTable")).isInstanceOf(UnclassifiedType.class));

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        ColumnFieldCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ColumnFieldCase.class)
    void columnFieldClassification(ColumnFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== ColumnReferenceField =====

    enum ColumnReferenceFieldCase {
        KNOWN_FK_BY_SQL_NAME(
            "@reference with a lowercase SQL FK name resolves to FkJoin",
            """
            type Film @table(name: "film") {
              languageName: String @field(name: "name") @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { film: Film }
            """,
            schema -> {
                var ref = (ColumnReferenceField) schema.field("Film", "languageName");
                assertThat(ref.joinPath()).hasSize(1);
                assertThat(ref.joinPath().get(0)).isInstanceOf(JoinStep.FkJoin.class);
            }),

        KNOWN_FK_BY_JAVA_CONSTANT(
            "@reference with a Java-constant-style FK name also resolves to FkJoin",
            """
            type Film @table(name: "film") {
              languageName: String @field(name: "name") @reference(path: [{key: "FILM__FILM_LANGUAGE_ID_FKEY"}])
            }
            type Query { film: Film }
            """,
            schema -> {
                var ref = (ColumnReferenceField) schema.field("Film", "languageName");
                assertThat(ref.joinPath()).hasSize(1);
                assertThat(ref.joinPath().get(0)).isInstanceOf(JoinStep.FkJoin.class);
            }),

        UNKNOWN_FK(
            "@reference with an unknown key produces an UnclassifiedField",
            """
            type Film @table(name: "film") {
              languageName: String @reference(path: [{key: "NO_SUCH_FK"}])
            }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.field("Film", "languageName")).isInstanceOf(UnclassifiedField.class)),

        IMPLICIT_COLUMN_NAME(
            "column name defaults to the GraphQL field name when @field is absent",
            """
            type Film @table(name: "film") {
              name: String @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { film: Film }
            """,
            schema -> assertThat(((ColumnReferenceField) schema.field("Film", "name")).columnName())
                .isEqualTo("name")),

        EXPLICIT_FIELD_DIRECTIVE(
            "@field(name:) overrides the column name on a ColumnReferenceField",
            """
            type Film @table(name: "film") {
              languageName: String @field(name: "name")
                  @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { film: Film }
            """,
            schema -> assertThat(((ColumnReferenceField) schema.field("Film", "languageName")).columnName())
                .isEqualTo("name")),

        TABLE_PATH(
            "@reference with {table:} resolves the unique FK between the two tables",
            """
            type Customer @table(name: "customer") {
              district: String @reference(path: [{table: "address"}])
            }
            type Query { customer: Customer }
            """,
            schema -> {
                var ref = (ColumnReferenceField) schema.field("Customer", "district");
                assertThat(ref.joinPath()).hasSize(1);
                assertThat(ref.joinPath().get(0)).isInstanceOf(JoinStep.FkJoin.class);
                var fk = (JoinStep.FkJoin) ref.joinPath().get(0);
                assertThat(fk.targetTableSqlName()).isEqualToIgnoringCase("address");
            }),

        TABLE_PATH_AMBIGUOUS(
            "@reference with {table:} when multiple FKs exist between the two tables → UnclassifiedField",
            """
            type Film @table(name: "film") {
              languageName: String @field(name: "name") @reference(path: [{table: "language"}])
            }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.field("Film", "languageName")).isInstanceOf(UnclassifiedField.class)),

        TABLE_PATH_NO_FK(
            "@reference with {table:} pointing to a table with no FK to the source → UnclassifiedField",
            """
            type Film @table(name: "film") {
              actorId: Int @reference(path: [{table: "actor"}])
            }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.field("Film", "actorId")).isInstanceOf(UnclassifiedField.class)),

        TABLE_PATH_ON_IMPLICIT_INPUT(
            "@reference with {table:} on a field of an input type implicitly bound to a return table",
            """
            input Input { district: String! @reference(path: [{table: "address"}]) }
            type Customer @table(name: "customer") { customerId: Int! @field(name: "customer_id") }
            type Query { query(in: Input!): Customer }
            """,
            schema -> {
                var it = (no.sikt.graphitron.rewrite.model.GraphitronType.TableInputType) schema.type("Input");
                assertThat(it.table().tableName()).isEqualToIgnoringCase("customer");
                var ref = (no.sikt.graphitron.rewrite.model.InputField.ColumnReferenceField) it.inputFields().get(0);
                assertThat(ref.joinPath()).hasSize(1);
                assertThat(ref.joinPath().get(0)).isInstanceOf(JoinStep.FkJoin.class);
                var fk = (JoinStep.FkJoin) ref.joinPath().get(0);
                assertThat(fk.targetTableSqlName()).isEqualToIgnoringCase("address");
                assertThat(ref.column().javaName()).isEqualTo("DISTRICT");
            });

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        ColumnReferenceFieldCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ColumnReferenceFieldCase.class)
    void columnReferenceFieldClassification(ColumnReferenceFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== NotGeneratedField =====

    enum NotGeneratedFieldCase {
        BASIC(
            "@notGenerated fields are classified as NotGeneratedField regardless of return type",
            """
            type Film @table(name: "film") { title: String @notGenerated }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.field("Film", "title")).isInstanceOf(NotGeneratedField.class));

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        NotGeneratedFieldCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(NotGeneratedFieldCase.class)
    void notGeneratedFieldClassification(NotGeneratedFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== MultitableReferenceField =====

    enum MultitableReferenceFieldCase {
        BASIC(
            "@multitableReference produces a MultitableReferenceField",
            """
            type Film @table(name: "film") {
              other: String @multitableReference(routes: [{typeName: "X", path: [{key: "K"}]}])
            }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.field("Film", "other")).isInstanceOf(MultitableReferenceField.class));

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        MultitableReferenceFieldCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(MultitableReferenceFieldCase.class)
    void multitableReferenceFieldClassification(MultitableReferenceFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== NodeIdField =====

    enum NodeIdFieldCase {
        WITH_NODE_DIRECTIVE(
            "@nodeId on a type that also has @node — classified as NodeIdField with resolved key columns",
            """
            type Film @table(name: "film") @node(keyColumns: ["film_id"]) {
              id: ID! @nodeId
            }
            type Query { film: Film }
            """,
            schema -> {
                var field = (NodeIdField) schema.field("Film", "id");
                assertThat(field.nodeKeyColumns()).isNotEmpty();
            }),

        WITHOUT_NODE_DIRECTIVE(
            "@nodeId on a type without @node — classified as UnclassifiedField",
            """
            type Film @table(name: "film") { id: ID! @nodeId }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.field("Film", "id")).isInstanceOf(UnclassifiedField.class));

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        NodeIdFieldCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(NodeIdFieldCase.class)
    void nodeIdFieldClassification(NodeIdFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== NodeIdReferenceField =====

    enum NodeIdReferenceFieldCase {
        RESOLVED(
            "typeName pointing to a @node type resolves to NodeIdReferenceField with a WithNode and empty joinPath",
            """
            type Language @table(name: "language") @node(keyColumns: ["language_id"]) {
              id: ID! @nodeId
            }
            type Film @table(name: "film") {
              languageId: ID! @nodeId(typeName: "Language")
            }
            type Query { film: Film }
            """,
            schema -> {
                var ref = (NodeIdReferenceField) schema.field("Film", "languageId");
                assertThat(ref.typeName()).isEqualTo("Language");
                assertThat(ref.nodeKeyColumns()).isNotEmpty();
                assertThat(ref.joinPath()).isEmpty();
            }),

        UNRESOLVED_TYPE_HAS_NO_NODE(
            "typeName pointing to a type that exists but lacks @node → UnclassifiedField",
            """
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
              languageId: ID! @nodeId(typeName: "Language")
            }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.field("Film", "languageId")).isInstanceOf(UnclassifiedField.class)),

        UNRESOLVED_TYPE_DOES_NOT_EXIST(
            "typeName pointing to a type that does not exist at all → UnclassifiedField",
            """
            type Film @table(name: "film") {
              languageId: ID! @nodeId(typeName: "NoSuchType")
            }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.field("Film", "languageId")).isInstanceOf(UnclassifiedField.class)),

        WITH_REFERENCE_PATH(
            "@reference(path:) on a @nodeId field populates the joinPath",
            """
            type Language @table(name: "language") @node(keyColumns: ["language_id"]) {
              id: ID! @nodeId
            }
            type Film @table(name: "film") {
              languageId: ID! @nodeId(typeName: "Language")
                  @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { film: Film }
            """,
            schema -> {
                var ref = (NodeIdReferenceField) schema.field("Film", "languageId");
                assertThat(ref.joinPath()).hasSize(1);
                assertThat(ref.joinPath().get(0)).isInstanceOf(JoinStep.FkJoin.class);
            });

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        NodeIdReferenceFieldCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(NodeIdReferenceFieldCase.class)
    void nodeIdReferenceFieldClassification(NodeIdReferenceFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== TableField =====

    enum TableFieldCase {
        SINGLE_RETURN_TYPE(
            "object return type → Single cardinality, null condition, empty joinPath",
            """
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") { language: Language }
            type Query { film: Film }
            """,
            schema -> {
                var tf = (TableField) schema.field("Film", "language");
                assertThat(tf.returnType().wrapper()).isInstanceOf(FieldWrapper.Single.class);
                assertThat(tf.filters()).isEmpty();
                assertThat(tf.joinPath()).isEmpty();
            }),

        LIST_RETURN_TYPE(
            "list-wrapped object return type → List cardinality",
            """
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") { actors: [Actor!]! }
            type Query { film: Film }
            """,
            schema -> assertThat(((TableField) schema.field("Film", "actors")).returnType().wrapper())
                .isInstanceOf(FieldWrapper.List.class)),

        CONNECTION_RETURN_TYPE(
            "connection type detected via edges.node structure → Connection wrapper, element type resolved from node",
            """
            type Actor @table(name: "actor") { name: String }
            type ActorEdge { node: Actor cursor: String }
            type ActorConnection { edges: [ActorEdge] }
            type Film @table(name: "film") { actors: ActorConnection }
            type Query { film: Film }
            """,
            schema -> {
                var tf = (TableField) schema.field("Film", "actors");
                assertThat(tf.returnType().wrapper()).isInstanceOf(FieldWrapper.Connection.class);
                assertThat(tf.returnType()).isInstanceOf(no.sikt.graphitron.rewrite.model.ReturnTypeRef.TableBoundReturnType.class);
                assertThat(tf.returnType().returnTypeName()).isEqualTo("Actor");
            }),

        SPLIT_QUERY(
            "@splitQuery — field classified as SplitTableField",
            """
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") { actors: [Actor!]! @splitQuery }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.field("Film", "actors")).isInstanceOf(SplitTableField.class)),

        WITH_REFERENCE_PATH(
            "@reference(path:) populates the joinPath with one FkJoin",
            """
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                language: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { film: Film }
            """,
            schema -> {
                var tf = (TableField) schema.field("Film", "language");
                assertThat(tf.joinPath()).hasSize(1);
                assertThat(tf.joinPath().get(0)).isInstanceOf(JoinStep.FkJoin.class);
            }),

        MULTI_STEP_REFERENCE_PATH(
            "@reference with two path elements where the second is unknown → UnclassifiedField",
            """
            type City @table(name: "city") { name: String }
            type Film @table(name: "film") {
                city: City @reference(path: [{key: "film_language_id_fkey"}, {key: "NO_SUCH_FK"}])
            }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.field("Film", "city")).isInstanceOf(UnclassifiedField.class)),

        CONDITION_IS_ALWAYS_NULL(
            "@condition support is deferred to P3; filters list is always empty even with @reference",
            """
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                language: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { film: Film }
            """,
            schema -> assertThat(((TableField) schema.field("Film", "language")).filters())
                .isEmpty()),

        DEFAULT_ORDER_INDEX(
            "@defaultOrder(index:) resolves index columns — columns from idx_actor_last_name",
            """
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") {
                actors: [Actor!]! @defaultOrder(index: "idx_actor_last_name")
            }
            type Query { film: Film }
            """,
            schema -> {
                var order = (OrderBySpec.Fixed) ((TableField) schema.field("Film", "actors")).orderBy();
                assertThat(order).isNotNull();
                assertThat(order.direction()).isEqualTo("ASC");
                assertThat(order.columns()).hasSize(1);
                assertThat(order.columns().get(0).column().sqlName()).isEqualToIgnoringCase("last_name");
            }),

        DEFAULT_ORDER_PRIMARY_KEY(
            "@defaultOrder(primaryKey: true) resolves PK columns — actor_id",
            """
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") {
                actors: [Actor!]! @defaultOrder(primaryKey: true)
            }
            type Query { film: Film }
            """,
            schema -> {
                var order = (OrderBySpec.Fixed) ((TableField) schema.field("Film", "actors")).orderBy();
                assertThat(order).isNotNull();
                assertThat(order.columns()).hasSize(1);
                assertThat(order.columns().get(0).column().sqlName()).isEqualToIgnoringCase("actor_id");
            }),

        DEFAULT_ORDER_FIELDS(
            "@defaultOrder(fields:) resolves column names and preserves collations",
            """
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") {
                actors: [Actor!]! @defaultOrder(fields: [{name: "last_name", collate: "C"}, {name: "first_name"}])
            }
            type Query { film: Film }
            """,
            schema -> {
                var order = (OrderBySpec.Fixed) ((TableField) schema.field("Film", "actors")).orderBy();
                assertThat(order.columns()).hasSize(2);
                assertThat(order.columns().get(0).column().sqlName()).isEqualToIgnoringCase("last_name");
                assertThat(order.columns().get(0).collation()).isEqualTo("C");
                assertThat(order.columns().get(1).column().sqlName()).isEqualToIgnoringCase("first_name");
                assertThat(order.columns().get(1).collation()).isNull();
            }),

        DEFAULT_ORDER_DIRECTION_DESC(
            "@defaultOrder(direction: DESC) stores the direction in the Fixed order",
            """
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") {
                actors: [Actor!]! @defaultOrder(primaryKey: true, direction: DESC)
            }
            type Query { film: Film }
            """,
            schema -> {
                var order = (OrderBySpec.Fixed) ((TableField) schema.field("Film", "actors")).orderBy();
                assertThat(order.direction()).isEqualTo("DESC");
            }),

        CONNECTION_WITH_DEFAULT_ORDER_INDEX(
            "@defaultOrder(index:) on a connection field resolves to Fixed order",
            """
            type Actor @table(name: "actor") { name: String }
            type ActorEdge { node: Actor cursor: String }
            type ActorConnection { edges: [ActorEdge] }
            type Film @table(name: "film") {
                actors: ActorConnection @defaultOrder(index: "idx_actor_last_name")
            }
            type Query { film: Film }
            """,
            schema -> {
                var order = (OrderBySpec.Fixed) ((TableField) schema.field("Film", "actors")).orderBy();
                assertThat(order).isNotNull();
                assertThat(order.columns()).hasSize(1);
                assertThat(order.columns().get(0).column().sqlName()).isEqualToIgnoringCase("last_name");
            }),

        NO_DEFAULT_ORDER_PK_FALLBACK(
            "list field with no @defaultOrder on PK table — PK columns auto-filled as default order",
            """
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") {
                actors: [Actor!]!
            }
            type Query { film: Film }
            """,
            schema -> {
                var order = (OrderBySpec.Fixed) ((TableField) schema.field("Film", "actors")).orderBy();
                assertThat(order).isNotNull();
                assertThat(order.direction()).isEqualTo("ASC");
                assertThat(order.columns()).hasSize(1);
                assertThat(order.columns().get(0).column().sqlName()).isEqualToIgnoringCase("actor_id");
            }),

        NO_DEFAULT_ORDER_PKLESS_TABLE(
            "list field with no @defaultOrder on PK-less table — classified as QueryTableField with None ordering",
            """
            type FilmList @table(name: "film_list") { title: String }
            type Query { films: [FilmList!]! }
            """,
            schema -> {
                assertThat(schema.field("Query", "films")).isInstanceOf(QueryField.QueryTableField.class);
                var f = (QueryField.QueryTableField) schema.field("Query", "films");
                assertThat(f.orderBy()).isInstanceOf(OrderBySpec.None.class);
            });

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        TableFieldCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(TableFieldCase.class)
    void tableFieldClassification(TableFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== TableMethodField =====

    enum TableMethodFieldCase {
        SINGLE_RETURN(
            "@tableMethod with object return type → Single cardinality",
            """
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                language: Language @tableMethod(tableMethodReference: {className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "get"})
            }
            type Query { film: Film }
            """,
            schema -> assertThat(((TableMethodField) schema.field("Film", "language")).returnType().wrapper())
                .isInstanceOf(FieldWrapper.Single.class)),

        LIST_RETURN(
            "@tableMethod with list return type → List cardinality",
            """
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") {
                actors: [Actor!]! @tableMethod(tableMethodReference: {className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "get"})
            }
            type Query { film: Film }
            """,
            schema -> assertThat(((TableMethodField) schema.field("Film", "actors")).returnType().wrapper())
                .isInstanceOf(FieldWrapper.List.class)),

        CONNECTION_RETURN(
            "@tableMethod with connection return type → Connection wrapper, element type from edges.node",
            """
            type Actor @table(name: "actor") { name: String }
            type ActorEdge { node: Actor cursor: String }
            type ActorConnection { edges: [ActorEdge] }
            type Film @table(name: "film") {
                actors: ActorConnection @tableMethod(tableMethodReference: {className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "get"})
            }
            type Query { film: Film }
            """,
            schema -> {
                var tf = (TableMethodField) schema.field("Film", "actors");
                assertThat(tf.returnType().wrapper()).isInstanceOf(FieldWrapper.Connection.class);
                assertThat(tf.returnType().returnTypeName()).isEqualTo("Actor");
            }),

        WITH_REFERENCE_PATH(
            "@tableMethod + @reference(path:) populates the joinPath",
            """
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                language: Language
                    @tableMethod(tableMethodReference: {className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "get"})
                    @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { film: Film }
            """,
            schema -> {
                var field = (TableMethodField) schema.field("Film", "language");
                assertThat(field.joinPath()).hasSize(1);
                assertThat(field.joinPath().get(0)).isInstanceOf(JoinStep.FkJoin.class);
            });

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        TableMethodFieldCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(TableMethodFieldCase.class)
    void tableMethodFieldClassification(TableMethodFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== NestingField =====

    enum NestingFieldCase {
        PLAIN_OBJECT_TYPE(
            "a field returning a plain object type (no @table) on a @table parent → NestingField",
            """
            type FilmDetails { title: String description: String }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.field("Film", "details")).isInstanceOf(NestingField.class)),

        LIST_OF_PLAIN_OBJECT_TYPE(
            "a list-wrapped plain object type on a @table parent → NestingField",
            """
            type Tag { label: String }
            type Film @table(name: "film") { tags: [Tag!]! }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.field("Film", "tags")).isInstanceOf(NestingField.class));

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        NestingFieldCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(NestingFieldCase.class)
    void nestingFieldClassification(NestingFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== ServiceTableField / ServiceRecordField =====

    enum ServiceFieldCase {
        ON_TABLE_TYPE_SCALAR_RETURN(
            "@service on a @table parent returning scalar → ServiceRecordField",
            """
            type Film @table(name: "film") {
                rating: String @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"})
            }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.field("Film", "rating")).isInstanceOf(ServiceRecordField.class)),

        TABLE_TYPE_RETURN(
            "@service on @table parent returning another @table type → ServiceTableField",
            """
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                language: Language @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"})
            }
            type Query { film: Film }
            """,
            schema -> {
                var f = (ServiceTableField) schema.field("Film", "language");
                assertThat(f.returnType()).isInstanceOf(no.sikt.graphitron.rewrite.model.ReturnTypeRef.TableBoundReturnType.class);
                assertThat(f.returnType().returnTypeName()).isEqualTo("Language");
            });

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        ServiceFieldCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ServiceFieldCase.class)
    void serviceFieldClassification(ServiceFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== ComputedField =====

    enum ComputedFieldCase {
        SCALAR_RETURN(
            "@externalField on a @table parent → ComputedField",
            """
            type Film @table(name: "film") { rating: String @externalField }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.field("Film", "rating")).isInstanceOf(ComputedField.class));

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        ComputedFieldCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ComputedFieldCase.class)
    void computedFieldClassification(ComputedFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== TableInterfaceField / InterfaceField / UnionField =====

    enum InterfaceUnionFieldCase {
        TABLE_INTERFACE_FIELD(
            "field returning a @table+@discriminate interface → TableInterfaceField",
            """
            interface MediaItem @table(name: "film") @discriminate(on: "kind") { title: String }
            type Film implements MediaItem @table(name: "film") @discriminator(value: "film") { title: String }
            type Actor @table(name: "actor") { media: MediaItem }
            type Query { actor: Actor }
            """,
            schema -> assertThat(schema.field("Actor", "media")).isInstanceOf(TableInterfaceField.class)),

        INTERFACE_FIELD(
            "field returning a plain interface (no @table) → InterfaceField",
            """
            interface Named { name: String }
            type Language implements Named @table(name: "language") { name: String }
            type Film @table(name: "film") { language: Named }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.field("Film", "language")).isInstanceOf(InterfaceField.class)),

        UNION_FIELD(
            "field returning a union → UnionField",
            """
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") { title: String }
            union MediaItem = Language | Film
            type Actor @table(name: "actor") { media: MediaItem }
            type Query { actor: Actor }
            """,
            schema -> assertThat(schema.field("Actor", "media")).isInstanceOf(UnionField.class));

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        InterfaceUnionFieldCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(InterfaceUnionFieldCase.class)
    void interfaceUnionFieldClassification(InterfaceUnionFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== Fields on non-table-mapped parents (ResultType / @record) =====

    enum NonTableParentCase {
        PROPERTY_FIELD_ON_RESULT_TYPE(
            "@record (ResultType) parent — scalar field → PropertyField using field name as columnName",
            """
            type FilmDetails @record { title: String }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var f = (PropertyField) schema.field("FilmDetails", "title");
                assertThat(f.columnName()).isEqualTo("title");
            }),

        PROPERTY_FIELD_EXPLICIT_NAME(
            "@record parent + @field(name:) — PropertyField uses the explicit column name",
            """
            type FilmDetails @record { title: String @field(name: "film_title") }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var f = (PropertyField) schema.field("FilmDetails", "title");
                assertThat(f.columnName()).isEqualTo("film_title");
            }),

        SERVICE_FIELD_ON_RESULT_TYPE(
            "@record parent + @service + scalar return → ServiceRecordField",
            """
            type FilmDetails @record { rating: String @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"}) }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.field("FilmDetails", "rating")).isInstanceOf(ServiceRecordField.class));

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        NonTableParentCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(NonTableParentCase.class)
    void nonTableParentFieldClassification(NonTableParentCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== ResultType backing-class classification =====

    enum ResultTypeCase {
        NO_CLASS(
            "@record with no backing class → PojoResultType with null fqClassName",
            """
            type FilmDetails @record { id: ID }
            type Query { foo: String }
            """,
            schema -> {
                var t = (PojoResultType) schema.type("FilmDetails");
                assertThat(t.fqClassName()).isNull();
            }),

        POJO_CLASS(
            "@record with plain Java class → PojoResultType with fqClassName",
            """
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) { id: ID }
            type Query { foo: String }
            """,
            schema -> {
                var t = (PojoResultType) schema.type("FilmDetails");
                assertThat(t.fqClassName()).isEqualTo("no.sikt.graphitron.codereferences.dummyreferences.DummyRecord");
            }),

        JAVA_RECORD_CLASS(
            "@record with Java record class → JavaRecordType with fqClassName",
            """
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.TestRecordDto"}) { id: ID }
            type Query { foo: String }
            """,
            schema -> {
                var t = (JavaRecordType) schema.type("FilmDetails");
                assertThat(t.fqClassName()).isEqualTo("no.sikt.graphitron.codereferences.dummyreferences.TestRecordDto");
            }),

        JOOQ_TABLE_RECORD_CLASS(
            "@record with jOOQ TableRecord class → JooqTableRecordType with fqClassName and resolved table",
            """
            type FilmDetails @record(record: {className: "no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.FilmRecord"}) { id: ID }
            type Query { foo: String }
            """,
            schema -> {
                var t = (JooqTableRecordType) schema.type("FilmDetails");
                assertThat(t.fqClassName()).isEqualTo("no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.FilmRecord");
                assertThat(t.table()).isNotNull();
                assertThat(t.table().tableName()).isEqualTo("film");
            }),

        UNKNOWN_CLASS(
            "@record with unresolvable class → UnclassifiedType with explanation",
            """
            type FilmDetails @record(record: {className: "com.example.nonexistent.Missing"}) { id: ID }
            type Query { foo: String }
            """,
            schema -> {
                var t = (UnclassifiedType) schema.type("FilmDetails");
                assertThat(t.reason()).contains("com.example.nonexistent.Missing").contains("could not be loaded");
            });

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        ResultTypeCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ResultTypeCase.class)
    void resultTypeBackingClassClassification(ResultTypeCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== P4: Field arguments =====

    enum ArgumentParsingCase {
        TABLE_FIELD_NO_ARGS(
            "TableField with no arguments — empty filters list",
            """
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") { actors: [Actor!]! }
            type Query { film: Film }
            """,
            schema -> {
                var f = (no.sikt.graphitron.rewrite.model.ChildField.TableField) schema.field("Film", "actors");
                assertThat(f.filters()).isEmpty();
            }),

        TABLE_FIELD_WITH_ARGS(
            "TableField with two column arguments — one GeneratedConditionFilter with two BodyParams",
            """
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") {
                actors(actor_id: ID!, first_name: [String!]): [Actor!]!
            }
            type Query { film: Film }
            """,
            schema -> {
                var f = (no.sikt.graphitron.rewrite.model.ChildField.TableField) schema.field("Film", "actors");
                assertThat(f.filters()).hasSize(1);
                var gcf = (GeneratedConditionFilter) f.filters().get(0);
                assertThat(gcf.bodyParams()).hasSize(2);
                assertThat(gcf.bodyParams().get(0).name()).isEqualTo("actor_id");
                assertThat(gcf.bodyParams().get(0).nonNull()).isTrue();
                assertThat(gcf.bodyParams().get(0).list()).isFalse();
                assertThat(gcf.bodyParams().get(1).name()).isEqualTo("first_name");
                assertThat(gcf.bodyParams().get(1).list()).isTrue();
            }),

        TABLE_FIELD_LOOKUP_KEY_ARG(
            "@lookupKey on a child-field argument (no @splitQuery) — field classified as LookupTableField",
            """
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") {
                actor(actor_id: ID! @lookupKey): Actor
            }
            type Query { film: Film }
            """,
            schema -> {
                var f = (no.sikt.graphitron.rewrite.model.ChildField.LookupTableField) schema.field("Film", "actor");
                assertThat(f.filters()).hasSize(1);
                assertThat(((GeneratedConditionFilter) f.filters().get(0)).bodyParams().get(0).name()).isEqualTo("actor_id");
            }),

        TABLE_FIELD_ORDER_BY_ARG(
            "@orderBy arg with valid input type → OrderBySpec.Argument on orderBy(); filters empty",
            """
            enum ActorOrderField { FIRST_NAME @order(index: "IDX_ACTOR_LAST_NAME") }
            enum Direction { ASC DESC }
            input ActorOrder { sortField: ActorOrderField! direction: Direction! }
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") {
                actors(order: ActorOrder @orderBy): [Actor!]!
            }
            type Query { film: Film }
            """,
            schema -> {
                var f = (no.sikt.graphitron.rewrite.model.ChildField.TableField) schema.field("Film", "actors");
                assertThat(f.filters()).isEmpty();
                assertThat(f.orderBy()).isInstanceOf(OrderBySpec.Argument.class);
                assertThat(((OrderBySpec.Argument) f.orderBy()).typeName()).isEqualTo("ActorOrder");
            }),

        SERVICE_FIELD_CONTEXT_ARGS(
            "@service field is classified as ServiceRecordField — method reference resolved",
            """
            type Film @table(name: "film") {
                rating: String @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"}, contextArguments: ["tenantId", "userId"])
            }
            type Query { film: Film }
            """,
            schema -> {
                var f = (no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField) schema.field("Film", "rating");
                assertThat(f.method().className()).isEqualTo("no.sikt.graphitron.rewrite.TestServiceStub");
                assertThat(f.method().methodName()).isEqualTo("get");
            }),

        TABLE_METHOD_FIELD_CONTEXT_ARGS(
            "@tableMethod with contextArguments — context param reflected into ParamSource.Context",
            """
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                language: Language
                    @tableMethod(tableMethodReference: {className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "getWithContext"}, contextArguments: ["tenantId"])
            }
            type Query { film: Film }
            """,
            schema -> {
                var f = (no.sikt.graphitron.rewrite.model.ChildField.TableMethodField) schema.field("Film", "language");
                assertThat(f.method().params())
                    .filteredOn(p -> p instanceof no.sikt.graphitron.rewrite.model.MethodRef.Param.Typed t
                        && t.source() instanceof no.sikt.graphitron.rewrite.model.ParamSource.Context)
                    .extracting(p -> ((no.sikt.graphitron.rewrite.model.MethodRef.Param.Typed) p).name())
                    .containsExactly("tenantId");
            });

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        ArgumentParsingCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ArgumentParsingCase.class)
    void argumentParsing(ArgumentParsingCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== P4: InputType classification =====

    enum InputTypeCase {
        BASIC_INPUT_TYPE(
            "input type with no @table → classified as InputType",
            """
            input FilmInput { title: String! releaseYear: Int }
            type Query { x: String }
            """,
            schema -> assertThat(schema.type("FilmInput"))
                .isInstanceOf(InputType.class)),

        NO_CLASS(
            "@record with no backing class → PojoInputType with null fqClassName",
            """
            input FilmInput @record { id: ID }
            type Query { x: String }
            """,
            schema -> {
                var t = (PojoInputType) schema.type("FilmInput");
                assertThat(t.fqClassName()).isNull();
            }),

        POJO_CLASS(
            "@record with plain Java class → PojoInputType with fqClassName",
            """
            input FilmInput @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) { id: ID }
            type Query { x: String }
            """,
            schema -> {
                var t = (PojoInputType) schema.type("FilmInput");
                assertThat(t.fqClassName()).isEqualTo("no.sikt.graphitron.codereferences.dummyreferences.DummyRecord");
            }),

        JAVA_RECORD_CLASS(
            "@record with Java record class → JavaRecordInputType with fqClassName",
            """
            input FilmInput @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.TestRecordDto"}) { id: ID }
            type Query { x: String }
            """,
            schema -> {
                var t = (JavaRecordInputType) schema.type("FilmInput");
                assertThat(t.fqClassName()).isEqualTo("no.sikt.graphitron.codereferences.dummyreferences.TestRecordDto");
            }),

        JOOQ_TABLE_RECORD_CLASS(
            "@record with jOOQ TableRecord class → JooqTableRecordInputType with fqClassName and resolved table",
            """
            input FilmInput @record(record: {className: "no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.FilmRecord"}) { id: ID }
            type Query { x: String }
            """,
            schema -> {
                var t = (JooqTableRecordInputType) schema.type("FilmInput");
                assertThat(t.fqClassName()).isEqualTo("no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.FilmRecord");
                assertThat(t.table()).isNotNull();
                assertThat(t.table().tableName()).isEqualTo("film");
            }),

        UNKNOWN_CLASS(
            "@record with unresolvable class → UnclassifiedType with explanation",
            """
            input FilmInput @record(record: {className: "com.example.nonexistent.Missing"}) { id: ID }
            type Query { x: String }
            """,
            schema -> {
                var t = (UnclassifiedType) schema.type("FilmInput");
                assertThat(t.reason()).contains("com.example.nonexistent.Missing").contains("could not be loaded");
            });

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        InputTypeCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(InputTypeCase.class)
    void inputTypeClassification(InputTypeCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== P4b: TableInputType classification =====

    enum TableInputTypeCase {
        EXPLICIT_TABLE_DIRECTIVE(
            "input type with @table → TableInputType with ResolvedTable",
            """
            input CustomerInput @table(name: "customer") { customerId: Int! @field(name: "customer_id") }
            type Query { x: String }
            """,
            schema -> {
                var it = (no.sikt.graphitron.rewrite.model.GraphitronType.TableInputType) schema.type("CustomerInput");
                assertThat(it.table()).isInstanceOf(no.sikt.graphitron.rewrite.model.TableRef.class);
                assertThat(it.table().tableName()).isEqualTo("customer");
                assertThat(it.inputFields()).hasSize(1);
                assertThat(it.inputFields().get(0)).isInstanceOf(no.sikt.graphitron.rewrite.model.InputField.ColumnField.class);
                var f = (no.sikt.graphitron.rewrite.model.InputField.ColumnField) it.inputFields().get(0);
                assertThat(f.name()).isEqualTo("customerId");
                assertThat(f.column().javaName()).isEqualTo("CUSTOMER_ID");
            }),

        EXPLICIT_TABLE_UNRESOLVED_COLUMN(
            "input type with @table but unknown column → UnclassifiedType",
            """
            input CustomerInput @table(name: "customer") { noSuchField: Int! }
            type Query { x: String }
            """,
            schema -> assertThat(schema.type("CustomerInput"))
                .isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType.class)),

        EXPLICIT_TABLE_UNRESOLVED_TABLE(
            "input type with @table pointing to unknown DB table → UnclassifiedType",
            """
            input NoSuchInput @table(name: "no_such_table") { id: Int! }
            type Query { x: String }
            """,
            schema -> assertThat(schema.type("NoSuchInput"))
                .isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType.class)),

        IMPLICIT_TABLE_FROM_LOOKUP_FIELD(
            "input type without @table used on a QueryLookupTableField → promoted to TableInputType",
            """
            input CustomerInput { customerId: Int! @field(name: "customer_id") }
            type Customer @table(name: "customer") { customerId: Int! @field(name: "customer_id") }
            type Query { customer(input: CustomerInput! @lookupKey): Customer }
            """,
            schema -> {
                assertThat(schema.type("CustomerInput"))
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronType.TableInputType.class);
                var it = (no.sikt.graphitron.rewrite.model.GraphitronType.TableInputType) schema.type("CustomerInput");
                assertThat(it.table().tableName()).isEqualTo("customer");
                assertThat(it.inputFields().get(0))
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.InputField.ColumnField.class);
            }),

        IMPLICIT_TABLE_CONFLICT(
            "input type used on fields with different return tables → UnclassifiedType",
            """
            input SharedInput { id: Int! }
            type Customer @table(name: "customer") { customerId: Int! @field(name: "customer_id") }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query {
                customer(input: SharedInput! @lookupKey): Customer
                film(input: SharedInput! @lookupKey): Film
            }
            """,
            schema -> assertThat(schema.type("SharedInput"))
                .isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType.class)),

        COLUMN_REFERENCE_FIELD(
            "@reference on an input field → ColumnReferenceField with resolved join path and column",
            """
            input FilmInput @table(name: "film") {
              filmId: Int! @field(name: "film_id")
              languageName: String @field(name: "name") @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { x: String }
            """,
            schema -> {
                var it = (no.sikt.graphitron.rewrite.model.GraphitronType.TableInputType) schema.type("FilmInput");
                assertThat(it.inputFields()).hasSize(2);
                var refField = it.inputFields().stream()
                    .filter(f -> f instanceof no.sikt.graphitron.rewrite.model.InputField.ColumnReferenceField)
                    .findFirst().orElseThrow();
                var crf = (no.sikt.graphitron.rewrite.model.InputField.ColumnReferenceField) refField;
                assertThat(crf.name()).isEqualTo("languageName");
                assertThat(crf.joinPath()).hasSize(1);
                assertThat(crf.joinPath().get(0)).isInstanceOf(no.sikt.graphitron.rewrite.model.JoinStep.FkJoin.class);
                assertThat(crf.column().javaName()).isEqualTo("NAME");
            }),

        COLUMN_REFERENCE_FIELD_UNKNOWN_FK(
            "@reference on an input field with unknown FK → UnclassifiedType",
            """
            input FilmInput @table(name: "film") {
              filmId: Int! @field(name: "film_id")
              languageName: String @field(name: "name") @reference(path: [{key: "no_such_fkey"}])
            }
            type Query { x: String }
            """,
            schema -> assertThat(schema.type("FilmInput"))
                .isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType.class));

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        TableInputTypeCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(TableInputTypeCase.class)
    void tableInputTypeClassification(TableInputTypeCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== Type classification =====

    enum TypeClassificationCase {
        RESOLVED_TABLE(
            "@table(name:) with a real DB table → TableType with ResolvedTable",
            """
            type Film @table(name: "film") { title: String }
            type Query { film: Film }
            """,
            schema -> {
                assertThat(schema.type("Film")).isInstanceOf(TableType.class);
                assertThat(((TableType) schema.type("Film")).table()).isInstanceOf(TableRef.class);
            }),

        TABLE_NAME_DEFAULTS_TO_LOWERCASE_TYPE_NAME(
            "@table without name attribute uses the lower-cased GraphQL type name as the table name",
            """
            type Film @table { title: String }
            type Query { film: Film }
            """,
            schema -> assertThat(((TableType) schema.type("Film")).table().tableName()).isEqualTo("film"));

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        TypeClassificationCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(TypeClassificationCase.class)
    void typeClassification(TypeClassificationCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== ErrorType =====

    enum ErrorTypeCase {
        GENERIC_HANDLER_TYPE_AND_CLASS_NAME(
            "GENERIC handler captures handlerType and className",
            """
            type MyError @error(handlers: [{handler: GENERIC, className: "com.example.MyException"}]) {
                message: String
            }
            type Query { x: String }
            """,
            schema -> {
                var errorType = (ErrorType) schema.type("MyError");
                assertThat(errorType.handlers()).hasSize(1);
                var h = errorType.handlers().get(0);
                assertThat(h.handlerType()).isEqualTo(ErrorHandlerType.GENERIC);
                assertThat(h.className()).isEqualTo("com.example.MyException");
            }),

        DATABASE_OPTIONAL_FIELDS(
            "DATABASE handler captures sqlState, code, and description; className is null",
            """
            type DbError @error(handlers: [{handler: DATABASE, sqlState: "23503", code: "1234", description: "FK violation"}]) {
                message: String
            }
            type Query { x: String }
            """,
            schema -> {
                var h = ((ErrorType) schema.type("DbError")).handlers().get(0);
                assertThat(h.handlerType()).isEqualTo(ErrorHandlerType.DATABASE);
                assertThat(h.className()).isNull();
                assertThat(h.sqlState()).isEqualTo("23503");
                assertThat(h.code()).isEqualTo("1234");
                assertThat(h.description()).isEqualTo("FK violation");
            }),

        CAPTURES_MATCHES_FIELD(
            "matches field is captured when present",
            """
            type MatchError @error(handlers: [{handler: GENERIC, className: "com.example.Ex", matches: "duplicate"}]) {
                message: String
            }
            type Query { x: String }
            """,
            schema -> assertThat(((ErrorType) schema.type("MatchError")).handlers().get(0).matches())
                .isEqualTo("duplicate")),

        MULTIPLE_HANDLERS(
            "multiple handler objects in the array are all captured",
            """
            type MultiError @error(handlers: [
                {handler: GENERIC, className: "com.example.Ex1"},
                {handler: DATABASE, sqlState: "23505"}
            ]) {
                message: String
            }
            type Query { x: String }
            """,
            schema -> {
                var handlers = ((ErrorType) schema.type("MultiError")).handlers();
                assertThat(handlers).hasSize(2);
                assertThat(handlers.get(0).handlerType()).isEqualTo(ErrorHandlerType.GENERIC);
                assertThat(handlers.get(1).handlerType()).isEqualTo(ErrorHandlerType.DATABASE);
            });

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        ErrorTypeCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ErrorTypeCase.class)
    void errorTypeClassification(ErrorTypeCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== hasLookupKeyAnywhere() depth guard =====

    /**
     * Verifies that {@code hasLookupKeyAnywhere()} does not recurse infinitely on circular
     * input type references. A depth limit of 10 in {@code inputTypeHasLookupKey()} is the
     * guard. A field whose input chain reaches the limit without finding {@code @lookupKey}
     * must be classified as a non-lookup query field (here {@code QueryTableField}) rather
     * than causing a stack overflow.
     */
    @Test
    void lookupKeySearch_depthGuardPreventsInfiniteRecursionOnCircularInputTypes() {
        // A → B → A … circular reference; no @lookupKey anywhere in the chain.
        // The guard stops at depth 10 and returns false, so the field falls through
        // to QueryTableField classification.
        var schema = build("""
            input A { b: B }
            input B { a: A }
            type Film @table(name: "film") { title: String }
            type Query { films(filter: A): [Film!]! }
            """);

        assertThat(schema.field("Query", "films"))
            .as("circular input chain with no @lookupKey → QueryTableField, not a stack overflow")
            .isInstanceOf(QueryField.QueryTableField.class);
    }

    // ===== Registry validation =====

    @Test
    void build_throwsWhenDirectiveMissingFromRegistry() {
        TypeDefinitionRegistry registry = new SchemaParser().parse("type Query { x: String }");
        assertThatThrownBy(() -> GraphitronSchemaBuilder.build(registry))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("@");
    }

    // ===== P5: Root field classification =====

    enum RootFieldCase {

        LOOKUP_QUERY_FIELD(
            "field with @lookupKey list arg → QueryLookupTableField with list return",
            """
            type Film @table(name: "film") { title: String }
            type Query { filmById(film_id: [ID] @lookupKey): [Film!]! }
            """,
            schema -> {
                assertThat(schema.field("Query", "filmById")).isInstanceOf(QueryField.QueryLookupTableField.class);
                var f = (QueryField.QueryLookupTableField) schema.field("Query", "filmById");
                assertThat(f.filters()).hasSize(1);
                assertThat(((GeneratedConditionFilter) f.filters().get(0)).bodyParams().get(0).name()).isEqualTo("film_id");
                assertThat(f.returnType().wrapper()).isInstanceOf(FieldWrapper.List.class);
            }),

        LOOKUP_NESTED_IN_INPUT(
            "@lookupKey nested in input type → field still classified as QueryLookupTableField",
            """
            input FilmKey { id: ID @lookupKey }
            type Film @table(name: "film") { title: String }
            type Query { filmByKey(key: [FilmKey]): [Film!]! }
            """,
            schema -> assertThat(schema.field("Query", "filmByKey")).isInstanceOf(QueryField.QueryLookupTableField.class)),

        LOOKUP_FIELD_COLUMN_ARG(
            "lookup field list arg whose column exists → ColumnFilter with resolved jOOQ field",
            """
            type Film @table(name: "film") { title: String }
            type Query { filmById(film_id: [ID] @lookupKey): [Film!]! }
            """,
            schema -> {
                var f = (QueryField.QueryLookupTableField) schema.field("Query", "filmById");
                assertThat(f.filters()).hasSize(1);
                assertThat(f.filters().get(0)).isInstanceOf(GeneratedConditionFilter.class);
                var gcf = (GeneratedConditionFilter) f.filters().get(0);
                var a = gcf.bodyParams().get(0);
                assertThat(a.name()).isEqualTo("film_id");
                assertThat(a.column().javaName()).isEqualTo("FILM_ID");
                assertThat(a.column().columnClass()).isNotEmpty();
            }),

        LOOKUP_FIELD_PLAIN_SCALAR_ARG(
            "lookup field list arg with no matching column → UnclassifiedField",
            """
            type Film @table(name: "film") { title: String }
            type Query { filmById(unknownColumn: [String] @lookupKey): [Film!]! }
            """,
            schema -> {
                assertThat(schema.field("Query", "filmById"))
                    .isInstanceOf(UnclassifiedField.class);
                var uf = (UnclassifiedField) schema.field("Query", "filmById");
                assertThat(uf.reason()).contains("unknownColumn");
            }),

        LOOKUP_FIELD_TABLE_INPUT_TYPE_ARG(
            "lookup field with explicit @table input type arg → filters empty (input types deferred)",
            """
            input FilmKey @table(name: "film") { filmId: Int @field(name: "film_id") }
            type Film @table(name: "film") { title: String }
            type Query { filmByKey(key: [FilmKey] @lookupKey): [Film!]! }
            """,
            schema -> {
                var f = (QueryField.QueryLookupTableField) schema.field("Query", "filmByKey");
                assertThat(f.filters()).isEmpty();
            }),

        LOOKUP_FIELD_IMPLICIT_TABLE_INPUT_TYPE_ARG(
            "lookup field with plain input type arg (no @table) → filters empty, type promoted to TableInputType",
            """
            input FilmKey { filmId: Int @field(name: "film_id") }
            type Film @table(name: "film") { title: String }
            type Query { filmByKey(key: [FilmKey] @lookupKey): [Film!]! }
            """,
            schema -> {
                var f = (QueryField.QueryLookupTableField) schema.field("Query", "filmByKey");
                assertThat(f.filters()).isEmpty();
                // The type was promoted to TableInputType in types map
                assertThat(schema.type("FilmKey"))
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronType.TableInputType.class);
            }),

        LOOKUP_FIELD_ORDERBY_ARG(
            "@orderBy arg with valid input type structure → OrderBySpec.Argument with resolved field names",
            """
            enum FilmOrderField { TITLE @order(index: "IDX_TITLE") }
            enum Direction { ASC DESC }
            input FilmOrder { sortField: FilmOrderField! direction: Direction! }
            type Film @table(name: "film") { title: String }
            type Query { filmById(film_id: [ID] @lookupKey, order: FilmOrder @orderBy): [Film!]! }
            """,
            schema -> {
                var f = (QueryField.QueryLookupTableField) schema.field("Query", "filmById");
                assertThat(f.orderBy()).isInstanceOf(OrderBySpec.Argument.class);
                var orderBy = (OrderBySpec.Argument) f.orderBy();
                assertThat(orderBy.sortFieldName()).isEqualTo("sortField");
                assertThat(orderBy.directionFieldName()).isEqualTo("direction");
                assertThat(f.filters()).hasSize(1);
                assertThat(((GeneratedConditionFilter) f.filters().get(0)).bodyParams().get(0).name()).isEqualTo("film_id");
            }),

        LOOKUP_FIELD_ORDERBY_ARG_BAD_STRUCTURE(
            "@orderBy arg whose type is not an input type → UnclassifiedField",
            """
            enum FilmOrder { TITLE }
            type Film @table(name: "film") { title: String }
            type Query { filmById(film_id: [ID] @lookupKey, order: FilmOrder @orderBy): [Film!]! }
            """,
            schema -> {
                assertThat(schema.field("Query", "filmById"))
                    .isInstanceOf(UnclassifiedField.class);
                var uf = (UnclassifiedField) schema.field("Query", "filmById");
                assertThat(uf.reason()).contains("not an input type");
            }),

        TABLE_QUERY_FIELD(
            "field returning @table type → QueryTableField",
            """
            type Film @table(name: "film") { title: String }
            type Query { films: [Film!]! }
            """,
            schema -> assertThat(schema.field("Query", "films")).isInstanceOf(QueryField.QueryTableField.class)),

        TABLE_QUERY_FIELD_WITH_ARGS(
            "table query field with @orderBy argument → OrderBySpec.Argument on orderBy(); filters empty",
            """
            enum FilmOrderField { TITLE @order(index: "IDX_TITLE") }
            enum Direction { ASC DESC }
            input FilmOrder { sortField: FilmOrderField! direction: Direction! }
            type Film @table(name: "film") { title: String }
            type Query { films(orderBy: FilmOrder @orderBy): [Film!]! }
            """,
            schema -> {
                var f = (QueryField.QueryTableField) schema.field("Query", "films");
                assertThat(f.filters()).isEmpty();
                assertThat(f.orderBy()).isInstanceOf(OrderBySpec.Argument.class);
            }),

        TABLE_METHOD_QUERY_FIELD(
            "@tableMethod on root field → QueryTableMethodTableField with context param reflected",
            """
            type Film @table(name: "film") { title: String }
            type Query {
                filteredFilms: [Film!]!
                    @tableMethod(tableMethodReference: {className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "getWithContext"}, contextArguments: ["tenantId"])
            }
            """,
            schema -> {
                assertThat(schema.field("Query", "filteredFilms")).isInstanceOf(QueryField.QueryTableMethodTableField.class);
                var f = (QueryField.QueryTableMethodTableField) schema.field("Query", "filteredFilms");
                assertThat(f.method().params())
                    .filteredOn(p -> p instanceof no.sikt.graphitron.rewrite.model.MethodRef.Param.Typed t
                        && t.source() instanceof no.sikt.graphitron.rewrite.model.ParamSource.Context)
                    .extracting(p -> ((no.sikt.graphitron.rewrite.model.MethodRef.Param.Typed) p).name())
                    .containsExactly("tenantId");
            }),

        NODE_QUERY_FIELD(
            "field named 'node' → QueryNodeField",
            """
            interface Node { id: ID! }
            type Film implements Node @table(name: "film") { id: ID! title: String }
            type Query { node(id: ID!): Node }
            """,
            schema -> assertThat(schema.field("Query", "node")).isInstanceOf(QueryField.QueryNodeField.class)),

        ENTITY_QUERY_FIELD(
            "field named '_entities' → QueryEntityField",
            """
            scalar _Any
            union _Entity = Film
            type Film @table(name: "film") { title: String }
            type Query { _entities(representations: [_Any!]!): [_Entity]! }
            """,
            schema -> assertThat(schema.field("Query", "_entities")).isInstanceOf(QueryField.QueryEntityField.class)),

        TABLE_INTERFACE_QUERY_FIELD(
            "field returning table-interface type → QueryTableInterfaceField",
            """
            interface MediaItem @table(name: "film") @discriminate(on: "kind") { title: String }
            type Film implements MediaItem @table(name: "film") @discriminator(value: "film") { title: String }
            type Query { media: MediaItem }
            """,
            schema -> assertThat(schema.field("Query", "media")).isInstanceOf(QueryField.QueryTableInterfaceField.class)),

        INTERFACE_QUERY_FIELD(
            "field returning plain interface → QueryInterfaceField",
            """
            interface Named { name: String }
            type Film implements Named @table(name: "film") { name: String }
            type Query { named: Named }
            """,
            schema -> assertThat(schema.field("Query", "named")).isInstanceOf(QueryField.QueryInterfaceField.class)),

        UNION_QUERY_FIELD(
            "field returning union → QueryUnionField",
            """
            type Film @table(name: "film") { title: String }
            type Actor @table(name: "actor") { name: String }
            union SearchResult = Film | Actor
            type Query { search: SearchResult }
            """,
            schema -> assertThat(schema.field("Query", "search")).isInstanceOf(QueryField.QueryUnionField.class)),

        SERVICE_QUERY_FIELD(
            "@service on root query field → QueryServiceTableField with method reference resolved",
            """
            type Film @table(name: "film") { title: String }
            type Query {
                externalFilm: Film @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"})
            }
            """,
            schema -> {
                assertThat(schema.field("Query", "externalFilm")).isInstanceOf(QueryField.QueryServiceTableField.class);
                var f = (QueryField.QueryServiceTableField) schema.field("Query", "externalFilm");
                assertThat(f.method().className()).isEqualTo("no.sikt.graphitron.rewrite.TestServiceStub");
                assertThat(f.method().methodName()).isEqualTo("get");
            }),

        INSERT_MUTATION_FIELD(
            "@mutation(typeName: INSERT) → MutationInsertTableField",
            """
            type Film @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { createFilm: Film @mutation(typeName: INSERT) }
            """,
            schema -> assertThat(schema.field("Mutation", "createFilm")).isInstanceOf(MutationField.MutationInsertTableField.class)),

        UPDATE_MUTATION_FIELD(
            "@mutation(typeName: UPDATE) → MutationUpdateTableField",
            """
            type Film @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { updateFilm: Film @mutation(typeName: UPDATE) }
            """,
            schema -> assertThat(schema.field("Mutation", "updateFilm")).isInstanceOf(MutationField.MutationUpdateTableField.class)),

        DELETE_MUTATION_FIELD(
            "@mutation(typeName: DELETE) → MutationDeleteTableField",
            """
            type Film @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { deleteFilm: Film @mutation(typeName: DELETE) }
            """,
            schema -> assertThat(schema.field("Mutation", "deleteFilm")).isInstanceOf(MutationField.MutationDeleteTableField.class)),

        UPSERT_MUTATION_FIELD(
            "@mutation(typeName: UPSERT) → MutationUpsertTableField",
            """
            type Film @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { upsertFilm: Film @mutation(typeName: UPSERT) }
            """,
            schema -> assertThat(schema.field("Mutation", "upsertFilm")).isInstanceOf(MutationField.MutationUpsertTableField.class)),

        SERVICE_MUTATION_FIELD(
            "@service on mutation field → MutationServiceTableField",
            """
            type Film @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation {
                externalMutation: Film @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "run"})
            }
            """,
            schema -> assertThat(schema.field("Mutation", "externalMutation")).isInstanceOf(MutationField.MutationServiceTableField.class));

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        RootFieldCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(RootFieldCase.class)
    void rootFieldClassification(RootFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== UnclassifiedField =====

    enum UnclassifiedFieldCase {

        CHILD_FIELD_ON_TABLE_TYPE_RETURNING_RESULT_TYPE(
            "field on @table type returning a @record type with no directive → UnclassifiedField (ConstructorField not yet supported)",
            """
            type FilmDetails @record { rating: String }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.field("Film", "details")).isInstanceOf(UnclassifiedField.class)),

        QUERY_FIELD_RETURNING_UNCLASSIFIED_TYPE(
            "query field returning a type with no Graphitron directive → UnclassifiedField",
            """
            type Untyped { value: String }
            type Query { data: Untyped }
            """,
            schema -> assertThat(schema.field("Query", "data")).isInstanceOf(UnclassifiedField.class)),

        MUTATION_FIELD_WITHOUT_DIRECTIVE(
            "mutation field without @mutation or @service → UnclassifiedField",
            """
            type Film @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { doStuff: Film }
            """,
            schema -> assertThat(schema.field("Mutation", "doStuff")).isInstanceOf(UnclassifiedField.class)),

        CHILD_FIELD_ON_UNCLASSIFIED_PARENT_TYPE(
            "field on a type with no Graphitron directive is not classified (type excluded from schema)",
            """
            type Untyped { value: String }
            type Query { data: Untyped }
            """,
            schema -> assertThat(schema.field("Untyped", "value")).isNull());

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        UnclassifiedFieldCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(UnclassifiedFieldCase.class)
    void unclassifiedFieldClassification(UnclassifiedFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== Type directive mutual exclusivity =====
    // @table, @record, and @error are mutually exclusive — the builder produces UnclassifiedType
    // carrying the names of the conflicting directives in its reason.

    enum TypeDirectiveConflictCase {

        TABLE_AND_RECORD_CONFLICT(
            "@table and @record on the same type → UnclassifiedType with reason mentioning both",
            """
            type Film @table(name: "film") @record { title: String }
            type Query { film: Film }
            """,
            "Film", "@table", "@record"),

        TABLE_AND_ERROR_CONFLICT(
            "@table and @error on the same type → UnclassifiedType with reason mentioning both",
            """
            type Film @table(name: "film") @error(handlers: [{handler: GENERIC, className: "com.example.Ex"}]) { title: String }
            type Query { film: Film }
            """,
            "Film", "@table", "@error"),

        RECORD_AND_ERROR_CONFLICT(
            "@record and @error on the same type → UnclassifiedType with reason mentioning both",
            """
            type Hybrid @record @error(handlers: [{handler: GENERIC, className: "com.example.Ex"}]) { value: String }
            type Query { x: String }
            """,
            "Hybrid", "@record", "@error");

        final String typeName;
        final String sdl;
        final String[] conflictingDirectives;
        TypeDirectiveConflictCase(String description, String sdl, String typeName, String... conflictingDirectives) {
            this.sdl = sdl;
            this.typeName = typeName;
            this.conflictingDirectives = conflictingDirectives;
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(TypeDirectiveConflictCase.class)
    void typeDirectiveConflict(TypeDirectiveConflictCase tc) {
        var schema = build(tc.sdl);
        assertThat(schema.type(tc.typeName)).isInstanceOf(UnclassifiedType.class);
        assertThat(((UnclassifiedType) schema.type(tc.typeName)).reason())
            .contains(tc.conflictingDirectives);
    }

    // ===== Child field directive mutual exclusivity =====
    // @service, @externalField, @tableMethod, (@nodeId || @reference), @notGenerated, and
    // @multitableReference are mutually exclusive. @nodeId and @reference CAN be combined.
    // The builder produces UnclassifiedField with a reason naming the conflicting directives.

    enum ChildFieldDirectiveConflictCase {

        SERVICE_AND_EXTERNAL_FIELD_CONFLICT(
            "@service and @externalField → UnclassifiedField with reason naming both",
            """
            type Film @table(name: "film") {
                title: String @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"}) @externalField
            }
            type Query { film: Film }
            """,
            "Film", "title", "@service", "@externalField"),

        SERVICE_AND_TABLE_METHOD_CONFLICT(
            "@service and @tableMethod → UnclassifiedField with reason naming both",
            """
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                language: Language
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"})
                    @tableMethod(tableMethodReference: {className: "com.example.Foo", method: "get"})
            }
            type Query { film: Film }
            """,
            "Film", "language", "@service", "@tableMethod"),

        SERVICE_AND_NODE_ID_CONFLICT(
            "@service and @nodeId → UnclassifiedField with reason naming both",
            """
            type Film @table(name: "film") {
                id: String @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"}) @nodeId
            }
            type Query { film: Film }
            """,
            "Film", "id", "@service", "@nodeId"),

        EXTERNAL_FIELD_AND_TABLE_METHOD_CONFLICT(
            "@externalField and @tableMethod → UnclassifiedField with reason naming both",
            """
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                language: Language
                    @externalField
                    @tableMethod(tableMethodReference: {className: "com.example.Foo", method: "get"})
            }
            type Query { film: Film }
            """,
            "Film", "language", "@externalField", "@tableMethod"),

        NOT_GENERATED_AND_SERVICE_CONFLICT(
            "@notGenerated and @service → UnclassifiedField with reason naming both",
            """
            type Film @table(name: "film") {
                title: String @notGenerated @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"})
            }
            type Query { film: Film }
            """,
            "Film", "title", "@notGenerated", "@service"),

        MULTITABLE_REFERENCE_AND_SERVICE_CONFLICT(
            "@multitableReference and @service → UnclassifiedField with reason naming both",
            """
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                language: Language @multitableReference @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"})
            }
            type Query { film: Film }
            """,
            "Film", "language", "@multitableReference", "@service");

        final String sdl;
        final String parentType;
        final String fieldName;
        final String[] conflictingDirectives;
        ChildFieldDirectiveConflictCase(String description, String sdl,
                String parentType, String fieldName, String... conflictingDirectives) {
            this.sdl = sdl;
            this.parentType = parentType;
            this.fieldName = fieldName;
            this.conflictingDirectives = conflictingDirectives;
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ChildFieldDirectiveConflictCase.class)
    void childFieldDirectiveConflict(ChildFieldDirectiveConflictCase tc) {
        var schema = build(tc.sdl);
        assertThat(schema.field(tc.parentType, tc.fieldName)).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) schema.field(tc.parentType, tc.fieldName)).reason())
            .contains(tc.conflictingDirectives);
    }

    // ===== Query/mutation field directive mutual exclusivity =====
    // Query fields: @service, @lookupKey, @tableMethod are mutually exclusive.
    // Mutation fields: @service, @mutation are mutually exclusive.
    // The builder produces UnclassifiedField with a reason naming the conflicting directives.

    enum RootFieldDirectiveConflictCase {

        SERVICE_AND_LOOKUP_KEY_CONFLICT(
            "@service and @lookupKey on query → UnclassifiedField with reason naming both",
            """
            type Film @table(name: "film") { title: String }
            type Query {
                film(id: ID @lookupKey): Film
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"})
            }
            """,
            "Query", "film", "@service", "@lookupKey"),

        SERVICE_AND_TABLE_METHOD_CONFLICT(
            "@service and @tableMethod on query → UnclassifiedField with reason naming both",
            """
            type Film @table(name: "film") { title: String }
            type Query {
                film: Film
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"})
                    @tableMethod(tableMethodReference: {className: "com.example.Foo", method: "get"})
            }
            """,
            "Query", "film", "@service", "@tableMethod"),

        LOOKUP_KEY_AND_TABLE_METHOD_CONFLICT(
            "@lookupKey and @tableMethod on query → UnclassifiedField with reason naming both",
            """
            type Film @table(name: "film") { title: String }
            type Query {
                film(id: ID @lookupKey): Film
                    @tableMethod(tableMethodReference: {className: "com.example.Foo", method: "get"})
            }
            """,
            "Query", "film", "@lookupKey", "@tableMethod"),

        SERVICE_AND_MUTATION_CONFLICT(
            "@service and @mutation on mutation → UnclassifiedField with reason naming both",
            """
            type Film @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation {
                createFilm: Film
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "run"})
                    @mutation(typeName: INSERT)
            }
            """,
            "Mutation", "createFilm", "@service", "@mutation");

        final String sdl;
        final String parentType;
        final String fieldName;
        final String[] conflictingDirectives;
        RootFieldDirectiveConflictCase(String description, String sdl,
                String parentType, String fieldName, String... conflictingDirectives) {
            this.sdl = sdl;
            this.parentType = parentType;
            this.fieldName = fieldName;
            this.conflictingDirectives = conflictingDirectives;
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(RootFieldDirectiveConflictCase.class)
    void rootFieldDirectiveConflict(RootFieldDirectiveConflictCase tc) {
        var schema = build(tc.sdl);
        assertThat(schema.field(tc.parentType, tc.fieldName)).isInstanceOf(UnclassifiedField.class);
        assertThat(((UnclassifiedField) schema.field(tc.parentType, tc.fieldName)).reason())
            .contains(tc.conflictingDirectives);
    }

    @Test
    void serviceOnQueryWithTableReturnType_classifiesAsQueryServiceTableField() {
        // @service on a query returning a @table type is valid — @service drives classification.
        // Having a @table return type is not a conflicting directive; it is only a fallback
        // when no explicit directive is present.
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type Query {
                film: Film @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"})
            }
            """);
        assertThat(schema.field("Query", "film")).isInstanceOf(QueryField.QueryServiceTableField.class);
    }

    // ===== Helper =====

    /**
     * Parses {@code schemaText} together with the Graphitron directive definitions and
     * builds a {@link GraphitronSchema} via {@link GraphitronSchemaBuilder}.
     */
    private GraphitronSchema build(String schemaText) {
        String directivesContent = SchemaReadingHelper.fileAsString(
            java.nio.file.Paths.get("../../graphitron-common/src/main/resources/directives.graphqls"));
        TypeDefinitionRegistry registry = new SchemaParser().parse(directivesContent + "\n" + schemaText);
        return GraphitronSchemaBuilder.build(registry);
    }
}
