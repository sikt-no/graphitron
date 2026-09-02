package no.sikt.graphitron.command;

import no.sikt.graphitron.model.jooq.ColumnRef;

import java.util.List;

/**
 * One graphitron-minted comparison in a condition row. {@code columns.size() > 1} is the
 * row-value form ({@code DSL.row(c1, ..., cN)}); {@link #match} picks equality or membership;
 * {@link #nonNull} is the presence-gating fact (the resolved call-site nullability conjunction,
 * {@code BodyParam.nonNull()} today): {@code false} means the rendered term is guarded on the
 * bound value. {@link #reach} empty means the term binds this row's own table; non-empty means a
 * correlated {@code EXISTS} over the {@link ReachPath}'s hops, with the comparison against the
 * terminal hop's alias. Reach sits per-term on the generated arm because the producer routes each
 * binding locally or remotely on its own, so one row routinely mixes a local equality with a
 * remote membership.
 *
 * <p>The term carries its own {@link ArgBinding}: a comparison without the value it compares
 * against is unrepresentable, and no renderer indexes parallel term and binding lists in
 * lockstep.
 */
public record ColumnTerm(
    List<ColumnRef> columns,
    MatchKind match,
    boolean nonNull,
    ArgBinding binding,
    ReachPath reach
) {

    public ColumnTerm {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("a column term compares at least one column");
        }
        columns = List.copyOf(columns);
        if (match == null) {
            throw new IllegalArgumentException("a column term carries its match kind");
        }
        if (binding == null) {
            throw new IllegalArgumentException("a column term carries the binding it compares against");
        }
        reach = reach == null ? ReachPath.none() : reach;
    }
}
