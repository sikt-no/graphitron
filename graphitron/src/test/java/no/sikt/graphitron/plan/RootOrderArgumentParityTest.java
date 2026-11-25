package no.sikt.graphitron.plan;

import no.sikt.graphitron.emit.RootQuery;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A root over one table whose {@code @orderBy} argument picks its order by is helped by one spec,
 * whichever route states it: a client's list of orders, or its one order, and on a connection the
 * cursor encoding the columns that spec sorts by. The store's spec equals the walk's on what the helper
 * reads, so a cursor handed out before the store emitted the root reads its next page after.
 */
@PipelineTier
class RootOrderArgumentParityTest {

    private static final String SCHEMA = """
        type Film @table(name: "film") { title: String }
        enum FilmSort {
            FILM_ID @order(primaryKey: true)
            TITLE   @order(fields: [{name: "title"}])
            TITLE_DESC @order(fields: [{name: "title", direction: DESC}])
        }
        input FilmOrderBy { field: FilmSort!  direction: SortDirection }
        type Query {
            filmsComposed(order: [FilmOrderBy] @orderBy): [Film!]! @defaultOrder(primaryKey: true)
            filmsComposedPage(order: [FilmOrderBy] @orderBy): [Film!]! @asConnection @defaultOrder(primaryKey: true)
            filmsPicked(order: FilmOrderBy @orderBy): [Film!]! @defaultOrder(fields: [{name: "title"}])
            filmsPickedPage(order: FilmOrderBy @orderBy): [Film!]! @asConnection
        }
        """;

    @ParameterizedTest
    @ValueSource(strings = {"filmsComposed", "filmsComposedPage", "filmsPicked", "filmsPickedPage"})
    void theStoreStatesTheSpecTheWalksHelperReads(String fieldName) {
        var walk = ((QueryField.QueryTableField) TestSchemaHelper.buildSchema(SCHEMA).field("Query", fieldName))
            .orderBy();
        var store = FactGraphs.inline().read("root-order-argument-parity", SCHEMA, CatalogInput.of(testContext()),
                TestSchemaHelper.testClasspath(), graph -> {
            var result = RootQuery.read(graph.handle()).get("Query." + fieldName);
            assertThat(result).as("the store's verdict on Query." + fieldName).isInstanceOf(RootQuery.Emitted.class);
            return LauncherCommands.argumentOrderOf((RootQuery.Emitted) result);
        });
        assertThat(helperShape(store)).isEqualTo(helperShape(walk));
    }

    private static final String SORT_KEYS = """
        type Film @table(name: "film") { title: String }
        enum FilmKeySort {
            ORIGINAL_LANGUAGE @order(fields: [{name: "name", path: [{key: "film_original_language_id_fkey"}]}])
            LATEST_STOCK @order(fields: [{method: {className: "no.sikt.graphitron.rewrite.test.extensions.FilmSortKeys", method: "latestStockStore"}}])
            TITLE @order(fields: [{name: "title"}])
            LANGUAGE_THEN_STOCK @order(fields: [{name: "name", path: [{key: "film_language_id_fkey"}]},
                {method: {className: "no.sikt.graphitron.rewrite.test.extensions.FilmSortKeys", method: "latestStockStore"}, direction: DESC}])
        }
        input FilmKeyOrderBy { field: FilmKeySort!  direction: SortDirection }
        type Query {
            filmsByKeys(order: FilmKeyOrderBy @orderBy): [Film!]!
                @defaultOrder(fields: [{name: "name", path: [{key: "film_language_id_fkey"}]}])
            filmsByKeysPage(order: FilmKeyOrderBy @orderBy): [Film!]! @asConnection
                @defaultOrder(fields: [{method: {className: "no.sikt.graphitron.rewrite.test.extensions.FilmSortKeys", method: "latestStockStore"}}, {name: "title"}])
            filmsByPath: [Film!]! @defaultOrder(fields: [{name: "name", path: [{key: "film_original_language_id_fkey"}]}])
            filmsByPathPage: [Film!]! @asConnection
                @defaultOrder(fields: [{name: "name", path: [{key: "film_original_language_id_fkey"}]}, {name: "title"}])
        }
        """;

    /**
     * A key down a path or computed by a method is numbered across the field's orders, each choice's in
     * the sort enum's order and then the default's, and the number names the emitted method a path key
     * calls and the alias its cursor reads: the store's keys, numbers and paths equal the walk's.
     */
    @ParameterizedTest
    @ValueSource(strings = {"filmsByKeys", "filmsByKeysPage", "filmsByPath", "filmsByPathPage"})
    void theStoreNumbersAndStatesTheSortKeysTheWalkDoes(String fieldName) {
        var walk = ((QueryField.QueryTableField) TestSchemaHelper.buildSchema(SORT_KEYS).field("Query", fieldName))
            .orderBy();
        var store = FactGraphs.inline().read("root-sort-key-parity", SORT_KEYS, CatalogInput.of(testContext()),
                TestSchemaHelper.sakilaExampleClasspath(), graph -> {
            var result = RootQuery.read(graph.handle()).get("Query." + fieldName);
            assertThat(result).as("the store's verdict on Query." + fieldName).isInstanceOf(RootQuery.Emitted.class);
            var emitted = (RootQuery.Emitted) result;
            return emitted.choice() != null
                ? (OrderBySpec) LauncherCommands.argumentOrderOf(emitted)
                : LauncherCommands.fixedOf(emitted.order(), new int[1]);
        });
        assertThat(helperShape(store)).isEqualTo(helperShape(walk));
    }

    /** What the helper and the cursor read of a spec. */
    private static List<String> helperShape(OrderBySpec spec) {
        var out = new ArrayList<String>();
        if (spec instanceof OrderBySpec.Fixed fixed) {
            out.add("fixed " + fixedShape(fixed));
            return out;
        }
        if (!(spec instanceof OrderBySpec.Argument argument)) {
            out.add("not an argument: " + spec);
            return out;
        }
        out.add(argument.name() + " " + argument.typeName() + " " + argument.nonNull() + " " + argument.list()
            + " " + argument.sortFieldName() + " " + argument.directionFieldName());
        argument.namedOrders().forEach(named -> out.add("named " + named.name() + " " + fixedShape(named.order())));
        if (argument.composition() instanceof OrderBySpec.Composition.Sequence sequence) {
            out.add("completion " + switch (sequence.completion()) {
                case OrderBySpec.SequenceCompletion.None none -> "none";
                case OrderBySpec.SequenceCompletion.AppendRowKey key -> "row key " + names(key.rowKey())
                    + " identifying " + key.identifyingKeys().stream().map(RootOrderArgumentParityTest::names).toList();
            });
        }
        out.add("base " + (argument.base() instanceof OrderBySpec.Fixed fixed ? fixedShape(fixed) : argument.base()));
        return out;
    }

    private static String fixedShape(OrderBySpec.Fixed fixed) {
        return fixed.entries().stream()
            .map(entry -> entryShape(entry) + " " + entry.direction() + " " + entry.collation() + " " + entry.nullable())
            .collect(Collectors.joining(", ", "[", "]")) + " uniformAsc " + fixed.uniformAsc();
    }

    private static String entryShape(OrderBySpec.OrderEntry entry) {
        return switch (entry) {
            case OrderBySpec.ColumnOrderEntry column -> column.column().sqlName();
            case OrderBySpec.SortKeyEntry key -> "#" + key.ordinal() + " " + switch (key.key()) {
                case OrderBySpec.SortKey.Path path -> "path " + path.hops().stream()
                    .map(hop -> hop.targetTable().tableName() + " via "
                        + ((no.sikt.graphitron.rewrite.model.On.ColumnPairs) hop.on()).keying().describe() + " "
                        + ((no.sikt.graphitron.rewrite.model.On.ColumnPairs) hop.on()).sourceSideColumns().stream()
                            .map(ColumnRef::sqlName).toList()
                        + "->" + ((no.sikt.graphitron.rewrite.model.On.ColumnPairs) hop.on()).targetSideColumns().stream()
                            .map(ColumnRef::sqlName).toList())
                    .toList() + " lands " + path.column().sqlName();
                case OrderBySpec.SortKey.Method method -> "method " + method.method().className() + "."
                    + method.method().methodName();
            };
        };
    }

    private static List<String> names(List<ColumnRef> columns) {
        return columns.stream().map(ColumnRef::sqlName).toList();
    }
}
