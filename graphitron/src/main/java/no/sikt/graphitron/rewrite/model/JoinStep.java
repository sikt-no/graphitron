package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * One step in the join path expressed by a {@code @reference} directive.
 *
 * <p>The path is an ordered sequence of hops navigating from the parent table to the target table.
 * All steps are fully resolved at build time; an unresolvable step causes the containing field to
 * be classified as {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField}.
 *
 * <ul>
 *   <li>{@link Hop} — the two-axis step (R333): a <b>target</b> node materialized by a
 *       {@link TableExpr}, and an <b>{@code on}</b> describing how the step joins to it
 *       ({@link On.ColumnPairs FK-derived column pairs} or an {@link On.Predicate authored
 *       predicate}). Every {@code @reference}-parsed step is a {@code Hop}.</li>
 *   <li>{@link LiftedHop} — single-hop terminal pre-keyed by a {@code @sourceRows} lifter or
 *       a class-backed-parent accessor; no FK, no source-side columns, the DataLoader key
 *       tuple <em>is</em> the target-column tuple. Transitional: its lifted slots are
 *       source-side key provenance (R333's {@code Lift} arm), which is R431's decomposition;
 *       R431 retires this permit when it re-types {@code SourceKey.path}.</li>
 * </ul>
 *
 * <p>Both permits implement {@link HasTargetTable} so emitters read {@code targetTable()} and
 * {@code alias()} uniformly. Slot iteration is the {@link HasSlots} capability, implemented by
 * {@link On.ColumnPairs} and {@link LiftedHop} — the two slot populations that coexist until
 * R431. Capabilities express what is uniformly true; how a {@link Hop} joins is a sealed
 * dispatch on {@link Hop#on()}.
 *
 * <p><b>Step contrast:</b>
 * <pre>
 *   Hop, On.ColumnPairs:            .join(target).onKey(FK)
 *   Hop, On.ColumnPairs + filter:   .join(target).onKey(FK) ... .where(filter(src, target))
 *   Hop, On.Predicate:              .join(target).on(condition(src, target))
 * </pre>
 * {@link Hop#filter()} is a WHERE clause on the enclosing SELECT — it does not affect the JOIN's
 * ON clause. {@link Hop#on()} is the ON clause.
 *
 * <h2>Cardinality invariant</h2>
 *
 * <p>A {@code @reference} join path must never change the cardinality of the source row set.
 * Two structural rules enforce this:
 *
 * <ol>
 *   <li><b>No row elimination.</b> A source row must always produce at least one output row.
 *       This depends on the query structure the generator chooses:
 *       <ul>
 *         <li><em>Correlated subquery</em> (scalar or multiset subselect): the outer row
 *             survives regardless of whether the inner join matches, so INNER JOIN is safe and
 *             preferred inside the subquery. A non-matching inner join simply makes the subquery
 *             return {@code NULL} or an empty array.</li>
 *         <li><em>Flat batch join</em> (DataLoader / split query): all source keys must appear
 *             in the result set so the DataLoader can align results to keys. INNER JOIN would
 *             silently drop source rows where the FK is {@code NULL}. LEFT JOIN is mandatory.</li>
 *       </ul>
 *       The join type is therefore a <em>generation-time decision</em> based on query structure,
 *       not a property of the step itself.</li>
 *
 *   <li><b>No unintended row multiplication.</b> Fan-out (one source row producing many output
 *       rows) is only valid when the referencing field returns a list or connection — in which case
 *       fan-out is the <em>intended</em> result, collected and grouped by the DataLoader. For a
 *       single-value field, any fan-out is a schema error. The validator is responsible for
 *       detecting mis-directed FK traversal (one-to-many navigation on a single-value field) and
 *       rejecting it.</li>
 * </ol>
 */
public sealed interface JoinStep permits JoinStep.Hop, JoinStep.LiftedHop {

    /**
     * Capability mixed in by every step that pre-resolves a target table the prelude joins to.
     * Lets emitters read {@link #targetTable()} and {@link #alias()} uniformly without a sealed
     * switch — every {@link JoinStep} permit implements this interface.
     *
     * <p>Capability interfaces and sealed switches serve different roles: this interface is the
     * "uniformly true" axis (every step has a target table); how a {@link Hop} joins varies by
     * identity and is answered by sealed dispatch on {@link Hop#on()}. Slot iteration lives on
     * {@link HasSlots}, implemented by the slot-carrying types ({@link On.ColumnPairs},
     * {@link LiftedHop}).
     */
    interface HasTargetTable {
        TableRef targetTable();
        String alias();
    }

    /**
     * One join step as two orthogonal facts (R333): a <b>target</b> node materialized by a
     * {@link TableExpr}, and an <b>{@code on}</b> describing how the step joins to it
     * ({@link On.ColumnPairs FK-derived column pairs} or an {@link On.Predicate authored
     * predicate}). Replaced the flat {@code FkJoin} / {@code ConditionJoin} variants, which
     * spliced the two facts into the permit name (R438).
     *
     * <p>{@code target} is the table node this step joins to; day one always a
     * {@link TableExpr.Catalog}. {@link #targetTable()} folds it back to the {@link TableRef}
     * for the uniform {@link HasTargetTable} read.
     *
     * <p>{@code on} is non-null on every hop: the shipped path representation has no start-node
     * entry (the source supplies the start; {@code path[0]} already joins). See {@link On} for
     * the forward contract when a start-node variant arrives.
     *
     * <p>{@code originTable} is the traversal-origin table of this hop — the side the join
     * enters <em>from</em>: the parent table for hop 0, the previous hop's target for subsequent
     * hops. Denormalized (it duplicates the previous step's target / the source table) and kept
     * mechanically because consumers read it pre-resolved; deleting it in favor of a
     * path-position derivation is homed in R431. {@code null} when the source is not
     * table-backed or the jOOQ catalog is unavailable (unit tests).
     *
     * <p>{@code filter} is an optional per-hop filter appended to the enclosing SELECT's WHERE —
     * <em>not</em> the JOIN's ON clause (that is {@code on}); resolved from a {@code condition:}
     * sub-argument accompanying a {@code key:}/{@code table:} path element. {@code null} when
     * the element carried none.
     *
     * <p>{@code alias} is the unique table alias for this step within the enclosing query,
     * computed at build time as {@code fieldName + "_" + stepIndex} (e.g. {@code "language_0"}
     * for the first step of a {@code language} field); unique per field × depth, which handles
     * self-referential join paths where the same table appears multiple times.
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
            // R435: lateralness and routine-ness are one fact, pinned on the hop itself. A
            // routine node carries no key metadata to join on (its correlation rides the call
            // arguments), and a catalog table never joins laterally — so On.Lateral appears
            // exactly on TableExpr.RoutineCall targets. Enforced here rather than per consumer
            // leaf so every carrier (TableField's chain, QueryRoutineTableField's hops guard)
            // inherits the correspondence.
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

    /**
     * One hop pre-keyed by a {@code @sourceRows} lifter or a class-backed-parent accessor —
     * no foreign key, no traversal direction, no source-side-distinct-from-target columns. The
     * DataLoader key tuple <em>is</em> the target-column tuple, encoded as a type fact: each
     * slot is a {@link JoinSlot.LifterSlot} whose single {@code column} component answers both
     * {@link JoinSlot#sourceSide()} and {@link JoinSlot#targetSide()}. The JOIN-on predicate of
     * the rows-method becomes {@code target.<slot.targetSide()> = parentInput.field(i+1)}
     * directly, identical in shape to the FK case.
     *
     * <p>The leaf-PK shape (no {@code @reference}) sits at {@code SourceKey.path = [hop]} with
     * a single {@code LiftedHop}. The {@code @reference}-composed shape is a list of FK-derived
     * {@link Hop}s; that path does not use {@code LiftedHop}.
     *
     * <p>Transitional: this permit is not a join-path fact — {@code @reference} path parsing
     * never produces it, and its lifted slots are source-side key provenance (R333's
     * {@code Lift} arm). R431 retires it when it re-types {@code SourceKey.path}, at which
     * point {@link HasSlots} collapses to its single {@link On.ColumnPairs} implementor.
     */
    record LiftedHop(
        TableRef targetTable,
        List<JoinSlot.LifterSlot> slots,
        String alias
    ) implements JoinStep, HasTargetTable, HasSlots {

        public LiftedHop {
            if (slots.isEmpty()) {
                throw new IllegalArgumentException(
                    "JoinStep.LiftedHop requires a non-empty slots list — every Reader arm "
                    + "delegating its key columns through this hop (SourceRowsCall, "
                    + "AccessorCall) needs at least one column.");
            }
            slots = List.copyOf(slots);
        }

        @Override public int slotCount() { return slots.size(); }
    }
}
