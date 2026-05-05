package no.sikt.graphitron.rewrite.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One step in the join path expressed by a {@code @reference} directive.
 *
 * <p>The path is an ordered sequence of hops navigating from the parent table to the target table.
 * All steps are fully resolved at build time; an unresolvable step causes the containing field to
 * be classified as {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField}.
 *
 * <ul>
 *   <li>{@link FkJoin} — navigate via a jOOQ foreign key ({@code .join(table).onKey(fk)}),
 *       with an optional WHERE filter on the enclosing SELECT.</li>
 *   <li>{@link ConditionJoin} — navigate via a user-supplied condition method (no FK constraint);
 *       the condition becomes the ON clause of an explicit JOIN.</li>
 *   <li>{@link LiftedHop} — single-hop terminal pre-keyed by a {@link BatchKey.LifterRowKeyed};
 *       no FK, no source-side columns, the DataLoader key tuple <em>is</em> the target-column
 *       tuple.</li>
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
public sealed interface JoinStep permits JoinStep.FkJoin, JoinStep.ConditionJoin, JoinStep.LiftedHop {

    /**
     * Capability mixed in by hops that pre-resolve a target table the prelude joins to. Lets
     * emitters read {@link #targetTable()}, {@link #slots()}, and {@link #alias()}
     * polymorphically — exactly where the accessors mean the same thing on every implementor.
     *
     * <p>{@link #slots()} returns {@link Iterable} rather than {@link List} on purpose: positional
     * methods ({@code .get(i)}, {@code .getFirst()}, {@code .subList(...)}) become compile errors
     * at the consumer rather than grep findings. Cardinality stays available through
     * {@link #slotCount()}. The variant identity (FK pairing vs lifter identity) lives on the
     * concrete {@link JoinSlot} permit returned by iteration; emitters iterate uniformly through
     * {@link JoinSlot#sourceSide()} and {@link JoinSlot#targetSide()} regardless of permit.
     */
    interface WithTarget {
        TableRef targetTable();
        String alias();
        Iterable<? extends JoinSlot> slots();
        int slotCount();

        /**
         * Source-side columns for this hop, materialised as a {@link List} for readers that
         * need the columns themselves (e.g. constructing a {@link BatchKey.RowKeyed} key tuple)
         * rather than slot-by-slot iteration. The order matches {@link #slots()}; index {@code i}
         * is {@code slots[i].sourceSide()}.
         */
        @LoadBearingClassifierCheck(
            key = "fk-join.slots-oriented-source-and-target",
            description = "BuildContext.synthesizeFkJoin orients each FkSlot at synthesis "
                + "time using sourceSqlName.equalsIgnoreCase(f.getTable().getName()): "
                + "sourceSide is always the column on the hop's source table, targetSide "
                + "always the column on the hop's target table, regardless of which end of "
                + "the catalog FK each maps to. Readers that previously consumed "
                + "fk.sourceColumns() under an implicit FK-on-source precondition "
                + "(parent-holds-FK in the older vocabulary) — to obtain 'the source-table "
                + "column' for a JOIN predicate or BatchKey tuple — now read sourceSide() "
                + "without the precondition: orientation is structural, not dispatched. "
                + "LifterSlot folds both sides onto a single column by construction "
                + "(DataLoader key tuple IS target-column tuple) so the same accessor reads "
                + "uniformly across FkJoin and LiftedHop variants.")
        default List<ColumnRef> sourceSideColumns() {
            List<ColumnRef> out = new ArrayList<>(slotCount());
            for (JoinSlot slot : slots()) out.add(slot.sourceSide());
            return List.copyOf(out);
        }

        /**
         * Target-side columns for this hop, materialised as a {@link List}. The order matches
         * {@link #slots()}; index {@code i} is {@code slots[i].targetSide()}.
         */
        default List<ColumnRef> targetSideColumns() {
            List<ColumnRef> out = new ArrayList<>(slotCount());
            for (JoinSlot slot : slots()) out.add(slot.targetSide());
            return List.copyOf(out);
        }
    }

    /**
     * One hop navigated by a jOOQ foreign key.
     *
     * <p>The generator emits {@code .join(targetAlias).onKey(Keys.FK_...)} for this step. Whether
     * it is an INNER or LEFT JOIN depends on the surrounding query structure (see the interface-level
     * cardinality invariant). All fields are pre-resolved at build time from the jOOQ catalog.
     *
     * <p>{@code fkName} is the SQL constraint name (e.g. {@code "film_language_id_fkey"}), retained
     * for error messages and debugging. {@code fk} is the resolved {@link ForeignKeyRef} carrying
     * the schema-correct {@code Keys} class plus the Java constant name; it is {@code null} when
     * the jOOQ catalog is not available (unit tests). Emitters consume it as
     * {@code .onKey($T.$L)} with {@code fk.keysClass()} / {@code fk.constantName()}.
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
     * enclosing SELECT — <em>not</em> on the JOIN's ON clause. The method receives the source-table
     * alias and the newly-joined table alias as its two arguments, in that order. This field is
     * {@code null} when no {@code condition} argument was specified alongside the key.
     */
    record FkJoin(
        String fkName,
        ForeignKeyRef fk,
        TableRef originTable,
        TableRef targetTable,
        List<JoinSlot.FkSlot> slots,
        MethodRef whereFilter,
        String alias
    ) implements JoinStep, WithTarget {

        public FkJoin {
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
     * {@code fieldName + "_" + stepIndex}. The target table is not pre-resolved here — condition
     * method resolution (P3) will provide it once reflection over the method signature is
     * implemented.
     *
     * <p>Contrast with {@link FkJoin#whereFilter}: that field is a WHERE clause on the enclosing
     * SELECT; this condition is the JOIN's ON clause.
     */
    record ConditionJoin(MethodRef condition, String alias) implements JoinStep {}

    /**
     * One hop pre-keyed by a {@link BatchKey.LifterRowKeyed} — no foreign key, no traversal
     * direction, no source-side-distinct-from-target columns. The DataLoader key tuple carried
     * by the BatchKey <em>is</em> the target-column tuple, encoded as a type fact: each slot is
     * a {@link JoinSlot.LifterSlot} whose single {@code column} component answers both
     * {@link JoinSlot#sourceSide()} and {@link JoinSlot#targetSide()}. The JOIN-on predicate of
     * the rows-method becomes {@code target.<slot.targetSide()> = parentInput.field(i+1)}
     * directly, identical in shape to the FK case.
     *
     * <p>{@link BatchKey.LifterRowKeyed} holds a single {@code LiftedHop} on its own record;
     * the classifier publishes the same instance through {@code joinPath = [hop]} for back-compat
     * with the existing rows-method loop, but the BatchKey is the source of truth. This makes
     * the single-hop invariant a type fact (one record, one hop) rather than a classifier
     * convention.
     */
    record LiftedHop(
        TableRef targetTable,
        List<JoinSlot.LifterSlot> slots,
        String alias
    ) implements JoinStep, WithTarget {

        public LiftedHop {
            slots = List.copyOf(slots);
        }

        @Override public int slotCount() { return slots.size(); }
    }
}
