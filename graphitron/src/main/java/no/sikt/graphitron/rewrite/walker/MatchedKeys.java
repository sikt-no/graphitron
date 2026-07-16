package no.sikt.graphitron.rewrite.walker;

import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.MatchedKey;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The shared PK-or-UK matcher both {@link UpdateRowsWalker} and
 * {@link DeleteRowsWalker} call. Extracts UpdateRowsWalker's original Stages 4-5 (candidate-
 * key enumeration + PK-first subset match + {@link MatchedKey} lift) so the row-identification logic
 * lives once. The match itself is verb-neutral: it asks "which catalog key does this input cover?",
 * which is the one concern UPDATE and DELETE genuinely share. What differs (UPDATE partitions into
 * SET/WHERE around the key; DELETE treats the whole input as WHERE and the key as a single-row guard)
 * stays in the respective walkers.
 *
 * <p>This is the seam Decision 6 in R266 names: a future shared {@code PredicateCarrier.LookupRows}
 * carrier (if a third consumer makes the shared contract clearest) grows from this helper plus the
 * verb-neutral {@link MatchedKey} / {@code KeyColumn} types, without foreclosing it now.
 */
public final class MatchedKeys {

    private MatchedKeys() {}

    /**
     * Enumerate the table's row-identifying candidate keys (PK first, then unique keys in jOOQ
     * declaration order, deduplicated on column set), each lifted into a {@link MatchedKey} arm.
     * The empty list is the degenerate keyless-table case the {@code NoUniqueKeyCoverage} diagnostics
     * report.
     */
    public static List<MatchedKey> candidates(JooqCatalog catalog, TableRef table) {
        return catalog.candidateKeys(table.tableName()).stream().map(MatchedKeys::toMatchedKey).toList();
    }

    /**
     * Find the first candidate key (PK preferred) whose column set is a subset of
     * {@code coveredSqlNames}; {@link Optional#empty()} when no key is covered. The PK-first ordering
     * of {@link #candidates} makes the PK the tiebreaker when both a PK and a UK are covered.
     */
    public static Optional<MatchedKey> firstCovered(JooqCatalog catalog, TableRef table, Set<String> coveredSqlNames) {
        for (var key : candidates(catalog, table)) {
            if (coveredSqlNames.containsAll(sqlNameSet(key.columns()))) {
                return Optional.of(key);
            }
        }
        return Optional.empty();
    }

    private static MatchedKey toMatchedKey(JooqCatalog.KeyEntry key) {
        var columns = key.columns().stream()
            .map(e -> new ColumnRef(e.sqlName(), e.javaName(), e.columnClass(), e.columnType()))
            .toList();
        return key.primary()
            ? new MatchedKey.PrimaryKey(columns, key.keyName())
            : new MatchedKey.UniqueKey(columns, key.keyName());
    }

    private static Set<String> sqlNameSet(List<ColumnRef> columns) {
        var out = new LinkedHashSet<String>();
        for (var c : columns) out.add(c.sqlName());
        return out;
    }
}
