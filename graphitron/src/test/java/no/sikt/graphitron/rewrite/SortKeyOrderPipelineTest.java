package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.SqlGeneratingField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An {@code @order} or {@code @defaultOrder} entry that sorts by a value the field's own table does
 * not hold: a column on a table reached through to-one hops ({@code FieldSort.path:}), or a key a
 * consumer method derives ({@code FieldSort.method:}). Each resolves to an
 * {@link OrderBySpec.SortKeyEntry}; the cases below pin which shapes classify, which are refused and
 * what each refusal says, and how such an entry passes through a connection's row-key completion,
 * which counts own-table columns only.
 */
@PipelineTier
class SortKeyOrderPipelineTest {

    private static final String STUB = "no.sikt.graphitron.rewrite.TestExternalFieldStub";

    private static final String FILM = """
        type Language @table(name: "language") { name: String }
        type Film @table(name: "film") { title: String }
        """;

    private static GraphitronSchema build(String sdl) {
        return TestSchemaHelper.buildSchema(sdl);
    }

    private static OrderBySpec.Fixed fixedOrder(GraphitronSchema schema, String type, String field) {
        var classified = schema.field(type, field);
        assertThat(classified).as(type + "." + field + " classifies").isInstanceOf(SqlGeneratingField.class);
        var order = ((SqlGeneratingField) classified).orderBy();
        assertThat(order).isInstanceOf(OrderBySpec.Fixed.class);
        return (OrderBySpec.Fixed) order;
    }

    private static String rejection(GraphitronSchema schema, String type, String field) {
        var classified = schema.field(type, field);
        assertThat(classified).as(type + "." + field + " is refused").isInstanceOf(UnclassifiedField.class);
        return ((UnclassifiedField) classified).rejection().message();
    }

    /** {@code "<what> <DIRECTION>"} per entry: a column's SQL name, a path's landing column, or a method. */
    private static List<String> described(OrderBySpec.Fixed fixed) {
        return fixed.entries().stream().map(entry -> switch (entry) {
            case OrderBySpec.ColumnOrderEntry column ->
                column.column().sqlName().toLowerCase(Locale.ROOT) + " " + entry.direction();
            case OrderBySpec.SortKeyEntry key -> switch (key.key()) {
                case OrderBySpec.SortKey.Path path -> path.hops().getLast().targetTable().tableName()
                    + "." + path.column().sqlName().toLowerCase(Locale.ROOT) + " " + entry.direction();
                case OrderBySpec.SortKey.Method method -> method.method().methodName() + "() " + entry.direction();
            };
        }).toList();
    }

    // ===== path: resolution =====

    @Test
    void childToParentPathResolvesToASortKeyOnTheLandingTable() {
        var schema = build(FILM + """
            type Query {
                films: [Film!]! @defaultOrder(fields: [{name: "name", path: [{key: "film_original_language_id_fkey"}]}])
            }
            """);
        var order = fixedOrder(schema, "Query", "films");
        assertThat(described(order)).containsExactly("language.name ASC");
        var key = (OrderBySpec.SortKeyEntry) order.entries().getFirst();
        assertThat(key.ordinal()).isZero();
        assertThat(key.nullable()).as("a film with no original language sorts on NULL").isTrue();
        var path = (OrderBySpec.SortKey.Path) key.key();
        assertThat(path.startTable().tableName()).isEqualToIgnoringCase("film");
        assertThat(path.hops()).hasSize(1);
    }

    @Test
    void oneToOneExtensionHopResolves() {
        var schema = build("""
            type StintPerson @table(name: "stint_person") { personId: Int @field(name: "person_id") }
            type Query {
                people: [StintPerson!]! @defaultOrder(fields: [{name: "valid_from", path: [{key: "stint_single_person_fkey"}]}])
            }
            """);
        assertThat(described(fixedOrder(schema, "Query", "people"))).containsExactly("stint_single.valid_from ASC");
    }

    @Test
    void oneToOneExtensionHopOverANullableUniqueKeyResolves() {
        var schema = build("""
            type StintPerson @table(name: "stint_person") { personId: Int @field(name: "person_id") }
            type Query {
                people: [StintPerson!]! @defaultOrder(fields: [{name: "label", path: [{key: "stint_badge_person_fkey"}]}])
            }
            """);
        assertThat(described(fixedOrder(schema, "Query", "people"))).containsExactly("stint_badge.label ASC");
    }

    @Test
    void ordinalsNumberTheFieldsSortKeysAcrossItsDefaultAndNamedOrders() {
        var schema = build(FILM + """
            enum FilmLanguageSort {
                LANGUAGE @order(fields: [{name: "name", path: [{key: "film_language_id_fkey"}]}])
                TITLE @order(fields: [{name: "title"}])
                ORIGINAL @order(fields: [{name: "name", path: [{key: "film_original_language_id_fkey"}]},
                                         {method: {className: "%s", method: "rating"}}])
            }
            input FilmLanguageOrder { field: FilmLanguageSort!  direction: SortDirection }
            type Query {
                films(order: FilmLanguageOrder @orderBy): [Film!]!
                    @defaultOrder(fields: [{name: "name", path: [{key: "film_original_language_id_fkey"}]}])
            }
            """.formatted(STUB));
        var arg = (OrderBySpec.Argument) ((QueryField.QueryTableField) schema.field("Query", "films")).orderBy();
        var ordinals = new java.util.ArrayList<Integer>();
        for (var named : arg.namedOrders()) named.order().sortKeys().forEach(k -> ordinals.add(k.ordinal()));
        ((OrderBySpec.Fixed) arg.base()).sortKeys().forEach(k -> ordinals.add(k.ordinal()));
        assertThat(ordinals).as("one ordinal per sort-key entry of the field, none shared").doesNotHaveDuplicates().hasSize(4);
    }

    @Test
    void toManyHopIsRefusedNamingTheHopAndPointingAtMethod() {
        var message = rejection(build("""
            type Film @table(name: "film") { title: String }
            type Query {
                films: [Film!]! @defaultOrder(fields: [{name: "last_update", path: [{table: "film_actor"}]}])
            }
            """), "Query", "films");
        assertThat(message)
            .contains("hop 1", "'film' to 'film_actor'", "is to-many", "one 'film' row has many 'film_actor' rows")
            .contains("method:")
            .contains("synthetic foreign key");
    }

    @Test
    void conditionAloneOnAHopIsRefusedPointingAtMethod() {
        var message = rejection(build(FILM + """
            type Query {
                films: [Film!]! @defaultOrder(fields: [{name: "name",
                    path: [{condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "join"}}]}])
            }
            """), "Query", "films");
        assertThat(message).contains("hop 1", "carries a condition:", "method:");
    }

    @Test
    void conditionBesideAKeyIsRefusedPointingAtMethod() {
        var message = rejection(build(FILM + """
            type Query {
                films: [Film!]! @defaultOrder(fields: [{name: "name",
                    path: [{key: "film_original_language_id_fkey",
                            condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "join"}}]}])
            }
            """), "Query", "films");
        assertThat(message).contains("hop 1", "carries a condition:", "method:");
    }

    @Test
    void unknownColumnOnTheLandingTableIsRefused() {
        var message = rejection(build(FILM + """
            type Query {
                films: [Film!]! @defaultOrder(fields: [{name: "title", path: [{key: "film_original_language_id_fkey"}]}])
            }
            """), "Query", "films");
        assertThat(message).contains("sort column 'title' is not a column of 'language'");
    }

    @Test
    void unresolvableKeyIsRefused() {
        var message = rejection(build(FILM + """
            type Query {
                films: [Film!]! @defaultOrder(fields: [{name: "name", path: [{key: "no_such_fkey"}]}])
            }
            """), "Query", "films");
        assertThat(message).contains("path: for sort column 'name' could not be resolved", "no_such_fkey");
    }

    @Test
    void pathWithoutNameIsRefused() {
        var message = rejection(build(FILM + """
            type Query {
                films: [Film!]! @defaultOrder(fields: [{path: [{key: "film_original_language_id_fkey"}]}])
            }
            """), "Query", "films");
        assertThat(message).contains("path: without name:");
    }

    @Test
    void anEnumValuesPathIsResolvedAndRefusedLikeADefaultOrders() {
        var message = rejection(build(FILM + """
            enum FilmSort { CAST @order(fields: [{name: "last_update", path: [{table: "film_actor"}]}]) }
            input FilmOrder { field: FilmSort!  direction: SortDirection }
            type Query { films(order: FilmOrder @orderBy): [Film!]! }
            """), "Query", "films");
        assertThat(message).contains("enum value 'CAST'", "is to-many");
    }

    // ===== refused shapes =====

    @Test
    void aMultitableParticipantIsRefused() {
        var schema = build("""
            interface Document { rowId: Int }
            type Film implements Document @table(name: "film") { rowId: Int @field(name: "film_id") }
            type Actor implements Document @table(name: "actor") { rowId: Int @field(name: "actor_id") }
            type Query {
                documents: [Document!]! @defaultOrder(fields: [{method: {className: "%s", method: "anyTable"}}])
            }
            """.formatted(STUB));
        assertThat(rejection(schema, "Query", "documents")).contains("2 participants could not be lowered");
        assertThat(schema.diagnostics())
            .filteredOn(e -> e.coordinate().equals("Query.documents/Film"))
            .singleElement()
            .satisfies(e -> assertThat(e.message()).contains("multitable interface or union participant"));
    }

    @Test
    void aTableValuedFunctionResultIsRefused() {
        var message = rejection(build("""
            type Tilgang {
                organisasjonskode: Int
                rollekode: String
            }
            type Query {
                tilganger(env: String!, serviceId: String!, feideId: String!): [Tilgang!]!
                    @routine(
                        name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr"
                        argMapping: "pEnv: env, pServiceId: serviceId, pFeideId: feideId"
                    )
                    @defaultOrder(fields: [{method: {className: "%s", method: "anyTable"}}])
            }
            """.formatted(STUB)), "Query", "tilganger");
        assertThat(message).contains("table-valued function result");
    }

    // ===== method: resolution =====

    @Test
    void methodResolvesToASortKey() {
        var schema = build(FILM + """
            type Query {
                films: [Film!]! @defaultOrder(fields: [{method: {className: "%s", method: "rating"}, direction: DESC}])
            }
            """.formatted(STUB));
        var order = fixedOrder(schema, "Query", "films");
        assertThat(described(order)).containsExactly("rating() DESC");
        var method = (OrderBySpec.SortKey.Method) ((OrderBySpec.SortKeyEntry) order.entries().getFirst()).key();
        assertThat(method.method().className()).isEqualTo(STUB);
    }

    @Test
    void methodOnAMissingClassIsRefused() {
        var message = rejection(build(FILM + """
            type Query {
                films: [Film!]! @defaultOrder(fields: [{method: {className: "no.sikt.does.not.Exist", method: "rating"}}])
            }
            """), "Query", "films");
        assertThat(message).contains("method: could not be resolved", "no.sikt.does.not.Exist");
    }

    @Test
    void methodNamingAMissingMethodIsRefused() {
        var message = rejection(build(FILM + """
            type Query {
                films: [Film!]! @defaultOrder(fields: [{method: {className: "%s", method: "doesNotExist"}}])
            }
            """.formatted(STUB)), "Query", "films");
        assertThat(message).contains("method: could not be resolved", "doesNotExist");
    }

    @Test
    void methodTakingAnotherTableIsRefused() {
        var message = rejection(build(FILM + """
            type Query {
                films: [Film!]! @defaultOrder(fields: [{method: {className: "%s", method: "fanLabel"}}])
            }
            """.formatted(STUB)), "Query", "films");
        assertThat(message).contains("method: could not be resolved", "does not accept the parent table 'film'");
    }

    @Test
    void methodNotReturningAFieldIsRefused() {
        var message = rejection(build(FILM + """
            type Query {
                films: [Film!]! @defaultOrder(fields: [{method: {className: "%s", method: "notAField"}}])
            }
            """.formatted(STUB)), "Query", "films");
        assertThat(message).contains("method: could not be resolved", "must return org.jooq.Field<X>");
    }

    @Test
    void methodWithArgMappingIsRefused() {
        var message = rejection(build(FILM + """
            type Query {
                films: [Film!]! @defaultOrder(fields: [{method: {className: "%s", method: "rating", argMapping: "x: y"}}])
            }
            """.formatted(STUB)), "Query", "films");
        assertThat(message).contains("does not take argMapping");
    }

    @Test
    void methodWithoutAMethodNameIsRefused() {
        var message = rejection(build(FILM + """
            type Query {
                films: [Film!]! @defaultOrder(fields: [{method: {className: "%s"}}])
            }
            """.formatted(STUB)), "Query", "films");
        assertThat(message).contains("method: is missing method");
    }

    @Test
    void methodBesideNameIsRefused() {
        var message = rejection(build(FILM + """
            type Query {
                films: [Film!]! @defaultOrder(fields: [{name: "title", method: {className: "%s", method: "rating"}}])
            }
            """.formatted(STUB)), "Query", "films");
        assertThat(message).contains("both name: and method:");
    }

    @Test
    void methodBesidePathIsRefused() {
        var message = rejection(build(FILM + """
            type Query {
                films: [Film!]! @defaultOrder(fields: [{method: {className: "%s", method: "rating"},
                    path: [{key: "film_original_language_id_fkey"}]}])
            }
            """.formatted(STUB)), "Query", "films");
        assertThat(message).contains("both method: and path:");
    }

    @Test
    void entryWithNeitherNameNorMethodIsRefused() {
        var message = rejection(build(FILM + """
            type Query { films: [Film!]! @defaultOrder(fields: [{collate: "C"}]) }
            """), "Query", "films");
        assertThat(message).contains("neither name: nor method:");
    }

    // ===== connection row-key completion =====

    @Test
    void pathOnlyOrderGetsTheRowKeyEvenWhenThePathColumnIsTheRelatedPrimaryKey() {
        var schema = build(FILM + """
            type Query {
                films: [Film!]! @asConnection
                    @defaultOrder(fields: [{name: "language_id", path: [{key: "film_original_language_id_fkey"}]}])
            }
            """);
        assertThat(described(fixedOrder(schema, "Query", "films")))
            .containsExactly("language.language_id ASC", "film_id ASC");
    }

    @Test
    void pathColumnsNamedLikeTheFieldsOwnKeyColumnsDoNotCountAsCoverage() {
        var schema = build("""
            type FilmActor @table(name: "film_actor") { lastUpdate: String @field(name: "last_update") }
            type Query {
                filmActors: [FilmActor!]! @asConnection @defaultOrder(fields: [
                    {name: "actor_id", path: [{key: "film_actor_actor_id_fkey"}]},
                    {name: "film_id", path: [{key: "film_actor_film_id_fkey"}]}
                ])
            }
            """);
        assertThat(described(fixedOrder(schema, "Query", "filmActors")))
            .containsExactly("actor.actor_id ASC", "film.film_id ASC", "actor_id ASC", "film_id ASC");
    }

    @Test
    void methodOnlyOrderGetsTheRowKeyInItsDirection() {
        var schema = build(FILM + """
            type Query {
                films: [Film!]! @asConnection
                    @defaultOrder(fields: [{method: {className: "%s", method: "rating"}, direction: DESC}])
            }
            """.formatted(STUB));
        assertThat(described(fixedOrder(schema, "Query", "films"))).containsExactly("rating() DESC", "film_id DESC");
    }

    @Test
    void anOwnTableKeyBesideAPathEntryAppendsNothing() {
        var schema = build(FILM + """
            type Query {
                films: [Film!]! @asConnection @defaultOrder(fields: [
                    {name: "name", path: [{key: "film_original_language_id_fkey"}]},
                    {name: "film_id"}
                ])
            }
            """);
        assertThat(described(fixedOrder(schema, "Query", "films")))
            .containsExactly("language.name ASC", "film_id ASC");
    }

    @Test
    void aListValuedOrderByCarriesItsSortKeysUncompletedAndAppendsTheKeyInTheHelper() {
        var schema = build(FILM + """
            enum FilmSort {
                ORIGINAL @order(fields: [{name: "language_id", path: [{key: "film_original_language_id_fkey"}]}])
                RATING @order(fields: [{method: {className: "%s", method: "rating"}}])
            }
            input FilmOrder { field: FilmSort!  direction: SortDirection }
            type Query { films(order: [FilmOrder!] @orderBy): [Film!]! @asConnection }
            """.formatted(STUB));
        var arg = (OrderBySpec.Argument) ((QueryField.QueryTableField) schema.field("Query", "films")).orderBy();
        var sequence = (OrderBySpec.Composition.Sequence) arg.composition();
        assertThat(sequence.completion()).isInstanceOf(OrderBySpec.SequenceCompletion.AppendRowKey.class);
        assertThat(sequence.namedOrders()).allSatisfy(named ->
            assertThat(named.order().entries()).singleElement().isInstanceOf(OrderBySpec.SortKeyEntry.class));
    }

    @Test
    void aSingleValuedOrderBysSortKeyValueIsCompletedWithTheRowKey() {
        var schema = build(FILM + """
            enum FilmSort { ORIGINAL @order(fields: [{name: "name", path: [{key: "film_original_language_id_fkey"}]}]) }
            input FilmOrder { field: FilmSort!  direction: SortDirection }
            type Query { films(order: FilmOrder @orderBy): [Film!]! @asConnection }
            """);
        var arg = (OrderBySpec.Argument) ((QueryField.QueryTableField) schema.field("Query", "films")).orderBy();
        assertThat(described(arg.namedOrders().getFirst().order()))
            .containsExactly("language.name ASC", "film_id ASC");
    }
}
