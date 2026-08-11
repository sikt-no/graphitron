package no.sikt.graphitron.rewrite;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.rewrite.BuildWarning;
import no.sikt.graphitron.rewrite.RejectionKind;
import no.sikt.graphitron.rewrite.catalog.CatalogBuilder;
import no.sikt.graphitron.rewrite.catalog.FieldClassification;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.ProjectionFor;
import no.sikt.graphitron.rewrite.catalog.TypeClassification;
import no.sikt.graphitron.rewrite.model.ParentCorrelation;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.SourceShape;
import no.sikt.graphitron.rewrite.model.ChildField.ColumnBackedField;
import no.sikt.graphitron.rewrite.model.ChildField.ErrorsField;
import no.sikt.graphitron.rewrite.model.DmlKind;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.ChildField.ColumnBackedReferenceField;
import no.sikt.graphitron.rewrite.model.ChildField.ComputedField;
import no.sikt.graphitron.rewrite.model.ChildField.InterfaceField;
import no.sikt.graphitron.rewrite.model.ChildField.NestingField;
import no.sikt.graphitron.rewrite.model.ChildField.ServiceTableField;
import no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField;
import no.sikt.graphitron.rewrite.model.ChildField.RecordReadField;
import no.sikt.graphitron.rewrite.model.ChildField.BatchedTableField;
import no.sikt.graphitron.rewrite.model.ChildField.TableField;
import no.sikt.graphitron.rewrite.model.ChildField.TableInterfaceField;
import no.sikt.graphitron.rewrite.model.ChildField.UnionField;
import no.sikt.graphitron.rewrite.model.AccessorResolution;
import no.sikt.graphitron.rewrite.model.Arity;
import no.sikt.graphitron.rewrite.model.KeyLift;
import no.sikt.graphitron.rewrite.model.ValueLocator;
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
import no.sikt.graphitron.rewrite.model.On;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.TableExpr;
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
import no.sikt.graphitron.rewrite.model.ScalarResolution;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableInterfaceType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnionType;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static no.sikt.graphitron.rewrite.DmlWriteReads.deleteArgOf;
import static no.sikt.graphitron.rewrite.DmlWriteReads.deleteRowsOf;
import static no.sikt.graphitron.rewrite.DmlWriteReads.insertInputOf;
import static no.sikt.graphitron.rewrite.DmlWriteReads.updateArgOf;
import static no.sikt.graphitron.rewrite.DmlWriteReads.updateRowsOf;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;

/**
 * Pipeline-tier classification tests. Each section has an enum where every constant is one (sdl, assertion)
 * case; the parameterised test method iterates the whole truth table automatically.
 */
@PipelineTier
class GraphitronSchemaBuilderTest {

    // ===== ColumnBackedField =====

    enum ColumnFieldCase implements ClassificationCase {
        IMPLICIT_COLUMN_NAME(
            "scalar field without @field uses the GraphQL field name as the column name",
            """
            type Film @table(name: "film") { title: String }
            type Query { film: Film }
            """,
            schema -> {
                var col = (ColumnBackedField) schema.field("Film", "title");
                assertThat(col).isInstanceOf(ColumnBackedField.class);
                assertThat(col.columns().get(0).sqlName()).isEqualTo("title");
                assertThat(col.columns().get(0)).isInstanceOf(ColumnRef.class);
            }),

        EXPLICIT_FIELD_DIRECTIVE(
            "@field(name:) overrides the column name used for the DB lookup",
            """
            type Film @table(name: "film") { movieTitle: String @field(name: "title") }
            type Query { film: Film }
            """,
            schema -> {
                var col = (ColumnBackedField) schema.field("Film", "movieTitle");
                assertThat(col.columns().get(0).sqlName()).isEqualTo("title");
            }),

        UNRESOLVED_COLUMN(
            "field not present in the DB table produces an UnclassifiedField",
            """
            type Film @table(name: "film") { doesNotExist: String }
            type Query { film: Film }
            """,
            schema -> assertThat(schema.field("Film", "doesNotExist")).isInstanceOf(UnclassifiedField.class)),

        // The bare `enum return type -> ColumnBackedField` verdict lives in the spec-by-example
        // corpus (`enum-column` ClassifiedCorpus example); enum-ness is a GraphQL-to-Java
        // conversion concern, not a classification one. This enum keeps the slot-asserting rows.

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
        @Override public Set<Class<?>> variants() { return Set.of(ColumnBackedField.class); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ColumnFieldCase.class)
    void columnFieldClassification(ColumnFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    @Test
    @ProjectionFor(ChildField.ColumnBackedField.class)
    void columnFieldProjectionCarriesTableAndColumnName() {
        var snapshot = buildSnapshot("""
            type Film @table(name: "film") { movieTitle: String @field(name: "title") }
            type Query { film: Film }
            """);
        var p = (FieldClassification.Column) snapshot.fieldClassificationsByCoord().get("Film.movieTitle");
        assertThat(p.tableName()).isEqualToIgnoringCase("film");
        assertThat(p.columnName()).isEqualTo("title");
    }

    // ===== ColumnBackedReferenceField =====

    enum ColumnReferenceFieldCase implements ClassificationCase {
        KNOWN_FK_BY_SQL_NAME(
            "@reference with a lowercase SQL FK name resolves to an FK-derived hop",
            """
            type Film @table(name: "film") {
              languageName: String @field(name: "name") @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { film: Film }
            """,
            schema -> {
                var ref = (ColumnBackedReferenceField) schema.field("Film", "languageName");
                assertThat(ref.joinPath()).hasSize(1);
                assertThat(ref.joinPath().get(0)).matches(TestFixtures::isFkHop, "FK-derived hop");
            }),

        KNOWN_FK_BY_JAVA_CONSTANT(
            "@reference with a Java-constant-style FK name also resolves to an FK-derived hop",
            """
            type Film @table(name: "film") {
              languageName: String @field(name: "name") @reference(path: [{key: "FILM__FILM_LANGUAGE_ID_FKEY"}])
            }
            type Query { film: Film }
            """,
            schema -> {
                var ref = (ColumnBackedReferenceField) schema.field("Film", "languageName");
                assertThat(ref.joinPath()).hasSize(1);
                assertThat(ref.joinPath().get(0)).matches(TestFixtures::isFkHop, "FK-derived hop");
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
            schema -> assertThat(((ColumnBackedReferenceField) schema.field("Film", "name")).columns().get(0).sqlName())
                .isEqualTo("name")),

        EXPLICIT_FIELD_DIRECTIVE(
            "@field(name:) overrides the column name on a ColumnBackedReferenceField",
            """
            type Film @table(name: "film") {
              languageName: String @field(name: "name")
                  @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { film: Film }
            """,
            schema -> assertThat(((ColumnBackedReferenceField) schema.field("Film", "languageName")).columns().get(0).sqlName())
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
                var ref = (ColumnBackedReferenceField) schema.field("Customer", "district");
                assertThat(ref.joinPath()).hasSize(1);
                assertThat(ref.joinPath().get(0)).matches(TestFixtures::isFkHop, "FK-derived hop");
                var fk = TestFixtures.fkHop(ref.joinPath().get(0));
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

        TABLE_PATH_ON_INPUT_FIELD(
            "@reference with {table:} on a plain input field resolves its FK join path against the "
                + "consuming field's return-type table; the implicit predicate surfaces on the "
                + "consumer as a RemoteColumnPredicate carrying the join path to the terminal table",
            """
            input Input { district: String! @reference(path: [{table: "address"}]) }
            type Customer @table(name: "customer") { customerId: Int! @field(name: "customer_id") }
            type Query { query(in: Input!): Customer }
            """,
            schema -> {
                var qf = (QueryField.QueryTableField) schema.field("Query", "query");
                var gcf = (GeneratedConditionFilter) qf.filters().get(0);
                var remote = (BodyParam.RemoteColumnPredicate) gcf.bodyParams().get(0);
                assertThat(remote.joinPath()).hasSize(1);
                assertThat(remote.joinPath().get(0)).matches(TestFixtures::isFkHop, "FK-derived hop");
                var fk = TestFixtures.fkHop(remote.joinPath().get(0));
                assertThat(fk.targetTable().tableName()).isEqualToIgnoringCase("address");
                var inner = (BodyParam.Eq) remote.inner();
                assertThat(inner.name()).isEqualTo("district");
                assertThat(inner.column().javaName()).isEqualTo("DISTRICT");
            }),

        CONDITION_PATH(
            "@reference with {condition: {className, method}} resolves to a condition-join hop",
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
                assertThat(ref.joinPath().get(0)).matches(TestFixtures::isConditionHop, "condition-join hop");
                var cj = TestFixtures.conditionHop(ref.joinPath().get(0));
                assertThat(TestFixtures.hopCondition(cj).method().methodName()).isEqualTo("join");
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
            }),

        // Condition-only @reference on a scalar return type (no @table to anchor the
        // terminal hop) AUTHOR_ERRORs at parse time. BuildContext.resolveConditionJoinTarget
        // returns AuthorError for the terminal hop because terminalTargetSqlName is null.
        CONDITION_ONLY_NO_RETURN_TYPE_TABLE_REJECTED(
            "@reference with {condition:}-only path on a scalar return type → UnclassifiedField with no-binding message",
            """
            type Film @table(name: "film") {
              actorName: String @reference(path: [{condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "join"}}])
            }
            type Query { film: Film }
            """,
            schema -> {
                var f = schema.field("Film", "actorName");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("cannot resolve target table")
                    .contains("no `@table` binding");
            });

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        ColumnReferenceFieldCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() { return Set.of(ColumnBackedReferenceField.class); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ColumnReferenceFieldCase.class)
    void columnReferenceFieldClassification(ColumnReferenceFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // An unresolvable @reference(key:) candidate hint must land in the author's frame.
    // The author wrote the jOOQ Java-constant form (the TABLE__CONSTRAINT name, detected by the
    // `__` separator), so the suggestions must be rendered in that namespace, not as the bare SQL
    // constraint names findForeignKey also accepts.
    @Test
    void referenceKeyMiss_constantNamespaceAttempt_suggestsInConstantNamespace() {
        var schema = build("""
            type Film @table(name: "film") {
              languageName: String @reference(path: [{key: "FILM__NO_SUCH_FK"}])
            }
            type Query { film: Film }
            """);
        var reason = ((UnclassifiedField) schema.field("Film", "languageName")).reason();
        assertThat(reason)
            .contains("did you mean:")
            // a real FK on `film`, rendered in the TABLE__CONSTRAINT form the author used
            .containsIgnoringCase("FILM__FILM_LANGUAGE_ID_FKEY")
            // not the bare SQL form, which would read as a different namespace
            .doesNotContain("film_language_id_fkey");
    }

    @Test
    void referenceKeyMiss_sqlNamespaceAttempt_suggestsInSqlNamespace() {
        var schema = build("""
            type Film @table(name: "film") {
              languageName: String @reference(path: [{key: "no_such_fk"}])
            }
            type Query { film: Film }
            """);
        var reason = ((UnclassifiedField) schema.field("Film", "languageName")).reason();
        assertThat(reason)
            .contains("did you mean:")
            .containsIgnoringCase("film_language_id_fkey")
            // a FK on an unrelated table is out of scope and must not crowd the hint
            .doesNotContainIgnoringCase("customer_address_id_fkey");
    }

    // At a multi-hop path position the candidate set must be scoped to the FKs touching the
    // current source table, not ranked against every FK in the catalog. Here the failing key sits
    // on the `film_actor` join table, so only its two FKs are relevant; `film`'s own FKs (which a
    // global Levenshtein sweep would surface) must not appear.
    @Test
    void referenceKeyMiss_secondHop_scopesCandidatesToJoinTable() {
        var schema = build("""
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") {
              actors: [Actor!]! @splitQuery @reference(path: [
                {key: "film_actor_film_id_fkey"},
                {key: "FILM_ACTOR__NO_SUCH_FK"}
              ])
            }
            type Query { film: Film }
            """);
        var reason = ((UnclassifiedField) schema.field("Film", "actors")).reason();
        assertThat(reason)
            .contains("did you mean:")
            // the sibling FK on the join table, in the author's namespace
            .containsIgnoringCase("FILM_ACTOR__FILM_ACTOR_ACTOR_ID_FKEY")
            // film's own FKs are out of scope at the join-table position
            .doesNotContainIgnoringCase("FILM__FILM_LANGUAGE_ID_FKEY");
    }

    @Test
    @ProjectionFor(ChildField.ColumnBackedReferenceField.class)
    void columnReferenceFieldProjectionCarriesJoinPathAndTerminalTable() {
        var snapshot = buildSnapshot("""
            type Film @table(name: "film") {
              languageName: String @field(name: "name") @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { film: Film }
            """);
        var p = (FieldClassification.ColumnReference) snapshot.fieldClassificationsByCoord().get("Film.languageName");
        assertThat(p.columnName()).isEqualTo("name");
        assertThat(p.tableName()).isEqualToIgnoringCase("language");
        assertThat(p.joinPath()).hasSize(1);
        assertThat(p.joinPath().get(0).targetTableName()).isEqualToIgnoringCase("language");
        assertThat(p.joinPath().get(0).fkName()).isEqualToIgnoringCase("film_language_id_fkey");
    }

    // ===== ParticipantColumnReferenceField =====
    // Cross-table fields on TableInterfaceType participants get their own classified leaf so the
    // interface fetcher's conditional LEFT JOIN wires the projection and the per-field
    // DataFetcher reads it back by alias.

    enum ParticipantColumnReferenceFieldCase implements ClassificationCase {
        SCALAR_REFERENCE_TO_OTHER_TABLE(
            "scalar @reference on a TableInterfaceType participant whose target is a different "
            + "table is classified as ParticipantColumnReferenceField with the cross-table FK hop "
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
                assertThat(TestFixtures.fkRef(pcrf.pairs()).sqlName()).isEqualToIgnoringCase("content_film_id_fkey");
                assertThat(pcrf.aliasName()).isEqualTo("FilmContent_rating");
                assertThat(pcrf.column().sqlName()).isEqualToIgnoringCase("rating");
            }),

        REFERENCE_TO_SAME_TABLE_FALLS_THROUGH(
            "scalar @reference whose target resolves to the participant's own table is NOT a "
            + "ParticipantColumnReferenceField; the classifier reverts to the standard "
            + "ColumnBackedReferenceField path",
            """
            interface Content @table(name: "content") @discriminate(on: "CONTENT_TYPE") {
              contentId: Int! @field(name: "CONTENT_ID")
            }
            type FilmContent implements Content @table(name: "content") @discriminator(value: "FILM") {
              contentId: Int! @field(name: "CONTENT_ID")
              # Hypothetical self-FK — left as a no-FK fallback to ensure the path doesn't lift to
              # ParticipantColumnReferenceField when no cross-table FK hop exists. The field falls
              # through to ColumnBackedReferenceField/UnclassifiedField via the existing classifier path.
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

    @Test
    @ProjectionFor(no.sikt.graphitron.rewrite.model.ChildField.ParticipantColumnReferenceField.class)
    void participantColumnReferenceFieldProjectionCarriesTargetTableAndAlias() {
        var snapshot = buildSnapshot("""
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
            """);
        var p = (FieldClassification.ParticipantCrossTable) snapshot.fieldClassificationsByCoord().get("FilmContent.rating");
        assertThat(p.targetTableName()).isEqualToIgnoringCase("film");
        assertThat(p.columnName()).isEqualToIgnoringCase("rating");
        assertThat(p.fkName()).isEqualToIgnoringCase("content_film_id_fkey");
        assertThat(p.alias()).isEqualTo("FilmContent_rating");
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

    // ===== Output-side NodeId carrier (ChildField.ColumnBackedField with NodeIdEncodeKeys compaction) =====

    enum NodeIdFieldCase implements ClassificationCase {
        WITH_NODE_DIRECTIVE(
            "@nodeId on a type that also has @node — classified as ColumnBackedField with NodeIdEncodeKeys compaction",
            """
            type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
              id: ID! @nodeId
            }
            type Query { film: Film }
            """,
            schema -> {
                var field = (ChildField.ColumnBackedField) schema.field("Film", "id");
                assertThat(field.columns().get(0)).isNotNull();
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
        @Override public Set<Class<?>> variants() { return Set.of(ChildField.ColumnBackedField.class); }
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
            "typeName pointing to a @node type with a single FK between tables → ColumnBackedField with NodeIdEncodeKeys (FK-mirror collapse: FK source columns ARE the keys)",
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
                var ref = (ChildField.ColumnBackedField) schema.field("City", "countryId");
                assertThat(ref.columns().get(0)).isNotNull();
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
            "@reference(path:) on a @nodeId field with FK-mirror collapse → ColumnBackedField with NodeIdEncodeKeys",
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
                var ref = (ChildField.ColumnBackedField) schema.field("Film", "languageId");
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
        @Override public Set<Class<?>> variants() { return Set.of(ChildField.ColumnBackedField.class, UnclassifiedField.class); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(NodeIdReferenceFieldCase.class)
    void nodeIdReferenceFieldClassification(NodeIdReferenceFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== TableField / BatchedTableField / BatchedTableField / TableField =====

    /**
     * Child field on a {@code @table} parent returning a {@code @table}-mapped type. One case per
     * variant the builder can produce from this shape. Covers the
     * <em>Child Fields (on {@code @table} parent)</em> table in {@code code-generation-triggers.adoc}.
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
                assertThat(tf.joinPath().get(0)).matches(TestFixtures::isFkHop, "FK-derived hop");
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
            "@asConnection @splitQuery → BatchedTableField with Connection wrapper "
            + "(per-parent pagination via ROW_NUMBER() envelope; plan-split-query-connection.md §1)",
            """
            type Customer @table(name: "customer") { firstName: String }
            type Store @table(name: "store") { customers: [Customer!]! @asConnection @splitQuery @defaultOrder(primaryKey: true) }
            type Query { store: Store }
            """,
            schema -> {
                var field = schema.field("Store", "customers");
                assertThat(field).isInstanceOf(ChildField.BatchedTableField.class);
                assertThat(((ChildField.BatchedTableField) field).returnType().wrapper())
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

        // The bare `@splitQuery -> BatchedTableField` verdict lives in the spec-by-example corpus
        // (`child-table` ClassifiedCorpus example, the minimal pair against the inline TableField).
        // This enum keeps the slot-asserting split cases below.

        // The bare `@splitQuery + @lookupKey -> BatchedTableField` verdict lives in the
        // spec-by-example corpus (`split-lookup` ClassifiedCorpus example). This enum keeps the
        // slot-asserting / rejection cases below (IMPLICIT_REFERENCE_SPLIT_LOOKUP_TABLE,
        // SPLIT_LOOKUP_TABLE_SINGLE_CARDINALITY_REJECTED).

        SPLIT_TABLE_SINGLE_CARDINALITY(
            "@splitQuery with single-cardinality parent-holds-FK reference → BatchedTableField with FK-column SourceKey",
            """
            type Address @table(name: "address") { address: String }
            type Customer @table(name: "customer") {
                address: Address @splitQuery @reference(path: [{key: "customer_address_id_fkey"}])
            }
            type Query { customer: Customer }
            """,
            schema -> {
                var f = (BatchedTableField) schema.field("Customer", "address");
                assertThat(f.returnType().wrapper()).isInstanceOf(FieldWrapper.Single.class);
                assertThat(f.joinPath()).hasSize(1);
                assertThat(f.sourceKey().wrap()).isInstanceOf(SourceKey.Wrap.Row.class);
                assertThat(f.sourceKey().columns()).extracting(ColumnRef::sqlName)
                    .containsExactly("address_id");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(BatchedTableField.class); }
        },

        IMPLICIT_REFERENCE_SPLIT_TABLE_SINGLE_CARDINALITY(
            "no @reference on single-cardinality @splitQuery with single FK → BatchedTableField, parent-FK SourceKey",
            """
            type Address @table(name: "address") { address: String }
            type Customer @table(name: "customer") { address: Address @splitQuery }
            type Query { customer: Customer }
            """,
            schema -> {
                var f = (BatchedTableField) schema.field("Customer", "address");
                assertThat(f.joinPath()).hasSize(1);
                assertThat(f.sourceKey().wrap()).isInstanceOf(SourceKey.Wrap.Row.class);
                assertThat(f.sourceKey().columns()).extracting(ColumnRef::sqlName)
                    .containsExactly("address_id");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(BatchedTableField.class); }
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
            @Override public Set<Class<?>> variants() { return Set.of(BatchedTableField.class); }
        },

        SPLIT_TABLE_MULTI_HOP_SINGLE_CARDINALITY(
            "single-cardinality @splitQuery with multi-hop parent-holds-FK path → BatchedTableField, first-hop FK SourceKey (R324)",
            """
            type Address @table(name: "address") { address: String }
            type Customer @table(name: "customer") {
                storeAddress: Address @splitQuery @reference(path: [{key: "customer_store_id_fkey"}, {key: "store_address_id_fkey"}])
            }
            type Query { customer: Customer }
            """,
            schema -> {
                var f = (BatchedTableField) schema.field("Customer", "storeAddress");
                assertThat(f.returnType().wrapper()).isInstanceOf(FieldWrapper.Single.class);
                assertThat(f.joinPath()).hasSize(2);
                assertThat(f.joinPath().get(0)).matches(TestFixtures::isFkHop, "FK-derived hop");
                assertThat(f.joinPath().get(1)).matches(TestFixtures::isFkHop, "FK-derived hop");
                // Keyed off the first hop's FK source columns (customer.store_id), hop-count
                // agnostic per FieldBuilder.deriveSplitQuerySource; the emitter bridges store -> address.
                assertThat(f.sourceKey().wrap()).isInstanceOf(SourceKey.Wrap.Row.class);
                assertThat(f.sourceKey().columns()).extracting(ColumnRef::sqlName)
                    .containsExactly("store_id");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(BatchedTableField.class); }
        },

        WITH_REFERENCE_PATH(
            "@reference(path:) populates the joinPath with one FK-derived hop",
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
                assertThat(tf.joinPath().get(0)).matches(TestFixtures::isFkHop, "FK-derived hop");
            }),

        IMPLICIT_REFERENCE_SPLIT_TABLE(
            "no @reference on @splitQuery with single FK between parent and target tables → BatchedTableField with one inferred FK hop",
            """
            type Customer @table(name: "customer") { firstName: String }
            type Store @table(name: "store") { customers: [Customer!]! @splitQuery }
            type Query { store: Store }
            """,
            schema -> {
                var f = (BatchedTableField) schema.field("Store", "customers");
                assertThat(f.joinPath()).hasSize(1);
                assertThat(f.joinPath().get(0)).matches(TestFixtures::isFkHop, "FK-derived hop");
            }),

        IMPLICIT_REFERENCE_SPLIT_LOOKUP_TABLE(
            "no @reference on @splitQuery + @lookupKey with single FK → BatchedTableField with one inferred FK hop",
            """
            type Customer @table(name: "customer") { firstName: String }
            type Store @table(name: "store") {
                customers(customer_id: ID! @lookupKey): [Customer!]! @splitQuery
            }
            type Query { store: Store }
            """,
            schema -> {
                var f = (BatchedTableField) schema.field("Store", "customers");
                assertThat(f.joinPath()).hasSize(1);
                assertThat(f.joinPath().get(0)).matches(TestFixtures::isFkHop, "FK-derived hop");
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
                assertThat(order.uniformAsc()).isTrue();
                assertThat(order.columns()).hasSize(1);
                assertThat(order.columns().get(0).column().sqlName()).isEqualToIgnoringCase("last_name");
                assertThat(order.columns().get(0).direction()).isEqualTo(OrderBySpec.SortDirection.ASC);
            }),

        DEFAULT_ORDER_INDEX_DESC(
            "R339: @defaultOrder(index:, direction: DESC) stamps DESC onto each index column",
            """
            type Actor @table(name: "actor") { name: String }
            type FilmActor @table(name: "film_actor") {
                actors: [Actor!]! @defaultOrder(index: "idx_actor_last_name", direction: DESC)
            }
            type Query { filmActor: FilmActor }
            """,
            schema -> {
                var order = (OrderBySpec.Fixed) ((TableField) schema.field("FilmActor", "actors")).orderBy();
                assertThat(order).isNotNull();
                assertThat(order.uniformAsc()).isFalse();
                assertThat(order.columns()).hasSize(1);
                assertThat(order.columns().get(0).column().sqlName()).isEqualToIgnoringCase("last_name");
                assertThat(order.columns().get(0).direction()).isEqualTo(OrderBySpec.SortDirection.DESC);
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
            "@defaultOrder(primaryKey: true, direction: DESC) stamps DESC onto each PK entry; "
            + "uniformAsc is false because the directive-level direction is honoured for primaryKey-mode",
            """
            type Actor @table(name: "actor") { name: String }
            type FilmActor @table(name: "film_actor") {
                actors: [Actor!]! @defaultOrder(primaryKey: true, direction: DESC)
            }
            type Query { filmActor: FilmActor }
            """,
            schema -> {
                var order = (OrderBySpec.Fixed) ((TableField) schema.field("FilmActor", "actors")).orderBy();
                // PrimaryKey-mode honours the directive-level direction:, so DESC is stamped
                // onto each synthesised PK entry and uniformAsc reflects it.
                assertThat(order.uniformAsc()).isFalse();
                assertThat(order.columns().get(0).direction()).isEqualTo(OrderBySpec.SortDirection.DESC);
            }),

        // Per-field direction in @defaultOrder(fields: [...]).
        PER_FIELD_DIRECTION_DEFAULT_ORDER(
            "R243: @defaultOrder(fields: [...]) with mixed per-field direction → "
            + "ColumnOrderEntry carries per-entry SortDirection; uniformAsc reflects the mix",
            """
            type Actor @table(name: "actor") { name: String }
            type FilmActor @table(name: "film_actor") {
                actors: [Actor!]! @defaultOrder(fields: [
                    {name: "last_name", direction: DESC},
                    {name: "first_name"}
                ])
            }
            type Query { filmActor: FilmActor }
            """,
            schema -> {
                var order = (OrderBySpec.Fixed) ((TableField) schema.field("FilmActor", "actors")).orderBy();
                assertThat(order.columns()).hasSize(2);
                assertThat(order.columns().get(0).column().sqlName()).isEqualToIgnoringCase("last_name");
                assertThat(order.columns().get(0).direction()).isEqualTo(OrderBySpec.SortDirection.DESC);
                assertThat(order.columns().get(1).column().sqlName()).isEqualToIgnoringCase("first_name");
                // Per-entry direction omitted → fallback to directive-level (ASC default).
                assertThat(order.columns().get(1).direction()).isEqualTo(OrderBySpec.SortDirection.ASC);
                assertThat(order.uniformAsc()).isFalse();
            }),

        // Directive-level direction on @defaultOrder pushes down per-entry as the fallback.
        DIRECTIVE_LEVEL_DIRECTION_PUSHES_DOWN(
            "R243: @defaultOrder(direction: DESC, fields: [...]) pushes DESC down onto entries "
            + "that omit per-field direction; per-field ASC overrides",
            """
            type Actor @table(name: "actor") { name: String }
            type FilmActor @table(name: "film_actor") {
                actors: [Actor!]! @defaultOrder(direction: DESC, fields: [
                    {name: "last_name"},
                    {name: "first_name", direction: ASC}
                ])
            }
            type Query { filmActor: FilmActor }
            """,
            schema -> {
                var order = (OrderBySpec.Fixed) ((TableField) schema.field("FilmActor", "actors")).orderBy();
                assertThat(order.columns()).hasSize(2);
                assertThat(order.columns().get(0).direction()).isEqualTo(OrderBySpec.SortDirection.DESC);
                assertThat(order.columns().get(1).direction()).isEqualTo(OrderBySpec.SortDirection.ASC);
                assertThat(order.uniformAsc()).isFalse();
            }),

        // Per-field direction in @order enum-value directives.
        PER_FIELD_DIRECTION_ORDER_ENUM_VALUE(
            "R243: enum value's @order(fields: [...]) with per-field direction → "
            + "NamedOrder's Fixed carries the per-entry directions and uniformAsc = false",
            """
            enum ActorOrderField {
                LASTNAME_DESC_FIRSTNAME_ASC @order(fields: [
                    {name: "last_name", direction: DESC},
                    {name: "first_name", direction: ASC}
                ])
            }
            input ActorOrder { sortField: ActorOrderField direction: SortDirection }
            type Actor @table(name: "actor") { name: String }
            type Query {
                actors(order: ActorOrder @orderBy): [Actor!]!
            }
            """,
            schema -> {
                var orderBy = (OrderBySpec.Argument) ((QueryField.QueryTableField) schema.field("Query", "actors")).orderBy();
                assertThat(orderBy.namedOrders()).hasSize(1);
                var named = orderBy.namedOrders().get(0);
                assertThat(named.name()).isEqualTo("LASTNAME_DESC_FIRSTNAME_ASC");
                assertThat(named.order().columns()).hasSize(2);
                assertThat(named.order().columns().get(0).direction()).isEqualTo(OrderBySpec.SortDirection.DESC);
                assertThat(named.order().columns().get(1).direction()).isEqualTo(OrderBySpec.SortDirection.ASC);
                assertThat(named.order().uniformAsc()).isFalse();
            }),

        CONNECTION_WITH_DEFAULT_ORDER_INDEX_SPLIT_CLASSIFIED(
            "ActorConnection + @splitQuery → BatchedTableField with Connection wrapper "
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
                assertThat(field).isInstanceOf(ChildField.BatchedTableField.class);
                assertThat(((ChildField.BatchedTableField) field).returnType().wrapper())
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
                assertThat(order.uniformAsc()).isTrue();
                assertThat(order.columns()).hasSize(1);
                assertThat(order.columns().get(0).column().sqlName()).isEqualToIgnoringCase("actor_id");
                assertThat(order.columns().get(0).direction()).isEqualTo(OrderBySpec.SortDirection.ASC);
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
            }),

        // Condition-only @reference path resolves its target table from the carrier
        // field's return-type @table binding (terminal-hop arm of
        // BuildContext.resolveConditionJoinTarget).
        CONDITION_ONLY_TERMINAL_RESOLVES_TARGET_FROM_RETURN_TYPE(
            "condition-only path on a TableField — the hop's targetTable resolved from "
            + "the return-type @table binding (terminal hop)",
            """
            type Actor @table(name: "actor") { firstName: String }
            type City @table(name: "city") {
                actor: Actor @reference(path: [{condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "join"}}])
            }
            type Query { city: City }
            """,
            schema -> {
                var field = (TableField) schema.field("City", "actor");
                assertThat(field.joinPath()).hasSize(1);
                assertThat(field.joinPath().get(0)).matches(TestFixtures::isConditionHop, "condition-join hop");
                var cj = TestFixtures.conditionHop(field.joinPath().get(0));
                assertThat(cj.targetTable().tableName()).isEqualToIgnoringCase("actor");
                assertThat(field.parentCorrelation())
                    .isInstanceOf(ParentCorrelation.OnParentJoin.class);
            }),

        // A condition-only path with @table on both sides preserves the legacy
        // {table:, condition:} combination semantics — the FK hop is derived from endpoints
        // and {condition:} folds into the hop filter, no condition join produced.
        TABLE_WITH_CONDITION_PRESERVES_WHERE_FILTER(
            "{table:, condition:} combination preserves whereFilter semantics — regression guard",
            """
            type Actor @table(name: "actor") { firstName: String }
            type Film @table(name: "film") {
                actors: [Actor!]! @reference(path: [{table: "film_actor", condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "join"}}, {table: "actor"}])
                    @defaultOrder(primaryKey: true)
            }
            type Query { film: Film }
            """,
            schema -> {
                var field = (TableField) schema.field("Film", "actors");
                assertThat(field.joinPath()).hasSize(2);
                var first = field.joinPath().get(0);
                assertThat(first).matches(TestFixtures::isFkHop, "FK-derived hop");
                assertThat(TestFixtures.fkHop(first).filter())
                    .as("{table:, condition:} folds the condition into Hop.filter, not a condition-join hop")
                    .isNotNull();
            }),

        // A condition-only path with @key on both sides preserves the legacy
        // {key:, condition:} combination semantics — same as above for key-form.
        KEY_WITH_CONDITION_PRESERVES_WHERE_FILTER(
            "{key:, condition:} combination preserves whereFilter semantics — regression guard",
            """
            type Actor @table(name: "actor") { firstName: String }
            type Film @table(name: "film") {
                actors: [Actor!]! @reference(path: [{key: "film_actor_film_id_fkey", condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "join"}}, {key: "film_actor_actor_id_fkey"}])
                    @defaultOrder(primaryKey: true)
            }
            type Query { film: Film }
            """,
            schema -> {
                var field = (TableField) schema.field("Film", "actors");
                assertThat(field.joinPath()).hasSize(2);
                var first = field.joinPath().get(0);
                assertThat(first).matches(TestFixtures::isFkHop, "FK-derived hop");
                assertThat(TestFixtures.fkHop(first).filter())
                    .as("{key:, condition:} folds the condition into Hop.filter, not a condition-join hop")
                    .isNotNull();
            }),

        // Intermediate-hop condition step with concrete jOOQ table parameters —
        // BuildContext.resolveConditionJoinTarget reflects on the method's second parameter
        // (no.sikt.graphitron.rewrite.test.jooq.tables.FilmActor) and resolves the target
        // table via JooqCatalog.findTableByClass.
        CONDITION_INTERMEDIATE_REFLECTS_METHOD_PARAM(
            "intermediate-hop condition with concrete jOOQ table params → targetTable resolved by reflection",
            """
            type Actor @table(name: "actor") { firstName: String }
            type Film @table(name: "film") {
                actors: [Actor!]! @reference(path: [
                    {condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "intermediate"}},
                    {table: "actor"}
                ]) @defaultOrder(primaryKey: true)
            }
            type Query { film: Film }
            """,
            schema -> {
                var field = (TableField) schema.field("Film", "actors");
                assertThat(field.joinPath()).hasSize(2);
                var first = field.joinPath().get(0);
                assertThat(first)
                    .as("intermediate-hop condition resolves to a condition-join hop")
                    .matches(TestFixtures::isConditionHop, "condition-join hop");
                var cj = TestFixtures.conditionHop(first);
                assertThat(cj.targetTable().tableName())
                    .as("intermediate condition-join targetTable resolved from the condition method's second parameter type")
                    .isEqualToIgnoringCase("film_actor");
            }),

        // Intermediate-hop condition step with Table<?> wildcard parameter — the parser
        // cannot infer the target from a wildcard, so it AUTHOR_ERRORs at parse time with the
        // wildcard-specific message.
        CONDITION_INTERMEDIATE_TABLE_WILDCARD_REJECTED(
            "intermediate-hop condition with Table<?> wildcard target param → UnclassifiedField with wildcard message",
            """
            type Actor @table(name: "actor") { firstName: String }
            type Film @table(name: "film") {
                actors: [Actor!]! @reference(path: [
                    {condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "join"}},
                    {table: "actor"}
                ]) @defaultOrder(primaryKey: true)
            }
            type Query { film: Film }
            """,
            schema -> {
                var f = schema.field("Film", "actors");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("wildcard target parameter")
                    .contains("Table<?>");
            }),

        // An unresolvable condition on a {key:}/{table:} path element is an
        // author error, not a generator crash. resolveConditionRef reports reflection failures
        // as (ref=null, error=null), so the branches must guard the null ref the same way the
        // condition-only branch does instead of handing it to JoinConditionRef's null check.
        KEY_WITH_UNRESOLVABLE_CONDITION_REJECTED(
            "{key:, condition:} with unresolvable condition method → UnclassifiedField, not NPE",
            """
            type Actor @table(name: "actor") { firstName: String }
            type Film @table(name: "film") {
                actors: [Actor!]! @reference(path: [{key: "film_actor_film_id_fkey", condition: {className: "no.sikt.graphitron.rewrite.NoSuchStub", method: "nope"}}, {key: "film_actor_actor_id_fkey"}])
                    @defaultOrder(primaryKey: true)
            }
            type Query { film: Film }
            """,
            schema -> {
                var f = schema.field("Film", "actors");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("could not be resolved");
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

    @Test
    @ProjectionFor({TableField.class, BatchedTableField.class})
    void tableFieldProjectionCarriesTargetTableAndAxisFlags() {
        // Plain TableField: list of @table-bound child rows from a parent @table.
        var s1 = buildSnapshot("""
            type Customer @table(name: "customer") { firstName: String }
            type Store @table(name: "store") { customers: [Customer!]! }
            type Query { store: Store }
            """);
        var plain = (FieldClassification.TableTarget) s1.fieldClassificationsByCoord().get("Store.customers");
        assertThat(plain.tableName()).isEqualToIgnoringCase("customer");
        assertThat(plain.splitBatched()).isFalse();
        assertThat(plain.hasLookupKey()).isFalse();

        // BatchedTableField: @splitQuery sets splitBatched.
        var s2 = buildSnapshot("""
            type Customer @table(name: "customer") { firstName: String }
            type Store @table(name: "store") { customers: [Customer!]! @splitQuery }
            type Query { store: Store }
            """);
        var split = (FieldClassification.TableTarget) s2.fieldClassificationsByCoord().get("Store.customers");
        assertThat(split.splitBatched()).isTrue();
        assertThat(split.hasLookupKey()).isFalse();

        // TableField: @lookupKey on a child arg sets hasLookupKey.
        var s3 = buildSnapshot("""
            type Customer @table(name: "customer") { firstName: String }
            type Store @table(name: "store") {
              customers(customer_id: ID! @lookupKey): [Customer!]!
            }
            type Query { store: Store }
            """);
        var lookup = (FieldClassification.TableTarget) s3.fieldClassificationsByCoord().get("Store.customers");
        assertThat(lookup.splitBatched()).isFalse();
        assertThat(lookup.hasLookupKey()).isTrue();

        // BatchedTableField: both axes.
        var s4 = buildSnapshot("""
            type Customer @table(name: "customer") { firstName: String }
            type Store @table(name: "store") {
              customers(customer_id: ID! @lookupKey): [Customer!]! @splitQuery
            }
            type Query { store: Store }
            """);
        var both = (FieldClassification.TableTarget) s4.fieldClassificationsByCoord().get("Store.customers");
        assertThat(both.splitBatched()).isTrue();
        assertThat(both.hasLookupKey()).isTrue();
    }

    // ===== NestingField =====

    enum NestingFieldCase implements ClassificationCase {
        // The bare `plain-object child on a @table parent -> NestingField` verdict (single and
        // list-wrapped) lives in the spec-by-example corpus (`nesting` ClassifiedCorpus example).
        // This enum keeps the slot-asserting nested-projection cases below (nestedFields() shape,
        // remap, roll-up, multi-level).

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
                    .isInstanceOfSatisfying(ColumnBackedField.class, cf -> {
                        assertThat(cf.name()).isEqualTo("title");
                        assertThat(cf.columns().get(0).javaName()).isEqualTo("TITLE");
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
                    .isInstanceOfSatisfying(ColumnBackedField.class, cf -> {
                        assertThat(cf.name()).isEqualTo("alias");
                        assertThat(cf.columns().get(0).javaName()).isEqualTo("TITLE");
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
                        .isInstanceOfSatisfying(ColumnBackedField.class, cf -> {
                            assertThat(cf.columns().get(0).javaName()).isEqualTo("RELEASE_YEAR");
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
            }),

        SPLIT_QUERY_ON_NESTING_DEFERRED(
            "@splitQuery on a nesting field → verdict kept, deferred diagnostic on the channel",
            """
            type FilmDetails { title: String }
            type Film @table(name: "film") { details: FilmDetails @splitQuery }
            type Query { film: Film }
            """,
            schema -> {
                // The verdict is not demoted: the nested subtree stays classified (the editor
                // view keeps its completions), and the build fails through the diagnostic
                // drain instead.
                assertThat(schema.field("Film", "details")).isInstanceOf(NestingField.class);
                assertThat(schema.diagnostics()).singleElement().satisfies(d -> {
                    assertThat(d.coordinate()).isEqualTo("Film.details");
                    assertThat(d.rejection()).isInstanceOf(Rejection.Deferred.class);
                    assertThat(d.message())
                        .contains("@splitQuery on a nesting field is not yet supported")
                        .contains("Remove @splitQuery to keep the inline projection");
                });
            }),

        SPLIT_QUERY_ON_MIXED_SOURCE_NESTING_NAMES_PRODUCER(
            "@splitQuery on a mixed-source nesting field → deferred diagnostic names the producer",
            """
            type FilmDetails { rating: String }
            type Film @table(name: "film") { details: FilmDetails @splitQuery }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeFilmDetailsRating"})
            }
            """,
            schema -> {
                // An author splitting a type that is also producer-backed most likely meant to
                // return the produced value; the diagnostic carries the producer hint the
                // unresolvable-nested-child path already composes.
                assertThat(schema.field("Film", "details")).isInstanceOf(NestingField.class);
                assertThat(schema.diagnostics()).singleElement().satisfies(d -> {
                    assertThat(d.coordinate()).isEqualTo("Film.details");
                    assertThat(d.rejection()).isInstanceOf(Rejection.Deferred.class);
                    assertThat(d.message())
                        .contains("@splitQuery on a nesting field is not yet supported")
                        .contains("'FilmDetails' is also produced")
                        .contains("add @service, @reference, or @externalField");
                });
            }),

        SPLIT_QUERY_ON_SHARED_NESTED_MEMBER_DIAGNOSED_ONCE(
            "@splitQuery on a shared nesting type's nested member → one diagnostic, not one per host",
            """
            type FilmMeta { title: String }
            type FilmDetails { meta: FilmMeta @splitQuery }
            type Film @table(name: "film") { details: FilmDetails }
            type FilmList @table(name: "film_list") { details: FilmDetails }
            type Query { film: Film films: [FilmList] }
            """,
            schema -> {
                // Each host classifies the shared type's members independently, so the nested
                // coordinate is visited once per host; the diagnostic channel carries it once.
                assertThat(schema.field("Film", "details")).isInstanceOf(NestingField.class);
                assertThat(schema.field("FilmList", "details")).isInstanceOf(NestingField.class);
                assertThat(schema.diagnostics()).singleElement().satisfies(d -> {
                    assertThat(d.coordinate()).isEqualTo("FilmDetails.meta");
                    assertThat(d.rejection()).isInstanceOf(Rejection.Deferred.class);
                });
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
    @ProjectionFor({no.sikt.graphitron.rewrite.model.ChildField.PivotField.class,
        no.sikt.graphitron.rewrite.model.ChildField.BatchedPivotField.class})
    void pivotFieldProjectionCarriesTableAndColumnsAndDeliveryFlag() {
        // Both @pivot delivery leaves project onto FieldClassification.Pivot, carrying the
        // attribute table, the two pivot columns, and the @splitQuery delivery fork.
        var snapshot = buildSnapshot("""
            type Texts { nob: String nno: String }
            type Film @table(name: "film") {
              texts: Texts
                @reference(path: [{table: "film_translation"}])
                @pivot(on: "lang_code", value: "title_txt")
              textsSplit: Texts @splitQuery
                @reference(path: [{table: "film_translation"}])
                @pivot(on: "lang_code", value: "title_txt")
            }
            type Query { film: Film }
            """);
        var inline = snapshot.fieldClassificationsByCoord().get("Film.texts");
        assertThat(inline).isInstanceOf(FieldClassification.Pivot.class);
        var p = (FieldClassification.Pivot) inline;
        assertThat(p.tableName()).isEqualTo("film_translation");
        assertThat(p.onColumn()).isEqualTo("lang_code");
        assertThat(p.valueColumn()).isEqualTo("title_txt");
        assertThat(p.batched()).isFalse();
        var split = (FieldClassification.Pivot) snapshot.fieldClassificationsByCoord().get("Film.textsSplit");
        assertThat(split.batched()).isTrue();
    }

    @Test
    @ProjectionFor(NestingField.class)
    void nestingFieldProjectionIsZeroPayload() {
        // NestingField fragments a parent's table-bound shape into a sub-projection; the
        // projection record carries no payload beyond its identity.
        var snapshot = buildSnapshot("""
            type Inner { title: String @field(name: "title") }
            type Film @table(name: "film") { inner: Inner }
            type Query { film: Film }
            """);
        var p = snapshot.fieldClassificationsByCoord().get("Film.inner");
        assertThat(p).isInstanceOf(FieldClassification.Nesting.class);
    }

    @Test
    void nestingField_splitQueryChildClassifiedAsNestedBatchedTableField() {
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
        assertThat(castField).isInstanceOf(BatchedTableField.class);
        var btf = (BatchedTableField) castField;
        assertThat(btf.parentTypeName()).isEqualTo("FilmInfo");
        assertThat(btf.sourceKey().wrap()).isInstanceOf(SourceKey.Wrap.Row.class);
        assertThat(btf.sourceKey().columns()).extracting(ColumnRef::javaName).containsExactly("FILM_ID");
    }

    // A plain-object nested type shared across two @table parents whose shared field
    // classifies as an inline TableField. Customer and Staff both FK to address; the nested
    // LocationInfo type exposes `address` as a TableField. The classifier resolves the FK
    // joinPath against each outer parent's table independently, and the multi-parent shape
    // check (GraphitronSchemaValidator.compareNestedFieldsShape) admits the TableField arm
    // rather than rejecting it as "not yet supported across multiple parents".
    @Test
    void multiParentSharedNesting_inlineTableFieldLeaf_classifiesAndValidatesPerParent() {
        var schema = build("""
            type Address @table(name: "address") { district: String }
            type LocationInfo { address: Address }
            type Customer @table(name: "customer") { info: LocationInfo }
            type Staff @table(name: "staff") { info: LocationInfo }
            type Query { customer: Customer staff: Staff }
            """);

        // Each parent's nested `address` field classifies as a TableField with a one-hop Fk
        // joinPath inferred from that parent's own table -> address FK.
        var customerAddress = nestedTableField(schema, "Customer", "info", "address");
        var staffAddress = nestedTableField(schema, "Staff", "info", "address");
        assertThat(TestFixtures.fkHop(customerAddress.joinPath().get(0)).targetTable().tableName())
            .isEqualToIgnoringCase("address");
        assertThat(TestFixtures.fkHop(staffAddress.joinPath().get(0)).targetTable().tableName())
            .isEqualToIgnoringCase("address");
        // The two parents resolve through their own FK constraints: the joinPath is genuinely
        // per-parent, not shared.
        assertThat(TestFixtures.fkRef(TestFixtures.fkPairs(customerAddress.joinPath().get(0))).sqlName())
            .isNotEqualTo(TestFixtures.fkRef(TestFixtures.fkPairs(staffAddress.joinPath().get(0))).sqlName());

        // The multi-parent shape check admits the shared TableField (no "not yet supported" error).
        var errors = new GraphitronSchemaValidator().validate(schema);
        assertThat(errors)
            .extracting(ValidationError::message)
            .noneMatch(m -> m.contains("not yet supported across multiple parents"));
    }

    private static TableField nestedTableField(GraphitronSchema schema, String parent, String nesting, String leaf) {
        var nf = (NestingField) schema.field(parent, nesting);
        var field = nf.nestedFields().stream()
            .filter(f -> f.name().equals(leaf))
            .findFirst().orElseThrow();
        assertThat(field).isInstanceOf(TableField.class);
        return (TableField) field;
    }

    // ===== ServiceTableField / ServiceRecordField =====

    enum ServiceFieldCase implements ClassificationCase {
        // The bare `@service on a @table parent returning a scalar -> ServiceRecordField` verdict
        // lives in the spec-by-example corpus (Film.rating in the `service` ClassifiedCorpus
        // example). This enum keeps the slot-asserting service cases below.

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
            "@service field with @reference {key:} (null parent SQL source) defaults the hop's originTable to the FK-side table",
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
                var fk = TestFixtures.fkHop(f.joinPath().get(0));
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

    @Test
    @ProjectionFor({ServiceTableField.class, ServiceRecordField.class})
    void serviceBackedProjectionCarriesMethodAndTableBoundFlag() {
        // ServiceRecordField — tableBound = false (scalar return); fixture mirrors the
        // existing ServiceFieldCase.SCALAR_RETURN.
        var s1 = buildSnapshot("""
            type Film @table(name: "film") {
                rating: String @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"})
            }
            type Query { film: Film }
            """);
        var recordBound = (FieldClassification.ServiceBacked) s1.fieldClassificationsByCoord().get("Film.rating");
        assertThat(recordBound.tableBound()).isFalse();
        assertThat(recordBound.tableName()).isNull();
        assertThat(recordBound.methodName()).isEqualTo("get");
        assertThat(recordBound.methodClassName()).isEqualTo("no.sikt.graphitron.rewrite.TestServiceStub");

        // ServiceTableField — tableBound = true, tableName = target table's name. Fixture
        // mirrors ServiceFieldCase.TABLE_TYPE_RETURN.
        var s2 = buildSnapshot("""
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                language: Language @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getLanguage"})
            }
            type Query { film: Film }
            """);
        var tableBound = (FieldClassification.ServiceBacked) s2.fieldClassificationsByCoord().get("Film.language");
        assertThat(tableBound.tableBound()).isTrue();
        assertThat(tableBound.tableName()).isEqualToIgnoringCase("language");
        assertThat(tableBound.methodName()).isEqualTo("getLanguage");
    }

    @Test
    @ProjectionFor({QueryField.QueryServicePolymorphicField.class, MutationField.MutationServicePolymorphicField.class})
    void servicePolymorphicProjectionCarriesParticipantsAndMethod() {
        // Route (a): a root @service field returning a multitable interface over distinct-table
        // participants (film, actor) resolves to the polymorphic-return arm, carrying the resolved
        // participant set and the service method. Query arm is single cardinality; Mutation arm is
        // list cardinality.
        var schema = build("""
            interface Searchable { name: String }
            type Film implements Searchable @table(name: "film") { name: String @field(name: "TITLE") }
            type Actor implements Searchable @table(name: "actor") { name: String @field(name: "FIRST_NAME") }
            type Query {
              searchOne: Searchable
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
            }
            type Mutation {
              searchMany: [Searchable]
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilms"})
            }
            """);

        var q = schema.field("Query", "searchOne");
        assertThat(q).isInstanceOf(QueryField.QueryServicePolymorphicField.class);
        var qp = (QueryField.QueryServicePolymorphicField) q;
        assertThat(qp.serviceMethodCall().methodName()).isEqualTo("getFilm");
        assertThat(qp.participants()).hasSize(2);
        assertThat(qp.returnType().wrapper().isList()).isFalse();

        var m = schema.field("Mutation", "searchMany");
        assertThat(m).isInstanceOf(MutationField.MutationServicePolymorphicField.class);
        var mp = (MutationField.MutationServicePolymorphicField) m;
        assertThat(mp.serviceMethodCall().methodName()).isEqualTo("getFilms");
        assertThat(mp.participants()).hasSize(2);
        assertThat(mp.returnType().wrapper().isList()).isTrue();
    }

    @Test
    void serviceReturningUnion_rejectedAsUnsupported() {
        // Union polymorphism is a generated-query-path capability only; a @service
        // returning a union is permanently unsupported (AUTHOR_ERROR), not deferred.
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type Actor @table(name: "actor") { firstName: String @field(name: "FIRST_NAME") }
            union FilmOrActor = Film | Actor
            type Query {
              search: FilmOrActor
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
            }
            """);
        var f = schema.field("Query", "search");
        assertThat(f).isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField.class);
        var unc = (no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField) f;
        assertThat(unc.kind()).isEqualTo(no.sikt.graphitron.rewrite.RejectionKind.AUTHOR_ERROR);
        assertThat(unc.reason()).contains("union").contains("must return a multitable interface");
    }

    @Test
    @ProjectionFor({QueryField.QueryServiceTableInterfaceField.class, MutationField.MutationServiceTableInterfaceField.class})
    void serviceReturningTableInterface_classifiesAsServiceTableInterfaceField() {
        // A @service returning a single-table discriminated interface (TableInterfaceType)
        // resolves through the table-bound service arm to the single-table service-interface
        // variant, carrying the service method plus the read-side discrimination data
        // (participant set, discriminator column, known discriminator values). Query arm is single
        // cardinality; Mutation arm is list. Modelled on servicePolymorphicProjectionCarriesParticipantsAndMethod.
        var schema = build("""
            interface MediaItem @table(name: "film") @discriminate(on: "kind") { title: String }
            type FilmItem implements MediaItem @table(name: "film") @discriminator(value: "film") { title: String }
            type Query {
              media: MediaItem
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
            }
            type Mutation {
              mediaSearch: [MediaItem]
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilms"})
            }
            """);

        var q = schema.field("Query", "media");
        assertThat(q).isInstanceOf(QueryField.QueryServiceTableInterfaceField.class);
        var qf = (QueryField.QueryServiceTableInterfaceField) q;
        assertThat(qf.serviceMethodCall().methodName()).isEqualTo("getFilm");
        assertThat(qf.discriminatorColumn()).isEqualTo("kind");
        assertThat(qf.knownDiscriminatorValues()).containsExactly("film");
        assertThat(qf.participants()).hasSize(1);
        assertThat(qf.returnType().wrapper().isList()).isFalse();

        var m = schema.field("Mutation", "mediaSearch");
        assertThat(m).isInstanceOf(MutationField.MutationServiceTableInterfaceField.class);
        var mf = (MutationField.MutationServiceTableInterfaceField) m;
        assertThat(mf.serviceMethodCall().methodName()).isEqualTo("getFilms");
        assertThat(mf.discriminatorColumn()).isEqualTo("kind");
        assertThat(mf.knownDiscriminatorValues()).containsExactly("film");
        assertThat(mf.participants()).hasSize(1);
        assertThat(mf.returnType().wrapper().isList()).isTrue();
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

    @Test
    @ProjectionFor(ComputedField.class)
    void computedFieldProjectionCarriesMethodCoordinates() {
        var snapshot = buildSnapshot("""
            type Film @table(name: "film") {
              displayTitle: String @externalField(reference: {className: "no.sikt.graphitron.rewrite.TestExternalFieldStub", method: "rating"})
            }
            type Query { film: Film }
            """);
        var p = (FieldClassification.Computed) snapshot.fieldClassificationsByCoord().get("Film.displayTitle");
        assertThat(p.methodClassName()).isEqualTo("no.sikt.graphitron.rewrite.TestExternalFieldStub");
        assertThat(p.methodName()).isEqualTo("rating");
    }

    // ===== TableInterfaceField / InterfaceField / UnionField =====

    enum InterfaceUnionFieldCase implements ClassificationCase {
        // The bare polymorphic child verdicts (TableInterfaceField, InterfaceField) live in the
        // spec-by-example corpus (`table-interface` example Inventory.media; `interface` example
        // Customer.address). This enum keeps the InterfaceType / participant-shape cases below,
        // which assert type-level slots.

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
            type Query { film: Film period: Datoperiode }
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

        // The bare `field returning a union -> UnionField` verdict lives in the spec-by-example
        // corpus (FilmActor.related in the `union` example). This enum keeps the
        // union-with-nesting rejection case below.

        UNION_WITH_NESTING_MEMBER(
            "union with a nesting-type member (no @table) → union classified as UnclassifiedType",
            """
            type Film @table(name: "film") { title: String }
            type DatePeriod { fraDato: String @field(name: "DATO_FRA") }
            union MediaOrPeriod = Film | DatePeriod
            type Query { film: Film mp: MediaOrPeriod }
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
            type Query { film: Film media: MediaItem }
            """,
            schema -> {
                var t = (UnionType) schema.type("MediaItem");
                assertThat(t.participants()).hasSize(2);
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnionType.class); }
        },

        TABLE_INTERFACE_ROOT_CONNECTION_DEFERRED(
            "@asConnection on a single-table-interface root → UnclassifiedField with a deferred "
                + "rejection (previously accepted and silently mis-emitted as an unpaginated fetch)",
            """
            interface MediaItem @table(name: "film") @discriminate(on: "kind") { title: String }
            type Film implements MediaItem @table(name: "film") @discriminator(value: "film") { title: String }
            type Query { allMedia: [MediaItem] @asConnection }
            """,
            schema -> assertThat(schema.field("Query", "allMedia"))
                .isInstanceOfSatisfying(UnclassifiedField.class, uf -> {
                    assertThat(uf.rejection()).isInstanceOf(Rejection.Deferred.class);
                    assertThat(uf.reason())
                        .contains("@asConnection on a root field")
                        .contains("single-table discriminated interface")
                        .contains("not yet supported");
                }));

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

    @Test
    @ProjectionFor({TableInterfaceField.class, TableInterfaceType.class})
    void tableInterfaceFieldProjectionCarriesDiscriminatorAndParticipants() {
        var snapshot = buildSnapshot("""
            interface MediaItem @table(name: "film") @discriminate(on: "kind") { title: String }
            type Film implements MediaItem @table(name: "film") @discriminator(value: "film") { title: String }
            type Inventory @table(name: "inventory") { media: MediaItem }
            type Query { inventory: Inventory }
            """);
        var f = (FieldClassification.TableInterface) snapshot.fieldClassificationsByCoord().get("Inventory.media");
        assertThat(f.tableName()).isEqualToIgnoringCase("film");
        assertThat(f.discriminatorColumn()).isEqualTo("kind");
        assertThat(f.participantTypeNames()).contains("Film");

        // TableInterfaceType — the type-side projection carrier with the same payload.
        var t = (TypeClassification.TableInterface) snapshot.typeClassificationsByName().get("MediaItem");
        assertThat(t.tableName()).isEqualToIgnoringCase("film");
        assertThat(t.discriminatorColumn()).isEqualTo("kind");
    }

    @Test
    @ProjectionFor({
        InterfaceField.class, UnionField.class,
        no.sikt.graphitron.rewrite.model.GraphitronType.InterfaceType.class,
        no.sikt.graphitron.rewrite.model.GraphitronType.UnionType.class
    })
    void polymorphicFieldProjectionCarriesParticipants() {
        // UnionField across two @table types — schema-reachable in the standard sakila catalog.
        var s1 = buildSnapshot("""
            type Film @table(name: "film") { title: String }
            type Actor @table(name: "actor") { firstName: String @field(name: "FIRST_NAME") }
            union FilmOrActor = Film | Actor
            type FilmActor @table(name: "film_actor") { related: FilmOrActor }
            type Query { filmActor: FilmActor }
            """);
        var fld = (FieldClassification.Polymorphic) s1.fieldClassificationsByCoord().get("FilmActor.related");
        assertThat(fld.participantTypeNames()).containsExactlyInAnyOrder("Film", "Actor");

        var uni = (TypeClassification.Union) s1.typeClassificationsByName().get("FilmOrActor");
        assertThat(uni.participantTypeNames()).containsExactlyInAnyOrder("Film", "Actor");

        // InterfaceType — plain (non-@table) interface across two @table participants.
        var s2 = buildSnapshot("""
            interface Named { name: String }
            type Address implements Named @table(name: "address") { name: String @field(name: "ADDRESS") }
            type Customer @table(name: "customer") { address: Named }
            type Query { customer: Customer }
            """);
        var iface = (TypeClassification.Interface) s2.typeClassificationsByName().get("Named");
        assertThat(iface.participantTypeNames()).contains("Address");
    }

    @Test
    @ProjectionFor({
        no.sikt.graphitron.rewrite.model.ChildField.BatchedInterfaceField.class,
        no.sikt.graphitron.rewrite.model.ChildField.BatchedUnionField.class
    })
    void batchedPolymorphicFieldProjectionCarriesParticipants() {
        // The DataLoader half of the polymorphic delivery split: the same Polymorphic
        // projection payload as the inline half, reached through list cardinality. Both
        // participants hold a single FK back to the parent table (the auto-discovery shape
        // the batched correlation requires).
        var s1 = buildSnapshot("""
            type Inventory @table(name: "inventory") { inventoryId: Int @field(name: "INVENTORY_ID") }
            type FilmActor @table(name: "film_actor") { actorId: Int @field(name: "ACTOR_ID") }
            union FilmRef = Inventory | FilmActor
            type Film @table(name: "film") { refs: [FilmRef!]! }
            type Query { film: Film }
            """);
        var fld = (FieldClassification.Polymorphic) s1.fieldClassificationsByCoord().get("Film.refs");
        assertThat(fld.participantTypeNames()).containsExactlyInAnyOrder("Inventory", "FilmActor");

        var s2 = buildSnapshot("""
            interface Occupant { name: String }
            type Customer implements Occupant @table(name: "customer") { name: String @field(name: "FIRST_NAME") }
            type Address @table(name: "address") { occupants: [Occupant!]! }
            type Query { address: Address }
            """);
        var fld2 = (FieldClassification.Polymorphic) s2.fieldClassificationsByCoord().get("Address.occupants");
        assertThat(fld2.participantTypeNames()).contains("Customer");
    }

    // ===== Fields on record-backed parents =====

    /** The resolved accessor's own member name (method or field), for locator assertions. */
    private static String accessorMemberName(AccessorResolution.Resolved accessor) {
        return switch (accessor) {
            case AccessorResolution.GetterPrefixed gp -> gp.method().getName();
            case AccessorResolution.BareName bn -> bn.method().getName();
            case AccessorResolution.FieldRead fr -> fr.field().getName();
        };
    }

    /**
     * Child field on a record-backed parent. One case per variant the builder can produce from
     * this shape. Covers the <em>Child Fields (on record-backed parent)</em> table in
     * {@code code-generation-triggers.adoc}.
     */
    enum NonTableParentCase implements ClassificationCase {
        RECORD_READ_ON_RESULT_TYPE(
            "record-backed (ResultType) parent — scalar field → RecordReadField with the field-name accessor",
            """
            type FilmDetails { title: String }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDetailsProps"})
            }
            """,
            schema -> {
                var f = (RecordReadField) schema.field("FilmDetails", "title");
                var ja = (ValueLocator.JavaAccessor) f.locator();
                assertThat(accessorMemberName(ja.accessor())).isEqualTo("title");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(RecordReadField.class); }
        },

        RECORD_READ_EXPLICIT_NAME(
            "record-backed parent + @field(name:) — the locator resolves against the explicit name",
            """
            type FilmDetails { title: String @field(name: "film_title") }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDetailsProps"})
            }
            """,
            schema -> {
                var f = (RecordReadField) schema.field("FilmDetails", "title");
                var ja = (ValueLocator.JavaAccessor) f.locator();
                assertThat(accessorMemberName(ja.accessor())).isEqualTo("film_title");
            }),

        SERVICE_FIELD_ON_RESULT_TYPE(
            "record-backed parent + @service + scalar return → DEFERRED until batch-key lift through parent chain ships",
            """
            type FilmDetails { rating: String @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"}) }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """,
            schema -> {
                var field = schema.field("FilmDetails", "rating");
                assertThat(field).isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField.class);
                var unc = (no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField) field;
                assertThat(unc.kind()).isEqualTo(no.sikt.graphitron.rewrite.RejectionKind.DEFERRED);
                assertThat(unc.reason()).contains("record-backed parent", "lifted through the parent chain");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField.class); }
        },

        // The bare `record-backed parent + @table return (no @lookupKey) -> BatchedTableField`
        // verdict lives in the spec-by-example corpus (FilmDetails.language in the `record-table`
        // ClassifiedCorpus example). This enum keeps the slot-asserting record-table cases below
        // (FK inference, single cardinality, @splitQuery warning, @sourceRow lifters).

        RECORD_LOOKUP_TABLE_FIELD(
            "record-backed parent (typed POJO) + @table return type + @lookupKey → BatchedTableField with populated SourceKey",
            """
            type Language @table(name: "language") { name: String }
            type FilmDetails {
              language(language_id: ID! @lookupKey): Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """,
            schema -> {
                var f = (BatchedTableField) schema.field("FilmDetails", "language");
                assertThat(f.lift()).isInstanceOf(KeyLift.FkColumns.class);
                assertThat(f.sourceKey().columns()).isNotEmpty();
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(BatchedTableField.class); }
        },

        IMPLICIT_REFERENCE_RECORD_TABLE(
            "JooqTableRecordType record-backed parent + @table return with single FK → BatchedTableField with one inferred FK hop",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails {
              inventories: [Inventory!]!
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
            }
            """,
            schema -> {
                var f = (BatchedTableField) schema.field("FilmDetails", "inventories");
                assertThat(f.joinPath()).hasSize(1);
                assertThat(f.joinPath().get(0)).matches(TestFixtures::isFkHop, "FK-derived hop");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(BatchedTableField.class); }
        },

        IMPLICIT_REFERENCE_RECORD_LOOKUP_TABLE(
            "JooqTableRecordType record-backed parent + @table return + @lookupKey with single FK → BatchedTableField with one inferred FK hop",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails {
              inventories(inventory_id: [Int!] @lookupKey): [Inventory!]!
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
            }
            """,
            schema -> {
                var f = (BatchedTableField) schema.field("FilmDetails", "inventories");
                assertThat(f.joinPath()).hasSize(1);
                assertThat(f.joinPath().get(0)).matches(TestFixtures::isFkHop, "FK-derived hop");
                assertThat(f.lift()).isInstanceOf(KeyLift.FkColumns.class);
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(BatchedTableField.class); }
        },

        // The bare `record-backed parent + non-table object return -> RecordReadField` verdict
        // lives in the spec-by-example corpus (FilmDetails.stats in the `mapping` ClassifiedCorpus
        // example). This enum keeps the slot-asserting record cases above.

        RECORD_TABLE_FIELD_SINGLE_CARDINALITY(
            "record-backed parent + @table return + single cardinality → BatchedTableField (R61 lifted Invariant #10)",
            """
            type Language @table(name: "language") { name: String }
            type FilmDetails {
              language: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """,
            schema -> {
                var f = (BatchedTableField) schema.field("FilmDetails", "language");
                assertThat(f.returnType().wrapper()).isInstanceOf(no.sikt.graphitron.rewrite.model.FieldWrapper.Single.class);
                assertThat(f.lift()).isInstanceOf(KeyLift.FkColumns.class);
                assertThat(f.emitsSingleRecordPerKey()).isTrue();
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(BatchedTableField.class); }
        },

        // The bare `@service + @table return -> ServiceTableField` verdict lives in the
        // spec-by-example corpus (Film.language in the `service` ClassifiedCorpus example). This
        // enum keeps the slot-asserting service cases above (e.g. TABLE_TYPE_RETURN, the
        // @reference origin-defaulting cases).

        // The `@table parent + record-backed child type` shape is the mixed-source reach: the child
        // type is projected as a NestingField off the @table parent and also read through its
        // producer, served by a run-time source-shape dispatch. Coverage lives in
        // MixedSourceNestedTypeReadsTest (positive) and MixedSourceNestingReachValidationTest
        // (negatives), not as a single-shape classification here.

        // @splitQuery on a record-backed parent field is a structural no-op (the record handoff
        // already opens a new DataLoader-backed scope) and should surface as a build warning so
        // the developer who added it learns the directive changes nothing. Two fixtures cover
        // the regular record-backed parent classification arms; the @sourceRow seam is covered in
        // SourceRowClassificationCase (one fixture, since both arms share the same emit-warning
        // seam).

        SPLIT_QUERY_ON_RECORD_PARENT_WARNS_TABLE_FIELD(
            "@splitQuery on record-backed parent + @table return → BatchedTableField + build warning naming the field coordinate",
            """
            type Language @table(name: "language") { name: String }
            type FilmDetails {
              language: Language @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """,
            schema -> {
                assertThat(schema.field("FilmDetails", "language")).isInstanceOf(BatchedTableField.class);
                assertThat(schema.warnings())
                    .extracting(BuildWarning::message)
                    .anyMatch(m -> m.contains("FilmDetails.language")
                        && m.contains("@splitQuery is redundant on a record-backed parent field"));
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(BatchedTableField.class); }
        },

        SPLIT_QUERY_ON_RECORD_PARENT_WARNS_LOOKUP_FIELD(
            "@splitQuery on record-backed parent + @lookupKey + @table return → BatchedTableField + build warning",
            """
            type Language @table(name: "language") { name: String }
            type FilmDetails {
              language(language_id: ID! @lookupKey): Language
                @splitQuery
                @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """,
            schema -> {
                assertThat(schema.field("FilmDetails", "language")).isInstanceOf(BatchedTableField.class);
                assertThat(schema.warnings())
                    .extracting(BuildWarning::message)
                    .anyMatch(m -> m.contains("FilmDetails.language")
                        && m.contains("@splitQuery is redundant on a record-backed parent field"));
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(BatchedTableField.class); }
        },

        // Holistic-surfacing rule. An unrelated rejection on the same field (here, an
        // unresolvable @reference) must not suppress the @splitQuery redundancy advisory ; the
        // developer needs both diagnostics, not just whichever fires first. Locks the warning
        // emission to the table-bound-return seam, before any rejection guard.
        SPLIT_QUERY_WARNS_ALONGSIDE_RECORD_PARENT_REJECTION(
            "@splitQuery on record-backed parent with unresolvable @reference → UnclassifiedField + build warning still fires",
            """
            type Language @table(name: "language") { name: String }
            type FilmDetails {
              language: Language @splitQuery @reference(path: [{key: "no_such_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """,
            schema -> {
                assertThat(schema.field("FilmDetails", "language")).isInstanceOf(UnclassifiedField.class);
                assertThat(schema.warnings())
                    .extracting(BuildWarning::message)
                    .anyMatch(m -> m.contains("FilmDetails.language")
                        && m.contains("@splitQuery is redundant on a record-backed parent field"));
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        // A hop-0 {key:, condition:} filter on a record-backed parent's @splitQuery
        // @reference. The filter reads the parent row, but a record parent has no @table to anchor
        // the filter's source parameter, so buildParentCorrelation routes it to AuthorError rather
        // than fabricating a parent anchor (without the guard the emitter would bind the hop-0
        // target alias as both filter parameters). The message names the escape hatch.
        SPLIT_QUERY_RECORD_PARENT_HOP0_FILTER_REJECTED(
            "hop-0 {key:, condition:} filter on a @splitQuery record-backed parent → UnclassifiedField (Structural) naming the escape hatch",
            """
            type Language @table(name: "language") { name: String }
            type FilmDetails {
              language: Language @splitQuery @reference(path: [{key: "film_language_id_fkey", condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "join"}}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """,
            schema -> {
                var f = schema.field("FilmDetails", "language");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("hop-0 `condition:` filter")
                    .contains("not a catalog table");
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
            @Override public Set<Class<?>> variants() { return Set.of(ChildField.BatchedTableField.class); }
        },

        SINGLE_RECORD_IDENTITY_FIELD_ORPHAN(
            "R178 Phase 4: a plain Object carrier wrapping a single Object-element data field "
                + "consumed by a Query field with no producing mutation → orphan carrier; the data "
                + "field stays unregistered (record-element identity passthrough is now handled by "
                + "the unified per-field classifier on producer-bound parents).",
            """
            type FilmDto {
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

    @Test
    @ProjectionFor({RecordReadField.class, BatchedTableField.class})
    void recordParentChildProjectionsCarryColumnAccessorAndTableTargetPayloads() {
        // RecordReadField — projection collapses to RecordOrProperty; the JavaAccessor locator
        // carries the accessor member name (no column).
        var s1 = buildSnapshot("""
            type FilmDetails { title: String @field(name: "film_title") }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDetailsProps"})
            }
            """);
        var prop = (FieldClassification.RecordOrProperty) s1.fieldClassificationsByCoord().get("FilmDetails.title");
        assertThat(prop.columnName()).isNull();
        assertThat(prop.accessorName()).isEqualTo("film_title");

        // BatchedTableField — projection is RecordTableTarget(tableName, joinPath, hasLookupKey=false).
        var s2 = buildSnapshot("""
            type Language @table(name: "language") { name: String }
            type FilmDetails {
              language: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """);
        var rt = (FieldClassification.RecordTableTarget) s2.fieldClassificationsByCoord().get("FilmDetails.language");
        assertThat(rt.tableName()).isEqualToIgnoringCase("language");
        assertThat(rt.hasLookupKey()).isFalse();

        // BatchedTableField — hasLookupKey = true.
        var s3 = buildSnapshot("""
            type Language @table(name: "language") { name: String }
            type FilmDetails {
              language(language_id: ID! @lookupKey): Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """);
        var rl = (FieldClassification.RecordTableTarget) s3.fieldClassificationsByCoord().get("FilmDetails.language");
        assertThat(rl.hasLookupKey()).isTrue();
    }

    @Test
    @ProjectionFor(ChildField.RecordCompositeField.class)
    void serviceRecordCompositeCarrierDataFieldProjectsAsRecordOrProperty() {
        // The @service record-composite carrier's data field (a source-passthrough projection
        // of the producer's in-memory composite list) classifies as RecordCompositeField; its LSP
        // projection is the record-backed RecordOrProperty label (no column, no accessor — the field
        // name stands in as the label).
        var s = buildSnapshot("""
            type Film @table(name: "film") { title: String }
            type Actor @table(name: "actor") { firstName: String @field(name: "first_name") }
            type DbErr @error(handlers: [{handler: DATABASE}]) { path: [String!]!  message: String! }
            union CreateError = DbErr
            type CreateFilmsResult {
                film: Film! @field(name: "filmRecord")
                actors: [Actor] @field(name: "actorRecords")
            }
            type CreateFilmsPayload {
                results: [CreateFilmsResult]
                errors: [CreateError]
            }
            type Query { x: String }
            type Mutation {
                createFilms: CreateFilmsPayload
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "createFilmsWithActors"})
            }
            """);
        var rc = (FieldClassification.RecordOrProperty)
            s.fieldClassificationsByCoord().get("CreateFilmsPayload.results");
        assertThat(rc).isNotNull();
        assertThat(rc.columnName()).isEqualTo("results");
        assertThat(rc.accessorName()).isNull();
    }

    // ===== @sourceRow classifier matrix =====

    /**
     * Classifier-level coverage for the {@code @sourceRow} directive — the lifter path
     * for record-backed parents whose backing class has no jOOQ FK metadata. Each case
     * pins one of the resolver invariants in
     * {@link no.sikt.graphitron.rewrite.SourceRowDirectiveResolver}: parent shape (Inv #1),
     * lifter parameter assignability (Inv #2), lifter return type (Inv #3), arity / column-class
     * match against the derived parent-side tuple (Inv #4), {@code @reference} composition
     * vs. leaf-PK derivation (Inv #5), {@code @asConnection} reject (Inv #9). Single-cardinality
     * (Inv #10) is gated by the validator and tested separately under the validation tier.
     *
     * <p>Lifter fixture methods live in {@link TestLifterStub}.
     */
    enum SourceRowClassificationCase implements ClassificationCase {
        POJO_PARENT_VALID_ROW1_LIST(
            "Pojo parent + valid Row1<Integer> lifter + @reference, list return → BatchedTableField with KeyLift.Lifter",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails {
              inventories: [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow1Integer")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """,
            schema -> {
                var f = (BatchedTableField) schema.field("FilmDetails", "inventories");
                var sk = f.sourceKey();
                assertThat(f.lift()).isInstanceOf(KeyLift.Lifter.class);
                var lifter = ((KeyLift.Lifter) f.lift()).lifter();
                assertThat(f.joinPath()).hasSize(1);
                assertThat(sk.columns()).hasSize(1);
                assertThat(sk.columns().get(0).sqlName()).isEqualTo("film_id");
                assertThat(lifter.declaringClass().reflectionName()).isEqualTo("no.sikt.graphitron.rewrite.TestLifterStub");
                assertThat(lifter.methodName()).isEqualTo("dummyRow1Integer");
                // First hop's target table is film (the FK's referenced side); the leaf is
                // inventory and lives on the field's returnType, not on the path.
                assertThat(f.returnType().table().tableName()).isEqualTo("inventory");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(BatchedTableField.class); }
        },

        POJO_PARENT_VALID_PLUS_LOOKUPKEY(
            "Pojo parent + valid Row1 lifter + @reference + @lookupKey arg → BatchedTableField with KeyLift.Lifter and lookupMapping populated",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails {
              inventories(inventory_id: [Int!]! @lookupKey): [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow1Integer")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """,
            schema -> {
                var f = (BatchedTableField) schema.field("FilmDetails", "inventories");
                assertThat(f.lift()).isInstanceOf(KeyLift.Lifter.class);
                assertThat(keyedMappingOf(f.lookup())).isNotNull();
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(BatchedTableField.class); }
        },

        // No case for "record-backed parent with no backing class + @sourceRow": under
        // reflection-only binding a no-backing record-backed type is a NestingType whose fields
        // don't classify, so that scenario cannot arise. UnclassifiedField coverage for SourceRow
        // lives in the other reject cases.
        TABLE_PARENT_REJECT(
            "@table parent + lifter → UnclassifiedField AUTHOR_ERROR (lifter is for record-backed parents)",
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
                assertThat(unc.reason()).contains("@sourceRow").contains("record-backed").contains("@reference");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        JOOQ_TABLE_RECORD_PARENT_REJECT(
            "JooqTableRecordType parent + lifter → UnclassifiedField AUTHOR_ERROR pointing at the catalog FK",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails {
              inventories: [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow1Integer")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
            }
            """,
            schema -> {
                var unc = (UnclassifiedField) schema.field("FilmDetails", "inventories");
                assertThat(unc.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
                assertThat(unc.reason()).contains("@sourceRow").contains("jOOQ-backed");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        JAVA_RECORD_PARENT_ADMIT(
            "JavaRecordType parent (non-null fqClassName) + lifter → BatchedTableField (admitted same as PojoResultType)",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails {
              inventories: [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "javaRecordRow1Integer")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeTestRecordDto"})
            }
            """,
            schema -> {
                var f = (BatchedTableField) schema.field("FilmDetails", "inventories");
                assertThat(f.lift()).isInstanceOf(KeyLift.Lifter.class);
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(BatchedTableField.class); }
        },

        MISSING_LIFTER_CLASS(
            "Lifter className that doesn't load → UnclassifiedField AUTHOR_ERROR with 'could not be loaded'",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails {
              inventories: [Inventory!]!
                @sourceRow(className: "com.example.Nonexistent", method: "doesNotMatter")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
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
            type FilmDetails {
              inventories: [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummiRow1Integer")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
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
            type FilmDetails {
              inventories: [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "wrongReturnLong")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
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
            type FilmDetails {
              inventories: [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "wrongParamType")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
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
            type FilmDetails {
              inventories: [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow2IntInt")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
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
            type FilmDetails {
              inventories: [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow1String")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
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
            type FilmDetails {
              inventories: [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow1WildcardNumber")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
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
            type FilmDetails {
              inventories: [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow1Integer")
                @reference(path: [{key: "non_existent_fk_key"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
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
            type FilmDetails {
              inventories: [Inventory!]!
                @asConnection
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow1Integer")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
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
            type FilmDetails {
              inventories: [Inventory!]!
                @field(name: "irrelevant_for_lifter")
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow1Integer")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """,
            schema -> {
                var f = (BatchedTableField) schema.field("FilmDetails", "inventories");
                assertThat(f.lift()).isInstanceOf(KeyLift.Lifter.class);
                assertThat(f.sourceKey().columns()).extracting(no.sikt.graphitron.rewrite.model.ColumnRef::sqlName)
                    .containsExactly("film_id");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(BatchedTableField.class); }
        },

        WITH_FIELD_LEVEL_CONDITION(
            "Pojo parent + lifter + @condition on the field → BatchedTableField; tfc.filters() carries the resolved ConditionFilter",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails {
              inventories: [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow1Integer")
                @reference(path: [{key: "inventory_film_id_fkey"}])
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "lifterFieldCondition"})
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """,
            schema -> {
                var f = (BatchedTableField) schema.field("FilmDetails", "inventories");
                assertThat(f.lift()).isInstanceOf(KeyLift.Lifter.class);
                assertThat(f.filters())
                    .filteredOn(filter -> filter instanceof ConditionFilter)
                    .extracting(filter -> ((ConditionFilter) filter).methodName())
                    .containsExactly("lifterFieldCondition");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(BatchedTableField.class); }
        },

        WITH_ORDER_BY_ARG(
            "Pojo parent + lifter + @orderBy arg → BatchedTableField; tfc.orderBy() carries the resolved OrderBySpec.Argument",
            """
            enum InventoryOrderField { ID @order(primaryKey: true) }
            enum Direction { ASC DESC }
            input InventoryOrder { sortField: InventoryOrderField! direction: Direction! }
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails {
              inventories(order: InventoryOrder @orderBy): [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow1Integer")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """,
            schema -> {
                var f = (BatchedTableField) schema.field("FilmDetails", "inventories");
                assertThat(f.lift()).isInstanceOf(KeyLift.Lifter.class);
                assertThat(f.orderBy()).isInstanceOf(OrderBySpec.Argument.class);
                var orderBy = (OrderBySpec.Argument) f.orderBy();
                assertThat(orderBy.typeName()).isEqualTo("InventoryOrder");
                assertThat(orderBy.namedOrders()).hasSize(1);
                assertThat(orderBy.namedOrders().get(0).name()).isEqualTo("ID");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(BatchedTableField.class); }
        },

        SCALAR_RETURN_REJECT(
            "Pojo parent + @sourceRow on a scalar-return field → UnclassifiedField AUTHOR_ERROR (directive applies only to @table-bound returns)",
            """
            type FilmDetails {
              rating: String
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow1Integer")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """,
            schema -> {
                var unc = (UnclassifiedField) schema.field("FilmDetails", "rating");
                assertThat(unc.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
                assertThat(unc.reason()).contains("@sourceRow").contains("@table-bound");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        LEAF_PK_NO_REFERENCE(
            "Pojo parent + @sourceRow alone (no @reference) → BatchedTableField with KeyLift.Lifter; lifter RowN matches the leaf target's PK columns directly.",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails {
              inventories: [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow1Integer")
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """,
            schema -> {
                var f = (BatchedTableField) schema.field("FilmDetails", "inventories");
                var sk = f.sourceKey();
                assertThat(f.lift()).isInstanceOf(KeyLift.Lifter.class);
                assertThat(sk.columns()).extracting(no.sikt.graphitron.rewrite.model.ColumnRef::sqlName)
                    .containsExactly("inventory_id");
                // Leaf-PK variant: hop-less — empty joinPath plus the pre-keyed
                // OnLiftedSlots correlation pointing at the leaf target.
                assertThat(f.joinPath()).isEmpty();
                assertThat(f.parentCorrelation()).isInstanceOf(ParentCorrelation.OnLiftedSlots.class);
                assertThat(((ParentCorrelation.OnLiftedSlots) f.parentCorrelation()).targetTable().tableName())
                    .isEqualTo("inventory");
                assertThat(((KeyLift.Lifter) f.lift()).lifter().methodName())
                    .isEqualTo("dummyRow1Integer");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(BatchedTableField.class); }
        },

        LEAF_PK_ARITY_MISMATCH(
            "Lifter Row arity 2 against single-column leaf-PK → UnclassifiedField AUTHOR_ERROR; the diagnostic distinguishes leaf-PK from first-hop source-side (R110 spec).",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails {
              inventories: [Inventory!]!
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow2IntInt")
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """,
            schema -> {
                var unc = (UnclassifiedField) schema.field("FilmDetails", "inventories");
                assertThat(unc.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
                assertThat(unc.reason()).contains("arity 2").contains("leaf-PK");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        // @splitQuery on a @sourceRow record-backed parent field is just as silently no-op as on
        // the regular record-backed parent path — the lifter-keyed DataLoader already opens a new
        // scope. One fixture is enough since both arms (BatchedTableField, BatchedTableField)
        // share the same emit-warning seam.
        SPLIT_QUERY_WARNS_ON_SOURCE_ROW(
            "@splitQuery on @sourceRow record-backed parent field → BatchedTableField + build warning naming the field coordinate",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails {
              inventories: [Inventory!]!
                @splitQuery
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "dummyRow1Integer")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """,
            schema -> {
                assertThat(schema.field("FilmDetails", "inventories")).isInstanceOf(BatchedTableField.class);
                assertThat(schema.warnings())
                    .extracting(BuildWarning::message)
                    .anyMatch(m -> m.contains("FilmDetails.inventories")
                        && m.contains("@splitQuery is redundant on a record-backed parent field"));
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(BatchedTableField.class); }
        },

        // Holistic-surfacing rule. The @sourceRow seam mirrors the regular record-backed parent
        // path: an unrelated lifter-signature rejection (Inv #3 in SourceRowDirectiveResolver)
        // must not suppress the @splitQuery redundancy advisory.
        SPLIT_QUERY_WARNS_ALONGSIDE_SOURCE_ROW_REJECTION(
            "@splitQuery on @sourceRow with bad lifter signature → UnclassifiedField + build warning still fires",
            """
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type FilmDetails {
              inventories: [Inventory!]!
                @splitQuery
                @sourceRow(className: "no.sikt.graphitron.rewrite.TestLifterStub", method: "wrongReturnLong")
                @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Film @table(name: "film") { details: FilmDetails }
            type Query {
                film: Film
                prodFilmDetails: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """,
            schema -> {
                var unc = (UnclassifiedField) schema.field("FilmDetails", "inventories");
                assertThat(unc.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
                assertThat(schema.warnings())
                    .extracting(BuildWarning::message)
                    .anyMatch(m -> m.contains("FilmDetails.inventories")
                        && m.contains("@splitQuery is redundant on a record-backed parent field"));
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

    // ===== Accessor-derived SourceKey classifier matrix =====

    /**
     * Classifier-level coverage for the auto-derivation that runs on record-backed parents
     * whose backing class exposes a typed zero-arg accessor returning a concrete jOOQ
     * {@code TableRecord} subtype. Pins the
     * {@link no.sikt.graphitron.rewrite.FieldBuilder#deriveAccessorRecordParentSource} match
     * rule across the cross-product corners: list-field × list / set accessor, single-field ×
     * single accessor, ambiguous candidates, cardinality mismatches, and heterogeneous element
     * types that fall through to the three-option AUTHOR_ERROR.
     *
     * <p>Backing-class fixtures live in
     * {@link no.sikt.graphitron.codereferences.dummyreferences.AccessorPayloads}.
     */
    enum AccessorDerivedSourceCase implements ClassificationCase {
        ACCESSOR_ROWKEYED_MANY_LIST_FIELD_LIST_ACCESSOR(
            "List field + list-of-TableRecord accessor → BatchedTableField with KeyLift.Accessor + Arity.MANY",
            """
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Payload {
              films: [Film!]!
            }
            type Query {
              payload: Payload @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeAccessorListPayload"})
            }
            """,
            schema -> {
                assertThat(schema.field("Payload", "films")).isInstanceOfSatisfying(BatchedTableField.class, f -> {
                    var sk = f.sourceKey();
                    assertThat(f.lift()).isInstanceOfSatisfying(KeyLift.Accessor.class, ac -> {
                        assertThat(ac.arity()).isEqualTo(Arity.MANY);
                        assertThat(ac.accessor().methodName()).isEqualTo("films");
                    });
                    assertThat(sk.columns()).hasSize(1);
                    assertThat(sk.columns().get(0).sqlName()).isEqualTo("film_id");
                    assertThat(f.joinPath()).isEmpty();
                    assertThat(f.parentCorrelation()).isInstanceOf(ParentCorrelation.OnLiftedSlots.class);
                });
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(BatchedTableField.class); }
        },

        ACCESSOR_ROWKEYED_MANY_LIST_FIELD_SET_ACCESSOR(
            "List field + set-of-TableRecord accessor → BatchedTableField with KeyLift.Accessor + Arity.MANY",
            """
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Payload {
              films: [Film!]!
            }
            type Query {
              payload: Payload @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeAccessorSetPayload"})
            }
            """,
            schema -> {
                assertThat(schema.field("Payload", "films")).isInstanceOfSatisfying(BatchedTableField.class, f -> {
                    var sk = f.sourceKey();
                    assertThat(f.lift()).isInstanceOfSatisfying(KeyLift.Accessor.class, ac ->
                        assertThat(ac.arity()).isEqualTo(Arity.MANY));
                    // The Set<X> vs List<X> split inside Many is not preserved on the lift; emit
                    // is uniform via Iterable. The fixture still exercises the Set classifier path.
                });
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(BatchedTableField.class); }
        },

        ACCESSOR_ROWKEYED_SINGLE_SINGLE_FIELD_SINGLE_ACCESSOR(
            "Single field + single-TableRecord accessor → BatchedTableField with KeyLift.Accessor + Arity.ONE",
            """
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Payload {
              film: Film
            }
            type Query {
              payload: Payload @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeAccessorSinglePayload"})
            }
            """,
            schema -> {
                assertThat(schema.field("Payload", "film")).isInstanceOfSatisfying(BatchedTableField.class, f -> {
                    var sk = f.sourceKey();
                    assertThat(f.lift()).isInstanceOfSatisfying(KeyLift.Accessor.class, ac -> {
                        assertThat(ac.arity()).isEqualTo(Arity.ONE);
                        assertThat(ac.accessor().methodName()).isEqualTo("film");
                    });
                    assertThat(sk.columns()).hasSize(1);
                    assertThat(sk.columns().get(0).sqlName()).isEqualTo("film_id");
                    assertThat(f.joinPath()).isEmpty();
                    assertThat(f.parentCorrelation()).isInstanceOf(ParentCorrelation.OnLiftedSlots.class);
                });
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(BatchedTableField.class); }
        },

        ACCESSOR_ROWKEYED_REJECTS_AMBIGUOUS(
            "Two accessors returning List<FilmRecord> for the same @table → UnclassifiedField (ambiguous)",
            """
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Payload {
              films: [Film!]!
            }
            type Query {
              payload: Payload @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeAccessorAmbiguousListPayload"})
            }
            """,
            schema -> {
                assertThat(schema.field("Payload", "films")).isInstanceOfSatisfying(UnclassifiedField.class, unc -> {
                    assertThat(unc.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
                    assertThat(unc.reason()).contains("more than one typed accessor");
                    assertThat(unc.reason()).contains("films");
                    assertThat(unc.reason()).contains("getFilms");
                    assertThat(unc.reason()).contains("@sourceRow");
                });
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        ACCESSOR_ROWKEYED_REJECTS_CARDINALITY_LIST_FIELD_SINGLE_ACCESSOR(
            "List field + single-record accessor → UnclassifiedField (cardinality mismatch)",
            """
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Payload {
              films: [Film!]!
            }
            type Query {
              payload: Payload @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeAccessorSingleAccessorOnListField"})
            }
            """,
            schema -> {
                assertThat(schema.field("Payload", "films")).isInstanceOfSatisfying(UnclassifiedField.class, unc -> {
                    assertThat(unc.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
                    assertThat(unc.reason()).contains("list field 'films'");
                    assertThat(unc.reason()).contains("returning a single record");
                });
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        ACCESSOR_ROWKEYED_FIELD_NAME_REMAPS_ACCESSOR(
            "@field(name:) on a free-form record-backed parent remaps the accessor base name → admits with the directive-named accessor",
            """
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Payload {
              film: Film @field(name: "filmRecord")
            }
            type Query {
              payload: Payload @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeAccessorRemappedPayload"})
            }
            """,
            schema -> {
                assertThat(schema.field("Payload", "film")).isInstanceOfSatisfying(BatchedTableField.class, f -> {
                    var sk = f.sourceKey();
                    assertThat(f.lift()).isInstanceOf(KeyLift.Accessor.class);
                    assertThat(((KeyLift.Accessor) f.lift()).arity()).isEqualTo(Arity.ONE);
                    // The carried method name is the actual accessor name (the directive value),
                    // not the SDL field name.
                    assertThat(((KeyLift.Accessor) f.lift()).accessor().methodName())
                        .isEqualTo("filmRecord");
                    assertThat(sk.columns()).hasSize(1);
                    assertThat(sk.columns().get(0).sqlName()).isEqualTo("film_id");
                });
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(BatchedTableField.class); }
        },

        ACCESSOR_ROWKEYED_FIELD_NAME_REJECTS_WITHOUT_DIRECTIVE(
            "Divergent accessor name with no @field(name:) on a free-form record-backed parent → falls through to the three-option AUTHOR_ERROR",
            """
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Payload {
              film: Film
            }
            type Query {
              payload: Payload @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeAccessorRemappedPayload"})
            }
            """,
            schema -> {
                assertThat(schema.field("Payload", "film")).isInstanceOfSatisfying(UnclassifiedField.class, unc -> {
                    assertThat(unc.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
                    assertThat(unc.reason()).contains("typed accessor");
                    assertThat(unc.reason()).contains("@sourceRow");
                    assertThat(unc.reason()).contains("typed jOOQ TableRecord");
                });
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        ACCESSOR_ROWKEYED_REJECTS_HETEROGENEOUS_ELEMENT(
            "Accessor element TableRecord doesn't match field's @table → falls through to three-option AUTHOR_ERROR",
            """
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Payload {
              films: Film
            }
            type Query {
              payload: Payload @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeAccessorHeterogeneousElementPayload"})
            }
            """,
            schema -> {
                assertThat(schema.field("Payload", "films")).isInstanceOfSatisfying(UnclassifiedField.class, unc -> {
                    assertThat(unc.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
                    // Falls through to the three-option message; the typed-accessor and
                    // @sourceRow and @table TableRecord options should all be named.
                    assertThat(unc.reason()).contains("typed accessor");
                    assertThat(unc.reason()).contains("@sourceRow");
                    assertThat(unc.reason()).contains("typed jOOQ TableRecord");
                });
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

    // ===== ResultType backing-class classification (reflection-only) =====
    // A result type's backing comes from the producing @service field's reflected return
    // type, or, for a DML carrier, a DML RETURNING payload, never the @record directive.
    // The bare backing verdicts (PojoResultType.Backed, JavaRecordType, JooqTableRecordType) live
    // in the spec-by-example corpus (`result-backing` ClassifiedCorpus example, confirmed by
    // VariantCoverageTest); the resultTypeBackingProjectionsCarryClassNameAndTablePayloads
    // projection test below keeps the backing-class / table-payload detail under test.

    @Test
    @ProjectionFor({
        PojoResultType.Backed.class,
        JavaRecordType.class, JooqTableRecordType.class
    })
    void resultTypeBackingProjectionsCarryClassNameAndTablePayloads() {
        // A DML carrier binds to its RETURNING table's record (JooqTableRecordType), so its
        // catalog projection is a JooqTableRecord.
        var s1 = buildSnapshot("""
            type Film @table(name: "film") { title: String }
            input FilmInput { title: String }
            type FilmPayload { films: [Film!] }
            type Query { x: String }
            type Mutation { createFilms(in: [FilmInput!]!): FilmPayload @mutation(typeName: INSERT) }
            """);
        assertThat(s1.typeClassificationsByName().get("FilmPayload"))
            .isInstanceOf(TypeClassification.JooqTableRecord.class);

        // PojoResultType.Backed → TypeClassification.PojoResult(fqClassName). Backing comes
        // from the @service producer's reflected return type, not the @record directive.
        var s2 = buildSnapshot("""
            type FilmDetails { id: ID }
            type Query {
                foo: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """);
        var backed = (TypeClassification.PojoResult) s2.typeClassificationsByName().get("FilmDetails");
        assertThat(backed.fqClassName()).isEqualTo("no.sikt.graphitron.codereferences.dummyreferences.DummyRecord");

        // JavaRecordType → TypeClassification.JavaRecord
        var s3 = buildSnapshot("""
            type FilmDetails { id: ID }
            type Query {
                foo: FilmDetails @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeTestRecordDto"})
            }
            """);
        var jr = (TypeClassification.JavaRecord) s3.typeClassificationsByName().get("FilmDetails");
        assertThat(jr.fqClassName()).isEqualTo("no.sikt.graphitron.codereferences.dummyreferences.TestRecordDto");

        // JooqTableRecordType → TypeClassification.JooqTableRecord with table
        var s4 = buildSnapshot("""
            type FilmDetails { id: ID }
            type Query {
                foo: FilmDetails @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
            }
            """);
        var jtr = (TypeClassification.JooqTableRecord) s4.typeClassificationsByName().get("FilmDetails");
        assertThat(jtr.fqClassName()).isEqualTo("no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord");
        assertThat(jtr.tableName()).isEqualTo("film");
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
            "@lookupKey on a child-field argument (no @splitQuery) — field classified as TableField; key flows through LookupMapping",
            """
            type Actor @table(name: "actor") { name: String }
            type FilmActor @table(name: "film_actor") {
                actors(actor_id: [Int!]! @lookupKey): [Actor!]!
            }
            type Query { filmActor: FilmActor }
            """,
            schema -> {
                var f = (no.sikt.graphitron.rewrite.model.ChildField.TableField) schema.field("FilmActor", "actors");
                // @lookupKey args are emitted via VALUES+JOIN from LookupMapping, not as filters.
                assertThat(f.filters()).isEmpty();
                assertThat(((no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) keyedMappingOf(f.lookup())).args()).hasSize(1);
                assertThat(((no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) keyedMappingOf(f.lookup())).args().get(0).argName()).isEqualTo("actor_id");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(TableField.class); }
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

        // ===== @condition directive on fields and arguments =====
        // These verify the projection semantics in docs/architecture/reference/argument-resolution.adoc.
        // The stub methods live in TestConditionStub (fieldCondition, argCondition, argConditionWithContext).

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
            "QueryTableField populates LookupMapping with one ScalarLookupArg per @lookupKey arg",
            """
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query {
                filmById(film_id: [ID] @lookupKey): [Film!]!
            }
            """,
            schema -> {
                var f = (QueryField.QueryTableField) schema.field("Query", "filmById");
                assertThat(keyedMappingOf(f.lookup()).targetTable().tableName()).isEqualTo("film");
                var cm = (no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) keyedMappingOf(f.lookup());
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
                assertThat(((no.sikt.graphitron.rewrite.model.ParamSource.Arg) renamedParam.source()).path().headName())
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
                assertThat(((no.sikt.graphitron.rewrite.model.ParamSource.Arg) argParams.get(0).source()).path().headName())
                    .isEqualTo("cityNames");
                assertThat(argParams.get(1).name()).isEqualTo("country");
                assertThat(((no.sikt.graphitron.rewrite.model.ParamSource.Arg) argParams.get(1).source()).path().headName())
                    .isEqualTo("countryId");
            }),

        // ===== UnboundArg / UnclassifiedArg error surfacing =====
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
            input FilmKey {
                languageName: String @reference(path: [{key: "film_language_id_fkey"}]) @field(name: "name") @lookupKey
            }
            type Film @table(name: "film") { title: String }
            type Query {
                filmByKey(key: FilmKey): [Film!]!
            }
            """,
            schema -> {
                // @lookupKey on INPUT_FIELD_DEFINITION is rejected on both mutation and query
                // surfaces. The diagnostic mentions the migration target (filter-by-default on
                // mutation inputs; the UPDATE SET/WHERE partition is catalog-derived by the walker).
                // For Query, the @lookupKey moves to the arg if lookup behavior is intended;
                // see LOOKUP_KEY_ON_NODEID_INPUT_FIELD_ADMITTED for that shape.
                var f = (no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField) schema.field("Query", "filmByKey");
                assertThat(f.reason())
                    .contains("@lookupKey on a mutation input field is no longer supported");
            }),

        LOOKUP_KEY_ON_NODEID_INPUT_FIELD_ADMITTED(
            "R130 + R144: @lookupKey on the ARGUMENT_DEFINITION of a same-table singular "
                + "`id: ID! @nodeId` plain input arg → admitted via the extraction-propagation "
                + "path. R144 retires @lookupKey on the input field; the arg-level @lookupKey "
                + "drives the lookup-binding walk over every admissible input field, resolved "
                + "against the consuming field's return table. The carrier "
                + "classifies as InputField.ColumnBackedField with NodeIdDecodeKeys extraction; "
                + "buildLookupBindings reads cf.extraction() directly so the resolver-supplied "
                + "decode method survives the binding-build (R130 fix at source). The MapGroup "
                + "carries one MapBinding whose extraction is the carrier's NodeIdDecodeKeys.",
            """
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId }
            input FilmLookupKey {
                id: ID! @nodeId
            }
            type Query {
                filmByKey(key: FilmLookupKey @lookupKey): [Film!]!
            }
            """,
            schema -> {
                var f = (no.sikt.graphitron.rewrite.model.QueryField.QueryTableField)
                    schema.field("Query", "filmByKey");
                var mapping = (no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping)
                    keyedMappingOf(f.lookup());
                assertThat(mapping.args()).hasSize(1);
                var arg = mapping.args().get(0);
                assertThat(arg).isInstanceOf(
                    no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping.LookupArg.MapInput.class);
                var mi = (no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping.LookupArg.MapInput) arg;
                assertThat(mi.bindings()).hasSize(1);
                assertThat(mi.bindings().get(0).extraction())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.NodeIdDecodeKeys.class);
            }),

        // ===== Phase 4: @condition on INPUT_FIELD_DEFINITION (condition emission via projectFilters) =====

        INPUT_FIELD_CONDITION_ARGMAPPING(
            "R53: @condition on an input field with argMapping — Java parameter name diverges from the input-field name",
            """
            input FilmInput {
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
                assertThat(((no.sikt.graphitron.rewrite.model.ParamSource.Arg) renamedParam.source()).path().headName())
                    .isEqualTo("filmId");
            }),

        INFER_NESTED_CONDITION_ARG_BY_NAME(
            "R355: @condition on an input-object input field with NO argMapping — the method's "
                + "fra/til params bind one level in to the same-named nested fields, producing the "
                + "depth-1 PathExpr the explicit-argMapping sibling produces (liftsList=false scalar leaves)",
            """
            scalar BigDecimal @scalarType(scalar: "graphql.scalars.ExtendedScalars.GraphQLBigDecimal")
            input SokVerdiRange { fra: BigDecimal  til: BigDecimal }
            input FilmVerdiFilter {
              range: SokVerdiRange
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "searchVerdiRange"}, override: true)
            }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { films(filter: FilmVerdiFilter): [Film!]! }
            """,
            schema -> {
                var f = (QueryField.QueryTableField) schema.field("Query", "films");
                var cond = (ConditionFilter) f.filters().stream()
                    .filter(x -> x instanceof ConditionFilter).findFirst().orElseThrow();
                assertThat(cond.methodName()).isEqualTo("searchVerdiRange");
                // Classifier-output equality (not emitted body strings): the inferred PathExpr is
                // byte-identical to what argMapping "fra: range.fra, til: range.til" yields — the
                // INFER_NESTED_CONDITION_ARG_EXPLICIT_ARGMAPPING_EQUIVALENT case asserts the same values.
                var argPaths = cond.params().stream()
                    .filter(p -> p.source() instanceof no.sikt.graphitron.rewrite.model.ParamSource.Arg)
                    .collect(java.util.stream.Collectors.toMap(
                        no.sikt.graphitron.rewrite.model.MethodRef.Param::name,
                        p -> ((no.sikt.graphitron.rewrite.model.ParamSource.Arg) p.source()).path()));
                assertThat(argPaths).hasSize(2)
                    .containsEntry("fra", PathExpr.step(PathExpr.head("range"), "fra", false))
                    .containsEntry("til", PathExpr.step(PathExpr.head("range"), "til", false));
            }),

        INFER_NESTED_CONDITION_ARG_EXPLICIT_ARGMAPPING_EQUIVALENT(
            "R355: the explicit-argMapping sibling of INFER_NESTED_CONDITION_ARG_BY_NAME — the same "
                + "schema with argMapping spelled out produces the identical PathExpr chain, pinning "
                + "that the inference fills in exactly the path the author would otherwise write",
            """
            scalar BigDecimal @scalarType(scalar: "graphql.scalars.ExtendedScalars.GraphQLBigDecimal")
            input SokVerdiRange { fra: BigDecimal  til: BigDecimal }
            input FilmVerdiFilter {
              range: SokVerdiRange
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "searchVerdiRange", argMapping: "fra: range.fra, til: range.til"}, override: true)
            }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { films(filter: FilmVerdiFilter): [Film!]! }
            """,
            schema -> {
                var f = (QueryField.QueryTableField) schema.field("Query", "films");
                var cond = (ConditionFilter) f.filters().stream()
                    .filter(x -> x instanceof ConditionFilter).findFirst().orElseThrow();
                var argPaths = cond.params().stream()
                    .filter(p -> p.source() instanceof no.sikt.graphitron.rewrite.model.ParamSource.Arg)
                    .collect(java.util.stream.Collectors.toMap(
                        no.sikt.graphitron.rewrite.model.MethodRef.Param::name,
                        p -> ((no.sikt.graphitron.rewrite.model.ParamSource.Arg) p.source()).path()));
                assertThat(argPaths).hasSize(2)
                    .containsEntry("fra", PathExpr.step(PathExpr.head("range"), "fra", false))
                    .containsEntry("til", PathExpr.step(PathExpr.head("range"), "til", false));
            }),

        INFER_NESTED_CONDITION_ARG_LIST_LIFTS_LIST(
            "R355: a depth-1 list field [BigDecimal] bound to a List<BigDecimal> param infers a Step "
                + "whose liftsList is COMPUTED true (via ArgBindingMap.isListShaped) — the scalar case "
                + "above leaves it false either way, so only this case separates computed from hardcoded",
            """
            scalar BigDecimal @scalarType(scalar: "graphql.scalars.ExtendedScalars.GraphQLBigDecimal")
            input SokVerdiListRange { verdier: [BigDecimal] }
            input FilmVerdiListFilter {
              range: SokVerdiListRange
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "searchVerdiList"}, override: true)
            }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { films(filter: FilmVerdiListFilter): [Film!]! }
            """,
            schema -> {
                var f = (QueryField.QueryTableField) schema.field("Query", "films");
                var cond = (ConditionFilter) f.filters().stream()
                    .filter(x -> x instanceof ConditionFilter).findFirst().orElseThrow();
                assertThat(cond.methodName()).isEqualTo("searchVerdiList");
                var argPaths = cond.params().stream()
                    .filter(p -> p.source() instanceof no.sikt.graphitron.rewrite.model.ParamSource.Arg)
                    .collect(java.util.stream.Collectors.toMap(
                        no.sikt.graphitron.rewrite.model.MethodRef.Param::name,
                        p -> ((no.sikt.graphitron.rewrite.model.ParamSource.Arg) p.source()).path()));
                assertThat(argPaths).hasSize(1)
                    .containsEntry("verdier", PathExpr.step(PathExpr.head("range"), "verdier", true));
            }),

        INFER_NESTED_CONDITION_ARG_AMBIGUOUS_FALLS_THROUGH(
            "R355: a parameter name matching a nested field in TWO input-object args is ambiguous "
                + "(two candidates across slots) → inference yields, leaving 'fra' unbound, so the "
                + "existing name-mismatch rejection fires rather than an arbitrary binding",
            """
            scalar BigDecimal @scalarType(scalar: "graphql.scalars.ExtendedScalars.GraphQLBigDecimal")
            input VerdiRangeA { fra: BigDecimal }
            input VerdiRangeB { fra: BigDecimal }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query {
              films(rangeA: VerdiRangeA, rangeB: VerdiRangeB): [Film!]!
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "searchVerdiAmbiguous"}, override: true)
            }
            """,
            schema -> {
                var f = (no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField)
                    schema.field("Query", "films");
                assertThat(f.reason())
                    .contains("parameter 'fra'")
                    .contains("is not a GraphQL argument and not a context key");
            }),

        PLAIN_INPUT_ARG_FIELD_CONDITION_EMITTED(
            "@condition on a plain input field, classified per call-site → condition emitted at the matching "
                + "call site; R205 Path B rejects the mismatched call site rather than silently dropping it",
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
                // The Language call site cannot resolve 'film_id': the same definition is fine
                // on film and malformed on language, which is why the malformed-shape fact is
                // keyed by definition joined with the resolving table. The consumer classifies
                // (the authored method fires) and the diagnostic fails the build.
                assertThat(schema.field("Query", "languages")).isNotInstanceOf(UnclassifiedField.class);
                var minted = schema.diagnostics().stream()
                    .filter(d -> "FilmInput.filmId".equals(d.coordinate()))
                    .toList();
                assertThat(minted).hasSize(1);
                var un = (Rejection.AuthorError.UnknownName) minted.getFirst().rejection();
                assertThat(un.attempt()).isEqualTo("film_id");
                assertThat(un.message()).contains("@condition(override: false)", "table 'language'");
            }),

        // ===== Implicit column conditions for input types (resolved per consuming field) =====

        INPUT_IMPLICIT_CONDITION_EXPLICIT_OVERRIDE_SUPPRESSES_OWN(
            "input field with @condition(override:true) → explicit fires, implicit for THAT field suppressed, sibling gets implicit",
            """
            input FilmInput {
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

        INPUT_IMPLICIT_CONDITION_LOOKUP_KEY_SKIPPED(
            "@lookupKey arg on a plain input arg (R144) → no implicit BodyParam emitted for "
                + "any binding-bound input field; the lookup VALUES+JOIN path owns them",
            """
            input FilmInput {
              filmId: Int! @field(name: "film_id")
              title: String @field(name: "title")
            }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") title: String! @field(name: "title") }
            type Query { films(filter: FilmInput @lookupKey): [Film!]! }
            """,
            schema -> {
                // Arg-level @lookupKey on a plain input promotes this to QueryTableField,
                // resolving the input's fields against the consumer's return table (film).
                // Every admissible input field becomes a binding under the filter-by-default
                // rule; bound fields are consumed by the LookupRows emission and must not appear
                // as implicit BodyParams. With no plain (non-table) arg present, no
                // GeneratedConditionFilter is emitted.
                var f = (SqlGeneratingField) schema.field("Query", "films");
                long condFilters = f.filters().stream().filter(fi -> fi instanceof GeneratedConditionFilter).count();
                assertThat(condFilters).isZero();
            }),

        INPUT_IMPLICIT_CONDITION_NESTED_TWO_LEVEL(
            "plain input with NestingField wrapping an un-annotated ColumnBackedField → implicit BodyParam at leaf path",
            """
            input OuterInput {
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

    /**
     * A sort enum bound to {@code @orderBy} with one value carrying {@code @order} and one
     * carrying no ordering directive rejects with a build error naming the unannotated value. The
     * unannotated value would otherwise be silently skipped by {@code OrderByResolver}, generating
     * an empty ORDER BY (nondeterministic keyset pagination); the docs promise this
     * per-value build failure.
     */
    @Test
    void r453_partiallyAnnotatedSortEnumRejectsNamingUnannotatedValue() {
        var schema = build("""
            enum ActorOrderField { FIRST_NAME @order(index: "IDX_ACTOR_LAST_NAME") LAST_NAME }
            enum Direction { ASC DESC }
            input ActorOrder { sortField: ActorOrderField! direction: Direction! }
            type Actor @table(name: "actor") { name: String }
            type FilmActor @table(name: "film_actor") {
                actors(order: ActorOrder @orderBy): [Actor!]!
            }
            type Query { filmActor: FilmActor }
            """);
        var errors = new GraphitronSchemaValidator().validate(schema);
        assertThat(errors)
            .extracting(ValidationError::message)
            .as("expected a build error naming the unannotated sort-enum value")
            .anyMatch(m -> m.contains("ActorOrderField")
                && m.contains("LAST_NAME")
                && m.contains("no ordering directive"));
    }

    /**
     * Two or more unannotated values accumulate into a single rejection listing every
     * missing value, rather than failing fast on the first. Pins the accumulate-all behaviour.
     */
    @Test
    void r453_multipleUnannotatedValuesAccumulateIntoOneRejection() {
        var schema = build("""
            enum ActorOrderField { FIRST_NAME @order(index: "IDX_ACTOR_LAST_NAME") LAST_NAME LAST_UPDATE }
            enum Direction { ASC DESC }
            input ActorOrder { sortField: ActorOrderField! direction: Direction! }
            type Actor @table(name: "actor") { name: String }
            type FilmActor @table(name: "film_actor") {
                actors(order: ActorOrder @orderBy): [Actor!]!
            }
            type Query { filmActor: FilmActor }
            """);
        var errors = new GraphitronSchemaValidator().validate(schema);
        var missingOrderRejections = errors.stream()
            .map(ValidationError::rejection)
            .filter(r -> r instanceof no.sikt.graphitron.rewrite.model.Rejection.AuthorError.SortEnumMissingOrder)
            .map(r -> (no.sikt.graphitron.rewrite.model.Rejection.AuthorError.SortEnumMissingOrder) r)
            .toList();
        assertThat(missingOrderRejections)
            .as("both unannotated values must ride a single rejection, not fail-fast on the first")
            .hasSize(1);
        // The validator threads a "Field '<parent>.<field>': " prefix onto enumTypeName via
        // prefixedWith (the RecordBindingMultiProducer precedent); the typed missingValues list
        // stays clean for downstream tooling.
        assertThat(missingOrderRejections.get(0).enumTypeName()).contains("ActorOrderField");
        assertThat(missingOrderRejections.get(0).missingValues())
            .containsExactly("LAST_NAME", "LAST_UPDATE");
    }

    // ===== P4: InputType classification =====

    enum InputTypeCase implements ClassificationCase {
        BASIC_INPUT_TYPE(
            "input type with no @table → classified as InputType",
            """
            input FilmInput { title: String! releaseYear: Int }
            type Query { x: String leafReach1(in: FilmInput): String }
            """,
            schema -> assertThat(schema.type("FilmInput"))
                .isInstanceOf(InputType.class)),

        NO_CLASS(
            "input with no reflected producer binding → PojoInputType with null fqClassName",
            """
            input FilmInput { id: ID }
            type Query { x: String leafReach1(in: FilmInput): String }
            """,
            schema -> {
                var t = (PojoInputType) schema.type("FilmInput");
                assertThat(t.fqClassName()).isNull();
            }),

        POJO_CLASS(
            "input consumed by a @service param whose reflected type is a plain Java class → PojoInputType with fqClassName",
            """
            input FilmInput { id: ID }
            type Query {
                foo(in: FilmInput): String @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "consumeDummyRecord"})
            }
            """,
            schema -> {
                var t = (PojoInputType) schema.type("FilmInput");
                assertThat(t.fqClassName()).isEqualTo("no.sikt.graphitron.codereferences.dummyreferences.DummyRecord");
            }),

        JAVA_RECORD_CLASS(
            "input consumed by a @service param whose reflected type is a Java record → JavaRecordInputType with fqClassName",
            """
            input FilmInput { id: ID }
            type Query {
                foo(in: FilmInput): String @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "consumeTestRecordDto"})
            }
            """,
            schema -> {
                var t = (JavaRecordInputType) schema.type("FilmInput");
                assertThat(t.fqClassName()).isEqualTo("no.sikt.graphitron.codereferences.dummyreferences.TestRecordDto");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(JavaRecordInputType.class); }
        },

        JOOQ_TABLE_RECORD_CLASS(
            "input consumed by a @service param whose reflected type is a jOOQ TableRecord → JooqTableRecordInputType with fqClassName and resolved table",
            """
            input FilmInput { id: ID }
            type Query {
                foo(in: FilmInput): String @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "consumeFilmRecord"})
            }
            """,
            schema -> {
                var t = (JooqTableRecordInputType) schema.type("FilmInput");
                assertThat(t.fqClassName()).isEqualTo("no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord");
                assertThat(t.table()).isNotNull();
                assertThat(t.table().tableName()).isEqualTo("film");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(JooqTableRecordInputType.class); }
        };
        // The @table + @record directive-ignored warning is covered by
        // RecordDirectiveIgnoredWarningTest at the classifier level; this enum carries no
        // applied @record.

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

    // ===== Plain-input implicit-predicate symmetry & rejection =====

    /**
     * A plain-input field that resolves to a column with no {@code @condition} emits an
     * implicit {@code BodyParam.Eq} on the resolved column. Carries
     * {@code @ProjectionFor(PojoInputType.class)}: the projection
     * coverage the meta-test requires.
     */
    @Test
    @ProjectionFor(PojoInputType.class)
    void plainInput_resolvedColumnWithoutCondition_emitsImplicitBodyParam() {
        // One plain input reused by two fields returning different tables. Every non-@table input
        // is plain (PojoInputType), and each call site resolves the input's fields against its own
        // consumer's table: per-consumer resolution, not a whole-type verdict. film_id exists on
        // both film and inventory, so both call sites resolve it and neither demotes the input.
        var schema = build("""
            input PlainFilter { filmId: Int! @field(name: "film_id") }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") }
            type Query {
              films(filter: PlainFilter): [Film!]!
              inventory(filter: PlainFilter): [Inventory!]!
            }
            """);
        assertThat(schema.type("PlainFilter")).isInstanceOf(PojoInputType.class);
        // The films call site emits a single GeneratedConditionFilter carrying one
        // BodyParam.Eq on film.film_id with a NestedInputField extraction.
        var f = (QueryField.QueryTableField) schema.field("Query", "films");
        assertThat(f.filters()).hasSize(1);
        var gcf = (GeneratedConditionFilter) f.filters().get(0);
        assertThat(gcf.bodyParams()).hasSize(1);
        var bp = (BodyParam.Eq) gcf.bodyParams().get(0);
        assertThat(bp.name()).isEqualTo("filmId");
        assertThat(bp.column().sqlName()).isEqualTo("film_id");
        var nif = (CallSiteExtraction.NestedInputField) bp.extraction();
        assertThat(nif.outerArgName()).isEqualTo("filter");
        assertThat(nif.path()).containsExactly("filmId");
        // Per-consumer resolution: the inventory call site independently resolves the same input
        // field against the inventory table, emitting its own implicit predicate on film_id there.
        var inv = (QueryField.QueryTableField) schema.field("Query", "inventory");
        assertThat(inv.filters()).hasSize(1);
        var invGcf = (GeneratedConditionFilter) inv.filters().get(0);
        assertThat(invGcf.bodyParams()).hasSize(1);
        var invBp = (BodyParam.Eq) invGcf.bodyParams().get(0);
        assertThat(invBp.name()).isEqualTo("filmId");
        assertThat(invBp.column().sqlName()).isEqualTo("film_id");
    }

    /** Acceptance test #2 — explicit-method-plus-implicit composition on a plain input. */
    @Test
    void plainInput_explicitMethodAndBareSibling_emitsBothFilters() {
        // Every non-@table input is plain (PojoInputType); each call site resolves it against its
        // own consumer's table. film_id exists on both film and inventory; release_year only on
        // film, so the composition assertion uses the films call site (where both fields resolve).
        var schema = build("""
            input PlainFilter {
              filmId: Int! @field(name: "film_id")
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "inputColumnCondition"})
              releaseYear: Int @field(name: "release_year")
            }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") releaseYear: Int @field(name: "release_year") }
            type Inventory @table(name: "inventory") { inventoryId: Int! @field(name: "inventory_id") releaseYear: Int @field(name: "release_year") }
            type Query {
              films(filter: PlainFilter): [Film!]!
              inventoryItems(filter: PlainFilter): [Inventory!]!
            }
            """);
        assertThat(schema.type("PlainFilter")).isInstanceOf(PojoInputType.class);
        var f = (QueryField.QueryTableField) schema.field("Query", "films");
        // The annotated filmId field contributes an explicit ConditionFilter; the bare
        // releaseYear field contributes an implicit BodyParam on film.release_year.
        var gcf = f.filters().stream()
            .filter(GeneratedConditionFilter.class::isInstance)
            .map(GeneratedConditionFilter.class::cast)
            .findFirst().orElseThrow();
        assertThat(gcf.bodyParams()).hasSize(1);
        assertThat(gcf.bodyParams().get(0).name()).isEqualTo("releaseYear");
        var explicit = f.filters().stream()
            .filter(fi -> fi instanceof ConditionFilter && !(fi instanceof GeneratedConditionFilter))
            .map(ConditionFilter.class::cast)
            .findFirst().orElseThrow();
        assertThat(explicit.methodName()).isEqualTo("inputColumnCondition");
    }

    /** Acceptance test #3 — override:true on a plain-input field suppresses its implicit. */
    @Test
    void plainInput_overrideTrueOnFieldCondition_suppressesImplicitBodyParam() {
        var schema = build("""
            input PlainFilter {
              filmId: Int! @field(name: "film_id")
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "inputColumnCondition"}, override: true)
            }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { films(filter: PlainFilter): [Film!]! }
            """);
        var f = (QueryField.QueryTableField) schema.field("Query", "films");
        // Override suppresses the implicit predicate; only the explicit ConditionFilter survives.
        var hasGcf = f.filters().stream().anyMatch(GeneratedConditionFilter.class::isInstance);
        assertThat(hasGcf).isFalse();
        var explicit = f.filters().stream()
            .filter(ConditionFilter.class::isInstance)
            .map(ConditionFilter.class::cast)
            .findFirst().orElseThrow();
        assertThat(explicit.methodName()).isEqualTo("inputColumnCondition");
    }

    /**
     * Consumer-derived arg-level {@code @lookupKey} on a plain input: the
     * composite-PK lookup shape (a {@code FilmActorKey}-style {@code id: ID! @nodeId} input)
     * carries no directive of its own. The input's fields resolve against the consuming
     * field's return table ({@code film_actor}), the lookup binding set is built from them,
     * and the per-NodeType decode runs once at the arg layer with the record slots binding
     * positionally to the target PK columns ({@code LookupMapping} {@code DecodedRecord}).
     */
    @Test
    void plainInput_argLevelLookupKey_resolvesViaConsumerTable() {
        var schema = build("""
            type FilmActor implements Node @table(name: "film_actor") @node { id: ID! @nodeId }
            input PlainFilmActorKey { id: ID! @nodeId }
            type Query {
                filmActorByKey(key: PlainFilmActorKey @lookupKey): [FilmActor!]!
            }
            """);
        // No @table on PlainFilmActorKey: it is a plain input, resolved per-consumer.
        assertThat(schema.type("PlainFilmActorKey")).isInstanceOf(PojoInputType.class);
        var f = (no.sikt.graphitron.rewrite.model.QueryField.QueryTableField)
            schema.field("Query", "filmActorByKey");
        var mapping = (no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) keyedMappingOf(f.lookup());
        assertThat(mapping.args()).hasSize(1);
        var dr = (no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping.LookupArg.DecodedRecord)
            mapping.args().get(0);
        assertThat(dr.bindings()).hasSize(2);
        assertThat(dr.bindings().get(0).index()).isZero();
        assertThat(dr.bindings().get(1).index()).isOne();
    }

    /**
     * The validator-mirror obligation for the consumer-derived {@code @lookupKey} path: a plain
     * input whose {@code @lookupKey} field does not resolve to a column on the consuming field's
     * table is a classify-time rejection naming that table, never a silent no-op (the lookup is a
     * VALUES-join over exactly these columns, so an unbound carrier has no binding to emit).
     */
    @Test
    void plainInput_argLevelLookupKey_unresolvableAgainstConsumerTable_rejectsNamingTable() {
        var schema = build("""
            input PlainKey { code: String @field(name: "no_such_column") }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { filmByKey(key: PlainKey @lookupKey): [Film!]! }
            """);
        var uf = (UnclassifiedField) schema.field("Query", "filmByKey");
        assertThat(uf.reason())
            .contains("@lookupKey argument 'key'")
            .contains("'code'")
            .contains("table 'film'");
    }

    /**
     * The headline behavior of consumer-derived input resolution: one directiveless input
     * consumed by fields on two different tables resolves independently per consumer. Both
     * consumers classify when the column exists on both tables; there is no whole-type table
     * verdict shared between them.
     */
    @Test
    void plainInput_reusedAcrossConsumersOnDifferentTables_resolvesPerConsumer() {
        var schema = build("""
            input SharedFilter { lastUpdate: String @field(name: "last_update") }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Actor @table(name: "actor") { actorId: Int! @field(name: "actor_id") }
            type Query {
                films(filter: SharedFilter): [Film!]!
                actors(filter: SharedFilter): [Actor!]!
            }
            """);
        assertThat(schema.type("SharedFilter")).isInstanceOf(PojoInputType.class);
        assertThat(schema.field("Query", "films"))
            .as("last_update resolves on film for the films consumer")
            .isNotInstanceOf(UnclassifiedField.class);
        assertThat(schema.field("Query", "actors"))
            .as("the same input resolves on actor for the actors consumer")
            .isNotInstanceOf(UnclassifiedField.class);
    }

    /**
     * The negative half of per-consumer resolution, and the regression home for the
     * cross-table-reuse flip: a field of the shared input that resolves on one consumer's
     * table but not the other's rejects only the non-resolving consumer, naming that
     * consumer's table (never silently resolving against some type-level table).
     */
    @Test
    void plainInput_reused_columnMissingOnOneConsumer_rejectsOnlyThatConsumerNamingItsTable() {
        var schema = build("""
            input SharedFilter { title: String @field(name: "title") }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Actor @table(name: "actor") { actorId: Int! @field(name: "actor_id") }
            type Query {
                films(filter: SharedFilter): [Film!]!
                actors(filter: SharedFilter): [Actor!]!
            }
            """);
        assertThat(schema.field("Query", "films"))
            .as("title exists on film; that consumer classifies")
            .isNotInstanceOf(UnclassifiedField.class);
        var uf = (UnclassifiedField) schema.field("Query", "actors");
        assertThat(uf.reason())
            .as("the actor consumer keeps the consequence")
            .contains("'title'");
        // The cause is the use-keyed cascade verdict, minted at the input field with the
        // occurrence path and the resolving table, so the actor consumer's table is named
        // there and film's never appears: exactly one occurrence fact, the actors one.
        var minted = schema.diagnostics().stream()
            .filter(d -> "SharedFilter.title".equals(d.coordinate()))
            .toList();
        assertThat(minted).hasSize(1);
        assertThat(minted.getFirst().message())
            .as("the cause names the non-resolving consumer's table and occurrence, not film")
            .contains("Query.actors(filter)/title")
            .contains("table 'actor'")
            .doesNotContain("film");
    }

    /**
     * Acceptance test #4, reshaped by the malformed-shape mint: an unresolvable column under
     * {@code @condition(override: false)} is the malformed shape, minted into the build
     * diagnostics at the definition with the resolving table in the key and the typed
     * {@link Rejection.AuthorError.UnknownName} payload (attempted column, candidates). The
     * consumer classifies: the authored method still fires (every {@code @condition} produces
     * SQL) while the diagnostic fails the build.
     */
    @Test
    void plainInput_unresolvedFieldWithCondition_mintsMalformedShapeWithUnknownName() {
        var schema = build("""
            input PlainFilter {
              noSuch: String @field(name: "no_such_column")
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "inputColumnCondition"})
            }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { films(filter: PlainFilter): [Film!]! }
            """);
        assertThat(schema.field("Query", "films")).isNotInstanceOf(UnclassifiedField.class);
        var minted = schema.diagnostics().stream()
            .filter(d -> "PlainFilter.noSuch".equals(d.coordinate()))
            .toList();
        assertThat(minted).hasSize(1);
        assertThat(minted.getFirst().rejection()).isInstanceOf(Rejection.AuthorError.UnknownName.class);
        var un = (Rejection.AuthorError.UnknownName) minted.getFirst().rejection();
        assertThat(un.attempt()).isEqualTo("no_such_column");
        assertThat(un.candidates()).isNotEmpty();
        assertThat(un.message()).contains("@condition(override: false)", "table 'film'", "did you mean:");
    }

    /**
     * Acceptance test #5 — Path B pin: a bare Unresolved (no {@code @condition}) on a plain
     * input still rejects loudly. The cause is the use-keyed cascade verdict in the build
     * diagnostics, carrying the typed {@link Rejection.AuthorError.UnknownName} shape and the
     * occurrence path; the consumer keeps one consequence rejection. Future contributors cannot
     * quietly reintroduce per-field skip without breaking this test by name.
     */
    @Test
    void plainInput_bareUnresolvedField_rejectsAsUnclassifiedFieldWithUnknownName() {
        var schema = build("""
            input PlainFilter { noSuch: String @field(name: "no_such_column") }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { films(filter: PlainFilter): [Film!]! }
            """);
        var uf = (UnclassifiedField) schema.field("Query", "films");
        assertThat(uf.reason()).contains("plain input type 'PlainFilter'", "input field 'noSuch'");
        var minted = schema.diagnostics().stream()
            .filter(d -> "PlainFilter.noSuch".equals(d.coordinate()))
            .toList();
        assertThat(minted).hasSize(1);
        assertThat(minted.getFirst().rejection()).isInstanceOf(Rejection.AuthorError.UnknownName.class);
        var un = (Rejection.AuthorError.UnknownName) minted.getFirst().rejection();
        assertThat(un.attempt()).isEqualTo("no_such_column");
        assertThat(un.message()).contains("input field 'noSuch'", "Query.films(filter)/noSuch");
    }

    /**
     * {@code @condition(override: true)} on a plain-input field whose name does not
     * match any column on the resolving table classifies as
     * {@link InputField.ConditionOwnedField}: the method owns the predicate entirely, so
     * whether a column also resolves is not the carrier's fact, and requiring one would reject
     * schemas where the condition method owns the WHERE clause. The projected filter list
     * carries only the explicit {@link ConditionFilter}, no {@link GeneratedConditionFilter}
     * / {@link BodyParam}.
     */
    @Test
    @ProjectionFor(InputField.ConditionOwnedField.class)
    void plainInput_overrideTrueWithoutMatchingColumn_classifiesAsConditionOwnedField() {
        // Mirrors the opptak-subgraph SakFilterV2Input.sakskode shape: bare String field with
        // @condition(override: true) and no @field(name:); the resolving table has no column
        // matching the field name.
        var schema = build("""
            input PlainFilter {
              sakskode: String
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "sakskodeCondition"}, override: true)
            }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { films(filter: PlainFilter): [Film!]! }
            """);
        assertThat(schema.type("PlainFilter")).isInstanceOf(PojoInputType.class);
        var f = (QueryField.QueryTableField) schema.field("Query", "films");
        // No implicit BodyParam — the field has no column binding; override:true means there's
        // nothing to suppress either, but the structural result is the same: only the explicit
        // ConditionFilter is emitted.
        var hasGcf = f.filters().stream().anyMatch(GeneratedConditionFilter.class::isInstance);
        assertThat(hasGcf).isFalse();
        var explicit = f.filters().stream()
            .filter(ConditionFilter.class::isInstance)
            .map(ConditionFilter.class::cast)
            .findFirst().orElseThrow();
        assertThat(explicit.methodName()).isEqualTo("sakskodeCondition");
    }

    /**
     * Boundary test: the override flag is the gate. {@code @condition(override: false)}
     * (or default) on a plain-input field with no matching column is the malformed shape;
     * this test pins the override:false↔override:true behaviour boundary by name so a future
     * contributor cannot quietly relax the override:false case alongside override:true.
     */
    @Test
    void plainInput_overrideFalseWithoutMatchingColumn_stillFailsTheBuild() {
        var schema = build("""
            input PlainFilter {
              sakskode: String
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "sakskodeCondition"})
            }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { films(filter: PlainFilter): [Film!]! }
            """);
        // override:false (default) means the implicit predicate is required to compose with
        // the explicit method; without a resolvable column there is no implicit predicate to
        // emit, so the malformed-shape fact fails the build from the diagnostics channel. The
        // consumer classifies (the authored method still fires), unlike the override:true
        // sibling above only in that the diagnostic exists.
        assertThat(schema.field("Query", "films")).isNotInstanceOf(UnclassifiedField.class);
        var minted = schema.diagnostics().stream()
            .filter(d -> "PlainFilter.sakskode".equals(d.coordinate()))
            .toList();
        assertThat(minted).hasSize(1);
        var un = (Rejection.AuthorError.UnknownName) minted.getFirst().rejection();
        assertThat(un.attempt()).isEqualTo("sakskode");
        assertThat(un.message()).contains("@condition(override: false)");
    }

    /**
     * {@code @condition(override: true)} with a broken condition method still rejects;
     * the override flag only relaxes the column-resolution requirement, not the condition
     * reflection requirement.
     *
     * <p>Under override:true the column is unused by construction, so the "no column 'sakskode'
     * found" arm must not surface alongside the condition error. The actionable diagnostic is the
     * condition failure, minted at the input field that carries the directive; the consuming field
     * states only the consequence.
     */
    @Test
    void plainInput_overrideTrueWithBrokenCondition_rejectsAsUnclassifiedField() {
        var schema = build("""
            input PlainFilter {
              sakskode: String
                @condition(condition: {className: "no.sikt.graphitron.rewrite.NoSuchClass", method: "nope"}, override: true)
            }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { films(filter: PlainFilter): [Film!]! }
            """);
        var uf = (UnclassifiedField) schema.field("Query", "films");
        assertThat(uf.reason()).contains("plain input type 'PlainFilter'", "1 input field could not be resolved");
        var diagnostics = TestSchemaHelper.diagnosticMessages(schema);
        assertThat(diagnostics).contains("PlainFilter.sakskode: ");
        assertThat(diagnostics).doesNotContain("no column 'sakskode' found");
    }

    /**
     * Acceptance test #6 — {@code @condition} reflection failure on a plain-input field
     * folds into the same sealed channel: {@link UnclassifiedField} on the surrounding query.
     */
    @Test
    void plainInput_conditionReflectionFailure_rejectsAsUnclassifiedField() {
        var schema = build("""
            input PlainFilter {
              filmId: Int! @field(name: "film_id")
                @condition(condition: {className: "no.sikt.graphitron.rewrite.NoSuchClass", method: "nope"})
            }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { films(filter: PlainFilter): [Film!]! }
            """);
        var uf = (UnclassifiedField) schema.field("Query", "films");
        assertThat(uf.reason()).contains("plain input type 'PlainFilter'", "1 input field could not be resolved");
        assertThat(TestSchemaHelper.diagnosticMessages(schema))
            .contains("PlainFilter.filmId: ")
            .contains("could not be loaded");
    }

    // ===== UnboundField cascade acceptance tests =====

    /**
     * #1 — Plain input + arg-level {@code @condition(override: true)} + non-binding field
     * is admitted: the classifier emits {@code UnboundField} (the field itself carries no
     * {@code @condition}, so it is the genuine-miss carrier) and the consumer's
     * {@code enclosingOverride = true} admits without an implicit predicate.
     */
    @Test
    @ProjectionFor(InputField.UnboundField.class)
    void r215_plainInputArgLevelOverrideAdmitsNonBindingField() {
        var schema = build("""
            input PlainFilter { foo: String }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query {
              films(filter: PlainFilter
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "lifterFieldCondition"}, override: true)
              ): [Film!]!
            }
            """);
        // Schema must build cleanly; the consumer admits PlainFilter.foo as UnboundField.
        var f = schema.field("Query", "films");
        assertThat(f).isInstanceOf(QueryField.QueryTableField.class);
        var qtf = (QueryField.QueryTableField) f;
        // The arg-level @condition emits one explicit ConditionFilter; no implicit BodyParam fires
        // for foo because the cascade suppresses it.
        var hasGcf = qtf.filters().stream().anyMatch(GeneratedConditionFilter.class::isInstance);
        assertThat(hasGcf).isFalse();
        assertThat(qtf.filters().stream().filter(ConditionFilter.class::isInstance).count()).isEqualTo(1L);
    }

    /**
     * #3 — plain input + bare non-binding field (no {@code @field}, no {@code @condition})
     * consumed by a non-override arg still rejects: the use-keyed cascade verdict mints into
     * the build diagnostics at the input field with the field name as the attempted column
     * and the occurrence path in the prose, and the consumer keeps one consequence rejection.
     */
    @Test
    void r215_plainInputBareNonBindingFieldRejectsAtConsumer() {
        var schema = build("""
            input FilmInput { foo: String }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { films(filter: FilmInput): [Film!]! }
            """);
        // walkInputFieldConditions sees UnboundField with condition.empty() and
        // enclosingOverride == false → mints the cascade verdict, consumer keeps the consequence.
        var uf = (UnclassifiedField) schema.field("Query", "films");
        assertThat(uf.reason()).contains("FilmInput", "input field 'foo'");
        var minted = schema.diagnostics().stream()
            .filter(d -> "FilmInput.foo".equals(d.coordinate()))
            .toList();
        assertThat(minted).hasSize(1);
        var un = (Rejection.AuthorError.UnknownName) minted.getFirst().rejection();
        assertThat(un.attempt()).isEqualTo("foo");
        assertThat(un.message()).contains("FilmInput", "input field 'foo'", "Query.films(filter)/foo");
    }

    /**
     * #6 — {@code @condition} on a mutation input field rejects. The mutation classifier
     * catches the {@code @condition(override: false)} arm at SDL walk time; the resulting
     * UnclassifiedField surfaces in the validator's output as a ValidationError. The
     * {@code @condition(override: true)} arm admits on UPDATE / DELETE (see test 7).
     */
    @Test
    void r215_validatorRejectsConditionOverrideFalseOnMutationInputField() {
        var schema = build("""
            input FilmUpdate {
              filmId: Int! @field(name: "film_id")
              title: String
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "inputColumnCondition"})
            }
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId filmId: Int! @field(name: "film_id") }
            type Mutation {
              updateFilm(in: FilmUpdate!): ID @mutation(typeName: UPDATE, table: "film")
            }
            type Query { x: String }
            """);
        // The title column carries a @condition the walker cannot emit, so it rejects with
        // UpdateRowsError.UnsupportedInputFieldShape naming the field.
        var uf = (UnclassifiedField) schema.field("Mutation", "updateFilm");
        assertThat(uf.reason()).contains("title");
        assertThat(uf.reason()).contains("@condition", "not supported");
    }

    /**
     * {@code @mutation(typeName: UPDATE) + @condition(override: true)} on
     * a non-key input field is rejected by {@code UpdateRowsWalker}: the shape has no emit-side
     * wiring, so admitting it would let the author's filter silently never run. The walker makes
     * the deferral honest with a typed {@code UpdateRowsError.OverrideConditionNotSupported}
     * rather than dropping the filter at emit.
     */
    @Test
    void r246_mutationUpdateConditionOverrideTrueOnNonPkFieldRejects() {
        var schema = build("""
            input FilmUpdate {
              filmId: Int! @field(name: "film_id")
              title: String
              syntheticName: String
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "syntheticNameCondition"}, override: true)
            }
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId filmId: Int! @field(name: "film_id") }
            type Mutation {
              updateFilm(in: FilmUpdate!): ID @mutation(typeName: UPDATE, table: "film")
            }
            type Query { x: String }
            """);
        var f = (UnclassifiedField) schema.field("Mutation", "updateFilm");
        assertThat(f.rejection()).isInstanceOf(
            no.sikt.graphitron.rewrite.model.UpdateRowsError.OverrideConditionNotSupported.class);
        assertThat(((no.sikt.graphitron.rewrite.model.UpdateRowsError.OverrideConditionNotSupported) f.rejection())
            .fieldName()).isEqualTo("syntheticName");
    }

    /**
     * The {@code @condition(override: true)} deferral on a non-key input field falls out for
     * the payload-returning UPDATE shape exactly as it does for the direct-return shape: both
     * forks run the same {@code UpdateRowsWalker}, so {@code classifyUpdatePayloadField} surfaces the
     * same typed {@code UpdateRowsError.OverrideConditionNotSupported}. This test pins that the
     * shared walker rejection is reached on the payload path, not silently dropped.
     */
    @Test
    void r258_mutationUpdatePayloadConditionOverrideTrueOnNonKeyFieldRejects() {
        var schema = build("""
            input FilmUpdate {
              filmId: Int! @field(name: "film_id")
              title: String
              syntheticName: String
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "syntheticNameCondition"}, override: true)
            }
            type Film @table(name: "film") { title: String }
            type FilmPayload { film: Film }
            type Mutation {
              updateFilmPayload(in: FilmUpdate!): FilmPayload @mutation(typeName: UPDATE)
            }
            type Query { x: String }
            """);
        var f = (UnclassifiedField) schema.field("Mutation", "updateFilmPayload");
        assertThat(f.rejection()).isInstanceOf(
            no.sikt.graphitron.rewrite.model.UpdateRowsError.OverrideConditionNotSupported.class);
        assertThat(((no.sikt.graphitron.rewrite.model.UpdateRowsError.OverrideConditionNotSupported) f.rejection())
            .fieldName()).isEqualTo("syntheticName");
    }

    /**
     * DELETE analogue of the override-condition deferral — {@code @mutation(typeName:
     * DELETE) + @condition(override: true)} on an input field is rejected by {@code DeleteRowsWalker}
     * with a typed {@code DeleteRowsError.OverrideConditionNotSupported}, rather than silently
     * dropping the filter at emit. The classifier routes the DELETE through the walker (not
     * resolveInput), so the rejection is the walker's typed arm.
     */
    @Test
    void r266_mutationDeleteConditionOverrideTrueOnInputFieldRejects() {
        var schema = build("""
            input FilmDelete {
              filmId: Int! @field(name: "film_id")
              syntheticName: String
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "syntheticNameCondition"}, override: true)
            }
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId filmId: Int! @field(name: "film_id") }
            type Mutation {
              deleteFilm(in: FilmDelete!): ID @mutation(typeName: DELETE, table: "film")
            }
            type Query { x: String }
            """);
        var f = (UnclassifiedField) schema.field("Mutation", "deleteFilm");
        assertThat(f.rejection()).isInstanceOf(
            no.sikt.graphitron.rewrite.model.DeleteRowsError.OverrideConditionNotSupported.class);
        assertThat(((no.sikt.graphitron.rewrite.model.DeleteRowsError.OverrideConditionNotSupported) f.rejection())
            .fieldName()).isEqualTo("syntheticName");
    }

    /**
     * Sibling — a non-key input field carrying a non-override {@code @condition} (a shape the
     * walker cannot emit) rejects the payload-returning UPDATE with
     * {@code UpdateRowsError.UnsupportedInputFieldShape}, the same way the direct-return shape does
     * (see {@code r215_validatorRejectsConditionOverrideFalseOnMutationInputField}). Confirms the
     * structural-payload scan admits first and the shared walker owns the field-shape rejection.
     */
    @Test
    void r258_mutationUpdatePayloadConditionNonOverrideOnNonKeyFieldRejects() {
        var schema = build("""
            input FilmUpdate {
              filmId: Int! @field(name: "film_id")
              title: String
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "inputColumnCondition"})
            }
            type Film @table(name: "film") { title: String }
            type FilmPayload { film: Film }
            type Mutation {
              updateFilmPayload(in: FilmUpdate!): FilmPayload @mutation(typeName: UPDATE)
            }
            type Query { x: String }
            """);
        var f = (UnclassifiedField) schema.field("Mutation", "updateFilmPayload");
        assertThat(f.rejection()).isInstanceOf(
            no.sikt.graphitron.rewrite.model.UpdateRowsError.UnsupportedInputFieldShape.class);
        assertThat(f.reason()).contains("title");
    }

    /**
     * #8 — Same shape on a INSERT mutation rejects. INSERT has no WHERE clause for the
     * override condition to bind into.
     */
    @Test
    void r215_mutationInsertConditionOverrideTrueRejects() {
        var schema = build("""
            input FilmInsert {
              title: String
              syntheticName: String
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "syntheticNameCondition"}, override: true)
            }
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId filmId: Int! @field(name: "film_id") }
            type Mutation {
              insertFilm(in: FilmInsert!): ID @mutation(typeName: INSERT, table: "film")
            }
            type Query { x: String }
            """);
        var uf = (UnclassifiedField) schema.field("Mutation", "insertFilm");
        assertThat(uf.reason()).contains("syntheticName");
        assertThat(uf.reason()).contains("INSERT");
    }

    /**
     * #11 — Inner explicit {@code @condition} on an {@code UnboundField} fires under an
     * outer {@code @condition(override: true)} cascade. Per the cascade-divergence section of
     * {@code docs/manual/how-to/migrating-from-legacy.adoc}, {@code override: true} suppresses
     * only the rewrite's implicit column predicate; every {@code @condition} the author writes
     * still produces SQL. The consumer arm mirrors the {@link
     * no.sikt.graphitron.rewrite.model.InputField.ColumnBackedField} arm structure: it emits the
     * explicit {@code @condition} unconditionally and decides rejection separately, so the inner
     * condition is never silently dropped when the cascade resolves the outer override.
     *
     * <p>The shape here is structurally malformed ({@code override:false} on a no-column field);
     * no validator walk over {@code PlainInputArg.fields()} rejects UnboundFields, so the schema
     * builds and the consumer-arm emit-anyway behavior honors the doc contract at runtime.
     */
    @Test
    void r215_innerExplicitConditionFiresOnUnboundFieldUnderOverrideCascade() {
        var schema = build("""
            input PlainFilter {
              syntheticName: String
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "syntheticNameCondition"}, override: false)
            }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query {
              films(filter: PlainFilter
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "lifterFieldCondition"}, override: true)
              ): [Film!]!
            }
            """);
        var f = schema.field("Query", "films");
        assertThat(f).isInstanceOf(QueryField.QueryTableField.class);
        var qtf = (QueryField.QueryTableField) f;
        // Two explicit ConditionFilters: outer arg-level @condition(override: true) and inner
        // field-level @condition. No GeneratedConditionFilter (no implicit predicate fires: the
        // outer override suppresses it, and the inner field has no column anyway).
        assertThat(qtf.filters().stream().filter(ConditionFilter.class::isInstance).count())
            .as("outer arg-level @condition plus inner field-level @condition both emit ConditionFilters")
            .isEqualTo(2L);
        assertThat(qtf.filters().stream().anyMatch(GeneratedConditionFilter.class::isInstance))
            .as("no implicit body params on UnboundField under cascade")
            .isFalse();
    }

    /**
     * #10 — Nested plain inputs propagate the cascade through {@code NestingField}. A
     * plain input nested under an arg-level {@code @condition(override: true)} admits a
     * non-binding inner field; the cascade resolves it via the consumer-side walker's
     * {@code nestOverride} composition.
     */
    @Test
    void r215_nestedPlainInputPropagatesCascade() {
        var schema = build("""
            input Inner { bar: String }
            input Outer { details: Inner }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query {
              films(filter: Outer
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "lifterFieldCondition"}, override: true)
              ): [Film!]!
            }
            """);
        var f = schema.field("Query", "films");
        assertThat(f).isInstanceOf(QueryField.QueryTableField.class);
    }

    // ===== Input-type classification and per-consumer input-field resolution =====

    // ===== InputField.UnboundField =====

    /**
     * The enum truth-table home of the {@code UnboundField} verdict (input field with no column
     * binding). The two admission gates are the field's own {@code @condition(override: true)}
     * and the enclosing-consumer override cascade; the sibling {@code @Test} methods above pin
     * the boundary and projection details (one carries the {@code @ProjectionFor}).
     */
    enum UnboundFieldCase implements ClassificationCase {
        OVERRIDE_TRUE_WITHOUT_MATCHING_COLUMN(
            "plain-input field with @condition(override: true) and no matching column on the "
                + "resolving table → UnboundField; the condition method owns the predicate, so "
                + "only the explicit ConditionFilter is emitted",
            """
            input PlainFilter {
              sakskode: String
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "sakskodeCondition"}, override: true)
            }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { films(filter: PlainFilter): [Film!]! }
            """,
            schema -> {
                var f = (QueryField.QueryTableField) schema.field("Query", "films");
                assertThat(f.filters().stream().anyMatch(GeneratedConditionFilter.class::isInstance))
                    .as("no implicit predicate for an unbound carrier")
                    .isFalse();
                var explicit = f.filters().stream()
                    .filter(ConditionFilter.class::isInstance)
                    .map(ConditionFilter.class::cast)
                    .findFirst().orElseThrow();
                assertThat(explicit.methodName()).isEqualTo("sakskodeCondition");
            }),

        CASCADE_ADMITTED_BARE_FIELD(
            "bare non-binding plain-input field admitted by the consumer's arg-level "
                + "@condition(override: true) cascade → UnboundField with condition.empty(); the "
                + "arg-level condition is the only filter",
            """
            input PlainFilter { foo: String }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query {
              films(filter: PlainFilter
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "lifterFieldCondition"}, override: true)
              ): [Film!]!
            }
            """,
            schema -> {
                var f = (QueryField.QueryTableField) schema.field("Query", "films");
                assertThat(f.filters().stream().anyMatch(GeneratedConditionFilter.class::isInstance))
                    .isFalse();
                assertThat(f.filters().stream().filter(ConditionFilter.class::isInstance).count())
                    .isEqualTo(1L);
            });

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        UnboundFieldCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() { return Set.of(InputField.UnboundField.class); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(UnboundFieldCase.class)
    void unboundFieldClassification(UnboundFieldCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    // ===== Composite @nodeId input carriers (InputField.ColumnBackedField / ColumnBackedReferenceField, arity > 1) =====

    /**
     * Composite-key input carriers on the default catalog: film_actor carries a two-column
     * primary key with synthesised node metadata (__NODE_TYPE_ID "FilmActor"), and
     * film_actor_note's composite FK targets it. Arity is a column count on the carrier leaf,
     * not a leaf dimension; these cases pin the arity > 1 flavour of the same leaves the
     * arity-1 cases land on.
     */
    enum CompositeNodeIdInputCase implements ClassificationCase {
        SAME_TABLE_COMPOSITE_NODE_ID_FILTER(
            "input `id: ID! @nodeId(typeName: \"FilmActor\")` consumed against FilmActor's own "
                + "composite-PK table → composite ColumnBackedField projecting a RowEq over "
                + "(actor_id, film_id) with the NodeIdDecodeKeys extraction",
            """
            type FilmActor implements Node @table(name: "film_actor") @node { id: ID! @nodeId }
            input FilmActorSelector { id: ID! @nodeId(typeName: "FilmActor") }
            type Query { filmActors(in: FilmActorSelector): [FilmActor!] }
            """,
            schema -> {
                var bp = compositeInputBodyParam(schema, "filmActors", BodyParam.RowEq.class);
                assertThat(bp.columns()).extracting(ColumnRef::sqlName)
                    .containsExactly("actor_id", "film_id");
                var leaf = ((CallSiteExtraction.NestedInputField) bp.extraction()).leaf();
                assertThat(((CallSiteExtraction.NodeIdDecodeKeys) leaf).decodeMethod().methodName())
                    .isEqualTo("decodeFilmActor");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(InputField.ColumnBackedField.class); }
        },

        FK_TARGET_COMPOSITE_NODE_ID_REFERENCE(
            "input `filmActor: ID! @nodeId(typeName: \"FilmActor\")` consumed on film_actor_note, "
                + "whose composite FK mirrors FilmActor's key columns → composite "
                + "ColumnBackedReferenceField whose lifted source columns are the note's own FK "
                + "pair (the arity > 1 FK-target case)",
            """
            type FilmActor implements Node @table(name: "film_actor") @node { id: ID! @nodeId }
            type FilmActorNote @table(name: "film_actor_note") { note: String @field(name: "note_txt") }
            input NoteFilter { filmActor: ID! @nodeId(typeName: "FilmActor") }
            type Query { filmActorNotes(in: NoteFilter): [FilmActorNote!] }
            """,
            schema -> {
                var bp = compositeInputBodyParam(schema, "filmActorNotes", BodyParam.RowEq.class);
                assertThat(bp.columns()).extracting(ColumnRef::sqlName)
                    .containsExactly("actor_id", "film_id");
                var leaf = ((CallSiteExtraction.NestedInputField) bp.extraction()).leaf();
                assertThat(leaf).isInstanceOf(CallSiteExtraction.NodeIdDecodeKeys.class);
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(InputField.ColumnBackedReferenceField.class); }
        };

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        CompositeNodeIdInputCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() { return Set.of(); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    private static <T extends BodyParam> T compositeInputBodyParam(
            GraphitronSchema schema, String queryFieldName, Class<T> shape) {
        var f = (QueryField.QueryTableField) schema.field("Query", queryFieldName);
        var gcf = (GeneratedConditionFilter) f.filters().stream()
            .filter(GeneratedConditionFilter.class::isInstance)
            .findFirst().orElseThrow();
        return gcf.bodyParams().stream()
            .filter(shape::isInstance).map(shape::cast).findFirst().orElseThrow();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(CompositeNodeIdInputCase.class)
    void compositeNodeIdInputClassification(CompositeNodeIdInputCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    enum InputFieldResolutionCase implements ClassificationCase {
        TABLE_ON_INPUT_IGNORED(
            "input type with @table → the same plain verdict as without it (the directive is "
                + "deprecated and ignored on inputs; fields resolve against each consuming field's "
                + "table). The deprecation surfaces as a build warning, not a verdict",
            """
            input CustomerInput @table(name: "customer") { customerId: Int! @field(name: "customer_id") }
            type Query { x: String leafReach1(in: CustomerInput): String }
            """,
            schema -> {
                assertThat(schema.type("CustomerInput")).isNotInstanceOf(UnclassifiedType.class);
                assertThat(schema.warnings())
                    .anyMatch(w -> w.message().contains("CustomerInput")
                        && w.message().contains("was ignored"));
            }),

        PLAIN_INPUT_ON_LOOKUP_FIELD_STAYS_PLAIN(
            "input type without @table used on a lookup field → stays a plain PojoInputType; the "
                + "arg-level @lookupKey resolves the input's fields against the consumer's table "
                + "(customer) at the call site rather than promoting the type",
            """
            input CustomerInput { customerId: Int! @field(name: "customer_id") }
            type Customer @table(name: "customer") { customerId: Int! @field(name: "customer_id") }
            type Query { customer(input: CustomerInput! @lookupKey): Customer }
            """,
            schema -> assertThat(schema.type("CustomerInput")).isInstanceOf(PojoInputType.class)),

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
            "@reference on an input field → resolved against the consumer's table; the implicit "
                + "predicate surfaces as a RemoteColumnPredicate with the FK join path and the "
                + "terminal column",
            """
            input FilmInput {
              filmId: Int! @field(name: "film_id")
              languageName: String @field(name: "name") @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { films(in: FilmInput): [Film!]! }
            """,
            schema -> {
                var qf = (QueryField.QueryTableField) schema.field("Query", "films");
                var gcf = (GeneratedConditionFilter) qf.filters().get(0);
                assertThat(gcf.bodyParams()).hasSize(2);
                var remote = gcf.bodyParams().stream()
                    .filter(BodyParam.RemoteColumnPredicate.class::isInstance)
                    .map(BodyParam.RemoteColumnPredicate.class::cast)
                    .findFirst().orElseThrow();
                assertThat(remote.name()).isEqualTo("languageName");
                assertThat(remote.joinPath()).hasSize(1);
                assertThat(remote.joinPath().get(0)).matches(TestFixtures::isFkHop, "FK-derived hop");
                assertThat(TestFixtures.fkHop(remote.joinPath().get(0)).targetTable().tableName())
                    .isEqualToIgnoringCase("language");
                assertThat(((BodyParam.Eq) remote.inner()).column().javaName()).isEqualTo("NAME");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(InputField.ColumnBackedReferenceField.class); }
        },

        COLUMN_REFERENCE_FIELD_UNKNOWN_FK(
            "@reference on an input field with unknown FK → the consuming field rejects as "
                + "UnclassifiedField naming the missing key",
            """
            input FilmInput {
              filmId: Int! @field(name: "film_id")
              languageName: String @field(name: "name") @reference(path: [{key: "no_such_fkey"}])
            }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { films(in: FilmInput): [Film!]! }
            """,
            schema -> {
                var uf = (UnclassifiedField) schema.field("Query", "films");
                assertThat(uf.reason()).contains("1 input field could not be resolved");
                // The cause is reported at the input field carrying it, not on the consumer.
                assertThat(TestSchemaHelper.diagnosticMessages(schema)).contains("no_such_fkey");
            }),

        NESTED_INPUT_FIELD_UNKNOWN_COLUMN_REJECTS_AT_CONSUMER(
            "nested plain input with an unresolvable column → the use-keyed cascade verdict "
                + "mints at the nested field with the attempted column and the occurrence path; "
                + "the non-override consumer keeps the consequence (R215 §3 defers "
                + "column-coverage to consumption)",
            """
            input BadInput { noSuch: String @field(name: "no_such_column") }
            input FilmInput {
              filmId: Int! @field(name: "film_id")
              details: BadInput!
            }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { films(in: FilmInput): [Film!]! }
            """,
            schema -> {
                var uf = (UnclassifiedField) schema.field("Query", "films");
                assertThat(uf.reason()).contains("input field 'noSuch'");
                var minted = schema.diagnostics().stream()
                    .filter(d -> "BadInput.noSuch".equals(d.coordinate()))
                    .toList();
                assertThat(minted).hasSize(1);
                var un = (Rejection.AuthorError.UnknownName) minted.getFirst().rejection();
                assertThat(un.attempt()).isEqualTo("no_such_column");
                assertThat(un.message()).contains("input field 'noSuch'", "Query.films(in)/details/noSuch");
            }),

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
            "@condition without override on the argument → the input stays a plain PojoInputType; "
                + "there is no whole-type routing gate, and column resolution runs per call site "
                + "against the consumer's table",
            """
            input FilterInput { filmId: Int! @field(name: "film_id") }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query {
              films(filter: FilterInput @condition(condition: {className: "C", method: "m"})): [Film]
            }
            """,
            schema -> assertThat(schema.type("FilterInput")).isInstanceOf(PojoInputType.class)),

        CONDITION_OWNED_FIELD(
            "@condition(override: true) on a plain input field → ConditionOwnedField: the "
                + "method owns the predicate entirely, only the explicit ConditionFilter is "
                + "emitted, and no implicit BodyParam exists to suppress",
            """
            input FilterInput {
              sakskode: String
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "sakskodeCondition"}, override: true)
            }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { films(filter: FilterInput): [Film!]! }
            """,
            schema -> {
                var qf = (QueryField.QueryTableField) schema.field("Query", "films");
                assertThat(qf.filters().stream().filter(ConditionFilter.class::isInstance).count()).isEqualTo(1L);
                assertThat(qf.filters().stream().anyMatch(GeneratedConditionFilter.class::isInstance)).isFalse();
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(InputField.ConditionOwnedField.class); }
        },

        // ===== Phase 4: @condition on INPUT_FIELD_DEFINITION =====

        COLUMN_REFERENCE_FIELD_WITH_CONDITION(
            "@condition on a @reference input field — the consumer emits an FkTargetConditionFilter "
                + "wrapping the condition with the FK correlation to the target table (the "
                + "condition method expects the FK-target table, not the input's own)",
            """
            input FilmInput {
              filmId: Int! @field(name: "film_id")
              languageName: String @field(name: "name") @reference(path: [{key: "film_language_id_fkey"}])
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "inputRefCondition"})
            }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") }
            type Query { films(in: FilmInput): [Film!]! }
            """,
            schema -> {
                var qf = (QueryField.QueryTableField) schema.field("Query", "films");
                var fkCond = qf.filters().stream()
                    .filter(no.sikt.graphitron.rewrite.model.FkTargetConditionFilter.class::isInstance)
                    .map(no.sikt.graphitron.rewrite.model.FkTargetConditionFilter.class::cast)
                    .findFirst().orElseThrow();
                assertThat(fkCond.methodName()).isEqualTo("inputRefCondition");
                assertThat(fkCond.targetTable().tableName()).isEqualToIgnoringCase("language");
                assertThat(fkCond.joinPath()).hasSize(1);
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(InputField.ColumnBackedReferenceField.class); }
        },

        NESTING_FIELD_WITH_CONDITION(
            "@condition on a NestingField — the consumer emits the wrapper's ConditionFilter, and "
                + "the nested leaves still contribute their implicit predicates (non-override)",
            """
            input TitleInput { title: String @field(name: "title") }
            input FilmInput {
              filmId: Int! @field(name: "film_id")
              details: TitleInput!
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "inputNestingCondition"})
            }
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") title: String @field(name: "title") }
            type Query { films(in: FilmInput): [Film!]! }
            """,
            schema -> {
                var qf = (QueryField.QueryTableField) schema.field("Query", "films");
                var cond = qf.filters().stream()
                    .filter(fi -> fi instanceof ConditionFilter && !(fi instanceof GeneratedConditionFilter))
                    .map(ConditionFilter.class::cast)
                    .findFirst().orElseThrow();
                assertThat(cond.methodName()).isEqualTo("inputNestingCondition");
                // The nested leaf (details.title) and the top-level filmId both emit implicits.
                var gcf = qf.filters().stream()
                    .filter(GeneratedConditionFilter.class::isInstance)
                    .map(GeneratedConditionFilter.class::cast)
                    .findFirst().orElseThrow();
                assertThat(gcf.bodyParams()).extracting(BodyParam::name)
                    .containsExactly("filmId", "title");
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

        NOT_GENERATED_REJECTED_INPUT_FIELD(
            "@notGenerated on an input field → the consuming field rejects as UnclassifiedField "
                + "with reason saying so",
            """
            input CustomerInput {
                customerId: Int! @field(name: "customer_id")
                hidden: String @notGenerated
            }
            type Customer @table(name: "customer") { customerId: Int! @field(name: "customer_id") }
            type Query { customers(in: CustomerInput): [Customer!]! }
            """,
            schema -> {
                var uf = (UnclassifiedField) schema.field("Query", "customers");
                assertThat(uf.reason()).contains("@notGenerated", "no longer supported");
            }),

        NOT_GENERATED_REJECTED_NESTED_INPUT(
            "@notGenerated on a field of a nested plain input → the consuming field rejects as "
                + "UnclassifiedField with reason saying so",
            """
            input InnerFilter { hidden: String @notGenerated }
            input CustomerInput {
                customerId: Int! @field(name: "customer_id")
                inner: InnerFilter
            }
            type Customer @table(name: "customer") { customerId: Int! @field(name: "customer_id") }
            type Query { customers(in: CustomerInput): [Customer!]! }
            """,
            schema -> {
                var uf = (UnclassifiedField) schema.field("Query", "customers");
                assertThat(uf.reason()).contains("1 input field could not be resolved");
                // Nested: the cause is reported at the nested input field carrying the directive.
                assertThat(TestSchemaHelper.diagnosticMessages(schema))
                    .contains("Input field 'InnerFilter.hidden'")
                    .contains("@notGenerated")
                    .contains("no longer supported");
            }),

        // ===== Canonical [ID!] @nodeId(typeName: T) =====

        ID_REFERENCE_NODEID_INFERRED(
            "[ID!] @nodeId(typeName:) with unique FK → ColumnBackedReferenceField with NodeIdDecodeKeys (FK inferred)",
            """
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId title: String }
            type Inventory @table(name: "inventory") { lastUpdate: String }
            input InventoryFilterInput {
              filmIds: [ID!] @nodeId(typeName: "Film")
            }
            type Query { inventories(in: InventoryFilterInput): [Inventory!]! }
            """,
            schema -> {
                var qf = (QueryField.QueryTableField) schema.field("Query", "inventories");
                var gcf = (GeneratedConditionFilter) qf.filters().get(0);
                var bp = (BodyParam.In) gcf.bodyParams().get(0);
                assertThat(bp.name()).isEqualTo("filmIds");
                assertThat(bp.column().sqlName()).isEqualTo("film_id");
                var nif = (CallSiteExtraction.NestedInputField) bp.extraction();
                assertThat(nif.leaf())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.ThrowOnMismatch.class);
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(InputField.ColumnBackedReferenceField.class); }
        },

        ID_REFERENCE_NODEID_EXPLICIT(
            "[ID!] @nodeId + @reference(path: [{key:}]) → lifted FK column predicate with NodeIdDecodeKeys leaf (FK explicit)",
            """
            type Language implements Node @table(name: "language") @node { id: ID! @nodeId name: String }
            type Film @table(name: "film") { title: String }
            input FilmFilterInput {
              languageIds: [ID!] @nodeId(typeName: "Language")
                                 @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { films(in: FilmFilterInput): [Film!]! }
            """,
            schema -> {
                var qf = (QueryField.QueryTableField) schema.field("Query", "films");
                var gcf = (GeneratedConditionFilter) qf.filters().get(0);
                var bp = (BodyParam.In) gcf.bodyParams().get(0);
                assertThat(bp.column().sqlName()).isEqualTo("language_id");
                var nif = (CallSiteExtraction.NestedInputField) bp.extraction();
                assertThat(nif.leaf())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.ThrowOnMismatch.class);
            }),

        ID_REFERENCE_AMBIGUOUS_FK(
            "[ID!] @nodeId with multiple FKs from source to target → consumer rejects as UnclassifiedField (needs @reference)",
            """
            type Language implements Node @table(name: "language") @node { id: ID! @nodeId name: String }
            type Film @table(name: "film") { title: String }
            input FilmFilterInput {
              languageIds: [ID!] @nodeId(typeName: "Language")
            }
            type Query { films(in: FilmFilterInput): [Film!]! }
            """,
            schema -> {
                var uf = (UnclassifiedField) schema.field("Query", "films");
                assertThat(uf.reason()).contains("1 input field could not be resolved");
                assertThat(TestSchemaHelper.diagnosticMessages(schema))
                    .contains("Input field 'FilmFilterInput.languageIds'")
                    .contains("no unique FK from 'film' to 'language'")
                    .contains("declare @reference(path: [{key: ...}]) to disambiguate");
            }),

        ID_REFERENCE_BAD_KEY(
            "[ID!] @nodeId + @reference to nonexistent FK key → consumer rejects as UnclassifiedField",
            """
            type Language implements Node @table(name: "language") @node { id: ID! @nodeId name: String }
            type Film @table(name: "film") { title: String }
            input FilmFilterInput {
              languageIds: [ID!] @nodeId(typeName: "Language")
                                 @reference(path: [{key: "no_such_fkey"}])
            }
            type Query { films(in: FilmFilterInput): [Film!]! }
            """,
            schema -> {
                var uf = (UnclassifiedField) schema.field("Query", "films");
                assertThat(uf.reason()).contains("1 input field could not be resolved");
                // The cause is reported at the input field carrying it, not on the consumer.
                assertThat(TestSchemaHelper.diagnosticMessages(schema)).contains("no_such_fkey");
            }),

        ID_REFERENCE_NO_FK_TO_TARGET(
            "[ID!] @nodeId where the consumer's table has zero FKs to target → consumer rejects as UnclassifiedField",
            """
            type Language implements Node @table(name: "language") @node { id: ID! @nodeId name: String }
            type Actor @table(name: "actor") { firstName: String }
            input ActorFilterInput {
              languageIds: [ID!] @nodeId(typeName: "Language")
            }
            type Query { actors(in: ActorFilterInput): [Actor!]! }
            """,
            schema -> {
                var uf = (UnclassifiedField) schema.field("Query", "actors");
                assertThat(uf.reason()).contains("1 input field could not be resolved");
                assertThat(TestSchemaHelper.diagnosticMessages(schema))
                    .contains("Input field 'ActorFilterInput.languageIds'")
                    .contains("no unique FK from 'actor' to 'language'");
            }),

        ID_REFERENCE_MIXED_DIRECTIVES_CANONICAL_WINS(
            "[ID!] @nodeId + @field(name:) → canonical branch wins, @field(name:) value ignored",
            """
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId title: String }
            type Inventory @table(name: "inventory") { lastUpdate: String }
            input InventoryFilterInput {
              filmIds: [ID!] @nodeId(typeName: "Film") @field(name: "BOGUS_NAME")
            }
            type Query { inventories(in: InventoryFilterInput): [Inventory!]! }
            """,
            schema -> {
                var qf = (QueryField.QueryTableField) schema.field("Query", "inventories");
                var gcf = (GeneratedConditionFilter) qf.filters().get(0);
                var bp = (BodyParam.In) gcf.bodyParams().get(0);
                assertThat(bp.column().sqlName()).isEqualTo("film_id");
                var nif = (CallSiteExtraction.NestedInputField) bp.extraction();
                assertThat(nif.leaf())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.ThrowOnMismatch.class);
            });

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        InputFieldResolutionCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() { return Set.of(); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(InputFieldResolutionCase.class)
    void inputFieldResolutionClassification(InputFieldResolutionCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    @Test
    @ProjectionFor(InputField.NestingField.class)
    void inputNestingFieldProjectionIsZeroPayload() {
        // Input fields are resolved per consuming field, never as a registry type walk, so
        // input-field declarations contribute no snapshot coordinates; the nesting wrapper is
        // zero-payload on the LSP surface. The plain input itself projects as PojoInput with
        // its consumer-derived table.
        var snapshot = buildSnapshot("""
            input InnerFilter { title: String @field(name: "title") }
            input FilmKey {
              filmId: Int @field(name: "film_id")
              inner: InnerFilter
            }
            type Film @table(name: "film") { title: String }
            type Query { film(key: FilmKey!): Film }
            """);
        var pojo = (TypeClassification.PojoInput) snapshot.typeClassificationsByName().get("FilmKey");
        assertThat(pojo.resolvedTables()).containsExactly("film");
        // Input nesting fields land on no snapshot coordinate of their own.
        var p = snapshot.fieldClassificationsByCoord().get("FilmKey.inner");
        if (p != null) {
            assertThat(p).isInstanceOf(FieldClassification.Nesting.class);
        }
    }

    // ===== Type classification =====

    enum TypeClassificationCase implements ClassificationCase {
        // The bare `@table -> TableType` verdict lives in the spec-by-example corpus (the
        // `catalog` ClassifiedCorpus example). This enum keeps
        // TABLE_NAME_DEFAULTS_TO_LOWERCASE_TYPE_NAME (which also asserts the resolved table())
        // and the projection test below.
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

        // The bare `Query / Mutation root -> RootType` verdict lives in the spec-by-example
        // corpus (the `catalog` ClassifiedCorpus example's Query). This enum keeps the
        // typeClassificationProjectionsCarryTableNodeAndRootShapes projection test below.

        ARG_MAPPING_INERT_ON_ENUM(
            "R53: argMapping on @enum → UnclassifiedType (structural-inertness rejection)",
            """
            enum Mood @enum(enumReference: {className: "no.sikt.graphitron.codereferences.dummyreferences.MoodEnum", argMapping: "x: y"}) { HAPPY SAD }
            type Query { foo(mood: Mood): String }
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
    void typeClassification(TypeClassificationCase tc) { tc.assertions.accept(build(tc.sdl)); }

    @Test
    @ProjectionFor({TableType.class, NodeType.class, RootType.class})
    void typeClassificationProjectionsCarryTableNodeAndRootShapes() {
        var snapshot = buildSnapshot("""
            type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
              id: ID! @nodeId
              title: String
            }
            type Actor @table(name: "actor") { firstName: String }
            type Query { film: Film actor: Actor }
            """);
        // NodeType wins over TableType when @node lifts the table to a NodeType.
        var node = (TypeClassification.Node) snapshot.typeClassificationsByName().get("Film");
        assertThat(node.tableName()).isEqualToIgnoringCase("film");
        assertThat(node.keyColumnNames()).contains("film_id");

        var table = (TypeClassification.Table) snapshot.typeClassificationsByName().get("Actor");
        assertThat(table.tableName()).isEqualToIgnoringCase("actor");

        var root = (TypeClassification.Root) snapshot.typeClassificationsByName().get("Query");
        assertThat(root.operation()).isEqualToIgnoringCase("query");
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
                assertThat(((ScalarResolution.Resolved) t.resolution()).scalarConstantField()).isEqualTo("GraphQLString");
                assertThat(((ScalarResolution.Resolved) t.resolution()).scalarConstantOwner().toString()).isEqualTo("graphql.Scalars");
            }),

        SPEC_BUILT_IN_ID_RESOLVES(
            "spec built-in 'ID' → ScalarType with Resolved(String, Scalars, GraphQLID)",
            """
            type Query { id: ID }
            """,
            schema -> {
                var t = (ScalarType) schema.type("ID");
                assertThat(((ScalarResolution.Resolved) t.resolution()).scalarConstantField()).isEqualTo("GraphQLID");
            }),

        DIRECTIVE_RESOLVES_CONSUMER_SCALAR(
            "scalar with @scalarType referencing a well-formed Coercing → ScalarType resolved to fixture",
            """
            scalar Money @scalarType(scalar: "no.sikt.graphitron.rewrite.scalarfixture.ScalarConstants.MONEY")
            type Query { x: Money }
            """,
            schema -> {
                var t = (ScalarType) schema.type("Money");
                assertThat(((ScalarResolution.Resolved) t.resolution()).scalarConstantField()).isEqualTo("MONEY");
                assertThat(((ScalarResolution.Resolved) t.resolution()).scalarConstantOwner().toString())
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
            "non-spec, non-federation scalar with no @scalarType → UnclassifiedType with the "
                + "@scalarType-pointing structural rejection (the single-path contract; there is no "
                + "convention fallback, so a re-added fallback fails this)",
            """
            scalar Money
            type Query { x: Money }
            """,
            schema -> {
                var t = (UnclassifiedType) schema.type("Money");
                assertThat(t.reason())
                    .contains("scalar 'Money'")
                    .contains("@scalarType")
                    .contains("fully.qualified.Class.FIELD");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(); }
        },

        DIRECTIVE_ALIASES_TO_DIFFERENT_CONSTANT(
            "scalar named 'BigDecimal' with @scalarType pointing at a differently-named constant → Synthesised",
            """
            scalar BigDecimal @scalarType(scalar: "no.sikt.graphitron.rewrite.scalarfixture.ScalarConstants.MONEY")
            type Query { x: BigDecimal }
            """,
            schema -> {
                // The SDL name 'BigDecimal' differs from the Money constant's intrinsic name, so the
                // scalar registers under 'BigDecimal' (Synthesised) borrowing Money's coercing
                // rather than registering the constant under 'Money'.
                var t = (ScalarType) schema.type("BigDecimal");
                assertThat(((ScalarResolution.Synthesised) t.resolution()).coercingSourceField()).isEqualTo("MONEY");
                assertThat(((ScalarResolution.Synthesised) t.resolution()).sdlName()).isEqualTo("BigDecimal");
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

    @Test
    @ProjectionFor(ScalarType.class)
    void scalarTypeProjectionCarriesJavaType() {
        var snapshot = buildSnapshot("type Query { x: String }");
        var p = (TypeClassification.Scalar) snapshot.typeClassificationsByName().get("String");
        assertThat(p.javaType()).isNotBlank();
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
            type Query { err: MyError }
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
            type Query { err: DbError }
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
            type Query { err: DbError }
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
            type Query { err: DbError }
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
            type Query { err: ValidationError }
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
            type Query { err: MatchError }
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
            type Query { err: MultiError }
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
            type Query { err: BadError }
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
            type Query { err: BadError }
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
            type Query { err: BadError }
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
            type Query { err: BadError }
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
            type Query { err: BadError }
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
            type Query { err: BadError }
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
            type Query { err: BadError }
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
            type Query { err: BadError }
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
            type Query { err: BadError }
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
            type Query { err: BadError }
            """,
            schema -> assertThat(((UnclassifiedType) schema.type("BadError")).reason())
                .contains("path")),

        REJECT_MISSING_MESSAGE_FIELD(
            "@error type missing 'message' field → UnclassifiedType",
            """
            type BadError @error(handlers: [{handler: GENERIC, className: "com.example.Ex"}]) {
                path: [String!]!
            }
            type Query { err: BadError }
            """,
            schema -> assertThat(((UnclassifiedType) schema.type("BadError")).reason())
                .contains("message")),

        // The `@error with a field beyond path/message -> ErrorType` admission nuance lives in
        // the spec-by-example corpus (the `error-type` ClassifiedCorpus example's ExtraFieldError).
        // This enum keeps the slot-asserting ErrorTypeCase rows and the projection test below.

        REJECT_WRONG_PATH_SHAPE(
            "@error type with path: String (not [String!]!) → UnclassifiedType",
            """
            type BadError @error(handlers: [{handler: GENERIC, className: "com.example.Ex"}]) {
                path: String
                message: String!
            }
            type Query { err: BadError }
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
            type Query { err: BadError }
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
            type Query { err: BadError }
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
            type Query { err: BadError }
            """,
            schema -> {
                var t = (UnclassifiedType) schema.type("BadError");
                assertThat(t.reason())
                    .contains("java.lang.String")
                    .contains("Throwable");
            }),

        // @field(name:) parse rejections on the @error surface.
        REJECT_BLANK_FIELD_DIRECTIVE_ON_EXTRA_FIELD(
            "extra field with @field(name: \"\") (blank) → UnclassifiedType",
            """
            type BadError @error(handlers: [{handler: GENERIC, className: "java.lang.RuntimeException"}]) {
                path: [String!]!
                message: String!
                detail: String @field(name: "")
            }
            type Query { err: BadError }
            """,
            schema -> {
                var t = (UnclassifiedType) schema.type("BadError");
                assertThat(t.reason())
                    .contains("detail")
                    .contains("blank");
            }),

        REJECT_FIELD_DIRECTIVE_ON_PATH(
            "@field on 'path' → UnclassifiedType (path is populated by Graphitron)",
            """
            type BadError @error(handlers: [{handler: GENERIC, className: "java.lang.RuntimeException"}]) {
                path: [String!]! @field(name: "somethingElse")
                message: String!
            }
            type Query { err: BadError }
            """,
            schema -> {
                var t = (UnclassifiedType) schema.type("BadError");
                assertThat(t.reason())
                    .contains("@field")
                    .contains("path");
            }),

        REJECT_FIELD_DIRECTIVE_ON_MESSAGE(
            "@field on 'message' → UnclassifiedType (message is populated by Graphitron)",
            """
            type BadError @error(handlers: [{handler: GENERIC, className: "java.lang.RuntimeException"}]) {
                path: [String!]!
                message: String! @field(name: "somethingElse")
            }
            type Query { err: BadError }
            """,
            schema -> {
                var t = (UnclassifiedType) schema.type("BadError");
                assertThat(t.reason())
                    .contains("@field")
                    .contains("message");
            });

        // The `@error co-located with @record -> ErrorType` precedence rule (@record is
        // deprecated and silently ignored on an @error type; @error wins) lives in the
        // spec-by-example corpus (the `error-type` ClassifiedCorpus example's RecordIgnoredError).
        // This enum keeps the slot-asserting ErrorTypeCase rows and the projection test below.

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

    @Test
    @ProjectionFor(ErrorType.class)
    void errorTypeProjectionCarriesHandlerKinds() {
        var snapshot = buildSnapshot("""
            type MyError @error(handlers: [{handler: GENERIC, className: "java.lang.IllegalArgumentException"}]) {
                path: [String!]!
                message: String!
            }
            type Query { err: MyError }
            """);
        var p = (TypeClassification.Error) snapshot.typeClassificationsByName().get("MyError");
        assertThat(p.handlerKinds()).isNotEmpty();
    }

    // ===== Fields on @error parents =====

    // The `@error parent path/message -> RecordReadField (DefaultRead)` verdict lives in the
    // spec-by-example corpus (the `error-field` ClassifiedCorpus example, MyError.path /
    // MyError.message); both fields resolve off the developer-supplied @error class via
    // graphql-java's default PropertyDataFetcher. No ErrorFieldCase enum exists here; the
    // RecordReadField leaf is also covered by the slot-asserting record cases above.

    // ===== Errors-shaped fields lifting from PolymorphicReturnType to ErrorsField =====

    /**
     * The five {@code PolymorphicReturnType} rejection arms in {@link FieldBuilder} lift to
     * {@link ErrorsField} when the polymorphic type's members are all {@code @error} types and
     * the field is a nullable list.
     *
     * <p>Each case targets one of the five lift sites by varying the carrier shape:
     *
     * <ul>
     *   <li>{@code classifyMutationField} — Mutation root {@code @service} returning a payload
     *       (the canonical {@code BehandleSakPayload} shape).</li>
     *   <li>{@code classifyChildFieldOnResultType} (non-service arm) — the payload's own
     *       {@code errors} field on a record-backed parent.</li>
     *   <li>Plus rejection cases: mixed-{@code @error}-and-non-{@code @error} unions, non-null
     *       list shapes, and pure non-{@code @error} polymorphic returns (which still fall
     *       through to the existing "polymorphic not supported" rejection).</li>
     * </ul>
     */
    enum ErrorsFieldCase implements ClassificationCase {
        UNION_OF_ALL_ERROR_TYPES_LIFTS_TO_ERRORS_FIELD(
            "union of @error types on record-backed payload — errors field lifts to ErrorsField",
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
            type BehandleSakPayload {
                ok: Boolean
                errors: [BehandleSakError!]
            }
            type Query {
                x: String
                p: BehandleSakPayload @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """,
            schema -> {
                var field = schema.field("BehandleSakPayload", "errors");
                assertThat(field).isInstanceOf(ErrorsField.class);
                var ef = (ErrorsField) field;
                assertThat(ef.errorTypes())
                    .extracting(et -> et.name())
                    .containsExactly("ValidationErr", "DbErr");
                // BehandleSakPayload is produced by a root @service field, so its errors field
                // rides the Outcome wrapper transport (WrapperArm), not the
                // developer-errors-slot PayloadAccessor passthrough.
                assertThat(ef.transport())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.ChildField.Transport.WrapperArm.class);
            }),

        INTERFACE_IMPLEMENTED_BY_ALL_ERROR_TYPES_LIFTS_TO_ERRORS_FIELD(
            "interface implemented by @error types on record-backed payload — errors field lifts to ErrorsField",
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
            type BehandleSakPayload {
                ok: Boolean
                errors: [BehandleSakError]
            }
            type Query {
                x: String
                p: BehandleSakPayload @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
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
            type BehandleSakPayload {
                ok: Boolean
                errors: [BehandleSakError]
            }
            type Query {
                x: String
                p: BehandleSakPayload @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """,
            schema -> {
                var field = schema.field("BehandleSakPayload", "errors");
                assertThat(field).isInstanceOf(ErrorsField.class);
                assertThat(((ErrorsField) field).errorTypes())
                    .extracting(et -> et.name())
                    .containsExactly("DbErr");
            }),

        // No mixed-union rejection case: under reflection-only binding the payload is produced by
        // a @service field, so a mixed (not-all-@error) union errors field is simply "not an
        // errors field" and routes to accessor resolution. Mixed-union errors-field validation
        // lives in the @service-carrier errors-field rules.
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
            type BehandleSakPayload {
                ok: Boolean
                errors: [BehandleSakError!]!
            }
            type Query {
                x: String
                p: BehandleSakPayload @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """,
            schema -> {
                var field = schema.field("BehandleSakPayload", "errors");
                assertThat(field).isInstanceOf(UnclassifiedField.class);
                var u = (UnclassifiedField) field;
                assertThat(u.reason())
                    .contains("must be nullable");
                assertThat(u.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
            });
        // The non-error-polymorphic-on-record-parent deferral is covered by
        // RecordParentMultiTablePolymorphicPipelineTest (childUnionField_recordParent_accessorKeyedSingle_deferred).

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
     * guard. A plain-input arg whose chain contains a circular reference rejects
     * loudly as {@code UnclassifiedField} (the inner classifier surfaces the
     * "circular input type reference detected" diagnostic); the assertion here is that the
     * build terminates rather than stack-overflowing.
     */
    @Test
    void lookupKeySearch_depthGuardPreventsInfiniteRecursionOnCircularInputTypes() {
        // A → B → A … circular reference; no @lookupKey anywhere in the chain.
        // The classifier's circular guard surfaces an Unresolved which the plain-input
        // resolver lifts as Rejected → UnclassifiedField. The build terminates.
        var schema = build("""
            input A { b: B }
            input B { a: A }
            type Film @table(name: "film") { title: String }
            type Query { films(filter: A): [Film!]! }
            """);

        assertThat(schema.field("Query", "films"))
            .as("circular input chain rejects loudly as UnclassifiedField; build terminates")
            .isInstanceOf(UnclassifiedField.class);
        var uf = (UnclassifiedField) schema.field("Query", "films");
        assertThat(uf.reason()).contains("1 input field could not be resolved");
        assertThat(TestSchemaHelper.diagnosticMessages(schema))
            .contains("circular input type reference detected");
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
        // @field(javaName:) is not a declared directive argument. graphql-java's standard
        // directive validation surfaces the rejection with an "unknown argument 'javaName'"
        // message at schema-build time; no custom message is bundled.
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
     * tables in {@code code-generation-triggers.adoc}.
     */
    enum RootFieldCase implements ClassificationCase {

        LOOKUP_QUERY_FIELD(
            "field with @lookupKey list arg → QueryTableField with list return; key flows through LookupMapping, not filters",
            """
            type Film @table(name: "film") { title: String }
            type Query { filmById(film_id: [ID] @lookupKey): [Film!]! }
            """,
            schema -> {
                assertThat(schema.field("Query", "filmById")).isInstanceOf(QueryField.QueryTableField.class);
                var f = (QueryField.QueryTableField) schema.field("Query", "filmById");
                // @lookupKey args do not populate filters(); they are emitted via VALUES+JOIN from
                // LookupMapping.args() (see docs/architecture/reference/argument-resolution.adoc).
                assertThat(f.filters()).isEmpty();
                var cm = (no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) keyedMappingOf(f.lookup());
                assertThat(cm.args()).hasSize(1);
                assertThat(cm.args().get(0).argName()).isEqualTo("film_id");
                assertThat(f.returnType().wrapper()).isInstanceOf(FieldWrapper.List.class);
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(QueryField.QueryTableField.class); }
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
                    .contains("@lookupKey on a mutation input field is no longer supported")
                    .contains("ARGUMENT_DEFINITION");
            }),

        LOOKUP_FIELD_COLUMN_ARG(
            "lookup field list arg whose column exists → resolved ScalarLookupArg in LookupMapping",
            """
            type Film @table(name: "film") { title: String }
            type Query { filmById(film_id: [ID] @lookupKey): [Film!]! }
            """,
            schema -> {
                var f = (QueryField.QueryTableField) schema.field("Query", "filmById");
                assertThat(f.filters()).isEmpty();
                var cm = (no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) keyedMappingOf(f.lookup());
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

        LOOKUP_FIELD_PLAIN_INPUT_TYPE_ARG_ADMITS_EVERY_FIELD(
            "R144: lookup field whose plain input type has admissible carriers and arg-level @lookupKey → QueryTableField; the input stays a plain PojoInputType and every admissible input field becomes a binding against the consumer's table (filter-by-default)",
            """
            input FilmKey { filmId: Int @field(name: "film_id") }
            type Film @table(name: "film") { title: String }
            type Query { filmByKey(key: [FilmKey] @lookupKey): [Film!]! }
            """,
            schema -> {
                var f = (QueryField.QueryTableField) schema.field("Query", "filmByKey");
                var cm = (no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) keyedMappingOf(f.lookup());
                assertThat(cm.args()).hasSize(1);
                var arg = (no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping.LookupArg.MapInput) cm.args().get(0);
                assertThat(arg.bindings()).hasSize(1);
                assertThat(arg.bindings().get(0).fieldName()).isEqualTo("filmId");
                assertThat(schema.type("FilmKey")).isInstanceOf(PojoInputType.class);
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(QueryField.QueryTableField.class); }
        },

        LOOKUP_FIELD_COMPOSITE_KEY_INPUT_TYPE_ARG(
            "lookup field whose plain input type carries two scalar fields with arg-level @lookupKey → QueryTableField with one MapInput LookupArg carrying two MapBindings (R144: every admissible input field becomes a binding when the arg carries @lookupKey)",
            """
            input FilmActorKey {
                filmId: Int @field(name: "film_id")
                actorId: Int @field(name: "actor_id")
            }
            type FilmActor @table(name: "film_actor") { lastUpdate: String @field(name: "last_update") }
            type Query { filmActorByKey(key: [FilmActorKey] @lookupKey): [FilmActor!]! }
            """,
            schema -> {
                var f = (QueryField.QueryTableField) schema.field("Query", "filmActorByKey");
                assertThat(f.filters()).isEmpty();
                var cm = (no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) keyedMappingOf(f.lookup());
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
            @Override public Set<Class<?>> variants() { return Set.of(QueryField.QueryTableField.class); }
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
                var f = (QueryField.QueryTableField) schema.field("Query", "filmById");
                assertThat(f.orderBy()).isInstanceOf(OrderBySpec.Argument.class);
                var orderBy = (OrderBySpec.Argument) f.orderBy();
                assertThat(orderBy.sortFieldName()).isEqualTo("sortField");
                assertThat(orderBy.directionFieldName()).isEqualTo("direction");
                assertThat(orderBy.namedOrders()).hasSize(1);
                assertThat(orderBy.namedOrders().get(0).name()).isEqualTo("TITLE");
                assertThat(f.filters()).isEmpty();
                var cm2 = (no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping) keyedMappingOf(f.lookup());
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

        // The bare `root field returning a @table type -> QueryTableField` verdict lives in the
        // spec-by-example corpus (Query.film / Query.films in the `catalog` example, Query.city in
        // the `child-table` example). This enum keeps the slot-asserting root cases below
        // (orderBy, filters, ordering defaults, the languages projection cases).

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

        // The bare Relay-root verdicts (QueryNodeField in canonical and federation-style-alias
        // forms, QueryNodesField) live in the spec-by-example corpus (`relay-node` example:
        // Query.node, Query.internalFilmNode, Query.nodes).

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

        // The bare polymorphic-root verdicts (QueryTableInterfaceField, QueryInterfaceField,
        // QueryUnionField) live in the spec-by-example corpus (Query.topMedia in the
        // `table-interface` example, Query.anyNamed in the `interface` example, Query.search in
        // the `union` example).

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
                assertThat(f.serviceMethodCall().fqClassName()).isEqualTo("no.sikt.graphitron.rewrite.TestServiceStub");
                assertThat(f.serviceMethodCall().methodName()).isEqualTo("getFilm");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(QueryField.QueryServiceTableField.class); }
        },

        // The bare `root @service into a non-table record-backed type -> QueryServiceRecordField`
        // verdict lives in the spec-by-example corpus (`query-service-record` ClassifiedCorpus
        // example, Query.filmDetails). This enum keeps the @ProjectionFor projection test below.

        // The bare DML write-then-project verdicts live in the spec-by-example corpus: INSERT ->
        // DmlTableField (`dml` example, createFilm) and UPDATE ->
        // DmlTableField (`mutation-roots` example, updateFilm). This enum keeps the
        // slot-asserting DmlReturnExpression / payload cases.

        DELETE_MUTATION_FIELD(
            "R266 / R287: @mutation(typeName: DELETE) returning ID → DmlTableField carrying "
                + "the DeleteRows walker carrier (filmId covers the PK → Identified; whereColumns is "
                + "every admitted input column).",
            """
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId filmId: Int! @field(name: "film_id") }
            input FilmInput { filmId: Int! @field(name: "film_id") }
            type Query { x: String }
            type Mutation { deleteFilm(in: FilmInput!): ID @mutation(typeName: DELETE, table: "film") }
            """,
            schema -> {
                var f = (MutationField.DmlTableField) schema.field("Mutation", "deleteFilm");
                var deleteRows = (no.sikt.graphitron.rewrite.model.DeleteRows.Identified) deleteRowsOf(f);
                assertThat(deleteRows.matchedKey().columns()).extracting(c -> c.sqlName()).containsExactly("film_id");
                assertThat(deleteRows.whereColumns()).extracting(c -> c.targetColumn().sqlName()).containsExactly("film_id");
                assertThat(deleteArgOf(f).table().tableName()).isEqualToIgnoringCase("film");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.DmlTableField.class); }
        },

        DELETE_MUTATION_NO_INPUT_ARG(
            "@mutation(typeName: DELETE) without an input arg → UnclassifiedField",
            """
            type Film @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation { deleteFilm: Film @mutation(typeName: DELETE) }
            """,
            schema -> {
                var field = schema.field("Mutation", "deleteFilm");
                assertThat(field).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) field).reason())
                    .contains("no input argument found on @mutation field");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        DELETE_MUTATION_MISSING_PK_COVERAGE(
            "R266: @mutation(typeName: DELETE) on a composite-PK table with one PK column missing and "
                + "no covered UK, without multiRow → UnclassifiedField carrying "
                + "DeleteRowsError.NoUniqueKeyCoverage.",
            """
            type FilmActor implements Node @table(name: "film_actor") @node { id: ID! @nodeId actorId: Int, filmId: Int }
            input FilmActorKey { filmId: Int! @field(name: "film_id") }
            type Query { x: String }
            type Mutation { deleteFilmActor(in: FilmActorKey!): ID @mutation(typeName: DELETE, table: "film_actor") }
            """,
            schema -> {
                var field = schema.field("Mutation", "deleteFilmActor");
                assertThat(field).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) field).rejection())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.DeleteRowsError.NoUniqueKeyCoverage.class);
                assertThat(((UnclassifiedField) field).reason())
                    .contains("covers no primary key or unique key", "multiRow: true");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        UPSERT_MUTATION_REJECTED_UNDER_R144(
            "@mutation(typeName: UPSERT) → UnclassifiedField (R144 refuses UPSERT pending R145)",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput { filmId: Int! @field(name: "film_id"), title: String }
            type Query { x: String }
            type Mutation { upsertFilm(in: FilmInput!): Film @mutation(typeName: UPSERT) }
            """,
            schema -> {
                var field = schema.field("Mutation", "upsertFilm");
                assertThat(field).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) field).reason())
                    .contains("@mutation(typeName: UPSERT) is not yet supported");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        // The bare @service-mutation verdicts live in the spec-by-example corpus
        // (`mutation-roots` example: externalMutation -> MutationServiceTableField re-queries the
        // catalog; externalRecord -> MutationServiceRecordField materializes the record). This
        // enum keeps the slot-asserting carrier cases below.

        // Positive baseline: an @service mutation whose SDL return is a payload CARRIER with a
        // single @table data field (resolving to a real catalog table) and NO forbidden directive
        // on that field is recognized as a carrier and classifies as MutationServiceRecordField,
        // so the payload object type stays in the model and schema assembly succeeds. Both single
        // and list cardinalities are covered. The @splitQuery sibling case below is the regression
        // (the data field carrying @splitQuery is what breaks it).
        SERVICE_MUTATION_SINGLE_RECORD_CARRIER_OVER_RESOLVABLE_TABLE(
            "@service mutation returning a single-record payload carrier whose @table data field "
                + "resolves to a real table -> MutationServiceRecordField (NOT MutationServiceTableField)",
            """
            type Film @table(name: "film") { title: String }
            type FilmPayload { film: Film }
            type Query { x: String }
            type Mutation {
                createFilm: FilmPayload @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runFilm"})
            }
            """,
            schema -> assertThat(schema.field("Mutation", "createFilm")).isInstanceOf(MutationField.MutationServiceRecordField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationServiceRecordField.class); }
        },

        SERVICE_MUTATION_LIST_RECORD_CARRIER_OVER_RESOLVABLE_TABLE(
            "@service mutation returning a list-record payload carrier whose @table data field "
                + "resolves to a real table -> MutationServiceRecordField (NOT MutationServiceTableField); "
                + "mirrors the opptak leggTilTagger -> LeggTilTaggerPayload { saker: [Sak!] } regression",
            """
            type Film @table(name: "film") { title: String }
            type FilmsPayload { films: [Film!] }
            type Query { x: String }
            type Mutation {
                createFilms: FilmsPayload @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmsAsList"})
            }
            """,
            schema -> assertThat(schema.field("Mutation", "createFilms")).isInstanceOf(MutationField.MutationServiceRecordField.class)) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationServiceRecordField.class); }
        },

        // Regression pin: the same @service-carrier shape, but the single @table data field
        // carries @splitQuery and the payload pairs it with an errors field (the opptak
        // leggTilTagger -> LeggTilTaggerPayload { saker: [Sak!] @splitQuery, errors } shape).
        // The @service-carrier scan must tolerate @splitQuery, which is redundant there (the
        // carrier data field's emit already runs a PK-keyed follow-up SELECT off the producer's
        // record; an advisory warning fires): if the scan instead returned NotApplicable, the
        // payload would never promote and the type would drop from the model while the mutation
        // field still emitted typeRef("FilmsPayload"), failing graphql-java assembly with
        // "type FilmsPayload not found in schema". With the tolerance, the payload promotes, the
        // mutation field classifies, and the errors field gives the producer the typed Outcome
        // channel.
        SERVICE_MUTATION_SPLITQUERY_CARRIER_DROPS_PAYLOAD_TYPE(
            "@service mutation returning an errors-bearing payload carrier whose @table data field "
                + "carries @splitQuery -> payload type survives (carrier path), not dropped",
            """
            type Film @table(name: "film") { title: String }
            type FilmErr @error(handlers: [{handler: GENERIC, className: "java.lang.IllegalArgumentException"}]) {
                path: [String!]!
                message: String!
            }
            union CreateFilmsError = FilmErr
            type FilmsPayload {
                films: [Film!] @splitQuery
                errors: [CreateFilmsError]
            }
            type Query { x: String }
            type Mutation {
                createFilms: FilmsPayload @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmsAsList"})
            }
            """,
            schema -> {
                assertThat(schema.type("FilmsPayload"))
                    .as("FilmsPayload must remain a classified output type (carrier), not be dropped, when its data field has @splitQuery")
                    .isNotNull()
                    .isNotInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType.class);
                assertThat(schema.field("Mutation", "createFilms"))
                    .isInstanceOf(MutationField.MutationServiceRecordField.class);
                // The data field classifies as BatchedTableField, a source=target re-fetch.
                // MANY (list) per-key cardinality, KeyLift.ProducedRecords reading the records off the
                // source (the OUTCOME_SUCCESS envelope is applied by the generator at the type
                // level, not carried on the SourceKey).
                var dataField = schema.field("FilmsPayload", "films");
                assertThat(dataField).isInstanceOf(no.sikt.graphitron.rewrite.model.ChildField.BatchedTableField.class);
                var lift = ((no.sikt.graphitron.rewrite.model.ChildField.BatchedTableField) dataField).lift();
                assertThat(lift).isInstanceOf(no.sikt.graphitron.rewrite.model.KeyLift.ProducedRecords.class);
                assertThat(((no.sikt.graphitron.rewrite.model.KeyLift.ProducedRecords) lift).arity())
                    .isEqualTo(no.sikt.graphitron.rewrite.model.Arity.MANY);
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationServiceRecordField.class); }
        },

        // Regression pin: an @service mutation returning a payload whose single data field is an
        // ID-encoding field ([ID] @nodeId), paired with an errors field (the opptak
        // fjernSakTagger -> FjernSakTaggerPayload { tagger: [ID] @nodeId, errors } shape,
        // delete-then-echo-ids). Note the list-of-nullable [ID] wrapper: the DML DELETE scan
        // rejects it, the @service-carrier scan admits it (the real opptak schema declares [ID]).
        // The ServiceEmitted binding grounds from @nodeId(typeName:)'s @table, the payload
        // promotes to JooqTableRecordType, and the data field classifies as SingleRecordIdField
        // encoding node ids straight off the producer's in-memory records (no re-fetch,
        // deletion-safe by construction). Without that grounding the payload would never register
        // while the mutation field still emitted typeRef("FilmIdsPayload"), failing graphql-java
        // assembly with "type FilmIdsPayload not found in schema".
        SERVICE_MUTATION_ID_CARRIER_ENCODES_FROM_RECORD(
            "@service mutation returning an [ID] @nodeId carrier classifies; the data field encodes "
                + "node ids off the producer's records with no re-fetch",
            """
            type Film implements Node @node @table(name: "film") { id: ID! @nodeId  title: String }
            type FilmErr @error(handlers: [{handler: GENERIC, className: "java.lang.IllegalArgumentException"}]) {
                path: [String!]!
                message: String!
            }
            union DeleteFilmsError = FilmErr
            type FilmIdsPayload {
                filmIds: [ID] @nodeId(typeName: "Film")
                errors: [DeleteFilmsError]
            }
            type Query { x: String }
            type Mutation {
                deleteFilms: FilmIdsPayload @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmsAsList"})
            }
            """,
            schema -> {
                assertThat(schema.type("FilmIdsPayload"))
                    .as("FilmIdsPayload must register as a producer-backed carrier, not be dropped")
                    .isNotNull()
                    .isNotInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType.class);
                assertThat(schema.field("Mutation", "deleteFilms"))
                    .isInstanceOf(MutationField.MutationServiceRecordField.class);
                // The data field encodes off Outcome.Success.value()'s records: SingleRecordIdField,
                // MANY cardinality, OUTCOME_SUCCESS envelope, the producer's table on the SourceKey.
                var dataField = schema.field("FilmIdsPayload", "filmIds");
                assertThat(dataField).isInstanceOf(no.sikt.graphitron.rewrite.model.ChildField.SingleRecordIdField.class);
                var idField = (no.sikt.graphitron.rewrite.model.ChildField.SingleRecordIdField) dataField;
                assertThat(idField.returnType().wrapper().isList()).isTrue();
                assertThat(idField.envelope())
                    .isEqualTo(no.sikt.graphitron.rewrite.model.SourceEnvelope.OUTCOME_SUCCESS);
                assertThat(idField.table().tableName()).isEqualTo("film");
            }) {
            @Override public Set<Class<?>> variants() {
                return Set.of(MutationField.MutationServiceRecordField.class,
                    no.sikt.graphitron.rewrite.model.ChildField.SingleRecordIdField.class);
            }
        },

        // The bare single-record DML payload verdict lives in the spec-by-example corpus
        // (`mutation-roots` example, createFilmPayload: an INSERT returning a plain object carrier
        // wrapping one @table data field; the carrier exposes the RETURNING rows as a record, the
        // follow-up projection being the data field's own re-fetch). This enum keeps the bulk /
        // cardinality-pairing cases (MUTATION_BULK_DML_RECORD_FIELD below) that assert slot detail.

        MUTATION_BULK_DML_RECORD_FIELD(
            "R141: @mutation(typeName: INSERT) with bulk input and a single-record DML "
                + "carrier whose data field is list-shaped → MutationBulkDmlRecordField. The "
                + "carrier classifier routes the bulk-input + list-data-field cell to the new "
                + "sealed leaf; the emitter batches per-row DML in input order and runs one "
                + "follow-up response SELECT keyed by the PKs collected in input order.",
            """
            type Film @table(name: "film") { title: String }
            type FilmsPayload { films: [Film!] }
            input FilmCreateInput { title: String }
            type Query { x: String }
            type Mutation {
                createFilmsPayload(in: [FilmCreateInput!]!): FilmsPayload @mutation(typeName: INSERT)
            }
            """,
            schema -> {
                var f = (MutationField.MutationBulkDmlRecordField)
                    schema.field("Mutation", "createFilmsPayload");
                assertThat(f.write()).isInstanceOf(no.sikt.graphitron.rewrite.model.OperationMember.Write.Insert.class);
                assertThat(f.write().listInput()).isTrue();
                assertThat(f.returnType().returnTypeName()).isEqualTo("FilmsPayload");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationBulkDmlRecordField.class); }
        },

        UPDATE_PAYLOAD_MUTATION_FIELD(
            "R258: @mutation(typeName: UPDATE) with single input and a single-record DML "
                + "payload → MutationDmlRecordField. The payload-returning UPDATE shares the "
                + "structural-payload emit shape with MutationDmlRecordField but sources its SET/WHERE "
                + "partition from the UpdateRowsWalker carrier (PK-or-UK), not @value.",
            """
            type Film @table(name: "film") { title: String }
            type FilmPayload { film: Film }
            input FilmUpdateInput { filmId: Int! @field(name: "film_id"), title: String }
            type Query { x: String }
            type Mutation {
                updateFilmPayload(in: FilmUpdateInput!): FilmPayload @mutation(typeName: UPDATE)
            }
            """,
            schema -> {
                var f = (MutationField.MutationDmlRecordField)
                    schema.field("Mutation", "updateFilmPayload");
                assertThat(f.returnType().returnTypeName()).isEqualTo("FilmPayload");
                assertThat(updateArgOf(f).table().tableName()).isEqualToIgnoringCase("film");
                assertThat(updateArgOf(f).list()).isFalse();
                // PK-or-UK partition: filmId (PK) → WHERE, title (non-key) → SET.
                assertThat(updateRowsOf(f).keyColumns()).extracting(c -> c.targetColumn().sqlName())
                    .containsExactly("film_id");
                assertThat(updateRowsOf(f).setColumns()).extracting(c -> c.targetColumn().sqlName())
                    .containsExactly("title");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationDmlRecordField.class); }
        },

        UPDATE_BULK_PAYLOAD_MUTATION_FIELD(
            "R258: @mutation(typeName: UPDATE) with bulk input and a single-record DML carrier "
                + "whose data field is list-shaped → MutationBulkDmlRecordField. Bulk sibling of "
                + "MutationDmlRecordField; the emitter batches per-row UPDATE in input order off "
                + "the same UpdateRows carrier partition.",
            """
            type Film @table(name: "film") { title: String }
            type FilmsPayload { films: [Film!] }
            input FilmUpdateInput { filmId: Int! @field(name: "film_id"), title: String }
            type Query { x: String }
            type Mutation {
                updateFilmsPayload(in: [FilmUpdateInput!]!): FilmsPayload @mutation(typeName: UPDATE)
            }
            """,
            schema -> {
                var f = (MutationField.MutationBulkDmlRecordField)
                    schema.field("Mutation", "updateFilmsPayload");
                assertThat(f.returnType().returnTypeName()).isEqualTo("FilmsPayload");
                assertThat(updateArgOf(f).list()).isTrue();
                assertThat(updateRowsOf(f).keyColumns()).extracting(c -> c.targetColumn().sqlName())
                    .containsExactly("film_id");
                assertThat(updateRowsOf(f).setColumns()).extracting(c -> c.targetColumn().sqlName())
                    .containsExactly("title");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationBulkDmlRecordField.class); }
        },

        DELETE_PAYLOAD_TABLE_ELEMENT_REJECTS(
            "R287: @mutation(typeName: DELETE) with a single-record payload whose data field is a "
                + "@table-element → UnclassifiedField. The row is gone after the statement and RETURNING "
                + "carries only the PK, so a full @table projection is impossible; the legitimate "
                + "MutationDmlRecordField shape carries an ID-element data field (see "
                + "MutationDmlNodeIdClassificationTest under the nodeidfixture catalog).",
            """
            type Film @table(name: "film") { title: String }
            type FilmPayload { film: Film }
            input FilmDeleteInput { filmId: Int! @field(name: "film_id") }
            type Query { x: String }
            type Mutation {
                deleteFilmPayload(in: FilmDeleteInput!): FilmPayload @mutation(typeName: DELETE, table: "film")
            }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "deleteFilmPayload");
                assertThat(f.reason()).contains(
                    "@table-element data field", "RETURNING carries only the primary key", "ID-typed data field");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        DELETE_BULK_PAYLOAD_TABLE_ELEMENT_REJECTS(
            "R287: @mutation(typeName: DELETE) with a bulk payload whose data field is a list-shaped "
                + "@table-element → UnclassifiedField. Bulk sibling of DELETE_PAYLOAD_TABLE_ELEMENT_REJECTS; "
                + "the legitimate MutationBulkDmlRecordField shape carries an ID-element data field.",
            """
            type Film @table(name: "film") { title: String }
            type FilmsPayload { films: [Film!] }
            input FilmDeleteInput { filmId: Int! @field(name: "film_id") }
            type Query { x: String }
            type Mutation {
                deleteFilmsPayload(in: [FilmDeleteInput!]!): FilmsPayload @mutation(typeName: DELETE, table: "film")
            }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "deleteFilmsPayload");
                assertThat(f.reason()).contains(
                    "@table-element data field", "RETURNING carries only the primary key", "ID-typed data field");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        SERVICE_MUTATION_FIELD_NAME_OVERRIDE_TEXT_ENUM(
            "R53 + R229: argMapping override + text-mapped enum arg → Direct (graphql-java translates at the boundary; the Java-side map retired)",
            """
            enum SortDir {
                ASC @field(name: "asc")
                DESC @field(name: "desc")
            }
            type FilmDetails { title: String }
            type Query { x: String }
            type Mutation {
                runWithEnumOverride(direction: SortDir): FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runWithEnumOverride", argMapping: "mode: direction"})
            }
            """,
            schema -> {
                var f = (MutationField.MutationServiceRecordField) schema.field("Mutation", "runWithEnumOverride");
                var entry = (no.sikt.graphitron.rewrite.model.MappingEntry.FromArg)
                    f.serviceMethodCall().methodArgs().get(0);
                assertThat(entry.javaName()).isEqualTo("mode");
                var scalar = (no.sikt.graphitron.rewrite.model.ValueShape.Scalar) entry.shape();
                assertThat(scalar.sdlPath().outerArgName()).isEqualTo("direction");
                // The schema emit registers.value("asc") /.value("desc") on the
                // GraphQLEnumValueDefinition, so graphql-java hands the runtime form directly to
                // env.getArgument(...). The Java method receives the DB string already; no map
                // lookup needed.
                assertThat(scalar.leafTransform())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.Direct.class);
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationServiceRecordField.class); }
        },

        SERVICE_MUTATION_FIELD_NAME_OVERRIDE_ON_ARG(
            "R53: argMapping on @service binds a GraphQL arg to a differently-named Java parameter",
            """
            input TestDtoStub { id: ID }
            type FilmDetails { title: String }
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
                var firstEntry = (no.sikt.graphitron.rewrite.model.MappingEntry.FromArg)
                    f.serviceMethodCall().methodArgs().get(0);
                assertThat(firstEntry.javaName()).isEqualTo("inputs");
                // The renamed SDL arg "input" sits at the outer path; ListOf wraps the bean since
                // the SDL type is [TestDtoStub!]!.
                var firstList = (no.sikt.graphitron.rewrite.model.ValueShape.ListOf) firstEntry.shape();
                assertThat(firstList.sdlPath().outerArgName()).isEqualTo("input");
                var secondEntry = (no.sikt.graphitron.rewrite.model.MappingEntry.FromArg)
                    f.serviceMethodCall().methodArgs().get(1);
                assertThat(secondEntry.javaName()).isEqualTo("dryRun");
                var secondScalar = (no.sikt.graphitron.rewrite.model.ValueShape.Scalar) secondEntry.shape();
                assertThat(secondScalar.sdlPath().outerArgName()).isEqualTo("dryRun");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationServiceRecordField.class); }
        },

        // ===== Input-bean classification =====

        SERVICE_MUTATION_FIELD_INPUT_BEAN_SINGULAR(
            "R150: @service taking a single consumer-authored record bean → ParamSource.Arg with InputBean extraction",
            """
            enum TestInputBeanEnum { LOW HIGH }
            input TestInputNested { key: String, value: String }
            input TestInputBean { title: String, rating: TestInputBeanEnum, nested: [TestInputNested!] }
            type FilmDetails { title: String }
            type Query { x: String }
            type Mutation {
                runWithInputBean(input: TestInputBean): FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runWithInputBean"})
            }
            """,
            schema -> {
                var f = (MutationField.MutationServiceRecordField) schema.field("Mutation", "runWithInputBean");
                var entry = (no.sikt.graphitron.rewrite.model.MappingEntry.FromArg)
                    f.serviceMethodCall().methodArgs().get(0);
                var rec = (no.sikt.graphitron.rewrite.model.ValueShape.RecordInput) entry.shape();
                assertThat(rec.javaClass().simpleName()).isEqualTo("TestInputBean");
                // Bindings cover all three SDL fields, in record component order.
                assertThat(rec.fields()).extracting(no.sikt.graphitron.rewrite.model.ValueShape.FieldBinding::sdlFieldName)
                    .containsExactly("title", "rating", "nested");
                // Enum leaf — EnumValueOf for the rating field.
                var ratingScalar = (no.sikt.graphitron.rewrite.model.ValueShape.Scalar) rec.fields().get(1).shape();
                assertThat(ratingScalar.leafTransform())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.EnumValueOf.class);
                // Nested-bean leaf — recursive RecordInput inside ListOf for the nested list.
                var nestedList = (no.sikt.graphitron.rewrite.model.ValueShape.ListOf) rec.fields().get(2).shape();
                assertThat(nestedList.elementShape())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.ValueShape.RecordInput.class);
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationServiceRecordField.class); }
        },

        SERVICE_MUTATION_FIELD_INPUT_BEAN_LIST(
            "R150: @service taking List<RecordBean> resolves the same InputBean extraction; helper-name choice is the emitter's job",
            """
            enum TestInputBeanEnum { LOW HIGH }
            input TestInputNested { key: String, value: String }
            input TestInputBean { title: String, rating: TestInputBeanEnum, nested: [TestInputNested!] }
            type FilmDetails { title: String }
            type Query { x: String }
            type Mutation {
                runWithInputBeans(inputs: [TestInputBean!]!): FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runWithInputBeans"})
            }
            """,
            schema -> {
                var f = (MutationField.MutationServiceRecordField) schema.field("Mutation", "runWithInputBeans");
                var entry = (no.sikt.graphitron.rewrite.model.MappingEntry.FromArg)
                    f.serviceMethodCall().methodArgs().get(0);
                var listOf = (no.sikt.graphitron.rewrite.model.ValueShape.ListOf) entry.shape();
                assertThat(listOf.javaType().toString()).startsWith("java.util.List<");
                var elt = (no.sikt.graphitron.rewrite.model.ValueShape.RecordInput) listOf.elementShape();
                assertThat(elt.javaClass().simpleName()).isEqualTo("TestInputBean");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationServiceRecordField.class); }
        },

        // ===== Input-bean primitive-field boxing =====

        SERVICE_MUTATION_FIELD_INPUT_BEAN_PRIMITIVE_RECORD(
            "R155: record bean with a primitive int component → FieldBinding.javaElementTypeName boxes to java.lang.Integer",
            """
            input TestInputBeanWithPrimitive { n: Int!, s: String }
            type FilmDetails { title: String }
            type Query { x: String }
            type Mutation {
                runWithInputBeanPrimitive(input: TestInputBeanWithPrimitive): FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runWithInputBeanPrimitive"})
            }
            """,
            schema -> {
                var f = (MutationField.MutationServiceRecordField) schema.field("Mutation", "runWithInputBeanPrimitive");
                var entry = (no.sikt.graphitron.rewrite.model.MappingEntry.FromArg)
                    f.serviceMethodCall().methodArgs().get(0);
                var rec = (no.sikt.graphitron.rewrite.model.ValueShape.RecordInput) entry.shape();
                var nField = rec.fields().stream()
                    .filter(fb -> fb.sdlFieldName().equals("n")).findFirst().orElseThrow();
                var nScalar = (no.sikt.graphitron.rewrite.model.ValueShape.Scalar) nField.shape();
                assertThat(nScalar.leafTransform())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.Direct.class);
                assertThat(nScalar.javaType().toString())
                    .as("primitive int component must box to java.lang.Integer so ClassName.bestGuess succeeds")
                    .isEqualTo("java.lang.Integer");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationServiceRecordField.class); }
        },

        SERVICE_MUTATION_FIELD_INPUT_JAVABEAN_PRIMITIVE_BOOLEAN(
            "R155: JavaBean with a void setActive(boolean) setter → FieldBinding.javaElementTypeName boxes to java.lang.Boolean",
            """
            input TestInputJavaBeanWithBoolean { active: Boolean! }
            type FilmDetails { title: String }
            type Query { x: String }
            type Mutation {
                runWithInputJavaBeanBoolean(input: TestInputJavaBeanWithBoolean): FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runWithInputJavaBeanBoolean"})
            }
            """,
            schema -> {
                var f = (MutationField.MutationServiceRecordField) schema.field("Mutation", "runWithInputJavaBeanBoolean");
                var entry = (no.sikt.graphitron.rewrite.model.MappingEntry.FromArg)
                    f.serviceMethodCall().methodArgs().get(0);
                var javaBean = (no.sikt.graphitron.rewrite.model.ValueShape.JavaBeanInput) entry.shape();
                var activeField = javaBean.fields().stream()
                    .filter(fb -> fb.sdlFieldName().equals("active")).findFirst().orElseThrow();
                var activeScalar = (no.sikt.graphitron.rewrite.model.ValueShape.Scalar) activeField.shape();
                assertThat(activeScalar.leafTransform())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.Direct.class);
                assertThat(activeScalar.javaType().toString())
                    .as("primitive boolean setter must box to java.lang.Boolean so ClassName.bestGuess succeeds")
                    .isEqualTo("java.lang.Boolean");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationServiceRecordField.class); }
        },

        // ===== @field(name:) Java-member binding on input beans =====

        SERVICE_MUTATION_FIELD_INPUT_BEAN_FIELD_RENAMED_RECORD(
            "R200: @field(name:) bridges a record component whose name diverges from the SDL field name — javaFieldName carries the directive value, sdlFieldName stays the SDL (Map-key) name",
            """
            input TestInputBeanRenamedInput { title: String @field(name: "heading"), rating: Int @field(name: "score") }
            type FilmDetails { title: String }
            type Query { x: String }
            type Mutation {
                runWithRenamedRecord(input: TestInputBeanRenamedInput): FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runWithRenamedRecord"})
            }
            """,
            schema -> {
                var f = (MutationField.MutationServiceRecordField) schema.field("Mutation", "runWithRenamedRecord");
                var entry = (no.sikt.graphitron.rewrite.model.MappingEntry.FromArg)
                    f.serviceMethodCall().methodArgs().get(0);
                var rec = (no.sikt.graphitron.rewrite.model.ValueShape.RecordInput) entry.shape();
                assertThat(rec.javaClass().simpleName()).isEqualTo("TestInputBeanRenamed");
                // SDL field name stays the wire/Map key; @field(name:) supplies the component name.
                assertThat(rec.fields()).extracting(no.sikt.graphitron.rewrite.model.ValueShape.FieldBinding::sdlFieldName)
                    .containsExactly("title", "rating");
                assertThat(rec.fields()).extracting(no.sikt.graphitron.rewrite.model.ValueShape.FieldBinding::javaFieldName)
                    .containsExactly("heading", "score");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationServiceRecordField.class); }
        },

        SERVICE_MUTATION_FIELD_INPUT_BEAN_FIELD_RENAMED_JAVABEAN(
            "R200: @field(name:) bridges a JavaBean setter property whose name diverges from the SDL field name — the binding key is the directive value, resolving to setHeading/setScore",
            """
            input TestInputJavaBeanRenamedInput { title: String @field(name: "heading"), rating: Int @field(name: "score") }
            type FilmDetails { title: String }
            type Query { x: String }
            type Mutation {
                runWithRenamedJavaBean(input: TestInputJavaBeanRenamedInput): FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runWithRenamedJavaBean"})
            }
            """,
            schema -> {
                var f = (MutationField.MutationServiceRecordField) schema.field("Mutation", "runWithRenamedJavaBean");
                var entry = (no.sikt.graphitron.rewrite.model.MappingEntry.FromArg)
                    f.serviceMethodCall().methodArgs().get(0);
                var javaBean = (no.sikt.graphitron.rewrite.model.ValueShape.JavaBeanInput) entry.shape();
                assertThat(javaBean.fields()).extracting(no.sikt.graphitron.rewrite.model.ValueShape.FieldBinding::sdlFieldName)
                    .containsExactly("title", "rating");
                assertThat(javaBean.fields()).extracting(no.sikt.graphitron.rewrite.model.ValueShape.FieldBinding::javaFieldName)
                    .containsExactly("heading", "score");
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

    @Test
    @ProjectionFor({QueryField.QueryTableField.class})
    void queryTableProjectionCarriesTableNameAndLookupFlag() {
        // QueryTableField — isLookup false on a plain list query.
        var s1 = buildSnapshot("""
            type Film @table(name: "film") { title: String }
            type Query { films: [Film!]! }
            """);
        var plain = (FieldClassification.QueryTable) s1.fieldClassificationsByCoord().get("Query.films");
        assertThat(plain.tableName()).isEqualToIgnoringCase("film");
        assertThat(plain.isLookup()).isFalse();

        // QueryTableField — @lookupKey on the arg flips the flag.
        var s2 = buildSnapshot("""
            type Film @table(name: "film") { title: String }
            type Query { filmById(film_id: ID! @lookupKey): Film }
            """);
        var lookup = (FieldClassification.QueryTable) s2.fieldClassificationsByCoord().get("Query.filmById");
        assertThat(lookup.tableName()).isEqualToIgnoringCase("film");
        assertThat(lookup.isLookup()).isTrue();
    }

    @Test
    void queryRoutineProjectionCarriesRoutineCoordinates() {
        // A @routine-sourced QueryTableField projects onto the method-backed RoutineBacked
        // classification (the source-axis fork of the QueryTable arm), with className = the
        // generated Routines class. The routine resolves against the sakila-db fixture catalog
        // (public.tilganger_for_feidebruker_med_fs_fiktivt_fnr).
        var snapshot = buildSnapshot("""
            type Tilgang @table(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr") {
              organisasjonskode: Int
              rollekode: String
            }
            type Query {
              tilganger(env: String!, serviceId: String!, feideId: String!): [Tilgang!]!
                @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr", argMapping: "pEnv: env, pServiceId: serviceId, pFeideId: feideId")
            }
            """);
        var p = (FieldClassification.RoutineBacked) snapshot.fieldClassificationsByCoord().get("Query.tilganger");
        assertThat(p.tableName()).isEqualToIgnoringCase("tilganger_for_feidebruker_med_fs_fiktivt_fnr");
        assertThat(p.methodName()).isEqualTo("tilgangerForFeidebrukerMedFsFiktivtFnr");
        assertThat(p.methodClassName()).endsWith(".Routines");
    }

    @Test
    @ProjectionFor(MutationField.MutationRoutineWriteField.class)
    void mutationRoutineWriteProjectionCarriesRoutineCoordinates() {
        // Like the routine read above, the routine write projects onto the method-backed
        // RoutineBacked classification (className = the generated Routines class; the
        // tableName is the terminus the response re-reads).
        var snapshot = buildSnapshot("""
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type Query { rental: Rental }
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): [Rental!]!
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @reference(path: [{table: "rental"}])
            }
            """);
        var p = (FieldClassification.RoutineBacked) snapshot.fieldClassificationsByCoord().get("Mutation.rentFilm");
        assertThat(p.tableName()).isEqualToIgnoringCase("rental");
        assertThat(p.methodName()).isEqualTo("rentFilm");
        assertThat(p.methodClassName()).endsWith(".Routines");
    }

    @Test
    @ProjectionFor(MutationField.MutationRoutineWriteRecordField.class)
    void mutationRoutineCarrierProjectionCarriesRoutineCoordinates() {
        // The routine carrier is still routine-backed: hover and jump-to-source route to the
        // routine's call surface exactly as on the direct-return sibling; the reported table
        // is the payload data field's target.
        var snapshot = buildSnapshot("""
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type RentFilmPayload { rental: Rental }
            type Query { rental: Rental }
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): RentFilmPayload
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            }
            """);
        var p = (FieldClassification.RoutineBacked) snapshot.fieldClassificationsByCoord().get("Mutation.rentFilm");
        assertThat(p.tableName()).isEqualToIgnoringCase("rental");
        assertThat(p.methodName()).isEqualTo("rentFilm");
        assertThat(p.methodClassName()).endsWith(".Routines");
    }

    // ===== Order-significant @routine / @reference composition =====
    // The classifier reads the ordered field-level directive applications once, enforces the
    // root-head rule, and validates the single-node chain (including columnMapping against the
    // implicit head at child positions). Chain shapes beyond the shipped root single-node
    // classify as typed Deferred until the chain build + emitters land.

    private static final String TILGANG_TYPE = """
        type Tilgang @table(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr") {
          organisasjonskode: Int
          rollekode: String
        }
        """;

    /**
     * The routine chain of a root read expected to be routine-sourced: asserts the
     * {@code RoutineResolution.Chain} arm first, so a fixture that silently classifies onto the
     * plain catalog source fails with the discriminating message rather than a cast error.
     */
    private static no.sikt.graphitron.rewrite.model.RoutineChain routineChainOf(QueryField.QueryTableField f) {
        assertThat(f.routine())
            .as("expected a routine-sourced read; the source axis resolved None")
            .isInstanceOf(no.sikt.graphitron.rewrite.model.RoutineResolution.Chain.class);
        return ((no.sikt.graphitron.rewrite.model.RoutineResolution.Chain) f.routine()).chain();
    }

    @Test
    void rootChainNotStartingWithRoutineRejectsAsStructural() {
        var schema = build(TILGANG_TYPE + """
            type Query {
              tilganger(env: String!, serviceId: String!, feideId: String!): [Tilgang!]!
                @reference(path: [{key: "some_fkey"}])
                @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr", argMapping: "pEnv: env, pServiceId: serviceId, pFeideId: feideId")
            }
            """);
        var f = (UnclassifiedField) schema.field("Query", "tilganger");
        assertThat(f.rejection()).isInstanceOf(Rejection.AuthorError.Structural.class);
        assertThat(f.reason()).contains("must start with @routine");
    }

    @Test
    void rootRoutineThenHopsChainClassifiesWithNameMatchedHop() {
        // The routine-then-hops chain: @routine supplies the FROM source, the @reference
        // hop lands the terminus. The hop out of the FK-less routine result keys by the
        // name-matched target PK: films_for_actor exposes film_id, film's PK column.
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type Query {
              recentFilms(actorId: Int!, minLength: Int!): [Film!]!
                @routine(name: "films_for_actor", argMapping: "pActorId: actorId, pMinLength: minLength")
                @reference(path: [{table: "film"}])
            }
            """);
        var f = (QueryField.QueryTableField) schema.field("Query", "recentFilms");
        var chain = routineChainOf(f);
        assertThat(chain.start().resultTable().tableName()).isEqualTo("films_for_actor");
        assertThat(f.returnType().table().tableName()).isEqualTo("film");
        assertThat(chain.hops()).hasSize(1);
        var hop = (JoinStep.Hop) chain.hops().get(0);
        assertThat(hop.targetTable().tableName()).isEqualTo("film");
        assertThat(hop.originTable().tableName()).isEqualTo("films_for_actor");
        var pairs = (On.ColumnPairs) hop.on();
        assertThat(pairs.keying()).isInstanceOf(On.Keying.NameMatchedKey.class);
        assertThat(pairs.slotCount()).isEqualTo(1);
        var slot = pairs.slots().iterator().next();
        assertThat(slot.sourceSide().sqlName()).isEqualToIgnoringCase("film_id");
        assertThat(slot.targetSide().sqlName()).isEqualToIgnoringCase("film_id");
    }

    @Test
    void rootRoutineChainTerminusMismatchRejectsAsStructural() {
        // Terminus rule for multi-node chains: the last node must be the field's @table type.
        var schema = build("""
            type Actor @table(name: "actor") { firstName: String }
            type Query {
              recentFilms(actorId: Int!, minLength: Int!): [Actor!]!
                @routine(name: "films_for_actor", argMapping: "pActorId: actorId, pMinLength: minLength")
                @reference(path: [{table: "film"}])
            }
            """);
        var f = (UnclassifiedField) schema.field("Query", "recentFilms");
        assertThat(f.rejection()).isInstanceOf(Rejection.AuthorError.Structural.class);
        assertThat(f.reason())
            .contains("resolves to table 'film'")
            .contains("the path must end on 'actor'");
    }

    @Test
    void rootRoutineChainNameMatchKeyingFailureRejectsWithCandidates() {
        // The name-match build check: the target's PK column must be exposed by SQL name on the
        // routine result. actor's PK (actor_id) is not among films_for_actor's result columns.
        var schema = build("""
            type Actor @table(name: "actor") { firstName: String }
            type Query {
              recentActors(actorId: Int!, minLength: Int!): [Actor!]!
                @routine(name: "films_for_actor", argMapping: "pActorId: actorId, pMinLength: minLength")
                @reference(path: [{table: "actor"}])
            }
            """);
        var f = (UnclassifiedField) schema.field("Query", "recentActors");
        assertThat(f.rejection()).isInstanceOf(Rejection.AuthorError.Structural.class);
        assertThat(f.reason())
            .contains("'actor_id' is not exposed by name on 'films_for_actor'")
            .contains("film_id"); // candidate hint lists the routine result's columns
    }

    @Test
    void rootChainWithTwoRoutineNodesDefersUntilLateralHopEmitLands() {
        var schema = build(TILGANG_TYPE + """
            type Query {
              tilganger(env: String!, serviceId: String!, feideId: String!): [Tilgang!]!
                @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr", argMapping: "pEnv: env, pServiceId: serviceId, pFeideId: feideId")
                @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr", argMapping: "pEnv: env, pServiceId: serviceId, pFeideId: feideId")
            }
            """);
        var f = (UnclassifiedField) schema.field("Query", "tilganger");
        assertThat(f.rejection()).isInstanceOf(Rejection.Deferred.class);
        assertThat(f.reason()).contains("more than one routine node");
    }

    @Test
    void repeatedReferenceApplicationsComposeOneChain() {
        // The chain rule for plain @reference: repeated field-level applications concatenate
        // their path elements in authored order over one running source, so the composed chain
        // is just a longer path to every downstream consumer (no routine node, no new leaf).
        var schema = build("""
            type Actor @table(name: "actor") { firstName: String }
            type Film @table(name: "film") {
              castActors: [Actor!]
                @reference(path: [{key: "film_actor_film_id_fkey"}])
                @reference(path: [{key: "film_actor_actor_id_fkey"}])
                @defaultOrder(primaryKey: true)
            }
            type Query { film: Film }
            """);
        var f = (ChildField.TableField) schema.field("Film", "castActors");
        assertThat(f.joinPath()).hasSize(2);
        assertThat(((JoinStep.Hop) f.joinPath().get(0)).targetTable().tableName()).isEqualTo("film_actor");
        assertThat(((JoinStep.Hop) f.joinPath().get(1)).targetTable().tableName()).isEqualTo("actor");
        assertThat(f.returnType().table().tableName()).isEqualTo("actor");
    }

    @Test
    void repeatedReferenceApplicationsComposeOneChainOnScalarField() {
        // Same rule on a scalar carrier: the concatenated chain lands the ordinary
        // ColumnBackedReferenceField with the terminal column resolved on the composed terminus.
        var schema = build("""
            type Film @table(name: "film") {
              anActorName: String
                @field(name: "first_name")
                @reference(path: [{key: "film_actor_film_id_fkey"}])
                @reference(path: [{key: "film_actor_actor_id_fkey"}])
            }
            type Query { film: Film }
            """);
        var f = (ChildField.ColumnBackedReferenceField) schema.field("Film", "anActorName");
        assertThat(f.joinPath()).hasSize(2);
        assertThat(f.columns().get(0).sqlName()).isEqualToIgnoringCase("first_name");
    }

    @Test
    void elementLessReferenceApplicationInChainRejectsAsStructural() {
        // Element-less inference resolves the FK between the field's endpoints; inside a
        // multi-application chain an application has no endpoints of its own.
        var schema = build("""
            type Actor @table(name: "actor") { firstName: String }
            type Film @table(name: "film") {
              castActors: [Actor!]
                @reference(path: [{key: "film_actor_film_id_fkey"}])
                @reference(path: [])
                @defaultOrder(primaryKey: true)
            }
            type Query { film: Film }
            """);
        var f = (UnclassifiedField) schema.field("Film", "castActors");
        assertThat(f.rejection()).isInstanceOf(Rejection.AuthorError.Structural.class);
        assertThat(f.reason()).contains("must carry at least one path element");
    }

    @Test
    void childRoutineThenHopsChainClassifiesWithNameMatchedHop() {
        // The child mirror of the root routine-then-hops chain: the lateral routine node heads
        // the chain (correlated on the implicit head), the @reference hop lands the terminus on
        // the film catalog table, keyed by the name-matched target PK.
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type Actor @table(name: "actor") {
              recentFilms(minLength: Int!): [Film!]
                @routine(name: "films_for_actor",
                         argMapping: "pMinLength: minLength",
                         columnMapping: "pActorId: actor_id")
                @reference(path: [{table: "film"}])
                @defaultOrder(primaryKey: true)
            }
            type Query { actors: [Actor] }
            """);
        var f = (ChildField.TableField) schema.field("Actor", "recentFilms");
        assertThat(f.joinPath()).hasSize(2);
        var routineHop = (JoinStep.Hop) f.joinPath().get(0);
        assertThat(routineHop.on()).isInstanceOf(On.Lateral.class);
        assertThat(routineHop.target()).isInstanceOf(TableExpr.RoutineCall.class);
        assertThat(routineHop.originTable().tableName()).isEqualTo("actor");
        var filmHop = (JoinStep.Hop) f.joinPath().get(1);
        assertThat(filmHop.targetTable().tableName()).isEqualTo("film");
        assertThat(filmHop.originTable().tableName()).isEqualTo("films_for_actor");
        var pairs = (On.ColumnPairs) filmHop.on();
        assertThat(pairs.keying()).isInstanceOf(On.Keying.NameMatchedKey.class);
        assertThat(f.parentCorrelation()).isInstanceOf(ParentCorrelation.OnLateralArgs.class);
    }

    @Test
    void childHopsThenRoutineChainBindsColumnMappingAgainstPreviousNode() {
        // The hops-then-routine chain: the FK hop precedes the routine, so columnMapping binds
        // against the previous hop's node (film_actor), not the implicit head (film); this is the
        // order-significance under test. The routine result is the terminus.
        var schema = build("""
            type ActorFilm @table(name: "films_for_actor") {
              filmId: Int @field(name: "FILM_ID")
            }
            type Film @table(name: "film") {
              castFilms(minLength: Int!): [ActorFilm!]
                @reference(path: [{table: "film_actor"}])
                @routine(name: "films_for_actor",
                         argMapping: "pMinLength: minLength",
                         columnMapping: "pActorId: actor_id")
                @defaultOrder(fields: [{name: "film_id"}])
            }
            type Query { film: Film }
            """);
        var f = (ChildField.TableField) schema.field("Film", "castFilms");
        assertThat(f.joinPath()).hasSize(2);
        var junctionHop = (JoinStep.Hop) f.joinPath().get(0);
        assertThat(junctionHop.targetTable().tableName()).isEqualTo("film_actor");
        assertThat(junctionHop.on()).isInstanceOf(On.ColumnPairs.class);
        assertThat(f.parentCorrelation()).isInstanceOf(ParentCorrelation.OnFkSlots.class);
        var routineHop = (JoinStep.Hop) f.joinPath().get(1);
        assertThat(routineHop.on()).isInstanceOf(On.Lateral.class);
        assertThat(routineHop.originTable().tableName()).isEqualTo("film_actor");
        var rc = (TableExpr.RoutineCall) routineHop.target();
        var pActorId = rc.routine().argBindings().stream()
            .filter(b -> b.routineParamName().equals("pActorId")).findFirst().orElseThrow();
        assertThat(pActorId.source()).isInstanceOf(ParamSource.SourceColumn.class);
        assertThat(((ParamSource.SourceColumn) pActorId.source()).column().sqlName())
            .isEqualToIgnoringCase("actor_id");
    }

    @Test
    void childSandwichChainClassifiesWithRoutineStrictlyBetweenTables() {
        // The sandwich: hops in, lateral routine, name-matched hop out. Three nodes after the
        // implicit head, the routine strictly between catalog tables.
        var schema = build("""
            type Film @table(name: "film") {
              castRecentFilms(minLength: Int!): [Film!]
                @reference(path: [{table: "film_actor"}])
                @routine(name: "films_for_actor",
                         argMapping: "pMinLength: minLength",
                         columnMapping: "pActorId: actor_id")
                @reference(path: [{table: "film"}])
                @defaultOrder(primaryKey: true)
            }
            type Query { film: Film }
            """);
        var f = (ChildField.TableField) schema.field("Film", "castRecentFilms");
        assertThat(f.joinPath()).hasSize(3);
        assertThat(((JoinStep.Hop) f.joinPath().get(0)).on()).isInstanceOf(On.ColumnPairs.class);
        assertThat(((JoinStep.Hop) f.joinPath().get(1)).on()).isInstanceOf(On.Lateral.class);
        var tailHop = (JoinStep.Hop) f.joinPath().get(2);
        assertThat(tailHop.targetTable().tableName()).isEqualTo("film");
        assertThat(((On.ColumnPairs) tailHop.on()).keying()).isInstanceOf(On.Keying.NameMatchedKey.class);
    }

    @Test
    void childRoutineTerminusMismatchRejectsAsStructural() {
        // Hops-then-routine whose field @table type is not the routine's result table.
        var schema = build("""
            type Actor @table(name: "actor") { firstName: String }
            type Film @table(name: "film") {
              castFilms(minLength: Int!): [Actor!]
                @reference(path: [{table: "film_actor"}])
                @routine(name: "films_for_actor",
                         argMapping: "pMinLength: minLength",
                         columnMapping: "pActorId: actor_id")
            }
            type Query { film: Film }
            """);
        var f = (UnclassifiedField) schema.field("Film", "castFilms");
        assertThat(f.rejection()).isInstanceOf(Rejection.AuthorError.Structural.class);
        assertThat(f.reason()).contains("does not match the routine's result table");
    }

    @Test
    void childChainColumnMappingUnknownColumnListsPreviousNodeCandidates() {
        // columnMapping resolves against the running source at the routine's position: here
        // the previous node is film_actor, so the candidate hint lists ITS columns, not the
        // implicit head's.
        var schema = build("""
            type ActorFilm @table(name: "films_for_actor") {
              filmId: Int @field(name: "FILM_ID")
            }
            type Film @table(name: "film") {
              castFilms(minLength: Int!): [ActorFilm!]
                @reference(path: [{table: "film_actor"}])
                @routine(name: "films_for_actor",
                         argMapping: "pMinLength: minLength",
                         columnMapping: "pActorId: title")
                @defaultOrder(fields: [{name: "film_id"}])
            }
            type Query { film: Film }
            """);
        var f = (UnclassifiedField) schema.field("Film", "castFilms");
        assertThat(f.rejection()).isInstanceOf(Rejection.AuthorError.UnknownName.class);
        assertThat(f.reason())
            .contains("not a column of the previous node ('film_actor')")
            .contains("actor_id"); // candidate hint lists the previous node's columns
    }

    @Test
    void childRoutineWithValidColumnMappingClassifiesAsLateralTableField() {
        // The correlated single-node chain: pEnv binds to the parent's `title` column
        // (columnMapping), the remaining params to field arguments. Classifies onto the inline
        // TableField leaf carrying the routine-node chain: one Hop whose target is
        // TableExpr.RoutineCall, joined On.Lateral, correlated via OnLateralArgs.
        var schema = build(TILGANG_TYPE + """
            type Film @table(name: "film") {
              title: String
              tilganger(serviceId: String!, feideId: String!): [Tilgang!]
                @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr",
                         argMapping: "pServiceId: serviceId, pFeideId: feideId",
                         columnMapping: "pEnv: title")
            }
            type Query { film: Film }
            """);
        var f = (ChildField.TableField) schema.field("Film", "tilganger");
        assertThat(f.joinPath()).hasSize(1);
        var hop = (JoinStep.Hop) f.joinPath().get(0);
        assertThat(hop.on()).isInstanceOf(On.Lateral.class);
        var rc = (TableExpr.RoutineCall) hop.target();
        assertThat(rc.resultTable().tableName()).isEqualTo("tilganger_for_feidebruker_med_fs_fiktivt_fnr");
        assertThat(hop.targetTable().tableName()).isEqualTo("tilganger_for_feidebruker_med_fs_fiktivt_fnr");
        assertThat(hop.originTable().tableName()).isEqualTo("film");
        assertThat(f.parentCorrelation()).isInstanceOf(ParentCorrelation.OnLateralArgs.class);
        // Binding shapes: the column-mapped param carries the resolved parent column, the
        // argument-mapped params ride ParamSource.Arg.
        var bySource = rc.routine().argBindings();
        assertThat(bySource).hasSize(3);
        var pEnv = bySource.stream().filter(b -> b.routineParamName().equals("pEnv")).findFirst().orElseThrow();
        assertThat(pEnv.source()).isInstanceOf(ParamSource.SourceColumn.class);
        assertThat(((ParamSource.SourceColumn) pEnv.source()).column().sqlName()).isEqualTo("title");
        var pServiceId = bySource.stream().filter(b -> b.routineParamName().equals("pServiceId")).findFirst().orElseThrow();
        assertThat(pServiceId.source()).isInstanceOf(ParamSource.Arg.class);
    }

    @Test
    void childRoutineWithoutColumnMappingAlsoClassifiesAsLateralTableField() {
        // The uncorrelated child single-node chain: every param binds from field arguments.
        // Same leaf and chain shape as the correlated case — the routine call is the row
        // source either way; the emitters fork on the presence of SourceColumn bindings.
        var schema = build(TILGANG_TYPE + """
            type Film @table(name: "film") {
              title: String
              tilganger(env: String!, serviceId: String!, feideId: String!): [Tilgang!]
                @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr",
                         argMapping: "pEnv: env, pServiceId: serviceId, pFeideId: feideId")
            }
            type Query { film: Film }
            """);
        var f = (ChildField.TableField) schema.field("Film", "tilganger");
        var hop = (JoinStep.Hop) f.joinPath().get(0);
        assertThat(hop.target()).isInstanceOf(TableExpr.RoutineCall.class);
        assertThat(hop.on()).isInstanceOf(On.Lateral.class);
    }

    // ===== @routine argMapping resolves on the shared seam =====
    //
    // The right-hand side of an argMapping entry names a GraphQL slot and walks into it, and that
    // namespace is the same at every directive, so @routine resolves through ArgBindingMap.of
    // exactly as @service and @condition do. These pin the capability the routing lands
    // (dot-paths), the shared wording it inherits, and the three rejections that only exist
    // because routing removed the side effect that used to surface a left-side typo.

    private static final String TILGANG_INPUT = """
        input TilgangInput {
          env: String!
          serviceId: String!
        }
        """;

    @Test
    void routineDotPathArgMappingLandsPathExprChain() {
        // The capability: a routine IN parameter binds to a field inside a wrapper input, which
        // the flat-slot membership check used to reject outright.
        var schema = build(TILGANG_TYPE + TILGANG_INPUT + """
            type Query {
              tilganger(input: TilgangInput!, feideId: String!): [Tilgang!]
                @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr",
                         argMapping: "pEnv: input.env, pServiceId: input.serviceId, pFeideId: feideId")
            }
            """);
        var chain = routineChainOf((QueryField.QueryTableField) schema.field("Query", "tilganger"));
        var pEnv = chain.start().routine().argBindings().stream()
            .filter(b -> b.routineParamName().equals("pEnv")).findFirst().orElseThrow();
        var path = ((ParamSource.Arg) pEnv.source()).path();
        assertThat(path).isInstanceOf(PathExpr.Step.class);
        assertThat(path.asString()).isEqualTo("input.env");
        assertThat(path.headName()).isEqualTo("input");
        // The flat sibling in the same entry list still lands a bare Head.
        var pFeideId = chain.start().routine().argBindings().stream()
            .filter(b -> b.routineParamName().equals("pFeideId")).findFirst().orElseThrow();
        assertThat(((ParamSource.Arg) pFeideId.source()).path()).isInstanceOf(PathExpr.Head.class);
    }

    @Test
    void routineArgMappingUnknownHeadSegmentRejectsWithTheSharedWording() {
        // Validator-mirrors-classifier: the head slot is unknown, and the message is the shared
        // one every directive renders, listing the arguments actually in scope. The retired
        // routine-only rejection named no candidates at all.
        var schema = build(TILGANG_TYPE + TILGANG_INPUT + """
            type Query {
              tilganger(input: TilgangInput!, feideId: String!): [Tilgang!]
                @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr",
                         argMapping: "pEnv: inputs.env, pServiceId: input.serviceId, pFeideId: feideId")
            }
            """);
        var f = (UnclassifiedField) schema.field("Query", "tilganger");
        assertThat(f.reason())
            .contains("references GraphQL argument 'inputs'")
            .contains("available arguments are")
            .contains("'input'");
    }

    @Test
    void routineArgMappingUnknownTailSegmentRejectsWithCandidateHint() {
        // The schema walk's own rejection, reached from the routine side for the first time:
        // the head resolves, a tail segment does not, and the message names the input type plus
        // the near-miss candidate.
        var schema = build(TILGANG_TYPE + TILGANG_INPUT + """
            type Query {
              tilganger(input: TilgangInput!, feideId: String!): [Tilgang!]
                @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr",
                         argMapping: "pEnv: input.envv, pServiceId: input.serviceId, pFeideId: feideId")
            }
            """);
        var f = (UnclassifiedField) schema.field("Query", "tilganger");
        assertThat(f.reason())
            .contains("segment 'envv' does not exist on input type 'TilgangInput'")
            .contains("env");
    }

    @Test
    void routineArgMappingNamingNonParameterRejects() {
        // The left-hand-side check the routine side never had. Before routing, a misspelled
        // target was silently dropped and the author was told something about the right-hand
        // side of an entry they did not write.
        var schema = build(TILGANG_TYPE + """
            type Query {
              tilganger(env: String!, serviceId: String!, feideId: String!): [Tilgang!]
                @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr",
                         argMapping: "pEnvv: env, pServiceId: serviceId, pFeideId: feideId")
            }
            """);
        var f = (UnclassifiedField) schema.field("Query", "tilganger");
        assertThat(f.reason())
            .contains("argMapping names parameter 'pEnvv'")
            .contains("is not an IN parameter")
            .contains("pEnv"); // candidate hint points at the parameter that was meant
    }

    @Test
    void routineParameterLeftUnboundRejectsListingTheAvailableArguments() {
        // The other half of the left-hand-side pair: nothing binds pEnv, so the rejection names
        // the parameter and the arguments that were available to bind it.
        var schema = build(TILGANG_TYPE + """
            type Query {
              tilganger(serviceId: String!, feideId: String!): [Tilgang!]
                @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr",
                         argMapping: "pServiceId: serviceId, pFeideId: feideId")
            }
            """);
        var f = (UnclassifiedField) schema.field("Query", "tilganger");
        assertThat(f.reason())
            .contains("parameter 'pEnv' has no binding")
            .contains("available arguments are")
            .contains("'serviceId'");
    }

    @Test
    void routineParameterBoundToInputObjectLeafRejectsNamingTheType() {
        // The reported footgun: binding a whole input object to a scalar IN parameter passed
        // validation and emitted a cast that threw at request time on the argument LinkedHashMap.
        // The wire-coercion gate has no opinion on a non-scalar leaf, so the routine side owns
        // this rejection; a jOOQ IN parameter has no bean concept to instantiate.
        var schema = build(TILGANG_TYPE + TILGANG_INPUT + """
            type Query {
              tilganger(input: TilgangInput!, feideId: String!): [Tilgang!]
                @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr",
                         argMapping: "pEnv: input, pServiceId: input.serviceId, pFeideId: feideId")
            }
            """);
        var f = (UnclassifiedField) schema.field("Query", "tilganger");
        assertThat(f.reason())
            .contains("parameter 'pEnv' binds to 'input'")
            .contains("TilgangInput")
            .contains("not a scalar or enum");
    }

    @Test
    void splitQueryOnCorrelatedRoutineChildClassifiesAsBatchedTableField() {
        // Batched form: @splitQuery forces the keyed re-query anchor, and a routine-headed
        // chain's batch key IS the routine's column-bound inputs — the SourceColumn bindings
        // ride the parentInput VALUES table and the lateral call reads them off it directly,
        // with no correlation JOIN predicate (design note on deriveSplitQuerySource).
        var schema = build(TILGANG_TYPE + """
            type Film @table(name: "film") {
              title: String
              tilganger(serviceId: String!, feideId: String!): [Tilgang!] @splitQuery
                @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr",
                         argMapping: "pServiceId: serviceId, pFeideId: feideId",
                         columnMapping: "pEnv: title")
            }
            type Query { film: Film }
            """);
        var f = (BatchedTableField) schema.field("Film", "tilganger");
        assertThat(f.joinPath()).hasSize(1);
        var hop = (JoinStep.Hop) f.joinPath().get(0);
        assertThat(hop.on()).isInstanceOf(On.Lateral.class);
        assertThat(hop.target()).isInstanceOf(TableExpr.RoutineCall.class);
        assertThat(f.parentCorrelation()).isInstanceOf(ParentCorrelation.OnLateralArgs.class);
        assertThat(f.sourceKey().columns()).extracting(ColumnRef::sqlName).containsExactly("title");
    }

    @Test
    void splitQueryOnUncorrelatedRoutineChildRejectsAsDirectiveConflict() {
        // An uncorrelated routine has nothing to key the batch on: @splitQuery demands a key
        // the field's shape cannot supply — a contradiction, not a capability gap.
        var schema = build(TILGANG_TYPE + """
            type Film @table(name: "film") {
              title: String
              tilganger(env: String!, serviceId: String!, feideId: String!): [Tilgang!] @splitQuery
                @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr",
                         argMapping: "pEnv: env, pServiceId: serviceId, pFeideId: feideId")
            }
            type Query { film: Film }
            """);
        var f = (UnclassifiedField) schema.field("Film", "tilganger");
        assertThat(f.rejection()).isInstanceOf(Rejection.InvalidSchema.DirectiveConflict.class);
        assertThat(f.reason()).contains("nothing to key the batch on");
    }

    @Test
    void splitQueryOnHopsThenRoutineChainKeysOnFirstHopSlots() {
        // When hops precede the routine, the step-0 correlation is the ordinary OnFkSlots and
        // the batch key stays the first hop's source-side columns; the mid-chain lateral reads
        // the previous hop's alias inside the batch query — no key rule change.
        var schema = build("""
            type ActorFilm @table(name: "films_for_actor") {
              filmId: Int @field(name: "FILM_ID")
            }
            type Film @table(name: "film") {
              castFilms(minLength: Int!): [ActorFilm!] @splitQuery
                @reference(path: [{table: "film_actor"}])
                @routine(name: "films_for_actor",
                         argMapping: "pMinLength: minLength",
                         columnMapping: "pActorId: actor_id")
                @defaultOrder(fields: [{name: "film_id"}])
            }
            type Query { film: Film }
            """);
        var f = (BatchedTableField) schema.field("Film", "castFilms");
        assertThat(f.joinPath()).hasSize(2);
        assertThat(f.parentCorrelation()).isInstanceOf(ParentCorrelation.OnFkSlots.class);
        assertThat(f.sourceKey().columns()).extracting(ColumnRef::sqlName).containsExactly("film_id");
        assertThat(((JoinStep.Hop) f.joinPath().get(1)).on()).isInstanceOf(On.Lateral.class);
    }

    @Test
    void lookupKeyOnRoutineChildStillDefers() {
        var schema = build(TILGANG_TYPE + """
            type Film @table(name: "film") {
              title: String
              tilganger(serviceId: String!, feideId: [String!]! @lookupKey): [Tilgang!] @splitQuery
                @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr",
                         argMapping: "pServiceId: serviceId, pFeideId: feideId",
                         columnMapping: "pEnv: title")
            }
            type Query { film: Film }
            """);
        var f = (UnclassifiedField) schema.field("Film", "tilganger");
        assertThat(f.rejection()).isInstanceOf(Rejection.Deferred.class);
        assertThat(f.reason()).contains("@lookupKey on a routine-backed child field");
    }

    @Test
    void childRoutineColumnMappingUnknownColumnRejectsWithCandidates() {
        var schema = build(TILGANG_TYPE + """
            type Film @table(name: "film") {
              title: String
              tilganger(serviceId: String!, feideId: String!): [Tilgang!]
                @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr",
                         argMapping: "pServiceId: serviceId, pFeideId: feideId",
                         columnMapping: "pEnv: no_such_column")
            }
            type Query { film: Film }
            """);
        var f = (UnclassifiedField) schema.field("Film", "tilganger");
        assertThat(f.rejection()).isInstanceOf(Rejection.AuthorError.UnknownName.class);
        assertThat(f.reason())
            .contains("not a column of the previous node")
            .contains("title"); // candidate hint lists the parent table's columns
        assertThat(f.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
    }

    @Test
    void rootRoutineWithColumnMappingRejectsAsStructural() {
        var schema = build(TILGANG_TYPE + """
            type Query {
              tilganger(serviceId: String!, feideId: String!): [Tilgang!]!
                @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr",
                         argMapping: "pServiceId: serviceId, pFeideId: feideId",
                         columnMapping: "pEnv: title")
            }
            """);
        var f = (UnclassifiedField) schema.field("Query", "tilganger");
        assertThat(f.rejection()).isInstanceOf(Rejection.AuthorError.Structural.class);
        assertThat(f.reason()).contains("a root chain's head has none");
    }

    @Test
    void routineParamInBothMappingsRejectsAsStructural() {
        var schema = build(TILGANG_TYPE + """
            type Film @table(name: "film") {
              title: String
              tilganger(env: String!, serviceId: String!, feideId: String!): [Tilgang!]
                @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr",
                         argMapping: "pEnv: env, pServiceId: serviceId, pFeideId: feideId",
                         columnMapping: "pEnv: title")
            }
            type Query { film: Film }
            """);
        var f = (UnclassifiedField) schema.field("Film", "tilganger");
        assertThat(f.rejection()).isInstanceOf(Rejection.AuthorError.Structural.class);
        assertThat(f.reason()).contains("exactly one source");
    }

    @Test
    void orderByArgumentOnRoutineFieldDefersAsCapabilityGap() {
        var schema = build(TILGANG_TYPE + """
            enum TilgangOrder { ROLLE }
            type Query {
              tilganger(env: String!, serviceId: String!, feideId: String!,
                        orderBy: TilgangOrder @orderBy): [Tilgang!]!
                @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr", argMapping: "pEnv: env, pServiceId: serviceId, pFeideId: feideId")
            }
            """);
        var f = (UnclassifiedField) schema.field("Query", "tilganger");
        assertThat(f.rejection()).isInstanceOf(Rejection.Deferred.class);
        assertThat(f.reason()).contains("@orderBy / @condition on a routine-backed field");
    }

    @Test
    void repeatedReferenceOnArgumentRejectsAsDirectiveConflict() {
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type Query {
              films(name: String @reference(path: [{key: "film_language_id_fkey"}])
                                 @reference(path: [{key: "film_language_id_fkey"}])): [Film!]!
            }
            """);
        var f = (UnclassifiedField) schema.field("Query", "films");
        assertThat(f.rejection()).isInstanceOf(Rejection.InvalidSchema.DirectiveConflict.class);
        assertThat(f.reason()).contains("repeated @reference on an argument");
    }

    @Test
    void repeatedReferenceOnInputFieldRejects() {
        // repeatable applies semantically only where order-composition does (output field
        // definitions); an input field has no table chain, so repetition is a conflict.
        var schema = build("""
            input PlainFilter {
              district: String
                @reference(path: [{table: "address"}])
                @reference(path: [{table: "address"}])
            }
            type Customer @table(name: "customer") { customerId: Int! @field(name: "customer_id") }
            type Query { customers(filter: PlainFilter): [Customer!]! }
            """);
        var f = (UnclassifiedField) schema.field("Query", "customers");
        assertThat(f.rejection()).isInstanceOf(Rejection.AuthorError.Structural.class);
        assertThat(f.reason()).contains("1 input field could not be resolved");
        assertThat(TestSchemaHelper.diagnosticMessages(schema))
            .contains("repeated @reference on an input field")
            .contains("compose the chain on the field instead");
    }

    @Test
    void asConnectionOnRoutineTerminusChainRejectsAsDirectiveConflict() {
        // A routine-terminus chain can never support keyset pagination: the FK-less routine
        // result carries no ordering contract. DirectiveConflict, not Deferred.
        var schema = build(TILGANG_TYPE + """
            type Query {
              tilganger(env: String!, serviceId: String!, feideId: String!): [Tilgang!]!
                @asConnection
                @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr", argMapping: "pEnv: env, pServiceId: serviceId, pFeideId: feideId")
            }
            """);
        var f = (UnclassifiedField) schema.field("Query", "tilganger");
        assertThat(f.rejection()).isInstanceOf(Rejection.InvalidSchema.DirectiveConflict.class);
        assertThat(f.reason()).contains("a routine-terminus chain does not support Connection return types");
    }

    @Test
    void asConnectionOnCatalogTerminusRoutineChainDefers() {
        // A chain that merely CONTAINS a routine node but terminates on a catalog table could
        // support pagination later, so it lands typed Deferred, not a conflict.
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type Query {
              recentFilms(actorId: Int!, minLength: Int!): [Film!]!
                @asConnection
                @routine(name: "films_for_actor", argMapping: "pActorId: actorId, pMinLength: minLength")
                @reference(path: [{table: "film"}])
            }
            """);
        var f = (UnclassifiedField) schema.field("Query", "recentFilms");
        assertThat(f.rejection()).isInstanceOf(Rejection.Deferred.class);
        assertThat(f.reason()).contains("Connection pagination over a catalog-terminus chain containing a routine node");
    }

    @Test
    void conditionOnRoutineFieldDefersAsCapabilityGap() {
        var schema = build(TILGANG_TYPE + """
            type Query {
              tilganger(env: String!, serviceId: String!, feideId: String!): [Tilgang!]!
                @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr", argMapping: "pEnv: env, pServiceId: serviceId, pFeideId: feideId")
                @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "fieldCondition"})
            }
            """);
        var f = (UnclassifiedField) schema.field("Query", "tilganger");
        assertThat(f.rejection()).isInstanceOf(Rejection.Deferred.class);
        assertThat(f.reason()).contains("@orderBy / @condition on a routine-backed field");
    }

    @Test
    void columnMappingTypeMismatchRejectsAsStructural() {
        // pEnv is TEXT (java.lang.String); film_id is an Integer column. The emitted call
        // passes the column's Field to the routine's Field overload, so the boxed Java types
        // must match — this would otherwise be a javac error in the generated source.
        var schema = build(TILGANG_TYPE + """
            type Film @table(name: "film") {
              title: String
              tilganger(serviceId: String!, feideId: String!): [Tilgang!]
                @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr",
                         argMapping: "pServiceId: serviceId, pFeideId: feideId",
                         columnMapping: "pEnv: film_id")
            }
            type Query { film: Film }
            """);
        var f = (UnclassifiedField) schema.field("Film", "tilganger");
        assertThat(f.rejection()).isInstanceOf(Rejection.AuthorError.Structural.class);
        assertThat(f.reason())
            .contains("java.lang.String")
            .contains("java.lang.Integer")
            .contains("must match the routine parameter's");
    }

    // ===== Routine-chain classification edges (D1 root-position gate, D2 conflict table) =====
    // D1 gates the root-chain interception on Query: @routine on Mutation is the routine-write arm (a
    // capability gap), Subscription lands its generic Deferred, and a non-routine Mutation chain
    // gets the Mutation story rather than the Query-oriented root-head rejection. D2 folds @routine
    // into the conflict detectors via a pairwise verdict table (Conflict dominates Deferred). D3
    // pins the single-node root desugar directly.

    @Test
    void rootSingleNodeRoutineDesugarsToRoutineSourcedTableFieldWithEmptyHops() {
        // D3 — the single-node root @routine is the degenerate chain: no @reference
        // application, the routine result is itself the terminus, so hops is empty.
        var schema = build(TILGANG_TYPE + """
            type Query {
              tilganger(env: String!, serviceId: String!, feideId: String!): [Tilgang!]!
                @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr", argMapping: "pEnv: env, pServiceId: serviceId, pFeideId: feideId")
            }
            """);
        var f = (QueryField.QueryTableField) schema.field("Query", "tilganger");
        var chain = routineChainOf(f);
        assertThat(chain.hops()).isEmpty();
        assertThat(chain.start().resultTable().tableName())
            .isEqualToIgnoringCase("tilganger_for_feidebruker_med_fs_fiktivt_fnr");
        assertThat(f.returnType().table().tableName())
            .isEqualToIgnoringCase("tilganger_for_feidebruker_med_fs_fiktivt_fnr");
    }

    @Test
    void mutationMultiNodeRoutineChainClassifiesAsRoutineWrite() {
        // D3 — a multi-node routine chain on Mutation lands MutationRoutineWriteField with the
        // pinned RoutineChain shape: the routine node is the start, the single @reference hop is
        // the post-commit re-read anchor, and the terminus is the field's @table type.
        var schema = build("""
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type Query { rental: Rental }
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): [Rental!]!
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @reference(path: [{table: "rental"}])
            }
            """);
        var f = (MutationField.MutationRoutineWriteField) schema.field("Mutation", "rentFilm");
        assertThat(f.chain().hops()).hasSize(1);
        assertThat(f.chain().start().resultTable().tableName()).isEqualToIgnoringCase("rent_film");
        assertThat(f.chain().terminus().tableName()).isEqualToIgnoringCase("rental");
        assertThat(f.returnType().table().tableName()).isEqualToIgnoringCase("rental");
        assertThat(f.errorChannel()).isEmpty();
    }

    @Test
    void mutationSingleNodeRoutineDefersToResultShapesFollowUp() {
        // D2 — the hop-less @routine on Mutation with a direct @table return has no @reference
        // hop and no payload data field to carry one, so it stays a typed Deferred from
        // classifyMutationField's @routine fork, its summary worded around the anchor's seat
        // and naming the shapes the result-shapes follow-up carries (void / scalar /
        // OUT-parameter binding / non-carrier Objects).
        var schema = build(TILGANG_TYPE + """
            type Query { tilgang: Tilgang }
            type Mutation {
              tilganger(env: String!, serviceId: String!, feideId: String!): [Tilgang!]!
                @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr", argMapping: "pEnv: env, pServiceId: serviceId, pFeideId: feideId")
            }
            """);
        var f = (UnclassifiedField) schema.field("Mutation", "tilganger");
        assertThat(f.rejection()).isInstanceOf(Rejection.Deferred.class);
        assertThat(f.reason()).contains("no payload data field");
    }

    // ===== The @routine payload carrier (the hop-less form) =====

    /** The single-cardinality carrier fixture over rent_film, with an errors channel. */
    private static final String RENT_FILM_CARRIER = """
        type DbErr @error(handlers: [{handler: DATABASE, sqlState: "23503"}]) {
            path: [String!]!
            message: String!
        }
        union RentFilmError = DbErr
        type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
        type RentFilmPayload {
          rental: Rental
          errors: [RentFilmError!]
        }
        type Query { rental: Rental }
        type Mutation {
          rentFilm(inventoryId: Int!, customerId: Int!): RentFilmPayload
            @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
        }
        """;

    @Test
    void mutationRoutineCarrierWithErrorsFieldClassifiesWithLocalContextChannel() {
        // The directiveless carrier admitted with an errors field: the leaf carries the routine
        // call, the name-matched captured pairs (target side == rental's PK), the target table,
        // the data field's arrival, and the narrow LocalContext channel.
        var schema = build(RENT_FILM_CARRIER);
        var f = (MutationField.MutationRoutineWriteRecordField) schema.field("Mutation", "rentFilm");
        assertThat(f.routine().methodName()).isEqualTo("rentFilm");
        assertThat(f.routineResultTable().tableName()).isEqualToIgnoringCase("rent_film");
        assertThat(f.targetTable().tableName()).isEqualToIgnoringCase("rental");
        assertThat(f.capturedPairs()).hasSize(1);
        assertThat(f.capturedPairs().get(0).sourceSide().sqlName()).isEqualToIgnoringCase("rental_id");
        assertThat(f.capturedPairs().get(0).targetSide().sqlName()).isEqualToIgnoringCase("rental_id");
        assertThat(f.dataFieldArrival()).isEqualTo(Arity.ONE);
        assertThat(f.errorChannel()).isPresent();
        assertThat(f.errorChannel().get().mappingsConstantName()).isEqualTo("RENT_FILM_PAYLOAD");
    }

    @Test
    void mutationRoutineCarrierErrorsFieldBindsLocalContextTransport() {
        // The transport pin: the carrier's errors field classifies as ErrorsField with
        // Transport.LocalContext. This is the assertion that fails if the activeChannel gate
        // was not widened to the routine arm — the errors field would silently fall through to
        // PayloadAccessor, the one transport a directiveless structural carrier cannot serve —
        // and it fails at classify time rather than three tiers later in execution.
        var schema = build(RENT_FILM_CARRIER);
        var errors = (ErrorsField) schema.field("RentFilmPayload", "errors");
        assertThat(errors.transport())
            .isInstanceOf(no.sikt.graphitron.rewrite.model.ChildField.Transport.LocalContext.class);
        // And the data field is the record-sourced re-fetch, correlated on the target's PK.
        var data = (BatchedTableField) schema.field("RentFilmPayload", "rental");
        assertThat(data.parentCorrelation())
            .isInstanceOf(no.sikt.graphitron.rewrite.model.ParentCorrelation.OnLiftedSlots.class);
    }

    @Test
    void mutationRoutineCarrierWithoutErrorsFieldClassifiesWithEmptyChannel() {
        // The carrier admitted without an errors field: same leaf, empty channel (the fetcher
        // wraps in the redacting catch arm).
        var schema = build("""
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type RentFilmPayload { rental: Rental }
            type Query { rental: Rental }
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): RentFilmPayload
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            }
            """);
        var f = (MutationField.MutationRoutineWriteRecordField) schema.field("Mutation", "rentFilm");
        assertThat(f.errorChannel()).isEmpty();
    }

    @Test
    void mutationRoutineCarrierListDataFieldDrivesListCardinalityOnTheLeaf() {
        // The data field's SDL wrapper is the only cardinality claim in the system (jOOQ types
        // every table-valued function as a Table<R>), read at the data field's own seat and
        // landing as the leaf's arrival component: a [Film!] data field means step 1 fetches
        // the whole captured set.
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type FilmsPayload { films: [Film!] }
            type Query { film: Film }
            type Mutation {
              filmsForActor(actorId: Int!, minLength: Int!): FilmsPayload
                @routine(name: "films_for_actor", argMapping: "pActorId: actorId, pMinLength: minLength")
            }
            """);
        var f = (MutationField.MutationRoutineWriteRecordField) schema.field("Mutation", "filmsForActor");
        assertThat(f.dataFieldArrival()).isEqualTo(Arity.MANY);
        assertThat(f.targetTable().tableName()).isEqualToIgnoringCase("film");
    }

    @Test
    void mutationRoutineCarrierFourthCellRejectsAsDirectiveConflictNamingTheDataField() {
        // @routine + @reference on the mutation field WITH a carrier return: the right fact at
        // the wrong grain. Rejects as the typed directive conflict naming the data field as the
        // path's seat, not the generic not-table-bound rejection.
        var schema = build("""
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type RentFilmPayload { rental: Rental }
            type Query { rental: Rental }
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): RentFilmPayload
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @reference(path: [{table: "rental"}])
            }
            """);
        var f = (UnclassifiedField) schema.field("Mutation", "rentFilm");
        assertThat(f.rejection()).isInstanceOf(Rejection.InvalidSchema.DirectiveConflict.class);
        assertThat(f.reason())
            .contains("'rental'")
            .contains("drop @reference")
            .doesNotContain("@table-annotated return type");
    }

    @Test
    void mutationRoutineCarrierUnmatchedTargetKeyRejectsWithCarrierSeatWording() {
        // A routine result that does not expose the target's PK column by name rejects with the
        // carrier seat's own wording: candidate hint over the routine's actual columns, the one
        // fix that works, and no 'condition:' clause (a condition join has no key tuple to
        // capture, so that fix would land the author in a second rejection).
        var schema = build("""
            type Actor @table(name: "actor") { firstName: String }
            type ActorPayload { actor: Actor }
            type Query { actor: Actor }
            type Mutation {
              filmsForActor(actorId: Int!, minLength: Int!): ActorPayload
                @routine(name: "films_for_actor", argMapping: "pActorId: actorId, pMinLength: minLength")
            }
            """);
        var f = (UnclassifiedField) schema.field("Mutation", "filmsForActor");
        assertThat(f.rejection()).isNotInstanceOf(Rejection.Deferred.class);
        assertThat(f.reason())
            .contains("actor_id")
            .contains("film_id") // candidate hint lists the routine result's columns
            .contains("expose the key column from the routine")
            .doesNotContain("condition:");
    }

    @Test
    void mutationRoutineCarrierReferenceOnDataFieldLandsPointedDeferred() {
        // A data field carrying @reference is the explicit path declaration, the follow-up's
        // question: the would-admit-but-for-the-directive probe lands a pointed typed Deferred
        // naming it, instead of the generic non-carrier fallthrough.
        var schema = build("""
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type RentFilmPayload {
              rental: Rental @reference(path: [{table: "rental"}])
            }
            type Query { rental: Rental }
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): RentFilmPayload
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            }
            """);
        var f = (UnclassifiedField) schema.field("Mutation", "rentFilm");
        assertThat(f.rejection()).isInstanceOf(Rejection.Deferred.class);
        assertThat(f.reason())
            .contains("@reference on a routine carrier's data field ('rental')")
            .contains("explicit");
    }

    @Test
    void mutationRoutineCarrierRecordElementDataFieldRejectsWithRoutineWording() {
        // A record-element data field (a class-backed non-@table element; here Details is
        // record-bound through the @service producer) rejects at this seat with routine
        // wording: the payload is re-read post-commit from a catalog table, which a record
        // element does not have.
        var schema = build("""
            type Details { note: String }
            type DetailsPayload { details: Details }
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type Query {
              rental: Rental
              d: Details @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): DetailsPayload
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            }
            """);
        var f = (UnclassifiedField) schema.field("Mutation", "rentFilm");
        assertThat(f.reason())
            .contains("record-element data field ('details')")
            .contains("@routine mutation field 'rentFilm'");
    }

    @Test
    void mutationRoutineCarrierIdElementDataFieldRejectsWithRoutineWording() {
        // The ID-element data field is the DELETE PK-echo permit; a routine write has no
        // PK-echo shape, so the ROUTINE carrier family refuses the element outright.
        var schema = build("""
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type RentFilmPayload { rentalId: ID }
            type Query { rental: Rental }
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): RentFilmPayload
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            }
            """);
        var f = (UnclassifiedField) schema.field("Mutation", "rentFilm");
        assertThat(f.reason())
            .contains("element type 'ID'")
            .contains("routine write has no PK-echo shape");
    }

    @Test
    void mutationRoutineCarrierNonNullDataFieldRejectsForNullability() {
        // D7: outcome (b) — the routine succeeded and the committed row is invisible to the
        // post-commit re-read — makes the zero-row re-read a first-class success path, so a
        // non-null single data field is an author error naming the zero-row reason.
        var schema = build("""
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type RentFilmPayload { rental: Rental! }
            type Query { rental: Rental }
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): RentFilmPayload
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            }
            """);
        var f = (UnclassifiedField) schema.field("Mutation", "rentFilm");
        assertThat(f.kind()).isEqualTo(RejectionKind.AUTHOR_ERROR);
        assertThat(f.reason())
            .contains("must be nullable")
            .contains("no row");
    }

    @Test
    void mutationRoutineChainNamingScalarFunctionDefersOffNonTableValuedArm() {
        // D2 — a routine name that exists in the schema but is not table-valued (here the
        // scalar rental_count_for_customer) routes off JooqCatalog's NonTableValuedRoutine
        // resolution arm to the result-shapes Deferred: the author named a real routine whose
        // call surface is the follow-up's work, not a typo.
        var schema = build("""
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type Query { rental: Rental }
            type Mutation {
              countRentals(customerId: Int!): [Rental!]!
                @routine(name: "rental_count_for_customer", argMapping: "pCustomerId: customerId")
                @reference(path: [{table: "rental"}])
            }
            """);
        var f = (UnclassifiedField) schema.field("Mutation", "countRentals");
        assertThat(f.rejection()).isInstanceOf(Rejection.Deferred.class);
        assertThat(f.reason()).contains("not table-valued");
    }

    @Test
    void mutationRoutineChainNamingAbsentRoutineKeepsStructuralRejection() {
        // D2 — the fixture pair's other half: a genuinely absent name stays the structural
        // not-in-catalog rejection (a typo is an authoring error, never a Deferred). This is the
        // distinction the NonTableValuedRoutine arm exists to make possible.
        var schema = build("""
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type Query { rental: Rental }
            type Mutation {
              countRentals(customerId: Int!): [Rental!]!
                @routine(name: "no_such_routine_anywhere", argMapping: "pCustomerId: customerId")
                @reference(path: [{table: "rental"}])
            }
            """);
        var f = (UnclassifiedField) schema.field("Mutation", "countRentals");
        assertThat(f.rejection()).isNotInstanceOf(Rejection.Deferred.class);
        assertThat(f.reason()).contains("no table-valued function named 'no_such_routine_anywhere'");
    }

    @Test
    void mutationRoutineChainWithReferenceFirstRejectsWithRootHeadRule() {
        // D3 — the root-head rule extends to Mutation write chains: directives compose the
        // chain in written order, and at root only the routine can supply the head.
        var schema = build("""
            type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
            type Query { rental: Rental }
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): [Rental!]!
                @reference(path: [{table: "rental"}])
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            }
            """);
        var f = (UnclassifiedField) schema.field("Mutation", "rentFilm");
        assertThat(f.rejection()).isInstanceOf(Rejection.AuthorError.Structural.class);
        assertThat(f.reason()).contains("move @routine first");
    }

    @Test
    void mutationRoutineChainWithConditionJoinedHopZeroDefers() {
        // D3 — a condition-joined hop 0 has no derivable post-commit re-read anchor (the
        // predicate references the routine alias, which must not appear in the follow-up query),
        // so the classifier's re-read-anchor verdict lands a typed Deferred instead of the leaf.
        var schema = build("""
            type Actor @table(name: "actor") { firstName: String }
            type Query { actor: Actor }
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): [Actor!]!
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @reference(path: [{condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "join"}}])
            }
            """);
        var f = (UnclassifiedField) schema.field("Mutation", "rentFilm");
        assertThat(f.rejection()).isInstanceOf(Rejection.Deferred.class);
        assertThat(f.reason()).contains("no derivable post-commit re-read anchor");
    }

    @Test
    void subscriptionRoutineChainLandsGenericSubscriptionDeferred() {
        // D1 — a Subscription routine chain falls through to classifyRootField's generic
        // Subscription Deferred (everything on Subscription lands there); it is not routed to the
        // Query routine classifier.
        var schema = build("""
            type Film @table(name: "film") { title: String }
            type Query { film: Film }
            type Subscription {
              recentFilms(actorId: Int!, minLength: Int!): [Film!]!
                @routine(name: "films_for_actor", argMapping: "pActorId: actorId, pMinLength: minLength")
                @reference(path: [{table: "film"}])
            }
            """);
        var f = (UnclassifiedField) schema.field("Subscription", "recentFilms");
        assertThat(f.rejection()).isInstanceOf(Rejection.Deferred.class);
        assertThat(f.reason()).contains("Subscription is not supported");
    }

    @Test
    void mutationRepeatedReferenceChainLandsMutationFallbackNotRootHeadRejection() {
        // D1 — a Mutation multi-node chain with no routine node (repeated @reference) falls
        // through to classifyMutationField, landing the Mutation "both absent" fallback rather than
        // the Query-oriented "move @routine first" root-head rejection.
        var schema = build("""
            type Actor @table(name: "actor") { firstName: String }
            type Query { actor: Actor }
            type Mutation {
              actors: [Actor!]
                @reference(path: [{table: "actor"}])
                @reference(path: [{table: "actor"}])
            }
            """);
        var f = (UnclassifiedField) schema.field("Mutation", "actors");
        assertThat(f.rejection()).isInstanceOf(Rejection.AuthorError.Structural.class);
        assertThat(f.reason())
            .contains("both absent")
            .doesNotContain("move @routine first");
    }

    // The @service x @routine conflicts (child, root single-node, root chain), the @routine x
    // @lookupKey root deferral, and the three-directive dominance rule migrated to the
    // store-backed detection's test (AuthoredClaimConflictsTest) when the walk's detector sites
    // dissolved into the authored claim views; the coordinates now classify by arm order and the
    // conflict is a located ValidationError.

    @Test
    @ProjectionFor({QueryField.QueryNodeField.class, QueryField.QueryNodesField.class})
    void queryNodeProjectionCarriesListMultiplicity() {
        var snapshot = buildSnapshot("""
            type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
              id: ID! @nodeId
            }
            type Query {
              node(id: ID!): Node
              nodes(ids: [ID!]!): [Node]!
            }
            """);
        var node = (FieldClassification.QueryNode) snapshot.fieldClassificationsByCoord().get("Query.node");
        assertThat(node.isList()).isFalse();
        var nodes = (FieldClassification.QueryNode) snapshot.fieldClassificationsByCoord().get("Query.nodes");
        assertThat(nodes.isList()).isTrue();
    }

    @Test
    @ProjectionFor({QueryField.QueryTableInterfaceField.class, QueryField.QueryInterfaceField.class, QueryField.QueryUnionField.class})
    void queryPolymorphicProjectionsCarryParticipants() {
        // QueryTableInterfaceField — single-table polymorphic root query field.
        var s1 = buildSnapshot("""
            interface MediaItem @table(name: "film") @discriminate(on: "kind") { title: String }
            type Film implements MediaItem @table(name: "film") @discriminator(value: "film") { title: String }
            type Query { media: MediaItem }
            """);
        var tableIface = (FieldClassification.QueryTableInterface) s1.fieldClassificationsByCoord().get("Query.media");
        assertThat(tableIface.tableName()).isEqualToIgnoringCase("film");
        assertThat(tableIface.discriminatorColumn()).isEqualTo("kind");
        assertThat(tableIface.participantTypeNames()).contains("Film");

        // QueryUnionField — multi-table union at root.
        var s2 = buildSnapshot("""
            type Film @table(name: "film") { title: String }
            type Language @table(name: "language") { name: String }
            union FilmOrLanguage = Film | Language
            type Query { item: FilmOrLanguage }
            """);
        var poly = (FieldClassification.QueryPolymorphic) s2.fieldClassificationsByCoord().get("Query.item");
        assertThat(poly.participantTypeNames()).containsExactlyInAnyOrder("Film", "Language");
    }

    @Test
    @ProjectionFor({QueryField.QueryServiceTableField.class, QueryField.QueryServiceRecordField.class})
    void queryServiceProjectionCarriesMethodAndTableBoundFlag() {
        // QueryServiceRecordField — scalar return; tableBound = false.
        var s1 = buildSnapshot("""
            type Query {
              rating: String @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"})
            }
            """);
        var rec = (FieldClassification.QueryService) s1.fieldClassificationsByCoord().get("Query.rating");
        assertThat(rec.tableBound()).isFalse();
        assertThat(rec.methodName()).isEqualTo("get");

        // QueryServiceTableField — @table return; tableBound = true.
        var s2 = buildSnapshot("""
            type Film @table(name: "film") { title: String }
            type Query {
              film: Film @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
            }
            """);
        var tb = (FieldClassification.QueryService) s2.fieldClassificationsByCoord().get("Query.film");
        assertThat(tb.tableBound()).isTrue();
        assertThat(tb.tableName()).isEqualToIgnoringCase("film");
    }

    // ===== DML mutation classification (Invariants #1, #7-#13 — see DmlReturnExpression and FieldBuilder.buildDmlField) =====
    //
    // Rich assertions for the four DML variants: input-shape invariants (one @table arg, no
    // listed input, no @condition, only ColumnBackedField entries inside the input), @lookupKey gates
    // for UPDATE / DELETE / UPSERT, return-type validation, and NodeId metadata resolution for
    // ScalarReturnType(ID) returns.  Each rejection case asserts on the {@code reason} text so
    // a regressed message would surface here, not silently in production.

    enum MutationDmlCase implements ClassificationCase {

        INSERT_HAPPY_PATH(
            "INSERT returning a @table type → DmlTableField, tableInputArg.inputTable is the return-derived write target",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput { title: String }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT) }
            """,
            schema -> {
                var f = (MutationField.DmlTableField) schema.field("Mutation", "createFilm");
                assertThat(insertInputOf(f).inputTable().tableName()).isEqualTo("film");
                assertThat(insertInputOf(f).fieldBindings()).isEmpty();
                var rex = (no.sikt.graphitron.rewrite.model.DmlReturnExpression.ProjectedSingle) f.returnExpression();
                assertThat(rex.returnTypeName()).isEqualTo("Film");
                // The classified reentry correlation: PK self-identity over the bound table's
                // primary key, carried on the arm so the emit never re-derives the key set.
                assertThat(rex.reentryCorrelation().targetTable().tableName()).isEqualTo("film");
                assertThat(rex.reentryCorrelation().columns())
                    .extracting(no.sikt.graphitron.rewrite.model.ColumnRef::sqlName)
                    .containsExactly("film_id");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.DmlTableField.class); }
        },

        UPDATE_LOOKUP_KEY_COVERS_SINGLE_PK(
            "R246: UPDATE input covering the single-column PK plus a non-key column → DmlTableField with the PK in keyColumns and the extra in setColumns",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput {
                filmId: Int! @field(name: "film_id")
                title: String
            }
            type Query { x: String }
            type Mutation { updateFilm(in: FilmInput!): Film @mutation(typeName: UPDATE) }
            """,
            schema -> {
                var f = (MutationField.DmlTableField) schema.field("Mutation", "updateFilm");
                var ur = (no.sikt.graphitron.rewrite.model.UpdateRows.Identified) updateRowsOf(f);
                assertThat(ur.matchedKey()).isInstanceOf(no.sikt.graphitron.rewrite.model.MatchedKey.PrimaryKey.class);
                assertThat(ur.keyColumns()).extracting(k -> k.targetColumn().sqlName()).containsExactly("film_id");
                assertThat(ur.setColumns()).extracting(s -> s.targetColumn().sqlName()).containsExactly("title");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.DmlTableField.class); }
        },

        UPDATE_NO_KEY_COVERAGE_REJECTED(
            "R246: UPDATE input covering no PK or UK → UnclassifiedField carrying UpdateRowsError.NoUniqueKeyCoverage",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput { title: String }
            type Query { x: String }
            type Mutation { updateFilm(in: FilmInput!): Film @mutation(typeName: UPDATE) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "updateFilm");
                assertThat(f.rejection()).isInstanceOf(
                    no.sikt.graphitron.rewrite.model.UpdateRowsError.NoUniqueKeyCoverage.class);
                assertThat(f.reason()).contains("covers no primary key or unique key");
            }),

        UPDATE_EMPTY_SET_REJECTED(
            "R246: UPDATE input covering exactly the PK and nothing else → UnclassifiedField carrying UpdateRowsError.NoSetFields",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput {
                filmId: Int! @field(name: "film_id")
            }
            type Query { x: String }
            type Mutation { updateFilm(in: FilmInput!): Film @mutation(typeName: UPDATE) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "updateFilm");
                assertThat(f.rejection()).isInstanceOf(
                    no.sikt.graphitron.rewrite.model.UpdateRowsError.NoSetFields.class);
                assertThat(f.reason()).contains("nothing to set");
            }),

        UPDATE_PARTIAL_COMPOSITE_PK_REJECTED(
            "R246: UPDATE on composite-PK table covering only one PK column → UnclassifiedField carrying UpdateRowsError.NoUniqueKeyCoverage",
            """
            type FilmActor @table(name: "film_actor") { actorId: Int! @field(name: "actor_id") }
            input FilmActorInput {
                actorId: Int! @field(name: "actor_id")
                lastUpdate: String @field(name: "last_update")
            }
            type Query { x: String }
            type Mutation { updateFilmActor(in: FilmActorInput!): FilmActor @mutation(typeName: UPDATE) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "updateFilmActor");
                assertThat(f.rejection()).isInstanceOf(
                    no.sikt.graphitron.rewrite.model.UpdateRowsError.NoUniqueKeyCoverage.class);
                assertThat(f.reason()).contains("film_actor");
            }),

        UPDATE_FULL_COMPOSITE_PK_HAPPY(
            "R246: UPDATE on composite-PK table covering all PK columns plus a non-key column → DmlTableField with both PK columns in keyColumns",
            """
            type FilmActor @table(name: "film_actor") { actorId: Int! @field(name: "actor_id") }
            input FilmActorInput {
                actorId: Int! @field(name: "actor_id")
                filmId: Int! @field(name: "film_id")
                lastUpdate: String @field(name: "last_update")
            }
            type Query { x: String }
            type Mutation { updateFilmActor(in: FilmActorInput!): FilmActor @mutation(typeName: UPDATE) }
            """,
            schema -> {
                var f = (MutationField.DmlTableField) schema.field("Mutation", "updateFilmActor");
                var ur = (no.sikt.graphitron.rewrite.model.UpdateRows.Identified) updateRowsOf(f);
                assertThat(ur.keyColumns()).extracting(k -> k.targetColumn().sqlName())
                    .containsExactlyInAnyOrder("actor_id", "film_id");
                assertThat(ur.setColumns()).extracting(s -> s.targetColumn().sqlName())
                    .containsExactly("last_update");
            }),

        DELETE_FULL_COMPOSITE_PK_HAPPY(
            "R266: DELETE on a composite-PK table where the input covers all PK columns → "
                + "DmlTableField with a DeleteRows.Identified carrier whose whereColumns "
                + "are both PK columns.",
            """
            type FilmActor implements Node @table(name: "film_actor") @node { id: ID! @nodeId actorId: Int! @field(name: "actor_id") }
            input FilmActorInput {
                actorId: Int! @field(name: "actor_id")
                filmId: Int! @field(name: "film_id")
            }
            type Query { x: String }
            type Mutation { deleteFilmActor(in: FilmActorInput!): ID @mutation(typeName: DELETE, table: "film_actor") }
            """,
            schema -> {
                var f = (MutationField.DmlTableField) schema.field("Mutation", "deleteFilmActor");
                var deleteRows = (no.sikt.graphitron.rewrite.model.DeleteRows.Identified) deleteRowsOf(f);
                assertThat(deleteRows.matchedKey().columns()).extracting(c -> c.sqlName())
                    .containsExactlyInAnyOrder("actor_id", "film_id");
                assertThat(deleteRows.whereColumns()).extracting(c -> c.targetColumn().sqlName())
                    .containsExactlyInAnyOrder("actor_id", "film_id");
            }),

        DELETE_PARTIAL_COMPOSITE_PK_REJECTED(
            "R266: DELETE on composite-PK table with only one PK column covered and no multiRow → "
                + "UnclassifiedField carrying DeleteRowsError.NoUniqueKeyCoverage",
            """
            type FilmActor implements Node @table(name: "film_actor") @node { id: ID! @nodeId actorId: Int! @field(name: "actor_id") }
            input FilmActorInput {
                actorId: Int! @field(name: "actor_id")
            }
            type Query { x: String }
            type Mutation { deleteFilmActor(in: FilmActorInput!): ID @mutation(typeName: DELETE, table: "film_actor") }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "deleteFilmActor");
                assertThat(f.rejection())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.DeleteRowsError.NoUniqueKeyCoverage.class);
                assertThat(f.reason())
                    .contains("covers no primary key or unique key", "multiRow: true");
            }),

        UPDATE_CARRIER_PARTITIONS_FIELDS_INTO_KEY_AND_SET(
            "R246: the UpdateRows carrier partitions input fields into keyColumns (matched-key members) and setColumns (the rest), in declaration order",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput {
                filmId: Int! @field(name: "film_id")
                title: String
                description: String
            }
            type Query { x: String }
            type Mutation { updateFilm(in: FilmInput!): Film @mutation(typeName: UPDATE) }
            """,
            schema -> {
                var ur = (no.sikt.graphitron.rewrite.model.UpdateRows.Identified)
                    updateRowsOf((MutationField.DmlTableField) schema.field("Mutation", "updateFilm"));
                assertThat(ur.keyColumns()).extracting(k -> k.sdlFieldName()).containsExactly("filmId");
                assertThat(ur.setColumns()).extracting(s -> s.sdlFieldName()).containsExactly("title", "description");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.DmlTableField.class); }
        },

        DML_NESTING_UPDATE_TABLE_RETURN_ADMITTED(
            "R186: direct-@table-return UPDATE with a nested non-@table grouping over outer-table columns "
                + "→ DmlTableField; the nested leaves flatten across keyColumns / setColumns "
                + "with NestedInputField access paths",
            """
            type Film @table(name: "film") { title: String }
            input FilmDetails { title: String, description: String }
            input FilmUpdateInput { filmId: Int! @field(name: "film_id"), details: FilmDetails }
            type Query { x: String }
            type Mutation { updateFilm(in: FilmUpdateInput!): Film @mutation(typeName: UPDATE) }
            """,
            schema -> {
                var f = (MutationField.DmlTableField) schema.field("Mutation", "updateFilm");
                var ur = (no.sikt.graphitron.rewrite.model.UpdateRows.Identified) updateRowsOf(f);
                // The top-level PK column stays a plain (non-nested) extraction.
                assertThat(ur.keyColumns()).singleElement().satisfies(k -> {
                    assertThat(k.targetColumn().sqlName()).isEqualTo("film_id");
                    assertThat(k.extraction())
                        .isNotInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.NestedInputField.class);
                });
                // The nested leaves flatten into setColumns, each carrying its descent path.
                assertThat(ur.setColumns()).extracting(s -> s.targetColumn().sqlName())
                    .containsExactly("title", "description");
                assertThat(ur.setColumns().get(0).extraction())
                    .isInstanceOfSatisfying(no.sikt.graphitron.rewrite.model.CallSiteExtraction.NestedInputField.class,
                        n -> assertThat(n.path()).containsExactly("details", "title"));
                assertThat(ur.setColumns().get(1).extraction())
                    .isInstanceOfSatisfying(no.sikt.graphitron.rewrite.model.CallSiteExtraction.NestedInputField.class,
                        n -> assertThat(n.path()).containsExactly("details", "description"));
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.DmlTableField.class); }
        },

        DML_NESTING_UPDATE_PAYLOAD_RETURN_ADMITTED(
            "R186: the canonical bulk + payload-returning UPDATE forcing-function shape classifies through "
                + "the UpdateRowsWalker to MutationBulkDmlRecordField; nested leaves flatten into the "
                + "UpdateRows carrier with their access paths",
            """
            type Film @table(name: "film") { title: String }
            type FilmsPayload { films: [Film!] }
            input FilmDetails { title: String, description: String }
            input FilmUpdateInput { filmId: Int! @field(name: "film_id"), details: FilmDetails }
            type Query { x: String }
            type Mutation { updateFilmsPayload(in: [FilmUpdateInput!]!): FilmsPayload @mutation(typeName: UPDATE) }
            """,
            schema -> {
                var f = (MutationField.MutationBulkDmlRecordField) schema.field("Mutation", "updateFilmsPayload");
                var ur = (no.sikt.graphitron.rewrite.model.UpdateRows.Identified) updateRowsOf(f);
                assertThat(ur.keyColumns()).extracting(k -> k.targetColumn().sqlName()).containsExactly("film_id");
                assertThat(ur.setColumns()).extracting(s -> s.targetColumn().sqlName())
                    .containsExactly("title", "description");
                assertThat(ur.setColumns()).allSatisfy(s ->
                    assertThat(s.extraction())
                        .isInstanceOfSatisfying(no.sikt.graphitron.rewrite.model.CallSiteExtraction.NestedInputField.class,
                            n -> assertThat(n.path()).first().isEqualTo("details")));
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.MutationBulkDmlRecordField.class); }
        },

        DML_INSERT_NESTING_OK(
            "R186 (flips DML_NESTING_FIELD_DEFERRED): INSERT with a nested non-@table grouping → "
                + "DmlTableField; fields() retains the NestingField envelope and the nested "
                + "leaf resolves against the outer @table. The flat leaf's wire access path is asserted "
                + "by the compilation / execution tiers (the INSERT VALUES emit walks fields()); "
                + "lookupKeyFields stays the top-level carrier filter (a NestingField is not one).",
            """
            type Film @table(name: "film") { title: String }
            input FilmTitleInput { title: String }
            input FilmInput { details: FilmTitleInput }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT) }
            """,
            schema -> {
                var f = (MutationField.DmlTableField) schema.field("Mutation", "createFilm");
                // fields() retains the SDL grouping envelope, and its nested leaf resolved to the
                // outer film.title column (proving admission + outer-table resolution context).
                assertThat(insertInputOf(f).fields()).singleElement()
                    .isInstanceOfSatisfying(no.sikt.graphitron.rewrite.model.InputField.NestingField.class,
                        nf -> assertThat(nf.fields()).singleElement().satisfies(leaf -> {
                            var cf = (no.sikt.graphitron.rewrite.model.InputField.ColumnBackedField) leaf;
                            assertThat(cf.columns().get(0).sqlName()).isEqualTo("title");
                        }));
                // A NestingField is not a LookupKeyField, so the top-level carrier filter is empty;
                // the nested wire access path lives on the walker carriers (UPDATE/DELETE) and is
                // recomputed at emit from fields() for INSERT, never on this view.
                assertThat(insertInputOf(f).lookupKeyFields()).isEmpty();
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.DmlTableField.class); }
        },

        DML_DELETE_NESTING_OK(
            "R186: DELETE with a nested grouping holding the PK column → DmlTableField; the "
                + "nested leaf lands in DeleteRows.whereColumns with its access path and counts toward the "
                + "single-row PK guard",
            """
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId filmId: Int! @field(name: "film_id") }
            input FilmKeyGroup { filmId: Int! @field(name: "film_id") }
            input FilmDeleteInput { keys: FilmKeyGroup }
            type Query { x: String }
            type Mutation { deleteFilm(in: FilmDeleteInput!): ID @mutation(typeName: DELETE, table: "film") }
            """,
            schema -> {
                var f = (MutationField.DmlTableField) schema.field("Mutation", "deleteFilm");
                var dr = (no.sikt.graphitron.rewrite.model.DeleteRows.Identified) deleteRowsOf(f);
                assertThat(dr.matchedKey()).isInstanceOf(no.sikt.graphitron.rewrite.model.MatchedKey.PrimaryKey.class);
                assertThat(dr.whereColumns()).singleElement().satisfies(w -> {
                    assertThat(w.targetColumn().sqlName()).isEqualTo("film_id");
                    assertThat(w.extraction())
                        .isInstanceOfSatisfying(no.sikt.graphitron.rewrite.model.CallSiteExtraction.NestedInputField.class,
                            n -> assertThat(n.path()).containsExactly("keys", "filmId"));
                });
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.DmlTableField.class); }
        },

        DML_NESTING_DEEP(
            "R186: two layers of nesting flatten to a single NestedInputField carrier with the full "
                + "multi-segment access path (never a wrapped chain)",
            """
            type Film @table(name: "film") { title: String }
            input FilmInner { title: String }
            input FilmMid { inner: FilmInner }
            input FilmUpdateInput { filmId: Int! @field(name: "film_id"), mid: FilmMid }
            type Query { x: String }
            type Mutation { updateFilm(in: FilmUpdateInput!): Film @mutation(typeName: UPDATE) }
            """,
            schema -> {
                var f = (MutationField.DmlTableField) schema.field("Mutation", "updateFilm");
                var ur = (no.sikt.graphitron.rewrite.model.UpdateRows.Identified) updateRowsOf(f);
                assertThat(ur.setColumns()).singleElement().satisfies(s -> {
                    assertThat(s.targetColumn().sqlName()).isEqualTo("title");
                    assertThat(s.extraction())
                        .isInstanceOfSatisfying(no.sikt.graphitron.rewrite.model.CallSiteExtraction.NestedInputField.class,
                            n -> {
                                assertThat(n.path()).containsExactly("mid", "inner", "title");
                                assertThat(n.leaf())
                                    .isNotInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.NestedInputField.class);
                            });
                });
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.DmlTableField.class); }
        },

        DML_NESTING_WITH_NODEID_FK_TARGET(
            "R186 composes with R189: a nested @nodeId(typeName:) FK-target leaf produces a "
                + "ColumnBackedReferenceField under the nesting whose lifted FK column lands in setColumns with a "
                + "NestedInputField path wrapping the NodeId decode",
            """
            type Country implements Node @table(name: "country") @node(keyColumns: ["country_id"]) { id: ID! @nodeId }
            type City @table(name: "city") { x: String }
            input CityRefs { countryId: ID! @nodeId(typeName: "Country") }
            input CityUpdateInput { cityId: Int! @field(name: "city_id"), refs: CityRefs }
            type Query { x: String }
            type Mutation { updateCity(in: CityUpdateInput!): City @mutation(typeName: UPDATE) }
            """,
            schema -> {
                var f = (MutationField.DmlTableField) schema.field("Mutation", "updateCity");
                var ur = (no.sikt.graphitron.rewrite.model.UpdateRows.Identified) updateRowsOf(f);
                assertThat(ur.setColumns()).singleElement().satisfies(s -> {
                    assertThat(s.targetColumn().sqlName()).isEqualTo("country_id");
                    assertThat(s.extraction())
                        .isInstanceOfSatisfying(no.sikt.graphitron.rewrite.model.CallSiteExtraction.NestedInputField.class,
                            n -> {
                                assertThat(n.path()).containsExactly("refs", "countryId");
                                assertThat(n.leaf())
                                    .isInstanceOf(no.sikt.graphitron.rewrite.model.CallSiteExtraction.NodeIdDecodeKeys.class);
                            });
                });
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.DmlTableField.class); }
        },

        DML_NESTING_UNRESOLVABLE_LEAF(
            "R186: an unresolvable nested leaf (here a @nodeId to a non-existent type) propagates through "
                + "the NestingField as an unresolvable-fields rejection on the consuming mutation field → "
                + "UnclassifiedField (input fields resolve per consuming field)",
            """
            type Film @table(name: "film") { title: String }
            input FilmDetails { ref: ID! @nodeId(typeName: "NoSuchType") }
            input FilmInput { filmId: Int! @field(name: "film_id"), details: FilmDetails }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "createFilm");
                assertThat(f.reason()).contains("1 input field could not be resolved");
                // The nesting level states its own consequence; the leaf cause is reported at the
                // nested input field, so neither is quoted inside the other any more.
                assertThat(TestSchemaHelper.diagnosticMessages(schema))
                    .contains("nested input type 'FilmDetails' has 1 unresolvable field")
                    .contains("Input field 'FilmDetails.ref'");
            }),

        DML_NESTING_LIST_REJECTED_UPDATE(
            "R186: a list-typed nested input on an UPDATE field is rejected with UpdateRowsError.UnsupportedInputFieldShape naming R186",
            """
            type Film @table(name: "film") { title: String }
            input FilmDetails { title: String }
            input FilmUpdateInput { filmId: Int! @field(name: "film_id"), details: [FilmDetails!] }
            type Query { x: String }
            type Mutation { updateFilm(in: FilmUpdateInput!): Film @mutation(typeName: UPDATE) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "updateFilm");
                assertThat(f.rejection())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.UpdateRowsError.UnsupportedInputFieldShape.class);
                assertThat(f.reason()).contains("list-typed nested input types");
            }),

        DML_NESTING_LIST_REJECTED_DELETE(
            "R186: a list-typed nested input on a DELETE field is rejected with DeleteRowsError.UnsupportedInputFieldShape naming R186",
            """
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId filmId: Int! @field(name: "film_id") }
            input FilmKeyGroup { filmId: Int! @field(name: "film_id") }
            input FilmDeleteInput { keys: [FilmKeyGroup!] }
            type Query { x: String }
            type Mutation { deleteFilm(in: FilmDeleteInput!): ID @mutation(typeName: DELETE, table: "film") }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "deleteFilm");
                assertThat(f.rejection())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.DeleteRowsError.UnsupportedInputFieldShape.class);
                assertThat(f.reason()).contains("list-typed nested input types");
            }),

        DML_NESTING_LIST_REJECTED_INSERT(
            "R186: a list-typed nested input on an INSERT field is rejected with a structural Rejection naming R186",
            """
            type Film @table(name: "film") { title: String }
            input FilmTitleInput { title: String }
            input FilmInput { details: [FilmTitleInput!] }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "createFilm");
                assertThat(f.reason()).contains("list-typed nested input types");
            }),

        DML_TWO_INPUT_ARGS_REJECTED(
            "DML mutation with two input arguments → UnclassifiedField (Invariant #1)",
            """
            type Film @table(name: "film") { title: String }
            input FilmInputA { title: String }
            input FilmInputB { description: String }
            type Query { x: String }
            type Mutation { createFilm(a: FilmInputA, b: FilmInputB): Film @mutation(typeName: INSERT) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "createFilm");
                assertThat(f.reason()).contains("more than one input argument");
            }),

        DML_PLAIN_INPUT_ARG_FIELD_DERIVED_UNBINDABLE_FIELD_REJECTED(
            "INSERT with a plain (non-@table) input arg is admitted (write target derived from the @table "
                + "return); the rejection is now the precise unbindable-field diagnostic on 'reason' "
                + "(no film column), not the blanket 'only accept @table' refusal",
            """
            type Film @table(name: "film") { title: String }
            input PlainOptions { reason: String }
            type Query { x: String }
            type Mutation { createFilm(opts: PlainOptions!): Film @mutation(typeName: INSERT) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "createFilm");
                assertThat(f.reason())
                    .contains("reason")
                    .contains("has no column binding");
            }),

        DML_TABLE_RETURN_PKLESS_TABLE_REJECTED(
            "R489: @table DML return backed by a PK-less table → UnclassifiedField. The reentry "
                + "companion re-fetches the written rows by primary key (the carried correlation is "
                + "PK self-identity), so a PK-less bound table has no key to correlate on; the "
                + "rejection is the enforcer the DmlReturnExpression arms' non-null correlation "
                + "javadoc leans on.",
            """
            type FilmList @table(name: "film_list") { title: String }
            input FilmListInput { title: String }
            type Query { x: String }
            type Mutation { createFilmList(in: FilmListInput!): FilmList @mutation(typeName: INSERT) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "createFilmList");
                assertThat(f.reason())
                    .contains("table 'film_list'")
                    .contains("no primary key")
                    .contains("return ID");
            }),

        DML_TABLE_RETURN_PKLESS_TABLE_REJECTED_LIST(
            "R489: the same PK-less rejection at list cardinality (both cardinalities pass the "
                + "single buildDmlField chokepoint).",
            """
            type FilmList @table(name: "film_list") { title: String }
            input FilmListInput { title: String }
            type Query { x: String }
            type Mutation { createFilmLists(in: [FilmListInput!]!): [FilmList!]! @mutation(typeName: INSERT) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "createFilmLists");
                assertThat(f.reason())
                    .contains("table 'film_list'")
                    .contains("no primary key")
                    .contains("return ID");
            }),

        DML_INSERT_LIST_LIST_OK(
            "DML INSERT with listed input + listed @table return → DmlTableField with tia.list() == true",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput { title: String }
            type Query { x: String }
            type Mutation { createFilms(in: [FilmInput!]!): [Film!]! @mutation(typeName: INSERT) }
            """,
            schema -> {
                var f = (MutationField.DmlTableField) schema.field("Mutation", "createFilms");
                assertThat(insertInputOf(f).list()).isTrue();
                var rex = (no.sikt.graphitron.rewrite.model.DmlReturnExpression.ProjectedList) f.returnExpression();
                assertThat(rex.returnTypeName()).isEqualTo("Film");
                assertThat(rex.reentryCorrelation().targetTable().tableName()).isEqualTo("film");
                assertThat(rex.reentryCorrelation().columns())
                    .extracting(no.sikt.graphitron.rewrite.model.ColumnRef::sqlName)
                    .containsExactly("film_id");
            }),

        DML_UPDATE_LIST_LIST_OK(
            "DML UPDATE with listed input + listed @table return → DmlTableField with inputArg.list() == true",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput {
                filmId: Int! @field(name: "film_id")
                title: String
            }
            type Query { x: String }
            type Mutation { updateFilms(in: [FilmInput!]!): [Film!]! @mutation(typeName: UPDATE) }
            """,
            schema -> {
                var f = (MutationField.DmlTableField) schema.field("Mutation", "updateFilms");
                assertThat(updateArgOf(f).list()).isTrue();
                var rex = (no.sikt.graphitron.rewrite.model.DmlReturnExpression.ProjectedList) f.returnExpression();
                assertThat(rex.returnTypeName()).isEqualTo("Film");
                assertThat(rex.reentryCorrelation().targetTable().tableName()).isEqualTo("film");
                assertThat(rex.reentryCorrelation().columns())
                    .extracting(no.sikt.graphitron.rewrite.model.ColumnRef::sqlName)
                    .containsExactly("film_id");
            }),

        DML_INSERT_DISCRIMINATED_INTERFACE_SINGLE(
            "R406: INSERT returning a single-table discriminated interface → DmlTableField "
                + "carrying DmlReturnExpression.DiscriminatedSingle with the discriminator column, known "
                + "values, and participant set (not ProjectedSingle).",
            """
            interface Content @table(name: "content") @discriminate(on: "CONTENT_TYPE") {
              contentId: Int! @field(name: "CONTENT_ID")
              title: String! @field(name: "TITLE")
            }
            type FilmContent implements Content @table(name: "content") @discriminator(value: "FILM") {
              contentId: Int! @field(name: "CONTENT_ID")
              title: String! @field(name: "TITLE")
              rating: String @reference(path: [{key: "content_film_id_fkey"}]) @field(name: "RATING")
            }
            type ShortContent implements Content @table(name: "content") @discriminator(value: "SHORT") {
              contentId: Int! @field(name: "CONTENT_ID")
              title: String! @field(name: "TITLE")
              description: String @field(name: "SHORT_DESCRIPTION")
            }
            input ContentInput {
              title: String! @field(name: "TITLE")
              contentType: String! @field(name: "CONTENT_TYPE")
            }
            type Query { content: Content }
            type Mutation { createContent(in: ContentInput!): Content @mutation(typeName: INSERT, table: "content") }
            """,
            schema -> {
                var f = (MutationField.DmlTableField) schema.field("Mutation", "createContent");
                assertThat(f.returnExpression())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.DmlReturnExpression.DiscriminatedSingle.class);
                var ds = (no.sikt.graphitron.rewrite.model.DmlReturnExpression.DiscriminatedSingle) f.returnExpression();
                assertThat(ds.interfaceName()).isEqualTo("Content");
                assertThat(ds.discriminatorColumn()).isEqualToIgnoringCase("CONTENT_TYPE");
                assertThat(ds.knownDiscriminatorValues()).containsExactlyInAnyOrder("FILM", "SHORT");
                assertThat(ds.participants()).hasSize(2);
                // Regression pin: must not silently accept as ProjectedSingle.
                assertThat(f.returnExpression())
                    .isNotInstanceOf(no.sikt.graphitron.rewrite.model.DmlReturnExpression.ProjectedSingle.class);
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.DmlTableField.class); }
        },

        DML_UPDATE_DISCRIMINATED_INTERFACE_SINGLE(
            "R406: UPDATE returning a single-table discriminated interface → DmlTableField "
                + "carrying DmlReturnExpression.DiscriminatedSingle (not ProjectedSingle).",
            """
            interface Content @table(name: "content") @discriminate(on: "CONTENT_TYPE") {
              contentId: Int! @field(name: "CONTENT_ID")
              title: String! @field(name: "TITLE")
            }
            type FilmContent implements Content @table(name: "content") @discriminator(value: "FILM") {
              contentId: Int! @field(name: "CONTENT_ID")
              title: String! @field(name: "TITLE")
            }
            type ShortContent implements Content @table(name: "content") @discriminator(value: "SHORT") {
              contentId: Int! @field(name: "CONTENT_ID")
              title: String! @field(name: "TITLE")
            }
            input ContentUpdateInput {
              contentId: Int! @field(name: "CONTENT_ID")
              title: String @field(name: "TITLE")
            }
            type Query { content: Content }
            type Mutation { updateContent(in: ContentUpdateInput!): Content @mutation(typeName: UPDATE, table: "content") }
            """,
            schema -> {
                var f = (MutationField.DmlTableField) schema.field("Mutation", "updateContent");
                assertThat(f.returnExpression())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.DmlReturnExpression.DiscriminatedSingle.class);
                var ds = (no.sikt.graphitron.rewrite.model.DmlReturnExpression.DiscriminatedSingle) f.returnExpression();
                assertThat(ds.interfaceName()).isEqualTo("Content");
                assertThat(ds.discriminatorColumn()).isEqualToIgnoringCase("CONTENT_TYPE");
                assertThat(ds.knownDiscriminatorValues()).containsExactlyInAnyOrder("FILM", "SHORT");
                assertThat(f.returnExpression())
                    .isNotInstanceOf(no.sikt.graphitron.rewrite.model.DmlReturnExpression.ProjectedSingle.class);
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.DmlTableField.class); }
        },

        DML_INSERT_DISCRIMINATED_INTERFACE_LIST(
            "R406: INSERT with listed input returning a listed single-table discriminated interface "
                + "([Content!]!) → DmlTableField carrying DmlReturnExpression.DiscriminatedList "
                + "(not ProjectedList).",
            """
            interface Content @table(name: "content") @discriminate(on: "CONTENT_TYPE") {
              contentId: Int! @field(name: "CONTENT_ID")
              title: String! @field(name: "TITLE")
            }
            type FilmContent implements Content @table(name: "content") @discriminator(value: "FILM") {
              contentId: Int! @field(name: "CONTENT_ID")
              title: String! @field(name: "TITLE")
            }
            type ShortContent implements Content @table(name: "content") @discriminator(value: "SHORT") {
              contentId: Int! @field(name: "CONTENT_ID")
              title: String! @field(name: "TITLE")
            }
            input ContentInput {
              title: String! @field(name: "TITLE")
              contentType: String! @field(name: "CONTENT_TYPE")
            }
            type Query { content: Content }
            type Mutation { createContents(in: [ContentInput!]!): [Content!]! @mutation(typeName: INSERT, table: "content") }
            """,
            schema -> {
                var f = (MutationField.DmlTableField) schema.field("Mutation", "createContents");
                assertThat(insertInputOf(f).list()).isTrue();
                assertThat(f.returnExpression())
                    .isInstanceOf(no.sikt.graphitron.rewrite.model.DmlReturnExpression.DiscriminatedList.class);
                var dl = (no.sikt.graphitron.rewrite.model.DmlReturnExpression.DiscriminatedList) f.returnExpression();
                assertThat(dl.interfaceName()).isEqualTo("Content");
                assertThat(dl.knownDiscriminatorValues()).containsExactlyInAnyOrder("FILM", "SHORT");
                // The discriminated arms carry the same classified reentry correlation as the
                // projected arms: PK self-identity over the shared table's primary key.
                assertThat(dl.reentryCorrelation().targetTable().tableName()).isEqualTo("content");
                assertThat(dl.reentryCorrelation().columns())
                    .extracting(no.sikt.graphitron.rewrite.model.ColumnRef::sqlName)
                    .containsExactly("content_id");
                assertThat(f.returnExpression())
                    .isNotInstanceOf(no.sikt.graphitron.rewrite.model.DmlReturnExpression.ProjectedList.class);
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(MutationField.DmlTableField.class); }
        },

        DML_DELETE_DISCRIMINATED_INTERFACE_REJECTED(
            "R406: DELETE returning a single-table discriminated interface still rejects through the "
                + "TableBoundReturnType DELETE floor (the interface is a TableBackedType, routes through "
                + "the same arm); the row is gone and RETURNING carries only the PK. Not a Discriminated "
                + "accept.",
            """
            interface Content @table(name: "content") @discriminate(on: "CONTENT_TYPE") {
              contentId: Int! @field(name: "CONTENT_ID")
              title: String! @field(name: "TITLE")
            }
            type FilmContent implements Content @table(name: "content") @discriminator(value: "FILM") {
              contentId: Int! @field(name: "CONTENT_ID")
              title: String! @field(name: "TITLE")
            }
            type ShortContent implements Content @table(name: "content") @discriminator(value: "SHORT") {
              contentId: Int! @field(name: "CONTENT_ID")
              title: String! @field(name: "TITLE")
            }
            input ContentDeleteInput {
              contentId: Int! @field(name: "CONTENT_ID")
            }
            type Query { content: Content }
            type Mutation { deleteContent(in: ContentDeleteInput!): Content @mutation(typeName: DELETE, table: "content") }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "deleteContent");
                assertThat(f.reason()).contains(
                    "@mutation(typeName: DELETE) return type", "(@table)",
                    "RETURNING carries only the primary key", "return ID");
            }),

        DML_DELETE_LIST_TABLE_REJECTED(
            "R287: DML DELETE with a listed @table return ([Film!]!) → UnclassifiedField. DELETE cannot "
                + "project a @table (the rows are gone, RETURNING carries only the PK); only INSERT / "
                + "UPDATE / UPSERT keep the projected-@table return. Return [ID!]! instead.",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput {
                filmId: Int! @field(name: "film_id")
            }
            type Query { x: String }
            type Mutation { deleteFilms(in: [FilmInput!]!): [Film!]! @mutation(typeName: DELETE, table: "film") }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "deleteFilms");
                assertThat(f.reason()).contains(
                    "@mutation(typeName: DELETE) return type", "(@table)",
                    "RETURNING carries only the primary key", "return ID");
            }),

        DML_UPSERT_LIST_LIST_REJECTED_UNDER_R144(
            "DML UPSERT with listed input + listed @table return → UnclassifiedField (R144 refuses UPSERT pending R145)",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput {
                filmId: Int! @field(name: "film_id")
                title: String
            }
            type Query { x: String }
            type Mutation { upsertFilms(in: [FilmInput!]!): [Film!]! @mutation(typeName: UPSERT) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "upsertFilms");
                assertThat(f.reason()).contains("@mutation(typeName: UPSERT) is not yet supported");
            }),

        DML_INSERT_LIST_SINGLE_T_REJECTED(
            "DML INSERT with listed input + single @table return → UnclassifiedField (Invariant #15)",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput { title: String }
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
            input FilmInput { title: String }
            type Query { x: String }
            type Mutation { createFilms(in: [FilmInput!]!): ID @mutation(typeName: INSERT, table: "film") }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "createFilms");
                assertThat(f.reason())
                    .contains("must return a list")
                    .contains("Invariant #15");
            }),

        DML_INSERT_LIST_PAYLOAD_REJECTED(
            "DML INSERT with listed input + record-backed payload return → UnclassifiedField (Invariant #15)",
            """
            type Film @table(name: "film") { title: String }
            type FilmPayload {
                film: Film
            }
            input FilmInput { title: String }
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
            input FilmInput { title: String }
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
            "R141: DML INSERT with single input + list-shaped data field on the carrier "
                + "→ UnclassifiedField (Invariant #16). Complementary cell of the bulk-input + "
                + "list-data-field admit; surfacing the cardinality mismatch as a typed rejection "
                + "rather than letting the per-row DML emit a list against single input.",
            """
            type Film @table(name: "film") { title: String }
            type FilmsPayload { films: [Film!] }
            input FilmInput { title: String }
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
            "R141: DML INSERT with bulk input + carrier carrying a sibling field that "
                + "does not resolve to a recognized DML payload data-field shape → "
                + "UnclassifiedField. The structural scan rejects with a descriptive reason "
                + "naming the offending field and the extension point (file a roadmap item).",
            """
            type Film @table(name: "film") { title: String }
            type FilmsPayload { films: [Film!] affectedRowCount: Int }
            input FilmInput { title: String }
            type Query { x: String }
            type Mutation {
                createFilmsPayload(in: [FilmInput!]!): FilmsPayload @mutation(typeName: INSERT, table: "film")
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
            input FilmInput { title: String }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): Int @mutation(typeName: INSERT, table: "film") }
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
            input FilmInput { title: String }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): Boolean @mutation(typeName: INSERT, table: "film") }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "createFilm");
                assertThat(f.reason())
                    .contains("'Boolean' is not yet supported");
            }),

        DML_ID_RETURN_NON_NODE_TABLE_REJECTED(
            "DML mutation returning ID without a matching @node SDL type → UnclassifiedField",
            """
            input FilmInput { title: String }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): ID @mutation(typeName: INSERT, table: "film") }
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
            input FilmInput { title: String }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT) }
            """,
            schema -> {
                var f = (MutationField.DmlTableField) schema.field("Mutation", "createFilm");
                var rex = (no.sikt.graphitron.rewrite.model.DmlReturnExpression.ProjectedSingle) f.returnExpression();
                assertThat(rex.returnTypeName()).isEqualTo("Film");
                assertThat(rex.reentryCorrelation().targetTable().tableName()).isEqualTo("film");
            }),

        DML_LIST_LOOKUP_KEY_FIELD_REJECTED(
            "DML mutation with list-typed admissible carrier input field → UnclassifiedField (R144: list cardinality must live on the outer argument)",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput {
                filmIds: [Int!]! @field(name: "film_id")
                title: String
            }
            type Query { x: String }
            type Mutation { updateFilm(in: FilmInput!): Film @mutation(typeName: UPDATE) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "updateFilm");
                assertThat(f.reason()).contains("list-typed input field is not supported");
            }),

        DML_RECORD_PAYLOAD_TABLE_ELEMENT_WITH_ERRORS_REJECTS(
            "R287: DELETE returning a record-backed carrier whose data field is a @table-element (film+errors) "
                + "→ UnclassifiedField. The carrier's record-backed nature does not change the data field's "
                + "shape: a @table-element projected off a deleted row is impossible (RETURNING carries "
                + "only the PK). Use an ID-element data field.",
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
            type DeleteFilmPayload {
                film: Film
                errors: [DeleteFilmError]
            }
            input FilmInput { filmId: Int! @field(name: "film_id") }
            type Query { x: String }
            type Mutation { deleteFilm(in: FilmInput!): DeleteFilmPayload @mutation(typeName: DELETE, table: "film") }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "deleteFilm");
                assertThat(f.reason()).contains(
                    "@table-element data field", "RETURNING carries only the primary key", "ID-typed data field");
            }),

        DML_RECORD_PAYLOAD_TABLE_ELEMENT_ROW_ONLY_REJECTS(
            "R287: DELETE returning a record-backed carrier whose only data field is a @table-element (film) "
                + "→ UnclassifiedField (same reason as the with-errors sibling).",
            """
            type Film @table(name: "film") { title: String }
            type DeleteFilmPayload {
                film: Film
            }
            input FilmInput { filmId: Int! @field(name: "film_id") }
            type Query { x: String }
            type Mutation { deleteFilm(in: FilmInput!): DeleteFilmPayload @mutation(typeName: DELETE, table: "film") }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "deleteFilm");
                assertThat(f.reason()).contains(
                    "@table-element data field", "RETURNING carries only the primary key", "ID-typed data field");
            }),

        DML_RECORD_PAYLOAD_LIST_REJECTED(
            "DML returning a list of record-backed payloads → UnclassifiedField (validateReturnType, list-payload not yet supported)",
            """
            type Film @table(name: "film") { title: String }
            type DeleteFilmPayload {
                film: Film
            }
            input FilmInput { filmId: Int! @field(name: "film_id") }
            type Query { x: String }
            type Mutation { deleteFilms(in: FilmInput!): [DeleteFilmPayload] @mutation(typeName: DELETE, table: "film") }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "deleteFilms");
                assertThat(f.reason())
                    .contains("(list of record-backed payloads) is not yet supported");
            }),

        DML_RECORD_PAYLOAD_NO_DATA_FIELD_REJECTED(
            "R161: DML returning a record-backed carrier with no data-channel-shaped field → UnclassifiedField (the structural scan rejects 'data: String' as an unrecognized carrier data-field shape since String is neither @table nor ID; user's developer-supplied class is no longer inspected)",
            """
            type Film @table(name: "film") { title: String }
            type SakPayload {
                data: String
                errors: [ValidationErr]
            }
            type ValidationErr @error(handlers: [{handler: VALIDATION}]) {
                path: [String!]!
                message: String!
            }
            input FilmInput { filmId: Int! @field(name: "film_id") }
            type Query { x: String }
            type Mutation { deleteFilm(in: FilmInput!): SakPayload @mutation(typeName: DELETE, table: "film") }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "deleteFilm");
                assertThat(f.reason())
                    .contains("not a recognized DML payload data-field shape");
            }),

        // ===== New admission and rejection cases =====

        // The two bare DELETE-admission verdicts live in the spec-by-example corpus
        // (`mutation-roots` example): DELETE admits onto DmlTableField via a
        // PK-covering filter input (deleteFilm) or an explicit multiRow: true broadcast over a
        // non-PK filter (deleteFilmsBroadcast). This enum keeps DELETE_MUTATION_FIELD, which
        // asserts the DeleteRows slot detail.

        R144_INSERT_MULTIROW_REJECTED(
            "R144: multiRow: true on @mutation(typeName: INSERT) → UnclassifiedField",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput { title: String }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT, multiRow: true) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "createFilm");
                assertThat(f.reason()).contains("INSERT", "does not accept multiRow: true");
            }),

        // @value is not a declared directive (an unknown-directive parse error, not a classifier
        // rejection), so it has no coverage rows here; the @condition-on-input-field rejection is
        // covered by the cascade / walker tests
        // (r215_validatorRejectsConditionOverrideFalseOnMutationInputField and the walker unit tests).

        R246_UPDATE_MULTIROW_TRUE_DEFERRED(
            "R246: multiRow: true on a direct-@table/ID-return UPDATE → UnclassifiedField with Rejection.Deferred (empty slug); the broadcast semantics has no replacement path",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput {
                filmId: Int! @field(name: "film_id")
                title: String
            }
            type Query { x: String }
            type Mutation { updateFilms(in: FilmInput!): Film @mutation(typeName: UPDATE, multiRow: true) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "updateFilms");
                assertThat(f.rejection()).isInstanceOf(Rejection.Deferred.class);
                assertThat(f.reason()).contains("UPDATE", "multiRow: true", "not yet supported");
            }),

        R246_UPDATE_ARG_CONDITION_STRUCTURAL_REJECTED(
            "R246: arg-level @condition on a direct-@table/ID-return UPDATE @mutation field argument → UnclassifiedField with Rejection.AuthorError.Structural",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput {
                filmId: Int! @field(name: "film_id")
                title: String
            }
            type Query { x: String }
            type Mutation {
                updateFilm(
                    in: FilmInput!
                    @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "argCondition"})
                ): Film @mutation(typeName: UPDATE)
            }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "updateFilm");
                assertThat(f.rejection()).isInstanceOf(Rejection.AuthorError.Structural.class);
                assertThat(f.reason()).contains("@condition", "@mutation field argument", "not supported");
            }),

        // ===== Forbidden directive on an otherwise-valid DML payload carrier =====
        // The base SDL is the would-admit bulk-update-payload fixture
        // (UPDATE_BULK_PAYLOAD_MUTATION_FIELD); each case adds one DML-forbidden directive to the
        // data field. Under ENFORCE the scan returns NotApplicable, the carrier is never promoted,
        // and the return falls through to the ScalarReturnType arm of validateReturnType, where the
        // probe names the offending field/directive instead of the misdirected generic message.

        R310_UPDATE_PAYLOAD_SPLIT_QUERY_FORBIDDEN_REJECTED(
            "R310: @mutation(typeName: UPDATE) returning an otherwise-valid R258 bulk-update-payload "
                + "carrier whose data field carries @splitQuery (DML-forbidden) → UnclassifiedField. "
                + "Instead of the misdirected 'use ID or a @table type', the message names the data "
                + "field and @splitQuery and appends the @service-carrier asymmetry note (R275).",
            """
            type Film @table(name: "film") { title: String }
            type FilmsPayload { films: [Film!] @splitQuery }
            input FilmUpdateInput { filmId: Int! @field(name: "film_id"), title: String }
            type Query { x: String }
            type Mutation {
                updateFilmsPayload(in: [FilmUpdateInput!]!): FilmsPayload @mutation(typeName: UPDATE, table: "film")
            }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "updateFilmsPayload");
                assertThat(f.reason())
                    .contains("films", "@splitQuery", "@service")
                    .doesNotContain("use ID or a @table type");
            }),

        R310_INSERT_PAYLOAD_SPLIT_QUERY_FORBIDDEN_REJECTED(
            "R310: the same forbidden-@splitQuery carrier under @mutation(typeName: INSERT), which "
                + "reaches validateReturnType through the inline INSERT path rather than "
                + "classifyUpdateTableField. Two DML kinds through two distinct routing paths pin the "
                + "'one arm covers all DML kinds' invariant.",
            """
            type Film @table(name: "film") { title: String }
            type FilmsPayload { films: [Film!] @splitQuery }
            input FilmInput { title: String }
            type Query { x: String }
            type Mutation {
                createFilmsPayload(in: [FilmInput!]!): FilmsPayload @mutation(typeName: INSERT, table: "film")
            }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "createFilmsPayload");
                assertThat(f.reason())
                    .contains("films", "@splitQuery")
                    .doesNotContain("use ID or a @table type");
            }),

        R310_UPDATE_PAYLOAD_CONDITION_FORBIDDEN_REJECTED(
            "R310: a non-@splitQuery forbidden directive (@condition) on an otherwise-valid carrier "
                + "data field → the targeted message names @condition and does NOT carry the "
                + "@splitQuery-only asymmetry note. Pins message generality across the broad "
                + "forbidden set.",
            """
            type Film @table(name: "film") { title: String }
            type FilmsPayload { films: [Film!] @condition }
            input FilmUpdateInput { filmId: Int! @field(name: "film_id"), title: String }
            type Query { x: String }
            type Mutation {
                updateFilmsPayload(in: [FilmUpdateInput!]!): FilmsPayload @mutation(typeName: UPDATE, table: "film")
            }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "updateFilmsPayload");
                assertThat(f.reason())
                    .contains("films", "@condition")
                    .doesNotContain("@splitQuery", "use ID or a @table type");
            }),

        R310_FORBIDDEN_DIRECTIVE_ON_NON_CARRIER_FALLS_THROUGH(
            "R310 negative control (the would-admit gate): a forbidden directive (@splitQuery) on a "
                + "payload that would NOT otherwise admit — two data-channel-shaped fields — still "
                + "falls to the generic message. The probe re-runs the scan with the forbidden check "
                + "disabled; that pass Rejects on the multi-data shape, so the probe does not over-fire.",
            """
            type Film @table(name: "film") { title: String }
            type FilmsPayload { films: [Film!] @splitQuery, moreFilms: [Film!] }
            input FilmInput { title: String }
            type Query { x: String }
            type Mutation {
                createFilmsPayload(in: [FilmInput!]!): FilmsPayload @mutation(typeName: INSERT, table: "film")
            }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "createFilmsPayload");
                assertThat(f.reason())
                    .contains("use ID or a @table type")
                    .doesNotContain("@splitQuery");
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

    @Test
    @ProjectionFor(MutationField.DmlTableField.class)
    void dmlMutationProjectionCarriesKindAndTablePayload() {
        // Three independent snapshots — separate registries because the UPDATE / UPSERT input
        // shapes don't cleanly coexist with the INSERT-canonical "title" column in one fixture.
        var sIns = buildSnapshot("""
            type Film @table(name: "film") { title: String }
            input FilmInput { title: String }
            type Query { x: String }
            type Mutation { createFilm(in: FilmInput!): Film @mutation(typeName: INSERT) }
            """);
        var ins = (FieldClassification.DmlMutation) sIns.fieldClassificationsByCoord().get("Mutation.createFilm");
        assertThat(ins.kind()).isEqualTo(DmlKind.INSERT);
        assertThat(ins.tableName()).isEqualToIgnoringCase("film");
        assertThat(ins.inputTypeName()).isEqualTo("FilmInput");

        var sUpd = buildSnapshot("""
            type Film @table(name: "film") { title: String }
            input FilmInput { filmId: Int! @field(name: "film_id") title: String }
            type Query { x: String }
            type Mutation { updateFilm(in: FilmInput!): Film @mutation(typeName: UPDATE) }
            """);
        var upd = (FieldClassification.DmlMutation) sUpd.fieldClassificationsByCoord().get("Mutation.updateFilm");
        assertThat(upd.kind()).isEqualTo(DmlKind.UPDATE);

        var sDel = buildSnapshot("""
            type Film implements Node @table(name: "film") @node { id: ID! @nodeId filmId: Int! @field(name: "film_id") }
            input FilmInput { filmId: Int! @field(name: "film_id") }
            type Query { x: String }
            type Mutation { deleteFilm(in: FilmInput!): ID @mutation(typeName: DELETE, table: "film") }
            """);
        var del = (FieldClassification.DmlMutation) sDel.fieldClassificationsByCoord().get("Mutation.deleteFilm");
        assertThat(del.kind()).isEqualTo(DmlKind.DELETE);
    }

    @Test
    @ProjectionFor({
        MutationField.MutationDmlRecordField.class, MutationField.MutationBulkDmlRecordField.class
    })
    void dmlRecordProjectionCarriesBulkFlagAndKind() {
        // Single (non-bulk) DML record carrier — bulk = false.
        var s1 = buildSnapshot("""
            type Film @table(name: "film") { title: String }
            type FilmPayload { film: Film }
            input FilmCreateInput { title: String }
            type Query { x: String }
            type Mutation {
                createFilm(in: FilmCreateInput!): FilmPayload @mutation(typeName: INSERT)
            }
            """);
        var single = (FieldClassification.DmlRecord) s1.fieldClassificationsByCoord().get("Mutation.createFilm");
        assertThat(single.bulk()).isFalse();
        assertThat(single.kind()).isEqualTo(DmlKind.INSERT);
        assertThat(single.tableName()).isEqualToIgnoringCase("film");

        // Bulk DML record carrier — bulk = true.
        var s2 = buildSnapshot("""
            type Film @table(name: "film") { title: String }
            type FilmsPayload { films: [Film!] }
            input FilmCreateInput { title: String }
            type Query { x: String }
            type Mutation {
                createFilmsPayload(in: [FilmCreateInput!]!): FilmsPayload @mutation(typeName: INSERT)
            }
            """);
        var bulk = (FieldClassification.DmlRecord) s2.fieldClassificationsByCoord().get("Mutation.createFilmsPayload");
        assertThat(bulk.bulk()).isTrue();
        assertThat(bulk.kind()).isEqualTo(DmlKind.INSERT);

        // The payload-returning UPDATE leaves project to DmlRecord with kind UPDATE off the
        // slim InputArgRef (single → bulk = false, bulk → bulk = true), so the LSP catalog surfaces
        // the same hover shape as the INSERT record carriers above.
        var s3 = buildSnapshot("""
            type Film @table(name: "film") { title: String }
            type FilmPayload { film: Film }
            input FilmUpdateInput { filmId: Int! @field(name: "film_id"), title: String }
            type Query { x: String }
            type Mutation {
                updateFilmPayload(in: FilmUpdateInput!): FilmPayload @mutation(typeName: UPDATE)
            }
            """);
        var updSingle = (FieldClassification.DmlRecord) s3.fieldClassificationsByCoord().get("Mutation.updateFilmPayload");
        assertThat(updSingle.bulk()).isFalse();
        assertThat(updSingle.kind()).isEqualTo(DmlKind.UPDATE);
        assertThat(updSingle.tableName()).isEqualToIgnoringCase("film");
        assertThat(updSingle.inputTypeName()).isEqualTo("FilmUpdateInput");

        var s4 = buildSnapshot("""
            type Film @table(name: "film") { title: String }
            type FilmsPayload { films: [Film!] }
            input FilmUpdateInput { filmId: Int! @field(name: "film_id"), title: String }
            type Query { x: String }
            type Mutation {
                updateFilmsPayload(in: [FilmUpdateInput!]!): FilmsPayload @mutation(typeName: UPDATE)
            }
            """);
        var updBulk = (FieldClassification.DmlRecord) s4.fieldClassificationsByCoord().get("Mutation.updateFilmsPayload");
        assertThat(updBulk.bulk()).isTrue();
        assertThat(updBulk.kind()).isEqualTo(DmlKind.UPDATE);

        // The payload-returning DELETE leaves (MutationDmlRecordField /
        // MutationBulkDmlRecordField) project to DmlRecord with kind DELETE off the slim
        // InputArgRef, the same hover shape as the UPDATE payload carriers above. Their only
        // admissible data field is an ID-element (the @table-element projection is rejected at
        // classification), which requires the synthesised __NODE_TYPE_ID metadata absent from the default
        // sakila catalog; the produced-leaf assertions therefore live in
        // MutationDmlNodeIdClassificationTest under the nodeidfixture catalog. The @ProjectionFor
        // annotation keeps the two leaves accounted for in ProjectionCoverageTest.
    }

    @Test
    @ProjectionFor({MutationField.MutationServiceTableField.class, MutationField.MutationServiceRecordField.class})
    void mutationServiceProjectionCarriesMethodAndTableNameWhenTableBound() {
        // Table-bound service mutation — tableName must be populated for hover surface
        // parity with QueryService.
        var s1 = buildSnapshot("""
            type Film @table(name: "film") { title: String }
            type Query { x: String }
            type Mutation {
              createFilm: Film
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
            }
            """);
        var tb = (FieldClassification.MutationService) s1.fieldClassificationsByCoord().get("Mutation.createFilm");
        assertThat(tb.tableBound()).isTrue();
        assertThat(tb.tableName()).isEqualToIgnoringCase("film");

        // Record-return service mutation — tableName null, tableBound = false.
        var s2 = buildSnapshot("""
            type Query { x: String }
            type Mutation {
              ping: String
                @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"})
            }
            """);
        var rec = (FieldClassification.MutationService) s2.fieldClassificationsByCoord().get("Mutation.ping");
        assertThat(rec.tableBound()).isFalse();
        assertThat(rec.tableName()).isNull();
    }

    // ===== MutationDeletePayloadCase =====

    /**
     * Admission / rejection matrix for {@code @mutation(typeName: DELETE)} carriers. The
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

        BULK_DELETE_TABLE_NULLABLE_NON_PK_REJECTS(
            "R287: bulk DELETE + [Foo!] @table-element carrier → UnclassifiedField (DELETE -> @table is rejected at authoring; the row is gone and RETURNING carries only the PK), pointing at the ID-typed carrier shape",
            """
            type Film @table(name: "film") { title: String }
            input FilmInput { filmId: Int! @field(name: "film_id") }
            type DeletedFilmsPayload { deleted: [Film!] }
            type Query { x: String }
            type Mutation { deleteFilms(in: [FilmInput!]!): DeletedFilmsPayload @mutation(typeName: DELETE, table: "film") }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "deleteFilms");
                assertThat(f.reason()).contains(
                    "@table-element data field", "RETURNING carries only the primary key", "ID-typed data field");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        BULK_DELETE_TABLE_NON_NULL_NON_PK_REJECTS(
            "R287: DELETE + [Foo!] @table-element carrier (even with a non-null non-PK column) → UnclassifiedField; DELETE -> @table is rejected wholesale",
            """
            type Film @table(name: "film") { title: String! }
            input FilmInput { filmId: Int! @field(name: "film_id") }
            type DeletedFilmsPayload { deleted: [Film!] }
            type Query { x: String }
            type Mutation { deleteFilms(in: [FilmInput!]!): DeletedFilmsPayload @mutation(typeName: DELETE, table: "film") }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "deleteFilms");
                assertThat(f.reason()).contains(
                    "@table-element data field", "RETURNING carries only the primary key", "ID-typed data field");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        INSERT_ID_PAYLOAD_REJECTS(
            "INSERT + [ID!] carrier → UnclassifiedField (the ID-typed PK-echo shape is admitted only on DELETE)",
            """
            type Film @table(name: "film") @node(typeId: "Film", keyColumns: ["film_id"]) { id: ID! @nodeId title: String }
            input FilmInput { title: String }
            type InsertedFilmsPayload { insertedIds: [ID!] }
            type Query { x: String }
            type Mutation { insertFilms(in: [FilmInput!]!): InsertedFilmsPayload @mutation(typeName: INSERT, table: "film") }
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
            input FilmInput { filmId: Int! @field(name: "film_id") }
            type DeletedFilmsPayload { deletedIds: [ID] }
            type Query { x: String }
            type Mutation { deleteFilms(in: [FilmInput!]!): DeletedFilmsPayload @mutation(typeName: DELETE, table: "film") }
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
            input FilmInput { filmId: Int! @field(name: "film_id") title: String }
            type UpdatedFilmsPayload { updatedIds: [ID!] }
            type Query { x: String }
            type Mutation { updateFilms(in: [FilmInput!]!): UpdatedFilmsPayload @mutation(typeName: UPDATE, table: "film") }
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
            input FilmInput { filmId: Int! @field(name: "film_id") title: String }
            type UpsertedFilmsPayload { upsertedIds: [ID!] }
            type Query { x: String }
            type Mutation { upsertFilms(in: [FilmInput!]!): UpsertedFilmsPayload @mutation(typeName: UPSERT) }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "upsertFilms");
                // The rejection message comes from either the permit-verb gate or
                // the upstream UPSERT-defer (whichever fires first); both surface as
                // UnclassifiedField, both name the verb.
                assertThat(f.reason()).contains("UPSERT");
            }) {
            @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedField.class); }
        },

        DELETE_TABLE_SERVICE_FIELD_REJECTS(
            "R287: DELETE + [Foo!] @table-element carrier (even with a @service-resolved field on the element type) → UnclassifiedField; DELETE -> @table is rejected wholesale",
            """
            type Film @table(name: "film") {
                title: String
                computedThing: String @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"})
            }
            input FilmInput { filmId: Int! @field(name: "film_id") }
            type DeletedFilmsPayload { deleted: [Film!] }
            type Query { x: String }
            type Mutation { deleteFilms(in: [FilmInput!]!): DeletedFilmsPayload @mutation(typeName: DELETE, table: "film") }
            """,
            schema -> {
                var f = (UnclassifiedField) schema.field("Mutation", "deleteFilms");
                assertThat(f.reason()).contains(
                    "@table-element data field", "RETURNING carries only the primary key", "ID-typed data field");
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

    @Test
    @ProjectionFor({
        ChildField.SingleRecordIdField.class
    })
    void singleRecordCarrierProjectionsCarryTablePayload() {
        // The single-record DML carrier data field classifies as BatchedTableField, which
        // projects as RecordTableTarget (the INSERT shape).
        var s1 = buildSnapshot("""
            type Film @table(name: "film") { title: String }
            type FilmPayload { film: Film }
            input FilmCreateInput { title: String }
            type Query { x: String }
            type Mutation {
                createFilm(in: FilmCreateInput!): FilmPayload @mutation(typeName: INSERT)
            }
            """);
        var carrier = (FieldClassification.RecordTableTarget) s1.fieldClassificationsByCoord().get("FilmPayload.film");
        assertThat(carrier.tableName()).isEqualToIgnoringCase("film");

        // SingleRecordIdField — the @service carrier's @nodeId-from-record data field,
        // encoding node ids off the producer's in-memory records (no re-fetch).
        var s3 = buildSnapshot("""
            type Film implements Node @node @table(name: "film") { id: ID! @nodeId  title: String }
            type FilmErr @error(handlers: [{handler: GENERIC, className: "java.lang.IllegalArgumentException"}]) {
                path: [String!]!
                message: String!
            }
            union DeleteFilmsError = FilmErr
            type FilmIdsPayload {
                filmIds: [ID] @nodeId(typeName: "Film")
                errors: [DeleteFilmsError]
            }
            type Query { x: String }
            type Mutation {
                deleteFilms: FilmIdsPayload @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilmsAsList"})
            }
            """);
        var serviceId = (FieldClassification.SingleRecordId)
            s3.fieldClassificationsByCoord().get("FilmIdsPayload.filmIds");
        assertThat(serviceId.tableName()).isEqualToIgnoringCase("film");
    }

    // ===== UnclassifiedField =====

    enum UnclassifiedFieldCase implements ClassificationCase {

        // The dangling-reference backstop (the rejectDanglingTypeReferences pass): an @service
        // mutation returning a payload whose ONLY field is the errors union (the opptak
        // FjernSakTaggerPayload with the data field commented out). The structural carrier scan
        // sees zero data fields (NotApplicable), no producer binding grounds, and the type never
        // registers; without the backstop the mutation field would still classify
        // MutationServiceRecordField and emit typeRef("FjernPayload"), failing graphql-java
        // assembly with "type FjernPayload not found in schema". The shape-agnostic
        // dangling-reference pass surfaces the orphan on the validation diagnostic channel.
        SERVICE_MUTATION_ERRORS_ONLY_ORPHAN_PAYLOAD_REJECTED(
            "@service mutation returning an errors-only payload (no data field) is a build-time "
                + "author error, never a dangling typeRef in the assembled schema",
            """
            type SakErr @error(handlers: [{handler: GENERIC, className: "java.lang.IllegalArgumentException"}]) {
                path: [String!]!
                message: String!
            }
            union FjernError = SakErr
            type FjernPayload {
                errors: [FjernError]
            }
            type Query { x: String }
            type Mutation {
                fjern: FjernPayload @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"})
            }
            """,
            schema -> {
                // The dangling-reference backstop keeps the field's real verdict; the
                // rejection rides the validation diagnostic channel.
                var f = schema.field("Mutation", "fjern");
                assertThat(f).isNotInstanceOf(UnclassifiedField.class);
                assertThat(new GraphitronSchemaValidator().validate(schema))
                    .extracting(ValidationError::message)
                    .anyMatch(m -> m.contains("FjernPayload")
                        && m.contains("did not classify") && m.contains("not found in schema"));
            }),

        // The dangling-reference backstop, generic arm: a @service mutation returning a directiveless
        // SDL Object that is not carrier-shaped at all (scalar-only fields scan Reject). The
        // dangling-reference pass catches it regardless of shape.
        SERVICE_MUTATION_UNRECOGNIZED_ORPHAN_PAYLOAD_REJECTED(
            "@service mutation returning an unrecognized directiveless SDL Object is a build-time "
                + "author error, never a dangling typeRef in the assembled schema",
            """
            type LoosePayload {
                note: String
            }
            type Query { x: String }
            type Mutation {
                doIt: LoosePayload @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "get"})
            }
            """,
            schema -> {
                // See SERVICE_MUTATION_ERRORS_ONLY_ORPHAN_PAYLOAD_REJECTED: the
                // backstop registers a diagnostic instead of demoting the field.
                var f = schema.field("Mutation", "doIt");
                assertThat(f).isNotInstanceOf(UnclassifiedField.class);
                assertThat(new GraphitronSchemaValidator().validate(schema))
                    .extracting(ValidationError::message)
                    .anyMatch(m -> m.contains("LoosePayload")
                        && m.contains("did not classify") && m.contains("not found in schema"));
            }),

        // The edge-decidable orphan: an @service mutation returning an [ID] @nodeId
        // carrier (the SERVICE_MUTATION_ID_CARRIER_ENCODES_FROM_RECORD shape) whose producer return
        // (List<LanguageRecord>) does not match the @nodeId(typeName: "Film") target's record
        // (FilmRecord), so no ServiceEmitted binding grounds and carrierTableBinding stays null. The
        // carrier scan still Admits the shape (IdElement), so this is a recognized-but-unbound carrier:
        // a definitively-orphan shape the producing edge owns and rejects directly with the richer
        // ID-element guidance, never reaching the shape-agnostic backstop. Unlike the two backstop
        // cases above (reason "did not classify into the model ... not found in schema"), the edge
        // verdict is registry-free (it reads typeBuilder.carrierTableBinding, not ctx.types), which
        // keeps the verdict order-independent. The assertion pins that the EDGE produced the
        // verdict (the ID-element reason is present and the backstop's "not found in schema"
        // signature is absent).
        SERVICE_MUTATION_ID_CARRIER_UNBOUND_ORPHAN_REJECTED_AT_EDGE(
            "@service mutation returning an [ID] @nodeId carrier whose producer return does not ground "
                + "the binding is rejected at the producing edge, not by the dangling backstop",
            """
            type Film implements Node @node @table(name: "film") { id: ID! @nodeId  title: String }
            type SakErr @error(handlers: [{handler: GENERIC, className: "java.lang.IllegalArgumentException"}]) {
                path: [String!]!
                message: String!
            }
            union DeleteFilmsError = SakErr
            type FilmIdsPayload {
                filmIds: [ID] @nodeId(typeName: "Film")
                errors: [DeleteFilmsError]
            }
            type Query { x: String }
            type Mutation {
                deleteFilms: FilmIdsPayload @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getLanguagesAsList"})
            }
            """,
            schema -> {
                var f = schema.field("Mutation", "deleteFilms");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("FilmIdsPayload", "did not classify", "ID-element carrier",
                        "no @service producer binding grounds it")
                    .doesNotContain("not found in schema");
            }),

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
                titleUppercase: String @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "childServiceRecordWrapWrongScalarValue"})
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

        // The @service "Disagrees" directive-ignored warning case lives in
        // RecordDirectiveIgnoredWarningTest; reflection-wins-over-the-directive binding is covered
        // by R96RecordBindingPipelineTest without any applied @record.

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
            type FilmDetails { title: String }
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
            type FilmDetails { title: String }
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
            type FilmDetails { title: String }
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
            type FilmDetails { title: String }
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
            type FilmDetails { title: String }
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
            type FilmDetails { title: String }
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
            }),

        // ===== @field(name:) input-bean binding rejections =====

        SERVICE_INPUT_BEAN_RECORD_COMPONENT_UNBOUND_REJECTED(
            "R200 direction A: a record component with no SDL field bound to it → UnclassifiedField; the canonical constructor needs every component (the old loop emitted a silent under-arity call)",
            """
            enum TestInputBeanEnum { LOW HIGH }
            input MissingNestedComponentInput { title: String, rating: TestInputBeanEnum }
            type FilmDetails { title: String }
            type Query { x: String }
            type Mutation {
                runWithInputBean(input: MissingNestedComponentInput): FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runWithInputBean"})
            }
            """,
            schema -> {
                var f = schema.field("Mutation", "runWithInputBean");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("TestInputBean")
                    .contains("component 'nested'")
                    .contains("every record component must bind");
            }),

        SERVICE_INPUT_BEAN_RECORD_FIELD_UNCONSUMED_REJECTED(
            "R200 direction B: an SDL input field that binds to no record component → UnclassifiedField; a record's input correspondence is total, so an unconsumed field (silent data drop) fails, unlike a JavaBean's tolerated partial population",
            """
            input SubsetRecordInput { a: String, b: String, c: String }
            type FilmDetails { title: String }
            type Query { x: String }
            type Mutation {
                runWithSubsetRecord(input: SubsetRecordInput): FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runWithSubsetRecord"})
            }
            """,
            schema -> {
                var f = schema.field("Mutation", "runWithSubsetRecord");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("TestInputSubsetRecord")
                    .contains("'c'")
                    .contains("names no component");
            }),

        SERVICE_INPUT_BEAN_FIELD_BINDING_AMBIGUOUS_REJECTED(
            "R200: two SDL input fields resolving to one Java-member binding key (a @field(name:) collision) → UnclassifiedField; one member cannot be populated by two fields",
            """
            input AmbiguousBindingInput { title: String @field(name: "heading"), subtitle: String @field(name: "heading"), rating: Int @field(name: "score") }
            type FilmDetails { title: String }
            type Query { x: String }
            type Mutation {
                runWithRenamedRecord(input: AmbiguousBindingInput): FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runWithRenamedRecord"})
            }
            """,
            schema -> {
                var f = schema.field("Mutation", "runWithRenamedRecord");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("'title'")
                    .contains("'subtitle'")
                    .contains("heading")
                    .contains("both bind to Java member");
            }),

        SERVICE_INPUT_BEAN_JAVABEAN_DIVERGENT_NO_FIELD_REJECTED(
            "R200 regression floor: a JavaBean whose property names diverge from the SDL field names and carry NO @field still rejects with 'has no fields matching' — @field is the only bridge, its absence must not start matching by coincidence",
            """
            input UnbridgedJavaBeanInput { title: String, rating: Int }
            type FilmDetails { title: String }
            type Query { x: String }
            type Mutation {
                runWithRenamedJavaBean(input: UnbridgedJavaBeanInput): FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runWithRenamedJavaBean"})
            }
            """,
            schema -> {
                var f = schema.field("Mutation", "runWithRenamedJavaBean");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("TestInputJavaBeanRenamed")
                    .contains("has no fields matching the SDL input type");
            }),

        SERVICE_INPUT_BEAN_BLANK_FIELD_NAME_REJECTED(
            "R200: a present-but-blank @field(name: \"\") on an input-bean field → UnclassifiedField; the malformed directive is rejected at classify time, not silently skipped on the JavaBean arm",
            """
            input BlankFieldNameInput { title: String @field(name: ""), rating: Int @field(name: "score") }
            type FilmDetails { title: String }
            type Query { x: String }
            type Mutation {
                runWithRenamedJavaBean(input: BlankFieldNameInput): FilmDetails
                    @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runWithRenamedJavaBean"})
            }
            """,
            schema -> {
                var f = schema.field("Mutation", "runWithRenamedJavaBean");
                assertThat(f).isInstanceOf(UnclassifiedField.class);
                assertThat(((UnclassifiedField) f).reason())
                    .contains("'title'")
                    .contains("@field(name:) with a blank value");
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

    @Test
    @ProjectionFor({UnclassifiedField.class, UnclassifiedType.class})
    void unclassifiedProjectionCarriesRejectionReason() {
        // UnclassifiedField — rejection message rides on the projection.
        var s1 = buildSnapshot("""
            type Film @table(name: "film") { doesNotExist: String }
            type Query { film: Film }
            """);
        var fld = (FieldClassification.Unresolvable) s1.fieldClassificationsByCoord().get("Film.doesNotExist");
        assertThat(fld.reason()).isNotBlank();

        // UnclassifiedType — same shape on the type side.
        var s2 = buildSnapshot("""
            type NoSuchTable @table(name: "no_such_table") { title: String }
            type Query { x: NoSuchTable }
            """);
        var typ = (TypeClassification.Unclassified) s2.typeClassificationsByName().get("NoSuchTable");
        assertThat(typ.reason()).isNotBlank();
    }

    // ===== Directive mutual exclusivity =====
    // Mutually exclusive claiming directives no longer tombstone at the builder: every claim is
    // a row in the store's authored claim views, and the conflict is the grouping rule in
    // no.sikt.graphitron.rewrite.derive.AuthoredClaimConflicts, reported as a located
    // ValidationError while the coordinate classifies by arm order. The conflict fixtures live
    // with that detection's store-backed test (AuthoredClaimConflictsTest).

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
    // The field name disambiguates sibling fields reaching the same table via different FKs; the
    // step index disambiguates hops within one multi-hop path. Uniqueness matters for flat batch
    // JOINs, where aliases are injected into a shared SELECT and a duplicate is a SQL error
    // (MULTISET correlated subqueries each get their own SQL scope regardless).

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
                var fk = TestFixtures.fkHop(tf.joinPath().get(0));
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
                var step0 = TestFixtures.fkHop(tf.joinPath().get(0));
                var step1 = TestFixtures.fkHop(tf.joinPath().get(1));
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
                var fk1 = TestFixtures.fkHop(((TableField) schema.field("Film", "language")).joinPath().get(0));
                var fk2 = TestFixtures.fkHop(((TableField) schema.field("Film", "originalLanguage")).joinPath().get(0));
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

    @Test
    @ProjectionFor({ConnectionType.class, EdgeType.class, PageInfoType.class})
    void connectionEdgePageInfoProjectionsCarryElementShape() {
        var snapshot = buildSnapshot("""
            type Film @table(name: "film") { id: ID }
            type Query { films: [Film!]! @asConnection }
            """);
        var conn = (TypeClassification.Connection) snapshot.typeClassificationsByName().get("QueryFilmsConnection");
        assertThat(conn.elementTypeName()).isEqualTo("Film");
        assertThat(conn.edgeTypeName()).isEqualTo("QueryFilmsEdge");

        var edge = (TypeClassification.Edge) snapshot.typeClassificationsByName().get("QueryFilmsEdge");
        assertThat(edge.elementTypeName()).isEqualTo("Film");

        assertThat(snapshot.typeClassificationsByName().get("PageInfo"))
            .isInstanceOf(TypeClassification.PageInfo.class);
    }

    // ===== Plain object type classification =====

    enum NestingTypeCase implements ClassificationCase {
        NESTED_UNDER_TABLE_PARENT(
            "directiveless object nested under a @table parent classifies as NestingType (R276: a "
            + "NestingType is assigned only via the NestingField that embeds it)",
            """
            type Inner { title: String }
            type Film @table(name: "film") { details: Inner }
            type Query { film: Film }
            """,
            schema -> {
                var t = (no.sikt.graphitron.rewrite.model.GraphitronType.NestingType)
                    schema.type("Inner");
                assertThat(t.name()).isEqualTo("Inner");
                assertThat(t.schemaType()).isNotNull();
                assertThat(t.schemaType().getName()).isEqualTo("Inner");
                assertThat(t.schemaType().getFieldDefinition("title")).isNotNull();
            });

        // The `@table type classifies as TableType, not NestingType` robustness verdict lives in
        // the spec-by-example corpus: a type's classification is a single exclusive verdict, so
        // the positive @classifiedType(as: TableType) on the `catalog` example's @table Film
        // subsumes the isNotInstanceOf(NestingType) negative. NestingType stays covered by
        // NESTED_UNDER_TABLE_PARENT above.

        final String sdl;
        final Consumer<GraphitronSchema> assertions;
        NestingTypeCase(String description, String sdl, Consumer<GraphitronSchema> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() {
            return Set.of(no.sikt.graphitron.rewrite.model.GraphitronType.NestingType.class);
        }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(NestingTypeCase.class)
    void plainObjectTypeClassification(NestingTypeCase tc) {
        tc.assertions.accept(build(tc.sdl));
    }

    @Test
    @ProjectionFor(no.sikt.graphitron.rewrite.model.GraphitronType.NestingType.class)
    void plainObjectTypeProjectionIsZeroPayload() {
        var snapshot = buildSnapshot("""
            type Inner { title: String }
            type Film @table(name: "film") { details: Inner }
            type Query { film: Film }
            """);
        assertThat(snapshot.typeClassificationsByName().get("Inner"))
            .isInstanceOf(TypeClassification.PlainObject.class);
    }

    @Test
    @ProjectionFor({no.sikt.graphitron.rewrite.model.GraphitronType.FacetsType.class,
        no.sikt.graphitron.rewrite.model.GraphitronType.FacetValueType.class})
    void facetTypeProjectionsArePlainObjects() {
        // The synthesised facet container and value types project as PlainObject (like
        // NestingType) so they stay describable through the CatalogBuilder seam; no
        // facet-specific classification leaf exists.
        var snapshot = buildSnapshot("""
            type Film @table(name: "film") { title: String }
            input FilmFilter {
                title: [String!] @field(name: "title") @asFacet
            }
            type Query {
                films(filter: FilmFilter): [Film!]! @asConnection @defaultOrder(primaryKey: true)
            }
            """);
        assertThat(snapshot.typeClassificationsByName().get("QueryFilmsConnectionFacets"))
            .isInstanceOf(TypeClassification.PlainObject.class);
        assertThat(snapshot.typeClassificationsByName().get("StringFacetValue"))
            .isInstanceOf(TypeClassification.PlainObject.class);
    }

    // ===== Enum type classification =====

    enum EnumTypeCase implements ClassificationCase {
        PLAIN_ENUM(
            "SDL-declared enum classifies as EnumType with pre-resolved value specs and GraphQLEnumType retained",
            """
            enum Status { ACTIVE INACTIVE }
            type Query { s: Status }
            """,
            schema -> {
                var t = (no.sikt.graphitron.rewrite.model.GraphitronType.EnumType)
                    schema.type("Status");
                assertThat(t.name()).isEqualTo("Status");
                assertThat(t.schemaType()).isNotNull();
                assertThat(t.values()).extracting(v -> v.sdlName())
                    .containsExactly("ACTIVE", "INACTIVE");
                // No @field(name:) → runtimeValue falls back to sdlName
                assertThat(t.values()).allSatisfy(v ->
                    assertThat(v.runtimeValue()).isEqualTo(v.sdlName()));
                assertThat(t.values()).allSatisfy(v -> assertThat(v.source()).isNotNull());
            }),

        ENUM_WITH_DEPRECATED_VALUE(
            "deprecation on an enum value lands on the EnumValueSpec's deprecationReason",
            """
            enum Status { ACTIVE OLD @deprecated(reason: "unused") }
            type Query { s: Status }
            """,
            schema -> {
                var t = (no.sikt.graphitron.rewrite.model.GraphitronType.EnumType)
                    schema.type("Status");
                var old = t.values().stream().filter(v -> v.sdlName().equals("OLD")).findFirst().orElseThrow();
                assertThat(old.deprecationReason()).isEqualTo("unused");
                var active = t.values().stream().filter(v -> v.sdlName().equals("ACTIVE")).findFirst().orElseThrow();
                assertThat(active.deprecationReason()).isNull();
            }),

        ENUM_WITH_FIELD_NAME_DIRECTIVE(
            "R229: @field(name:) on an enum value lifts to EnumValueSpec.runtimeValue at classify time",
            """
            enum PersonIdentifikasjon {
                FODSELSNUMMER @field(name: "FØDSELSNUMMER")
                ANNET
            }
            type Query { id: PersonIdentifikasjon }
            """,
            schema -> {
                var t = (no.sikt.graphitron.rewrite.model.GraphitronType.EnumType)
                    schema.type("PersonIdentifikasjon");
                var fodsel = t.values().stream().filter(v -> v.sdlName().equals("FODSELSNUMMER")).findFirst().orElseThrow();
                assertThat(fodsel.runtimeValue()).isEqualTo("FØDSELSNUMMER");
                var annet = t.values().stream().filter(v -> v.sdlName().equals("ANNET")).findFirst().orElseThrow();
                assertThat(annet.runtimeValue()).isEqualTo("ANNET");
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

    @Test
    @ProjectionFor(no.sikt.graphitron.rewrite.model.GraphitronType.EnumType.class)
    void enumTypeProjectionIsZeroPayload() {
        var snapshot = buildSnapshot("""
            enum Status { ACTIVE INACTIVE }
            type Query { s: Status }
            """);
        assertThat(snapshot.typeClassificationsByName().get("Status"))
            .isInstanceOf(TypeClassification.Enum.class);
    }

    // ===== Case-insensitive type-name collision =====

    enum CaseInsensitiveTypeClashCase implements ClassificationCase {
        SDL_VS_SDL(
            "two SDL types differing only in case keep their classified verdict; the collision surfaces "
                + "as full-group validation diagnostics (R317 slice 5; was demote-to-UnclassifiedType)",
            """
            type Poengklasse @table(name: "film") { v: String }
            type poengklasse @table(name: "film") { v: String }
            type Query { a: Poengklasse b: poengklasse }
            """,
            (schema, sdl) -> {
                // The case-fold collision does not demote; verdicts stay real and the
                // collision rides the validation channel.
                assertThat(schema.type("Poengklasse")).isNotInstanceOf(UnclassifiedType.class);
                assertThat(schema.type("poengklasse")).isNotInstanceOf(UnclassifiedType.class);
                var errors = new GraphitronSchemaValidator().validate(schema);
                assertThat(errors)
                    .filteredOn(e -> e.message().contains("case-insensitively"))
                    .extracting(ValidationError::kind, ValidationError::message)
                    .containsExactlyInAnyOrder(
                        tuple(RejectionKind.INVALID_SCHEMA,
                            "Type 'Poengklasse': collides case-insensitively with 'Poengklasse', 'poengklasse'"
                                + "; rename one of the colliding types (case-only differences are not portable across case-insensitive filesystems)"),
                        tuple(RejectionKind.INVALID_SCHEMA,
                            "Type 'poengklasse': collides case-insensitively with 'Poengklasse', 'poengklasse'"
                                + "; rename one of the colliding types (case-only differences are not portable across case-insensitive filesystems)"));
            }),

        SYNTH_VS_SYNTH(
            "two @asConnection carriers whose synth Connection-names differ only in case demote both",
            """
            type Item @table(name: "film") { v: String }
            type Query {
                foo: [Item!]! @asConnection(connectionName: "FooConnection")
                bar: [Item!]! @asConnection(connectionName: "fooConnection")
            }
            """,
            (schema, sdl) -> {
                assertThat(schema.type("FooConnection")).isNotInstanceOf(UnclassifiedType.class);
                assertThat(schema.type("fooConnection")).isNotInstanceOf(UnclassifiedType.class);
                var errors = new GraphitronSchemaValidator().validate(schema);
                assertThat(errors)
                    .filteredOn(e -> e.message().contains("case-insensitively"))
                    .extracting(ValidationError::kind, ValidationError::message)
                    .contains(
                        tuple(RejectionKind.INVALID_SCHEMA,
                            "Type 'FooConnection': synthesised connection type collides case-insensitively with 'FooConnection', 'fooConnection'"
                                + "; rename the source field or set @asConnection(connectionName: \"...\") to a name that is unique under case-folding"),
                        tuple(RejectionKind.INVALID_SCHEMA,
                            "Type 'fooConnection': synthesised connection type collides case-insensitively with 'FooConnection', 'fooConnection'"
                                + "; rename the source field or set @asConnection(connectionName: \"...\") to a name that is unique under case-folding"));
                // Synthesised Connection's SourceLocation pins at the @asConnection carrier
                // field. preludeLineCount accounts for directives.graphqls + the Node-interface
                // line the test helper prepends; line 3 of the user SDL is the `foo` carrier and
                // line 4 is `bar`. Column 5 is "    foo:" (4-space indent + 1-indexed column).
                int preludeLines = TestSchemaHelper.preludeLineCount(sdl);
                var fooErr = errors.stream()
                    .filter(e -> e.coordinate().equals("FooConnection") && e.message().contains("case-insensitively"))
                    .findFirst().orElseThrow();
                assertThat(fooErr.location()).isNotNull();
                assertThat(fooErr.location().getLine()).isEqualTo(preludeLines + 3);
                assertThat(fooErr.location().getColumn()).isEqualTo(5);
                var barErr = errors.stream()
                    .filter(e -> e.coordinate().equals("fooConnection") && e.message().contains("case-insensitively"))
                    .findFirst().orElseThrow();
                assertThat(barErr.location()).isNotNull();
                assertThat(barErr.location().getLine()).isEqualTo(preludeLines + 4);
                assertThat(barErr.location().getColumn()).isEqualTo(5);
            }),

        SDL_VS_SYNTH(
            "an SDL type whose name case-equals a synthesised Connection-name demotes both",
            """
            type Item @table(name: "film") { v: String }
            type fooConnection @table(name: "film") { v: String }
            type Query {
                foo: [Item!]! @asConnection(connectionName: "FooConnection")
                stash: fooConnection
            }
            """,
            (schema, sdl) -> {
                assertThat(schema.type("FooConnection")).isNotInstanceOf(UnclassifiedType.class);
                assertThat(schema.type("fooConnection")).isNotInstanceOf(UnclassifiedType.class);
                var errors = new GraphitronSchemaValidator().validate(schema);
                var clashMessages = errors.stream()
                    .filter(e -> e.message().contains("case-insensitively"))
                    .map(ValidationError::message)
                    .toList();
                assertThat(clashMessages)
                    .anyMatch(m -> m.startsWith("Type 'FooConnection':")
                        && m.contains("synthesised connection type")
                        && m.contains("'FooConnection'") && m.contains("'fooConnection'"))
                    .anyMatch(m -> m.startsWith("Type 'fooConnection':")
                        && m.contains("collides case-insensitively")
                        && m.contains("'FooConnection'") && m.contains("'fooConnection'"));
                // Synth-Connection side carries the @asConnection carrier-field location;
                // SDL side carries its own parse location.
                var synthErr = errors.stream()
                    .filter(e -> e.coordinate().equals("FooConnection") && e.message().contains("case-insensitively"))
                    .findFirst().orElseThrow();
                assertThat(synthErr.location()).isNotNull();
                assertThat(synthErr.location().getColumn()).isEqualTo(5);
                var sdlErr = errors.stream()
                    .filter(e -> e.coordinate().equals("fooConnection") && e.message().contains("case-insensitively"))
                    .findFirst().orElseThrow();
                assertThat(sdlErr.location()).isNotNull();
            }),

        SYNTH_EDGE_VS_SDL(
            "an SDL type case-equal to a synthesised Edge name demotes both via the SYNTH_EDGE origin arm",
            """
            type Item @table(name: "film") { v: String }
            type fooEdge @table(name: "film") { v: String }
            type Query {
                foo: [Item!]! @asConnection(connectionName: "FooConnection")
                stash: fooEdge
            }
            """,
            (schema, sdl) -> {
                assertThat(schema.type("FooEdge")).isNotInstanceOf(UnclassifiedType.class);
                assertThat(schema.type("fooEdge")).isNotInstanceOf(UnclassifiedType.class);
                var errors = new GraphitronSchemaValidator().validate(schema);
                var clashMessages = errors.stream()
                    .filter(e -> e.message().contains("case-insensitively"))
                    .map(ValidationError::message)
                    .toList();
                assertThat(clashMessages)
                    .anyMatch(m -> m.startsWith("Type 'FooEdge':")
                        && m.contains("synthesised edge type")
                        && m.contains("'FooEdge'") && m.contains("'fooEdge'"))
                    .anyMatch(m -> m.startsWith("Type 'fooEdge':")
                        && m.contains("collides case-insensitively")
                        && m.contains("'FooEdge'") && m.contains("'fooEdge'"));
                // Synth-Edge side carries the @asConnection carrier-field location;
                // SDL side carries its own parse location.
                var synthErr = errors.stream()
                    .filter(e -> e.coordinate().equals("FooEdge") && e.message().contains("case-insensitively"))
                    .findFirst().orElseThrow();
                assertThat(synthErr.location()).isNotNull();
                assertThat(synthErr.location().getColumn()).isEqualTo(5);
                var sdlErr = errors.stream()
                    .filter(e -> e.coordinate().equals("fooEdge") && e.message().contains("case-insensitively"))
                    .findFirst().orElseThrow();
                assertThat(sdlErr.location()).isNotNull();
            }),

        SYNTH_PAGE_INFO_VS_SDL(
            "an SDL type case-equal to the synthesised PageInfo demotes both via the SYNTH_PAGE_INFO origin arm",
            """
            type Item @table(name: "film") { v: String }
            type pageInfo @table(name: "film") { v: String }
            type Query {
                foo: [Item!]! @asConnection
                stash: pageInfo
            }
            """,
            (schema, sdl) -> {
                assertThat(schema.type("PageInfo")).isNotInstanceOf(UnclassifiedType.class);
                assertThat(schema.type("pageInfo")).isNotInstanceOf(UnclassifiedType.class);
                var errors = new GraphitronSchemaValidator().validate(schema);
                var clashMessages = errors.stream()
                    .filter(e -> e.message().contains("case-insensitively"))
                    .map(ValidationError::message)
                    .toList();
                assertThat(clashMessages)
                    .anyMatch(m -> m.startsWith("Type 'PageInfo':")
                        && m.contains("synthesised PageInfo type")
                        && m.contains("'PageInfo'") && m.contains("'pageInfo'"))
                    .anyMatch(m -> m.startsWith("Type 'pageInfo':")
                        && m.contains("collides case-insensitively")
                        && m.contains("'PageInfo'") && m.contains("'pageInfo'"));
                // Synth-PageInfo deliberately has null location. A single PageInfo serves
                // every connection in the schema, so no carrier site is the actionable one; the
                // SDL member's parse location anchors the diagnostic.
                var synthErr = errors.stream()
                    .filter(e -> e.coordinate().equals("PageInfo") && e.message().contains("case-insensitively"))
                    .findFirst().orElseThrow();
                assertThat(synthErr.location()).isNull();
                var sdlErr = errors.stream()
                    .filter(e -> e.coordinate().equals("pageInfo") && e.message().contains("case-insensitively"))
                    .findFirst().orElseThrow();
                assertThat(sdlErr.location()).isNotNull();
            }),

        THREE_WAY_GROUP(
            "three case-equivalent SDL types each surface a ValidationError naming all three",
            """
            type Foo @table(name: "film") { v: String }
            type FOO @table(name: "film") { v: String }
            type foo @table(name: "film") { v: String }
            type Query { a: Foo b: FOO c: foo }
            """,
            (schema, sdl) -> {
                assertThat(schema.type("Foo")).isNotInstanceOf(UnclassifiedType.class);
                assertThat(schema.type("FOO")).isNotInstanceOf(UnclassifiedType.class);
                assertThat(schema.type("foo")).isNotInstanceOf(UnclassifiedType.class);
                var errors = new GraphitronSchemaValidator().validate(schema);
                var clashMessages = errors.stream()
                    .filter(e -> e.message().contains("case-insensitively"))
                    .map(ValidationError::message)
                    .toList();
                // Every member's message names the full collision group.
                assertThat(clashMessages).hasSize(3)
                    .allMatch(m -> m.contains("'Foo'") && m.contains("'FOO'") && m.contains("'foo'"));
                assertThat(clashMessages).anyMatch(m -> m.startsWith("Type 'Foo':"));
                assertThat(clashMessages).anyMatch(m -> m.startsWith("Type 'FOO':"));
                assertThat(clashMessages).anyMatch(m -> m.startsWith("Type 'foo':"));
            }),

        NO_CLASH_BASELINE(
            "distinct type names without case-equivalence produce no collision diagnostics",
            """
            type Foo @table(name: "film") { v: String }
            type Bar @table(name: "film") { v: String }
            type Query { a: Foo b: Bar }
            """,
            (schema, sdl) -> {
                assertThat(schema.type("Foo")).isNotInstanceOf(UnclassifiedType.class);
                assertThat(schema.type("Bar")).isNotInstanceOf(UnclassifiedType.class);
                var errors = new GraphitronSchemaValidator().validate(schema);
                assertThat(errors)
                    .extracting(ValidationError::message)
                    .noneMatch(m -> m.contains("case-insensitively"));
            });

        final String sdl;
        final BiConsumer<GraphitronSchema, String> assertions;
        CaseInsensitiveTypeClashCase(String description, String sdl, BiConsumer<GraphitronSchema, String> assertions) {
            this.sdl = sdl;
            this.assertions = assertions;
        }
        @Override public Set<Class<?>> variants() { return Set.of(UnclassifiedType.class); }
        @Override public String toString() { return name().toLowerCase().replace('_', ' '); }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(CaseInsensitiveTypeClashCase.class)
    void caseInsensitiveTypeClash(CaseInsensitiveTypeClashCase tc) {
        tc.assertions.accept(build(tc.sdl), tc.sdl);
    }

    // ===== Helper =====

    private GraphitronSchema build(String schemaText) {
        return TestSchemaHelper.buildSchema(schemaText);
    }

    /**
     * Builds an {@link LspSchemaSnapshot.Built} for the per-block sibling
     * projection assertions. Mirrors {@link #build(String)} but routes through
     * {@link CatalogBuilder#buildSnapshot} so the snapshot carries the same
     * {@link FieldClassification} / {@link TypeClassification} projections the LSP
     * consumes; the sibling projection assertions use it to verify the projection's
     * payload is faithful to the classifier's outcome.
     */
    private LspSchemaSnapshot.Built buildSnapshot(String schemaText) {
        var ctx = TestConfiguration.testContext();
        TypeDefinitionRegistry registry = TestSchemaHelper.parseRegistryWithPrelude(schemaText);
        var bundle = GraphitronSchemaBuilder.buildBundle(registry, ctx);
        var jooq = new no.sikt.graphitron.rewrite.JooqCatalog(ctx.jooqPackage());
        var catalog = CatalogBuilder.build(jooq, bundle.assembled(), ctx);
        return CatalogBuilder.buildSnapshot(registry, bundle.model(), catalog);
    }

    /**
     * The keyed lookup payload off a fetch leaf's resolution; asserts the keyed arm first so a
     * fixture that silently stopped resolving its lookup fails with the discriminating message
     * rather than a class cast.
     */
    private static no.sikt.graphitron.rewrite.model.LookupMapping keyedMappingOf(
            no.sikt.graphitron.rewrite.model.LookupResolution lookup) {
        assertThat(lookup)
            .as("the coordinate must resolve a keyed lookup")
            .isInstanceOf(no.sikt.graphitron.rewrite.model.LookupResolution.Keyed.class);
        return ((no.sikt.graphitron.rewrite.model.LookupResolution.Keyed) lookup).mapping();
    }

}
