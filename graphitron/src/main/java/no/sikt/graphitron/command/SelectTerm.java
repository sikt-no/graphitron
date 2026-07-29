package no.sikt.graphitron.command;

import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.ParentCorrelation;

import java.util.List;
import java.util.Objects;

/**
 * One SELECT-list expression a {@link Contribution.Project} lands when its gating field is
 * selected. Arms are SQL shapes, never reasons: two contributions that render to the same SQL
 * shape use the same arm, so a proposed arm that renders {@code table.COL} exactly as
 * {@link Column} does is {@link Column}, whatever fact motivated it.
 *
 * <p>Aliasing is a slot, not an arm: {@link Column} carries {@link TermAlias} (both cases occur
 * on that shape today); the subselect-shaped arms are result-key-aliased structurally and
 * {@link Aggregate} carries its fixed projected name, since an aggregate has no column identity
 * and the pivot read side addresses it by that name across every occurrence.
 */
public sealed interface SelectTerm {

    /**
     * A column on this unit's own table context: {@code table.<COL>}, aliased per
     * {@link TermAlias}. The composite node-key carrier lands one {@code Column} per key column.
     */
    record Column(ColumnRef column, TermAlias alias) implements SelectTerm {
        public Column {
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(alias, "alias");
        }
    }

    /**
     * A correlated single-column subselect over a {@code @reference} path:
     * {@code DSL.field(DSL.select(<terminal>.<COL>)...).as("__rk_" + key)}. A scalar
     * {@code @reference} is not a call: it names no callee unit, so it is a term whose
     * expression happens to be a subselect. The empty-path standalone shape is not this term
     * (it collapses to {@link Column} with {@link TermAlias#BY_RESULT_KEY}); {@code path} is
     * therefore non-empty and {@code correlation} non-null, mirroring the carrier invariant.
     */
    record ScalarSubselect(
        List<JoinStep> path,
        ParentCorrelation correlation,
        ColumnRef terminal
    ) implements SelectTerm {
        public ScalarSubselect {
            path = List.copyOf(path);
            if (path.isEmpty()) {
                throw new IllegalArgumentException(
                    "ScalarSubselect requires a non-empty path; the standalone reference shape "
                    + "is a Column term aliased by result key");
            }
            Objects.requireNonNull(correlation, "correlation");
            Objects.requireNonNull(terminal, "terminal");
        }
    }

    /**
     * An authored {@code @externalField} helper invocation:
     * {@code <Class>.<method>(table).as("__rk_" + key)}. The method owns the SQL-side
     * expression; this unit contributes only the table context and the result-key alias.
     */
    record HelperCall(MethodRef method) implements SelectTerm {
        public HelperCall {
            Objects.requireNonNull(method, "method");
        }
    }

    /**
     * One {@code @pivot} slot's filtered aggregate over the attribute-table context:
     * {@code DSL.max(table.<value>).filterWhere(table.<discriminator>.eq(DSL.inline(token)))
     * .as(asName)}. Carried by pivot-unit contributions only; {@code asName} is the slot's
     * read name, fixed across occurrences because the slot fetcher reads by name.
     */
    record Aggregate(
        ColumnRef value,
        ColumnRef discriminator,
        String token,
        String asName
    ) implements SelectTerm {
        public Aggregate {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(discriminator, "discriminator");
            Objects.requireNonNull(token, "token");
            if (asName == null || asName.isBlank()) {
                throw new IllegalArgumentException("Aggregate.asName must be non-blank");
            }
        }
    }
}
