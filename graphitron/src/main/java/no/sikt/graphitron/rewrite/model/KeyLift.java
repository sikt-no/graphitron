package no.sikt.graphitron.rewrite.model;

import java.util.Objects;

/**
 * The field-level key-lift fact for a batched field: how the emitted fetcher lifts the
 * DataLoader batch key off the parent's held object. The mechanism only — with nothing about
 * where the key points (the leaf's {@code returnType.table()} / {@link ParentCorrelation}) or
 * what backing the parent has (the leaf's stored {@link SourceShape}; for class-backed parents,
 * the enclosing {@link GraphitronType.ResultType}). R431: these are the four live arms of the
 * retired seven-arm {@code SourceKey.Reader}, whose service arms duplicated the producer's
 * {@link MethodRef} signature and whose {@code ResultRowWalk} arm dissolved into the
 * {@link ChildField.SingleRecordIdField} leaf plus the first-class {@link SourceEnvelope}.
 * R432 made the axis total on the merged batched leaves: a total {@code lift} removes an
 * absence case and tells no lie — a table-parent {@code @splitQuery} field genuinely lifts by
 * column projection ({@link FkColumns}), the same mechanism a record-backed result parent uses.
 *
 * <p>Carried by {@link ChildField.BatchedTableField} (total since R432; the Table-sourced arm
 * always carries {@link FkColumns}), {@link ChildField.BatchedLookupTableField} (same gate),
 * {@link ChildField.RecordTableMethodField}, and (as {@code parentKeyLift}) the polymorphic
 * {@link ChildField.InterfaceField} / {@link ChildField.UnionField}. Dispatched exhaustively by
 * {@code GeneratorUtils.buildRecordParentKeyExtraction} on the record-sourced paths; the
 * Table-sourced emit path stays wrap-driven ({@code GeneratorUtils.buildKeyExtraction} against
 * the parent's own table row), so the Table arm's stored {@link FkColumns} is consumed only by
 * the constructor's {@link #checkResidueAgreement} today — deliberate provisioning for R314's
 * unified fetcher. {@code @service}-backed fields never carry a lift: their key extraction is
 * authored by the {@code Sources} signature.
 *
 * <p>The emitted key-row shape is a total derivation of the arm ({@link #wrap()}): the two
 * member-read arms pin their shape by contract (the accessor returns a {@code TableRecord}
 * projected to {@code RecordN<...>} keys; the {@code @sourceRows} lifter contract emits a
 * {@code RowN<...>}), and the column-read arms build {@code RowN<...>} tuples. Residue
 * {@link SourceKey}s on lift-carrying leaves are constructed through this derivation, which is
 * what made the old compact-constructor pairings ({@code SourceRowsCall} ⇒ {@code Wrap.Row},
 * {@code AccessorCall} ⇒ {@code Wrap.Record}) unrepresentable rather than runtime-checked; the
 * leaf constructors pin the construction rule via {@link #checkResidueAgreement}.
 */
public sealed interface KeyLift {

    /**
     * The key tuple is projected off the held jOOQ record by column, packaged as a
     * {@code RowN<...>} key via {@code DSL.row(...)}. The mechanism, not the provenance: on a
     * Table-sourced batched leaf the held record is the parent's own table row (the
     * {@code @splitQuery} shape, R432); on a record-backed result parent it is the producer's
     * record (catalog-FK columns, or sql-name / accessor reads per the enclosing
     * {@link GraphitronType.ResultType}). Also the polymorphic Row arm, where the parent IS the
     * source and the key is its PK tuple.
     */
    record FkColumns() implements KeyLift {}

    /**
     * {@code @sourceRow} static lifter producing the {@code RowN<...>} batch key from the
     * parent's backing class — authored provenance for the member read the catalog cannot
     * infer. Leaf-PK and {@code @reference}-composed shapes share this arm; the path identity
     * lives on the leaf's {@code joinPath} / {@link ParentCorrelation.OnLiftedSlots}.
     */
    record Lifter(LifterRef lifter) implements KeyLift {
        public Lifter {
            Objects.requireNonNull(lifter, "lifter");
        }
    }

    /**
     * Typed zero-arg instance accessor on the parent's backing class returning a concrete jOOQ
     * {@code TableRecord} (single) or {@code List}/{@code Set} of them ({@link #arity()} is the
     * accessor's return arity, resolved at the reflection boundary — not the field wrapper's).
     * Each returned record projects to a {@code RecordN<...>} key over the element table's PK
     * columns at emit time.
     */
    record Accessor(AccessorRef accessor, Arity arity) implements KeyLift {
        public Accessor {
            Objects.requireNonNull(accessor, "accessor");
            Objects.requireNonNull(arity, "arity");
        }
    }

    /**
     * R305 — the source <em>is</em> the produced target record(s): a DML or {@code @service}
     * producer handed back the target table's record(s) on {@code env.getSource()} (or
     * {@code Outcome.Success.value()}), and the field reads the identifying PK off them to
     * re-project the {@code @table} (source=target re-fetch). {@link #arity()} is the produced
     * wrapper's arity: {@link Arity#ONE} reads the PK off the single source record;
     * {@link Arity#MANY} iterates the held collection, one {@code RowN} PK key per element (the
     * {@code LOAD_MANY} contract). The source envelope is handled by the generator at the type
     * level ({@code sourceIsOutcome}), not carried here.
     */
    record ProducedRecords(Arity arity) implements KeyLift {
        public ProducedRecords {
            Objects.requireNonNull(arity, "arity");
        }
    }

    /**
     * The emitted DataLoader key-row shape, a total derivation of the arm (see the class
     * javadoc). Lift-carrying leaves construct their residue {@link SourceKey}'s wrap through
     * this method.
     */
    default SourceKey.Wrap wrap() {
        return switch (this) {
            case FkColumns ignored       -> new SourceKey.Wrap.Row();
            case Lifter ignored          -> new SourceKey.Wrap.Row();
            case Accessor ignored        -> new SourceKey.Wrap.Record();
            case ProducedRecords ignored -> new SourceKey.Wrap.Row();
        };
    }

    /**
     * Pins the construction rule on a lift-carrying leaf: the residue key's stored wrap is the
     * lift arm's {@link #wrap()} derivation, so the retired reader-to-wrap pairings cannot
     * silently disagree on a hand-built instance. Called from the compact constructors of the
     * carrying {@link ChildField} variants; a violation is a classifier-side bug.
     */
    static void checkResidueAgreement(KeyLift lift, SourceKey key, String variantName) {
        if (lift == null) return;
        if (!key.wrap().equals(lift.wrap())) {
            throw new IllegalArgumentException(
                variantName + ": residue key wrap " + key.wrap() + " disagrees with the "
                + lift.getClass().getSimpleName() + " lift arm's derived wrap " + lift.wrap()
                + "; lift-carrying leaves construct their key through KeyLift.wrap()");
        }
    }
}
