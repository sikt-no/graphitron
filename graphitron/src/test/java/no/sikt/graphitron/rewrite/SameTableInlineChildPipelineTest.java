package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ChildField.NestingField.NullContract;
import no.sikt.graphitron.rewrite.model.ChildField.NestingField.PresenceLeaf;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.LookupResolution;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An inline child field whose return type is bound to its parent's own table, with no
 * {@code @reference}, has no correlation to the parent. Narrowed by its own filters or lookup it
 * keeps its documented read of the table, the same rows under every parent; single-valued and
 * argument-free it reads the parent's own row ({@link ChildField.SameRowTableField}); any other
 * shape would read every row of the table under every parent and is rejected.
 */
@PipelineTier
class SameTableInlineChildPipelineTest {

    private static final String KEY_DIRECTIVE =
        "directive @key(fields: String!, resolvable: Boolean = true) repeatable on OBJECT | INTERFACE\n";

    private static final String SAME_ROW = """
        type FilmSameRow @table(name: "film") { title: String }
        """;

    private static void assertUnnarrowedRejection(GraphitronSchema schema, String fieldName, String shape) {
        assertThat(schema.field("Film", fieldName))
            .isInstanceOfSatisfying(UnclassifiedField.class, f -> {
                assertThat(f.rejection()).isInstanceOf(Rejection.AuthorError.Structural.class);
                assertThat(f.rejection().message())
                    .startsWith("'FilmSameRow' is bound to the same table as 'Film' ('film')")
                    .contains("has no @reference")
                    .contains(shape + ", so it would read every row of 'film' under every parent")
                    .contains("single-valued with no arguments")
                    .contains("@reference(path: [{key: ...}])")
                    .endsWith("an argument, @condition or @lookupKey.");
            });
    }

    @Nested
    class ParentsOwnRow {

        @Test
        void singleArgumentFree_classifiesAsSameRow() {
            var schema = TestSchemaHelper.buildSchema(SAME_ROW + """
                type Film @table(name: "film") {
                    title: String
                    sameRow: FilmSameRow
                }
                type Query { films: [Film!]! }
                """);

            assertThat(schema.field("Film", "sameRow"))
                .isInstanceOfSatisfying(ChildField.SameRowTableField.class, f -> {
                    assertThat(f.returnType().returnTypeName()).isEqualTo("FilmSameRow");
                    assertThat(f.returnType().table().tableName()).isEqualTo("film");
                });
            assertThat(new GraphitronSchemaValidator().validate(schema))
                .filteredOn(e -> "Film.sameRow".equals(e.coordinate()))
                .isEmpty();
        }

        @Test
        void underKeyEntityParent_classifiesAsSameRow() {
            var schema = TestSchemaHelper.buildSchema(KEY_DIRECTIVE + SAME_ROW + """
                type Film @table(name: "film") @key(fields: "title") {
                    title: String
                    sameRow: FilmSameRow
                }
                type Query { x: String }
                """);

            assertThat(schema.field("Film", "sameRow")).isInstanceOf(ChildField.SameRowTableField.class);
        }
    }

    @Nested
    class Unnarrowed {

        @Test
        void list_rejected() {
            var schema = TestSchemaHelper.buildSchema(SAME_ROW + """
                type Film @table(name: "film") {
                    title: String
                    everyFilm: [FilmSameRow!]!
                }
                type Query { films: [Film!]! }
                """);

            assertUnnarrowedRejection(schema, "everyFilm", "the field is a list");
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

            assertUnnarrowedRejection(schema, "someFilms", "the field is a list");
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

            assertUnnarrowedRejection(schema, "firstFilm", "the field takes arguments that filter nothing");
        }

        @Test
        void rejection_surfacesThroughValidatorOutput() {
            var schema = TestSchemaHelper.buildSchema(SAME_ROW + """
                type Film @table(name: "film") {
                    title: String
                    everyFilm: [FilmSameRow!]!
                }
                type Query { films: [Film!]! }
                """);

            assertThat(new GraphitronSchemaValidator().validate(schema))
                .filteredOn(e -> "Film.everyFilm".equals(e.coordinate()))
                .extracting(ValidationError::message)
                .singleElement()
                .satisfies(m -> assertThat(m)
                    .startsWith("Field 'Film.everyFilm': 'FilmSameRow' is bound to the same table as 'Film'")
                    .contains("('film')"));
        }
    }

    @Nested
    class Narrowed {

        private static void assertStandaloneTableField(GraphitronSchema schema, String fieldName) {
            assertThat(schema.field("Film", fieldName))
                .isInstanceOfSatisfying(ChildField.TableField.class, f -> {
                    assertThat(f.joinPath()).isEmpty();
                    assertThat(f.parentCorrelation()).isNull();
                });
        }

        @Test
        void singleWithFieldLevelCondition_keepsTheTableRead() {
            var schema = TestSchemaHelper.buildSchema(SAME_ROW + """
                type Film @table(name: "film") {
                    title: String
                    picked: FilmSameRow
                        @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "lifterFieldCondition"})
                }
                type Query { films: [Film!]! }
                """);

            assertStandaloneTableField(schema, "picked");
        }

        @Test
        void listWithArgumentFilter_keepsTheTableRead() {
            var schema = TestSchemaHelper.buildSchema(SAME_ROW + """
                type Film @table(name: "film") {
                    title: String
                    related(title: String): [FilmSameRow!]
                }
                type Query { films: [Film!]! }
                """);

            assertStandaloneTableField(schema, "related");
            assertThat(((ChildField.TableField) schema.field("Film", "related")).filters()).isNotEmpty();
        }

        @Test
        void listLookupKey_keepsTheTableRead() {
            var schema = TestSchemaHelper.buildSchema(SAME_ROW + """
                type Film @table(name: "film") {
                    title: String
                    byId(film_id: [Int!]! @lookupKey): [FilmSameRow!]!
                }
                type Query { films: [Film!]! }
                """);

            assertStandaloneTableField(schema, "byId");
            assertThat(((ChildField.TableField) schema.field("Film", "byId")).lookup())
                .isNotInstanceOf(LookupResolution.None.class);
        }
    }

    @Nested
    class NullContractOverTheReturnType {

        private static NullContract contractOf(GraphitronSchema schema, String fieldName) {
            return schema.sameRowNullContract(
                (ChildField.SameRowTableField) schema.field("Film", fieldName));
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
                    years: FilmYears
                }
                type Query { films: [Film!]! }
                """);

            assertThat(contractOf(schema, "years"))
                .isInstanceOfSatisfying(NullContract.CollapsesWhenAbsent.class, c ->
                    assertThat(c.leaves()).extracting(PresenceLeaf::columns)
                        .extracting(cols -> cols.getFirst().sqlName())
                        .containsExactly("release_year", "original_language_id"));
        }

        @Test
        void nonNullField_alwaysObject() {
            var schema = TestSchemaHelper.buildSchema(SAME_ROW + """
                type Film @table(name: "film") {
                    title: String
                    sameRow: FilmSameRow!
                }
                type Query { films: [Film!]! }
                """);

            assertThat(contractOf(schema, "sameRow"))
                .isEqualTo(new NullContract.AlwaysObject(new NullContract.Reason.NonNull()));
        }

        @Test
        void listChild_alwaysObject() {
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
                    cast: FilmCast
                }
                type Query { films: [Film!]! }
                """);

            assertThat(contractOf(schema, "cast"))
                .isInstanceOfSatisfying(NullContract.AlwaysObject.class, c ->
                    assertThat(c.reason()).isInstanceOfSatisfying(NullContract.Reason.BlockedBy.class,
                        b -> assertThat(b.blocker().name()).isEqualTo("actors")));
        }

        @Test
        void sameTableCycle_terminatesAsAlwaysObject() {
            var schema = TestSchemaHelper.buildSchema("""
                type FilmProfile @table(name: "film") {
                    title: String
                    film: Film
                }
                type Film @table(name: "film") {
                    title: String
                    profile: FilmProfile
                }
                type Query { films: [Film!]! }
                """);

            assertThat(schema.field("FilmProfile", "film")).isInstanceOf(ChildField.SameRowTableField.class);
            assertThat(contractOf(schema, "profile"))
                .isInstanceOfSatisfying(NullContract.AlwaysObject.class, c ->
                    assertThat(c.reason()).isInstanceOfSatisfying(NullContract.Reason.BlockedBy.class,
                        b -> assertThat(b.blocker().name()).isEqualTo("film")));
        }
    }

    @Test
    void sharedRule_nestingAndSameRowAgreeOverTheSameFields() {
        var schema = TestSchemaHelper.buildSchema("""
            type FilmYears @table(name: "film") { releaseYear: Int @field(name: "release_year") }
            type FilmYearsNested { releaseYear: Int @field(name: "release_year") }
            type Film @table(name: "film") {
                title: String
                years: FilmYears
                yearsNested: FilmYearsNested
            }
            type Query { films: [Film!]! }
            """);

        var nested = (ChildField.NestingField) schema.field("Film", "yearsNested");
        var sameRow = schema.sameRowNullContract((ChildField.SameRowTableField) schema.field("Film", "years"));
        assertThat(sameRow).isEqualTo(nested.nullContract());
        assertThat(sameRow.presenceColumns()).isEqualTo(nested.parentRowColumns());
        assertThat(sameRow.presenceColumns()).extracting(c -> c.sqlName()).isEqualTo(List.of("release_year"));
    }
}
