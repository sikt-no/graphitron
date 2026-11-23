package no.sikt.graphitron.command;

import no.sikt.graphitron.model.jooq.ColumnRef;

import java.util.List;

/**
 * One graphitron-minted comparison in a condition row, kept as the facet planner's grouping
 * source. {@code columns.size() > 1} is the row-value form ({@code DSL.row(c1, ..., cN)}).
 * {@link #reach} empty means the term binds this row's own table; non-empty means a correlated
 * {@code EXISTS} over the {@link ReachPath}'s hops, with the comparison against the terminal
 * hop's alias.
 */
public record ColumnTerm(List<ColumnRef> columns, ReachPath reach) {

    public ColumnTerm {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("a column term compares at least one column");
        }
        columns = List.copyOf(columns);
        reach = reach == null ? ReachPath.none() : reach;
    }
}
