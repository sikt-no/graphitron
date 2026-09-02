package no.sikt.graphitron.command;

import no.sikt.graphitron.model.jooq.ColumnRef;
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
 * on that shape today); {@link ScalarSubselect} and {@link Aggregate} carry a fixed projected
 * name or fall back to the runtime result key, since neither has a column identity and their
 * read sides address them by the name the producer decided.
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
     * {@code DSL.field(DSL.select(<terminal>.<COL>)...).as(<asName, or "__rk_" + key>)}, capped
     * {@code .limit(1)} so it cannot multiply the rows of the statement that projects it. A
     * scalar {@code @reference} is not a call: it names no callee unit, so it is a term whose
     * expression happens to be a subselect. The empty-path standalone shape is not this term
     * (it collapses to {@link Column} with {@link TermAlias#BY_RESULT_KEY}); {@code path} is
     * therefore non-empty and {@code correlation} non-null, mirroring the carrier invariant.
     *
     * <p>{@code asName} is the fixed projected name, or {@code null} to alias by the runtime
     * result key (the projection-unit shape, where aliased duplicate selections must stay
     * distinct). {@code gate} is an optional extra WHERE conjunct on the parent row, {@code null}
     * for a term whose correlation is its whole parent-side restriction.
     */
    record ScalarSubselect(
        List<JoinStep> path,
        ParentCorrelation correlation,
        ColumnRef terminal,
        String asName,
        ParentColumnEquals gate
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
            if (asName != null && asName.isBlank()) {
                throw new IllegalArgumentException(
                    "ScalarSubselect.asName must be non-blank when present; null is the "
                    + "alias-by-result-key shape");
            }
        }

        /** The result-key-aliased, ungated shape: the plain scalar {@code @reference}. */
        public ScalarSubselect(List<JoinStep> path, ParentCorrelation correlation, ColumnRef terminal) {
            this(path, correlation, terminal, null, null);
        }

        /**
         * An equality between a column of the <em>parent</em> row and a literal, ANDed into the
         * subselect's WHERE beside the correlation and the per-hop filters. Qualified off the
         * parent table local's own jOOQ instance by SQL name (the qualifier jOOQ renders for the
         * FROM clause), so the reference matches the enclosing statement's FROM by construction
         * and stays unambiguous when a joined table re-declares the column.
         *
         * <p>{@code column} is the resolved {@link ColumnRef} rather than a bare SQL name because
         * the renderer needs both spellings: {@code sqlName} for that qualified reference, and
         * {@code javaName} for the column's {@code getDataType()}, which types the {@code value}
         * bind so a Postgres-enum column compares against an operand of its own type.
         */
        public record ParentColumnEquals(ColumnRef column, String value) {
            public ParentColumnEquals {
                Objects.requireNonNull(column, "column");
                Objects.requireNonNull(value, "value");
            }
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
