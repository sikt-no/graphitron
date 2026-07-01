package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * Pre-resolved shape of the step-0 parent correlation for {@code @reference}-carrying child
 * fields. Lifts the binary fork between FK-slot correlation and ConditionJoin-method correlation
 * out of the emitters into the model — the five emitter sites (inline {@code TableField} /
 * {@code LookupTableField} / {@code ColumnReferenceField} step-0; split-rows
 * {@code buildListMethod} / {@code buildSingleMethod} / {@code buildConnectionMethod}) all read
 * variant identity from a sealed switch on this carrier rather than evaluating
 * {@code instanceof JoinStep.ConditionJoin} over {@code joinPath.get(0)}.
 *
 * <p>The two axes are decoupled by design: {@link JoinStep.HasTargetTable} handles "what is this
 * hop's target table" (uniform across permits); {@link ParentCorrelation} handles "what shape does
 * parent correlation take at this path" (forks on first-hop identity). Any
 * {@link no.sikt.graphitron.rewrite.model.ChildField} variant can carry
 * {@link OnConditionJoin} regardless of which intermediate hops appear in the joinPath.
 *
 * <p>Classifier-time invariant: each carrier field's compact constructor verifies
 * {@code parentCorrelation.firstHop() == joinPath.get(0)} so the model can never carry a
 * correlation that disagrees with the path it sits on. The carrier is a denormalised view of
 * data already on {@code joinPath.get(0)} + {@code sourceKey} (where present) + the carrier
 * field's parent type's {@code @table} binding — pre-resolved once at parse time so consumers
 * don't re-derive at each emit site.
 */
public sealed interface ParentCorrelation
        permits ParentCorrelation.OnFkSlots, ParentCorrelation.OnConditionJoin {

    /**
     * Identity of the first hop this correlation pairs against. The arm-specific accessors
     * ({@link OnFkSlots#firstHop()}, {@link OnConditionJoin#firstHop()}) return narrower
     * subtypes; this helper folds them onto the {@link JoinStep} root so the carrier-side
     * invariant {@code parentCorrelation.firstStep() == joinPath.get(0)} can be expressed
     * without an arm-specific cast at every consumer.
     */
    default JoinStep firstStep() {
        return switch (this) {
            case OnFkSlots fk -> (JoinStep) fk.firstHop();
            case OnConditionJoin cj -> cj.firstHop();
        };
    }

    /**
     * Carrier-side classifier invariant: a non-empty {@code joinPath} carries a non-null
     * {@link ParentCorrelation} whose {@link #firstStep()} is the same instance as
     * {@code joinPath.get(0)}; an empty joinPath carries a null correlation
     * (the lookup runs standalone and no parent correlation is needed). Each ChildField
     * variant compact constructor invokes this helper so the model can never carry a
     * correlation that disagrees with the path it sits on.
     */
    static void checkCarrierInvariant(ParentCorrelation pc, List<JoinStep> joinPath, String variantName) {
        if (joinPath.isEmpty()) {
            if (pc != null) {
                throw new IllegalArgumentException(
                    variantName + ".parentCorrelation must be null when joinPath is empty "
                    + "(standalone-lookup shape); got " + pc.getClass().getSimpleName());
            }
            return;
        }
        if (pc == null) {
            throw new NullPointerException(
                variantName + ".parentCorrelation must not be null when joinPath is non-empty");
        }
        if (pc.firstStep() != joinPath.get(0)) {
            throw new IllegalArgumentException(
                variantName + ".parentCorrelation.firstStep() must be the same object as "
                + "joinPath.get(0); BuildContext.buildParentCorrelation produces both from the "
                + "same JoinStep list.");
        }
    }

    /**
     * First hop is an FK or lifter; emitter-side correlation is the existing slot-based
     * predicate ({@code firstAlias.<slot.targetSide()> = parent.<slot.sourceSide()>} per slot).
     */
    record OnFkSlots(JoinStep.WithTarget firstHop) implements ParentCorrelation {
        public OnFkSlots {
            if (firstHop == null) {
                throw new NullPointerException("ParentCorrelation.OnFkSlots.firstHop must not be null");
            }
        }
    }

    /**
     * First hop is a condition method; emitter-side correlation is the condition method call
     * itself ({@code .join(firstAlias).on(method(parentAlias, firstAlias))}).
     *
     * <p>{@code parentTable} is the carrier field's parent type's {@code @table} binding —
     * pre-resolved at parse time so split-rows emitters can declare
     * {@code <ParentTable> parentAlias = Tables.<PARENT>.as(...)} without re-deriving the parent
     * table at emit time. Inline emitters use the SDL-context parent table directly and read
     * this field only as a cross-check (the classifier-time invariant pins identity).
     *
     * <p>{@code parentPkCols} is the parent's PK column list, populated for split-rows emission
     * paths (where {@code parentInput} is the VALUES-derived table the emitter joins on parent
     * PK columns). Empty for inline emission paths where {@code parentInput} is not
     * materialised.
     */
    record OnConditionJoin(
            JoinStep.ConditionJoin firstHop,
            TableRef parentTable,
            List<ColumnRef> parentPkCols
    ) implements ParentCorrelation {
        public OnConditionJoin {
            if (firstHop == null) {
                throw new NullPointerException("ParentCorrelation.OnConditionJoin.firstHop must not be null");
            }
            if (parentTable == null) {
                throw new NullPointerException(
                    "ParentCorrelation.OnConditionJoin.parentTable must not be null; the parser "
                    + "routes the no-parent-table case through Rejection.AuthorError upstream.");
            }
            parentPkCols = List.copyOf(parentPkCols);
        }
    }
}
