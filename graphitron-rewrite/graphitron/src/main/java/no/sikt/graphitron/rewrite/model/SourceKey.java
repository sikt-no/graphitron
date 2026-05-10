package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;

import java.util.List;
import java.util.Objects;

/**
 * Singular per-field metadata for a DataLoader-backed (or otherwise source-bearing) child
 * field. Replaces the eleven-permit {@link BatchKey} hierarchy with a flat record whose
 * components encode orthogonal axes the dispatch sites previously recovered through
 * {@code instanceof} checks.
 *
 * <p>Pairs with {@link LoaderRegistration} (DataLoader identity + container kind) at the
 * field-classifier site: one {@link SourceKey} per field; the {@link LoaderRegistration} is a
 * separate value because the same {@link SourceKey} shape can be loaded into either a
 * positional or mapped DataLoader container.
 *
 * <h2>Components</h2>
 *
 * <ul>
 *   <li>{@link #target()} — the table the rows-method body reads from, or {@code null} for
 *       source-bearing fields with no table-bound target (notably scalar-returning
 *       {@code @service} fields backed by {@link Reader.ServiceUntypedRecord}). Pre-resolved
 *       so consumers avoid re-deriving from {@link #path()} / {@link #columns()} (the
 *       spec's original derivation requires {@link ColumnRef} to carry table info, which it
 *       does not today; storing the resolved {@link TableRef} keeps the existing model
 *       surface unchanged).</li>
 *   <li>{@link #columns()} — entry-point columns for the rows-method's parent-input VALUES
 *       table. When {@link #path()} is empty: target-side columns (the catalog-FK / accessor
 *       arms). When {@link #path()} is non-empty: first-hop source-side columns (the
 *       {@code @sourceRow + @reference} chain).</li>
 *   <li>{@link #path()} — empty when the source is target-aligned ({@link Reader.ColumnRead},
 *       {@link Reader.AccessorCall} for the leaf-PK shape, {@link Reader.ServiceTableRecord}
 *       returning the target record); non-empty when traversal walks an FK chain to the
 *       target ({@link Reader.SourceRowsCall} on a {@code @reference} path) or a single
 *       {@link JoinStep.LiftedHop} (lifter-leaf and accessor arms). {@link JoinStep} (rather
 *       than the spec's narrower {@code List<JoinStep.FkJoin>}) admits both
 *       {@link JoinStep.FkJoin} and {@link JoinStep.LiftedHop}; the lifter / accessor arms
 *       carry the latter.</li>
 *   <li>{@link #wrap()} — the Java shape of one row of source data:
 *       {@link Wrap#ROW} ({@code RowN<...>}), {@link Wrap#RECORD} ({@code RecordN<...>}),
 *       or {@link Wrap#TABLE_RECORD} (the typed jOOQ {@code TableRecord} subclass).</li>
 *   <li>{@link #cardinality()} — {@link Cardinality#ONE} (one source row per
 *       DataLoader key) or {@link Cardinality#MANY} (a list / accessor walk).</li>
 *   <li>{@link #reader()} — the rows-method body's input contract (where the body reads
 *       its data from): catalog-FK column, typed accessor on a {@code @record} parent,
 *       {@code @sourceRows} static lifter, or {@code @service} return record.</li>
 * </ul>
 *
 * <h2>Compact-constructor invariants</h2>
 *
 * <p>Cross-axis consistency rules. Each rejection here is a classifier-side bug (a producer
 * built a malformed key); the classifier is expected to never produce these shapes, and the
 * rejections double as a tripwire for future producers. Each invariant carries a
 * {@link LoadBearingClassifierCheck} declaration.
 *
 * <ul>
 *   <li>{@link Reader.SourceRowsCall} → {@link Wrap#ROW}. The {@code @sourceRows} lifter
 *       contract pins output to entry-point columns shaped as {@code RowN<...>}.</li>
 *   <li>{@link Reader.AccessorCall} → {@link Wrap#RECORD}. The accessor returns a
 *       {@code TableRecord}; both the single ({@code AccessorKeyedSingle}) and the
 *       loadMany ({@code AccessorKeyedMany}) projections produce {@code RecordN<...>} keys
 *       at emit time.</li>
 *   <li>{@link Reader.ServiceTableRecord} with {@code recordType} equal to
 *       {@code target().recordClass()} → {@link #path()} empty. Walking past target is
 *       structurally redundant when the service produced a target-aligned record.</li>
 * </ul>
 */
@LoadBearingClassifierCheck(
    key = "source-key.source-rows-call-wraps-row",
    description = "SourceKey's compact constructor rejects Reader.SourceRowsCall paired with "
        + "anything other than Wrap.ROW. The lifter contract is "
        + "(ParentBackingClass) -> RowN<...>; emitting against any other wrap would produce "
        + "a row-vs-record-call mismatch the rows-method body cannot reconcile.")
@LoadBearingClassifierCheck(
    key = "source-key.accessor-call-wraps-record",
    description = "SourceKey's compact constructor rejects Reader.AccessorCall paired with "
        + "anything other than Wrap.RECORD. AccessorKeyedSingle and AccessorKeyedMany both "
        + "emit RecordN<...> keys today; the rows-method body reads value1()..valueN() off "
        + "the key, which only Wrap.RECORD supplies.")
@LoadBearingClassifierCheck(
    key = "source-key.service-table-record-target-aligned-empty-path",
    description = "SourceKey's compact constructor rejects Reader.ServiceTableRecord whose "
        + "recordType matches target's recordClass paired with a non-empty path. The service "
        + "already produced a target-aligned record; walking past target is structurally "
        + "redundant.")
public record SourceKey(
    TableRef target,
    List<ColumnRef> columns,
    List<JoinStep> path,
    Wrap wrap,
    Cardinality cardinality,
    Reader reader
) {

    /** The Java shape of one row of source data. */
    public enum Wrap {
        /** {@code RowN<...>} — values only, no value-N accessors. */
        ROW,
        /** {@code RecordN<...>} — values + value1()..valueN() accessors. */
        RECORD,
        /** A typed jOOQ {@code TableRecord} subclass (e.g. {@code FilmRecord}). */
        TABLE_RECORD
    }

    /** Source-side cardinality per DataLoader key. */
    public enum Cardinality {
        /** One source row per key (catalog-FK, accessor-single, service-target-aligned). */
        ONE,
        /** Many source rows per key (list-valued source, accessor-many, list-valued service). */
        MANY
    }

    public SourceKey {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(wrap, "wrap");
        Objects.requireNonNull(cardinality, "cardinality");
        columns = List.copyOf(columns);
        path = List.copyOf(path);

        if (reader instanceof Reader.SourceRowsCall && wrap != Wrap.ROW) {
            throw new IllegalArgumentException(
                "SourceKey: Reader.SourceRowsCall requires Wrap.ROW (lifter contract pins "
                + "output to RowN<...>); got Wrap." + wrap);
        }
        if (reader instanceof Reader.AccessorCall && wrap != Wrap.RECORD) {
            throw new IllegalArgumentException(
                "SourceKey: Reader.AccessorCall requires Wrap.RECORD (accessor returns "
                + "TableRecord; rows-method body reads valueN() off the key); got Wrap." + wrap);
        }
        if (reader instanceof Reader.ServiceTableRecord stra
                && target != null
                && Objects.equals(stra.recordType(), target.recordClass())
                && !path.isEmpty()) {
            throw new IllegalArgumentException(
                "SourceKey: Reader.ServiceTableRecord with recordType matching target's "
                + "recordClass cannot carry a non-empty path (the service already produced a "
                + "target-aligned record; walking past target is structurally redundant).");
        }
    }

    /**
     * The rows-method body's input contract. Five permits in R38 (R75 will add a sixth,
     * {@code ResultRowWalk}, on this same axis as a one-permit addition rather than as a
     * twelfth {@link BatchKey} permit).
     *
     * <p>The five permits split on what data the rows-method body reads to produce its
     * output:
     *
     * <ul>
     *   <li>{@link ColumnRead} — read FK columns from the parent record (catalog-FK arms,
     *       today's {@link BatchKey.RowKeyed} on a non-{@code @record} or {@code @record}
     *       parent whose backing class is a jOOQ {@code TableRecord}).</li>
     *   <li>{@link AccessorCall} — call a typed zero-arg instance accessor on the parent's
     *       backing class (today's {@link BatchKey.AccessorKeyedSingle} /
     *       {@link BatchKey.AccessorKeyedMany} arms).</li>
     *   <li>{@link SourceRowsCall} — call a {@code @sourceRows} static lifter on a utility
     *       class to produce a {@code RowN<...>} (today's {@link BatchKey.LifterLeafKeyed}
     *       / {@link BatchKey.LifterPathKeyed} arms).</li>
     *   <li>{@link ServiceTableRecord} — invoke a {@code @service} method whose return
     *       type is a typed jOOQ {@code TableRecord} (today's {@code ServiceTableField}
     *       projection).</li>
     *   <li>{@link ServiceUntypedRecord} — invoke a {@code @service} method whose return
     *       type is {@code Record<>} or scalar (today's {@code ServiceRecordField}
     *       projection).</li>
     * </ul>
     */
    public sealed interface Reader {

        /**
         * Catalog-FK column read off the parent record. The body emits
         * {@code parent.get(fkColumn)} (or the equivalent indexed access against a
         * {@code TableRecord}) and packages the result as a {@code RowN<...>} key.
         */
        record ColumnRead() implements Reader {}

        /**
         * Typed zero-arg instance accessor on a {@code @record} parent's backing class
         * whose return type is a concrete jOOQ {@code TableRecord} (single, list, or set
         * cardinality recorded by {@link SourceKey#cardinality()} +
         * {@link LoaderRegistration#container()}, not by the {@link Reader} permit).
         */
        record AccessorCall(AccessorRef accessor) implements Reader {
            public AccessorCall {
                Objects.requireNonNull(accessor, "accessor");
            }
        }

        /**
         * {@code @sourceRows} static lifter producing a {@code RowN<...>} batch key from a
         * {@code @record} parent's backing class.
         */
        record SourceRowsCall(LifterRef lifter) implements Reader {
            public SourceRowsCall {
                Objects.requireNonNull(lifter, "lifter");
            }
        }

        /**
         * {@code @service} method whose return type is a typed jOOQ {@code TableRecord}
         * subclass. {@code recordType} is the resolved {@link ClassName} of that subclass
         * (e.g. {@code FilmRecord}); the rows-method body's call shape uses it to type the
         * loader's per-key value and the rows-method's outer return.
         */
        record ServiceTableRecord(ClassName recordType) implements Reader {
            public ServiceTableRecord {
                Objects.requireNonNull(recordType, "recordType");
            }
        }

        /**
         * {@code @service} method whose return type is {@code Record<>} or scalar — no
         * typed {@code TableRecord} subclass. The rows-method body falls back to the
         * scalar / untyped record code path; the loader's per-key value is the field's
         * scalar Java type or raw {@code org.jooq.Record}.
         */
        record ServiceUntypedRecord() implements Reader {}
    }
}
