package no.sikt.graphitron.mcp.rag;

import no.sikt.graphitron.model.boot.StoreReader;
import no.sikt.graphitron.model.read.StoreHandle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;

/**
 * The {@code catalog.search} corpus, read off the SQL catalog census: every table in the graph with its
 * columns in definition order, which the descriptor composer folds into one embeddable string per
 * table.
 *
 * <p>Written here, beside the index that embeds it, rather than with the queries the structured catalog
 * tools use. Those are shaped by what a wire response carries; this one is shaped by what an embedder
 * reads, and the two overlap in {@code FROM} clause and nowhere else. What the store shares between
 * consumers is the relation, so a query living with its consumer is the arrangement rather than a
 * duplication of one.
 *
 * <p>Two queries for the whole graph rather than one per table. The corpus is composed on every
 * observation, so its cost is paid per search rather than per capture, and a query per table would make
 * that cost the table count.
 */
public final class CatalogCorpus {

    private CatalogCorpus() {}

    /**
     * Reads the graph's tables and columns inside one transaction.
     *
     * <p>Through a reader rather than the session's own handle because the answer is two queries: on the
     * writer's connection a capture could commit between them and yield a corpus mixing one
     * generation's tables with the next one's columns. That would hash to something neither generation
     * produces, so the index would re-embed and then re-embed again on the next search.
     *
     * @param graphName the graph whose partition the corpus is drawn from, rebuilt into a scope per
     *     transaction rather than held, the {@code DSLContext} a read is handed being valid for that
     *     call only
     */
    public static List<CorpusTable> read(StoreReader reader, String graphName) {
        return reader.read(dsl -> read(new StoreHandle(dsl, graphName)));
    }

    /**
     * Reads the columns first and lets the table query drive the result, so a table the census holds
     * with no columns still gets a descriptor rather than being dropped by the grouping.
     *
     * <p>Both reads reach the census through {@link StoreHandle#reads}, the {@code sql_} family being
     * source-keyed rather than graph-keyed: a query that forgets the predicate composes a corpus with a
     * sibling module's tables folded in, and an agent would find a table its own schema cannot reach.
     *
     * <p>Grouped by the {@code (schema, name)} pair the entry id is composed from rather than by the
     * whole key: a graph's {@code sql_} sources are generated packages that partition by schema, so two
     * of them naming one table would mean one schema generated twice. Grouping by what the id is spelled
     * from also means the group key cannot disagree with the entry it ends up on.
     */
    private static List<CorpusTable> read(StoreHandle store) {
        var columnRows = store.dsl()
            .select(SQL_COLUMN.TABLE_SCHEMA, SQL_COLUMN.TABLE_NAME, SQL_COLUMN.COLUMN_NAME,
                SQL_COLUMN.DESCRIPTION)
            .from(SQL_COLUMN)
            .where(store.reads(SQL_COLUMN.SOURCE_NAME))
            .orderBy(SQL_COLUMN.TABLE_SCHEMA.asc(), SQL_COLUMN.TABLE_NAME.asc(), SQL_COLUMN.ORDINAL.asc())
            .fetch();

        var columnsByTable = new LinkedHashMap<String, List<CorpusTable.Column>>();
        for (var row : columnRows) {
            columnsByTable
                .computeIfAbsent(row.value1() + "." + row.value2(), t -> new ArrayList<>())
                .add(new CorpusTable.Column(row.value3(), row.value4()));
        }

        return store.dsl()
            .select(SQL_TABLE.TABLE_SCHEMA, SQL_TABLE.TABLE_NAME, SQL_TABLE.DESCRIPTION)
            .from(SQL_TABLE)
            .where(store.reads(SQL_TABLE.SOURCE_NAME))
            .orderBy(SQL_TABLE.TABLE_SCHEMA.asc(), SQL_TABLE.TABLE_NAME.asc())
            .fetch(r -> new CorpusTable(r.value1(), r.value2(), r.value3(),
                List.copyOf(columnsByTable.getOrDefault(r.value1() + "." + r.value2(), List.of()))));
    }
}
