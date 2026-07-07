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
 *   <li>{@link Hop} — the two-axis step (R333): a {@link TableExpr} target plus an {@link On}
 *       describing how the step joins. Replaces {@link FkJoin} / {@link ConditionJoin}, which
 *       remain permits only until the cutover completes.</li>
 *   <li>{@link FkJoin} — navigate via a jOOQ foreign key ({@code .join(table).onKey(fk)}),
 *       with an optional WHERE filter on the enclosing SELECT.</li>
 *   <li>{@link ConditionJoin} — navigate via a user-supplied condition method (no FK constraint);
 *       the condition becomes the ON clause of an explicit JOIN.</li>
 *   <li>{@link LiftedHop} — single-hop terminal pre-keyed by a {@code @sourceRows} lifter or
 *       a class-backed-parent accessor; no FK, no source-side columns, the DataLoader key
 *       tuple <em>is</em> the target-column tuple.</li>
 * </ul>
 *
 * <p>{@link FkJoin} and {@link LiftedHop} share the {@link WithTarget} capability so the
 * rows-method prelude reads {@code targetTable()}, {@code slots()}, and {@code alias()}
 * uniformly without per-accessor sealed switches. The variant identity is carried by the
 * slot subtype ({@link JoinSlot.FkSlot} vs. {@link JoinSlot.LifterSlot}), not by the
 * carrier; capabilities express what is uniformly true, sealed switches express what
 * varies by identity.
 *
 * <p><b>Variant contrast:</b>
 * <pre>
 *   FkJoin (key only):          .join(target).onKey(FK)
 *   FkJoin (key + whereFilter): .join(target).onKey(FK) ... .where(filter(src, target))
 *   ConditionJoin:              .join(target).on(condition(src, target))
 * </pre>
 * {@code whereFilter} is a WHERE clause on the enclosing SELECT — it does not affect the JOIN's
 * ON clause. {@link ConditionJoin#condition} is the ON clause.
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
public sealed interface JoinStep permits JoinStep.Hop, JoinStep.FkJoin, JoinStep.ConditionJoin, JoinStep.LiftedHop {

    /**
     * Capability mixed in by every hop that pre-resolves a target table the prelude joins to.
     * Lets emitters read {@link #targetTable()} and {@link #alias()} uniformly without a sealed
     * switch — every {@link JoinStep} permit implements this interface (FkJoin and LiftedHop via
     * {@link WithTarget}; ConditionJoin directly).
     *
     * <p>Capability interfaces and sealed switches serve different roles: this interface is the
     * "uniformly true" axis (every hop has a target table); {@link WithTarget} below adds the
     * FK-correlation slot iteration that only FK-style hops (FkJoin, LiftedHop) carry.
     */
    interface HasTargetTable {
        TableRef targetTable();
        String alias();
    }

    /**
     * Capability mixed in by hops that pre-resolve a target table <em>and</em> a slot list pairing
     * source / target columns for FK-correlation predicates. Lets emitters read
     * {@link #targetTable()}, {@link HasSlots#slots()}, and {@link #alias()} polymorphically —
     * exactly where the accessors mean the same thing on every implementor.
     *
     * <p>The slot-iteration contract (including the {@link Iterable}-not-{@link List}
     * discipline) lives on {@link HasSlots}, which this interface composes with
     * {@link HasTargetTable}. Transitional: dies with the flat {@link FkJoin} variant when the
     * two-axis {@link Hop} cutover completes — slot presence then varies <em>within</em> a step
     * ({@link On.ColumnPairs} has slots, {@link On.Predicate} does not), so it stops being a
     * step-level capability and is answered by sealed dispatch on {@link Hop#on()} instead.
     *
     * <p>{@link JoinStep.ConditionJoin} implements {@link HasTargetTable} directly (no slot list)
     * — its source/target correlation is the condition method call, not paired columns.
     */
    interface WithTarget extends HasTargetTable, HasSlots {
    }

    /**
     * One join step as two orthogonal facts (R333): a <b>target</b> node materialized by a
     * {@link TableExpr}, and an <b>{@code on}</b> describing how the step joins to it
     * ({@link On.ColumnPairs FK-derived column pairs} or an {@link On.Predicate authored
     * predicate}). Replaces the flat {@link FkJoin} / {@link ConditionJoin} variants, which
     * spliced the two facts into the permit name.
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
     * computed at build time as {@code fieldName + "_" + stepIndex}; unique per field × depth,
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
        }

        @Override
        public TableRef targetTable() {
            return switch (target) {
                case TableExpr.Catalog c -> c.table();
            };
        }
    }

    /**
     * One hop navigated by a jOOQ foreign key.
     *
     * <p>The generator emits {@code .join(targetAlias).onKey(Keys.FK_...)} for this step. Whether
     * it is an INNER or LEFT JOIN depends on the surrounding query structure (see the interface-level
     * cardinality invariant). All fields are pre-resolved at build time from the jOOQ catalog.
     *
     * <p>{@code fk} is the resolved {@link ForeignKeyRef} carrying the SQL constraint name, the
     * schema-correct {@code Keys} class, and the Java constant name. Non-null by the type system:
     * the {@link no.sikt.graphitron.rewrite.BuildContext#synthesizeFkJoin} resolver routes a
     * catalog-miss on the FK name into a {@code FkJoinResolution.UnknownForeignKey} arm rather
     * than producing an {@code FkJoin} with a null FK. Emitters consume it as
     * {@code .onKey($T.$L)} with {@code fk.keysClass()} / {@code fk.constantName()}; readers that
     * need the SQL constraint name use {@code fk.sqlName()}.
     *
     * <p>{@code slots} carries the FK pairing as a list of {@link JoinSlot.FkSlot}, oriented at
     * synthesis time: each slot's {@link JoinSlot#sourceSide()} is the column on the hop's source
     * table (the side the join is entered from), and {@link JoinSlot#targetSide()} is the column
     * on the hop's target table. The FK-direction question (which end of the catalog FK sits on
     * which side) is answered once in {@code BuildContext.synthesizeFkJoin} and baked into the
     * slot pair, so emitter sites read {@code targetAlias.<slot.targetSide()>.eq(sourceCtx.<slot.sourceSide()>)}
     * uniformly without re-deriving direction. The list is empty when the jOOQ catalog is
     * unavailable (unit tests).
     *
     * <p>{@code originTable} is the <em>traversal-origin</em> table of this hop — i.e. the side
     * the join enters <em>from</em>, which is the parent table for hop 0 and the previous hop's
     * target for subsequent hops. Falls back to an empty {@link TableRef} when the jOOQ catalog
     * is unavailable.
     *
     * <p>{@code targetTable} resolves to the table on the hop's target side (the side the join
     * lands on). Combined with {@code slots[i].targetSide()}, this gives the fully-qualified
     * target columns each slot pairs against.
     *
     * <p>{@code alias} is the unique table alias for this step within the enclosing query, computed
     * at build time as {@code fieldName + "_" + stepIndex} (e.g. {@code "language_0"} for the
     * first step of a {@code language} field). The alias is unique per field × depth, which handles
     * self-referential join paths where the same table appears multiple times.
     *
     * <p>{@code whereFilter} is an optional user-supplied condition method resolved from a
     * {@code condition} argument on the same {@code @reference} path element as the {@code key}.
     * When present, the generator appends it as a {@code .where()} or {@code .and()} clause on the
     * enclosing SELECT — <em>not</em> on the JOIN's ON clause. The {@link JoinConditionRef}
     * wrapper types the calling convention (source-table alias, newly-joined alias, in that
     * order). This field is {@code null} when no {@code condition} argument was specified
     * alongside the key.
     */
    record FkJoin(
        ForeignKeyRef fk,
        TableRef originTable,
        TableRef targetTable,
        List<JoinSlot.FkSlot> slots,
        JoinConditionRef whereFilter,
        String alias
    ) implements JoinStep, WithTarget {

        public FkJoin {
            if (fk == null) {
                throw new NullPointerException(
                    "FkJoin.fk must not be null; resolution failures route through"
                    + " BuildContext.synthesizeFkJoin's FkJoinResolution sub-taxonomy.");
            }
            slots = List.copyOf(slots);
        }

        @Override public int slotCount() { return slots.size(); }
    }

    /**
     * One hop navigated by a user-supplied condition method (no FK constraint involved).
     *
     * <p>The condition method becomes the ON clause of an explicit join: the generator emits
     * {@code .join(targetAlias).on(condition(sourceAlias, targetAlias))}. Used when there is no
     * database foreign key for this join step. Typical use: reconnecting a service or
     * {@code @externalField} result back to the parent table when no FK exists.
     *
     * <p>{@code alias} is the unique table alias for this step, computed at build time as
     * {@code fieldName + "_" + stepIndex}.
     *
     * <p>{@code targetTable} is pre-resolved at parse time by {@code BuildContext
     * .resolveConditionJoinTarget}: for a terminal hop, from the carrier field's return-type
     * {@code @table} binding; for an intermediate hop, by reflecting on the condition method's
     * second parameter. Both unresolvable cases route through {@code Rejection.AuthorError}
     * upstream; the compact constructor below is the structural safety net so consumers can
     * read {@link #targetTable()} without a null check.
     *
     * <p>Contrast with {@link FkJoin#whereFilter}: that field is a WHERE clause on the enclosing
     * SELECT; this condition is the JOIN's ON clause.
     */
    record ConditionJoin(JoinConditionRef condition, TableRef targetTable, String alias)
            implements JoinStep, HasTargetTable {

        public ConditionJoin {
            if (condition == null) {
                throw new NullPointerException("ConditionJoin.condition must not be null");
            }
            if (targetTable == null) {
                throw new NullPointerException(
                    "ConditionJoin.targetTable must not be null; BuildContext.parsePathElement "
                    + "resolves it from the carrier field's return-type @table binding (terminal "
                    + "hop) or by reflecting on the condition method's second parameter "
                    + "(intermediate hop). Both unresolvable cases route through "
                    + "Rejection.AuthorError upstream.");
            }
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
     * a single {@code LiftedHop}. The {@code @reference}-composed shape is a list of
     * {@link FkJoin} hops; that path does not use {@code LiftedHop}.
     */
    record LiftedHop(
        TableRef targetTable,
        List<JoinSlot.LifterSlot> slots,
        String alias
    ) implements JoinStep, WithTarget {

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
