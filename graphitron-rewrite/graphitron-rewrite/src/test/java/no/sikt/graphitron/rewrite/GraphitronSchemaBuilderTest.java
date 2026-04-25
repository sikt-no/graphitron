package no.sikt.graphitron.rewrite;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.rewrite.BuildWarning;
import no.sikt.graphitron.rewrite.RejectionKind;
import no.sikt.graphitron.rewrite.model.ErrorHandlerType;
import no.sikt.graphitron.rewrite.model.ChildField;
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
import no.sikt.graphitron.rewrite.model.ChildField.RecordField;
import no.sikt.graphitron.rewrite.model.ChildField.RecordLookupTableField;
import no.sikt.graphitron.rewrite.model.ChildField.RecordTableField;
import no.sikt.graphitron.rewrite.model.ChildField.SplitLookupTableField;
import no.sikt.graphitron.rewrite.model.ChildField.SplitTableField;
import no.sikt.graphitron.rewrite.model.ChildField.TableField;
import no.sikt.graphitron.rewrite.model.ChildField.TableInterfaceField;
import no.sikt.graphitron.rewrite.model.ChildField.TableMethodField;
import no.sikt.graphitron.rewrite.model.ChildField.UnionField;
import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField.NotGeneratedField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ConditionFilter;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.SqlGeneratingField;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.GraphitronType.ConnectionType;
import no.sikt.graphitron.rewrite.model.GraphitronType.EdgeType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType;
import no.sikt.graphitron.rewrite.model.GraphitronType.InputType;
import no.sikt.graphitron.rewrite.model.GraphitronType.InterfaceType;
import no.sikt.graphitron.rewrite.model.GraphitronType.PageInfoType;
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
import no.sikt.graphitron.rewrite.model.GraphitronType.TableInputType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableInterfaceType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnionType;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.TableRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;
import java.util.function.Consumer;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Level 2 classification tests. Each section has an enum where every constant is one (sdl, assertion)
 * case; the parameterised test method iterates the whole truth table automatically.
 */
class GraphitronSchemaBuilderTest {

    // ===== ColumnField =====

    enum ColumnFieldCase implements ClassificationCase {
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
        @Override public Set<Class<?>> variants() { return Set.of(ColumnField.class); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ColumnFieldCase.class)
    void columnFieldClassification(ColumnFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== ColumnReferenceField =====

    enum ColumnReferenceFieldCase implements ClassificationCase {
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
                assertThat(fk.targetTable().tableName()).isEqualToIgnoringCase("address");
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
                assertThat(fk.targetTable().tableName()).isEqualToIgnoringCase("address");
                assertThat(ref.column().javaName()).isEqualTo("DISTRICT");
            }),

        CONDITION_PATH(
            "@reference with {condition: {className, method}} resolves to a ConditionJoin",
            """
            type Actor @table(name: "actor") { firstName: String }
            type Film @table(name: "film") {
              actor: Actor @reference(path: [{condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "join"}}])
            }
            type Query { film: Film }
            """,
            schema -> {
                var ref = (TableField) schema.field("Film", "actor");
                assertThat(ref.joinPath()).hasSize(1);
                assertThat(ref.joinPath().get(0)).isInstanceOf(JoinStep.ConditionJoin.class);
                var cj = (JoinStep.ConditionJoin) ref.joinPath().get(0);
                assertThat(cj.condition().methodName()).isEqualTo("join");
            }),

        CONDITION_PATH_UNKNOWN_CLASS(
            "@reference with {condition:} pointing to a missing class → UnclassifiedField",
            """
            type Actor @table(name: "actor") { firstName: String }
            type Film @table(name: "film") {
              actor: Actor @reference(path: [{condition: {className: "no.sikt.does.not.Exist", method: "join"}}])
            }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.field("Film", "actor")).isInstanceOf(UnclassifiedField.class));

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        ColumnReferenceFieldCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() { return Set.of(ColumnReferenceField.class); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ColumnReferenceFieldCase.class)
    void columnReferenceFieldClassification(ColumnReferenceFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== NotGeneratedField =====

    enum NotGeneratedFieldCase implements ClassificationCase {
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
        @Override public Set<Class<?>> variants() { return Set.of(NotGeneratedField.class); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(NotGeneratedFieldCase.class)
    void notGeneratedFieldClassification(NotGeneratedFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== MultitableReferenceField =====

    enum MultitableReferenceFieldCase implements ClassificationCase {
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
        @Override public Set<Class<?>> variants() { return Set.of(MultitableReferenceField.class); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(MultitableReferenceFieldCase.class)
    void multitableReferenceFieldClassification(MultitableReferenceFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== NodeIdField =====

    enum NodeIdFieldCase implements ClassificationCase {
        WITH_NODE_DIRECTIVE(
            "@nodeId on a type that also has @node — classified as NodeIdField with resolved key columns",
            """
            type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
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
        @Override public Set<Class<?>> variants() { return Set.of(NodeIdField.class); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(NodeIdFieldCase.class)
    void nodeIdFieldClassification(NodeIdFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== NodeIdReferenceField =====

    enum NodeIdReferenceFieldCase implements ClassificationCase {
        RESOLVED(
            "typeName pointing to a @node type with a single FK between tables → NodeIdReferenceField with a one-hop inferred joinPath",
            """
            type Country implements Node @table(name: "country") @node(keyColumns: ["country_id"]) {
              id: ID! @nodeId
            }
            type City @table(name: "city") {
              countryId: ID! @nodeId(typeName: "Country")
            }
            type Query { city: City }
            """,
            schema -> {
                var ref = (NodeIdReferenceField) schema.field("City", "countryId");
                assertThat(ref.typeName()).isEqualTo("Country");
                assertThat(ref.nodeKeyColumns()).isNotEmpty();
                assertThat(ref.joinPath()).hasSize(1);
                assertThat(ref.joinPath().get(0)).isInstanceOf(JoinStep.FkJoin.class);
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
            type Language implements Node @table(name: "language") @node(keyColumns: ["language_id"]) {
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
            }),

        IMPLICIT_REFERENCE_ZERO_FK(
            "no @reference on @nodeId with no direct FK between parent and target tables → UnclassifiedField",
            """
            type Actor implements Node @table(name: "actor") @node(keyColumns: ["actor_id"]) { id: ID! @nodeId }
            type Film @table(name: "film") { actorId: ID! @nodeId(typeName: "Actor") }
            type Query { film: Film }
            """,
            schema -> {
                var f = schema.field("Film", "actorId");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("no foreign key found")
                    .contains("'film'")
                    .contains("'actor'");
            }),

        IMPLICIT_REFERENCE_MULTIPLE_FK(
            "no @reference on @nodeId with multiple FKs between parent and target tables → UnclassifiedField",
            """
            type Language implements Node @table(name: "language") @node(keyColumns: ["language_id"]) { id: ID! @nodeId }
            type Film @table(name: "film") { languageId: ID! @nodeId(typeName: "Language") }
            type Query { film: Film }
            """,
            schema -> {
                var f = schema.field("Film", "languageId");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("multiple foreign keys found")
                    .contains("'film'")
                    .contains("'language'");
            });

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        NodeIdReferenceFieldCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() { return Set.of(NodeIdReferenceField.class); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(NodeIdReferenceFieldCase.class)
    void nodeIdReferenceFieldClassification(NodeIdReferenceFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== TableField / SplitTableField / SplitLookupTableField / LookupTableField =====

    /**
     * Child field on a {@code @table} parent returning a {@code @table}-mapped type. One case per
     * variant the builder can produce from this shape. Covers the
     * <em>Child Fields (on {@code @table} parent)</em> table in {@code code-generation-triggers.md}.
     */
    enum TableFieldCase implements ClassificationCase {
        SINGLE_RETURN_TYPE(
            "@table return type (default) → TableField (Single cardinality, one-hop inferred joinPath)",
            """
            type Country @table(name: "country") { name: String }
            type City @table(name: "city") { country: Country }
            type Query { city: City }
            """,
            schema -> {
                var tf = (TableField) schema.field("City", "country");
                assertThat(tf.returnType().wrapper()).isInstanceOf(FieldWrapper.Single.class);
                assertThat(tf.filters()).isEmpty();
                assertThat(tf.joinPath()).hasSize(1);
                assertThat(tf.joinPath().get(0)).isInstanceOf(JoinStep.FkJoin.class);
            }),

        LIST_RETURN_TYPE(
            "list @table return type → TableField (List cardinality)",
            """
            type Customer @table(name: "customer") { firstName: String }
            type Store @table(name: "store") { customers: [Customer!]! }
            type Query { store: Store }
            """,
            schema -> assertThat(((TableField) schema.field("Store", "customers")).returnType().wrapper())
                .isInstanceOf(FieldWrapper.List.class)),

        CONNECTION_STRUCTURE_REJECTED(
            "edges.node connection structure on inline TableField → UnclassifiedField (requires @splitQuery)",
            """
            type Customer @table(name: "customer") { firstName: String }
            type CustomerEdge { node: Customer cursor: String }
            type CustomerConnection { edges: [CustomerEdge] }
            type Store @table(name: "store") { customers: CustomerConnection }
            type Query { store: Store }
            """,
            schema -> {
                var f = schema.field("Store", "customers");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("@asConnection on inline (non-@splitQuery) TableField is not supported")
                    .contains("@splitQuery");
            }),

        AS_CONNECTION_REJECTED_NO_SPLIT(
            "@asConnection without @splitQuery on inline TableField → UnclassifiedField",
            """
            type Customer @table(name: "customer") { firstName: String }
            type Store @table(name: "store") { customers: [Customer!]! @asConnection @defaultOrder(primaryKey: true) }
            type Query { store: Store }
            """,
            schema -> {
                var f = schema.field("Store", "customers");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("@asConnection on inline (non-@splitQuery) TableField is not supported");
            }),

        AS_CONNECTION_SPLIT_CLASSIFIED(
            "@asConnection @splitQuery → SplitTableField with Connection wrapper "
            + "(per-parent pagination via ROW_NUMBER() envelope; plan-split-query-connection.md §1)",
            """
            type Customer @table(name: "customer") { firstName: String }
            type Store @table(name: "store") { customers: [Customer!]! @asConnection @splitQuery @defaultOrder(primaryKey: true) }
            type Query { store: Store }
            """,
            schema -> {
                var field = schema.field("Store", "customers");
                assertThat(field).isInstanceOf(ChildField.SplitTableField.class);
                assertThat(((ChildField.SplitTableField) field).returnType().wrapper())
                    .isInstanceOf(FieldWrapper.Connection.class);
            }),

        AS_CONNECTION_LOOKUP_REJECTED(
            "@asConnection + @lookupKey → UnclassifiedField: @lookupKey establishes a positional "
            + "input-list↔output-list correspondence, which pagination breaks. Invalid schema, not "
            + "a generator gap. Holds whether or not @splitQuery is also present.",
            """
            type Customer @table(name: "customer") { firstName: String }
            type Store @table(name: "store") {
                customersByKey(customer_id: [Int!]! @lookupKey): [Customer!]! @asConnection @splitQuery @defaultOrder(primaryKey: true)
            }
            type Query { store: Store }
            """,
            schema -> {
                var rejected = (UnclassifiedField) schema.field("Store", "customersByKey");
                assertThat(rejected).isInstanceOf(UnclassifiedField.class);
                assertThat(rejected.reason())
                    .contains("@asConnection on @lookupKey fields is invalid")
                    .contains("positional correspondence");
                // Ratchet: this rejection is a permanent schema-modeling error, not a generator
                // gap. INVALID_SCHEMA must not drift back to DEFERRED.
                assertThat(rejected.kind()).isEqualTo(RejectionKind.INVALID_SCHEMA);
            }),

        SPLIT_QUERY(
            "@splitQuery (no @lookupKey) on @table parent → SplitTableField",
            """
            type Customer @table(name: "customer") { firstName: String }
            type Store @table(name: "store") { customers: [Customer!]! @splitQuery }
            type Query { store: Store }
            """,
            schema -> assertThat(schema.field("Store", "customers")).isInstanceOf(SplitTableField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(SplitTableField.class); }
        },

        SPLIT_LOOKUP_TABLE_FIELD(
            "@splitQuery + @lookupKey on @table parent → SplitLookupTableField",
            """
            type Customer @table(name: "customer") { firstName: String }
            type Store @table(name: "store") {
                customers(customer_id: ID! @lookupKey): [Customer!]! @splitQuery
            }
            type Query { store: Store }
            """,
            schema -> assertThat(schema.field("Store", "customers")).isInstanceOf(SplitLookupTableField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(SplitLookupTableField.class); }
        },

        SPLIT_TABLE_SINGLE_CARDINALITY(
            "@splitQuery with single-cardinality parent-holds-FK reference → SplitTableField with FK-column BatchKey",
            """
            type Address @table(name: "address") { address: String }
            type Customer @table(name: "customer") {
                address: Address @splitQuery @reference(path: [{key: "customer_address_id_fkey"}])
            }
            type Query { customer: Customer }
            """,
            schema -> {
                var f = (SplitTableField) schema.field("Customer", "address");
                assertThat(f.returnType().wrapper()).isInstanceOf(FieldWrapper.Single.class);
                assertThat(f.joinPath()).hasSize(1);
                var rk = (BatchKey.RowKeyed) f.batchKey();
                assertThat(rk.keyColumns()).extracting(ColumnRef::sqlName)
                    .containsExactly("address_id");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(SplitTableField.class); }
        },

        IMPLICIT_REFERENCE_SPLIT_TABLE_SINGLE_CARDINALITY(
            "no @reference on single-cardinality @splitQuery with single FK → SplitTableField, parent-FK BatchKey",
            """
            type Address @table(name: "address") { address: String }
            type Customer @table(name: "customer") { address: Address @splitQuery }
            type Query { customer: Customer }
            """,
            schema -> {
                var f = (SplitTableField) schema.field("Customer", "address");
                assertThat(f.joinPath()).hasSize(1);
                var rk = (BatchKey.RowKeyed) f.batchKey();
                assertThat(rk.keyColumns()).extracting(ColumnRef::sqlName)
                    .containsExactly("address_id");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(SplitTableField.class); }
        },

        SPLIT_LOOKUP_TABLE_SINGLE_CARDINALITY_REJECTED(
            "single-cardinality @splitQuery @lookupKey → UnclassifiedField (§1b rejection)",
            """
            type Customer @table(name: "customer") { firstName: String }
            type Store @table(name: "store") {
                customer(customer_id: ID! @lookupKey): Customer @splitQuery
            }
            type Query { store: Store }
            """,
            schema -> {
                assertThat(schema.field("Store", "customer")).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) schema.field("Store", "customer")).reason())
                    .contains("Single-cardinality @splitQuery @lookupKey is not supported");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(SplitLookupTableField.class); }
        },

        SPLIT_TABLE_MULTI_HOP_SINGLE_CARDINALITY_REJECTED(
            "single-cardinality @splitQuery with multi-hop path → UnclassifiedField (§1c rejection)",
            """
            type Address @table(name: "address") { address: String }
            type Customer @table(name: "customer") {
                storeAddress: Address @splitQuery @reference(path: [{key: "customer_store_id_fkey"}, {key: "store_address_id_fkey"}])
            }
            type Query { customer: Customer }
            """,
            schema -> {
                assertThat(schema.field("Customer", "storeAddress")).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) schema.field("Customer", "storeAddress")).reason())
                    .contains("Single-cardinality @splitQuery requires a single-hop parent-holds-FK reference path");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(SplitTableField.class); }
        },

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

        IMPLICIT_REFERENCE_SPLIT_TABLE(
            "no @reference on @splitQuery with single FK between parent and target tables → SplitTableField with one inferred FkJoin",
            """
            type Customer @table(name: "customer") { firstName: String }
            type Store @table(name: "store") { customers: [Customer!]! @splitQuery }
            type Query { store: Store }
            """,
            schema -> {
                var f = (SplitTableField) schema.field("Store", "customers");
                assertThat(f.joinPath()).hasSize(1);
                assertThat(f.joinPath().get(0)).isInstanceOf(JoinStep.FkJoin.class);
            }),

        IMPLICIT_REFERENCE_SPLIT_LOOKUP_TABLE(
            "no @reference on @splitQuery + @lookupKey with single FK → SplitLookupTableField with one inferred FkJoin",
            """
            type Customer @table(name: "customer") { firstName: String }
            type Store @table(name: "store") {
                customers(customer_id: ID! @lookupKey): [Customer!]! @splitQuery
            }
            type Query { store: Store }
            """,
            schema -> {
                var f = (SplitLookupTableField) schema.field("Store", "customers");
                assertThat(f.joinPath()).hasSize(1);
                assertThat(f.joinPath().get(0)).isInstanceOf(JoinStep.FkJoin.class);
            }),

        IMPLICIT_REFERENCE_ZERO_FK(
            "no @reference on @splitQuery with no direct FK between parent and target → UnclassifiedField",
            """
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") { actors: [Actor!]! @splitQuery }
            type Query { film: Film }
            """,
            schema -> {
                var f = schema.field("Film", "actors");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("no foreign key found")
                    .contains("'film'")
                    .contains("'actor'");
            }),

        IMPLICIT_REFERENCE_MULTIPLE_FK(
            "no @reference on @splitQuery with multiple FKs between parent and target → UnclassifiedField",
            """
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") { languages: [Language!]! @splitQuery }
            type Query { film: Film }
            """,
            schema -> {
                var f = schema.field("Film", "languages");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("multiple foreign keys found")
                    .contains("'film'")
                    .contains("'language'");
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
            "@reference without @condition → TableField with empty filters (condition support deferred to P3)",
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
            type FilmActor @table(name: "film_actor") {
                actors: [Actor!]! @defaultOrder(index: "idx_actor_last_name")
            }
            type Query { filmActor: FilmActor }
            """,
            schema -> {
                var order = (OrderBySpec.Fixed) ((TableField) schema.field("FilmActor", "actors")).orderBy();
                assertThat(order).isNotNull();
                assertThat(order.direction()).isEqualTo("ASC");
                assertThat(order.columns()).hasSize(1);
                assertThat(order.columns().get(0).column().sqlName()).isEqualToIgnoringCase("last_name");
            }),

        DEFAULT_ORDER_PRIMARY_KEY(
            "@defaultOrder(primaryKey: true) resolves PK columns — actor_id",
            """
            type Actor @table(name: "actor") { name: String }
            type FilmActor @table(name: "film_actor") {
                actors: [Actor!]! @defaultOrder(primaryKey: true)
            }
            type Query { filmActor: FilmActor }
            """,
            schema -> {
                var order = (OrderBySpec.Fixed) ((TableField) schema.field("FilmActor", "actors")).orderBy();
                assertThat(order).isNotNull();
                assertThat(order.columns()).hasSize(1);
                assertThat(order.columns().get(0).column().sqlName()).isEqualToIgnoringCase("actor_id");
            }),

        DEFAULT_ORDER_FIELDS(
            "@defaultOrder(fields:) resolves column names and preserves collations",
            """
            type Actor @table(name: "actor") { name: String }
            type FilmActor @table(name: "film_actor") {
                actors: [Actor!]! @defaultOrder(fields: [{name: "last_name", collate: "C"}, {name: "first_name"}])
            }
            type Query { filmActor: FilmActor }
            """,
            schema -> {
                var order = (OrderBySpec.Fixed) ((TableField) schema.field("FilmActor", "actors")).orderBy();
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
            type FilmActor @table(name: "film_actor") {
                actors: [Actor!]! @defaultOrder(primaryKey: true, direction: DESC)
            }
            type Query { filmActor: FilmActor }
            """,
            schema -> {
                var order = (OrderBySpec.Fixed) ((TableField) schema.field("FilmActor", "actors")).orderBy();
                assertThat(order.direction()).isEqualTo("DESC");
            }),

        CONNECTION_WITH_DEFAULT_ORDER_INDEX_SPLIT_CLASSIFIED(
            "ActorConnection + @splitQuery → SplitTableField with Connection wrapper "
            + "(structural connection detection + @defaultOrder by index; plan-split-query-connection.md §1)",
            """
            type Actor @table(name: "actor") { name: String }
            type ActorEdge { node: Actor cursor: String }
            type ActorConnection { edges: [ActorEdge] }
            type FilmActor @table(name: "film_actor") {
                actors: ActorConnection @splitQuery @defaultOrder(index: "idx_actor_last_name")
            }
            type Query { filmActor: FilmActor }
            """,
            schema -> {
                var field = schema.field("FilmActor", "actors");
                assertThat(field).isInstanceOf(ChildField.SplitTableField.class);
                assertThat(((ChildField.SplitTableField) field).returnType().wrapper())
                    .isInstanceOf(FieldWrapper.Connection.class);
            }),

        NO_DEFAULT_ORDER_PK_FALLBACK(
            "no @defaultOrder on PK table → TableField with PK columns auto-filled as Fixed order",
            """
            type Actor @table(name: "actor") { name: String }
            type FilmActor @table(name: "film_actor") {
                actors: [Actor!]!
            }
            type Query { filmActor: FilmActor }
            """,
            schema -> {
                var order = (OrderBySpec.Fixed) ((TableField) schema.field("FilmActor", "actors")).orderBy();
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
        @Override public Set<Class<?>> variants() { return Set.of(TableField.class); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(TableFieldCase.class)
    void tableFieldClassification(TableFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== TableMethodField =====

    enum TableMethodFieldCase implements ClassificationCase {
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
        @Override public Set<Class<?>> variants() { return Set.of(TableMethodField.class); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(TableMethodFieldCase.class)
    void tableMethodFieldClassification(TableMethodFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== NestingField =====

    enum NestingFieldCase implements ClassificationCase {
        PLAIN_OBJECT_TYPE(
            "a field returning a plain object type (no @table) on a @table parent → NestingField",
            """
            type FilmDetails { title: String description: String }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.field("Film", "details")).isInstanceOf(NestingField.class)),

        LIST_OF_PLAIN_OBJECT_TYPE(
            "a list-wrapped plain object type on a @table parent → NestingField (validator rejects at list cardinality)",
            """
            type Tag { title: String }
            type Film @table(name: "film") { tags: [Tag!]! }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.field("Film", "tags")).isInstanceOf(NestingField.class)),

        NESTED_SCALAR_RESOLVES_TO_PARENT_COLUMN(
            "nested scalar resolves to the outer parent's column",
            """
            type FilmDetails { title: String }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var nf = (NestingField) schema.field("Film", "details");
                assertThat(nf.nestedFields()).singleElement()
                    .isInstanceOfSatisfying(ColumnField.class, cf -> {
                        assertThat(cf.name()).isEqualTo("title");
                        assertThat(cf.column().javaName()).isEqualTo("TITLE");
                    });
            }),

        NESTED_FIELD_NAME_REMAP(
            "@field(name:) on a nested scalar remaps the column",
            """
            type FilmDetails { alias: String @field(name: "title") }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var nf = (NestingField) schema.field("Film", "details");
                assertThat(nf.nestedFields()).singleElement()
                    .isInstanceOfSatisfying(ColumnField.class, cf -> {
                        assertThat(cf.name()).isEqualTo("alias");
                        assertThat(cf.column().javaName()).isEqualTo("TITLE");
                    });
            }),

        NESTED_UNMATCHED_COLUMN_ROLLS_UP(
            "unmatched nested scalar rolls up into the outer NestingField as UnclassifiedField",
            """
            type FilmDetails { unmappable: String }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.field("Film", "details"))
                .isInstanceOfSatisfying(UnclassifiedField.class, uf ->
                    assertThat(uf.reason()).contains("FilmDetails", "unmappable"))),

        MULTI_LEVEL_NESTING(
            "multi-level nesting produces a recursive NestingField.nestedFields() entry",
            """
            type FilmMeta { releaseYear: Int @field(name: "release_year") }
            type FilmDetails { title: String meta: FilmMeta }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var outer = (NestingField) schema.field("Film", "details");
                assertThat(outer.nestedFields()).hasSize(2);
                assertThat(outer.nestedFields().get(1)).isInstanceOfSatisfying(NestingField.class, inner -> {
                    assertThat(inner.name()).isEqualTo("meta");
                    assertThat(inner.nestedFields()).singleElement()
                        .isInstanceOfSatisfying(ColumnField.class, cf -> {
                            assertThat(cf.column().javaName()).isEqualTo("RELEASE_YEAR");
                        });
                });
            }),

        SELF_REFERENTIAL_CYCLE_DETECTED(
            "self-referential nested type → UnclassifiedField with circular-reference message",
            """
            type FilmDetails { title: String nested: FilmDetails }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.field("Film", "details"))
                .isInstanceOfSatisfying(UnclassifiedField.class, uf ->
                    assertThat(uf.reason()).contains("circular type reference", "FilmDetails"))),

        SHARED_NESTED_TYPE_ACROSS_PARENTS_COMPATIBLE(
            "two @table parents referencing the same nested type classify independently",
            """
            type FilmDetails { title: String }
            type Film @table(name: "film") { details: FilmDetails }
            type FilmList @table(name: "film_list") { details: FilmDetails }
            type Query { film: Film films: [FilmList] }
            """,
            schema -> {
                assertThat(schema.field("Film", "details")).isInstanceOf(NestingField.class);
                assertThat(schema.field("FilmList", "details")).isInstanceOf(NestingField.class);
            });

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        NestingFieldCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() { return Set.of(NestingField.class); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(NestingFieldCase.class)
    void nestingFieldClassification(NestingFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    @Test
    void nestingField_splitTableFieldClassifiedAsNestedSplitTableField() {
        var schema = build("""
            type Actor @table(name: "actor") { name: String }
            type FilmInfo {
                cast: [Actor!]! @splitQuery
                    @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
            }
            type Film @table(name: "film") { info: FilmInfo }
            type Query { film: Film }
            """);
        var infoField = (NestingField) schema.field("Film", "info");
        var castField = infoField.nestedFields().stream()
            .filter(f -> f.name().equals("cast"))
            .findFirst().orElseThrow();
        assertThat(castField).isInstanceOf(SplitTableField.class);
        var stf = (SplitTableField) castField;
        assertThat(stf.parentTypeName()).isEqualTo("FilmInfo");
        assertThat(stf.batchKey()).isInstanceOf(BatchKey.RowKeyed.class);
        var rk = (BatchKey.RowKeyed) stf.batchKey();
        assertThat(rk.keyColumns()).extracting(ColumnRef::javaName).containsExactly("FILM_ID");
    }

    // ===== ServiceTableField / ServiceRecordField =====

    enum ServiceFieldCase implements ClassificationCase {
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
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(ServiceTableField.class); }
        };

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        ServiceFieldCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() { return Set.of(ServiceRecordField.class); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ServiceFieldCase.class)
    void serviceFieldClassification(ServiceFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== ComputedField =====

    enum ComputedFieldCase implements ClassificationCase {
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
        @Override public Set<Class<?>> variants() { return Set.of(ComputedField.class); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ComputedFieldCase.class)
    void computedFieldClassification(ComputedFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== TableInterfaceField / InterfaceField / UnionField =====

    enum InterfaceUnionFieldCase implements ClassificationCase {
        TABLE_INTERFACE_FIELD(
            "field returning a @table+@discriminate interface → TableInterfaceField",
            """
            interface MediaItem @table(name: "film") @discriminate(on: "kind") { title: String }
            type Film implements MediaItem @table(name: "film") @discriminator(value: "film") { title: String }
            type Inventory @table(name: "inventory") { media: MediaItem }
            type Query { inventory: Inventory }
            """,
            schema -> assertThat(schema.field("Inventory", "media")).isInstanceOf(TableInterfaceField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(TableInterfaceField.class); }
        },

        INTERFACE_FIELD(
            "field returning a plain interface (no @table) → InterfaceField",
            """
            interface Named { name: String }
            type Language implements Named @table(name: "language") { name: String }
            type Film @table(name: "film") { language: Named }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.field("Film", "language")).isInstanceOf(InterfaceField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(InterfaceField.class); }
        },

        INTERFACE_WITH_NESTING_IMPLEMENTORS(
            "interface whose implementing types are nesting types (no @table) → InterfaceType with Unbound participants, no error",
            """
            interface Datoperiode { fraDato: String tilDato: String }
            type EmnerolleGyldighetsperiode implements Datoperiode {
                fraDato: String @field(name: "DATO_FRA")
                tilDato: String @field(name: "DATO_TIL")
            }
            type KlasserolleGyldighetsperiode implements Datoperiode {
                fraDato: String @field(name: "DATO_FRA")
                tilDato: String @field(name: "DATO_TIL")
            }
            type Film @table(name: "film") { title: String }
            type Query { film: Film }
            """,
            schema -> {
                var it = (InterfaceType) schema.type("Datoperiode");
                assertThat(it.participants())
                    .extracting(p -> p.typeName())
                    .containsExactlyInAnyOrder("EmnerolleGyldighetsperiode", "KlasserolleGyldighetsperiode");
                assertThat(it.participants()).allMatch(p -> p instanceof ParticipantRef.Unbound);
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(InterfaceType.class); }
        },

        UNION_FIELD(
            "field returning a union → UnionField",
            """
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") { title: String }
            union MediaItem = Language | Film
            type Actor @table(name: "actor") { media: MediaItem }
            type Query { actor: Actor }
            """,
            schema -> assertThat(schema.field("Actor", "media")).isInstanceOf(UnionField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(UnionField.class); }
        },

        UNION_WITH_NESTING_MEMBER(
            "union with a nesting-type member (no @table) → union classified as UnclassifiedType",
            """
            type Film @table(name: "film") { title: String }
            type DatePeriod { fraDato: String @field(name: "DATO_FRA") }
            union MediaOrPeriod = Film | DatePeriod
            type Query { film: Film }
            """,
            schema -> assertThat(schema.type("MediaOrPeriod")).isInstanceOf(UnclassifiedType.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(); }
        },

        TABLE_INTERFACE_TYPE(
            "@table+@discriminate interface → TableInterfaceType with discriminator and participants",
            """
            interface MediaItem @table(name: "film") @discriminate(on: "kind") { title: String }
            type Film implements MediaItem @table(name: "film") @discriminator(value: "film") { title: String }
            type Query { film: Film }
            """,
            schema -> {
                var t = (TableInterfaceType) schema.type("MediaItem");
                assertThat(t.discriminatorColumn()).isEqualTo("kind");
                assertThat(t.participants()).isNotEmpty();
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(TableInterfaceType.class); }
        },

        UNION_TYPE(
            "plain union of @table types → UnionType with participants",
            """
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") { title: String }
            union MediaItem = Language | Film
            type Query { film: Film }
            """,
            schema -> {
                var t = (UnionType) schema.type("MediaItem");
                assertThat(t.participants()).hasSize(2);
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnionType.class); }
        };

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        InterfaceUnionFieldCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() { return Set.of(); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(InterfaceUnionFieldCase.class)
    void interfaceUnionFieldClassification(InterfaceUnionFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== Fields on @record parents =====

    /**
     * Child field on a {@code @record} parent. One case per variant the builder can produce from
     * this shape. Covers the <em>Child Fields (on {@code @record} parent)</em> table in
     * {@code code-generation-triggers.md}.
     */
    enum NonTableParentCase implements ClassificationCase {
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
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(PropertyField.class); }
        },

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
            schema -> assertThat(schema.field("FilmDetails", "rating")).isInstanceOf(ServiceRecordField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(ServiceRecordField.class); }
        },

        RECORD_TABLE_FIELD(
            "@record parent (typed POJO) + @table return type (no @lookupKey) → RecordTableField",
            """
            type Language @table(name: "language") { name: String }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
              language: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.field("FilmDetails", "language")).isInstanceOf(RecordTableField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(RecordTableField.class); }
        },

        RECORD_LOOKUP_TABLE_FIELD(
            "@record parent (typed POJO) + @table return type + @lookupKey → RecordLookupTableField with populated BatchKey",
            """
            type Language @table(name: "language") { name: String }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
              language(language_id: ID! @lookupKey): Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var f = (RecordLookupTableField) schema.field("FilmDetails", "language");
                assertThat(f.batchKey()).isInstanceOf(no.sikt.graphitron.rewrite.model.BatchKey.RowKeyed.class);
                assertThat(((no.sikt.graphitron.rewrite.model.BatchKey.RowKeyed) f.batchKey()).keyColumns()).isNotEmpty();
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(RecordLookupTableField.class); }
        },

        IMPLICIT_REFERENCE_RECORD_TABLE(
            "JooqTableRecordType @record parent + @table return with single FK → RecordTableField with one inferred FkJoin",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord"}) {
              inventories: [Inventory!]!
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var f = (RecordTableField) schema.field("FilmDetails", "inventories");
                assertThat(f.joinPath()).hasSize(1);
                assertThat(f.joinPath().get(0)).isInstanceOf(JoinStep.FkJoin.class);
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(RecordTableField.class); }
        },

        IMPLICIT_REFERENCE_RECORD_LOOKUP_TABLE(
            "JooqTableRecordType @record parent + @table return + @lookupKey with single FK → RecordLookupTableField with one inferred FkJoin",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord"}) {
              inventories(inventory_id: [Int!] @lookupKey): [Inventory!]!
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var f = (RecordLookupTableField) schema.field("FilmDetails", "inventories");
                assertThat(f.joinPath()).hasSize(1);
                assertThat(f.joinPath().get(0)).isInstanceOf(JoinStep.FkJoin.class);
                assertThat(f.batchKey()).isInstanceOf(no.sikt.graphitron.rewrite.model.BatchKey.RowKeyed.class);
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(RecordLookupTableField.class); }
        },

        RECORD_FIELD(
            "@record parent + non-table object return type → RecordField",
            """
            type FilmStats @record { count: Int }
            type FilmDetails @record { stats: FilmStats }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.field("FilmDetails", "stats")).isInstanceOf(RecordField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(RecordField.class); }
        },

        SERVICE_TABLE_FIELD_ON_RECORD_PARENT(
            "@record parent + @service + @table return type → ServiceTableField",
            """
            type Language @table(name: "language") { name: String }
            type FilmDetails @record { language: Language @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"}) }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.field("FilmDetails", "language")).isInstanceOf(ServiceTableField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(ServiceTableField.class); }
        },

        CONSTRUCTOR_FIELD(
            "@table parent + @record child type → ConstructorField",
            """
            type FilmDetails @record { rating: String }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.field("Film", "details")).isInstanceOf(ChildField.ConstructorField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(ChildField.ConstructorField.class); }
        };

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        NonTableParentCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() { return Set.of(); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(NonTableParentCase.class)
    void nonTableParentFieldClassification(NonTableParentCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== ResultType backing-class classification =====

    enum ResultTypeCase implements ClassificationCase {
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
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(JavaRecordType.class); }
        },

        JOOQ_TABLE_RECORD_CLASS(
            "@record with jOOQ TableRecord class → JooqTableRecordType with fqClassName and resolved table",
            """
            type FilmDetails @record(record: {className: "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord"}) { id: ID }
            type Query { foo: String }
            """,
            schema -> {
                var t = (JooqTableRecordType) schema.type("FilmDetails");
                assertThat(t.fqClassName()).isEqualTo("no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord");
                assertThat(t.table()).isNotNull();
                assertThat(t.table().tableName()).isEqualTo("film");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(JooqTableRecordType.class); }
        },

        UNKNOWN_CLASS(
            "@record with unresolvable class → UnclassifiedType with explanation",
            """
            type FilmDetails @record(record: {className: "com.example.nonexistent.Missing"}) { id: ID }
            type Query { foo: String }
            """,
            schema -> {
                var t = (UnclassifiedType) schema.type("FilmDetails");
                assertThat(t.reason()).contains("com.example.nonexistent.Missing").contains("could not be loaded");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(); }
        };

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        ResultTypeCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() { return Set.of(PojoResultType.class); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ResultTypeCase.class)
    void resultTypeBackingClassClassification(ResultTypeCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== P4: Field arguments =====

    enum ArgumentParsingCase implements ClassificationCase {
        TABLE_FIELD_NO_ARGS(
            "TableField with no arguments — empty filters list",
            """
            type Actor @table(name: "actor") { name: String }
            type FilmActor @table(name: "film_actor") { actors: [Actor!]! }
            type Query { filmActor: FilmActor }
            """,
            schema -> {
                var f = (no.sikt.graphitron.rewrite.model.ChildField.TableField) schema.field("FilmActor", "actors");
                assertThat(f.filters()).isEmpty();
            }),

        TABLE_FIELD_WITH_ARGS(
            "TableField with two column arguments — one GeneratedConditionFilter with two BodyParams",
            """
            type Actor @table(name: "actor") { name: String }
            type FilmActor @table(name: "film_actor") {
                actors(actor_id: ID!, first_name: [String!]): [Actor!]!
            }
            type Query { filmActor: FilmActor }
            """,
            schema -> {
                var f = (no.sikt.graphitron.rewrite.model.ChildField.TableField) schema.field("FilmActor", "actors");
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
            "@lookupKey on a child-field argument (no @splitQuery) — field classified as LookupTableField; key flows through LookupMapping",
            """
            type Actor @table(name: "actor") { name: String }
            type FilmActor @table(name: "film_actor") {
                actors(actor_id: [Int!]! @lookupKey): [Actor!]!
            }
            type Query { filmActor: FilmActor }
            """,
            schema -> {
                var f = (no.sikt.graphitron.rewrite.model.ChildField.LookupTableField) schema.field("FilmActor", "actors");
                // @lookupKey args are emitted via VALUES+JOIN from LookupMapping, not as filters.
                assertThat(f.filters()).isEmpty();
                assertThat(((no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) f.lookupMapping()).columns()).hasSize(1);
                assertThat(((no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) f.lookupMapping()).columns().get(0).argName()).isEqualTo("actor_id");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(LookupTableField.class); }
        },

        TABLE_FIELD_ORDER_BY_ARG(
            "@orderBy arg with valid input type → OrderBySpec.Argument on orderBy(); filters empty",
            """
            enum ActorOrderField { FIRST_NAME @order(index: "IDX_ACTOR_LAST_NAME") }
            enum Direction { ASC DESC }
            input ActorOrder { sortField: ActorOrderField! direction: Direction! }
            type Actor @table(name: "actor") { name: String }
            type FilmActor @table(name: "film_actor") {
                actors(order: ActorOrder @orderBy): [Actor!]!
            }
            type Query { filmActor: FilmActor }
            """,
            schema -> {
                var f = (no.sikt.graphitron.rewrite.model.ChildField.TableField) schema.field("FilmActor", "actors");
                assertThat(f.filters()).isEmpty();
                assertThat(f.orderBy()).isInstanceOf(OrderBySpec.Argument.class);
                var orderBy = (OrderBySpec.Argument) f.orderBy();
                assertThat(orderBy.typeName()).isEqualTo("ActorOrder");
                assertThat(orderBy.namedOrders()).hasSize(1);
                assertThat(orderBy.namedOrders().get(0).name()).isEqualTo("FIRST_NAME");
            }),

        TABLE_FIELD_ORDER_BY_LEGACY_INDEX(
            "@orderBy with deprecated @index on sort enum value → same OrderBySpec.Argument as @order(index:)",
            """
            enum ActorOrderField { FIRST_NAME @index(name: "IDX_ACTOR_LAST_NAME") }
            enum Direction { ASC DESC }
            input ActorOrder { sortField: ActorOrderField! direction: Direction! }
            type Actor @table(name: "actor") { name: String }
            type FilmActor @table(name: "film_actor") {
                actors(order: ActorOrder @orderBy): [Actor!]!
            }
            type Query { filmActor: FilmActor }
            """,
            schema -> {
                var f = (no.sikt.graphitron.rewrite.model.ChildField.TableField) schema.field("FilmActor", "actors");
                assertThat(f.orderBy()).isInstanceOf(OrderBySpec.Argument.class);
                var orderBy = (OrderBySpec.Argument) f.orderBy();
                assertThat(orderBy.namedOrders()).hasSize(1);
                assertThat(orderBy.namedOrders().get(0).name()).isEqualTo("FIRST_NAME");
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

        SERVICE_FIELD_DSL_CONTEXT_PARAM(
            "@service method with DSLContext parameter — reflected as ParamSource.DslContext, field not UnclassifiedField",
            """
            type Film @table(name: "film") {
                rating: String @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getWithDsl"})
            }
            type Query { film: Film }
            """,
            schema -> {
                var f = (no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField) schema.field("Film", "rating");
                assertThat(f.method().params())
                    .filteredOn(p -> p instanceof no.sikt.graphitron.rewrite.model.MethodRef.Param.Typed t
                        && t.source() instanceof no.sikt.graphitron.rewrite.model.ParamSource.DslContext)
                    .hasSize(1);
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
            }),

        // ===== @condition directive on fields and arguments (argres step 4) =====
        // These verify the four-state projection table in docs/argument-resolution.md. The stub
        // methods live in TestConditionStub (fieldCondition, argCondition, argConditionWithContext).

        FIELD_CONDITION_ADDITIVE(
            "field-level @condition without override — filters contain implicit condition AND ConditionFilter",
            """
            type Language @table(name: "language") { name: String }
            type Query {
                languages(cityNames: String @field(name: "name"), countryId: String @field(name: "name")):
                    [Language!]! @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "fieldCondition"})
            }
            """,
            schema -> {
                var f = (QueryField.QueryTableField) schema.field("Query", "languages");
                assertThat(f.filters()).hasSize(2);
                assertThat(f.filters().get(0)).isInstanceOf(GeneratedConditionFilter.class);
                assertThat(f.filters().get(1)).isInstanceOf(ConditionFilter.class);
                var cond = (ConditionFilter) f.filters().get(1);
                assertThat(cond.methodName()).isEqualTo("fieldCondition");
            }),

        FIELD_CONDITION_OVERRIDE_SUPPRESSES_AUTO(
            "field-level @condition(override: true) — filters contain ONLY the field-level ConditionFilter",
            """
            type Language @table(name: "language") { name: String }
            type Query {
                languages(cityNames: String @field(name: "name"), countryId: String @field(name: "name")):
                    [Language!]! @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "fieldCondition"}, override: true)
            }
            """,
            schema -> {
                var f = (QueryField.QueryTableField) schema.field("Query", "languages");
                assertThat(f.filters()).hasSize(1);
                assertThat(f.filters().get(0)).isInstanceOf(ConditionFilter.class);
            }),

        ARG_CONDITION_ADDITIVE(
            "arg-level @condition without override — implicit condition for that arg AND ConditionFilter are BOTH emitted",
            """
            type Language @table(name: "language") { name: String }
            type Query {
                languages(cityNames: String @field(name: "name")
                    @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "argCondition"})):
                    [Language!]!
            }
            """,
            schema -> {
                var f = (QueryField.QueryTableField) schema.field("Query", "languages");
                assertThat(f.filters()).hasSize(2);
                assertThat(f.filters().get(0)).isInstanceOf(GeneratedConditionFilter.class);
                assertThat(((GeneratedConditionFilter) f.filters().get(0)).bodyParams().get(0).name())
                    .isEqualTo("cityNames");
                assertThat(f.filters().get(1)).isInstanceOf(ConditionFilter.class);
                assertThat(((ConditionFilter) f.filters().get(1)).methodName()).isEqualTo("argCondition");
            }),

        ARG_CONDITION_OVERRIDE_SUPPRESSES_THIS_ARG(
            "arg-level @condition(override: true) — that arg's auto is suppressed; other args' autos remain",
            """
            type Language @table(name: "language") { name: String }
            type Query {
                languages(
                    cityNames: String @field(name: "name")
                        @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "argCondition"}, override: true),
                    countryId: String @field(name: "name")
                ): [Language!]!
            }
            """,
            schema -> {
                var f = (QueryField.QueryTableField) schema.field("Query", "languages");
                assertThat(f.filters()).hasSize(2);
                var gcf = (GeneratedConditionFilter) f.filters().get(0);
                assertThat(gcf.bodyParams()).extracting(BodyParam::name).containsExactly("countryId");
                assertThat(f.filters().get(1)).isInstanceOf(ConditionFilter.class);
            }),

        QUERY_LOOKUP_TABLE_FIELD_MAPPING(
            "QueryLookupTableField populates LookupMapping with one LookupColumn per @lookupKey arg",
            """
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query {
                filmById(film_id: [ID] @lookupKey): [Film!]!
            }
            """,
            schema -> {
                var f = (QueryField.QueryLookupTableField) schema.field("Query", "filmById");
                assertThat(f.lookupMapping().targetTable().tableName()).isEqualTo("film");
                assertThat(((no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) f.lookupMapping()).columns())
                    .hasSize(1)
                    .extracting(no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping.LookupColumn::argName)
                    .containsExactly("film_id");
                assertThat(((no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) f.lookupMapping()).columns().get(0).targetColumn().sqlName()).isEqualTo("film_id");
                assertThat(((no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) f.lookupMapping()).columns().get(0).list()).isTrue();
            }),

        ARG_CONDITION_CONTEXT_ARGS(
            "arg-level @condition with contextArguments — context param reflected into the ConditionFilter",
            """
            type Language @table(name: "language") { name: String }
            type Query {
                languages(cityNames: String @field(name: "name")
                    @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "argConditionWithContext"}, contextArguments: ["tenantId"])):
                    [Language!]!
            }
            """,
            schema -> {
                var f = (QueryField.QueryTableField) schema.field("Query", "languages");
                var cond = (ConditionFilter) f.filters().get(1);
                assertThat(cond.params())
                    .filteredOn(p -> p instanceof no.sikt.graphitron.rewrite.model.MethodRef.Param.Typed t
                        && t.source() instanceof no.sikt.graphitron.rewrite.model.ParamSource.Context)
                    .extracting(p -> ((no.sikt.graphitron.rewrite.model.MethodRef.Param.Typed) p).name())
                    .containsExactly("tenantId");
            }),

        // ===== UnboundArg / UnclassifiedArg error surfacing (argres step 10) =====
        // classifyArguments emits UnboundArg when a scalar arg can't resolve to a column and
        // UnclassifiedArg for other structural failures. projectFilters promotes both into
        // per-argument errors with the "argument 'X': <reason>" format; the owning field becomes
        // UnclassifiedField. These tests lock the contract in place.

        UNBOUND_ARG_COLUMN_NOT_IN_TABLE(
            "scalar arg bound to a non-existent column — UnclassifiedField with candidate hint in the reason",
            """
            type Film @table(name: "film") {
                filmId: Int! @field(name: "film_id")
            }
            type Query {
                films(tytle: String @field(name: "tytle")): [Film!]!
            }
            """,
            schema -> {
                var f = (no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField) schema.field("Query", "films");
                assertThat(f.reason())
                    .contains("argument 'tytle'")
                    .contains("could not be resolved in table 'film'")
                    .contains("did you mean");
                // Ratchet: typo-class rejections are AUTHOR_ERROR (typo-fixable). Must not drift
                // into INVALID_SCHEMA or DEFERRED.
                assertThat(f.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
            }),

        LOOKUP_KEY_ON_INPUT_FIELD_WITH_REFERENCE_JOIN_REJECTED(
            "@lookupKey on a @reference-navigating input-type field → classify-time error (argres Phase 3 supports scalar ColumnField bindings only)",
            """
            input FilmKey @table(name: "film") {
                languageName: String @reference(path: [{key: "film_language_id_fkey"}]) @field(name: "name") @lookupKey
            }
            type Film @table(name: "film") { title: String }
            type Query {
                filmByKey(key: FilmKey): [Film!]!
            }
            """,
            schema -> {
                var f = (no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField) schema.field("Query", "filmByKey");
                assertThat(f.reason())
                    .contains("@lookupKey is only supported on scalar column fields");
            }),

        // ===== Phase 4: @condition on INPUT_FIELD_DEFINITION (condition emission via projectFilters) =====

        TABLE_INPUT_ARG_FIELD_CONDITION_EMITTED(
            "@condition on a @table input field → ConditionFilter appears in the query field's filters",
            """
            input FilmInput @table(name: "film") {
              filmId: Int! @field(name: "film_id")
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "inputColumnCondition"})
            }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { films(filter: FilmInput): [Film!]! }
            """,
            schema -> {
                var f = (QueryField.QueryTableField) schema.field("Query", "films");
                assertThat(f.filters()).hasSize(1);
                assertThat(f.filters().get(0)).isInstanceOf(ConditionFilter.class);
                assertThat(((ConditionFilter) f.filters().get(0)).methodName()).isEqualTo("inputColumnCondition");
            }),

        PLAIN_INPUT_ARG_FIELD_CONDITION_EMITTED(
            "@condition on a plain input field (conflicting tables, classified per call-site) → condition emitted on the matching call site",
            """
            input FilmInput {
              filmId: Int! @field(name: "film_id")
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "inputColumnCondition"})
            }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Language @table(name: "language") { name: String }
            type Query {
              films(filter: FilmInput): [Film!]!
              languages(filter: FilmInput): [Language!]!
            }
            """,
            schema -> {
                var films = (QueryField.QueryTableField) schema.field("Query", "films");
                assertThat(films.filters()).hasSize(1);
                assertThat(((ConditionFilter) films.filters().get(0)).methodName()).isEqualTo("inputColumnCondition");
                var languages = (QueryField.QueryTableField) schema.field("Query", "languages");
                assertThat(languages.filters()).isEmpty();
            }),

        // ===== Implicit column conditions for @table input types =====

        TABLE_INPUT_IMPLICIT_CONDITION_BODYPARAM_EMITTED(
            "@table input field with no @condition → implicit BodyParam with NestedInputField extraction in GCF",
            """
            input FilmInput @table(name: "film") {
              filmId: ID @field(name: "film_id")
            }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { films(filter: FilmInput): [Film!]! }
            """,
            schema -> {
                var f = (QueryField.QueryTableField) schema.field("Query", "films");
                assertThat(f.filters()).hasSize(1);
                var gcf = (GeneratedConditionFilter) f.filters().get(0);
                assertThat(gcf.bodyParams()).hasSize(1);
                var bp = gcf.bodyParams().get(0);
                assertThat(bp.name()).isEqualTo("filmId");
                assertThat(bp.extraction()).isInstanceOf(CallSiteExtraction.NestedInputField.class);
                var nif = (CallSiteExtraction.NestedInputField) bp.extraction();
                assertThat(nif.outerArgName()).isEqualTo("filter");
                assertThat(nif.path()).containsExactly("filmId");
            }),

        TABLE_INPUT_IMPLICIT_CONDITION_EXPLICIT_OVERRIDE_SUPPRESSES_OWN(
            "@table input field with @condition(override:true) → explicit fires, implicit for THAT field suppressed, sibling gets implicit",
            """
            input FilmInput @table(name: "film") {
              filmId: ID @field(name: "film_id")
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "inputColumnCondition"}, override: true)
              title: String @field(name: "title")
            }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") title: String! @field(name: "title") }
            type Query { films(filter: FilmInput): [Film!]! }
            """,
            schema -> {
                var f = (QueryField.QueryTableField) schema.field("Query", "films");
                // GCF from title implicit, ConditionFilter from filmId explicit
                assertThat(f.filters()).hasSize(2);
                var gcf = (GeneratedConditionFilter) f.filters().get(0);
                assertThat(gcf.bodyParams()).hasSize(1);
                assertThat(gcf.bodyParams().get(0).name()).isEqualTo("title");
                assertThat(f.filters().get(1)).isInstanceOf(ConditionFilter.class);
                assertThat(((ConditionFilter) f.filters().get(1)).methodName()).isEqualTo("inputColumnCondition");
            }),

        TABLE_INPUT_IMPLICIT_CONDITION_EXPLICIT_SUPPRESSES_IMPLICIT(
            "@table input field with explicit @condition → implicit BodyParam not emitted for that field; sibling plain field gets one",
            """
            input FilmInput @table(name: "film") {
              filmId: ID @field(name: "film_id") @condition(condition: {className:"no.sikt.graphitron.rewrite.TestConditionStub", method:"inputColumnCondition"})
              title: String @field(name: "title")
            }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") title: String! @field(name: "title") }
            type Query { films(filter: FilmInput): [Film!]! }
            """,
            schema -> {
                var f = (QueryField.QueryTableField) schema.field("Query", "films");
                // filmId has explicit @condition → 1 ConditionFilter
                // title has no @condition → 1 implicit BodyParam in GCF
                long condFilters = f.filters().stream().filter(fi -> !(fi instanceof GeneratedConditionFilter)).count();
                assertThat(condFilters).isEqualTo(1);
                var gcf = f.filters().stream()
                    .filter(fi -> fi instanceof GeneratedConditionFilter)
                    .map(fi -> (GeneratedConditionFilter) fi)
                    .findFirst().orElseThrow();
                assertThat(gcf.bodyParams()).hasSize(1);
                assertThat(gcf.bodyParams().get(0).name()).isEqualTo("title");
            }),

        TABLE_INPUT_IMPLICIT_CONDITION_LOOKUP_KEY_SKIPPED(
            "@lookupKey field on a @table input → no implicit BodyParam emitted; sibling plain field gets one",
            """
            input FilmInput @table(name: "film") {
              filmId: Int! @field(name: "film_id") @lookupKey
              title: String @field(name: "title")
            }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") title: String! @field(name: "title") }
            type Query { films(filter: FilmInput): [Film!]! }
            """,
            schema -> {
                // @lookupKey on an input field promotes this to QueryLookupTableField; access
                // filters via the SqlGeneratingField interface shared with QueryTableField.
                var f = (SqlGeneratingField) schema.field("Query", "films");
                // filmId is @lookupKey → consumed by LookupValuesJoinEmitter, must not also
                // appear as an implicit BodyParam. title has no @condition → implicit emitted.
                var gcf = (GeneratedConditionFilter) f.filters().stream()
                    .filter(fi -> fi instanceof GeneratedConditionFilter)
                    .findFirst().orElseThrow();
                assertThat(gcf.bodyParams()).hasSize(1);
                assertThat(gcf.bodyParams().get(0).name()).isEqualTo("title");
            }),

        TABLE_INPUT_IMPLICIT_CONDITION_NESTED_TWO_LEVEL(
            "@table input with NestingField wrapping an un-annotated ColumnField → implicit BodyParam at leaf path",
            """
            input OuterInput @table(name: "film") {
              inner: InnerInput
            }
            input InnerInput {
              filmId: ID @field(name: "film_id")
            }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { films(filter: OuterInput): [Film!]! }
            """,
            schema -> {
                var f = (QueryField.QueryTableField) schema.field("Query", "films");
                var gcf = (GeneratedConditionFilter) f.filters().get(0);
                assertThat(gcf.bodyParams()).hasSize(1);
                var bp = gcf.bodyParams().get(0);
                assertThat(bp.name()).isEqualTo("filmId");
                var nif = (CallSiteExtraction.NestedInputField) bp.extraction();
                assertThat(nif.outerArgName()).isEqualTo("filter");
                assertThat(nif.path()).containsExactly("inner", "filmId");
            });

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        ArgumentParsingCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() { return Set.of(); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ArgumentParsingCase.class)
    void argumentParsing(ArgumentParsingCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== P4: InputType classification =====

    enum InputTypeCase implements ClassificationCase {
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
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(JavaRecordInputType.class); }
        },

        JOOQ_TABLE_RECORD_CLASS(
            "@record with jOOQ TableRecord class → JooqTableRecordInputType with fqClassName and resolved table",
            """
            input FilmInput @record(record: {className: "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord"}) { id: ID }
            type Query { x: String }
            """,
            schema -> {
                var t = (JooqTableRecordInputType) schema.type("FilmInput");
                assertThat(t.fqClassName()).isEqualTo("no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord");
                assertThat(t.table()).isNotNull();
                assertThat(t.table().tableName()).isEqualTo("film");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(JooqTableRecordInputType.class); }
        },

        UNKNOWN_CLASS(
            "@record with unresolvable class → UnclassifiedType with explanation",
            """
            input FilmInput @record(record: {className: "com.example.nonexistent.Missing"}) { id: ID }
            type Query { x: String }
            """,
            schema -> {
                var t = (UnclassifiedType) schema.type("FilmInput");
                assertThat(t.reason()).contains("com.example.nonexistent.Missing").contains("could not be loaded");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(); }
        },

        TABLE_PLUS_RECORD(
            "@table + @record on an input → @record wins, @table shadowed with build warning",
            """
            input FilmInput
                @table(name: "film")
                @record(record: {className: "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord"})
            { id: ID }
            type Query { x: String }
            """,
            schema -> {
                // @record dispatches first — classification ignores @table entirely.
                var t = (JooqTableRecordInputType) schema.type("FilmInput");
                assertThat(t.fqClassName()).isEqualTo("no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord");
                // Warning emitted naming the shadowed directive.
                assertThat(schema.warnings())
                    .extracting(BuildWarning::message)
                    .anyMatch(m -> m.contains("FilmInput") && m.contains("@table is shadowed by @record"));
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(); }
        };

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        InputTypeCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() { return Set.of(PojoInputType.class); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(InputTypeCase.class)
    void inputTypeClassification(InputTypeCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== P4b: TableInputType classification =====

    enum TableInputTypeCase implements ClassificationCase {
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
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(TableInputType.class, InputField.ColumnField.class); }
        },

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
            "input type used on fields with different return tables → PojoInputType (unbound)",
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
                .isInstanceOf(PojoInputType.class)),

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
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(InputField.ColumnReferenceField.class); }
        },

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
                .isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType.class)),

        NESTED_INPUT_FIELD(
            "nested plain input type (no @table) → NestingField with inline ColumnFields",
            """
            input TitleInput { title: String @field(name: "title") }
            input FilmInput @table(name: "film") {
              filmId: Int! @field(name: "film_id")
              details: TitleInput!
            }
            type Query { x: String }
            """,
            schema -> {
                var it = (no.sikt.graphitron.rewrite.model.GraphitronType.TableInputType) schema.type("FilmInput");
                assertThat(it.inputFields()).hasSize(2);
                var nf = (no.sikt.graphitron.rewrite.model.InputField.NestingField) it.inputFields().stream()
                    .filter(f -> f instanceof no.sikt.graphitron.rewrite.model.InputField.NestingField)
                    .findFirst().orElseThrow();
                assertThat(nf.name()).isEqualTo("details");
                assertThat(nf.typeName()).isEqualTo("TitleInput");
                assertThat(nf.nonNull()).isTrue();
                assertThat(nf.fields()).hasSize(1);
                var inner = (no.sikt.graphitron.rewrite.model.InputField.ColumnField) nf.fields().get(0);
                assertThat(inner.column().javaName()).isEqualTo("TITLE");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(InputField.NestingField.class); }
        },

        NESTED_INPUT_FIELD_UNKNOWN_COLUMN(
            "nested plain input type with unresolvable column → UnclassifiedType on parent",
            """
            input BadInput { noSuch: String @field(name: "no_such_column") }
            input FilmInput @table(name: "film") {
              filmId: Int! @field(name: "film_id")
              details: BadInput!
            }
            type Query { x: String }
            """,
            schema -> assertThat(schema.type("FilmInput"))
                .isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType.class)),

        ARG_CONDITION_OVERRIDE(
            "input type used on an argument with @condition(override: true) → PojoInputType (skip table validation)",
            """
            input FilterInput { notAColumn: Int }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query {
              films(filter: FilterInput @condition(condition: {className: "C", method: "m"}, override: true)): [Film]
            }
            """,
            schema -> assertThat(schema.type("FilterInput")).isInstanceOf(PojoInputType.class)),

        FIELD_CONDITION_OVERRIDE(
            "input type used on a field with @condition(override: true) → PojoInputType (skip table validation)",
            """
            input FilterInput { notAColumn: Int }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query {
              films(filter: FilterInput): [Film]
                @condition(condition: {className: "C", method: "m"}, override: true)
            }
            """,
            schema -> assertThat(schema.type("FilterInput")).isInstanceOf(PojoInputType.class)),

        CONDITION_WITHOUT_OVERRIDE(
            "@condition without override on the argument → override branch does not fire; implicit-table path still runs",
            """
            input FilterInput { filmId: Int! @field(name: "film_id") }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query {
              films(filter: FilterInput @condition(condition: {className: "C", method: "m"})): [Film]
            }
            """,
            schema -> assertThat(schema.type("FilterInput"))
                .isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronType.TableInputType.class)),

        // ===== Phase 4: @condition on INPUT_FIELD_DEFINITION =====

        COLUMN_FIELD_WITH_CONDITION(
            "@condition on a ColumnField — condition() is populated on the classified field",
            """
            input FilmInput @table(name: "film") {
              filmId: Int! @field(name: "film_id")
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "inputColumnCondition"})
            }
            type Query { x: String }
            """,
            schema -> {
                var it = (TableInputType) schema.type("FilmInput");
                var cf = (no.sikt.graphitron.rewrite.model.InputField.ColumnField) it.inputFields().get(0);
                assertThat(cf.condition()).isPresent();
                assertThat(cf.condition().get().filter().methodName()).isEqualTo("inputColumnCondition");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(InputField.ColumnField.class); }
        },

        COLUMN_REFERENCE_FIELD_WITH_CONDITION(
            "@condition on a ColumnReferenceField — condition() is populated",
            """
            input FilmInput @table(name: "film") {
              filmId: Int! @field(name: "film_id")
              languageName: String @field(name: "name") @reference(path: [{key: "film_language_id_fkey"}])
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "inputRefCondition"})
            }
            type Query { x: String }
            """,
            schema -> {
                var it = (TableInputType) schema.type("FilmInput");
                var crf = (no.sikt.graphitron.rewrite.model.InputField.ColumnReferenceField) it.inputFields().stream()
                    .filter(f -> f instanceof no.sikt.graphitron.rewrite.model.InputField.ColumnReferenceField)
                    .findFirst().orElseThrow();
                assertThat(crf.condition()).isPresent();
                assertThat(crf.condition().get().filter().methodName()).isEqualTo("inputRefCondition");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(InputField.ColumnReferenceField.class); }
        },

        NESTING_FIELD_WITH_CONDITION(
            "@condition on a NestingField — condition() is populated on the nesting wrapper",
            """
            input TitleInput { title: String @field(name: "title") }
            input FilmInput @table(name: "film") {
              filmId: Int! @field(name: "film_id")
              details: TitleInput!
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "inputNestingCondition"})
            }
            type Query { x: String }
            """,
            schema -> {
                var it = (TableInputType) schema.type("FilmInput");
                var nf = (no.sikt.graphitron.rewrite.model.InputField.NestingField) it.inputFields().stream()
                    .filter(f -> f instanceof no.sikt.graphitron.rewrite.model.InputField.NestingField)
                    .findFirst().orElseThrow();
                assertThat(nf.condition()).isPresent();
                assertThat(nf.condition().get().filter().methodName()).isEqualTo("inputNestingCondition");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(InputField.NestingField.class); }
        },

        INPUT_FIELD_CONDITION_OVERRIDE(
            "input field carrying @condition(override: true) → whole type bypasses column validation → PojoInputType",
            """
            input FilterInput { notAColumn: Int @condition(condition: {className: "C", method: "m"}, override: true) }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { films(filter: FilterInput): [Film] }
            """,
            schema -> assertThat(schema.type("FilterInput")).isInstanceOf(PojoInputType.class));

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        TableInputTypeCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() { return Set.of(); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(TableInputTypeCase.class)
    void tableInputTypeClassification(TableInputTypeCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== Type classification =====

    enum TypeClassificationCase implements ClassificationCase {
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
            schema -> assertThat(((TableType) schema.type("Film")).table().tableName()).isEqualTo("film")),

        NODE_TYPE(
            "@table+@node type → NodeType with resolved key columns",
            """
            type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
              id: ID! @nodeId
            }
            type Query { film: Film }
            """,
            schema -> {
                var t = (NodeType) schema.type("Film");
                assertThat(t.table().tableName()).isEqualTo("film");
                assertThat(t.nodeKeyColumns()).isNotEmpty();
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(NodeType.class); }
        },

        ROOT_TYPE(
            "Query and Mutation root types → RootType",
            """
            type Film @table(name: "film") { title: String }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.type("Query")).isInstanceOf(RootType.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(RootType.class); }
        };

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        TypeClassificationCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() { return Set.of(TableType.class); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(TypeClassificationCase.class)
    void typeClassification(TypeClassificationCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== ErrorType =====

    enum ErrorTypeCase implements ClassificationCase {
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
        @Override public Set<Class<?>> variants() { return Set.of(ErrorType.class); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ErrorTypeCase.class)
    void errorTypeClassification(ErrorTypeCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== Fields on @error parents =====

    /**
     * Child fields on an {@code @error} type. Fields are expected to be scalar or enum —
     * they back a POJO exposed via graphql-java's default {@code PropertyDataFetcher}.
     */
    enum ErrorFieldCase implements ClassificationCase {
        SCALAR_FIELD(
            "@error parent — scalar field → PropertyField using field name as columnName",
            """
            type MyError @error(handlers: [{handler: GENERIC, className: "com.example.Ex"}]) {
                message: String!
            }
            type Query { x: String }
            """,
            schema -> {
                var f = (PropertyField) schema.field("MyError", "message");
                assertThat(f.columnName()).isEqualTo("message");
            }),

        LIST_OF_SCALARS_FIELD(
            "@error parent — list-of-scalars field (Error.path pattern) → PropertyField",
            """
            interface Error { path: [String!]!, message: String! }
            type MyError implements Error @error(handlers: [{handler: GENERIC, className: "com.example.Ex"}]) {
                path: [String!]!
                message: String!
            }
            type Query { x: String }
            """,
            schema -> {
                assertThat(schema.field("MyError", "path")).isInstanceOf(PropertyField.class);
                assertThat(schema.field("MyError", "message")).isInstanceOf(PropertyField.class);
            }),

        ENUM_FIELD(
            "@error parent — enum field → PropertyField",
            """
            enum Severity { LOW HIGH }
            type MyError @error(handlers: [{handler: GENERIC, className: "com.example.Ex"}]) {
                severity: Severity!
            }
            type Query { x: String }
            """,
            schema -> assertThat(schema.field("MyError", "severity")).isInstanceOf(PropertyField.class)),

        OBJECT_FIELD_REJECTED(
            "@error parent — object return type → UnclassifiedField with reason",
            """
            type Detail { info: String }
            type MyError @error(handlers: [{handler: GENERIC, className: "com.example.Ex"}]) {
                detail: Detail
            }
            type Query { x: String }
            """,
            schema -> {
                assertThat(schema.field("MyError", "detail")).isInstanceOf(UnclassifiedField.class);
                var uf = (UnclassifiedField) schema.field("MyError", "detail");
                assertThat(uf.reason()).contains("scalar or enum");
            });

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        ErrorFieldCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() { return Set.of(PropertyField.class); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ErrorFieldCase.class)
    void errorFieldClassification(ErrorFieldCase tc) {
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
        assertThatThrownBy(() -> GraphitronSchemaBuilder.build(registry, TestConfiguration.testContext()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("@");
    }

    // ===== Root field classification =====

    /**
     * Root field classification. One case per {@code QueryField} / {@code MutationField} variant
     * the builder can produce. Covers the <em>Query Fields</em> and <em>Mutation Fields</em>
     * tables in {@code code-generation-triggers.md}.
     */
    enum RootFieldCase implements ClassificationCase {

        LOOKUP_QUERY_FIELD(
            "field with @lookupKey list arg → QueryLookupTableField with list return; key flows through LookupMapping, not filters",
            """
            type Film @table(name: "film") { title: String }
            type Query { filmById(film_id: [ID] @lookupKey): [Film!]! }
            """,
            schema -> {
                assertThat(schema.field("Query", "filmById")).isInstanceOf(QueryField.QueryLookupTableField.class);
                var f = (QueryField.QueryLookupTableField) schema.field("Query", "filmById");
                // @lookupKey args no longer populate filters() — see docs/argument-resolution.md Phase 1.
                // They are emitted via VALUES+JOIN from LookupMapping.columns() instead.
                assertThat(f.filters()).isEmpty();
                assertThat(((no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) f.lookupMapping()).columns()).hasSize(1);
                assertThat(((no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) f.lookupMapping()).columns().get(0).argName()).isEqualTo("film_id");
                assertThat(f.returnType().wrapper()).isInstanceOf(FieldWrapper.List.class);
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(QueryField.QueryLookupTableField.class); }
        },

        LOOKUP_NESTED_IN_INPUT(
            "@lookupKey nested in input type with no direct scalar key → UnclassifiedField (composite-key support deferred to Phase 3)",
            """
            input FilmKey { id: ID @lookupKey }
            type Film @table(name: "film") { title: String }
            type Query { filmByKey(key: [FilmKey]): [Film!]! }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Query", "filmByKey");
                assertThat(f.reason())
                    .contains("@lookupKey is declared")
                    .contains("no argument resolved to a lookup column");
            }),

        LOOKUP_FIELD_COLUMN_ARG(
            "lookup field list arg whose column exists → resolved LookupColumn in LookupMapping",
            """
            type Film @table(name: "film") { title: String }
            type Query { filmById(film_id: [ID] @lookupKey): [Film!]! }
            """,
            schema -> {
                var f = (QueryField.QueryLookupTableField) schema.field("Query", "filmById");
                assertThat(f.filters()).isEmpty();
                assertThat(((no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) f.lookupMapping()).columns()).hasSize(1);
                var col = ((no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) f.lookupMapping()).columns().get(0);
                assertThat(col.argName()).isEqualTo("film_id");
                assertThat(col.targetColumn().javaName()).isEqualTo("FILM_ID");
                assertThat(col.targetColumn().columnClass()).isNotEmpty();
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

        LOOKUP_FIELD_TABLE_INPUT_TYPE_ARG_NO_INNER_LOOKUP_KEY(
            "lookup field whose @table input type has no @lookupKey on any scalar field → UnclassifiedField (Phase 3 requires @lookupKey on the input-type scalar fields, not on the outer argument)",
            """
            input FilmKey @table(name: "film") { filmId: Int @field(name: "film_id") }
            type Film @table(name: "film") { title: String }
            type Query { filmByKey(key: [FilmKey] @lookupKey): [Film!]! }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Query", "filmByKey");
                assertThat(f.reason())
                    .contains("@lookupKey is declared")
                    .contains("no argument resolved to a lookup column");
            }),

        LOOKUP_FIELD_IMPLICIT_TABLE_INPUT_TYPE_ARG_NO_INNER_LOOKUP_KEY(
            "lookup field whose plain input type (promoted to TableInputType) has no @lookupKey on any field → UnclassifiedField; promoted type remains in the types map",
            """
            input FilmKey { filmId: Int @field(name: "film_id") }
            type Film @table(name: "film") { title: String }
            type Query { filmByKey(key: [FilmKey] @lookupKey): [Film!]! }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Query", "filmByKey");
                assertThat(f.reason())
                    .contains("@lookupKey is declared")
                    .contains("no argument resolved to a lookup column");
                // The type was still promoted to TableInputType in types map (classification
                // of the input type happens independently of field classification).
                assertThat(schema.type("FilmKey"))
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronType.TableInputType.class);
            }),

        LOOKUP_FIELD_COMPOSITE_KEY_INPUT_TYPE_ARG(
            "lookup field whose @table input type carries @lookupKey on two scalar fields → QueryLookupTableField with two composite LookupColumns (2-segment SourcePath each)",
            """
            input FilmActorKey @table(name: "film_actor") {
                filmId: Int @field(name: "film_id") @lookupKey
                actorId: Int @field(name: "actor_id") @lookupKey
            }
            type FilmActor @table(name: "film_actor") { lastUpdate: String @field(name: "last_update") }
            type Query { filmActorByKey(key: [FilmActorKey] @lookupKey): [FilmActor!]! }
            """,
            schema -> {
                var f = (QueryField.QueryLookupTableField) schema.field("Query", "filmActorByKey");
                assertThat(f.filters()).isEmpty();
                assertThat(((no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) f.lookupMapping()).columns()).hasSize(2);
                var cols = ((no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) f.lookupMapping()).columns();
                assertThat(cols).allSatisfy(c -> {
                    assertThat(c.isComposite()).isTrue();
                    assertThat(c.sourcePath().segments()).hasSize(2);
                    assertThat(c.sourcePath().segments().get(0)).isEqualTo("key");
                    assertThat(c.list()).isTrue();
                });
                assertThat(cols).extracting(c -> c.sourcePath().segments().get(1))
                    .containsExactly("filmId", "actorId");
                assertThat(cols).extracting(c -> c.targetColumn().javaName())
                    .containsExactly("FILM_ID", "ACTOR_ID");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(QueryField.QueryLookupTableField.class); }
        },

        LOOKUP_FIELD_ORDERBY_ARG(
            "@orderBy arg with valid input type structure → OrderBySpec.Argument with resolved field names",
            """
            enum FilmOrderField { TITLE @order(primaryKey: true) }
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
                assertThat(orderBy.namedOrders()).hasSize(1);
                assertThat(orderBy.namedOrders().get(0).name()).isEqualTo("TITLE");
                assertThat(f.filters()).isEmpty();
                assertThat(((no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) f.lookupMapping()).columns()).hasSize(1);
                assertThat(((no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) f.lookupMapping()).columns().get(0).argName()).isEqualTo("film_id");
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
            schema -> assertThat(schema.field("Query", "films")).isInstanceOf(QueryField.QueryTableField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(QueryField.QueryTableField.class); }
        },

        TABLE_QUERY_FIELD_WITH_ARGS(
            "table query field with @orderBy argument → OrderBySpec.Argument on orderBy(); filters empty",
            """
            enum FilmOrderField { TITLE @order(primaryKey: true) }
            enum Direction { ASC DESC }
            input FilmOrder { sortField: FilmOrderField! direction: Direction! }
            type Film @table(name: "film") { title: String }
            type Query { films(orderBy: FilmOrder @orderBy): [Film!]! }
            """,
            schema -> {
                var f = (QueryField.QueryTableField) schema.field("Query", "films");
                assertThat(f.filters()).isEmpty();
                assertThat(f.orderBy()).isInstanceOf(OrderBySpec.Argument.class);
                var orderBy = (OrderBySpec.Argument) f.orderBy();
                assertThat(orderBy.namedOrders()).hasSize(1);
                assertThat(orderBy.namedOrders().get(0).name()).isEqualTo("TITLE");
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
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(QueryField.QueryTableMethodTableField.class); }
        },

        NODE_QUERY_FIELD(
            "field named 'node' → QueryNodeField",
            """
            interface Node { id: ID! }
            type Film implements Node @table(name: "film") { id: ID! title: String }
            type Query { node(id: ID!): Node }
            """,
            schema -> assertThat(schema.field("Query", "node")).isInstanceOf(QueryField.QueryNodeField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(QueryField.QueryNodeField.class); }
        },

        ENTITY_QUERY_FIELD(
            "field named '_entities' → QueryEntityField",
            """
            scalar _Any
            union _Entity = Film
            type Film @table(name: "film") { title: String }
            type Query { _entities(representations: [_Any!]!): [_Entity]! }
            """,
            schema -> assertThat(schema.field("Query", "_entities")).isInstanceOf(QueryField.QueryEntityField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(QueryField.QueryEntityField.class); }
        },

        TABLE_INTERFACE_QUERY_FIELD(
            "field returning table-interface type → QueryTableInterfaceField",
            """
            interface MediaItem @table(name: "film") @discriminate(on: "kind") { title: String }
            type Film implements MediaItem @table(name: "film") @discriminator(value: "film") { title: String }
            type Query { media: MediaItem }
            """,
            schema -> assertThat(schema.field("Query", "media")).isInstanceOf(QueryField.QueryTableInterfaceField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(QueryField.QueryTableInterfaceField.class); }
        },

        INTERFACE_QUERY_FIELD(
            "field returning plain interface → QueryInterfaceField",
            """
            interface Named { name: String }
            type Film implements Named @table(name: "film") { name: String }
            type Query { named: Named }
            """,
            schema -> assertThat(schema.field("Query", "named")).isInstanceOf(QueryField.QueryInterfaceField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(QueryField.QueryInterfaceField.class); }
        },

        UNION_QUERY_FIELD(
            "field returning union → QueryUnionField",
            """
            type Film @table(name: "film") { title: String }
            type Actor @table(name: "actor") { name: String }
            union SearchResult = Film | Actor
            type Query { search: SearchResult }
            """,
            schema -> assertThat(schema.field("Query", "search")).isInstanceOf(QueryField.QueryUnionField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(QueryField.QueryUnionField.class); }
        },

        SERVICE_QUERY_FIELD(
            "@service on root query field, @table return type → QueryServiceTableField",
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
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(QueryField.QueryServiceTableField.class); }
        },

        QUERY_SERVICE_RECORD_FIELD(
            "@service on root query field, non-table return type → QueryServiceRecordField",
            """
            type FilmDetails @record { title: String }
            type Query {
                filmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"})
            }
            """,
            schema -> assertThat(schema.field("Query", "filmDetails")).isInstanceOf(QueryField.QueryServiceRecordField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(QueryField.QueryServiceRecordField.class); }
        },

        INSERT_MUTATION_FIELD(
            "@mutation(typeName: INSERT) → MutationInsertTableField",
            """
            type Film @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { createFilm: Film @mutation(typeName: INSERT) }
            """,
            schema -> assertThat(schema.field("Mutation", "createFilm")).isInstanceOf(MutationField.MutationInsertTableField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationInsertTableField.class); }
        },

        UPDATE_MUTATION_FIELD(
            "@mutation(typeName: UPDATE) → MutationUpdateTableField",
            """
            type Film @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { updateFilm: Film @mutation(typeName: UPDATE) }
            """,
            schema -> assertThat(schema.field("Mutation", "updateFilm")).isInstanceOf(MutationField.MutationUpdateTableField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationUpdateTableField.class); }
        },

        DELETE_MUTATION_FIELD(
            "@mutation(typeName: DELETE) → MutationDeleteTableField",
            """
            type Film @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { deleteFilm: Film @mutation(typeName: DELETE) }
            """,
            schema -> assertThat(schema.field("Mutation", "deleteFilm")).isInstanceOf(MutationField.MutationDeleteTableField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationDeleteTableField.class); }
        },

        UPSERT_MUTATION_FIELD(
            "@mutation(typeName: UPSERT) → MutationUpsertTableField",
            """
            type Film @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { upsertFilm: Film @mutation(typeName: UPSERT) }
            """,
            schema -> assertThat(schema.field("Mutation", "upsertFilm")).isInstanceOf(MutationField.MutationUpsertTableField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationUpsertTableField.class); }
        },

        SERVICE_MUTATION_FIELD(
            "@service on mutation field, @table return type → MutationServiceTableField",
            """
            type Film @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation {
                externalMutation: Film @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "run"})
            }
            """,
            schema -> assertThat(schema.field("Mutation", "externalMutation")).isInstanceOf(MutationField.MutationServiceTableField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationServiceTableField.class); }
        },

        MUTATION_SERVICE_RECORD_FIELD(
            "@service on mutation field, non-table return type → MutationServiceRecordField",
            """
            type FilmDetails @record { title: String }
            type Query { x: String }
            type Mutation {
                externalMutation: FilmDetails @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "run"})
            }
            """,
            schema -> assertThat(schema.field("Mutation", "externalMutation")).isInstanceOf(MutationField.MutationServiceRecordField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationServiceRecordField.class); }
        };

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        RootFieldCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() { return Set.of(); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(RootFieldCase.class)
    void rootFieldClassification(RootFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== UnclassifiedField =====

    enum UnclassifiedFieldCase implements ClassificationCase {

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
        @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(UnclassifiedFieldCase.class)
    void unclassifiedFieldClassification(UnclassifiedFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== ConstructorField — @table parent with @record child =====

    @Test
    void constructorField_tableParentRecordChild_classifiedAsConstructorField() {
        var schema = build("""
            type FilmDetails @record { rating: String }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """);
        assertThat(schema.field("Film", "details")).isInstanceOf(ChildField.ConstructorField.class);
    }

    // ===== Type directive mutual exclusivity =====
    // @table, @record, and @error are mutually exclusive — the builder produces UnclassifiedType
    // carrying the names of the conflicting directives in its reason.

    enum TypeDirectiveConflictCase implements ClassificationCase {

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
        @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedType.class); }
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

    enum ChildFieldDirectiveConflictCase implements ClassificationCase {

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
        @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
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

    enum RootFieldDirectiveConflictCase implements ClassificationCase {

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
        @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
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

    // ===== JoinStep alias computation =====
    // Each join step is assigned a unique table alias at build time: fieldName + "_" + stepIndex.
    // This guarantees alias uniqueness across the entire query:
    //   - Two fields on the same parent referencing the same target table via different FKs
    //     get different aliases because their field names differ (e.g. "language_0" vs
    //     "originalLanguage_0").
    //   - A multi-hop path on the same field gets different aliases because the step index
    //     increments (e.g. "city_0" for the first hop, "city_1" for the second).
    //
    // Practical consequence for code generation:
    //   For MULTISET correlated subqueries, each call to Actor.subselectMany/subselectOne
    //   operates in its own SQL scope, so alias collisions cannot occur even if the same
    //   Actor method is called twice (e.g. leadMaleActor and leadFemaleActor on Film).
    //   For flat batch JOINs the aliases would be injected directly into a shared SELECT,
    //   making the fieldName prefix essential to prevent duplicate alias errors.

    enum JoinStepAliasCase implements ClassificationCase {

        SINGLE_HOP_ALIAS(
            "single-hop @reference gets alias fieldName_0",
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
                var fk = (JoinStep.FkJoin) tf.joinPath().get(0);
                assertThat(fk.alias()).isEqualTo("language_0");
            }),

        TWO_HOP_PATH_ALIASES_HAVE_DISTINCT_INDICES(
            "two-hop @reference gets aliases fieldName_0 and fieldName_1",
            """
            type City @table(name: "city") { name: String }
            type Customer @table(name: "customer") {
                city: City @reference(path: [
                    {key: "customer_address_id_fkey"},
                    {key: "address_city_id_fkey"}
                ])
            }
            type Query { customer: Customer }
            """,
            schema -> {
                var tf = (TableField) schema.field("Customer", "city");
                assertThat(tf.joinPath()).hasSize(2);
                var step0 = (JoinStep.FkJoin) tf.joinPath().get(0);
                var step1 = (JoinStep.FkJoin) tf.joinPath().get(1);
                assertThat(step0.alias()).isEqualTo("city_0");
                assertThat(step1.alias()).isEqualTo("city_1");
            }),

        TWO_FIELDS_SAME_TARGET_TABLE_DIFFERENT_FKS_HAVE_DISTINCT_ALIASES(
            "two fields on the same parent targeting the same table via different FKs get distinct aliases",
            """
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                language: Language @reference(path: [{key: "film_language_id_fkey"}])
                originalLanguage: Language @reference(path: [{key: "film_original_language_id_fkey"}])
            }
            type Query { film: Film }
            """,
            schema -> {
                var fk1 = (JoinStep.FkJoin) ((TableField) schema.field("Film", "language")).joinPath().get(0);
                var fk2 = (JoinStep.FkJoin) ((TableField) schema.field("Film", "originalLanguage")).joinPath().get(0);
                assertThat(fk1.alias()).isEqualTo("language_0");
                assertThat(fk2.alias()).isEqualTo("originalLanguage_0");
                // The two calls to Language.subselectMany/subselectOne in a Film query will
                // carry different aliases, so no duplicate-alias error occurs in flat JOINs.
                assertThat(fk1.alias()).isNotEqualTo(fk2.alias());
            });

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        JoinStepAliasCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() { return Set.of(TableField.class); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(JoinStepAliasCase.class)
    void joinStepAliasComputation(JoinStepAliasCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== Connection / Edge / PageInfo promotion =====

    enum ConnectionTypeCase implements ClassificationCase {
        DIRECTIVE_DRIVEN_MINIMAL(
            "@asConnection on a bare list → ConnectionType + EdgeType + PageInfoType in schema.types()",
            """
            type Film @table(name: "film") { id: ID }
            type Query { films: [Film!]! @asConnection }
            """,
            schema -> {
                var conn = (ConnectionType) schema.type("QueryFilmsConnection");
                assertThat(conn.elementTypeName()).isEqualTo("Film");
                assertThat(conn.edgeTypeName()).isEqualTo("QueryFilmsEdge");
                assertThat(conn.itemNullable()).isFalse();
                assertThat(conn.shareable()).isFalse();
                assertThat(conn.schemaType()).isNotNull();
                assertThat(conn.schemaType().getFieldDefinition("edges")).isNotNull();
                assertThat(conn.schemaType().getFieldDefinition("nodes")).isNotNull();
                assertThat(conn.schemaType().getFieldDefinition("pageInfo")).isNotNull();

                var edge = (EdgeType) schema.type("QueryFilmsEdge");
                assertThat(edge.elementTypeName()).isEqualTo("Film");
                assertThat(edge.itemNullable()).isFalse();
                assertThat(edge.schemaType().getFieldDefinition("cursor")).isNotNull();
                assertThat(edge.schemaType().getFieldDefinition("node")).isNotNull();

                var pi = (PageInfoType) schema.type("PageInfo");
                assertThat(pi.shareable()).isFalse();
                assertThat(pi.schemaType().getFieldDefinition("hasNextPage")).isNotNull();
                assertThat(pi.schemaType().getFieldDefinition("endCursor")).isNotNull();
            }),

        EXPLICIT_CONNECTION_NAME(
            "@asConnection(connectionName:) overrides the derived name",
            """
            type Film @table(name: "film") { id: ID }
            type Query { films: [Film!]! @asConnection(connectionName: "MyFilmsConnection") }
            """,
            schema -> {
                assertThat(schema.type("MyFilmsConnection")).isInstanceOf(ConnectionType.class);
                assertThat(schema.type("QueryFilmsConnection")).isNull();
                assertThat(schema.type("MyFilmsEdge")).isInstanceOf(EdgeType.class);
            }),

        NULLABLE_ITEM(
            "item nullability of the bare list carries through to ConnectionType.itemNullable",
            """
            type Film @table(name: "film") { id: ID }
            type Query { films: [Film] @asConnection }
            """,
            schema -> {
                var conn = (ConnectionType) schema.type("QueryFilmsConnection");
                assertThat(conn.itemNullable()).isTrue();
                var edge = (EdgeType) schema.type("QueryFilmsEdge");
                assertThat(edge.itemNullable()).isTrue();
            }),

        STRUCTURAL_CONNECTION(
            "a hand-written Connection/Edge/PageInfo pattern classifies as ConnectionType/EdgeType/PageInfoType",
            """
            type Film @table(name: "film") { id: ID }
            type FilmsConnection { edges: [FilmsEdge!]! nodes: [Film!]! pageInfo: PageInfo! }
            type FilmsEdge { cursor: String! node: Film! }
            type PageInfo { hasNextPage: Boolean! hasPreviousPage: Boolean! startCursor: String endCursor: String }
            type Query { films: FilmsConnection }
            """,
            schema -> {
                var conn = (ConnectionType) schema.type("FilmsConnection");
                assertThat(conn.elementTypeName()).isEqualTo("Film");
                assertThat(conn.edgeTypeName()).isEqualTo("FilmsEdge");
                assertThat(conn.schemaType()).isNotNull();
                // Structural path: schemaType is the SDL-parsed instance, reused verbatim.
                assertThat(schema.type("FilmsEdge")).isInstanceOf(EdgeType.class);
                assertThat(schema.type("PageInfo")).isInstanceOf(PageInfoType.class);
            }),

        DEDUP_SAME_EXPLICIT_NAME(
            "two carriers pointing at the same explicit connectionName dedup to a single variant",
            """
            type Film @table(name: "film") { id: ID }
            type Query {
              films:  [Film!]! @asConnection(connectionName: "SharedConnection")
              films2: [Film!]! @asConnection(connectionName: "SharedConnection")
            }
            """,
            schema -> {
                assertThat(schema.type("SharedConnection")).isInstanceOf(ConnectionType.class);
                assertThat(schema.type("SharedEdge")).isInstanceOf(EdgeType.class);
            }),

        NO_PAGE_INFO_SYNTHESIS_WHEN_DECLARED(
            "an SDL-declared PageInfo is reused — the synthesis step does not overwrite it",
            """
            type Film @table(name: "film") { id: ID }
            type PageInfo { hasNextPage: Boolean! hasPreviousPage: Boolean! startCursor: String endCursor: String }
            type Query { films: [Film!]! @asConnection }
            """,
            schema -> {
                var pi = (PageInfoType) schema.type("PageInfo");
                // Structural SDL-parsed schemaType — not the synthesised instance.
                assertThat(pi.schemaType()).isNotNull();
                assertThat(pi.shareable()).isFalse();
            });

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        ConnectionTypeCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() {
            return Set.of(ConnectionType.class, EdgeType.class, PageInfoType.class);
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ConnectionTypeCase.class)
    void connectionTypePromotion(ConnectionTypeCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== Plain object type classification =====

    enum PlainObjectTypeCase implements ClassificationCase {
        NESTED_DTO_WITHOUT_DIRECTIVES(
            "plain object type with no directives → PlainObjectType entry in schema.types()",
            """
            type Film { id: ID! title: String }
            type Query { foo: String }
            """,
            schema -> {
                var t = (no.sikt.graphitron.rewrite.model.GraphitronType.PlainObjectType)
                    schema.type("Film");
                assertThat(t.name()).isEqualTo("Film");
                assertThat(t.schemaType()).isNotNull();
                assertThat(t.schemaType().getName()).isEqualTo("Film");
                assertThat(t.schemaType().getFieldDefinition("id")).isNotNull();
            }),

        STANDALONE_RETURN_TYPE(
            "plain object used as a Query return also classifies as PlainObjectType",
            """
            type Meta { totalCount: Int }
            type Query { meta: Meta }
            """,
            schema -> {
                assertThat(schema.type("Meta"))
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronType.PlainObjectType.class);
            }),

        DOES_NOT_OVERRIDE_DIRECTIVE_CLASSIFICATION(
            "@table-annotated types still classify as TableType, not PlainObjectType",
            """
            type Film @table(name: "film") { id: ID! }
            type Query { f: Film }
            """,
            schema -> {
                assertThat(schema.type("Film"))
                    .isInstanceOf(TableType.class)
                    .isNotInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronType.PlainObjectType.class);
            });

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        PlainObjectTypeCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() {
            return Set.of(no.sikt.graphitron.rewrite.model.GraphitronType.PlainObjectType.class);
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(PlainObjectTypeCase.class)
    void plainObjectTypeClassification(PlainObjectTypeCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== Enum type classification =====

    enum EnumTypeCase implements ClassificationCase {
        PLAIN_ENUM(
            "SDL-declared enum classifies as EnumType with its GraphQLEnumType as schemaType",
            """
            enum Status { ACTIVE INACTIVE }
            type Query { s: Status }
            """,
            schema -> {
                var t = (no.sikt.graphitron.rewrite.model.GraphitronType.EnumType)
                    schema.type("Status");
                assertThat(t.name()).isEqualTo("Status");
                assertThat(t.schemaType()).isNotNull();
                assertThat(t.schemaType().getValues()).extracting(v -> v.getName())
                    .containsExactly("ACTIVE", "INACTIVE");
            }),

        ENUM_WITH_DEPRECATED_VALUE(
            "deprecation on an enum value survives on schemaType",
            """
            enum Status { ACTIVE OLD @deprecated(reason: "unused") }
            type Query { s: Status }
            """,
            schema -> {
                var t = (no.sikt.graphitron.rewrite.model.GraphitronType.EnumType)
                    schema.type("Status");
                assertThat(t.schemaType().getValue("OLD").isDeprecated()).isTrue();
            });

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        EnumTypeCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() {
            return Set.of(no.sikt.graphitron.rewrite.model.GraphitronType.EnumType.class);
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(EnumTypeCase.class)
    void enumTypeClassification(EnumTypeCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== Helper =====

    private GraphitronSchema build(String schemaText) {
        return TestSchemaHelper.buildSchema(schemaText);
    }
}
