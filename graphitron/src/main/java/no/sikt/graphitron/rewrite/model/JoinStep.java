package no.sikt.graphitron.rewrite.model;

import java.util.List;
import no.sikt.graphitron.model.jooq.TableRef;

/**
 * One step in the join path expressed by a {@code @reference} directive.
 *
 * <p>The path is an ordered sequence of hops navigating from the parent table to the target table.
 * All steps are fully resolved at build time; an unresolvable step causes the containing field to
 * be classified as {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField}.
 * {@link Hop} is the sole permit and {@code @reference} parsing the sole producer; how a hop
 * joins is a sealed dispatch on {@link Hop#on()}:
 * <pre>
 *   Hop, On.ColumnPairs:            .join(target).onKey(FK)
 *   Hop, On.ColumnPairs + filter:   .join(target).onKey(FK) ... .where(filter(src, target))
 *   Hop, On.Predicate:              .join(target).on(condition(src, target))
 * </pre>
 * {@link Hop#filter()} is a WHERE clause on the enclosing SELECT and does not affect the JOIN's
 * ON clause; {@link Hop#on()} is the ON clause.
 *
 * <h2>Cardinality invariant</h2>
 *
 * <p>A {@code @reference} join path must never change the cardinality of the source row set:
 *
 * <ol>
 *   <li><b>No row elimination.</b> A source row must always produce at least one output row.
 *       Inside a correlated subquery the outer row survives a non-matching join (the subquery
 *       returns {@code NULL} or an empty array), so INNER JOIN is safe. In a flat batch join
 *       (DataLoader / split query) every source key must appear in the result set so the
 *       DataLoader can align results to keys; INNER JOIN would silently drop rows with a
 *       {@code NULL} FK, so LEFT JOIN is mandatory. The join type is a generation-time decision
 *       based on query structure, not a property of the step.</li>
 *
 *   <li><b>No unintended row multiplication.</b> Fan-out is only valid when the referencing
 *       field returns a list or connection, where it is the intended result, collected and
 *       grouped by the DataLoader. Nothing rejects a fanning hop under a single-value field:
 *       the emission answers it instead, by never putting such a hop in the row-producing
 *       statement. A single-value {@code @reference} lowers to a capped correlated subselect
 *       ({@link no.sikt.graphitron.command.SelectTerm.ScalarSubselect}), which picks one row
 *       whatever the hop's cardinality.</li>
 * </ol>
 */
public sealed interface JoinStep permits JoinStep.Hop {

    /**
     * Capability mixed in by every {@link JoinStep} permit: a step always pre-resolves a target
     * table, so emitters read {@link #targetTable()} and {@link #alias()} uniformly without a
     * sealed switch. How a hop joins varies by identity and stays with sealed dispatch on
     * {@link Hop#on()}.
     */
    interface HasTargetTable {
        TableRef targetTable();
        String alias();
    }

    /**
     * One join step as two orthogonal facts: a <b>target</b> node materialized by a
     * {@link TableExpr}, and an <b>{@code on}</b> describing how the step joins to it
     * ({@link On.ColumnPairs FK-derived column pairs}, an {@link On.Predicate authored
     * predicate}, or {@link On.Lateral} for routine targets).
     *
     * <p>{@code target} is the table node this step joins to; {@link #targetTable()} folds it
     * back to the {@link TableRef} for the uniform {@link HasTargetTable} read.
     *
     * <p>{@code on} is non-null on every hop (constructor-enforced): the path representation has
     * no start-node entry; the source supplies the start, so {@code path[0]} already joins.
     *
     * <p>{@code originTable} is the traversal-origin table of this hop, the side the join
     * enters <em>from</em>: the parent table for hop 0, the previous hop's target for subsequent
     * hops. Denormalized so consumers read it pre-resolved. {@code null} when the source is not
     * table-backed or the jOOQ catalog is unavailable (unit tests).
     *
     * <p>{@code filter} is an optional per-hop filter appended to the enclosing SELECT's WHERE,
     * <em>not</em> the JOIN's ON clause (that is {@code on}); resolved from a {@code condition:}
     * sub-argument accompanying a {@code key:}/{@code table:} path element. {@code null} when
     * the element carried none.
     *
     * <p>{@code alias} is the unique table alias for this step within the enclosing query,
     * computed at build time as {@code fieldName + "_" + stepIndex}; unique per field and depth,
     * which handles self-referential join paths where the same table appears multiple times.
     */
    record Hop(
        TableExpr target,
        On on,
        TableRef originTable,
        JoinConditionRef filter,
        String alias
    ) implements JoinStep, HasTargetTable {

        public Hop {
            if (target == null) {
                throw new NullPointerException("JoinStep.Hop.target must not be null");
            }
            if (on == null) {
                throw new NullPointerException(
                    "JoinStep.Hop.on must not be null: every shipped hop joins. A future "
                    + "start-node entry is its own sealed variant, never a null/absent on.");
            }
            if (alias == null) {
                throw new NullPointerException("JoinStep.Hop.alias must not be null");
            }
            // Lateralness and routine-ness are one fact, pinned on the hop itself: a routine
            // node carries no key metadata to join on (its correlation rides the call
            // arguments), and a catalog table never joins laterally, so On.Lateral appears
            // exactly on TableExpr.RoutineCall targets. Enforced here rather than per consumer
            // leaf so every carrier inherits the correspondence.
            if ((target instanceof TableExpr.RoutineCall) != (on instanceof On.Lateral)) {
                throw new IllegalArgumentException(
                    "JoinStep.Hop joins a routine node laterally and a catalog node by key or "
                    + "predicate — got target " + target.getClass().getSimpleName()
                    + " with on " + on.getClass().getSimpleName());
            }
        }

        @Override
        public TableRef targetTable() {
            return switch (target) {
                case TableExpr.Catalog c -> c.table();
                case TableExpr.RoutineCall rc -> rc.resultTable();
            };
        }
    }

}
