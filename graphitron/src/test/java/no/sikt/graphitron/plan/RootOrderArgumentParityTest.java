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

    /** What the helper and the cursor read of a spec. */
    private static List<String> helperShape(OrderBySpec spec) {
        var out = new ArrayList<String>();
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
            .map(entry -> (entry instanceof OrderBySpec.ColumnOrderEntry column ? column.column().sqlName() : "key")
                + " " + entry.direction() + " " + entry.collation() + " " + entry.nullable())
            .collect(Collectors.joining(", ", "[", "]")) + " uniformAsc " + fixed.uniformAsc();
    }

    private static List<String> names(List<ColumnRef> columns) {
        return columns.stream().map(ColumnRef::sqlName).toList();
    }
}
