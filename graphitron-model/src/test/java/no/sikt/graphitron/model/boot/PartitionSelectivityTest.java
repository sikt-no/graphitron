package no.sikt.graphitron.model.boot;

import no.sikt.graphitron.model.catalog.GraphPartition;
import no.sikt.graphitron.model.test.FactStores;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a store states the partition dimension's selectivity on every relation carrying it, which is
 * the one statistic no {@code ANALYZE} can reach the pass that needs it with:
 * {@link GraphPartition#DECLARED_SELECTIVITY} carries why, and the short form is that H2's
 * {@code ANALYZE} commits, so a derivation stage planning inside a transaction can never run one.
 *
 * <p><b>Exact rather than approximate.</b> The claim is over every graph-keyed base table the schema
 * declares, not a sample of them, because the sweep's whole argument over a hand-written line per
 * table is that it makes the declaration true by construction. A new graph-keyed relation arriving
 * outside the sweep is exactly what this fails on.
 *
 * <p>Beside the class rather than in a module that consumes it, for {@link GraphitronModelStoreTest}'s
 * reason: this is an assertion about what creating the store does.
 */
class PartitionSelectivityTest {

    /**
     * The claim, on a store nothing has captured into and nothing has analysed, which is the state
     * the declaration exists for.
     */
    @Test
    @DisplayName("a created store declares the partition selectivity on every graph-keyed base table")
    void everyGraphKeyedBaseTableCarriesTheDeclaration() {
        try (var store = FactStores.inMemory()) {
            DSLContext dsl = store.dsl();
            List<String> keyed = GraphPartition.keyedBaseTables(dsl);
            assertThat(keyed)
                .as("base tables carrying the partition column. Most of the schema, or the census"
                    + " below is asking the wrong question and every claim here is vacuous")
                .hasSizeGreaterThan(100);
            assertThat(undeclared(dsl, keyed))
                .as("graph-keyed base tables reporting something other than the declared"
                    + " selectivity on their partition column, and what each reports instead. None:"
                    + " the sweep runs over the column, so a relation cannot be outside it")
                .isEmpty();
        }
    }

    /**
     * That the declaration is a property of the schema rather than of a session. The sweep runs
     * where the schema is created and never on reopen, so a warm store carries what its own creation
     * stated; were the value session state, every reopened store would plan the stages of a second
     * graph without it.
     */
    @Test
    @DisplayName("a reopened store carries the declaration its creation stated")
    void aReopenedStoreCarriesIt(@TempDir Path home) {
        try (var created = FactStores.fileBacked(home)) {
            assertThat(undeclared(created.dsl(), GraphPartition.keyedBaseTables(created.dsl())))
                .as("undeclared graph-keyed base tables on the store that created the schema")
                .isEmpty();
        }
        try (var reopened = FactStores.fileBacked(home)) {
            assertThat(undeclared(reopened.dsl(), GraphPartition.keyedBaseTables(reopened.dsl())))
                .as("undeclared graph-keyed base tables on a reopen of the same home, which runs no"
                    + " sweep of its own")
                .isEmpty();
        }
    }

    /**
     * The offenders, each with what it reports instead, so a failure names the relation and the
     * value rather than only a count.
     */
    private static Map<String, Integer> undeclared(DSLContext dsl, List<String> keyed) {
        var offenders = new TreeMap<String, Integer>();
        for (String relation : keyed) {
            Integer selectivity = dsl.fetch("""
                SELECT SELECTIVITY FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = ? AND COLUMN_NAME = ?
                """, relation, GraphPartition.COLUMN)
                .stream().map(row -> row.get(0, Integer.class)).findFirst().orElse(null);
            if (selectivity == null || selectivity != GraphPartition.DECLARED_SELECTIVITY) {
                offenders.put(relation, selectivity);
            }
        }
        return offenders;
    }
}
