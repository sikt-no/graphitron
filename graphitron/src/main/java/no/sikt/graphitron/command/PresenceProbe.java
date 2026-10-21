package no.sikt.graphitron.command;

import no.sikt.graphitron.model.jooq.ColumnRef;

import java.util.List;
import java.util.Objects;

/**
 * The presence probe of a nullable single-object field: one projected column, aliased
 * {@link #alias()}, that is non-null exactly when the object holds a value no own-row column
 * shows, read by the field's fetcher beside its presence columns. It renders as
 * {@code CASE WHEN <every column leaf absent> AND (EXISTS(<term>) OR ...) THEN 1 END}: the guard
 * keeps the probes from running when a column already proves the object present, which the
 * fetcher checks first.
 *
 * <p>A probe is a rendered expression under a derived alias, not a column of the row, so it rides
 * beside the carriers' column demand rather than in it; the base-name column contract of
 * {@link no.sikt.graphitron.rewrite.model.ParentRowDemand} is untouched.
 *
 * @param alias       the column's alias, {@link ReservedAliases#presenceProbe}
 * @param columnLeaves the object's column leaves: each is absent when any of its columns is
 *                    null, and the guard holds when every leaf is absent
 * @param terms       one existence test per reference or list the object holds; never empty
 */
public record PresenceProbe(String alias, List<List<ColumnRef>> columnLeaves, List<ProbeTerm> terms) {

    public PresenceProbe {
        Objects.requireNonNull(alias, "alias");
        columnLeaves = columnLeaves.stream().map(List::copyOf).toList();
        terms = List.copyOf(terms);
        if (terms.isEmpty()) {
            throw new IllegalArgumentException("a presence probe carries at least one term");
        }
    }

    /**
     * One existence test: a row reached over {@code reach} from the correlated alias, under
     * {@code filter}, in which some of {@code targetColumns} is non-null, or any row when they are
     * empty.
     *
     * @param reach         the field's own join path; never empty
     * @param filter        the field's condition glue call, or {@code null} when it has no filters;
     *                      it binds request context only, so it is called with an empty argument map
     * @param targetColumns columns of the terminal table whose non-null value proves the field
     *                      present
     */
    public record ProbeTerm(ReachPath reach, GlueCall filter, List<ColumnRef> targetColumns) {
        public ProbeTerm {
            Objects.requireNonNull(reach, "reach");
            if (reach.isEmpty()) {
                throw new IllegalArgumentException("a probe term correlates over a non-empty reach");
            }
            targetColumns = List.copyOf(targetColumns);
        }
    }
}
