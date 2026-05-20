package no.sikt.graphitron.rewrite;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.rewrite.BuildWarning;
import no.sikt.graphitron.rewrite.RejectionKind;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ChildField.ColumnField;
import no.sikt.graphitron.rewrite.model.ChildField.ErrorsField;
import no.sikt.graphitron.rewrite.model.DmlKind;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.ChildField.ColumnReferenceField;
import no.sikt.graphitron.rewrite.model.ChildField.ComputedField;
import no.sikt.graphitron.rewrite.model.ChildField.InterfaceField;
import no.sikt.graphitron.rewrite.model.ChildField.NestingField;
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
import no.sikt.graphitron.rewrite.model.ChildField.RecordTableMethodField;
import no.sikt.graphitron.rewrite.model.ChildField.UnionField;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
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
import no.sikt.graphitron.rewrite.model.GraphitronType.ScalarType;
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
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;

/**
 * Pipeline-tier classification tests. Each section has an enum where every constant is one (sdl, assertion)
 * case; the parameterised test method iterates the whole truth table automatically.
 */
@PipelineTier
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
            }),

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
            schema -> assertThat(schema.field("Film", "actor")).isInstanceOf(UnclassifiedField.class)),

        CONDITION_PATH_ARGMAPPING_REJECTED(
            "R53: @reference path-step @condition with argMapping → UnclassifiedField; reason names path-step site",
            """
            type Actor @table(name: "actor") { firstName: String }
            type Film @table(name: "film") {
              actor: Actor @reference(path: [{condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "join", argMapping: "x: y"}}])
            }
            type Query { film: Film }
            """,
            schema -> {
                var f = schema.field("Film", "actor");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("path-step @condition")
                    .contains("no GraphQL arguments are in scope");
            });

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

    // ===== ParticipantColumnReferenceField =====
    // Cross-table fields on TableInterfaceType participants get their own classified leaf so the
    // interface fetcher's conditional LEFT JOIN wires the projection and the per-field
    // DataFetcher reads it back by alias.

    enum ParticipantColumnReferenceFieldCase implements ClassificationCase {
        SCALAR_REFERENCE_TO_OTHER_TABLE(
            "scalar @reference on a TableInterfaceType participant whose target is a different "
            + "table is classified as ParticipantColumnReferenceField with the cross-table FkJoin "
            + "and a unique alias name",
            """
            interface Content @table(name: "content") @discriminate(on: "CONTENT_TYPE") {
              contentId: Int! @field(name: "CONTENT_ID")
            }
            type FilmContent implements Content @table(name: "content") @discriminator(value: "FILM") {
              contentId: Int! @field(name: "CONTENT_ID")
              rating: String @reference(path: [{key: "content_film_id_fkey"}]) @field(name: "RATING")
            }
            type ShortContent implements Content @table(name: "content") @discriminator(value: "SHORT") {
              contentId: Int! @field(name: "CONTENT_ID")
            }
            type Query { content: Content }
            """,
            schema -> {
                var f = schema.field("FilmContent", "rating");
                assertThat(f).isInstanceOf(no.sikt.graphitron.rewrite.model.ChildField.ParticipantColumnReferenceField.class);
                var pcrf = (no.sikt.graphitron.rewrite.model.ChildField.ParticipantColumnReferenceField) f;
                assertThat(pcrf.targetTable().tableName()).isEqualToIgnoringCase("film");
                assertThat(pcrf.fkJoin().fk().sqlName()).isEqualToIgnoringCase("content_film_id_fkey");
                assertThat(pcrf.aliasName()).isEqualTo("FilmContent_rating");
                assertThat(pcrf.column().sqlName()).isEqualToIgnoringCase("rating");
            }),

        REFERENCE_TO_SAME_TABLE_FALLS_THROUGH(
            "scalar @reference whose target resolves to the participant's own table is NOT a "
            + "ParticipantColumnReferenceField; the classifier reverts to the standard "
            + "ColumnReferenceField path",
            """
            interface Content @table(name: "content") @discriminate(on: "CONTENT_TYPE") {
              contentId: Int! @field(name: "CONTENT_ID")
            }
            type FilmContent implements Content @table(name: "content") @discriminator(value: "FILM") {
              contentId: Int! @field(name: "CONTENT_ID")
              # Hypothetical self-FK — left as a no-FK fallback to ensure the path doesn't lift to
              # ParticipantColumnReferenceField when no cross-table FkJoin exists. The field falls
              # through to ColumnReferenceField/UnclassifiedField via the existing classifier path.
              titleAlias: String @field(name: "TITLE")
            }
            type ShortContent implements Content @table(name: "content") @discriminator(value: "SHORT") {
              contentId: Int! @field(name: "CONTENT_ID")
            }
            type Query { content: Content }
            """,
            schema -> assertThat(schema.field("FilmContent", "titleAlias"))
                .isNotInstanceOf(no.sikt.graphitron.rewrite.model.ChildField.ParticipantColumnReferenceField.class));

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        ParticipantColumnReferenceFieldCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() { return Set.of(no.sikt.graphitron.rewrite.model.ChildField.ParticipantColumnReferenceField.class); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParticipantColumnReferenceFieldCase.class)
    void participantColumnReferenceFieldClassification(ParticipantColumnReferenceFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== @multitableReference (deprecated: rejected by the classifier) =====

    enum MultitableReferenceFieldCase implements ClassificationCase {
        REJECTED(
            "@multitableReference is no longer supported → UnclassifiedField with reason saying so",
            """
            type Film @table(name: "film") {
              other: String @multitableReference(routes: [{typeName: "X", path: [{key: "K"}]}])
            }
            type Query { film: Film }
            """,
            schema -> {
                assertThat(schema.field("Film", "other")).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) schema.field("Film", "other")).reason())
                    .contains("@multitableReference", "no longer supported");
            }),

        REJECTED_WINS_OVER_CONFLICT(
            "@multitableReference + @service → UnclassifiedField with deprecation reason, not a mutual-exclusivity reason",
            """
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                language: Language @multitableReference @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"})
            }
            type Query { film: Film }
            """,
            schema -> {
                assertThat(schema.field("Film", "language")).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) schema.field("Film", "language")).reason())
                    .contains("@multitableReference", "no longer supported");
            });

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        MultitableReferenceFieldCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(MultitableReferenceFieldCase.class)
    void multitableReferenceFieldClassification(MultitableReferenceFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== Output-side NodeId carrier (post-R50: ChildField.ColumnField with NodeIdEncodeKeys compaction) =====

    enum NodeIdFieldCase implements ClassificationCase {
        WITH_NODE_DIRECTIVE(
            "@nodeId on a type that also has @node — classified as ColumnField with NodeIdEncodeKeys compaction",
            """
            type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
              id: ID! @nodeId
            }
            type Query { film: Film }
            """,
            schema -> {
                var field = (ChildField.ColumnField) schema.field("Film", "id");
                assertThat(field.column()).isNotNull();
                assertThat(field.compaction())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteCompaction.NodeIdEncodeKeys.class);
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
        @Override public Set<Class<?>> variants() { return Set.of(ChildField.ColumnField.class); }
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
            "typeName pointing to a @node type with a single FK between tables → ColumnField with NodeIdEncodeKeys (FK-mirror collapse: FK source columns ARE the keys)",
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
                var ref = (ChildField.ColumnField) schema.field("City", "countryId");
                assertThat(ref.column()).isNotNull();
                assertThat(ref.compaction())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteCompaction.NodeIdEncodeKeys.class);
                assertThat(((no.sikt.graphitron.rewrite.model.CallSiteCompaction.NodeIdEncodeKeys) ref.compaction())
                    .encodeMethod().methodName()).isEqualTo("encodeCountry");
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
            "@reference(path:) on a @nodeId field with FK-mirror collapse → ColumnField with NodeIdEncodeKeys",
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
                var ref = (ChildField.ColumnField) schema.field("Film", "languageId");
                assertThat(ref.compaction())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteCompaction.NodeIdEncodeKeys.class);
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
        @Override public Set<Class<?>> variants() { return Set.of(ChildField.ColumnField.class, UnclassifiedField.class); }
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
            "@splitQuery with single-cardinality parent-holds-FK reference → SplitTableField with FK-column SourceKey",
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
                assertThat(f.sourceKey().reader()).isInstanceOf(SourceKey.Reader.ColumnRead.class);
                assertThat(f.sourceKey().columns()).extracting(ColumnRef::sqlName)
                    .containsExactly("address_id");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(SplitTableField.class); }
        },

        IMPLICIT_REFERENCE_SPLIT_TABLE_SINGLE_CARDINALITY(
            "no @reference on single-cardinality @splitQuery with single FK → SplitTableField, parent-FK SourceKey",
            """
            type Address @table(name: "address") { address: String }
            type Customer @table(name: "customer") { address: Address @splitQuery }
            type Query { customer: Customer }
            """,
            schema -> {
                var f = (SplitTableField) schema.field("Customer", "address");
                assertThat(f.joinPath()).hasSize(1);
                assertThat(f.sourceKey().reader()).isInstanceOf(SourceKey.Reader.ColumnRead.class);
                assertThat(f.sourceKey().columns()).extracting(ColumnRef::sqlName)
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
                language: Language
                    @tableMethod(className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "getLanguage")
                    @reference(path: [{key: "film_language_id_fkey"}])
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
                actors: [Actor!]!
                    @tableMethod(className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "getActor")
                    @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
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
                actors: ActorConnection
                    @tableMethod(className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "getActor")
                    @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
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
                    @tableMethod(className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "getLanguage")
                    @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { film: Film }
            """,
            schema -> {
                var field = (TableMethodField) schema.field("Film", "language");
                assertThat(field.joinPath()).hasSize(1);
                assertThat(field.joinPath().get(0)).isInstanceOf(JoinStep.FkJoin.class);
            }),

        WITH_AUTO_FK_INFERENCE(
            "@tableMethod without @reference and exactly one FK between parent and target table → single-hop FkJoin auto-inferred",
            """
            type Film @table(name: "film") { title: String }
            type Inventory @table(name: "inventory") {
                film: Film @tableMethod(className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "getFilm")
            }
            type Query { inventory: Inventory }
            """,
            schema -> {
                var field = (TableMethodField) schema.field("Inventory", "film");
                assertThat(field.joinPath()).hasSize(1);
                assertThat(field.joinPath().get(0)).isInstanceOf(JoinStep.FkJoin.class);
                assertThat(((JoinStep.FkJoin) field.joinPath().get(0)).targetTable().tableName())
                    .isEqualToIgnoringCase("film");
            }),

        WITH_CONDITION_PATH(
            "@tableMethod + @reference(path:[{condition:...}]) resolves to a ConditionJoin",
            """
            type Actor @table(name: "actor") { firstName: String }
            type Film @table(name: "film") {
                actor: Actor
                    @tableMethod(className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "getActor")
                    @reference(path: [{condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "join"}}])
            }
            type Query { film: Film }
            """,
            schema -> {
                var field = (TableMethodField) schema.field("Film", "actor");
                assertThat(field.joinPath()).hasSize(1);
                assertThat(field.joinPath().get(0)).isInstanceOf(JoinStep.ConditionJoin.class);
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
        assertThat(stf.sourceKey().reader()).isInstanceOf(SourceKey.Reader.ColumnRead.class);
        assertThat(stf.sourceKey().columns()).extracting(ColumnRef::javaName).containsExactly("FILM_ID");
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
                language: Language @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getLanguage"})
            }
            type Query { film: Film }
            """,
            schema -> {
                var f = (ServiceTableField) schema.field("Film", "language");
                assertThat(f.returnType()).isInstanceOf(no.sikt.graphitron.rewrite.model.ReturnTypeRef.TableBoundReturnType.class);
                assertThat(f.returnType().returnTypeName()).isEqualTo("Language");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(ServiceTableField.class); }
        },

        NULL_SOURCE_KEY_PATH_ORIGIN_DEFAULTS_TO_FK_SIDE(
            "@service field with @reference {key:} (null parent SQL source) defaults FkJoin originTable to the FK-side table",
            """
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                language: Language
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getLanguage"})
                    @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { film: Film }
            """,
            schema -> {
                var f = (ServiceTableField) schema.field("Film", "language");
                assertThat(f.joinPath()).hasSize(1);
                var fk = (JoinStep.FkJoin) f.joinPath().get(0);
                // @service path passes null currentSourceSqlName into parsePathElement, so the
                // {key:} branch falls back to the FK-side ("film") as the implicit traversal
                // origin (forward-traversal default).
                assertThat(fk.originTable().tableName()).isEqualToIgnoringCase("film");
                assertThat(fk.targetTable().tableName()).isEqualToIgnoringCase("language");
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
            "@externalField on a @table parent → ComputedField with resolved MethodRef",
            """
            type Film @table(name: "film") {
                displayTitle: String @externalField(reference: {className: "no.sikt.graphitron.rewrite.TestExternalFieldStub", method: "rating"})
            }
            type Query { film: Film }
            """,
            schema -> {
                var field = schema.field("Film", "displayTitle");
                assertThat(field).isInstanceOf(ComputedField.class);
                var cf = (ComputedField) field;
                assertThat(cf.method()).isNotNull();
                assertThat(cf.method().className()).isEqualTo("no.sikt.graphitron.rewrite.TestExternalFieldStub");
                assertThat(cf.method().methodName()).isEqualTo("rating");
                assertThat(cf.method().params()).hasSize(1);
                assertThat(cf.method().params().get(0).source()).isInstanceOf(no.sikt.graphitron.rewrite.model.ParamSource.Table.class);
            }),

        METHOD_NOT_FOUND(
            "@externalField referencing a missing method → UnclassifiedField (AUTHOR_ERROR)",
            """
            type Film @table(name: "film") {
                displayTitle: String @externalField(reference: {className: "no.sikt.graphitron.rewrite.TestExternalFieldStub", method: "doesNotExist"})
            }
            type Query { film: Film }
            """,
            schema -> {
                var field = schema.field("Film", "displayTitle");
                assertThat(field).isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField.class);
                var unc = (no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField) field;
                assertThat(unc.kind()).isEqualTo(no.sikt.graphitron.rewrite.RejectionKind.AUTHOR_ERROR);
                assertThat(unc.reason()).contains("doesNotExist");
            }),

        NAME_COLLIDES_WITH_COLUMN(
            "@externalField name colliding with an existing column → UnclassifiedField (AUTHOR_ERROR)",
            """
            type Film @table(name: "film") {
                title: String @externalField(reference: {className: "no.sikt.graphitron.rewrite.TestExternalFieldStub", method: "rating"})
            }
            type Query { film: Film }
            """,
            schema -> {
                var field = schema.field("Film", "title");
                assertThat(field).isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField.class);
                var unc = (no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField) field;
                assertThat(unc.kind()).isEqualTo(no.sikt.graphitron.rewrite.RejectionKind.AUTHOR_ERROR);
                assertThat(unc.reason()).contains("collides with column 'title'");
            }),

        METHOD_DEFAULTS_TO_FIELD_NAME(
            "@externalField with reference omitting `method:` → method defaults to field name",
            """
            type Film @table(name: "film") {
                isEnglish: Boolean @externalField(reference: {className: "no.sikt.graphitron.rewrite.TestExternalFieldStub"})
            }
            type Query { film: Film }
            """,
            schema -> {
                var field = schema.field("Film", "isEnglish");
                assertThat(field).isInstanceOf(ComputedField.class);
                var cf = (ComputedField) field;
                assertThat(cf.method()).isNotNull();
                assertThat(cf.method().className()).isEqualTo("no.sikt.graphitron.rewrite.TestExternalFieldStub");
                assertThat(cf.method().methodName()).isEqualTo("isEnglish");
            }),

        METHOD_DEFAULT_NOT_FOUND(
            "@externalField omitting `method:` but field name has no matching static method → UnclassifiedField (AUTHOR_ERROR)",
            """
            type Film @table(name: "film") {
                noSuchExternalMethod: Boolean @externalField(reference: {className: "no.sikt.graphitron.rewrite.TestExternalFieldStub"})
            }
            type Query { film: Film }
            """,
            schema -> {
                var field = schema.field("Film", "noSuchExternalMethod");
                assertThat(field).isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField.class);
                var unc = (no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField) field;
                assertThat(unc.kind()).isEqualTo(no.sikt.graphitron.rewrite.RejectionKind.AUTHOR_ERROR);
                assertThat(unc.reason()).contains("noSuchExternalMethod");
            }),

        EMPTY_REFERENCE(
            "@externalField with an empty reference {} → UnclassifiedField (AUTHOR_ERROR, missing className)",
            """
            type Film @table(name: "film") {
                isEnglish: Boolean @externalField(reference: {})
            }
            type Query { film: Film }
            """,
            schema -> {
                var field = schema.field("Film", "isEnglish");
                assertThat(field).isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField.class);
                var unc = (no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField) field;
                assertThat(unc.kind()).isEqualTo(no.sikt.graphitron.rewrite.RejectionKind.AUTHOR_ERROR);
                assertThat(unc.reason()).contains("missing className");
            }),

        ARG_MAPPING_INERT_ON_EXTERNAL_FIELD(
            "R53: argMapping on @externalField → UnclassifiedField (structural-inertness rejection)",
            """
            type Film @table(name: "film") {
                computedRating: String @externalField(reference: {className: "no.sikt.graphitron.rewrite.TestExternalFieldStub", method: "rating", argMapping: "x: y"})
            }
            type Query { film: Film }
            """,
            schema -> {
                var field = schema.field("Film", "computedRating");
                assertThat(field).isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField.class);
                assertThat(((no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField) field).reason())
                    .contains("argMapping is not supported on @externalField");
            });

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
            // R36 Track B3 requires per-participant FK auto-discovery from the parent table
            // to each participant's table. Customer has a single FK to address
            // (customer.address_id), so the auto-discovery resolves cleanly.
            """
            interface Named { name: String }
            type Address implements Named @table(name: "address") { name: String @field(name: "ADDRESS") }
            type Customer @table(name: "customer") { address: Named }
            type Query { customer: Customer }
            """,
            schema -> assertThat(schema.field("Customer", "address")).isInstanceOf(InterfaceField.class)) {
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
            // FilmActor has a single FK to film (film_actor_film_id_fkey) and a single FK to
            // actor (film_actor_actor_id_fkey); the per-participant auto-discovery resolves
            // each branch's FK independently.
            """
            type Film @table(name: "film") { title: String }
            type Actor @table(name: "actor") { firstName: String @field(name: "FIRST_NAME") }
            union FilmOrActor = Film | Actor
            type FilmActor @table(name: "film_actor") { related: FilmOrActor }
            type Query { filmActor: FilmActor }
            """,
            schema -> assertThat(schema.field("FilmActor", "related")).isInstanceOf(UnionField.class)) {
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
            "@record parent + @service + scalar return → DEFERRED until batch-key lift through parent chain ships",
            """
            type FilmDetails @record { rating: String @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"}) }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var field = schema.field("FilmDetails", "rating");
                assertThat(field).isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField.class);
                var unc = (no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField) field;
                assertThat(unc.kind()).isEqualTo(no.sikt.graphitron.rewrite.RejectionKind.DEFERRED);
                assertThat(unc.reason()).contains("@record-typed parent", "lifted through the parent chain");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField.class); }
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
            "@record parent (typed POJO) + @table return type + @lookupKey → RecordLookupTableField with populated SourceKey",
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
                assertThat(f.sourceKey().reader()).isInstanceOf(SourceKey.Reader.ColumnRead.class);
                assertThat(f.sourceKey().columns()).isNotEmpty();
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
                assertThat(f.sourceKey().reader()).isInstanceOf(SourceKey.Reader.ColumnRead.class);
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

        RECORD_TABLE_FIELD_SINGLE_CARDINALITY(
            "@record parent + @table return + single cardinality → RecordTableField (R61 lifted Invariant #10)",
            """
            type Language @table(name: "language") { name: String }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
              language: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var f = (RecordTableField) schema.field("FilmDetails", "language");
                assertThat(f.returnType().wrapper()).isInstanceOf(no.sikt.graphitron.rewrite.model.FieldWrapper.Single.class);
                assertThat(f.sourceKey().reader()).isInstanceOf(SourceKey.Reader.ColumnRead.class);
                assertThat(f.emitsSingleRecordPerKey()).isTrue();
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(RecordTableField.class); }
        },

        SERVICE_TABLE_FIELD_ON_RECORD_PARENT(
            "@record parent + @service + @table return type → ServiceTableField",
            """
            type Language @table(name: "language") { name: String }
            type FilmDetails @record { language: Language @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getLanguage"}) }
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
        },

        // R3: @splitQuery on a @record-parent field is a structural no-op (the record handoff
        // already opens a new DataLoader-backed scope) and should surface as a build warning so
        // the developer who added it learns the directive changes nothing. Two fixtures cover
        // the regular @record-parent classification arms; the @sourceRow seam is covered in
        // SourceRowClassificationCase (one fixture, since both arms share the same emit-warning
        // seam).

        SPLIT_QUERY_ON_RECORD_PARENT_WARNS_TABLE_FIELD(
            "@splitQuery on @record-parent + @table return → RecordTableField + build warning naming the field coordinate",
            """
            type Language @table(name: "language") { name: String }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
              language: Language @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                assertThat(schema.field("FilmDetails", "language")).isInstanceOf(RecordTableField.class);
                assertThat(schema.warnings())
                    .extracting(BuildWarning::message)
                    .anyMatch(m -> m.contains("FilmDetails.language")
                        && m.contains("@splitQuery is redundant on a @record-parent field"));
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(RecordTableField.class); }
        },

        SPLIT_QUERY_ON_RECORD_PARENT_WARNS_LOOKUP_FIELD(
            "@splitQuery on @record-parent + @lookupKey + @table return → RecordLookupTableField + build warning",
            """
            type Language @table(name: "language") { name: String }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
              language(language_id: ID! @lookupKey): Language
                @splitQuery
                @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                assertThat(schema.field("FilmDetails", "language")).isInstanceOf(RecordLookupTableField.class);
                assertThat(schema.warnings())
                    .extracting(BuildWarning::message)
                    .anyMatch(m -> m.contains("FilmDetails.language")
                        && m.contains("@splitQuery is redundant on a @record-parent field"));
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(RecordLookupTableField.class); }
        },

        // R3: holistic-surfacing rule. An unrelated rejection on the same field (here, an
        // unresolvable @reference) must not suppress the @splitQuery redundancy advisory ; the
        // developer needs both diagnostics, not just whichever fires first. Locks the warning
        // emission to the table-bound-return seam, before any rejection guard.
        SPLIT_QUERY_WARNS_ALONGSIDE_RECORD_PARENT_REJECTION(
            "@splitQuery on @record-parent with unresolvable @reference → UnclassifiedField + build warning still fires",
            """
            type Language @table(name: "language") { name: String }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
              language: Language @splitQuery @reference(path: [{key: "no_such_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                assertThat(schema.field("FilmDetails", "language")).isInstanceOf(UnclassifiedField.class);
                assertThat(schema.warnings())
                    .extracting(BuildWarning::message)
                    .anyMatch(m -> m.contains("FilmDetails.language")
                        && m.contains("@splitQuery is redundant on a @record-parent field"));
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        SINGLE_RECORD_PAYLOAD_DATA_FIELD_ORPHAN(
            "Plain Object carrier (no @record/@table) wrapping a single @table-element data field, "
                + "consumed by a Query field with no producing mutation → orphan carrier; no "
                + "producer site registers the data field, and graphql-java's never-traverse-"
                + "unproduced-fields guarantee makes the missing registration structurally safe.",
            """
            type Film @table(name: "film") { title: String }
            type FilmPayload { films: [Film!] }
            type Query { wrappedFilms: FilmPayload }
            """,
            schema -> assertThat(schema.field("FilmPayload", "films"))
                .as("orphan-carrier data field has no fieldRegistry entry after R158")
                .isNull()) {
            @Override public Set<Class<?>> variants() { return Set.of(ChildField.SingleRecordTableField.class); }
        },

        SINGLE_RECORD_IDENTITY_FIELD_ORPHAN(
            "R178 Phase 4: a plain Object carrier wrapping a single record-element data field "
                + "(@record-backed ResultType) consumed by a Query field with no producing mutation "
                + "→ orphan carrier; the data field stays unregistered (record-element identity "
                + "passthrough is now handled by the unified per-field classifier on producer-bound "
                + "parents).",
            """
            type FilmDto @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
                title: String
            }
            type FilmDtoPayload { film: FilmDto }
            type Query { wrappedFilm: FilmDtoPayload }
            """,
            schema -> assertThat(schema.field("FilmDtoPayload", "film"))
                .as("orphan record-element carrier data field has no fieldRegistry entry after R178")
                .isNull()) {
            @Override public Set<Class<?>> variants() { return Set.of(); }
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

    // ===== @sourceRow classifier matrix (R110) =====

    /**
     * Classifier-level coverage for the {@code @sourceRow} directive — the lifter path
     * for {@code @record} parents whose backing class has no jOOQ FK metadata. Each case
     * pins one of the resolver invariants in
     * {@link no.sikt.graphitron.rewrite.SourceRowDirectiveResolver}: parent shape (Inv #1),
     * lifter parameter assignability (Inv #2), lifter return type (Inv #3), arity / column-class
     * match against the derived parent-side tuple (Inv #4), {@code @reference} composition
     * vs. leaf-PK derivation (Inv #5), {@code @asConnection} reject (Inv #9). Single-cardinality
     * (Inv #10) is gated by the validator (existing
     * {@code SplitRowsMethodEmitter.unsupportedReason} arm) and tested separately under the
     * validation tier.
     *
     * <p>Lifter fixture methods live in {@link TestLifterStub}.
     */
    enum SourceRowClassificationCase implements ClassificationCase {
        POJO_PARENT_VALID_ROW1_LIST(
            "Pojo parent + valid Row1<Integer> lifter + @reference, list return → RecordTableField with LifterPathKeyed",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
              inventories: [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow1Integer")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var f = (RecordTableField) schema.field("FilmDetails", "inventories");
                var sk = f.sourceKey();
                assertThat(sk.reader()).isInstanceOf(SourceKey.Reader.SourceRowsCall.class);
                var lifter = ((SourceKey.Reader.SourceRowsCall) sk.reader()).lifter();
                assertThat(f.joinPath()).hasSize(1);
                assertThat(f.joinPath()).isEqualTo(sk.path());
                assertThat(sk.columns()).hasSize(1);
                assertThat(sk.columns().get(0).sqlName()).isEqualTo("film_id");
                assertThat(lifter.declaringClass().reflectionName()).isEqualTo("no.sikt.graphitron.rewrite.TestLifterStub");
                assertThat(lifter.methodName()).isEqualTo("dummyRow1Integer");
                // First hop's target table is film (the FK's referenced side); the leaf is
                // inventory and lives on the field's returnType, not on the path.
                assertThat(f.returnType().table().tableName()).isEqualTo("inventory");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(RecordTableField.class); }
        },

        POJO_PARENT_VALID_PLUS_LOOKUPKEY(
            "Pojo parent + valid Row1 lifter + @reference + @lookupKey arg → RecordLookupTableField with LifterPathKeyed and lookupMapping populated",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
              inventories(inventory_id: [Int!]! @lookupKey): [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow1Integer")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var f = (RecordLookupTableField) schema.field("FilmDetails", "inventories");
                assertThat(f.sourceKey().reader()).isInstanceOf(SourceKey.Reader.SourceRowsCall.class);
                assertThat(f.lookupMapping()).isNotNull();
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(RecordLookupTableField.class); }
        },

        NULL_FQ_CLASS_NAME(
            "Pojo parent (null fqClassName) + lifter → UnclassifiedField AUTHOR_ERROR (Invariant #1: parent must declare backing class)",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails @record {
              inventories: [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow1Integer")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var unc = (UnclassifiedField) schema.field("FilmDetails", "inventories");
                assertThat(unc.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
                assertThat(unc.reason()).contains("@sourceRow").contains("backing class");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        TABLE_PARENT_REJECT(
            "@table parent + lifter → UnclassifiedField AUTHOR_ERROR (lifter is for @record parents)",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type Film @table(name: "film") {
              inventories: [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow1Integer")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Query { film: Film }
            """,
            schema -> {
                var unc = (UnclassifiedField) schema.field("Film", "inventories");
                assertThat(unc.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
                assertThat(unc.reason()).contains("@sourceRow").contains("@record").contains("@reference");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        JOOQ_TABLE_RECORD_PARENT_REJECT(
            "JooqTableRecordType parent + lifter → UnclassifiedField AUTHOR_ERROR pointing at the catalog FK",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord"}) {
              inventories: [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow1Integer")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var unc = (UnclassifiedField) schema.field("FilmDetails", "inventories");
                assertThat(unc.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
                assertThat(unc.reason()).contains("@sourceRow").contains("jOOQ-backed");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        JAVA_RECORD_PARENT_ADMIT(
            "JavaRecordType parent (non-null fqClassName) + lifter → RecordTableField (admitted same as PojoResultType)",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.TestRecordDto"}) {
              inventories: [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "javaRecordRow1Integer")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var f = (RecordTableField) schema.field("FilmDetails", "inventories");
                assertThat(f.sourceKey().reader()).isInstanceOf(SourceKey.Reader.SourceRowsCall.class);
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(RecordTableField.class); }
        },

        MISSING_LIFTER_CLASS(
            "Lifter className that doesn't load → UnclassifiedField AUTHOR_ERROR with 'could not be loaded'",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
              inventories: [Inventory!]!
                @sourceRow(className: "com.example.Nonexistent", method: "doesNotMatter")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var unc = (UnclassifiedField) schema.field("FilmDetails", "inventories");
                assertThat(unc.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
                assertThat(unc.reason()).contains("com.example.Nonexistent").contains("could not be loaded");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        MISSING_LIFTER_METHOD(
            "Lifter method name not on the class → UnclassifiedField AUTHOR_ERROR with candidate hint",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
              inventories: [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummiRow1Integer")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var unc = (UnclassifiedField) schema.field("FilmDetails", "inventories");
                assertThat(unc.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
                assertThat(unc.reason()).contains("dummiRow1Integer").contains("dummyRow1Integer");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        WRONG_RETURN_LONG(
            "Lifter return type is Long (not Row<N>) → UnclassifiedField AUTHOR_ERROR (Invariant #3)",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
              inventories: [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "wrongReturnLong")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var unc = (UnclassifiedField) schema.field("FilmDetails", "inventories");
                assertThat(unc.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
                assertThat(unc.reason()).contains("must return org.jooq.Row");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        WRONG_PARAM_TYPE(
            "Lifter parameter type incompatible with parent backing class → UnclassifiedField AUTHOR_ERROR (Invariant #2)",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
              inventories: [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "wrongParamType")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var unc = (UnclassifiedField) schema.field("FilmDetails", "inventories");
                assertThat(unc.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
                assertThat(unc.reason()).contains("not assignable from").contains("DummyRecord");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        ARITY_MISMATCH_PATH(
            "Lifter Row arity 2 against single-column first-hop source-side → UnclassifiedField AUTHOR_ERROR (Invariant #4, path-keyed arity)",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
              inventories: [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow2IntInt")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var unc = (UnclassifiedField) schema.field("FilmDetails", "inventories");
                assertThat(unc.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
                assertThat(unc.reason()).contains("arity 2").contains("first-hop source-side");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        COLUMN_CLASS_MISMATCH(
            "Lifter Row1<String> with target column typed Integer → UnclassifiedField AUTHOR_ERROR (Invariant #4)",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
              inventories: [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow1String")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var unc = (UnclassifiedField) schema.field("FilmDetails", "inventories");
                assertThat(unc.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
                assertThat(unc.reason()).contains("does not match first-hop source-side column").contains("film_id");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        WILDCARD_RETURN_REJECT(
            "Lifter Row1<? extends Number> wildcard type-arg → UnclassifiedField AUTHOR_ERROR (Invariant #4 wildcard arm)",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
              inventories: [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow1WildcardNumber")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var unc = (UnclassifiedField) schema.field("FilmDetails", "inventories");
                assertThat(unc.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
                assertThat(unc.reason()).contains("wildcard").contains("film_id");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        REFERENCE_PARSE_FAILURE(
            "@sourceRow + @reference with an unknown FK key → UnclassifiedField AUTHOR_ERROR; the @reference parse error surfaces directly without re-validating against the lifter (R110 spec).",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
              inventories: [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow1Integer")
                @reference(path: [{key: "non_existent_fk_key"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var unc = (UnclassifiedField) schema.field("FilmDetails", "inventories");
                assertThat(unc.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
                assertThat(unc.reason()).contains("@sourceRow").contains("@reference parse error");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        AS_CONNECTION_REJECT(
            "@sourceRow on @asConnection field → UnclassifiedField AUTHOR_ERROR (Invariant #9)",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
              inventories: [Inventory!]!
                @asConnection
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow1Integer")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var unc = (UnclassifiedField) schema.field("FilmDetails", "inventories");
                assertThat(unc.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
                assertThat(unc.reason()).contains("@asConnection");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        WITH_FIELD_NAME_NON_INTERACTION(
            "Pojo parent + lifter + @field(name:) on the field → classifier ignores @field name; @reference path resolves independently",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
              inventories: [Inventory!]!
                @field(name: "irrelevant_for_lifter")
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow1Integer")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var f = (RecordTableField) schema.field("FilmDetails", "inventories");
                assertThat(f.sourceKey().reader()).isInstanceOf(SourceKey.Reader.SourceRowsCall.class);
                assertThat(f.sourceKey().columns()).extracting(no.sikt.graphitron.rewrite.model.ColumnRef::sqlName)
                    .containsExactly("film_id");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(RecordTableField.class); }
        },

        WITH_FIELD_LEVEL_CONDITION(
            "Pojo parent + lifter + @condition on the field → RecordTableField; tfc.filters() carries the resolved ConditionFilter",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
              inventories: [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow1Integer")
                @reference(path: [{key: "inventory_film_id_fkey"}])
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "lifterFieldCondition"})
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var f = (RecordTableField) schema.field("FilmDetails", "inventories");
                assertThat(f.sourceKey().reader()).isInstanceOf(SourceKey.Reader.SourceRowsCall.class);
                assertThat(f.filters())
                    .filteredOn(filter -> filter instanceof ConditionFilter)
                    .extracting(filter -> ((ConditionFilter) filter).methodName())
                    .containsExactly("lifterFieldCondition");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(RecordTableField.class); }
        },

        WITH_ORDER_BY_ARG(
            "Pojo parent + lifter + @orderBy arg → RecordTableField; tfc.orderBy() carries the resolved OrderBySpec.Argument",
            """
            enum InventoryOrderField { ID @order(primaryKey: true) }
            enum Direction { ASC DESC }
            input InventoryOrder { sortField: InventoryOrderField! direction: Direction! }
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
              inventories(order: InventoryOrder @orderBy): [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow1Integer")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var f = (RecordTableField) schema.field("FilmDetails", "inventories");
                assertThat(f.sourceKey().reader()).isInstanceOf(SourceKey.Reader.SourceRowsCall.class);
                assertThat(f.orderBy()).isInstanceOf(OrderBySpec.Argument.class);
                var orderBy = (OrderBySpec.Argument) f.orderBy();
                assertThat(orderBy.typeName()).isEqualTo("InventoryOrder");
                assertThat(orderBy.namedOrders()).hasSize(1);
                assertThat(orderBy.namedOrders().get(0).name()).isEqualTo("ID");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(RecordTableField.class); }
        },

        SCALAR_RETURN_REJECT(
            "Pojo parent + @sourceRow on a scalar-return field → UnclassifiedField AUTHOR_ERROR (directive applies only to @table-bound returns)",
            """
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
              rating: String
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow1Integer")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var unc = (UnclassifiedField) schema.field("FilmDetails", "rating");
                assertThat(unc.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
                assertThat(unc.reason()).contains("@sourceRow").contains("@table-bound");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        LEAF_PK_NO_REFERENCE(
            "Pojo parent + @sourceRow alone (no @reference) → RecordTableField with LifterLeafKeyed; lifter RowN matches the leaf target's PK columns directly.",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
              inventories: [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow1Integer")
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var f = (RecordTableField) schema.field("FilmDetails", "inventories");
                var sk = f.sourceKey();
                assertThat(sk.reader()).isInstanceOf(SourceKey.Reader.SourceRowsCall.class);
                assertThat(sk.columns()).extracting(no.sikt.graphitron.rewrite.model.ColumnRef::sqlName)
                    .containsExactly("inventory_id");
                // Leaf-PK variant: path contains a single LiftedHop pointing at the leaf target.
                assertThat(sk.path()).hasSize(1);
                assertThat(sk.path().get(0)).isInstanceOf(JoinStep.LiftedHop.class);
                assertThat(((JoinStep.LiftedHop) sk.path().get(0)).targetTable().tableName()).isEqualTo("inventory");
                assertThat(f.joinPath()).hasSize(1);
                assertThat(f.joinPath().get(0)).isSameAs(sk.path().get(0));
                assertThat(((SourceKey.Reader.SourceRowsCall) sk.reader()).lifter().methodName())
                    .isEqualTo("dummyRow1Integer");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(RecordTableField.class); }
        },

        LEAF_PK_ARITY_MISMATCH(
            "Lifter Row arity 2 against single-column leaf-PK → UnclassifiedField AUTHOR_ERROR; the diagnostic distinguishes leaf-PK from first-hop source-side (R110 spec).",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
              inventories: [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow2IntInt")
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var unc = (UnclassifiedField) schema.field("FilmDetails", "inventories");
                assertThat(unc.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
                assertThat(unc.reason()).contains("arity 2").contains("leaf-PK");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        // R3: @splitQuery on a @sourceRow @record-parent field is just as silently no-op as on
        // the regular @record-parent path — the lifter-keyed DataLoader already opens a new
        // scope. One fixture is enough since both arms (RecordTableField, RecordLookupTableField)
        // share the same emit-warning seam.
        SPLIT_QUERY_WARNS_ON_SOURCE_ROW(
            "@splitQuery on @sourceRow @record-parent field → RecordTableField + build warning naming the field coordinate",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
              inventories: [Inventory!]!
                @splitQuery
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow1Integer")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                assertThat(schema.field("FilmDetails", "inventories")).isInstanceOf(RecordTableField.class);
                assertThat(schema.warnings())
                    .extracting(BuildWarning::message)
                    .anyMatch(m -> m.contains("FilmDetails.inventories")
                        && m.contains("@splitQuery is redundant on a @record-parent field"));
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(RecordTableField.class); }
        },

        // R3: holistic-surfacing rule. The @sourceRow seam mirrors the regular @record-parent
        // path: an unrelated lifter-signature rejection (Inv #3 in SourceRowDirectiveResolver)
        // must not suppress the @splitQuery redundancy advisory.
        SPLIT_QUERY_WARNS_ALONGSIDE_SOURCE_ROW_REJECTION(
            "@splitQuery on @sourceRow with bad lifter signature → UnclassifiedField + build warning still fires",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
              inventories: [Inventory!]!
                @splitQuery
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "wrongReturnLong")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var unc = (UnclassifiedField) schema.field("FilmDetails", "inventories");
                assertThat(unc.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
                assertThat(schema.warnings())
                    .extracting(BuildWarning::message)
                    .anyMatch(m -> m.contains("FilmDetails.inventories")
                        && m.contains("@splitQuery is redundant on a @record-parent field"));
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        };

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        SourceRowClassificationCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() { return Set.of(); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(SourceRowClassificationCase.class)
    void sourceRowClassification(SourceRowClassificationCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== Accessor-derived SourceKey classifier matrix (R60) =====

    /**
     * Classifier-level coverage for the auto-derivation that runs on {@code @record} parents
     * whose backing class exposes a typed zero-arg accessor returning a concrete jOOQ
     * {@code TableRecord} subtype. Pins the
     * {@link no.sikt.graphitron.rewrite.FieldBuilder#deriveAccessorRecordParentSource} match
     * rule across the cross-product corners: list-field × list / set accessor, single-field ×
     * single accessor, ambiguous candidates, cardinality mismatches, and heterogeneous element
     * types that fall through to the rewritten three-option AUTHOR_ERROR.
     *
     * <p>Backing-class fixtures live in
     * {@link no.sikt.graphitron.codereferences.dummyreferences.AccessorPayloads}.
     */
    enum AccessorDerivedSourceCase implements ClassificationCase {
        ACCESSOR_ROWKEYED_MANY_LIST_FIELD_LIST_ACCESSOR(
            "List field + list-of-TableRecord accessor → RecordTableField with AccessorCall + Cardinality.MANY",
            """
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Payload @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.AccessorPayloads$ListPayload"}) {
              films: [Film!]!
            }
            type Query { payload: Payload }
            """,
            schema -> {
                var f = (RecordTableField) schema.field("Payload", "films");
                var sk = f.sourceKey();
                assertThat(sk.reader()).isInstanceOf(SourceKey.Reader.AccessorCall.class);
                assertThat(sk.cardinality()).isEqualTo(SourceKey.Cardinality.MANY);
                assertThat(((SourceKey.Reader.AccessorCall) sk.reader()).accessor().methodName()).isEqualTo("films");
                assertThat(sk.columns()).hasSize(1);
                assertThat(sk.columns().get(0).sqlName()).isEqualTo("film_id");
                assertThat(f.joinPath()).hasSize(1);
                assertThat(f.joinPath().get(0)).isSameAs(sk.path().get(0));
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(RecordTableField.class); }
        },

        ACCESSOR_ROWKEYED_MANY_LIST_FIELD_SET_ACCESSOR(
            "List field + set-of-TableRecord accessor → RecordTableField with AccessorCall + Cardinality.MANY",
            """
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Payload @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.AccessorPayloads$SetPayload"}) {
              films: [Film!]!
            }
            type Query { payload: Payload }
            """,
            schema -> {
                var f = (RecordTableField) schema.field("Payload", "films");
                var sk = f.sourceKey();
                assertThat(sk.reader()).isInstanceOf(SourceKey.Reader.AccessorCall.class);
                assertThat(sk.cardinality()).isEqualTo(SourceKey.Cardinality.MANY);
                // The Set<X> vs List<X> split inside Many is not preserved on the SourceKey; emit
                // is uniform via Iterable. The fixture still exercises the Set classifier path.
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(RecordTableField.class); }
        },

        ACCESSOR_ROWKEYED_SINGLE_SINGLE_FIELD_SINGLE_ACCESSOR(
            "Single field + single-TableRecord accessor → RecordTableField with AccessorCall + Cardinality.ONE",
            """
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Payload @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.AccessorPayloads$SinglePayload"}) {
              film: Film
            }
            type Query { payload: Payload }
            """,
            schema -> {
                var f = (RecordTableField) schema.field("Payload", "film");
                var sk = f.sourceKey();
                assertThat(sk.reader()).isInstanceOf(SourceKey.Reader.AccessorCall.class);
                assertThat(sk.cardinality()).isEqualTo(SourceKey.Cardinality.ONE);
                assertThat(((SourceKey.Reader.AccessorCall) sk.reader()).accessor().methodName()).isEqualTo("film");
                assertThat(sk.columns()).hasSize(1);
                assertThat(sk.columns().get(0).sqlName()).isEqualTo("film_id");
                assertThat(f.joinPath()).hasSize(1);
                assertThat(f.joinPath().get(0)).isSameAs(sk.path().get(0));
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(RecordTableField.class); }
        },

        ACCESSOR_ROWKEYED_REJECTS_AMBIGUOUS(
            "Two accessors returning List<FilmRecord> for the same @table → UnclassifiedField (ambiguous)",
            """
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Payload @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.AccessorPayloads$AmbiguousListPayload"}) {
              films: [Film!]!
            }
            type Query { payload: Payload }
            """,
            schema -> {
                var unc = (UnclassifiedField) schema.field("Payload", "films");
                assertThat(unc.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
                assertThat(unc.reason()).contains("more than one typed accessor");
                assertThat(unc.reason()).contains("films");
                assertThat(unc.reason()).contains("getFilms");
                assertThat(unc.reason()).contains("@sourceRow");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        ACCESSOR_ROWKEYED_REJECTS_CARDINALITY_LIST_FIELD_SINGLE_ACCESSOR(
            "List field + single-record accessor → UnclassifiedField (cardinality mismatch)",
            """
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Payload @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.AccessorPayloads$SingleAccessorOnListField"}) {
              films: [Film!]!
            }
            type Query { payload: Payload }
            """,
            schema -> {
                var unc = (UnclassifiedField) schema.field("Payload", "films");
                assertThat(unc.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
                assertThat(unc.reason()).contains("list field 'films'");
                assertThat(unc.reason()).contains("returning a single record");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        ACCESSOR_ROWKEYED_FIELD_NAME_REMAPS_ACCESSOR(
            "@field(name:) on a free-form @record parent remaps the accessor base name → admits with the directive-named accessor",
            """
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Payload @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.AccessorPayloads$RemappedPayload"}) {
              film: Film @field(name: "filmRecord")
            }
            type Query { payload: Payload }
            """,
            schema -> {
                var f = (RecordTableField) schema.field("Payload", "film");
                var sk = f.sourceKey();
                assertThat(sk.reader()).isInstanceOf(SourceKey.Reader.AccessorCall.class);
                assertThat(sk.cardinality()).isEqualTo(SourceKey.Cardinality.ONE);
                // The carried method name is the actual accessor name (the directive value),
                // not the SDL field name.
                assertThat(((SourceKey.Reader.AccessorCall) sk.reader()).accessor().methodName())
                    .isEqualTo("filmRecord");
                assertThat(sk.columns()).hasSize(1);
                assertThat(sk.columns().get(0).sqlName()).isEqualTo("film_id");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(RecordTableField.class); }
        },

        ACCESSOR_ROWKEYED_FIELD_NAME_REJECTS_WITHOUT_DIRECTIVE(
            "Divergent accessor name with no @field(name:) on a free-form @record parent → falls through to the three-option AUTHOR_ERROR",
            """
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Payload @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.AccessorPayloads$RemappedPayload"}) {
              film: Film
            }
            type Query { payload: Payload }
            """,
            schema -> {
                var unc = (UnclassifiedField) schema.field("Payload", "film");
                assertThat(unc.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
                assertThat(unc.reason()).contains("typed accessor");
                assertThat(unc.reason()).contains("@sourceRow");
                assertThat(unc.reason()).contains("typed jOOQ TableRecord");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        ACCESSOR_ROWKEYED_REJECTS_HETEROGENEOUS_ELEMENT(
            "Accessor element TableRecord doesn't match field's @table → falls through to three-option AUTHOR_ERROR",
            """
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Payload @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.AccessorPayloads$HeterogeneousElementPayload"}) {
              films: Film
            }
            type Query { payload: Payload }
            """,
            schema -> {
                var unc = (UnclassifiedField) schema.field("Payload", "films");
                assertThat(unc.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
                // Falls through to the rewritten three-option message; the typed-accessor and
                // @sourceRow and @table TableRecord options should all be named.
                assertThat(unc.reason()).contains("typed accessor");
                assertThat(unc.reason()).contains("@sourceRow");
                assertThat(unc.reason()).contains("typed jOOQ TableRecord");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        };

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        AccessorDerivedSourceCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() { return Set.of(); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(AccessorDerivedSourceCase.class)
    void accessorDerivedSourceClassification(AccessorDerivedSourceCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== RecordTableMethodField (R43 commit 4 — child @tableMethod on @record parent) =====

    /**
     * Classifier coverage for {@link RecordTableMethodField}: child {@code @tableMethod} on a
     * {@code @record} (non-table) parent. Two admit arms (FK-auto-derive on a JooqTableRecord-
     * backed parent + lifter-derived via {@code @sourceRow} on a free-form DTO parent) and one
     * rejection (free-form DTO without {@code @sourceRow}). Emit stays stubbed at commit 4;
     * end-to-end emit + execution coverage lands with commit 5.
     */
    enum RecordTableMethodFieldCase implements ClassificationCase {
        JOOQ_TABLE_RECORD_PARENT_AUTO_FK(
            "JooqTableRecordType @record parent + @tableMethod with single FK → RecordTableMethodField, auto-FK source-key",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord"}) {
              inventories: [Inventory!]! @tableMethod(className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "getInventory")
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var f = (RecordTableMethodField) schema.field("FilmDetails", "inventories");
                assertThat(f.joinPath()).hasSize(1);
                assertThat(f.joinPath().get(0)).isInstanceOf(JoinStep.FkJoin.class);
                assertThat(f.sourceKey().reader()).isInstanceOf(SourceKey.Reader.ColumnRead.class);
                assertThat(f.sourceKey().cardinality()).isEqualTo(SourceKey.Cardinality.MANY);
                assertThat(f.method().methodName()).isEqualTo("getInventory");
            }),

        JOOQ_TABLE_RECORD_PARENT_EXPLICIT_REFERENCE(
            "JooqTableRecordType @record parent + @tableMethod + @reference(path:) → RecordTableMethodField with explicit FK path",
            """
            type Language @table(name: "language") { name: String }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord"}) {
              language: Language
                @tableMethod(className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "getLanguage")
                @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var f = (RecordTableMethodField) schema.field("FilmDetails", "language");
                assertThat(f.joinPath()).hasSize(1);
                assertThat(f.joinPath().get(0)).isInstanceOf(JoinStep.FkJoin.class);
                assertThat(f.sourceKey().reader()).isInstanceOf(SourceKey.Reader.ColumnRead.class);
            }),

        FREE_FORM_PARENT_NO_SOURCEROW_REJECTED(
            "Pojo parent (no FK metadata) + @tableMethod without @sourceRow → UnclassifiedField AUTHOR_ERROR pointing at the lift options",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
              inventories: [Inventory!]! @tableMethod(className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "getInventory")
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query { film: Film }
            """,
            schema -> {
                var unc = (UnclassifiedField) schema.field("FilmDetails", "inventories");
                assertThat(unc.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
                assertThat(unc.reason()).contains("@tableMethod").contains("@sourceRow").contains("TableRecord");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        };

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        RecordTableMethodFieldCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() { return Set.of(RecordTableMethodField.class); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(RecordTableMethodFieldCase.class)
    void recordTableMethodFieldClassification(RecordTableMethodFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== ResultType backing-class classification =====

    enum ResultTypeCase implements ClassificationCase {
        NO_CLASS(
            "@record with no backing class → PojoResultType.NoBacking",
            """
            type FilmDetails @record { id: ID }
            type Query { foo: String }
            """,
            schema -> {
                var t = (PojoResultType.NoBacking) schema.type("FilmDetails");
                assertThat(t.fqClassName()).isNull();
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(PojoResultType.NoBacking.class); }
        },

        POJO_CLASS(
            "@record with plain Java class → PojoResultType.Backed with fqClassName",
            """
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) { id: ID }
            type Query { foo: String }
            """,
            schema -> {
                var t = (PojoResultType.Backed) schema.type("FilmDetails");
                assertThat(t.fqClassName()).isEqualTo("no.sikt.graphitron.codereferences.dummyreferences.DummyRecord");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(PojoResultType.Backed.class); }
        },

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
        },

        ARG_MAPPING_INERT_ON_RECORD(
            "R53: argMapping on @record → UnclassifiedType (structural-inertness rejection)",
            """
            type FilmDetails @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.TestRecordDto", argMapping: "x: y"}) { id: ID }
            type Query { foo: String }
            """,
            schema -> {
                var t = (UnclassifiedType) schema.type("FilmDetails");
                assertThat(t.reason()).contains("argMapping is not supported on @record");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(); }
        };

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        ResultTypeCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() {
            // Per-case overrides above; this default is unused since NO_CLASS and POJO_CLASS
            // declare their own concrete sub-permit, and the JavaRecordType / JooqTableRecordType
            // / UnclassifiedType cases override too. Kept for the case-of-last-resort.
            return Set.of();
        }
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
                assertThat(((no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) f.lookupMapping()).args()).hasSize(1);
                assertThat(((no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) f.lookupMapping()).args().get(0).argName()).isEqualTo("actor_id");
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
                    @tableMethod(className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "getLanguageWithContext", contextArguments: ["tenantId"])
                    @reference(path: [{key: "film_language_id_fkey"}])
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
            "QueryLookupTableField populates LookupMapping with one ScalarLookupArg per @lookupKey arg",
            """
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query {
                filmById(film_id: [ID] @lookupKey): [Film!]!
            }
            """,
            schema -> {
                var f = (QueryField.QueryLookupTableField) schema.field("Query", "filmById");
                assertThat(f.lookupMapping().targetTable().tableName()).isEqualTo("film");
                var cm = (no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) f.lookupMapping();
                assertThat(cm.args())
                    .hasSize(1)
                    .extracting(no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping.LookupArg::argName)
                    .containsExactly("film_id");
                var arg = (no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping.LookupArg.ScalarLookupArg) cm.args().get(0);
                assertThat(arg.targetColumn().sqlName()).isEqualTo("film_id");
                assertThat(arg.list()).isTrue();
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

        ARG_CONDITION_ARGMAPPING_DUAL_BOUND(
            "R53: arg-level @condition with argMapping — Java-name override coexists with @field(name:) column binding on the same arg",
            """
            type Language @table(name: "language") { name: String }
            type Query {
                languages(cityNames: String @field(name: "name")
                    @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "argConditionRenamed", argMapping: "city: cityNames"})):
                    [Language!]!
            }
            """,
            schema -> {
                var f = (QueryField.QueryTableField) schema.field("Query", "languages");
                // Implicit GeneratedConditionFilter: @field(name: "name") drives the column-binding axis.
                var gcf = (GeneratedConditionFilter) f.filters().get(0);
                assertThat(gcf.bodyParams()).hasSize(1);
                var bp = (no.sikt.graphitron.rewrite.model.BodyParam.Eq) gcf.bodyParams().get(0);
                assertThat(bp.name()).isEqualTo("cityNames");
                assertThat(bp.column().sqlName()).isEqualTo("name");
                // Explicit ConditionFilter: argMapping drives the Java-binding axis (city ← cityNames).
                var cond = (ConditionFilter) f.filters().get(1);
                assertThat(cond.methodName()).isEqualTo("argConditionRenamed");
                var renamedParam = cond.params().stream()
                    .filter(p -> p instanceof no.sikt.graphitron.rewrite.model.MethodRef.Param.Typed t
                        && t.source() instanceof no.sikt.graphitron.rewrite.model.ParamSource.Arg)
                    .map(p -> (no.sikt.graphitron.rewrite.model.MethodRef.Param.Typed) p)
                    .findFirst().orElseThrow();
                assertThat(renamedParam.name()).isEqualTo("city");
                assertThat(((no.sikt.graphitron.rewrite.model.ParamSource.Arg) renamedParam.source()).graphqlArgName())
                    .isEqualTo("cityNames");
            }),

        FIELD_CONDITION_ARGMAPPING(
            "R53: field-level @condition with argMapping — both Java parameters bind to differently-named GraphQL args",
            """
            type Language @table(name: "language") { name: String }
            type Query {
                languages(cityNames: String @field(name: "name"), countryId: String @field(name: "name")):
                    [Language!]! @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "fieldConditionRenamed", argMapping: "city: cityNames, country: countryId"})
            }
            """,
            schema -> {
                var f = (QueryField.QueryTableField) schema.field("Query", "languages");
                var cond = (ConditionFilter) f.filters().get(1);
                assertThat(cond.methodName()).isEqualTo("fieldConditionRenamed");
                var argParams = cond.params().stream()
                    .filter(p -> p instanceof no.sikt.graphitron.rewrite.model.MethodRef.Param.Typed t
                        && t.source() instanceof no.sikt.graphitron.rewrite.model.ParamSource.Arg)
                    .map(p -> (no.sikt.graphitron.rewrite.model.MethodRef.Param.Typed) p)
                    .toList();
                assertThat(argParams).hasSize(2);
                assertThat(argParams.get(0).name()).isEqualTo("city");
                assertThat(((no.sikt.graphitron.rewrite.model.ParamSource.Arg) argParams.get(0).source()).graphqlArgName())
                    .isEqualTo("cityNames");
                assertThat(argParams.get(1).name()).isEqualTo("country");
                assertThat(((no.sikt.graphitron.rewrite.model.ParamSource.Arg) argParams.get(1).source()).graphqlArgName())
                    .isEqualTo("countryId");
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

        LOOKUP_KEY_ON_INPUT_FIELD_RETIRED_R144(
            "@lookupKey on any INPUT_FIELD_DEFINITION → classify-time retirement error (R144); the diagnostic names the migration path",
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
                // R144: @lookupKey on INPUT_FIELD_DEFINITION is retired across mutation and query
                // surfaces. The diagnostic mentions the migration target (filter-by-default on
                // mutation inputs, @value for UPDATE assignment columns).
                // For Query, the @lookupKey moves to the arg if lookup behavior is intended;
                // see LOOKUP_KEY_ON_NODEID_INPUT_FIELD_ADMITTED for that shape.
                var f = (no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField) schema.field("Query", "filmByKey");
                assertThat(f.reason())
                    .contains("R144");
            }),

        LOOKUP_KEY_ON_NODEID_INPUT_FIELD_ADMITTED(
            "R130 + R144: @lookupKey on the ARGUMENT_DEFINITION of a same-table singular "
                + "`id: ID! @nodeId` @table input arg → admitted via the extraction-propagation "
                + "path. R144 retires @lookupKey on the input field; the arg-level @lookupKey "
                + "drives the lookup-binding walk over every admissible input field. The carrier "
                + "classifies as InputField.ColumnField with NodeIdDecodeKeys extraction; "
                + "buildLookupBindings reads cf.extraction() directly so the resolver-supplied "
                + "decode method survives the binding-build (R130 fix at source). The MapGroup "
                + "carries one MapBinding whose extraction is the carrier's NodeIdDecodeKeys.",
            """
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId }
            input FilmLookupKey @table(name: "film") {
                id: ID! @nodeId
            }
            type Query {
                filmByKey(key: FilmLookupKey @lookupKey): [Film!]!
            }
            """,
            schema -> {
                var f = (no.sikt.graphitron.rewrite.model.QueryField.QueryLookupTableField)
                    schema.field("Query", "filmByKey");
                var mapping = (no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping)
                    f.lookupMapping();
                assertThat(mapping.args()).hasSize(1);
                var arg = mapping.args().get(0);
                assertThat(arg).isInstanceOf(
                    no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping.LookupArg.MapInput.class);
                var mi = (no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping.LookupArg.MapInput) arg;
                assertThat(mi.bindings()).hasSize(1);
                assertThat(mi.bindings().get(0).extraction())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.NodeIdDecodeKeys.class);
            }),

        LOOKUP_KEY_ON_NODEID_INPUT_FIELD_ADMITTED_COMPOSITE_PK(
            "R130 + R144: @lookupKey on the ARGUMENT_DEFINITION of a same-table singular "
                + "`id: ID! @nodeId` @table input arg (composite-PK NodeType) → admitted as a "
                + "DecodedRecordGroup, projected to a LookupArg.DecodedRecord at the "
                + "lookup-mapping layer. The per-NodeType decode runs once at the arg layer and "
                + "the N record slots bind positionally to the target PK columns.",
            """
            type FilmActor implements Node @table(name: "film_actor") @node { id: ID! @nodeId }
            input FilmActorLookupKey @table(name: "film_actor") {
                id: ID! @nodeId
            }
            type Query {
                filmActorByKey(key: FilmActorLookupKey @lookupKey): [FilmActor!]!
            }
            """,
            schema -> {
                var f = (no.sikt.graphitron.rewrite.model.QueryField.QueryLookupTableField)
                    schema.field("Query", "filmActorByKey");
                var mapping = (no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping)
                    f.lookupMapping();
                assertThat(mapping.args()).hasSize(1);
                var arg = mapping.args().get(0);
                assertThat(arg).isInstanceOf(
                    no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping.LookupArg.DecodedRecord.class);
                var dr = (no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping.LookupArg.DecodedRecord) arg;
                assertThat(dr.bindings()).hasSize(2);
                assertThat(dr.bindings().get(0).index()).isZero();
                assertThat(dr.bindings().get(1).index()).isOne();
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

        TABLE_INPUT_FIELD_CONDITION_ARGMAPPING(
            "R53: @condition on an input field with argMapping — Java parameter name diverges from the input-field name",
            """
            input FilmInput @table(name: "film") {
              filmId: Int! @field(name: "film_id")
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "inputFieldConditionRenamed", argMapping: "id: filmId"})
            }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { films(filter: FilmInput): [Film!]! }
            """,
            schema -> {
                var f = (QueryField.QueryTableField) schema.field("Query", "films");
                assertThat(f.filters()).hasSize(1);
                var cond = (ConditionFilter) f.filters().get(0);
                assertThat(cond.methodName()).isEqualTo("inputFieldConditionRenamed");
                var renamedParam = cond.params().stream()
                    .filter(p -> p instanceof no.sikt.graphitron.rewrite.model.MethodRef.Param.Typed t
                        && t.source() instanceof no.sikt.graphitron.rewrite.model.ParamSource.Arg)
                    .map(p -> (no.sikt.graphitron.rewrite.model.MethodRef.Param.Typed) p)
                    .findFirst().orElseThrow();
                assertThat(renamedParam.name()).isEqualTo("id");
                assertThat(((no.sikt.graphitron.rewrite.model.ParamSource.Arg) renamedParam.source()).graphqlArgName())
                    .isEqualTo("filmId");
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
            "@lookupKey arg on a @table input arg (R144) → no implicit BodyParam emitted for "
                + "any binding-bound input field; sibling plain (non-table) arg gets implicit "
                + "BodyParams normally",
            """
            input FilmInput @table(name: "film") {
              filmId: Int! @field(name: "film_id")
              title: String @field(name: "title")
            }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") title: String! @field(name: "title") }
            type Query { films(filter: FilmInput @lookupKey): [Film!]! }
            """,
            schema -> {
                // Arg-level @lookupKey on a @table input promotes this to QueryLookupTableField.
                // Every admissible input field becomes a binding under R144's filter-by-default
                // rule; bound fields are consumed by LookupValuesJoinEmitter and must not appear
                // as implicit BodyParams. With no plain (non-table) arg present, no
                // GeneratedConditionFilter is emitted.
                var f = (SqlGeneratingField) schema.field("Query", "films");
                long condFilters = f.filters().stream().filter(fi -> fi instanceof GeneratedConditionFilter).count();
                assertThat(condFilters).isZero();
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
            "@table + @record on an input → R96: @table wins; @record's className is ignored; "
                + "Shadowed-by-@table directive-ignored warning fires at the post-classification site.",
            """
            input FilmInput
                @table(name: "film")
                @record(record: {className: "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord"})
            { id: ID }
            type Query { x: String }
            """,
            schema -> {
                // R96: @table wins. With `id` failing to resolve as a film column, the @table
                // path produces UnclassifiedType — the Shadowed-by-@table warning still fires.
                assertThat(schema.warnings())
                    .extracting(BuildWarning::message)
                    .anyMatch(m -> m.contains("FilmInput")
                        && m.contains("carries both @table and")
                        && m.contains("@record")
                        && m.contains("the @record directive is ignored"));
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
            schema -> assertThat(schema.type("FilterInput")).isInstanceOf(PojoInputType.class)),

        NOT_GENERATED_REJECTED_TABLE_INPUT(
            "@notGenerated on a @table input field → UnclassifiedType with reason saying so",
            """
            input CustomerInput @table(name: "customer") {
                customerId: Int! @field(name: "customer_id")
                hidden: String @notGenerated
            }
            type Query { x: String }
            """,
            schema -> {
                var t = schema.type("CustomerInput");
                assertThat(t).isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType.class);
                assertThat(((no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType) t).reason())
                    .contains("@notGenerated", "no longer supported");
            }),

        NOT_GENERATED_REJECTED_NESTED_INPUT(
            "@notGenerated on a field of a plain input nested inside a @table input → UnclassifiedType with reason saying so",
            """
            input InnerFilter { hidden: String @notGenerated }
            input CustomerInput @table(name: "customer") {
                customerId: Int! @field(name: "customer_id")
                inner: InnerFilter
            }
            type Query { x: String }
            """,
            schema -> {
                var t = schema.type("CustomerInput");
                assertThat(t).isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType.class);
                assertThat(((no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType) t).reason())
                    .contains("@notGenerated", "no longer supported");
            }),

        // ===== Canonical [ID!] @nodeId(typeName: T) (post-R50 successor of IdReferenceField) =====

        ID_REFERENCE_NODEID_INFERRED(
            "[ID!] @nodeId(typeName:) with unique FK → ColumnReferenceField with NodeIdDecodeKeys (FK inferred)",
            """
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId title: String }
            type Inventory @table(name: "inventory") { lastUpdate: String }
            input InventoryFilterInput @table(name: "inventory") {
              filmIds: [ID!] @nodeId(typeName: "Film")
            }
            type Query { inventory: Inventory }
            """,
            schema -> {
                var tit = (TableInputType) schema.type("InventoryFilterInput");
                var f = (InputField.ColumnReferenceField) tit.inputFields().stream()
                    .filter(InputField.ColumnReferenceField.class::isInstance).findFirst().orElseThrow();
                assertThat(f.list()).isTrue();
                assertThat(f.column().sqlName()).isEqualTo("film_id");
                assertThat(f.extraction())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.SkipMismatchedElement.class);
                assertThat(f.joinPath()).hasSize(1);
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(InputField.ColumnReferenceField.class); }
        },

        ID_REFERENCE_NODEID_EXPLICIT(
            "[ID!] @nodeId + @reference(path: [{key:}]) → ColumnReferenceField with NodeIdDecodeKeys (FK explicit)",
            """
            type Language implements Node @table(name: "language") @node { id: ID! @nodeId name: String }
            type Film @table(name: "film") { title: String }
            input FilmFilterInput @table(name: "film") {
              languageIds: [ID!] @nodeId(typeName: "Language")
                                 @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { film: Film }
            """,
            schema -> {
                var tit = (TableInputType) schema.type("FilmFilterInput");
                var f = (InputField.ColumnReferenceField) tit.inputFields().stream()
                    .filter(InputField.ColumnReferenceField.class::isInstance).findFirst().orElseThrow();
                assertThat(f.column().sqlName()).isEqualTo("language_id");
                assertThat(f.extraction())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.SkipMismatchedElement.class);
                assertThat(f.joinPath()).hasSize(1);
            }),

        ID_REFERENCE_AMBIGUOUS_FK(
            "[ID!] @nodeId with multiple FKs from source to target → UnclassifiedType (needs @reference)",
            """
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") { title: String }
            input FilmFilterInput @table(name: "film") {
              languageIds: [ID!] @nodeId(typeName: "Language")
            }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.type("FilmFilterInput")).isInstanceOf(UnclassifiedType.class)),

        ID_REFERENCE_BAD_KEY(
            "[ID!] @nodeId + @reference to nonexistent FK key → UnclassifiedType",
            """
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") { title: String }
            input FilmFilterInput @table(name: "film") {
              languageIds: [ID!] @nodeId(typeName: "Language")
                                 @reference(path: [{key: "no_such_fkey"}])
            }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.type("FilmFilterInput")).isInstanceOf(UnclassifiedType.class)),

        ID_REFERENCE_NO_FK_TO_TARGET(
            "[ID!] @nodeId where source table has zero FKs to target → UnclassifiedType",
            """
            type Language @table(name: "language") { name: String }
            type Actor @table(name: "actor") { firstName: String }
            input ActorFilterInput @table(name: "actor") {
              languageIds: [ID!] @nodeId(typeName: "Language")
            }
            type Query { actor: Actor }
            """,
            schema -> assertThat(schema.type("ActorFilterInput")).isInstanceOf(UnclassifiedType.class)),

        ID_REFERENCE_MIXED_DIRECTIVES_CANONICAL_WINS(
            "[ID!] @nodeId + @field(name:) → canonical branch wins, @field(name:) value ignored",
            """
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId title: String }
            type Inventory @table(name: "inventory") { lastUpdate: String }
            input InventoryFilterInput @table(name: "inventory") {
              filmIds: [ID!] @nodeId(typeName: "Film") @field(name: "BOGUS_NAME")
            }
            type Query { inventory: Inventory }
            """,
            schema -> {
                var tit = (TableInputType) schema.type("InventoryFilterInput");
                var f = (InputField.ColumnReferenceField) tit.inputFields().stream()
                    .filter(InputField.ColumnReferenceField.class::isInstance).findFirst().orElseThrow();
                assertThat(f.column().sqlName()).isEqualTo("film_id");
                assertThat(f.extraction())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.SkipMismatchedElement.class);
            });

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
        },

        ARG_MAPPING_INERT_ON_ENUM(
            "R53: argMapping on @enum → UnclassifiedType (structural-inertness rejection)",
            """
            enum Mood @enum(enumReference: {className: "no.sikt.graphitron.codereferences.dummyreferences.MoodEnum", argMapping: "x: y"}) { HAPPY SAD }
            type Query { foo: String }
            """,
            schema -> {
                var t = (UnclassifiedType) schema.type("Mood");
                assertThat(t.reason()).contains("argMapping is not supported on @enum");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(); }
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

    // ===== ScalarType =====

    enum ScalarTypeClassificationCase implements ClassificationCase {
        SPEC_BUILT_IN_STRING_RESOLVES(
            "spec built-in 'String' → ScalarType with Resolved(String, Scalars, GraphQLString)",
            """
            type Query { x: String }
            """,
            schema -> {
                var t = (ScalarType) schema.type("String");
                assertThat(t.resolution().scalarConstantField()).isEqualTo("GraphQLString");
                assertThat(t.resolution().scalarConstantOwner().toString()).isEqualTo("graphql.Scalars");
            }),

        SPEC_BUILT_IN_ID_RESOLVES(
            "spec built-in 'ID' → ScalarType with Resolved(String, Scalars, GraphQLID)",
            """
            type Query { id: ID }
            """,
            schema -> {
                var t = (ScalarType) schema.type("ID");
                assertThat(t.resolution().scalarConstantField()).isEqualTo("GraphQLID");
            }),

        DIRECTIVE_RESOLVES_CONSUMER_SCALAR(
            "scalar with @scalarType referencing a well-formed Coercing → ScalarType resolved to fixture",
            """
            scalar Money @scalarType(scalar: "no.sikt.graphitron.rewrite.scalarfixture.ScalarConstants.MONEY")
            type Query { x: Money }
            """,
            schema -> {
                var t = (ScalarType) schema.type("Money");
                assertThat(t.resolution().scalarConstantField()).isEqualTo("MONEY");
                assertThat(t.resolution().scalarConstantOwner().toString())
                    .isEqualTo("no.sikt.graphitron.rewrite.scalarfixture.ScalarConstants");
                assertThat(t.resolution().javaType().toString())
                    .isEqualTo("no.sikt.graphitron.rewrite.scalarfixture.Money");
            }),

        DIRECTIVE_ON_SPEC_BUILT_IN_DIRECTIVE_CONFLICT(
            "@scalarType on a spec built-in name → UnclassifiedType with DirectiveConflict",
            """
            scalar String @scalarType(scalar: "no.sikt.graphitron.rewrite.scalarfixture.ScalarConstants.MONEY")
            type Query { x: String }
            """,
            schema -> {
                var t = (UnclassifiedType) schema.type("String");
                assertThat(t.rejection()).isInstanceOf(Rejection.InvalidSchema.DirectiveConflict.class);
                assertThat(t.reason()).contains("not allowed on the GraphQL spec built-in 'String'");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(); }
        },

        DIRECTIVE_CLASS_NOT_FOUND_REJECTS(
            "@scalarType pointing at a missing class → UnclassifiedType with ClassNotFound-derived reason",
            """
            scalar Money @scalarType(scalar: "does.not.exist.Class.FIELD")
            type Query { x: Money }
            """,
            schema -> {
                var t = (UnclassifiedType) schema.type("Money");
                assertThat(t.reason()).contains("not on the codegen classpath");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(); }
        },

        DIRECTIVE_COERCING_ERASED_REJECTS(
            "@scalarType pointing at a raw Coercing → UnclassifiedType with CoercingErased reason",
            """
            scalar Money @scalarType(scalar: "no.sikt.graphitron.rewrite.scalarfixture.ScalarConstants.RAW_MONEY")
            type Query { x: Money }
            """,
            schema -> {
                var t = (UnclassifiedType) schema.type("Money");
                assertThat(t.reason()).contains("erased type parameters").contains("RAW_TYPE");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(); }
        },

        UNANNOTATED_NON_SPEC_SCALAR_ESCALATES(
            "non-spec scalar with no @scalarType and not in convention table → UnclassifiedType",
            """
            scalar Money
            type Query { x: Money }
            """,
            schema -> {
                var t = (UnclassifiedType) schema.type("Money");
                assertThat(t.reason())
                    .contains("scalar 'Money'")
                    .contains("@scalarType")
                    .contains("graphql-java-extended-scalars");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(); }
        },

        CONVENTION_LAYER_RESOLVES_BIG_DECIMAL(
            "non-spec scalar 'BigDecimal' with extended-scalars on classpath → ScalarType via convention",
            """
            scalar BigDecimal
            type Query { x: BigDecimal }
            """,
            schema -> {
                var t = (ScalarType) schema.type("BigDecimal");
                assertThat(t.resolution().scalarConstantField()).isEqualTo("GraphQLBigDecimal");
                assertThat(t.resolution().scalarConstantOwner().toString())
                    .isEqualTo("graphql.scalars.ExtendedScalars");
                assertThat(t.resolution().javaType().toString()).isEqualTo("java.math.BigDecimal");
            }),

        CONVENTION_LAYER_RESOLVES_UUID(
            "non-spec scalar 'UUID' with extended-scalars on classpath → ScalarType via convention",
            """
            scalar UUID
            type Query { x: UUID }
            """,
            schema -> {
                var t = (ScalarType) schema.type("UUID");
                assertThat(t.resolution().scalarConstantField()).isEqualTo("UUID");
                assertThat(t.resolution().javaType().toString()).isEqualTo("java.util.UUID");
            }),

        DIRECTIVE_BEATS_CONVENTION(
            "scalar named 'BigDecimal' with @scalarType wins over the convention layer",
            """
            scalar BigDecimal @scalarType(scalar: "no.sikt.graphitron.rewrite.scalarfixture.ScalarConstants.MONEY")
            type Query { x: BigDecimal }
            """,
            schema -> {
                // Directive resolves to Money, not the convention table's GraphQLBigDecimal.
                var t = (ScalarType) schema.type("BigDecimal");
                assertThat(t.resolution().scalarConstantField()).isEqualTo("MONEY");
                assertThat(t.resolution().javaType().toString())
                    .isEqualTo("no.sikt.graphitron.rewrite.scalarfixture.Money");
            });

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        ScalarTypeClassificationCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() { return Set.of(ScalarType.class); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ScalarTypeClassificationCase.class)
    void scalarTypeClassification(ScalarTypeClassificationCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== ErrorType =====

    enum ErrorTypeCase implements ClassificationCase {
        GENERIC_LIFTS_TO_EXCEPTION_HANDLER(
            "GENERIC with className lifts to ExceptionHandler",
            """
            type MyError @error(handlers: [{handler: GENERIC, className: "java.lang.IllegalArgumentException"}]) {
                path: [String!]!
                message: String!
            }
            type Query { x: String }
            """,
            schema -> {
                var errorType = (ErrorType) schema.type("MyError");
                assertThat(errorType.handlers()).hasSize(1);
                var h = errorType.handlers().get(0);
                assertThat(h).isInstanceOf(ErrorType.ExceptionHandler.class);
                var eh = (ErrorType.ExceptionHandler) h;
                assertThat(eh.exceptionClassName()).isEqualTo("java.lang.IllegalArgumentException");
                assertThat(eh.matches()).isEmpty();
                assertThat(eh.description()).isEmpty();
            }),

        DATABASE_SQL_STATE_LIFTS_TO_SQL_STATE_HANDLER(
            "{handler: DATABASE, sqlState: ...} lifts to SqlStateHandler",
            """
            type DbError @error(handlers: [{handler: DATABASE, sqlState: "23503", description: "FK violation"}]) {
                path: [String!]!
                message: String!
            }
            type Query { x: String }
            """,
            schema -> {
                var h = ((ErrorType) schema.type("DbError")).handlers().get(0);
                assertThat(h).isInstanceOf(ErrorType.SqlStateHandler.class);
                var sh = (ErrorType.SqlStateHandler) h;
                assertThat(sh.sqlState()).isEqualTo("23503");
                assertThat(sh.description()).contains("FK violation");
            }),

        DATABASE_CODE_LIFTS_TO_VENDOR_CODE_HANDLER(
            "{handler: DATABASE, code: ...} lifts to VendorCodeHandler",
            """
            type DbError @error(handlers: [{handler: DATABASE, code: "1"}]) {
                path: [String!]!
                message: String!
            }
            type Query { x: String }
            """,
            schema -> {
                var h = ((ErrorType) schema.type("DbError")).handlers().get(0);
                assertThat(h).isInstanceOf(ErrorType.VendorCodeHandler.class);
                assertThat(((ErrorType.VendorCodeHandler) h).vendorCode()).isEqualTo("1");
            }),

        DATABASE_NO_DISCRIMINATOR_LIFTS_TO_EXCEPTION_HANDLER_SQL_EXCEPTION(
            "{handler: DATABASE} (no discriminator) lifts to ExceptionHandler(java.sql.SQLException)",
            """
            type DbError @error(handlers: [{handler: DATABASE}]) {
                path: [String!]!
                message: String!
            }
            type Query { x: String }
            """,
            schema -> {
                var h = ((ErrorType) schema.type("DbError")).handlers().get(0);
                assertThat(h).isInstanceOf(ErrorType.ExceptionHandler.class);
                assertThat(((ErrorType.ExceptionHandler) h).exceptionClassName())
                    .isEqualTo("java.sql.SQLException");
            }),

        VALIDATION_LIFTS_TO_VALIDATION_HANDLER(
            "{handler: VALIDATION} lifts to ValidationHandler",
            """
            type ValidationError @error(handlers: [{handler: VALIDATION, description: "input invalid"}]) {
                path: [String!]!
                message: String!
            }
            type Query { x: String }
            """,
            schema -> {
                var h = ((ErrorType) schema.type("ValidationError")).handlers().get(0);
                assertThat(h).isInstanceOf(ErrorType.ValidationHandler.class);
                assertThat(h.matches()).isEmpty();
                assertThat(h.description()).contains("input invalid");
            }),

        CAPTURES_MATCHES_FIELD(
            "matches field is captured on ExceptionHandler",
            """
            type MatchError @error(handlers: [{handler: GENERIC, className: "java.lang.IllegalArgumentException", matches: "duplicate"}]) {
                path: [String!]!
                message: String!
            }
            type Query { x: String }
            """,
            schema -> assertThat(((ErrorType) schema.type("MatchError")).handlers().get(0).matches())
                .contains("duplicate")),

        MULTIPLE_HANDLERS_PRESERVE_ORDER(
            "multiple handler objects in the array are captured in source order",
            """
            type MultiError @error(handlers: [
                {handler: GENERIC, className: "java.lang.IllegalArgumentException"},
                {handler: DATABASE, sqlState: "23505"}
            ]) {
                path: [String!]!
                message: String!
            }
            type Query { x: String }
            """,
            schema -> {
                var handlers = ((ErrorType) schema.type("MultiError")).handlers();
                assertThat(handlers).hasSize(2);
                assertThat(handlers.get(0)).isInstanceOf(ErrorType.ExceptionHandler.class);
                assertThat(handlers.get(1)).isInstanceOf(ErrorType.SqlStateHandler.class);
            }),

        REJECT_GENERIC_WITHOUT_CLASS_NAME(
            "GENERIC without className → UnclassifiedType",
            """
            type BadError @error(handlers: [{handler: GENERIC}]) {
                path: [String!]!
                message: String!
            }
            type Query { x: String }
            """,
            schema -> {
                var t = (UnclassifiedType) schema.type("BadError");
                assertThat(t.reason()).contains("GENERIC").contains("className");
            }),

        REJECT_GENERIC_WITH_SQL_STATE(
            "GENERIC with sqlState → UnclassifiedType",
            """
            type BadError @error(handlers: [{handler: GENERIC, className: "com.example.Ex", sqlState: "23503"}]) {
                path: [String!]!
                message: String!
            }
            type Query { x: String }
            """,
            schema -> {
                var t = (UnclassifiedType) schema.type("BadError");
                assertThat(t.reason()).contains("GENERIC").contains("sqlState");
            }),

        REJECT_GENERIC_WITH_CODE(
            "GENERIC with code → UnclassifiedType",
            """
            type BadError @error(handlers: [{handler: GENERIC, className: "com.example.Ex", code: "1"}]) {
                path: [String!]!
                message: String!
            }
            type Query { x: String }
            """,
            schema -> {
                var t = (UnclassifiedType) schema.type("BadError");
                assertThat(t.reason()).contains("GENERIC").contains("code");
            }),

        REJECT_DATABASE_WITH_BOTH_DISCRIMINATORS(
            "DATABASE with both sqlState and code → UnclassifiedType",
            """
            type BadError @error(handlers: [{handler: DATABASE, sqlState: "23503", code: "1"}]) {
                path: [String!]!
                message: String!
            }
            type Query { x: String }
            """,
            schema -> {
                var t = (UnclassifiedType) schema.type("BadError");
                assertThat(t.reason()).contains("DATABASE").contains("sqlState").contains("code");
            }),

        REJECT_DATABASE_WITH_CLASS_NAME(
            "DATABASE with explicit className → UnclassifiedType",
            """
            type BadError @error(handlers: [{handler: DATABASE, className: "org.springframework.dao.DataAccessException"}]) {
                path: [String!]!
                message: String!
            }
            type Query { x: String }
            """,
            schema -> {
                var t = (UnclassifiedType) schema.type("BadError");
                assertThat(t.reason()).contains("DATABASE").contains("className");
            }),

        REJECT_VALIDATION_WITH_CLASS_NAME(
            "VALIDATION with className → UnclassifiedType",
            """
            type BadError @error(handlers: [{handler: VALIDATION, className: "com.example.Ex"}]) {
                path: [String!]!
                message: String!
            }
            type Query { x: String }
            """,
            schema -> {
                var t = (UnclassifiedType) schema.type("BadError");
                assertThat(t.reason()).contains("VALIDATION").contains("className");
            }),

        REJECT_VALIDATION_WITH_SQL_STATE(
            "VALIDATION with sqlState → UnclassifiedType",
            """
            type BadError @error(handlers: [{handler: VALIDATION, sqlState: "23503"}]) {
                path: [String!]!
                message: String!
            }
            type Query { x: String }
            """,
            schema -> {
                var t = (UnclassifiedType) schema.type("BadError");
                assertThat(t.reason()).contains("VALIDATION").contains("sqlState");
            }),

        REJECT_VALIDATION_WITH_CODE(
            "VALIDATION with code → UnclassifiedType",
            """
            type BadError @error(handlers: [{handler: VALIDATION, code: "1"}]) {
                path: [String!]!
                message: String!
            }
            type Query { x: String }
            """,
            schema -> {
                var t = (UnclassifiedType) schema.type("BadError");
                assertThat(t.reason()).contains("VALIDATION").contains("code");
            }),

        REJECT_VALIDATION_WITH_MATCHES(
            "VALIDATION with matches → UnclassifiedType",
            """
            type BadError @error(handlers: [{handler: VALIDATION, matches: "foo"}]) {
                path: [String!]!
                message: String!
            }
            type Query { x: String }
            """,
            schema -> {
                var t = (UnclassifiedType) schema.type("BadError");
                assertThat(t.reason()).contains("VALIDATION").contains("matches");
            }),

        REJECT_MISSING_PATH_FIELD(
            "@error type missing 'path' field → UnclassifiedType",
            """
            type BadError @error(handlers: [{handler: GENERIC, className: "com.example.Ex"}]) {
                message: String!
            }
            type Query { x: String }
            """,
            schema -> assertThat(((UnclassifiedType) schema.type("BadError")).reason())
                .contains("path")),

        REJECT_MISSING_MESSAGE_FIELD(
            "@error type missing 'message' field → UnclassifiedType",
            """
            type BadError @error(handlers: [{handler: GENERIC, className: "com.example.Ex"}]) {
                path: [String!]!
            }
            type Query { x: String }
            """,
            schema -> assertThat(((UnclassifiedType) schema.type("BadError")).reason())
                .contains("message")),

        ADMIT_EXTRA_FIELD(
            "@error type with field beyond path/message → ErrorType (per-handler accessor check fires on the carrier, not the type)",
            """
            enum Severity { LOW HIGH }
            type SomeError @error(handlers: [{handler: GENERIC, className: "java.lang.IllegalArgumentException"}]) {
                path: [String!]!
                message: String!
                severity: Severity!
            }
            type Query { x: String }
            """,
            schema -> assertThat(schema.type("SomeError"))
                .isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType.class)),

        REJECT_WRONG_PATH_SHAPE(
            "@error type with path: String (not [String!]!) → UnclassifiedType",
            """
            type BadError @error(handlers: [{handler: GENERIC, className: "com.example.Ex"}]) {
                path: String
                message: String!
            }
            type Query { x: String }
            """,
            schema -> assertThat(((UnclassifiedType) schema.type("BadError")).reason())
                .contains("path").contains("[String!]!")),

        REJECT_WRONG_MESSAGE_SHAPE(
            "@error type with message: String (nullable, not String!) → UnclassifiedType",
            """
            type BadError @error(handlers: [{handler: GENERIC, className: "com.example.Ex"}]) {
                path: [String!]!
                message: String
            }
            type Query { x: String }
            """,
            schema -> assertThat(((UnclassifiedType) schema.type("BadError")).reason())
                .contains("message").contains("String!")),

        REJECT_GENERIC_WITH_UNRESOLVABLE_CLASS_NAME(
            "GENERIC with className that cannot be loaded on the classifier classpath → UnclassifiedType",
            """
            type BadError @error(handlers: [{handler: GENERIC, className: "no.does.not.exist.Missing"}]) {
                path: [String!]!
                message: String!
            }
            type Query { x: String }
            """,
            schema -> {
                var t = (UnclassifiedType) schema.type("BadError");
                assertThat(t.reason())
                    .contains("no.does.not.exist.Missing")
                    .contains("could not be loaded");
            }),

        REJECT_GENERIC_WITH_NON_THROWABLE_CLASS(
            "GENERIC with className that resolves but doesn't extend Throwable → UnclassifiedType",
            """
            type BadError @error(handlers: [{handler: GENERIC, className: "java.lang.String"}]) {
                path: [String!]!
                message: String!
            }
            type Query { x: String }
            """,
            schema -> {
                var t = (UnclassifiedType) schema.type("BadError");
                assertThat(t.reason())
                    .contains("java.lang.String")
                    .contains("Throwable");
            }),

        // R12 source-direct dispatch: @error has no developer-supplied data class. @record and
        // @error are mutually exclusive — the matched throwable itself is the runtime source
        // for fields declared on the @error type, so co-locating @record(record: {className})
        // would name a Java class that classifier never instantiates and the runtime never reads.
        REJECT_RECORD_PLUS_ERROR_MUTUALLY_EXCLUSIVE(
            "@error co-located with @record → UnclassifiedType (mutually exclusive)",
            """
            type ValidationError
                @error(handlers: [{handler: VALIDATION}])
                @record(record: {className: "java.lang.Object"}) {
                path: [String!]!
                message: String!
            }
            type Query { x: String }
            """,
            schema -> {
                var t = (UnclassifiedType) schema.type("ValidationError");
                assertThat(t.reason())
                    .contains("@record")
                    .contains("@error")
                    .contains("mutually exclusive");
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
     * Child fields on an {@code @error} type. The {@code @error} contract restricts the field
     * set to exactly {@code path: [String!]!} and {@code message: String!} (enforced at type
     * classification time); both classify as {@link PropertyField} so graphql-java's default
     * {@code PropertyDataFetcher} resolves them off the developer-supplied @error class.
     */
    enum ErrorFieldCase implements ClassificationCase {
        PATH_AND_MESSAGE_CLASSIFY_AS_PROPERTY_FIELDS(
            "@error parent — path and message both classify as PropertyField",
            """
            type MyError @error(handlers: [{handler: GENERIC, className: "java.lang.IllegalArgumentException"}]) {
                path: [String!]!
                message: String!
            }
            type Query { x: String }
            """,
            schema -> {
                assertThat(schema.field("MyError", "path")).isInstanceOf(PropertyField.class);
                assertThat(schema.field("MyError", "message")).isInstanceOf(PropertyField.class);
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

    // ===== Errors-shaped fields lifting from PolymorphicReturnType to ErrorsField =====

    /**
     * The five {@code PolymorphicReturnType} rejection arms in {@link FieldBuilder} lift to
     * {@link ErrorsField} when the polymorphic type's members are all {@code @error} types and
     * the field is a nullable list. Covers the production blocker per
     * {@code error-handling-parity.md} §2a/§2b.
     *
     * <p>Each case targets one of the five lift sites by varying the carrier shape:
     *
     * <ul>
     *   <li>{@code classifyMutationField} — Mutation root {@code @service} returning a payload
     *       (the canonical {@code BehandleSakPayload} shape).</li>
     *   <li>{@code classifyChildFieldOnResultType} (non-service arm) — the payload's own
     *       {@code errors} field on a {@code @record} parent.</li>
     *   <li>Plus rejection cases: mixed-{@code @error}-and-non-{@code @error} unions, non-null
     *       list shapes, and pure non-{@code @error} polymorphic returns (which still fall
     *       through to the existing "polymorphic not supported" rejection).</li>
     * </ul>
     */
    enum ErrorsFieldCase implements ClassificationCase {
        UNION_OF_ALL_ERROR_TYPES_LIFTS_TO_ERRORS_FIELD(
            "union of @error types on @record payload — errors field lifts to ErrorsField",
            """
            type ValidationErr @error(handlers: [{handler: VALIDATION}]) {
                path: [String!]!
                message: String!
            }
            type DbErr @error(handlers: [{handler: DATABASE, sqlState: "23503"}]) {
                path: [String!]!
                message: String!
            }
            union BehandleSakError = ValidationErr | DbErr
            type BehandleSakPayload @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
                ok: Boolean
                errors: [BehandleSakError!]
            }
            type Query { x: String }
            """,
            schema -> {
                var field = schema.field("BehandleSakPayload", "errors");
                assertThat(field).isInstanceOf(ErrorsField.class);
                var ef = (ErrorsField) field;
                assertThat(ef.errorTypes())
                    .extracting(et -> et.name())
                    .containsExactly("ValidationErr", "DbErr");
                // Phase B round-trip pin: the lifted ErrorsField carries the Transport
                // discriminator selected at classify time. Today's lift defaults to
                // PayloadAccessor because no producer emits ErrorChannel.LocalContext yet.
                assertThat(ef.transport())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.ChildField.Transport.PayloadAccessor.class);
            }),

        INTERFACE_IMPLEMENTED_BY_ALL_ERROR_TYPES_LIFTS_TO_ERRORS_FIELD(
            "interface implemented by @error types on @record payload — errors field lifts to ErrorsField",
            """
            interface BehandleSakError {
                path: [String!]!
                message: String!
            }
            type ValidationErr implements BehandleSakError @error(handlers: [{handler: VALIDATION}]) {
                path: [String!]!
                message: String!
            }
            type DbErr implements BehandleSakError @error(handlers: [{handler: DATABASE, sqlState: "23503"}]) {
                path: [String!]!
                message: String!
            }
            type BehandleSakPayload @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
                ok: Boolean
                errors: [BehandleSakError]
            }
            type Query { x: String }
            """,
            schema -> {
                var field = schema.field("BehandleSakPayload", "errors");
                assertThat(field).isInstanceOf(ErrorsField.class);
                var ef = (ErrorsField) field;
                assertThat(ef.errorTypes())
                    .extracting(et -> et.name())
                    .containsExactlyInAnyOrder("ValidationErr", "DbErr");
            }),

        SINGLE_ERROR_TYPE_UNION_LIFTS_TO_ERRORS_FIELD(
            "single-member union of one @error type — still lifts (one entry in errorTypes)",
            """
            type DbErr @error(handlers: [{handler: DATABASE}]) {
                path: [String!]!
                message: String!
            }
            union BehandleSakError = DbErr
            type BehandleSakPayload @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
                ok: Boolean
                errors: [BehandleSakError]
            }
            type Query { x: String }
            """,
            schema -> {
                var field = schema.field("BehandleSakPayload", "errors");
                assertThat(field).isInstanceOf(ErrorsField.class);
                assertThat(((ErrorsField) field).errorTypes())
                    .extracting(et -> et.name())
                    .containsExactly("DbErr");
            }),

        MIXED_ERROR_AND_NON_ERROR_UNION_REJECTS(
            "mixed @error + non-@error union — UnclassifiedField with mixed-not-supported reason",
            """
            type DbErr @error(handlers: [{handler: DATABASE}]) {
                path: [String!]!
                message: String!
            }
            type Plain @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
                whatever: String
            }
            union Mixed = DbErr | Plain
            type Payload @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
                ok: Boolean
                errors: [Mixed]
            }
            type Query { x: String }
            """,
            schema -> {
                var field = schema.field("Payload", "errors");
                assertThat(field).isInstanceOf(UnclassifiedField.class);
                var u = (UnclassifiedField) field;
                assertThat(u.reason())
                    .contains("every member declared @error")
                    .contains("Plain");
                assertThat(u.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
            }),

        NON_NULL_LIST_OF_ERRORS_REJECTS(
            "errors: [SomeError]! (non-null list) — UnclassifiedField with nullability reason",
            """
            type ValidationErr @error(handlers: [{handler: VALIDATION}]) {
                path: [String!]!
                message: String!
            }
            type DbErr @error(handlers: [{handler: DATABASE}]) {
                path: [String!]!
                message: String!
            }
            union BehandleSakError = ValidationErr | DbErr
            type BehandleSakPayload @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
                ok: Boolean
                errors: [BehandleSakError!]!
            }
            type Query { x: String }
            """,
            schema -> {
                var field = schema.field("BehandleSakPayload", "errors");
                assertThat(field).isInstanceOf(UnclassifiedField.class);
                var u = (UnclassifiedField) field;
                assertThat(u.reason())
                    .contains("must be nullable");
                assertThat(u.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
            }),

        NON_ERROR_POLYMORPHIC_FALLS_THROUGH_TO_DEFERRED_REJECTION(
            "polymorphic with no @error members on a Pojo parent + single cardinality — DEFERRED via R105's single-cardinality arm",
            // R105 wires the @record-parent polymorphic classifier arm but defers the Pojo /
            // JavaRecord + single-cardinality shape (the single-cardinality emitter cannot
            // safely consume a non-jOOQ-Record source). The field falls into that deferred arm
            // before reaching the @sourceRow follow-up; the message reflects the
            // single-cardinality deferral, not the original "polymorphic not supported" wording.
            """
            type Cat @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
                whiskers: Int
            }
            type Dog @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
                tail: String
            }
            union Pet = Cat | Dog
            type Payload @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyRecord"}) {
                pet: Pet
            }
            type Query { x: String }
            """,
            schema -> {
                var field = schema.field("Payload", "pet");
                assertThat(field).isInstanceOf(UnclassifiedField.class);
                var u = (UnclassifiedField) field;
                assertThat(u.reason()).contains("polymorphic");
                assertThat(u.kind()).isEqualTo(RejectionKind.DEFERRED);
            });

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        ErrorsFieldCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() { return Set.of(ErrorsField.class); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ErrorsFieldCase.class)
    void errorsFieldClassification(ErrorsFieldCase tc) {
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

    @Test
    void build_rejectsRetiredFieldJavaNameArg_withGraphqlJavaParseError() {
        // R41: @field(javaName:) was retired together with the @field(name:) override on @service
        // method args. graphql-java's standard directive validation surfaces the rejection with
        // an "unknown argument 'javaName'" message at schema-build time — sufficient migration
        // signal; no custom message is bundled.
        assertThatThrownBy(() -> TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String @field(name: "title", javaName: "getTitle") }
            type Query { film: Film }
            """))
            .hasMessageContaining("javaName");
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
                // They are emitted via VALUES+JOIN from LookupMapping.args() instead.
                assertThat(f.filters()).isEmpty();
                var cm = (no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) f.lookupMapping();
                assertThat(cm.args()).hasSize(1);
                assertThat(cm.args().get(0).argName()).isEqualTo("film_id");
                assertThat(f.returnType().wrapper()).isInstanceOf(FieldWrapper.List.class);
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(QueryField.QueryLookupTableField.class); }
        },

        LOOKUP_NESTED_IN_INPUT(
            "@lookupKey on a plain input type's scalar field → UnclassifiedField (R144 retirement diagnostic)",
            """
            input FilmKey { id: ID @lookupKey }
            type Film @table(name: "film") { title: String }
            type Query { filmByKey(key: [FilmKey]): [Film!]! }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Query", "filmByKey");
                assertThat(f.reason())
                    .contains("R144")
                    .contains("ARGUMENT_DEFINITION");
            }),

        LOOKUP_FIELD_COLUMN_ARG(
            "lookup field list arg whose column exists → resolved ScalarLookupArg in LookupMapping",
            """
            type Film @table(name: "film") { title: String }
            type Query { filmById(film_id: [ID] @lookupKey): [Film!]! }
            """,
            schema -> {
                var f = (QueryField.QueryLookupTableField) schema.field("Query", "filmById");
                assertThat(f.filters()).isEmpty();
                var cm = (no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) f.lookupMapping();
                assertThat(cm.args()).hasSize(1);
                var arg = (no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping.LookupArg.ScalarLookupArg) cm.args().get(0);
                assertThat(arg.argName()).isEqualTo("film_id");
                assertThat(arg.targetColumn().javaName()).isEqualTo("FILM_ID");
                assertThat(arg.targetColumn().columnClass()).isNotEmpty();
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

        LOOKUP_FIELD_TABLE_INPUT_TYPE_ARG_ADMITS_EVERY_FIELD(
            "R144: lookup field whose @table input type has scalar admissible carriers and the arg carries @lookupKey → QueryLookupTableField (every admissible input field becomes a binding under R144's filter-by-default rule)",
            """
            input FilmKey @table(name: "film") { filmId: Int @field(name: "film_id") }
            type Film @table(name: "film") { title: String }
            type Query { filmByKey(key: [FilmKey] @lookupKey): [Film!]! }
            """,
            schema -> {
                var f = (QueryField.QueryLookupTableField) schema.field("Query", "filmByKey");
                var cm = (no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) f.lookupMapping();
                assertThat(cm.args()).hasSize(1);
                var arg = (no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping.LookupArg.MapInput) cm.args().get(0);
                assertThat(arg.bindings()).hasSize(1);
                assertThat(arg.bindings().get(0).fieldName()).isEqualTo("filmId");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(QueryField.QueryLookupTableField.class); }
        },

        LOOKUP_FIELD_IMPLICIT_TABLE_INPUT_TYPE_ARG_ADMITS_EVERY_FIELD(
            "R144: lookup field whose plain input type (promoted to TableInputType) with admissible carriers and arg-level @lookupKey → QueryLookupTableField; the promoted type remains in the types map",
            """
            input FilmKey { filmId: Int @field(name: "film_id") }
            type Film @table(name: "film") { title: String }
            type Query { filmByKey(key: [FilmKey] @lookupKey): [Film!]! }
            """,
            schema -> {
                var f = (QueryField.QueryLookupTableField) schema.field("Query", "filmByKey");
                var cm = (no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) f.lookupMapping();
                assertThat(cm.args()).hasSize(1);
                assertThat(schema.type("FilmKey"))
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronType.TableInputType.class);
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(QueryField.QueryLookupTableField.class); }
        },

        LOOKUP_FIELD_COMPOSITE_KEY_INPUT_TYPE_ARG(
            "lookup field whose @table input type carries two scalar fields with arg-level @lookupKey → QueryLookupTableField with one MapInput LookupArg carrying two MapBindings (R144: every admissible input field becomes a binding when the arg carries @lookupKey)",
            """
            input FilmActorKey @table(name: "film_actor") {
                filmId: Int @field(name: "film_id")
                actorId: Int @field(name: "actor_id")
            }
            type FilmActor @table(name: "film_actor") { lastUpdate: String @field(name: "last_update") }
            type Query { filmActorByKey(key: [FilmActorKey] @lookupKey): [FilmActor!]! }
            """,
            schema -> {
                var f = (QueryField.QueryLookupTableField) schema.field("Query", "filmActorByKey");
                assertThat(f.filters()).isEmpty();
                var cm = (no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) f.lookupMapping();
                assertThat(cm.args()).hasSize(1);
                var arg = (no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping.LookupArg.MapInput) cm.args().get(0);
                assertThat(arg.argName()).isEqualTo("key");
                assertThat(arg.list()).isTrue();
                assertThat(arg.bindings()).hasSize(2);
                assertThat(arg.bindings()).extracting(no.sikt.graphitron.rewrite.model.InputColumnBinding.MapBinding::fieldName)
                    .containsExactly("filmId", "actorId");
                assertThat(arg.bindings()).extracting(b -> b.targetColumn().javaName())
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
                var cm2 = (no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) f.lookupMapping();
                assertThat(cm2.args()).hasSize(1);
                assertThat(cm2.args().get(0).argName()).isEqualTo("film_id");
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
                    @tableMethod(className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "getFilmWithContext", contextArguments: ["tenantId"])
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
            "field returning Node (single) → QueryNodeField",
            """
            interface Node { id: ID! }
            type Film implements Node @table(name: "film") { id: ID! title: String }
            type Query { node(id: ID!): Node }
            """,
            schema -> assertThat(schema.field("Query", "node")).isInstanceOf(QueryField.QueryNodeField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(QueryField.QueryNodeField.class); }
        },

        NODES_QUERY_FIELD(
            "field returning [Node] → QueryNodesField",
            """
            interface Node { id: ID! }
            type Film implements Node @table(name: "film") { id: ID! title: String }
            type Query { node(id: ID!): Node nodes(ids: [ID!]!): [Node] }
            """,
            schema -> assertThat(schema.field("Query", "nodes")).isInstanceOf(QueryField.QueryNodesField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(QueryField.QueryNodesField.class); }
        },

        ALIASED_NODE_QUERY_FIELD(
            "non-'node' field returning Node → QueryNodeField (federation-style alias)",
            """
            interface Node { id: ID! }
            type Film implements Node @table(name: "film") { id: ID! title: String }
            type Query { node(id: ID!): Node internalFilmNode(id: ID): Node }
            """,
            schema -> assertThat(schema.field("Query", "internalFilmNode")).isInstanceOf(QueryField.QueryNodeField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(QueryField.QueryNodeField.class); }
        },

        ENTITY_QUERY_FIELD(
            "field named '_entities' has no special handling; classifies as UnclassifiedField (underscore-prefixed return type not in ctx.types)",
            """
            scalar _Any
            union _Entity = Film
            type Film @table(name: "film") { title: String }
            type Query { _entities(representations: [_Any!]!): [_Entity]! }
            """,
            schema -> assertThat(schema.field("Query", "_entities")).isInstanceOf(UnclassifiedField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(); }
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
                externalFilm: Film @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
            }
            """,
            schema -> {
                assertThat(schema.field("Query", "externalFilm")).isInstanceOf(QueryField.QueryServiceTableField.class);
                var f = (QueryField.QueryServiceTableField) schema.field("Query", "externalFilm");
                assertThat(f.method().className()).isEqualTo("no.sikt.graphitron.rewrite.TestServiceStub");
                assertThat(f.method().methodName()).isEqualTo("getFilm");
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
            input FilmInput @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT) }
            """,
            schema -> assertThat(schema.field("Mutation", "createFilm")).isInstanceOf(MutationField.MutationInsertTableField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationInsertTableField.class); }
        },

        UPDATE_MUTATION_FIELD(
            "@mutation(typeName: UPDATE) → MutationUpdateTableField",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { filmId: Int! @field(name: "film_id"), title: String @value }
            type Query { x: String }
            type Mutation { updateFilm(in: FilmInput!): Film @mutation(typeName: UPDATE) }
            """,
            schema -> assertThat(schema.field("Mutation", "updateFilm")).isInstanceOf(MutationField.MutationUpdateTableField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationUpdateTableField.class); }
        },

        DELETE_MUTATION_FIELD(
            "@mutation(typeName: DELETE) → MutationDeleteTableField",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { x: String }
            type Mutation { deleteFilm(in: FilmInput!): Film @mutation(typeName: DELETE) }
            """,
            schema -> assertThat(schema.field("Mutation", "deleteFilm")).isInstanceOf(MutationField.MutationDeleteTableField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationDeleteTableField.class); }
        },

        DELETE_MUTATION_NO_INPUT_ARG(
            "@mutation(typeName: DELETE) without @table input arg → UnclassifiedField",
            """
            type Film @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { deleteFilm: Film @mutation(typeName: DELETE) }
            """,
            schema -> {
                var field = schema.field("Mutation", "deleteFilm");
                assertThat(field).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) field).reason())
                    .contains("no @table input argument found on @mutation field");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        DELETE_MUTATION_MISSING_PK_COVERAGE(
            "@mutation(typeName: DELETE) on a composite-PK table with one filter column missing → UnclassifiedField (R144 PK-coverage rejection without multiRow)",
            """
            type FilmActor @table(name: "film_actor") { actorId: Int, filmId: Int }
            input FilmActorKey @table(name: "film_actor") { filmId: Int! @field(name: "film_id") }
            type Query { x: String }
            type Mutation { deleteFilmActor(in: FilmActorKey!): ID @mutation(typeName: DELETE) }
            """,
            schema -> {
                var field = schema.field("Mutation", "deleteFilmActor");
                assertThat(field).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) field).reason())
                    .contains("filter columns do not cover all PK column(s)", "multiRow: true");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        UPSERT_MUTATION_REJECTED_UNDER_R144(
            "@mutation(typeName: UPSERT) → UnclassifiedField (R144 refuses UPSERT pending R145)",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { filmId: Int! @field(name: "film_id"), title: String @value }
            type Query { x: String }
            type Mutation { upsertFilm(in: FilmInput!): Film @mutation(typeName: UPSERT) }
            """,
            schema -> {
                var field = schema.field("Mutation", "upsertFilm");
                assertThat(field).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) field).reason())
                    .contains("@mutation(typeName: UPSERT) is not supported under the R144");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        SERVICE_MUTATION_FIELD(
            "@service on mutation field, @table return type → MutationServiceTableField",
            """
            type Film @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation {
                externalMutation: Film @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runFilm"})
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
        },

        MUTATION_DML_RECORD_FIELD(
            "R75 / Phase 1: @mutation(typeName: INSERT) with single @table input and a single-"
                + "record DML payload (plain SDL Object wrapping one @table-element data field) "
                + "→ MutationDmlRecordField. R141 pairs the input cardinality to the data field's "
                + "wrapper, so this row's single + single shape stays on the original sealed leaf.",
            """
            type Film @table(name: "film") { title: String }
            type FilmPayload { film: Film }
            input FilmCreateInput @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation {
                createFilm(in: FilmCreateInput!): FilmPayload @mutation(typeName: INSERT)
            }
            """,
            schema -> assertThat(schema.field("Mutation", "createFilm")).isInstanceOf(MutationField.MutationDmlRecordField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationDmlRecordField.class); }
        },

        MUTATION_BULK_DML_RECORD_FIELD(
            "R141: @mutation(typeName: INSERT) with bulk @table input and a single-record DML "
                + "carrier whose data field is list-shaped → MutationBulkDmlRecordField. The "
                + "carrier classifier routes the bulk-input + list-data-field cell to the new "
                + "sealed leaf; the emitter batches per-row DML in input order and runs one "
                + "follow-up response SELECT keyed by the PKs collected in input order.",
            """
            type Film @table(name: "film") { title: String }
            type FilmsPayload { films: [Film!] }
            input FilmCreateInput @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation {
                createFilmsPayload(in: [FilmCreateInput!]!): FilmsPayload @mutation(typeName: INSERT)
            }
            """,
            schema -> {
                var f = (MutationField.MutationBulkDmlRecordField)
                    schema.field("Mutation", "createFilmsPayload");
                assertThat(f.kind()).isEqualTo(no.sikt.graphitron.rewrite.model.DmlKind.INSERT);
                assertThat(f.tableInputArg().list()).isTrue();
                assertThat(f.returnType().returnTypeName()).isEqualTo("FilmsPayload");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationBulkDmlRecordField.class); }
        },

        SERVICE_MUTATION_FIELD_NAME_OVERRIDE_TEXT_ENUM(
            "R53: argMapping override + text-mapped enum arg → TextMapLookup keyed by the GraphQL arg name (regression guard for enrichArgExtractions)",
            """
            enum SortDir {
                ASC @field(name: "asc")
                DESC @field(name: "desc")
            }
            type FilmDetails @record { title: String }
            type Query { x: String }
            type Mutation {
                runWithEnumOverride(direction: SortDir): FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runWithEnumOverride", argMapping: "mode: direction"})
            }
            """,
            schema -> {
                var f = (MutationField.MutationServiceRecordField) schema.field("Mutation", "runWithEnumOverride");
                var p = f.method().params().get(0);
                assertThat(p.name()).isEqualTo("mode");
                assertThat(((no.sikt.graphitron.rewrite.model.ParamSource.Arg) p.source()).graphqlArgName())
                    .isEqualTo("direction");
                var extraction = ((no.sikt.graphitron.rewrite.model.ParamSource.Arg) p.source()).extraction();
                assertThat(extraction).isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.TextMapLookup.class);
                var tl = (no.sikt.graphitron.rewrite.model.CallSiteExtraction.TextMapLookup) extraction;
                // Map field name is derived from the GraphQL arg name (DIRECTION), not the
                // Java parameter name (MODE). Catches a regression in enrichArgExtractions.
                assertThat(tl.mapFieldName()).isEqualTo("RUNWITHENUMOVERRIDE_DIRECTION_MAP");
                assertThat(tl.valueMapping()).containsEntry("ASC", "asc").containsEntry("DESC", "desc");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationServiceRecordField.class); }
        },

        SERVICE_MUTATION_FIELD_NAME_OVERRIDE_ON_ARG(
            "R53: argMapping on @service binds a GraphQL arg to a differently-named Java parameter",
            """
            input TestDtoStub { id: ID }
            type FilmDetails @record { title: String }
            type Query { x: String }
            type Mutation {
                runWithRenamedInputs(
                    input: [TestDtoStub!]!,
                    dryRun: Boolean
                ): FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runWithRenamedInputs", argMapping: "inputs: input"})
            }
            """,
            schema -> {
                var f = (MutationField.MutationServiceRecordField) schema.field("Mutation", "runWithRenamedInputs");
                var firstParam = f.method().params().get(0);
                assertThat(firstParam.name()).isEqualTo("inputs");
                assertThat(firstParam.source()).isInstanceOf(no.sikt.graphitron.rewrite.model.ParamSource.Arg.class);
                assertThat(((no.sikt.graphitron.rewrite.model.ParamSource.Arg) firstParam.source()).graphqlArgName())
                    .isEqualTo("input");
                var secondParam = f.method().params().get(1);
                assertThat(secondParam.name()).isEqualTo("dryRun");
                assertThat(((no.sikt.graphitron.rewrite.model.ParamSource.Arg) secondParam.source()).graphqlArgName())
                    .isEqualTo("dryRun");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationServiceRecordField.class); }
        },

        // ===== R150 input-bean classification =====

        SERVICE_MUTATION_FIELD_INPUT_BEAN_SINGULAR(
            "R150: @service taking a single consumer-authored record bean → ParamSource.Arg with InputBean extraction",
            """
            enum TestInputBeanEnum { LOW HIGH }
            input TestInputNested { key: String, value: String }
            input TestInputBean { title: String, rating: TestInputBeanEnum, nested: [TestInputNested!] }
            type FilmDetails @record { title: String }
            type Query { x: String }
            type Mutation {
                runWithInputBean(input: TestInputBean): FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runWithInputBean"})
            }
            """,
            schema -> {
                var f = (MutationField.MutationServiceRecordField) schema.field("Mutation", "runWithInputBean");
                var p0 = f.method().params().get(0);
                assertThat(p0.source()).isInstanceOf(no.sikt.graphitron.rewrite.model.ParamSource.Arg.class);
                var extraction = ((no.sikt.graphitron.rewrite.model.ParamSource.Arg) p0.source()).extraction();
                assertThat(extraction).isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.InputBean.class);
                var ib = (no.sikt.graphitron.rewrite.model.CallSiteExtraction.InputBean) extraction;
                assertThat(ib.beanClass().simpleName()).isEqualTo("TestInputBean");
                assertThat(ib.target())
                    .isEqualTo(no.sikt.graphitron.rewrite.model.CallSiteExtraction.InputBean.Target.RECORD);
                // Bindings cover all three SDL fields, in record component order.
                assertThat(ib.fields()).extracting(no.sikt.graphitron.rewrite.model.CallSiteExtraction.FieldBinding::sdlFieldName)
                    .containsExactly("title", "rating", "nested");
                // Enum leaf — EnumValueOf for the rating field.
                assertThat(ib.fields().get(1).leaf())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.EnumValueOf.class);
                // Nested-bean leaf — recursive InputBean for the nested list.
                assertThat(ib.fields().get(2).leaf())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.InputBean.class);
                assertThat(ib.fields().get(2).list()).isTrue();
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationServiceRecordField.class); }
        },

        SERVICE_MUTATION_FIELD_INPUT_BEAN_LIST(
            "R150: @service taking List<RecordBean> resolves the same InputBean extraction; helper-name choice is the emitter's job",
            """
            enum TestInputBeanEnum { LOW HIGH }
            input TestInputNested { key: String, value: String }
            input TestInputBean { title: String, rating: TestInputBeanEnum, nested: [TestInputNested!] }
            type FilmDetails @record { title: String }
            type Query { x: String }
            type Mutation {
                runWithInputBeans(inputs: [TestInputBean!]!): FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runWithInputBeans"})
            }
            """,
            schema -> {
                var f = (MutationField.MutationServiceRecordField) schema.field("Mutation", "runWithInputBeans");
                var p0 = f.method().params().get(0);
                assertThat(p0.typeName()).startsWith("java.util.List<");
                var extraction = ((no.sikt.graphitron.rewrite.model.ParamSource.Arg) p0.source()).extraction();
                assertThat(extraction).isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.InputBean.class);
                assertThat(((no.sikt.graphitron.rewrite.model.CallSiteExtraction.InputBean) extraction)
                    .beanClass().simpleName()).isEqualTo("TestInputBean");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationServiceRecordField.class); }
        },

        // ===== R155 input-bean primitive-field boxing =====

        SERVICE_MUTATION_FIELD_INPUT_BEAN_PRIMITIVE_RECORD(
            "R155: record bean with a primitive int component → FieldBinding.javaElementTypeName boxes to java.lang.Integer",
            """
            input TestInputBeanWithPrimitive { n: Int!, s: String }
            type FilmDetails @record { title: String }
            type Query { x: String }
            type Mutation {
                runWithInputBeanPrimitive(input: TestInputBeanWithPrimitive): FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runWithInputBeanPrimitive"})
            }
            """,
            schema -> {
                var f = (MutationField.MutationServiceRecordField) schema.field("Mutation", "runWithInputBeanPrimitive");
                var p0 = f.method().params().get(0);
                var extraction = ((no.sikt.graphitron.rewrite.model.ParamSource.Arg) p0.source()).extraction();
                var ib = (no.sikt.graphitron.rewrite.model.CallSiteExtraction.InputBean) extraction;
                assertThat(ib.target())
                    .isEqualTo(no.sikt.graphitron.rewrite.model.CallSiteExtraction.InputBean.Target.RECORD);
                var nField = ib.fields().stream()
                    .filter(fb -> fb.sdlFieldName().equals("n")).findFirst().orElseThrow();
                assertThat(nField.leaf()).isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.Direct.class);
                assertThat(nField.list()).isFalse();
                assertThat(nField.javaElementTypeName())
                    .as("primitive int component must box to java.lang.Integer so ClassName.bestGuess succeeds")
                    .isEqualTo("java.lang.Integer");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationServiceRecordField.class); }
        },

        SERVICE_MUTATION_FIELD_INPUT_JAVABEAN_PRIMITIVE_BOOLEAN(
            "R155: JavaBean with a void setActive(boolean) setter → FieldBinding.javaElementTypeName boxes to java.lang.Boolean",
            """
            input TestInputJavaBeanWithBoolean { active: Boolean! }
            type FilmDetails @record { title: String }
            type Query { x: String }
            type Mutation {
                runWithInputJavaBeanBoolean(input: TestInputJavaBeanWithBoolean): FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runWithInputJavaBeanBoolean"})
            }
            """,
            schema -> {
                var f = (MutationField.MutationServiceRecordField) schema.field("Mutation", "runWithInputJavaBeanBoolean");
                var p0 = f.method().params().get(0);
                var extraction = ((no.sikt.graphitron.rewrite.model.ParamSource.Arg) p0.source()).extraction();
                var ib = (no.sikt.graphitron.rewrite.model.CallSiteExtraction.InputBean) extraction;
                assertThat(ib.target())
                    .isEqualTo(no.sikt.graphitron.rewrite.model.CallSiteExtraction.InputBean.Target.JAVA_BEAN);
                var activeField = ib.fields().stream()
                    .filter(fb -> fb.sdlFieldName().equals("active")).findFirst().orElseThrow();
                assertThat(activeField.leaf()).isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.Direct.class);
                assertThat(activeField.list()).isFalse();
                assertThat(activeField.javaElementTypeName())
                    .as("primitive boolean setter must box to java.lang.Boolean so ClassName.bestGuess succeeds")
                    .isEqualTo("java.lang.Boolean");
            }) {
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

    // ===== DML mutation classification (Invariants #1, #7-#13 — see DmlReturnExpression and FieldBuilder.buildDmlField) =====
    //
    // Rich assertions for the four DML variants: input-shape invariants (one @table arg, no
    // listed input, no @condition, only ColumnField entries inside the input), @lookupKey gates
    // for UPDATE / DELETE / UPSERT, return-type validation, and NodeId metadata resolution for
    // ScalarReturnType(ID) returns.  Each rejection case asserts on the {@code reason} text so
    // a regressed message would surface here, not silently in production.

    enum MutationDmlCase implements ClassificationCase {

        INSERT_HAPPY_PATH(
            "INSERT with @table input → MutationInsertTableField, tableInputArg.inputTable matches the SDL @table",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT) }
            """,
            schema -> {
                var f = (MutationField.MutationInsertTableField) schema.field("Mutation", "createFilm");
                assertThat(f.tableInputArg().inputTable().tableName()).isEqualTo("film");
                assertThat(f.tableInputArg().fieldBindings()).isEmpty();
                assertThat(f.returnExpression())
                    .isEqualTo(new no.sikt.graphitron.rewrite.model.DmlReturnExpression.ProjectedSingle("Film"));
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationInsertTableField.class); }
        },

        UPDATE_LOOKUP_KEY_COVERS_SINGLE_PK(
            "UPDATE with @lookupKey on the single-column PK → MutationUpdateTableField with non-empty fieldBindings",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") {
                filmId: Int! @field(name: "film_id")
                title: String @value
            }
            type Query { x: String }
            type Mutation { updateFilm(in: FilmInput!): Film @mutation(typeName: UPDATE) }
            """,
            schema -> {
                var f = (MutationField.MutationUpdateTableField) schema.field("Mutation", "updateFilm");
                assertThat(f.tableInputArg().fieldBindings()).hasSize(1);
                assertThat(f.tableInputArg().fieldBindings().get(0).targetColumns().get(0).sqlName()).isEqualTo("film_id");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationUpdateTableField.class); }
        },

        UPDATE_NO_VALUE_FIELDS_REJECTED(
            "UPDATE without any @value fields → UnclassifiedField (R144 requires at least one @value)",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { updateFilm(in: FilmInput!): Film @mutation(typeName: UPDATE) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "updateFilm");
                assertThat(f.reason()).contains("@mutation(typeName: UPDATE) has no @value fields to set");
            }),

        UPDATE_EVERY_FIELD_VALUE_MARKED_REJECTED(
            "UPDATE where every input field is @value-marked → UnclassifiedField (R144: no filter fields → would update every row)",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") {
                title: String @value
            }
            type Query { x: String }
            type Mutation { updateFilm(in: FilmInput!): Film @mutation(typeName: UPDATE) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "updateFilm");
                assertThat(f.reason()).contains("no filter fields", "every input field is");
            }),

        UPDATE_PARTIAL_COMPOSITE_PK_REJECTED(
            "UPDATE on composite-PK table where filter columns cover only one PK column without multiRow → UnclassifiedField listing missing PK column (R144 PK-coverage)",
            """
            type FilmActor @table(name: "film_actor") { actorId: Int! @field(name: "actor_id") }
            input FilmActorInput @table(name: "film_actor") {
                actorId: Int! @field(name: "actor_id")
                lastUpdate: String @field(name: "last_update") @value
            }
            type Query { x: String }
            type Mutation { updateFilmActor(in: FilmActorInput!): FilmActor @mutation(typeName: UPDATE) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "updateFilmActor");
                assertThat(f.reason())
                    .contains("filter columns do not cover all PK column(s)")
                    .contains("film_id")
                    .contains("multiRow: true");
            }),

        UPDATE_FULL_COMPOSITE_PK_HAPPY(
            "UPDATE on composite-PK table where filter columns cover all PK columns and one @value field is present → MutationUpdateTableField with both bindings (R144)",
            """
            type FilmActor @table(name: "film_actor") { actorId: Int! @field(name: "actor_id") }
            input FilmActorInput @table(name: "film_actor") {
                actorId: Int! @field(name: "actor_id")
                filmId: Int! @field(name: "film_id")
                lastUpdate: String @field(name: "last_update") @value
            }
            type Query { x: String }
            type Mutation { updateFilmActor(in: FilmActorInput!): FilmActor @mutation(typeName: UPDATE) }
            """,
            schema -> {
                var f = (MutationField.MutationUpdateTableField) schema.field("Mutation", "updateFilmActor");
                assertThat(f.tableInputArg().fieldBindings().stream()
                        .flatMap(g -> g.targetColumns().stream())
                        .map(c -> c.sqlName())
                        .toList())
                    .containsExactlyInAnyOrder("actor_id", "film_id");
            }),

        DELETE_FULL_COMPOSITE_PK_HAPPY(
            "DELETE on composite-PK table where @lookupKey covers all PK columns → MutationDeleteTableField with both bindings",
            """
            type FilmActor @table(name: "film_actor") { actorId: Int! @field(name: "actor_id") }
            input FilmActorInput @table(name: "film_actor") {
                actorId: Int! @field(name: "actor_id")
                filmId: Int! @field(name: "film_id")
            }
            type Query { x: String }
            type Mutation { deleteFilmActor(in: FilmActorInput!): FilmActor @mutation(typeName: DELETE) }
            """,
            schema -> {
                var f = (MutationField.MutationDeleteTableField) schema.field("Mutation", "deleteFilmActor");
                assertThat(f.tableInputArg().fieldBindings().stream()
                        .flatMap(g -> g.targetColumns().stream())
                        .map(c -> c.sqlName())
                        .toList())
                    .containsExactlyInAnyOrder("actor_id", "film_id");
            }),

        DELETE_PARTIAL_COMPOSITE_PK_REJECTED(
            "DELETE on composite-PK table with only one filter column and no multiRow → UnclassifiedField (R144 PK-coverage rejection)",
            """
            type FilmActor @table(name: "film_actor") { actorId: Int! @field(name: "actor_id") }
            input FilmActorInput @table(name: "film_actor") {
                actorId: Int! @field(name: "actor_id")
            }
            type Query { x: String }
            type Mutation { deleteFilmActor(in: FilmActorInput!): FilmActor @mutation(typeName: DELETE) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "deleteFilmActor");
                assertThat(f.reason())
                    .contains("filter columns do not cover all PK column(s)", "multiRow: true");
            }),

        UPDATE_TIA_PARTITIONS_FIELDS_INTO_LOOKUP_AND_SET(
            "UPDATE TableInputArg projects fields into typed lookupKeyFields / setFields in declaration order (R144: @value-marked fields land in setFields)",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") {
                filmId: Int! @field(name: "film_id")
                title: String @value
                description: String @value
            }
            type Query { x: String }
            type Mutation { updateFilm(in: FilmInput!): Film @mutation(typeName: UPDATE) }
            """,
            schema -> {
                var tia = ((MutationField.MutationUpdateTableField) schema.field("Mutation", "updateFilm")).tableInputArg();
                assertThat(tia.lookupKeyFields()).extracting("name").containsExactly("filmId");
                assertThat(tia.setFields()).extracting("name").containsExactly("title", "description");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationUpdateTableField.class); }
        },

        DML_NESTING_FIELD_DEFERRED(
            "DML mutation with NestingField in input → UnclassifiedField (Invariant #7)",
            """
            type Film @table(name: "film") { title: String }
            input FilmTitleInput { title: String }
            input FilmInput @table(name: "film") { details: FilmTitleInput }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "createFilm");
                assertThat(f.reason()).contains("nested input types in @mutation fields are not yet supported");
            }),

        DML_TWO_TABLE_INPUT_ARGS_REJECTED(
            "DML mutation with two @table input arguments → UnclassifiedField (Invariant #1)",
            """
            type Film @table(name: "film") { title: String }
            input FilmInputA @table(name: "film") { title: String }
            input FilmInputB @table(name: "film") { description: String }
            type Query { x: String }
            type Mutation { createFilm(a: FilmInputA, b: FilmInputB): Film @mutation(typeName: INSERT) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "createFilm");
                assertThat(f.reason()).contains("more than one @table input argument");
            }),

        DML_PLAIN_INPUT_ARG_REJECTED(
            "DML mutation with a plain (non-@table) input arg → UnclassifiedField (Invariant #13)",
            """
            type Film @table(name: "film") { title: String }
            input PlainOptions { reason: String }
            type Query { x: String }
            type Mutation { createFilm(opts: PlainOptions!): Film @mutation(typeName: INSERT) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "createFilm");
                assertThat(f.reason()).contains("@mutation fields only accept @table input arguments");
            }),

        DML_INSERT_LIST_LIST_OK(
            "DML INSERT with listed input + listed @table return → MutationInsertTableField with tia.list() == true",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { createFilms(in: [FilmInput!]!): [Film!]! @mutation(typeName: INSERT) }
            """,
            schema -> {
                var f = (MutationField.MutationInsertTableField) schema.field("Mutation", "createFilms");
                assertThat(f.tableInputArg().list()).isTrue();
                assertThat(f.returnExpression())
                    .isEqualTo(new no.sikt.graphitron.rewrite.model.DmlReturnExpression.ProjectedList("Film"));
            }),

        DML_UPDATE_LIST_LIST_OK(
            "DML UPDATE with listed input + listed @table return → MutationUpdateTableField with tia.list() == true",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") {
                filmId: Int! @field(name: "film_id")
                title: String @value
            }
            type Query { x: String }
            type Mutation { updateFilms(in: [FilmInput!]!): [Film!]! @mutation(typeName: UPDATE) }
            """,
            schema -> {
                var f = (MutationField.MutationUpdateTableField) schema.field("Mutation", "updateFilms");
                assertThat(f.tableInputArg().list()).isTrue();
                assertThat(f.returnExpression())
                    .isEqualTo(new no.sikt.graphitron.rewrite.model.DmlReturnExpression.ProjectedList("Film"));
            }),

        DML_DELETE_LIST_LIST_OK(
            "DML DELETE with listed input + listed @table return → MutationDeleteTableField with tia.list() == true",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") {
                filmId: Int! @field(name: "film_id")
            }
            type Query { x: String }
            type Mutation { deleteFilms(in: [FilmInput!]!): [Film!]! @mutation(typeName: DELETE) }
            """,
            schema -> {
                var f = (MutationField.MutationDeleteTableField) schema.field("Mutation", "deleteFilms");
                assertThat(f.tableInputArg().list()).isTrue();
                assertThat(f.returnExpression())
                    .isEqualTo(new no.sikt.graphitron.rewrite.model.DmlReturnExpression.ProjectedList("Film"));
            }),

        DML_UPSERT_LIST_LIST_REJECTED_UNDER_R144(
            "DML UPSERT with listed input + listed @table return → UnclassifiedField (R144 refuses UPSERT pending R145)",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") {
                filmId: Int! @field(name: "film_id")
                title: String @value
            }
            type Query { x: String }
            type Mutation { upsertFilms(in: [FilmInput!]!): [Film!]! @mutation(typeName: UPSERT) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "upsertFilms");
                assertThat(f.reason()).contains("@mutation(typeName: UPSERT) is not supported under the R144");
            }),

        DML_INSERT_LIST_SINGLE_T_REJECTED(
            "DML INSERT with listed input + single @table return → UnclassifiedField (Invariant #15)",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { createFilms(in: [FilmInput!]!): Film @mutation(typeName: INSERT) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "createFilms");
                assertThat(f.reason())
                    .contains("must return a list")
                    .contains("Invariant #15");
            }),

        DML_INSERT_LIST_SINGLE_ID_REJECTED(
            "DML INSERT with listed input + single ID return → UnclassifiedField (Invariant #15, encoded-single arm)",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { createFilms(in: [FilmInput!]!): ID @mutation(typeName: INSERT) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "createFilms");
                assertThat(f.reason())
                    .contains("must return a list")
                    .contains("Invariant #15");
            }),

        DML_INSERT_LIST_PAYLOAD_REJECTED(
            "DML INSERT with listed input + @record payload return → UnclassifiedField (Invariant #15)",
            """
            type Film @table(name: "film") { title: String }
            type FilmPayload @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DeleteFilmRowOnlyPayload"}) {
                film: Film
            }
            input FilmInput @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { createFilms(in: [FilmInput!]!): FilmPayload @mutation(typeName: INSERT) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "createFilms");
                assertThat(f.reason())
                    .contains("must return a list")
                    .contains("Invariant #15");
            }),

        DML_INSERT_LIST_PLAIN_PAYLOAD_REJECTED(
            "DML INSERT with listed input + plain SDL Object payload return + singleton-shaped "
                + "data field → UnclassifiedField (R138's lifted Invariant #15). R141 routes the "
                + "complementary cell (bulk input + list-shaped data field) to "
                + "MutationBulkDmlRecordField; this row covers the unchanged singleton-arm "
                + "rejection.",
            """
            type Film @table(name: "film") { title: String }
            type FilmPayload { film: Film }
            input FilmInput @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { createFilmsPayload(in: [FilmInput!]!): FilmPayload @mutation(typeName: INSERT) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "createFilmsPayload");
                assertThat(f.reason())
                    .contains("must return a list")
                    .contains("Invariant #15");
            }),

        DML_INSERT_SINGLE_LIST_DATA_REJECTED(
            "R141: DML INSERT with single @table input + list-shaped data field on the carrier "
                + "→ UnclassifiedField (Invariant #16). Complementary cell of the bulk-input + "
                + "list-data-field admit; surfacing the cardinality mismatch as a typed rejection "
                + "rather than letting the per-row DML emit a list against single input.",
            """
            type Film @table(name: "film") { title: String }
            type FilmsPayload { films: [Film!] }
            input FilmInput @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { createFilmPayload(in: FilmInput!): FilmsPayload @mutation(typeName: INSERT) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "createFilmPayload");
                assertThat(f.reason())
                    .contains("single @table input cannot")
                    .contains("list-shaped data field")
                    .contains("Invariant #16");
            }),

        DML_INSERT_LIST_PAYLOAD_UNRECOGNIZED_DATA_FIELD_REJECTED(
            "R141: DML INSERT with bulk @table input + carrier carrying a sibling field that "
                + "does not resolve to a recognized DML payload data-field shape → "
                + "UnclassifiedField. The structural scan rejects with a descriptive reason "
                + "naming the offending field and the extension point (file a roadmap item).",
            """
            type Film @table(name: "film") { title: String }
            type FilmsPayload { films: [Film!] affectedRowCount: Int }
            input FilmInput @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation {
                createFilmsPayload(in: [FilmInput!]!): FilmsPayload @mutation(typeName: INSERT)
            }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "createFilmsPayload");
                assertThat(f.reason())
                    .contains("affectedRowCount")
                    .contains("not a recognized DML payload data-field shape")
                    .contains("file a roadmap item");
            }),

        DML_NON_ID_RETURN_REJECTED(
            "DML mutation with non-ID/non-@table return → UnclassifiedField (Invariant #14)",
            """
            input FilmInput @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): Int @mutation(typeName: INSERT) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "createFilm");
                assertThat(f.reason())
                    .contains("@mutation(typeName: INSERT) return type 'Int' is not yet supported")
                    .contains("use ID or a @table type");
            }),

        DML_BOOLEAN_RETURN_REJECTED(
            "DML mutation with Boolean return → UnclassifiedField (Invariant #14)",
            """
            input FilmInput @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): Boolean @mutation(typeName: INSERT) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "createFilm");
                assertThat(f.reason())
                    .contains("'Boolean' is not yet supported");
            }),

        DML_ID_RETURN_NON_NODE_TABLE_REJECTED(
            "DML mutation returning ID without a matching @node SDL type → UnclassifiedField",
            """
            input FilmInput @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): ID @mutation(typeName: INSERT) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "createFilm");
                assertThat(f.reason())
                    .contains("no @node type is declared for table 'film'");
            }),

        DML_TABLE_RETURN_NON_NODE_HAPPY(
            "DML mutation returning a @table type on a non-@node table → classified successfully (encodeReturn empty)",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT) }
            """,
            schema -> {
                var f = (MutationField.MutationInsertTableField) schema.field("Mutation", "createFilm");
                assertThat(f.returnExpression())
                    .isEqualTo(new no.sikt.graphitron.rewrite.model.DmlReturnExpression.ProjectedSingle("Film"));
            }),

        DML_LIST_LOOKUP_KEY_FIELD_REJECTED(
            "DML mutation with list-typed admissible carrier input field → UnclassifiedField (R144: list cardinality must live on the outer argument)",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") {
                filmIds: [Int!]! @field(name: "film_id")
                title: String @value
            }
            type Query { x: String }
            type Mutation { updateFilm(in: FilmInput!): Film @mutation(typeName: UPDATE) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "updateFilm");
                assertThat(f.reason()).contains("list-typed input field is not supported");
            }),

        DML_RECORD_PAYLOAD_WITH_ERRORS_HAPPY(
            "DML returning a @record carrier with film+errors → MutationDmlRecordField (@record(record:{className:}) on a Java-record-backed wrapper, admitted by the structural DML-payload scan)",
            """
            type ValidationErr @error(handlers: [{handler: VALIDATION}]) {
                path: [String!]!
                message: String!
            }
            type DbErr @error(handlers: [{handler: DATABASE, sqlState: "23503"}]) {
                path: [String!]!
                message: String!
            }
            union DeleteFilmError = ValidationErr | DbErr
            type Film @table(name: "film") { title: String }
            type DeleteFilmPayload @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DeleteFilmPayload"}) {
                film: Film
                errors: [DeleteFilmError]
            }
            input FilmInput @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { x: String }
            type Mutation { deleteFilm(in: FilmInput!): DeleteFilmPayload @mutation(typeName: DELETE) }
            """,
            schema -> {
                var f = (MutationField.MutationDmlRecordField) schema.field("Mutation", "deleteFilm");
                assertThat(f.kind()).isEqualTo(no.sikt.graphitron.rewrite.model.DmlKind.DELETE);
                assertThat(f.errorChannel()).isPresent();
            }),

        DML_RECORD_PAYLOAD_ROW_ONLY_HAPPY(
            "DML returning a @record carrier with film only → MutationDmlRecordField (bare carrier on JavaRecordType wrapper, no errors channel, admitted by the structural DML-payload scan)",
            """
            type Film @table(name: "film") { title: String }
            type DeleteFilmPayload @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DeleteFilmRowOnlyPayload"}) {
                film: Film
            }
            input FilmInput @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { x: String }
            type Mutation { deleteFilm(in: FilmInput!): DeleteFilmPayload @mutation(typeName: DELETE) }
            """,
            schema -> {
                var f = (MutationField.MutationDmlRecordField) schema.field("Mutation", "deleteFilm");
                assertThat(f.kind()).isEqualTo(no.sikt.graphitron.rewrite.model.DmlKind.DELETE);
                assertThat(f.errorChannel()).isEmpty();
            }),

        DML_RECORD_PAYLOAD_LIST_REJECTED(
            "DML returning a list of @record payloads → UnclassifiedField (validateReturnType, list-payload not yet supported)",
            """
            type Film @table(name: "film") { title: String }
            type DeleteFilmPayload @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.DeleteFilmRowOnlyPayload"}) {
                film: Film
            }
            input FilmInput @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { x: String }
            type Mutation { deleteFilms(in: FilmInput!): [DeleteFilmPayload] @mutation(typeName: DELETE) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "deleteFilms");
                assertThat(f.reason())
                    .contains("(list of @record) is not yet supported");
            }),

        DML_RECORD_PAYLOAD_NO_DATA_FIELD_REJECTED(
            "R161: DML returning a @record carrier with no data-channel-shaped field → UnclassifiedField (the structural scan rejects 'data: String' as an unrecognized carrier data-field shape since String is neither @table nor ID; user's developer-supplied class is no longer inspected)",
            """
            type Film @table(name: "film") { title: String }
            type SakPayload @record(record: {className: "no.sikt.graphitron.codereferences.dummyreferences.SakPayload"}) {
                data: String
                errors: [ValidationErr]
            }
            type ValidationErr @error(handlers: [{handler: VALIDATION}]) {
                path: [String!]!
                message: String!
            }
            input FilmInput @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { x: String }
            type Mutation { deleteFilm(in: FilmInput!): SakPayload @mutation(typeName: DELETE) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "deleteFilm");
                assertThat(f.reason())
                    .contains("not a recognized DML payload data-field shape");
            }),

        // ===== R144 new admission and rejection cases =====

        R144_DELETE_PK_COVERED_ADMIT(
            "R144: DELETE with PK-covering filter input → MutationDeleteTableField",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { x: String }
            type Mutation { deleteFilm(in: FilmInput!): Film @mutation(typeName: DELETE) }
            """,
            schema -> assertThat(schema.field("Mutation", "deleteFilm")).isInstanceOf(MutationField.MutationDeleteTableField.class)),

        R144_DELETE_MULTIROW_ADMIT(
            "R144: DELETE without PK-covering filter but with multiRow: true → MutationDeleteTableField (broadcast opt-in)",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { title: String @field(name: "title") }
            type Query { x: String }
            type Mutation { deleteFilms(in: FilmInput!): Film @mutation(typeName: DELETE, multiRow: true) }
            """,
            schema -> assertThat(schema.field("Mutation", "deleteFilms")).isInstanceOf(MutationField.MutationDeleteTableField.class)),

        R144_INSERT_MULTIROW_REJECTED(
            "R144: multiRow: true on @mutation(typeName: INSERT) → UnclassifiedField",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT, multiRow: true) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "createFilm");
                assertThat(f.reason()).contains("INSERT", "does not accept multiRow: true");
            }),

        R144_DELETE_VALUE_DIRECTIVE_REJECTED(
            "R144: @value on DELETE input field → UnclassifiedField (DELETE has no assignment clause)",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") {
                filmId: Int! @field(name: "film_id")
                title: String @value
            }
            type Query { x: String }
            type Mutation { deleteFilm(in: FilmInput!): Film @mutation(typeName: DELETE) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "deleteFilm");
                assertThat(f.reason()).contains("@value is not valid", "DELETE");
            }),

        R144_VALUE_WITH_CONDITION_REJECTED(
            "R144: @value and @condition on the same input field → UnclassifiedField (mutually exclusive)",
            """
            type Film @table(name: "film") { title: String, rating: String }
            input FilmInput @table(name: "film") {
                filmId: Int! @field(name: "film_id") @value
                    @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "inputColumnCondition"})
            }
            type Query { x: String }
            type Mutation { updateFilm(in: FilmInput!): Film @mutation(typeName: UPDATE) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "updateFilm");
                assertThat(f.reason()).contains("@value and @condition", "mutually exclusive");
            });

        final String description;
        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        MutationDmlCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.description = description;
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() { return Set.of(); }
        @Override public String toString() { return description; }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(MutationDmlCase.class)
    void mutationDmlClassification(MutationDmlCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== MutationDeletePayloadCase (R156) =====

    /**
     * R156 admission / rejection matrix for {@code @mutation(typeName: DELETE)} carriers. The
     * structural DML-payload scan admits two element-shape arms (the ID-typed PK-echo shape and
     * the {@code @table}-element shape with a clean PK-only projection) and rejects everything
     * else, including the ID-typed shape on non-DELETE verbs (the post-image of
     * INSERT/UPDATE/UPSERT is richer than the PK), list-of-nullable {@code [ID]} wrappers,
     * {@code @table}-element types with non-PK non-nullable / {@code @service} / unsupported
     * (FK-traversing / child-collection) fields, and ID-typed carriers whose input
     * {@code @table} is not {@code @node}-backed or whose explicit {@code @nodeId} pins to a
     * different table.
     */
    enum MutationDeletePayloadCase implements ClassificationCase {

        BULK_DELETE_TABLE_NULLABLE_NON_PK_ADMITS(
            "bulk DELETE + [Foo!] carrier with only nullable non-PK column fields → MutationBulkDmlRecordField + SingleRecordTableFieldFromReturning with PkResolution.NonPkNullable projection",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type DeletedFilmsPayload { deleted: [Film!] }
            type Query { x: String }
            type Mutation { deleteFilms(in: [FilmInput!]!): DeletedFilmsPayload @mutation(typeName: DELETE) }
            """,
            schema -> {
                var mut = schema.field("Mutation", "deleteFilms");
                assertThat(mut).isInstanceOf(MutationField.MutationBulkDmlRecordField.class);
                var dataField = schema.field("DeletedFilmsPayload", "deleted");
                assertThat(dataField).isInstanceOf(ChildField.SingleRecordTableFieldFromReturning.class);
                var tableCarrier = (ChildField.SingleRecordTableFieldFromReturning) dataField;
                assertThat(tableCarrier.projection())
                    .anyMatch(arm -> arm instanceof no.sikt.graphitron.rewrite.model.PkResolution.NonPkNullable n
                                  && n.fieldName().equals("title"));
            }) {
            @Override public Set<Class<?>> variants() {
                return Set.of(MutationField.MutationBulkDmlRecordField.class,
                    ChildField.SingleRecordTableFieldFromReturning.class);
            }
        },

        BULK_DELETE_TABLE_NON_NULL_NON_PK_REJECTS(
            "DELETE + [Foo!] with non-null non-PK column → UnclassifiedField naming the offending field, pointing at the ID-typed carrier shape",
            """
            type Film @table(name: "film") { title: String! }
            input FilmInput @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type DeletedFilmsPayload { deleted: [Film!] }
            type Query { x: String }
            type Mutation { deleteFilms(in: [FilmInput!]!): DeletedFilmsPayload @mutation(typeName: DELETE) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "deleteFilms");
                assertThat(f.reason()).contains("title", "non-primary-key columns", "non-nullable", "ID-typed carrier field");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        INSERT_ID_PAYLOAD_REJECTS(
            "INSERT + [ID!] carrier → UnclassifiedField (the ID-typed PK-echo shape is admitted only on DELETE)",
            """
            type Film @table(name: "film") @node(typeId: "Film", keyColumns: ["film_id"]) { id: ID! @nodeId title: String }
            input FilmInput @table(name: "film") { title: String }
            type InsertedFilmsPayload { insertedIds: [ID!] }
            type Query { x: String }
            type Mutation { insertFilms(in: [FilmInput!]!): InsertedFilmsPayload @mutation(typeName: INSERT) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "insertFilms");
                assertThat(f.reason()).contains("element type ID", "PK-echo permit", "DELETE",
                    "INSERT");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        DELETE_LIST_OF_NULLABLE_REJECTS(
            "DELETE + [ID] (list-of-nullable) carrier → UnclassifiedField at the structural scan (wrapper-shape reject)",
            """
            type Film @table(name: "film") @node(typeId: "Film", keyColumns: ["film_id"]) { id: ID! @nodeId title: String }
            input FilmInput @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type DeletedFilmsPayload { deletedIds: [ID] }
            type Query { x: String }
            type Mutation { deleteFilms(in: [FilmInput!]!): DeletedFilmsPayload @mutation(typeName: DELETE) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "deleteFilms");
                assertThat(f.reason()).contains("ID", "list-of-nullable", "list-of-non-null");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        UPDATE_ID_PAYLOAD_REJECTS(
            "UPDATE + [ID!] carrier → UnclassifiedField (the ID-typed PK-echo shape is admitted only on DELETE)",
            """
            type Film @table(name: "film") @node(typeId: "Film", keyColumns: ["film_id"]) { id: ID! @nodeId title: String }
            input FilmInput @table(name: "film") { filmId: Int! @field(name: "film_id") title: String @value }
            type UpdatedFilmsPayload { updatedIds: [ID!] }
            type Query { x: String }
            type Mutation { updateFilms(in: [FilmInput!]!): UpdatedFilmsPayload @mutation(typeName: UPDATE) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "updateFilms");
                assertThat(f.reason()).contains("element type ID", "PK-echo permit", "DELETE", "UPDATE");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        UPSERT_ID_PAYLOAD_REJECTS(
            "UPSERT + [ID!] carrier → UnclassifiedField (the ID-typed shape's verb rule fires before R144's UPSERT-defer)",
            """
            type Film @table(name: "film") @node(typeId: "Film", keyColumns: ["film_id"]) { id: ID! @nodeId title: String }
            input FilmInput @table(name: "film") { filmId: Int! @field(name: "film_id") title: String @value }
            type UpsertedFilmsPayload { upsertedIds: [ID!] }
            type Query { x: String }
            type Mutation { upsertFilms(in: [FilmInput!]!): UpsertedFilmsPayload @mutation(typeName: UPSERT) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "upsertFilms");
                // The rejection message comes from either the R156 permit-verb gate or
                // R144's upstream UPSERT-defer (whichever fires first); both surface as
                // UnclassifiedField, both name the verb.
                assertThat(f.reason()).contains("UPSERT");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        DELETE_TABLE_SERVICE_FIELD_REJECTS(
            "DELETE + [Foo!] with a @service-resolved field on the element type → UnclassifiedField pointing at the ID-typed carrier shape",
            """
            type Film @table(name: "film") {
                title: String
                computedThing: String @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"})
            }
            input FilmInput @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type DeletedFilmsPayload { deleted: [Film!] }
            type Query { x: String }
            type Mutation { deleteFilms(in: [FilmInput!]!): DeletedFilmsPayload @mutation(typeName: DELETE) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "deleteFilms");
                assertThat(f.reason()).contains("computedThing", "@service", "ID-typed carrier field");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        ;

        final String description;
        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        MutationDeletePayloadCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.description = description;
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() { return Set.of(); }
        @Override public String toString() { return description; }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(MutationDeletePayloadCase.class)
    void mutationDeletePayloadClassification(MutationDeletePayloadCase tc) {
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
            schema -> assertThat(schema.field("Untyped", "value")).isNull()),

        SERVICE_AT_ROOT_WITH_CONNECTION_RETURN_REJECTED(
            "@service at root returning a Connection-shaped type → UnclassifiedField (Invariants §1)",
            """
            type Film @table(name: "film") { title: String }
            type FilmConnection {
                edges: [FilmEdge]
                pageInfo: PageInfo!
            }
            type FilmEdge {
                node: Film
                cursor: String!
            }
            type PageInfo {
                hasNextPage: Boolean!
                hasPreviousPage: Boolean!
                startCursor: String
                endCursor: String
            }
            type Query {
                externalFilms(first: Int, after: String): FilmConnection
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"})
            }
            """,
            schema -> {
                var f = schema.field("Query", "externalFilms");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("@service at the root does not support Connection return types");
            }),

        TABLEMETHOD_AT_ROOT_WITH_CONNECTION_RETURN_REJECTED(
            "@tableMethod at root returning a Connection-shaped type → UnclassifiedField (Invariants §1)",
            """
            type Film @table(name: "film") { title: String }
            type FilmConnection {
                edges: [FilmEdge]
                pageInfo: PageInfo!
            }
            type FilmEdge {
                node: Film
                cursor: String!
            }
            type PageInfo {
                hasNextPage: Boolean!
                hasPreviousPage: Boolean!
                startCursor: String
                endCursor: String
            }
            type Query {
                methodFilms(first: Int, after: String): FilmConnection
                    @tableMethod(className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "get")
            }
            """,
            schema -> {
                var f = schema.field("Query", "methodFilms");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("@tableMethod at the root does not support Connection return types");
            }),

        SERVICE_AT_ROOT_WITH_SOURCES_PARAM_REJECTED(
            "@service at root with List<Row1<Integer>> parameter → UnclassifiedField (Invariants §2)",
            """
            type Film @table(name: "film") { title: String }
            type Query {
                batchedFilms: Film @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmWithSources"})
            }
            """,
            schema -> {
                var f = schema.field("Query", "batchedFilms");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("@service at the root does not support List<Row>/List<Record> batch parameters");
            }),

        SERVICE_AT_ROOT_WITH_TABLERECORD_PARAM_NAME_MISMATCH_REJECTED(
            "R185: @service at root with List<FilmRecord> parameter whose name doesn't match any GraphQL argument → arg-mismatch diagnostic (not Sources-batch)",
            """
            type Film @table(name: "film") { title: String }
            type Query {
                batchedFilms(input: [ID!]): [Film!]!
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmsWithTableRecordSources"})
            }
            """,
            schema -> {
                var f = schema.field("Query", "batchedFilms");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("does not match any GraphQL argument or context key")
                    .contains("input")
                    .doesNotContain("does not support List<Row>");
            }),

        TABLEMETHOD_WITH_WIDER_RETURN_TYPE_REJECTED(
            "@tableMethod whose method returns a wider Table<?> rather than the generated jOOQ table class → UnclassifiedField (Invariants §3)",
            """
            type Film @table(name: "film") { title: String }
            type Query {
                wider: [Film!]! @tableMethod(className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "get")
            }
            """,
            schema -> {
                var f = schema.field("Query", "wider");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("must return the generated jOOQ table class");
            }),

        TABLEMETHOD_AT_ROOT_WITH_SCALAR_RETURN_REJECTED(
            "@tableMethod at root returning a scalar → UnclassifiedField (R43: directive binds a jOOQ table method, always table-typed)",
            """
            type Film @table(name: "film") { title: String }
            type Query {
                count: Int @tableMethod(className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "getFilm")
            }
            """,
            schema -> {
                var f = schema.field("Query", "count");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .isEqualTo("@tableMethod requires a @table-annotated return type");
            }),

        TABLEMETHOD_ON_CHILD_WITH_SCALAR_RETURN_REJECTED(
            "@tableMethod on a child field returning a scalar → UnclassifiedField (R43: clear schema error, not a deferred stub)",
            """
            type Film @table(name: "film") {
                title: String @tableMethod(className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "getFilm")
            }
            type Query { film: Film }
            """,
            schema -> {
                var f = schema.field("Film", "title");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .isEqualTo("@tableMethod requires a @table-annotated return type");
            }),

        TABLEMETHOD_CHILD_AMBIGUOUS_FK_REJECTED(
            "@tableMethod on a child of a @table parent with multiple FKs between parent and target and no @reference → UnclassifiedField (R43 commit 2)",
            """
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                language: Language @tableMethod(className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "getLanguage")
            }
            type Query { film: Film }
            """,
            schema -> {
                var f = schema.field("Film", "language");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("multiple foreign keys")
                    .contains("'film'")
                    .contains("'language'");
            }),

        TABLEMETHOD_CHILD_MISSING_FK_REJECTED(
            "@tableMethod on a child of a @table parent with no FK between parent and target and no @reference → UnclassifiedField (R43 commit 2)",
            """
            type Actor @table(name: "actor") { firstName: String }
            type Film @table(name: "film") {
                actors: [Actor!]! @tableMethod(className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "getActor")
            }
            type Query { film: Film }
            """,
            schema -> {
                var f = schema.field("Film", "actors");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("no foreign key")
                    .contains("'film'")
                    .contains("'actor'");
            }),

        TABLEMETHOD_CHILD_LAST_HOP_MISMATCH_REJECTED(
            "@tableMethod with @reference whose last hop lands on a table other than the return-type table → UnclassifiedField (R43 commit 2)",
            """
            type Actor @table(name: "actor") { firstName: String }
            type Film @table(name: "film") {
                wrongTarget: Actor
                    @tableMethod(className: "no.sikt.graphitron.rewrite.TestTableMethodStub", method: "getActor")
                    @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { film: Film }
            """,
            schema -> {
                var f = schema.field("Film", "wrongTarget");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("last hop lands on 'language'")
                    .contains("'actor'");
            }),

        SERVICE_WITH_WRONG_RETURN_TYPE_REJECTED(
            "@service at root whose method's return type does not match the field's @table-bound return → UnclassifiedField (strict service return-type)",
            """
            type Film @table(name: "film") { title: String }
            type Query {
                wrongReturn: Film @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"})
            }
            """,
            schema -> {
                var f = schema.field("Query", "wrongReturn");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("must return 'FilmRecord'");
            }),

        CHILD_SERVICE_TABLE_BOUND_WRONG_RETURN_REJECTED(
            "child @service on @table parent whose declared return doesn't match the rows-method outer shape → UnclassifiedField",
            """
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                language: Language @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "childServiceRowKeyedWrongReturn"})
            }
            type Query { film: Film }
            """,
            schema -> {
                var f = schema.field("Film", "language");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("must return 'List<LanguageRecord>'");
            }),

        CHILD_SERVICE_TABLE_BOUND_RAW_RECORD_REJECTED(
            "R177 migration arm: child @service declaring raw List<Record> for a List<TableBound> field → UnclassifiedField post-R177 (was accepted pre-R177)",
            """
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                languages: [Language] @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "childServiceRowKeyedRawRecordList"})
            }
            type Query { film: Film }
            """,
            schema -> {
                var f = schema.field("Film", "languages");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("must return 'List<List<LanguageRecord>>'");
            }),

        CHILD_SERVICE_TABLE_BOUND_SPECIFIC_RECORD_ACCEPTED(
            "R177 acceptance arm: child @service declaring List<List<LanguageRecord>> for a List<TableBound> field → classified (was rejected pre-R177)",
            """
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                languages: [Language] @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "childServiceRowKeyedSpecificRecordList"})
            }
            type Query { film: Film }
            """,
            schema -> {
                var f = schema.field("Film", "languages");
                assertThat(f).isNotInstanceOf(UnclassifiedField.class);
            }),

        CHILD_SERVICE_TABLE_BOUND_WRONG_RECORD_REJECTED(
            "R177 cross-record regression arm: child @service declaring List<List<FilmRecord>> for a List<Language> field → UnclassifiedField (wrong jOOQ record class)",
            """
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                languages: [Language] @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "childServiceRowKeyedWrongRecordList"})
            }
            type Query { film: Film }
            """,
            schema -> {
                var f = schema.field("Film", "languages");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("must return 'List<List<LanguageRecord>>'");
            }),

        CHILD_SERVICE_SCALAR_WRONG_VALUE_TYPE_REJECTED(
            "child @service on @table parent declaring Map<K, Integer> for a String-valued field → UnclassifiedField",
            """
            type Film @table(name: "film") {
                titleUppercase: String @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "childServiceMappedRecordKeyedWrongScalarValue"})
            }
            type Query { film: Film }
            """,
            schema -> {
                var f = schema.field("Film", "titleUppercase");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("must return 'Map<Record1<Integer>, String>'");
            }),

        MUTATION_SERVICE_WITH_CONNECTION_RETURN_REJECTED(
            "@service on mutation field returning a Connection-shaped type → UnclassifiedField (Invariants §1, mutation arm)",
            """
            type Film @table(name: "film") { title: String }
            type FilmConnection {
                edges: [FilmEdge]
                pageInfo: PageInfo!
            }
            type FilmEdge {
                node: Film
                cursor: String!
            }
            type PageInfo {
                hasNextPage: Boolean!
                hasPreviousPage: Boolean!
                startCursor: String
                endCursor: String
            }
            type Query { x: String }
            type Mutation {
                paginatedMutation: FilmConnection
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "run"})
            }
            """,
            schema -> {
                var f = schema.field("Mutation", "paginatedMutation");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("@service at the root does not support Connection return types");
            }),

        MUTATION_SERVICE_WITH_SOURCES_PARAM_REJECTED(
            "@service on mutation field with a List<Row1<Integer>> parameter → UnclassifiedField (Invariants §2, mutation arm)",
            """
            type Film @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation {
                batchedMutation: [Film!]!
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmsWithSources"})
            }
            """,
            schema -> {
                var f = schema.field("Mutation", "batchedMutation");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("@service at the root does not support List<Row>/List<Record> batch parameters");
            }),

        SERVICE_WITH_INNER_GENERIC_MISMATCH_REJECTED(
            "@service at root with right outer wrapper but wrong inner generic → UnclassifiedField (strict comparison is structural, not raw-class)",
            """
            type Film @table(name: "film") { title: String }
            type Query {
                innerMismatch: [Film!]!
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getLanguages"})
            }
            """,
            schema -> {
                var f = schema.field("Query", "innerMismatch");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("must return")
                    .contains("FilmRecord")
                    .contains("LanguageRecord");
            }),

        SERVICE_WITH_LIST_VS_SINGLE_MISMATCH_REJECTED(
            "@service at root: Single field whose method returns Result<FilmRecord> → UnclassifiedField (cardinality mismatch)",
            """
            type Film @table(name: "film") { title: String }
            type Query {
                cardinalityMismatch: Film
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilms"})
            }
            """,
            schema -> {
                var f = schema.field("Query", "cardinalityMismatch");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("must return")
                    .contains("FilmRecord");
            }),

        SERVICE_WITH_RECORD_BACKING_CLASS_MISMATCH_SILENT_CORRECT(
            "R96: @service at root: @record-declared className disagrees with the method's reflected "
                + "return — reflection wins silently; directive-ignored 'Disagrees' warning fires.",
            """
            type FilmDetails @record(record: {className: "no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord"}) {
                title: String
            }
            type Query {
                filmDetails: FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getLanguage"})
            }
            """,
            schema -> {
                // R96: the directive's claim ("FilmRecord") is informational only; the walker's
                // reflection-derived class ("LanguageRecord") wins. FilmDetails classifies as
                // JooqTableRecordType backed by LanguageRecord, and the directive-ignored
                // warning's Disagrees variant surfaces the lie.
                assertThat(schema.warnings())
                    .extracting(BuildWarning::message)
                    .anyMatch(m -> m.contains("FilmDetails")
                        && m.contains("derives a different backing class")
                        && m.contains("LanguageRecord"));
            }),

        MUTATION_SERVICE_WITH_WRONG_RETURN_TYPE_REJECTED(
            "@service on mutation field with mismatched return type → UnclassifiedField (strict-return applies on mutation arm too)",
            """
            type Film @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation {
                wrongReturnMutation: Film
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "run"})
            }
            """,
            schema -> {
                var f = schema.field("Mutation", "wrongReturnMutation");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("must return 'FilmRecord'");
            }),

        NOT_GENERATED_DIRECTIVE_REJECTED(
            "@notGenerated is no longer supported → UnclassifiedField with reason saying so",
            """
            type Film @table(name: "film") { title: String @notGenerated }
            type Query { film: Film }
            """,
            schema -> {
                assertThat(schema.field("Film", "title")).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) schema.field("Film", "title")).reason())
                    .contains("@notGenerated", "no longer supported");
            }),

        NOT_GENERATED_REJECTED_PLAIN_INPUT_ARG(
            "@notGenerated on a plain input-arg field → surrounding query field is UnclassifiedField with reason saying so",
            """
            input FilmFilter { title: String, hidden: String @notGenerated }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { films(f: FilmFilter): [Film] }
            """,
            schema -> {
                var f = schema.field("Query", "films");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("@notGenerated", "no longer supported");
            }),

        SERVICE_ARG_MAPPING_DUPLICATE_JAVA_TARGET(
            "R53: argMapping with two entries for the same Java parameter → UnclassifiedField (parser-level rejection)",
            """
            input TestDtoStub { id: ID }
            type FilmDetails @record { title: String }
            type Query { x: String }
            type Mutation {
                runWithRenamedInputs(
                    input: [TestDtoStub!]!,
                    extras: [TestDtoStub!]!
                ): FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runWithRenamedInputs", argMapping: "inputs: input, inputs: extras"})
            }
            """,
            schema -> {
                var f = schema.field("Mutation", "runWithRenamedInputs");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("argMapping has duplicate entries for Java parameter 'inputs'");
            }),

        SERVICE_ARG_MAPPING_UNKNOWN_GRAPHQL_ARG(
            "R53: argMapping references a GraphQL argument not on the field → UnclassifiedField (pre-reflection rejection)",
            """
            input TestDtoStub { id: ID }
            type FilmDetails @record { title: String }
            type Query { x: String }
            type Mutation {
                runWithRenamedInputs(
                    input: [TestDtoStub!]!,
                    dryRun: Boolean
                ): FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runWithRenamedInputs", argMapping: "inputs: notAnArg"})
            }
            """,
            schema -> {
                var f = schema.field("Mutation", "runWithRenamedInputs");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("argMapping entry 'inputs: notAnArg'")
                    .contains("references GraphQL argument 'notAnArg'");
            }),

        SERVICE_ARG_MAPPING_TYPO_GUARD(
            "R53: argMapping references a Java parameter that does not exist on the resolved method → UnclassifiedField (post-reflection typo guard)",
            """
            input TestDtoStub { id: ID }
            type FilmDetails @record { title: String }
            type Query { x: String }
            type Mutation {
                runWithRenamedInputs(
                    input: [TestDtoStub!]!,
                    dryRun: Boolean
                ): FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runWithRenamedInputs", argMapping: "missing: input"})
            }
            """,
            schema -> {
                var f = schema.field("Mutation", "runWithRenamedInputs");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("argMapping entry 'missing: input'")
                    .contains("references Java parameter 'missing'");
            }),

        SERVICE_MAP_PARAM_FOR_INPUT_OBJECT_REJECTED(
            "R150: @service with Map<String, Object> Java param paired with an input-object SDL slot → UnclassifiedField; Map is a permanent anti-pattern at the service boundary, not a v1 deferral",
            """
            input TestInputBean { title: String }
            type FilmDetails @record { title: String }
            type Query { x: String }
            type Mutation {
                runWithMapInput(input: TestInputBean): FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runWithMapInput"})
            }
            """,
            schema -> {
                var f = schema.field("Mutation", "runWithMapInput");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("input")
                    .contains("java.util.Map")
                    .contains("anti-pattern");
            }),

        SERVICE_RECURSIVE_BEAN_REJECTED(
            "R150: @service with a self-referential record bean → UnclassifiedField; the walker would otherwise infinite-loop on the cyclic shape",
            """
            input TestInputRecursive { name: String, children: [TestInputRecursive!] }
            type FilmDetails @record { title: String }
            type Query { x: String }
            type Mutation {
                runWithRecursiveBean(input: TestInputRecursive): FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runWithRecursiveBean"})
            }
            """,
            schema -> {
                var f = schema.field("Mutation", "runWithRecursiveBean");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("TestInputRecursive")
                    .contains("recursive");
            }),

        SERVICE_NON_PUBLIC_BEAN_REJECTED(
            "R150: @service with a package-private record bean → UnclassifiedField; generated fetchers live in a different package",
            """
            input TestInputPackagePrivate { title: String }
            type FilmDetails @record { title: String }
            type Query { x: String }
            type Mutation {
                runWithPackagePrivateBean(input: TestInputPackagePrivate): FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runWithPackagePrivateBean"})
            }
            """,
            schema -> {
                var f = schema.field("Mutation", "runWithPackagePrivateBean");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("TestInputPackagePrivate")
                    .contains("not public");
            });

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
            "Film", "@table", "@error");

        // @record + @error is intentionally NOT a conflict: the @record(record: {className: ...})
        // names the @error type's developer-supplied backing class. See
        // ErrorTypeCase.ERROR_WITH_RECORD_RESOLVES_BACKING_CLASS for the success case.

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
    // @service, @externalField, @tableMethod, (@nodeId || @reference), and @multitableReference
    // are mutually exclusive. @nodeId and @reference CAN be combined.
    // The builder produces UnclassifiedField with a reason naming the conflicting directives.

    enum ChildFieldDirectiveConflictCase implements ClassificationCase {

        SERVICE_AND_EXTERNAL_FIELD_CONFLICT(
            "@service and @externalField → UnclassifiedField with reason naming both",
            """
            type Film @table(name: "film") {
                title: String
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"})
                    @externalField(reference: {className: "no.sikt.graphitron.rewrite.TestExternalFieldStub", method: "rating"})
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
                    @tableMethod(className: "com.example.Foo", method: "get")
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
                    @externalField(reference: {className: "no.sikt.graphitron.rewrite.TestExternalFieldStub", method: "rating"})
                    @tableMethod(className: "com.example.Foo", method: "get")
            }
            type Query { film: Film }
            """,
            "Film", "language", "@externalField", "@tableMethod");

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
                    @tableMethod(className: "com.example.Foo", method: "get")
            }
            """,
            "Query", "film", "@service", "@tableMethod"),

        LOOKUP_KEY_AND_TABLE_METHOD_CONFLICT(
            "@lookupKey and @tableMethod on query → UnclassifiedField with reason naming both",
            """
            type Film @table(name: "film") { title: String }
            type Query {
                film(id: ID @lookupKey): Film
                    @tableMethod(className: "com.example.Foo", method: "get")
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
                film: Film @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
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
                // Synthesised connections always carry totalCount: Int (nullable).
                var totalCount = conn.schemaType().getFieldDefinition("totalCount");
                assertThat(totalCount).isNotNull();
                assertThat(totalCount.getType()).isEqualTo(graphql.Scalars.GraphQLInt);

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
                // No totalCount declared in SDL; structural path leaves the field absent.
                assertThat(conn.schemaType().getFieldDefinition("totalCount")).isNull();
            }),

        STRUCTURAL_CONNECTION_WITH_TOTALCOUNT(
            "a hand-written Connection that declares totalCount: Int keeps the field on schemaType()",
            """
            type Film @table(name: "film") { id: ID }
            type FilmsConnection { edges: [FilmsEdge!]! nodes: [Film!]! pageInfo: PageInfo! totalCount: Int }
            type FilmsEdge { cursor: String! node: Film! }
            type PageInfo { hasNextPage: Boolean! hasPreviousPage: Boolean! startCursor: String endCursor: String }
            type Query { films: FilmsConnection }
            """,
            schema -> {
                var conn = (ConnectionType) schema.type("FilmsConnection");
                var fd = conn.schemaType().getFieldDefinition("totalCount");
                assertThat(fd).isNotNull();
                assertThat(fd.getType()).isEqualTo(graphql.Scalars.GraphQLInt);
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
