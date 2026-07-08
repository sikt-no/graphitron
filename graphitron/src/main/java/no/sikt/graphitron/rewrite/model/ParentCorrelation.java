package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * Pre-resolved shape of the step-0 parent correlation for {@code @reference}-carrying child
 * fields. Lifts the binary fork between slot-pair correlation and condition-method correlation
 * out of the emitters into the model — the five emitter sites (inline {@code TableField} /
 * {@code LookupTableField} / {@code ColumnReferenceField} step-0; split-rows
 * {@code buildListMethod} / {@code buildSingleMethod} / {@code buildConnectionMethod}) all read
 * variant identity from a sealed switch on this carrier rather than inspecting
 * {@code joinPath.get(0)} themselves.
 *
 * <p>The two axes are decoupled by design: {@link JoinStep.HasTargetTable} handles "what is this
 * hop's target table" (uniform across permits); {@link ParentCorrelation} handles "what shape does
 * parent correlation take at this path" (forks on the first hop's {@link On}). Any
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
     * Identity of the first hop this correlation pairs against, declared on the
     * {@link JoinStep} root so the carrier-side invariant
     * {@code parentCorrelation.firstHop() == joinPath.get(0)} can be expressed uniformly.
     * Both permits satisfy it through their {@code firstHop} record component
     * ({@link OnConditionJoin}'s covariantly, as {@link JoinStep.Hop}).
     */
    JoinStep firstHop();

    /**
     * The table that owns the DataLoader key columns ({@code SourceKey.columns()}) this
     * correlation pairs the parent-input {@code VALUES} table against. The rows-method emitters
     * read {@code Tables.<OWNER>.<COL>.getDataType()} off this table so each {@code VALUES} cell
     * binds through the column's registered jOOQ {@code Converter} (R413); which table owns the
     * key columns was decided by the classifier when it chose them, so the fork is folded here
     * rather than re-derived per emit site:
     *
     * <ul>
     *   <li>{@link OnFkSlots} with a {@link JoinStep.Hop} first hop — the hop-0 origin table
     *       (the side the key columns are drawn from, per {@code deriveSplitQuerySource} /
     *       {@code deriveFkRecordParentSource} / {@code SourceRowDirectiveResolver}).</li>
     *   <li>{@link OnFkSlots} with a {@link JoinStep.LiftedHop} first hop — the hop's target
     *       table (the key tuple IS the target-column tuple by {@code LiftedHop}
     *       construction).</li>
     *   <li>{@link OnConditionJoin} — the parent table (keys are the parent's own PK).</li>
     * </ul>
     */
    default TableRef parentKeyOwnerTable() {
        return switch (this) {
            case OnFkSlots fk -> switch (fk.firstHop()) {
                case JoinStep.Hop hop -> hop.originTable();
                case JoinStep.LiftedHop lifted -> lifted.targetTable();
            };
            case OnConditionJoin cj -> cj.parentTable();
        };
    }

    /**
     * Carrier-side classifier invariant: a non-empty {@code joinPath} carries a non-null
     * {@link ParentCorrelation} whose {@link #firstHop()} is the same instance as
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
        if (pc.firstHop() != joinPath.get(0)) {
            throw new IllegalArgumentException(
                variantName + ".parentCorrelation.firstHop() must be the same object as "
                + "joinPath.get(0); BuildContext.buildParentCorrelation produces both from the "
                + "same JoinStep list.");
        }
    }

    /**
     * First hop carries pairable slots; emitter-side correlation is the slot-based predicate
     * ({@code firstAlias.<slot.targetSide()> = parent.<slot.sourceSide()>} per slot), read
     * through {@link #slots()}.
     *
     * <p>{@code firstHop} is a {@link JoinStep.Hop} whose {@code on} is
     * {@link On.ColumnPairs}, or a {@link JoinStep.LiftedHop} — the two slot-carrying shapes.
     * The compact constructor rejects everything else, so {@link #slots()} never throws.
     */
    record OnFkSlots(JoinStep firstHop) implements ParentCorrelation {
        public OnFkSlots {
            if (firstHop == null) {
                throw new NullPointerException("ParentCorrelation.OnFkSlots.firstHop must not be null");
            }
            boolean pairable = switch (firstHop) {
                case JoinStep.Hop hop -> hop.on() instanceof On.ColumnPairs;
                case JoinStep.LiftedHop ignored -> true;
            };
            if (!pairable) {
                throw new IllegalArgumentException(
                    "ParentCorrelation.OnFkSlots.firstHop must carry pairable slots (a Hop with "
                    + "On.ColumnPairs, or a LiftedHop); got " + firstHop);
            }
        }

        /** The slot pairs the correlation predicate is built from. */
        public HasSlots slots() {
            return switch (firstHop) {
                case JoinStep.Hop hop -> (On.ColumnPairs) hop.on();
                case JoinStep.LiftedHop lifted -> lifted;
            };
        }
    }

    /**
     * First hop joins on a condition method; emitter-side correlation is the condition method
     * call itself ({@code .join(firstAlias).on(method(parentAlias, firstAlias))}), read through
     * {@link #condition()}.
     *
     * <p>{@code firstHop} is a {@link JoinStep.Hop} whose {@code on} is {@link On.Predicate};
     * the compact constructor rejects everything else.
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
            JoinStep.Hop firstHop,
            TableRef parentTable,
            List<ColumnRef> parentPkCols
    ) implements ParentCorrelation {
        public OnConditionJoin {
            if (firstHop == null) {
                throw new NullPointerException("ParentCorrelation.OnConditionJoin.firstHop must not be null");
            }
            if (!(firstHop.on() instanceof On.Predicate)) {
                throw new IllegalArgumentException(
                    "ParentCorrelation.OnConditionJoin.firstHop must join on a condition method "
                    + "(On.Predicate); got " + firstHop.on());
            }
            if (parentTable == null) {
                throw new NullPointerException(
                    "ParentCorrelation.OnConditionJoin.parentTable must not be null; the parser "
                    + "routes the no-parent-table case through Rejection.AuthorError upstream.");
            }
            parentPkCols = List.copyOf(parentPkCols);
        }

        /** The step-0 condition method the correlation predicate calls. */
        public JoinConditionRef condition() {
            return ((On.Predicate) firstHop.on()).condition();
        }
    }
}
