package no.sikt.graphitron.rewrite.model;

import java.util.List;
import java.util.Objects;

/**
 * Pre-resolved shape of the step-0 parent correlation for source-correlated child fields.
 * Lifts the fork between slot-pair correlation, condition-method correlation, lateral-argument
 * correlation, and the pre-keyed lifted correlation out of the emitters into the model — the
 * emitter sites (inline {@code TableField} / {@code LookupTableField} / {@code ColumnReferenceField}
 * step-0; split-rows {@code buildListMethod} / {@code buildSingleMethod} /
 * {@code buildConnectionMethod}) all read variant identity from a sealed switch on this carrier
 * rather than inspecting {@code joinPath.get(0)} themselves.
 *
 * <p>The two axes are decoupled by design: {@link JoinStep.HasTargetTable} handles "what is this
 * hop's target table" (uniform across permits); {@link ParentCorrelation} handles "what shape does
 * parent correlation take at this path" (forks on the first hop). Any
 * {@link no.sikt.graphitron.rewrite.model.ChildField} variant can carry
 * {@link OnParentJoin} regardless of which intermediate hops appear in the joinPath.
 *
 * <p><b>Grain and topology are one decision (R450).</b> A hop-0 {@code filter()} reads the parent
 * row, so it makes the parent's identity part of the fetch's inputs: the batch must be keyed on
 * the parent PK, and the query must anchor the parent table so the filter's source parameter has
 * an alias to bind. Both follow from landing the {@link OnParentJoin} arm, so the classifier lands
 * <em>any</em> hop-0 {@code filter()} on {@link OnParentJoin} regardless of the hop's {@link On}
 * (a filter-less FK hop keeps {@link OnFkSlots}). {@link #parentKeyColumns()} projects the grain
 * off the arm so it cannot disagree with the topology the same arm dictates.
 *
 * <p>Classifier-time invariant ({@link #checkCarrierInvariant}): a hop-anchored correlation's
 * first hop is the same instance as {@code joinPath.get(0)}, and the hop-less {@link OnLiftedSlots}
 * arm pairs only with an empty {@code joinPath} — the model can never carry a correlation that
 * disagrees with the path it sits on. The hop-anchored arms are denormalised views of data already
 * on {@code joinPath.get(0)} + the carrier field's parent type's {@code @table} binding —
 * pre-resolved once at parse time so consumers don't re-derive at each emit site.
 */
public sealed interface ParentCorrelation
        permits ParentCorrelation.OnFkSlots, ParentCorrelation.OnLiftedSlots,
                ParentCorrelation.OnParentJoin, ParentCorrelation.OnLateralArgs {

    /**
     * The table that owns the DataLoader key columns ({@code SourceKey.columns()}) this
     * correlation pairs the parent-input {@code VALUES} table against. The rows-method emitters
     * read {@code Tables.<OWNER>.<COL>.getDataType()} off this table so each {@code VALUES} cell
     * binds through the column's registered jOOQ {@code Converter} (R413); which table owns the
     * key columns was decided by the classifier when it chose them, so the fork is folded here
     * rather than re-derived per emit site:
     *
     * <ul>
     *   <li>{@link OnFkSlots} — the hop-0 origin table (the side the key columns are drawn from,
     *       per {@code deriveSplitQuerySource} / {@code deriveFkRecordParentSource}).</li>
     *   <li>{@link OnLiftedSlots} — the target table (the key tuple IS the target-column tuple
     *       by construction; R431, formerly the {@code LiftedHop} arm).</li>
     *   <li>{@link OnParentJoin} — the parent table (keys are the parent's own PK).</li>
     * </ul>
     */
    default TableRef parentKeyOwnerTable() {
        return switch (this) {
            case OnFkSlots fk -> fk.firstHop().originTable();
            case OnLiftedSlots lifted -> lifted.targetTable();
            case OnParentJoin pj -> pj.parentTable();
            // The lateral call's column bindings are drawn from the parent table (the chain's
            // implicit head), which is the hop-0 origin.
            case OnLateralArgs la -> la.firstHop().originTable();
        };
    }

    /**
     * The parent-side columns the {@code @splitQuery} batch keys on: the {@code VALUES}
     * {@code parentInput} table carries one cell per column, and the DataLoader key tuple IS this
     * column tuple. This is the batch <em>grain</em>, and (R450) it is a pure projection off the
     * arm so grain and correlation topology cannot drift apart:
     *
     * <ul>
     *   <li>{@link OnFkSlots} — the first hop's source-side columns (the FK-holder side). Two
     *       parents sharing these values share one batch entry, which is correct precisely
     *       because the correlation reads nothing off the parent beyond them.</li>
     *   <li>{@link OnLiftedSlots} — the target-column tuple itself (source and target sides are
     *       the same columns; PK self-identity is the degenerate case of the FK pairing).</li>
     *   <li>{@link OnParentJoin} — the parent's own primary-key columns. A hop-0 filter (or a
     *       condition-join first hop) reads arbitrary parent columns, so the parent's identity is
     *       part of the fetch's inputs and the grain must be the parent PK; keying on anything
     *       coarser would hand two distinct parents one shared verdict.</li>
     *   <li>{@link OnLateralArgs} — the routine call's column-bound inputs (the
     *       {@link ParamSource.SourceColumn} argument bindings). A routine result is a pure
     *       function of its inputs, so the input tuple is the natural key; may be empty for an
     *       uncorrelated routine, which the classifier rejects upstream as a directive conflict.</li>
     * </ul>
     *
     * <p>{@code deriveSplitQuerySource} reads entry columns off this accessor rather than
     * re-deriving them from the path, so "parent-PK grain iff parent-anchor topology" is
     * structurally enforced by the single arm choice.
     */
    default List<ColumnRef> parentKeyColumns() {
        return switch (this) {
            case OnFkSlots fk -> fk.slots().sourceSideColumns();
            case OnLiftedSlots lifted -> lifted.columns();
            case OnParentJoin pj -> pj.parentTable().primaryKeyColumns();
            case OnLateralArgs la -> ((TableExpr.RoutineCall) la.firstHop().target())
                .routine().argBindings().stream()
                .filter(b -> b.source() instanceof ParamSource.SourceColumn)
                .map(b -> ((ParamSource.SourceColumn) b.source()).column())
                .distinct()
                .toList();
        };
    }

    /**
     * Carrier-side classifier invariant. A non-empty {@code joinPath} carries a non-null,
     * hop-anchored {@link ParentCorrelation} whose first hop is the same instance as
     * {@code joinPath.get(0)}. An empty joinPath carries either a null correlation (the lookup
     * runs standalone and no parent correlation is needed) or the hop-less {@link OnLiftedSlots}
     * arm (the pre-keyed lifter / accessor / re-fetch shape, R431 — formerly a single
     * {@code JoinStep.LiftedHop} smuggled into the path). Each ChildField variant compact
     * constructor invokes this helper so the model can never carry a correlation that disagrees
     * with the path it sits on.
     */
    static void checkCarrierInvariant(ParentCorrelation pc, List<JoinStep> joinPath, String variantName) {
        if (joinPath.isEmpty()) {
            if (pc != null && !(pc instanceof OnLiftedSlots)) {
                throw new IllegalArgumentException(
                    variantName + ".parentCorrelation must be null (standalone-lookup shape) or "
                    + "OnLiftedSlots (pre-keyed lifted shape) when joinPath is empty; got "
                    + pc.getClass().getSimpleName());
            }
            return;
        }
        if (pc == null) {
            throw new NullPointerException(
                variantName + ".parentCorrelation must not be null when joinPath is non-empty");
        }
        JoinStep firstHop = switch (pc) {
            case OnFkSlots fk -> fk.firstHop();
            case OnParentJoin pj -> pj.firstHop();
            case OnLateralArgs la -> la.firstHop();
            case OnLiftedSlots ignored -> throw new IllegalArgumentException(
                variantName + ".parentCorrelation is OnLiftedSlots but joinPath is non-empty; "
                + "the lifted arm is hop-less by construction (its key tuple IS the target-column "
                + "tuple, no traversal).");
        };
        if (firstHop != joinPath.get(0)) {
            throw new IllegalArgumentException(
                variantName + ".parentCorrelation's first hop must be the same object as "
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
     * {@link On.ColumnPairs} — the slot-carrying shape. The compact constructor rejects
     * everything else, so {@link #slots()} never throws.
     */
    record OnFkSlots(JoinStep.Hop firstHop) implements ParentCorrelation {
        public OnFkSlots {
            if (firstHop == null) {
                throw new NullPointerException("ParentCorrelation.OnFkSlots.firstHop must not be null");
            }
            if (!(firstHop.on() instanceof On.ColumnPairs)) {
                throw new IllegalArgumentException(
                    "ParentCorrelation.OnFkSlots.firstHop must carry pairable slots (a Hop with "
                    + "On.ColumnPairs); got " + firstHop);
            }
        }

        /** The slot pairs the correlation predicate is built from. */
        public On.ColumnPairs slots() {
            return (On.ColumnPairs) firstHop.on();
        }
    }

    /**
     * The pre-keyed correlation (R431, formerly {@code OnFkSlots} wrapping a
     * {@code JoinStep.LiftedHop}): the DataLoader key tuple <em>is</em> the target-column tuple —
     * no foreign key, no traversal, no source side distinct from the target side. Carried by the
     * {@code @sourceRows} lifter-leaf, the class-backed-parent accessor arm, and the
     * source=target carrier re-fetch (R305), whose correlation is PK self-identity — the
     * degenerate case of the FK pairing (R333). The rows-method JOIN predicate is
     * {@code target.<col> = parentInput.field(col)} per column, identical in shape to the FK
     * case with both sides the same column.
     *
     * <p>Hop-less by construction: the carrier's {@code joinPath} is empty
     * ({@link #checkCarrierInvariant}), and {@code @reference}-parsed paths are the only
     * {@link JoinStep} population. The emitters synthesize the single target alias from
     * {@code targetTable} exactly as the retired single-hop path did.
     */
    record OnLiftedSlots(
            TableRef targetTable,
            List<ColumnRef> columns
    ) implements ParentCorrelation {
        public OnLiftedSlots {
            Objects.requireNonNull(targetTable, "targetTable");
            if (columns.isEmpty()) {
                throw new IllegalArgumentException(
                    "ParentCorrelation.OnLiftedSlots requires a non-empty column tuple — every "
                    + "arm delegating its key columns through this correlation (SourceRowsCall, "
                    + "AccessorCall, ProducedRecordRead) needs at least one column.");
            }
            columns = List.copyOf(columns);
        }
    }

    /**
     * The parent-anchor arm (R450, the generalization of the former {@code OnConditionJoin}): the
     * batch anchors the parent table and hop 0 attaches off it. In the split-rows shape,
     * {@code parentInput} joins the parent table on its PK, then hop 0 attaches to {@code firstHop}
     * off {@code parentAlias}; the parent PK grain (see {@link #parentKeyColumns()}) is what makes
     * that anchoring correct. This arm carries only the topology payload ({@code firstHop},
     * {@code parentTable}) and deliberately exposes <em>no</em> {@code condition()} accessor: a
     * partial accessor whose meaning depends on the hop's occupant is the axis smell this refactor
     * removes. Consumers dispatch the hop-0 attach on {@code firstHop().on()} per {@link JoinStep}'s
     * own two-axis model:
     *
     * <ul>
     *   <li>{@link On.ColumnPairs} — the ordinary forward join ({@code .onKey(FK)} / the
     *       name-matched conjunction), with any hop-0 {@code filter()} binding {@code parentAlias}
     *       as its source. This is the R450 case: a filter-carrying FK hop that previously landed
     *       {@link OnFkSlots} and bound the hop-0 target alias as both filter parameters.</li>
     *   <li>{@link On.Predicate} — the two-arg condition method call
     *       ({@code method(parentAlias, firstAlias)}), the former {@code OnConditionJoin}
     *       behaviour.</li>
     * </ul>
     *
     * <p>{@code firstHop} is a {@link JoinStep.Hop}; the compact constructor rejects a lateral hop
     * (a routine node correlates through its call arguments, which is {@link OnLateralArgs}, never a
     * parent anchor).
     *
     * <p>{@code parentTable} is the carrier field's parent type's {@code @table} binding —
     * pre-resolved at parse time so split-rows emitters can declare
     * {@code <ParentTable> parentAlias = Tables.<PARENT>.as(...)} and read the parent PK grain off
     * it without re-deriving the parent table at emit time. Inline emitters use the SDL-context
     * parent table directly and read this field only as a cross-check (the classifier-time
     * invariant pins identity). The parser routes the no-parent-{@code @table} case (a filter or
     * condition-join first hop whose source row is not a catalog table) through
     * {@code Rejection.AuthorError} upstream, so this is never null.
     */
    record OnParentJoin(
            JoinStep.Hop firstHop,
            TableRef parentTable
    ) implements ParentCorrelation {
        public OnParentJoin {
            if (firstHop == null) {
                throw new NullPointerException("ParentCorrelation.OnParentJoin.firstHop must not be null");
            }
            if (firstHop.on() instanceof On.Lateral) {
                throw new IllegalArgumentException(
                    "ParentCorrelation.OnParentJoin.firstHop must not join laterally; a routine "
                    + "node correlates through its call arguments (OnLateralArgs), not a parent "
                    + "anchor. got " + firstHop.on());
            }
            if (parentTable == null) {
                throw new NullPointerException(
                    "ParentCorrelation.OnParentJoin.parentTable must not be null; the parser "
                    + "routes the no-parent-table case through Rejection.AuthorError upstream.");
            }
        }
    }

    /**
     * First hop is a lateral routine node (R435): correlation rides the routine call's
     * arguments ({@link ParamSource.SourceColumn} bindings on the hop's
     * {@link TableExpr.RoutineCall} target), so the step-0 WHERE contributes nothing
     * ({@code noCondition()}) and the emitters render the correlated columns inside the call
     * expression itself. This arm is the one-to-one mirror of {@link On.Lateral}; the other two
     * On occupants ({@link On.ColumnPairs}, {@link On.Predicate}) are split across {@link OnFkSlots}
     * and {@link OnParentJoin} by whether hop 0 carries a parent-reading {@code filter()} (R450).
     *
     * <p>No payload beyond {@code firstHop}: the correlated columns live on the target's
     * {@link RoutineRef.ArgBinding}s, keeping this carrier a pure step-0 dispatch fact.
     */
    record OnLateralArgs(JoinStep.Hop firstHop) implements ParentCorrelation {
        public OnLateralArgs {
            if (firstHop == null) {
                throw new NullPointerException("ParentCorrelation.OnLateralArgs.firstHop must not be null");
            }
            if (!(firstHop.on() instanceof On.Lateral)) {
                throw new IllegalArgumentException(
                    "ParentCorrelation.OnLateralArgs.firstHop must join laterally (On.Lateral); got "
                    + firstHop.on());
            }
        }
    }
}
