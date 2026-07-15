package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * How a {@link JoinStep.Hop} joins to its target — the <em>on</em> axis of the two-axis step
 * model (R333). Orthogonal to the target node itself ({@link TableExpr}): any target can in
 * principle be joined by either arm.
 *
 * <p>Non-null on every shipped hop, and it stays that way: R333 models {@code on} as absent
 * exactly for the path's start node, but the shipped path representation has no start-node entry
 * (the source supplies the start; {@code path[0]} already joins). When a start-node entry
 * arrives (R435's root routine node), it lands as its own sealed sibling or a
 * {@code (start, List<Hop>)} path carrier — never by widening {@code Hop.on} to
 * {@code Optional<On>}, which would reintroduce a null-in-exactly-one-case state and forfeit the
 * every-hop-joins certainty the non-null component buys.
 *
 * <p>A new way to join is a new arm here (e.g. {@link Lateral} for routine targets), not a new
 * step type. The FK-vs-derived question is its own small seal on {@link ColumnPairs}
 * ({@link Keying}, the seal R438 pre-planned): the catalog FK is one derivation of the pairs,
 * the R435 name-matched key (hops adjacent to a routine node, whose result table carries no FK
 * metadata) the other.
 */
public sealed interface On permits On.ColumnPairs, On.Predicate, On.Lateral {

    /**
     * How a {@link ColumnPairs}' pairing was derived. Provenance with one emit consequence: the
     * ON clause shape differs per arm ({@code .onKey(Keys.<FK>)} for legible generated code
     * where a catalog FK exists, the explicit column-equality conjunction over {@code slots}
     * where none does — see {@code JoinPathEmitter.emitBridgingJoin}). Correlation and
     * split-rows readers stay on {@code slots} and never dispatch here.
     */
    sealed interface Keying permits Keying.ForeignKey, Keying.NameMatchedKey {

        /** Human-readable derivation label for diagnostics; never emitted into generated code. */
        String describe();

        /**
         * The pairs were derived from a resolved catalog foreign key. {@code fk} carries what
         * the {@code .onKey(Keys.<FK>)} emit needs ({@code keysClass()} / {@code constantName()});
         * classification and diagnostics additionally read {@code fk.sqlName()}. Non-null by
         * the type system: catalog misses route through {@code BuildContext.synthesizeFkJoin}'s
         * {@code FkJoinResolution} sub-taxonomy rather than producing a pair list with no
         * provenance.
         */
        record ForeignKey(ForeignKeyRef fk) implements Keying {
            public ForeignKey {
                if (fk == null) {
                    throw new NullPointerException(
                        "On.Keying.ForeignKey.fk must not be null; resolution failures route "
                        + "through BuildContext.synthesizeFkJoin's FkJoinResolution sub-taxonomy.");
                }
            }
            @Override public String describe() { return "FK '" + fk.sqlName() + "'"; }
        }

        /**
         * The pairs were derived by name-matching the target's unique key (PK by default)
         * against the previous node's columns — R333's keying rule for FK-less nodes, minted by
         * R435 for hops adjacent to a routine node. The build check (the previous node exposes
         * every key column by SQL name) ran at derivation time; {@code targetKeyName} is the
         * matched constraint's SQL name, retained for diagnostics only. The emitted ON is the
         * explicit column-equality conjunction over {@code slots} — there is no {@code Keys}
         * constant to reference.
         */
        record NameMatchedKey(String targetKeyName) implements Keying {
            public NameMatchedKey {
                if (targetKeyName == null) {
                    throw new NullPointerException(
                        "On.Keying.NameMatchedKey.targetKeyName must not be null");
                }
            }
            @Override public String describe() { return "name-matched key '" + targetKeyName + "'"; }
        }
    }

    /**
     * The step joins on paired source / target columns.
     *
     * <p>{@code keying} is the pairing's derivation ({@link Keying}): a resolved catalog FK or
     * the R435 name-matched key. Emitters dispatch on it for the ON clause shape; slot readers
     * do not.
     *
     * <p>{@code slots} carries the pairing as {@link JoinSlot.FkSlot}s oriented at synthesis
     * time: each slot's {@link JoinSlot#sourceSide()} is the column on the hop's origin table,
     * {@link JoinSlot#targetSide()} the column on the hop's target. The direction question is
     * answered once at synthesis time (for FK pairs in {@code BuildContext.synthesizeFkJoin})
     * and baked into the pair, so readers are direction-blind. The list is empty when the jOOQ
     * catalog is unavailable (unit tests).
     */
    record ColumnPairs(Keying keying, List<JoinSlot.FkSlot> slots) implements On {
        public ColumnPairs {
            if (keying == null) {
                throw new NullPointerException("On.ColumnPairs.keying must not be null");
            }
            slots = List.copyOf(slots);
        }

        public int slotCount() { return slots.size(); }

        /**
         * Source-side columns, materialised as a {@link List} for readers that need the columns
         * themselves (e.g. constructing a {@code SourceKey} entry-point column tuple) rather
         * than slot-by-slot iteration. The order matches {@link #slots()}; index {@code i} is
         * {@code slots[i].sourceSide()}. (R431: formerly on the {@code HasSlots} capability,
         * which died with {@code JoinStep.LiftedHop} — this is its only implementor.)
         */
        public List<ColumnRef> sourceSideColumns() {
            return slots.stream().map(JoinSlot::sourceSide).toList();
        }

        /**
         * Target-side columns, materialised as a {@link List}. The order matches
         * {@link #slots()}; index {@code i} is {@code slots[i].targetSide()}.
         */
        public List<ColumnRef> targetSideColumns() {
            return slots.stream().map(JoinSlot::targetSide).toList();
        }
    }

    /**
     * The step joins on a user-supplied condition method (no FK involved): the generator emits
     * {@code .join(target).on(condition(sourceAlias, targetAlias))}. The
     * {@link JoinConditionRef} wrapper types the two-argument calling convention.
     */
    record Predicate(JoinConditionRef condition) implements On {
        public Predicate {
            if (condition == null) {
                throw new NullPointerException("On.Predicate.condition must not be null");
            }
        }
    }

    /**
     * The step joins laterally (R435): the target is a {@link TableExpr.RoutineCall} whose
     * arguments reference the previous node's columns ({@link ParamSource.SourceColumn}
     * bindings), so the correlation rides the call arguments rather than an ON predicate or
     * column pairs. Lateralness is a positive fact on this axis — never an overloaded
     * {@code on}-absence.
     *
     * <p>The arm carries no payload: the correlated columns live on the target's
     * {@link RoutineRef.ArgBinding}s, keeping the "what is the node" and "how does the step
     * join" axes from duplicating each other. An uncorrelated routine node (all bindings
     * argument-sourced) still uses this arm — the routine call is the row source either way,
     * and the emitters render the same call shape with zero column references.
     */
    record Lateral() implements On {}
}
