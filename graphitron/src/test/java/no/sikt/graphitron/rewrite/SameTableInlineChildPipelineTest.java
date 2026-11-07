package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.command.CallWrap;
import no.sikt.graphitron.command.Contribution;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.On;
import no.sikt.graphitron.rewrite.model.ParentCorrelation;
import no.sikt.graphitron.rewrite.model.Presence.NullContract;
import no.sikt.graphitron.rewrite.model.Presence.PresenceLeaf;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A child field whose return type is a {@code @table} type bound to its parent's own table. Two
 * {@code @table} types on one table are a join, so the author states it: naming the table's own
 * primary key in {@code @reference} joins each row to itself and reads the parent's own row, an
 * ordinary {@link ChildField.TableField} over an {@link On.Keying.PrimaryKey} hop. With no
 * {@code @reference} the field takes the table's one self-referencing key, forward from a
 * single-valued field and in reverse from a list; on a table with none it is rejected, whatever
 * its arguments filter.
 */
@PipelineTier
class SameTableInlineChildPipelineTest {

    private static final String KEY_DIRECTIVE =
        "directive @key(fields: String!, resolvable: Boolean = true) repeatable on OBJECT | INTERFACE\n";

    private static final String SAME_ROW = """
        type FilmSameRow @table(name: "film") { title: String }
        """;

    private static void assertPrimaryKeyHop(ChildField.TableField f, String keyName, List<String> columns) {
        assertThat(f.joinPath()).singleElement().isInstanceOfSatisfying(JoinStep.Hop.class, hop -> {
            assertThat(hop.on()).isInstanceOfSatisfying(On.ColumnPairs.class, cp -> {
                assertThat(cp.keying()).isEqualTo(new On.Keying.PrimaryKey(keyName));
                assertThat(cp.sourceSideColumns()).extracting(c -> c.sqlName()).isEqualTo(columns);
                assertThat(cp.targetSideColumns()).extracting(c -> c.sqlName()).isEqualTo(columns);
            });
        });
        assertThat(f.parentCorrelation()).isInstanceOf(ParentCorrelation.OnFkSlots.class);
    }

    private static List<String> errorsAt(GraphitronSchema schema, String coordinate) {
        return new GraphitronSchemaValidator().validate(schema).stream()
            .filter(e -> coordinate.equals(e.coordinate()))
            .map(ValidationError::message)
            .toList();
    }

    @Nested
    class PrimaryKeyReference {

        @Test
        void bySqlName_classifiesAsTableFieldOverTheIdentityJoin() {
            var schema = TestSchemaHelper.buildSchema(SAME_ROW + """
                type Film @table(name: "film") {
                    title: String
                    sameRow: FilmSameRow @reference(path: [{key: "film_pkey"}])
                }
                type Query { films: [Film!]! }
                """);

            assertThat(schema.field("Film", "sameRow"))
                .isInstanceOfSatisfying(ChildField.TableField.class, f ->
                    assertPrimaryKeyHop(f, "film_pkey", List.of("film_id")));
            assertThat(errorsAt(schema, "Film.sameRow")).isEmpty();
        }

        @Test
        void byJooqConstant_classifiesAsTheSameHop() {
            var schema = TestSchemaHelper.buildSchema(SAME_ROW + """
                type Film @table(name: "film") {
                    title: String
                    sameRow: FilmSameRow @reference(path: [{key: "FILM_PKEY"}])
                }
                type Query { films: [Film!]! }
                """);

            assertThat(schema.field("Film", "sameRow"))
                .isInstanceOfSatisfying(ChildField.TableField.class, f ->
                    assertPrimaryKeyHop(f, "film_pkey", List.of("film_id")));
        }

        @Test
        void underANestedObject_classifiesAndValidates() {
            var schema = TestSchemaHelper.buildSchema(SAME_ROW + """
                type FilmNest { row: FilmSameRow @reference(path: [{key: "film_pkey"}]) }
                type Film @table(name: "film") {
                    title: String
                    nest: FilmNest
                }
                type Query { films: [Film!]! }
                """);

            assertThat(schema.field("Film", "nest"))
                .isInstanceOfSatisfying(ChildField.NestingField.class, nf ->
                    assertThat(nf.nestedFields()).singleElement()
                        .isInstanceOfSatisfying(ChildField.TableField.class, f ->
                            assertPrimaryKeyHop(f, "film_pkey", List.of("film_id"))));
            assertThat(new GraphitronSchemaValidator().validate(schema)).isEmpty();
        }

        @Test
        void underKeyEntityParent_classifies() {
            var schema = TestSchemaHelper.buildSchema(KEY_DIRECTIVE + SAME_ROW + """
                type Film @table(name: "film") @key(fields: "title") {
                    title: String
                    sameRow: FilmSameRow @reference(path: [{key: "film_pkey"}])
                }
                type Query { x: String }
                """);

            assertThat(schema.field("Film", "sameRow"))
                .isInstanceOfSatisfying(ChildField.TableField.class, f ->
                    assertPrimaryKeyHop(f, "film_pkey", List.of("film_id")));
        }

        @Test
        void splitQuery_isABatchKeyedOnTheParentsPrimaryKey() {
            var schema = TestSchemaHelper.buildSchema(SAME_ROW + """
                type Film @table(name: "film") {
                    title: String
                    sameRow: FilmSameRow @splitQuery @reference(path: [{key: "film_pkey"}])
                }
                type Query { films: [Film!]! }
                """);

            assertThat(schema.field("Film", "sameRow"))
                .isInstanceOfSatisfying(ChildField.BatchedTableField.class, f ->
                    assertThat(f.sourceKey().columns()).extracting(c -> c.sqlName())
                        .containsExactly("film_id"));
            assertThat(errorsAt(schema, "Film.sameRow")).isEmpty();
        }

        @Test
        void compositeKey_pairsEveryColumnInKeyOrder() {
            var schema = TestSchemaHelper.buildSchema("""
                type FilmActorRow @table(name: "film_actor") { lastUpdate: String @field(name: "last_update") }
                type FilmActor @table(name: "film_actor") {
                    row: FilmActorRow @reference(path: [{key: "film_actor_pkey"}])
                }
                type Query { filmActors: [FilmActor!]! }
                """);

            assertThat(schema.field("FilmActor", "row"))
                .isInstanceOfSatisfying(ChildField.TableField.class, f ->
                    assertPrimaryKeyHop(f, "film_actor_pkey", List.of("actor_id", "film_id")));
        }

        @Test
        void selfReferencingTable_primaryKeyAndForeignKeyEachResolveToTheirOwn() {
            var schema = TestSchemaHelper.buildSchema("""
                type CategoryRow @table(name: "category") { name: String }
                type Category @table(name: "category") {
                    name: String
                    row: CategoryRow @reference(path: [{key: "category_pkey"}])
                    parent: CategoryRow @reference(path: [{key: "category_parent_category_id_fkey"}])
                }
                type Query { categories: [Category!]! }
                """);

            assertThat(schema.field("Category", "row"))
                .isInstanceOfSatisfying(ChildField.TableField.class, f ->
                    assertPrimaryKeyHop(f, "category_pkey", List.of("category_id")));
            assertThat(schema.field("Category", "parent"))
                .isInstanceOfSatisfying(ChildField.TableField.class, f ->
                    assertThat(f.joinPath()).singleElement().isInstanceOfSatisfying(JoinStep.Hop.class, hop ->
                        assertThat(((On.ColumnPairs) hop.on()).keying())
                            .isInstanceOf(On.Keying.ForeignKey.class)));
        }

        @Test
        void conditionReference_onASameTableSingleField_classifiesInline() {
            var schema = TestSchemaHelper.buildSchema(SAME_ROW + """
                type Film @table(name: "film") {
                    title: String
                    sameRow: FilmSameRow @reference(path: [{condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "join"}}])
                }
                type Query { films: [Film!]! }
                """);

            assertThat(schema.field("Film", "sameRow"))
                .isInstanceOfSatisfying(ChildField.TableField.class, f ->
                    assertThat(f.joinPath()).singleElement().isInstanceOfSatisfying(JoinStep.Hop.class, hop ->
                        assertThat(hop.on()).isInstanceOf(On.Predicate.class)));
        }
    }

    @Nested
    class PrimaryKeyRejections {

        private void assertRejected(String schemaText, String type, String field, String... fragments) {
            var schema = TestSchemaHelper.buildSchema(schemaText);
            assertThat(errorsAt(schema, type + "." + field)).singleElement()
                .satisfies(m -> assertThat(m).contains(fragments));
        }

        @Test
        void withASecondElement_rejected() {
            assertRejected(SAME_ROW + """
                type Language @table(name: "language") { name: String }
                type Film @table(name: "film") {
                    title: String
                    lang: Language @reference(path: [{key: "film_pkey"}, {key: "film_language_id_fkey"}])
                }
                type Query { films: [Film!]! }
                """, "Film", "lang", "key 'film_pkey' names the primary key of 'film'", "not admitted here");
        }

        @Test
        void anotherTablesPrimaryKey_rejected() {
            assertRejected(SAME_ROW + """
                type Film @table(name: "film") {
                    title: String
                    sameRow: FilmSameRow @reference(path: [{key: "actor_pkey"}])
                }
                type Query { films: [Film!]! }
                """, "Film", "sameRow", "key 'actor_pkey' names the primary key of 'actor', not of 'film'");
        }

        @Test
        void returnTableDiffers_rejected() {
            assertRejected("""
                type Language @table(name: "language") { name: String }
                type Film @table(name: "film") {
                    title: String
                    lang: Language @reference(path: [{key: "film_pkey"}])
                }
                type Query { films: [Film!]! }
                """, "Film", "lang", "the field's return type is bound to 'language'");
        }

        @Test
        void atAnArgumentSite_rejected() {
            var schema = TestSchemaHelper.buildSchema(SAME_ROW + """
                type Film @table(name: "film") {
                    title: String
                    sameRow(title: String @reference(path: [{key: "film_pkey"}])): FilmSameRow @reference(path: [{key: "film_pkey"}])
                }
                type Query { films: [Film!]! }
                """);
            assertThat(errorsAt(schema, "Film.sameRow"))
                .anySatisfy(m -> assertThat(m).contains("names the primary key of 'film'"));
        }

        @Test
        void onAList_rejected() {
            assertRejected(SAME_ROW + """
                type Film @table(name: "film") {
                    title: String
                    rows: [FilmSameRow] @reference(path: [{key: "film_pkey"}])
                }
                type Query { films: [Film!]! }
                """, "Film", "rows", "reads exactly one row", "make the field single-valued");
        }

        @Test
        void misspelled_hintsThePrimaryKey() {
            assertRejected(SAME_ROW + """
                type Film @table(name: "film") {
                    title: String
                    sameRow: FilmSameRow @reference(path: [{key: "film_pkye"}])
                }
                type Query { films: [Film!]! }
                """, "Film", "sameRow", "key 'film_pkye' could not be resolved", "film_pkey");
        }
    }

    @Nested
    class Unreferenced {

        @Test
        void singleArgumentFree_rejectedWithBothRemedies() {
            var schema = TestSchemaHelper.buildSchema(SAME_ROW + """
                type Film @table(name: "film") {
                    title: String
                    names: FilmSameRow
                }
                type Query { films: [Film!]! }
                """);

            assertThat(schema.field("Film", "names"))
                .isInstanceOfSatisfying(UnclassifiedField.class, f ->
                    assertThat(f.rejection()).isInstanceOf(Rejection.AuthorError.Structural.class));
            assertThat(errorsAt(schema, "Film.names")).singleElement()
                .satisfies(m -> assertThat(m)
                    .startsWith("Field 'Film.names': 'FilmSameRow' is bound to the same table as 'Film' ('film')")
                    .contains("two @table types on one table are a join")
                    .contains("drop @table from 'FilmSameRow'")
                    .contains("@reference(path: [{key: \"film_pkey\"}])")
                    .doesNotContain("@lookupKey")
                    .doesNotContain("narrow"));
        }

        @Test
        void tableWithoutAPrimaryKey_offersTheCondition() {
            var schema = TestSchemaHelper.buildSchema("""
                type RoleRow @table(name: "role_assignment") { roleName: String @field(name: "role_name") }
                type Role @table(name: "role_assignment") {
                    roleCode: String @field(name: "role_code")
                    row: RoleRow
                }
                type Query { roles: [Role!]! }
                """);

            assertThat(errorsAt(schema, "Role.row")).singleElement()
                .satisfies(m -> assertThat(m)
                    .contains("@reference(path: [{condition: ...}])")
                    .doesNotContain("primary key"));
        }

        @Test
        void list_rejectedWithoutThePrimaryKey() {
            var schema = TestSchemaHelper.buildSchema(SAME_ROW + """
                type Film @table(name: "film") {
                    title: String
                    everyFilm: [FilmSameRow!]!
                }
                type Query { films: [Film!]! }
                """);

            assertThat(errorsAt(schema, "Film.everyFilm")).singleElement()
                .satisfies(m -> assertThat(m)
                    .contains("('film') and the field has no @reference")
                    .contains("@reference(path: [{key: ...}])")
                    .contains("@reference(path: [{condition: ...}])")
                    .doesNotContain("film_pkey")
                    .doesNotContain("narrow"));
        }

        @Test
        void listWithOnlyFirst_rejected() {
            var schema = TestSchemaHelper.buildSchema(SAME_ROW + """
                type Film @table(name: "film") {
                    title: String
                    someFilms(first: Int): [FilmSameRow!]!
                }
                type Query { films: [Film!]! }
                """);

            assertThat(schema.field("Film", "someFilms")).isInstanceOf(UnclassifiedField.class);
        }

        @Test
        void singleWithOnlyAnOrderArgument_rejected() {
            var schema = TestSchemaHelper.buildSchema(SAME_ROW + """
                enum FilmSort { TITLE @order(fields: [{name: "title"}]) }
                input FilmOrderBy { field: FilmSort!  direction: SortDirection }
                type Film @table(name: "film") {
                    title: String
                    firstFilm(order: FilmOrderBy @orderBy): FilmSameRow
                }
                type Query { films: [Film!]! }
                """);

            assertThat(errorsAt(schema, "Film.firstFilm")).singleElement()
                .satisfies(m -> assertThat(m)
                    .contains("pair none of them with the parent")
                    .contains("film_pkey")
                    .doesNotContain("@lookupKey"));
        }
    }

    @Nested
    class Narrowed {

        private static void assertRejected(GraphitronSchema schema, String fieldName) {
            assertThat(schema.field("Film", fieldName))
                .isInstanceOfSatisfying(UnclassifiedField.class, f ->
                    assertThat(f.rejection()).isInstanceOf(Rejection.AuthorError.Structural.class));
            assertThat(errorsAt(schema, "Film." + fieldName)).singleElement()
                .satisfies(m -> assertThat(m)
                    .contains("'FilmSameRow' is bound to the same table as 'Film' ('film')")
                    .contains("two @table types on one table are a join"));
        }

        @Test
        void singleWithFieldLevelCondition_rejected() {
            var schema = TestSchemaHelper.buildSchema(SAME_ROW + """
                type Film @table(name: "film") {
                    title: String
                    picked: FilmSameRow
                        @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "lifterFieldCondition"})
                }
                type Query { films: [Film!]! }
                """);

            assertRejected(schema, "picked");
        }

        @Test
        void singleWithAnArgumentFilter_rejectedWithThePrimaryKey() {
            var schema = TestSchemaHelper.buildSchema(SAME_ROW + """
                type Film @table(name: "film") {
                    title: String
                    byTitle(title: String): FilmSameRow
                }
                type Query { films: [Film!]! }
                """);

            assertRejected(schema, "byTitle");
            assertThat(errorsAt(schema, "Film.byTitle")).singleElement()
                .satisfies(m -> assertThat(m)
                    .contains("pair none of them with the parent")
                    .contains("@reference(path: [{key: \"film_pkey\"}])"));
        }

        @Test
        void listWithArgumentFilter_rejected() {
            var schema = TestSchemaHelper.buildSchema(SAME_ROW + """
                type Film @table(name: "film") {
                    title: String
                    related(title: String): [FilmSameRow!]
                }
                type Query { films: [Film!]! }
                """);

            assertRejected(schema, "related");
        }

        @Test
        void listLookupKey_rejected() {
            var schema = TestSchemaHelper.buildSchema(SAME_ROW + """
                type Film @table(name: "film") {
                    title: String
                    byId(film_id: [Int!]! @lookupKey): [FilmSameRow!]!
                }
                type Query { films: [Film!]! }
                """);

            assertRejected(schema, "byId");
        }
    }

    @Nested
    class SelfReferencingKeyInferred {

        private static final String CATEGORY = """
            type Category @table(name: "category") {
                name: String
                parent: Category
                children: [Category!]!
                parentNamed(name: String): Category
            }
            type Query { categories: [Category!]! }
            """;

        private static void assertSelfKeyHop(ChildField.TableField f, String sourceColumn, String targetColumn) {
            assertThat(f.joinPath()).singleElement().isInstanceOfSatisfying(JoinStep.Hop.class, hop ->
                assertThat(hop.on()).isInstanceOfSatisfying(On.ColumnPairs.class, cp -> {
                    assertThat(cp.keying()).isInstanceOfSatisfying(On.Keying.ForeignKey.class, k ->
                        assertThat(k.fk().sqlName()).isEqualTo("category_parent_category_id_fkey"));
                    assertThat(cp.sourceSideColumns()).extracting(c -> c.sqlName()).containsExactly(sourceColumn);
                    assertThat(cp.targetSideColumns()).extracting(c -> c.sqlName()).containsExactly(targetColumn);
                }));
            assertThat(f.parentCorrelation()).isInstanceOf(ParentCorrelation.OnFkSlots.class);
        }

        @Test
        void single_followsTheKeyForwardToTheRowItPointsAt() {
            var schema = TestSchemaHelper.buildSchema(CATEGORY);

            assertThat(schema.field("Category", "parent"))
                .isInstanceOfSatisfying(ChildField.TableField.class, f ->
                    assertSelfKeyHop(f, "parent_category_id", "category_id"));
            assertThat(errorsAt(schema, "Category.parent")).isEmpty();
        }

        @Test
        void list_followsTheKeyInReverseToTheRowsPointingHere() {
            var schema = TestSchemaHelper.buildSchema(CATEGORY);

            assertThat(schema.field("Category", "children"))
                .isInstanceOfSatisfying(ChildField.TableField.class, f ->
                    assertSelfKeyHop(f, "category_id", "parent_category_id"));
            assertThat(errorsAt(schema, "Category.children")).isEmpty();
        }

        @Test
        void inferredHop_isTheHopTheWrittenKeyResolvesTo() {
            var inferred = TestSchemaHelper.buildSchema(CATEGORY);
            var written = TestSchemaHelper.buildSchema("""
                type Category @table(name: "category") {
                    name: String
                    parent: Category @reference(path: [{key: "category_parent_category_id_fkey"}])
                    children: [Category!]! @reference(path: [{key: "category_parent_category_id_fkey"}])
                }
                type Query { categories: [Category!]! }
                """);

            for (var field : List.of("parent", "children")) {
                assertThat(((ChildField.TableField) inferred.field("Category", field)).joinPath())
                    .isEqualTo(((ChildField.TableField) written.field("Category", field)).joinPath());
            }
        }

        @Test
        void narrowedSingle_stillCorrelatesOverTheKey() {
            var schema = TestSchemaHelper.buildSchema(CATEGORY);

            assertThat(schema.field("Category", "parentNamed"))
                .isInstanceOfSatisfying(ChildField.TableField.class, f -> {
                    assertSelfKeyHop(f, "parent_category_id", "category_id");
                    assertThat(f.filters()).isNotEmpty();
                });
        }

        @Test
        void splitList_batchesOnTheParentsKey() {
            var schema = TestSchemaHelper.buildSchema("""
                type Category @table(name: "category") {
                    name: String
                    children: [Category!]! @splitQuery
                }
                type Query { categories: [Category!]! }
                """);

            assertThat(schema.field("Category", "children"))
                .isInstanceOfSatisfying(ChildField.BatchedTableField.class, f ->
                    assertThat(f.sourceKey().columns()).extracting(c -> c.sqlName())
                        .containsExactly("category_id"));
            assertThat(errorsAt(schema, "Category.children")).isEmpty();
        }
    }

    @Nested
    class NullContractOverTheReturnType {

        private static NullContract contractOf(GraphitronSchema schema, String type, String fieldName) {
            return schema.nullContractOf((ChildField.TableField) schema.field(type, fieldName));
        }

        @Test
        void columnLeaves_collapseWhenAbsent() {
            var schema = TestSchemaHelper.buildSchema("""
                type FilmYears @table(name: "film") {
                    releaseYear: Int @field(name: "release_year")
                    originalLanguageId: Int @field(name: "original_language_id")
                }
                type Film @table(name: "film") {
                    title: String
                    years: FilmYears @reference(path: [{key: "film_pkey"}])
                }
                type Query { films: [Film!]! }
                """);

            assertThat(contractOf(schema, "Film", "years"))
                .isInstanceOfSatisfying(NullContract.CollapsesWhenAbsent.class, c ->
                    assertThat(c.columnLeaves()).extracting(PresenceLeaf.Columns::columns)
                        .extracting(cols -> cols.getFirst().sqlName())
                        .containsExactly("release_year", "original_language_id"));
        }

        @Test
        void nonNullField_alwaysObject() {
            var schema = TestSchemaHelper.buildSchema(SAME_ROW + """
                type Film @table(name: "film") {
                    title: String
                    sameRow: FilmSameRow! @reference(path: [{key: "film_pkey"}])
                }
                type Query { films: [Film!]! }
                """);

            assertThat(contractOf(schema, "Film", "sameRow"))
                .isEqualTo(new NullContract.AlwaysObject(new NullContract.Reason.NonNull()));
        }

        @Test
        void listChild_probesInsideTheMultiset() {
            var schema = TestSchemaHelper.buildSchema("""
                type Actor @table(name: "actor") { actorId: Int @field(name: "actor_id") }
                type FilmCast @table(name: "film") {
                    title: String
                    actors: [Actor!]! @reference(path: [
                        {key: "film_actor_film_id_fkey"},
                        {key: "film_actor_actor_id_fkey"}
                    ])
                }
                type Film @table(name: "film") {
                    title: String
                    cast: FilmCast @reference(path: [{key: "film_pkey"}])
                }
                type Query { films: [Film!]! }
                """);

            assertThat(contractOf(schema, "Film", "cast"))
                .isInstanceOfSatisfying(NullContract.CollapsesWhenAbsent.class, c ->
                    assertThat(c.probed()).singleElement()
                        .isInstanceOfSatisfying(PresenceLeaf.Probe.class, p -> assertThat(p.field().name()).isEqualTo("actors")));
            var wrap = (CallWrap.Multiset) ((Contribution.Call) ProjectionRenderTestSupport.contribution(
                schema, DEFAULT_OUTPUT_PACKAGE, "Film", "cast")).wrap();
            assertThat(wrap.presence()).extracting(c -> c.sqlName()).containsExactly("title");
            assertThat(wrap.probe()).isPresent();
        }

        @Test
        void namesInEveryLanguageShape_columnsAndAReference_collapsesThroughItsProbe() {
            // The sis Karakter.navnAlleSprak shape, migrated to the primary-key reference: a
            // nullable same-table child whose type reads columns of the parent row and also holds
            // a reference, which counts by the row it reaches, not by its join column.
            var schema = TestSchemaHelper.buildSchema("""
                type Language @table(name: "language") { name: String }
                type FilmNames @table(name: "film") {
                    nb: String @field(name: "title")
                    en: String @field(name: "description")
                    language: Language @reference(path: [{key: "film_original_language_id_fkey"}])
                }
                type Film @table(name: "film") {
                    title: String
                    names: FilmNames @reference(path: [{key: "film_pkey"}])
                }
                type Query { films: [Film!]! }
                """);

            assertThat(contractOf(schema, "Film", "names"))
                .isInstanceOfSatisfying(NullContract.CollapsesWhenAbsent.class, c -> {
                    assertThat(c.presenceColumns()).extracting(col -> col.sqlName())
                        .containsExactly("title", "description");
                    assertThat(c.probed()).singleElement()
                        .isInstanceOfSatisfying(PresenceLeaf.Probe.class, p -> assertThat(p.targetColumns()).extracting(col -> col.sqlName())
                            .containsExactly("name"));
                });
        }

        @Test
        void sameTableCycle_overTwoPrimaryKeyReferences_terminates() {
            var schema = TestSchemaHelper.buildSchema("""
                type FilmProfile @table(name: "film") {
                    title: String
                    film: Film @reference(path: [{key: "film_pkey"}])
                }
                type Film @table(name: "film") {
                    title: String
                    profile: FilmProfile @reference(path: [{key: "film_pkey"}])
                }
                type Query { films: [Film!]! }
                """);

            assertThat(schema.field("FilmProfile", "film")).isInstanceOf(ChildField.TableField.class);
            assertThat(contractOf(schema, "Film", "profile"))
                .isInstanceOfSatisfying(NullContract.CollapsesWhenAbsent.class, c -> {
                    assertThat(c.presenceColumns()).extracting(col -> col.sqlName()).containsExactly("title");
                    assertThat(c.probed()).singleElement()
                        .isInstanceOfSatisfying(PresenceLeaf.Probe.class, p -> assertThat(p.field().name()).isEqualTo("film"));
                });
            assertThat(errorsAt(schema, "Film.profile")).isEmpty();
        }
    }
}
