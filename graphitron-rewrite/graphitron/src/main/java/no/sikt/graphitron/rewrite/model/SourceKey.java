package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;

import java.util.List;
import java.util.Objects;

/**
 * Singular per-field metadata for a DataLoader-backed (or otherwise source-bearing) child
 * field. A flat record whose components encode orthogonal axes the dispatch sites resolve
 * by reading components rather than {@code instanceof} branches.
 *
 * <p>Pairs with {@link LoaderRegistration} (DataLoader container kind + dispatch shape) at
 * the field-classifier site: one {@link SourceKey} per field; the {@link LoaderRegistration}
 * is a separate value because the same {@link SourceKey} shape can be loaded into either a
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
 *       {@link Wrap.Row} ({@code RowN<...>}), {@link Wrap.Record} ({@code RecordN<...>}),
 *       or {@link Wrap.TableRecord} (the typed jOOQ {@code TableRecord} subclass, with the
 *       {@link ClassName} payload).</li>
 *   <li>{@link #cardinality()} — {@link Cardinality#ONE} (one source row per
 *       DataLoader key) or {@link Cardinality#MANY} (a list / accessor walk).</li>
 *   <li>{@link #reader()} — the rows-method body's input contract (where the body reads
 *       its data from): catalog-FK column, typed accessor on a class-backed parent,
 *       {@code @sourceRows} static lifter, or {@code @service} return record.</li>
 * </ul>
 *
 * <h2>Compact-constructor invariants</h2>
 *
 * <p>Cross-axis consistency rules. Each rejection here is a classifier-side bug (a producer
 * built a malformed key); the classifier is expected to never produce these shapes, and the
 * rejections double as a tripwire for future producers.
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
 *   <li>{@link Reader.ResultRowWalk} → {@link Wrap.Record} or
 *       {@link Wrap.TableRecord} whose {@code className} equals {@code target.recordClass()},
 *       and {@link #path()} empty. The upstream producer (DML mutation fetcher or
 *       carrier-shaped {@code @service} method) emits target-aligned rows; cardinality
 *       determines whether the consumer sees a single row ({@link Cardinality#ONE}) or a
 *       list / {@code Result} ({@link Cardinality#MANY}).</li>
 * </ul>
 */
public record SourceKey(
    TableRef target,
    List<ColumnRef> columns,
    List<JoinStep> path,
    Wrap wrap,
    Cardinality cardinality,
    Reader reader
) {

    /**
     * The Java shape of one row of source data. Sealed so the {@link TableRecord} arm can
     * carry the developer-declared {@code TableRecord} subclass payload that the column-tuple
     * arms have no use for; {@link #keyElementType()} is total without an extra nullable
     * field on {@link SourceKey}.
     */
    public sealed interface Wrap {
        /** {@code RowN<...>} — values only, no value-N accessors. */
        record Row() implements Wrap {}
        /** {@code RecordN<...>} — values + value1()..valueN() accessors. */
        record Record() implements Wrap {}
        /**
         * A typed jOOQ {@code TableRecord} subclass (e.g. {@code FilmRecord}); {@code className}
         * is the developer-declared subtype that propagates to the rows-method's parameter
         * shape and the loader-key element type.
         */
        record TableRecord(ClassName className) implements Wrap {
            public TableRecord {
                Objects.requireNonNull(className, "className");
            }
        }
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

        if (reader instanceof Reader.SourceRowsCall && !(wrap instanceof Wrap.Row)) {
            throw new IllegalArgumentException(
                "SourceKey: Reader.SourceRowsCall requires Wrap.Row (lifter contract pins "
                + "output to RowN<...>); got " + wrap);
        }
        if (reader instanceof Reader.AccessorCall && !(wrap instanceof Wrap.Record)) {
            throw new IllegalArgumentException(
                "SourceKey: Reader.AccessorCall requires Wrap.Record (accessor returns "
                + "TableRecord; rows-method body reads valueN() off the key); got " + wrap);
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
        if (reader instanceof Reader.ResultRowWalk rrw) {
            boolean wrapOk = wrap instanceof Wrap.Record
                || (wrap instanceof Wrap.TableRecord tr
                    && target != null
                    && Objects.equals(tr.className(), target.recordClass()));
            if (!wrapOk) {
                throw new IllegalArgumentException(
                    "SourceKey: Reader.ResultRowWalk requires Wrap.Record or "
                    + "Wrap.TableRecord(target.recordClass()) (upstream producer emits "
                    + "target-aligned rows; the data-field fetcher's typed source read relies "
                    + "on wrap to type the row shape); got " + wrap);
            }
            if (!path.isEmpty()) {
                throw new IllegalArgumentException(
                    "SourceKey: Reader.ResultRowWalk requires empty path (target-aligned by "
                    + "construction; the producer's row shape IS the data table).");
            }
            // R275: the Outcome envelope only ever pairs with the @service carrier (Wrap.TableRecord);
            // the DML carrier (Wrap.Record) delivers its row(s) bare on env.getSource(). Pinning the
            // coupling here lets the data-field emitter handle the narrowing in the TableRecord arm
            // only, with no envelope branch in the Record (DML) arm.
            if (rrw.envelope() == Reader.SourceEnvelope.OUTCOME_SUCCESS
                    && !(wrap instanceof Wrap.TableRecord)) {
                throw new IllegalArgumentException(
                    "SourceKey: Reader.ResultRowWalk(OUTCOME_SUCCESS) requires Wrap.TableRecord "
                    + "(the Outcome envelope is the @service error-channel carrier, whose producer "
                    + "returns a typed TableRecord); got " + wrap);
            }
        }
    }

    /**
     * The DataLoader key element type — {@code RowN<...>}, {@code RecordN<...>}, or the
     * developer-declared {@code TableRecord} subclass — derived from {@link #wrap()} and
     * {@link #columns()}.
     *
     * <p>For {@link Wrap.Row}: {@code Row<n>} parameterised by each column's
     * {@link ColumnRef#columnClass()}.
     * For {@link Wrap.Record}: {@code Record<n>} parameterised by each column's
     * {@code columnClass}. For {@link Wrap.TableRecord}: the captured
     * {@link Wrap.TableRecord#className()}.
     */
    public TypeName keyElementType() {
        return keyElementType(wrap, columns);
    }

    /**
     * Static derivation of the DataLoader key element type from the {@code (wrap, columns)}
     * pair alone. Used by {@link MethodRef.Param.Sourced} and {@link ParamSource.Sources}
     * consumers that hold the triple {@code (wrap, columns, container)} directly without a
     * full {@link SourceKey} (only the source-shape side of the data is available, not the
     * field-side {@code target} / {@code path} / {@code cardinality} / {@code reader}).
     */
    public static TypeName keyElementType(Wrap wrap, List<ColumnRef> columns) {
        return switch (wrap) {
            case Wrap.Row r            -> jooqShape("Row", columns);
            case Wrap.Record r         -> jooqShape("Record", columns);
            case Wrap.TableRecord tr   -> tr.className();
        };
    }

    private static TypeName jooqShape(String shape, List<ColumnRef> cols) {
        ClassName container = ClassName.get("org.jooq", shape + cols.size());
        TypeName[] args = cols.stream()
            .map(c -> (TypeName) ClassName.bestGuess(c.columnClass()))
            .toArray(TypeName[]::new);
        return ParameterizedTypeName.get(container, args);
    }

    /**
     * The rows-method body's input contract. Six permits today.
     *
     * <p>The permits split on what data the rows-method body reads to produce its
     * output:
     *
     * <ul>
     *   <li>{@link ColumnRead} — read FK columns from the parent record (catalog-FK arms on
     *       table-backed parents, or class-backed parents whose backing class is a
     *       jOOQ {@code TableRecord}).</li>
     *   <li>{@link AccessorCall} — call a typed zero-arg instance accessor on the parent's
     *       backing class (single or list/set cardinality recorded by
     *       {@link SourceKey#cardinality()}).</li>
     *   <li>{@link SourceRowsCall} — call a {@code @sourceRows} static lifter on a utility
     *       class to produce a {@code RowN<...>} (single-hop and FK-chain shapes both).</li>
     *   <li>{@link ServiceTableRecord} — invoke a {@code @service} method whose return
     *       type is a typed jOOQ {@code TableRecord}.</li>
     *   <li>{@link ServiceUntypedRecord} — invoke a {@code @service} method whose return
     *       type is {@code Record<>} or scalar; no typed {@code TableRecord} subclass.</li>
     *   <li>{@link ResultRowWalk} — source rows come from a {@code Result<RecordN<...>>}
     *       that an upstream DML mutation fetcher produced and graphql-java is now
     *       traversing. The data field's fetcher reads {@code env.getSource()} typed by
     *       {@link SourceKey#wrap()} × {@link SourceKey#columns()} and extracts
     *       {@code SourceRow} instances directly; no DataLoader, no
     *       {@link LoaderRegistration}.</li>
     * </ul>
     */
    public sealed interface Reader {

        /**
         * How a {@link ResultRowWalk} reaches its source row(s) inside {@code env.getSource()}.
         * The row shape ({@link Wrap}) is identical on both arms; this axis records only the
         * outer envelope the upstream producer wrapped the row(s) in, so the data-field fetcher
         * knows whether to read {@code env.getSource()} directly or unwrap an {@code Outcome} first.
         */
        enum SourceEnvelope {
            /** {@code env.getSource()} is the row(s) directly (DML mutation carrier). */
            DIRECT,
            /**
             * {@code env.getSource()} is the non-null {@code Outcome} of an error-channel
             * {@code @service} carrier (R244/R275): the row(s) live in
             * {@code Outcome.Success.value()}, and the {@code Outcome.ErrorList} arm resolves the
             * data field to {@code null}. Set at classification time when the carrier payload
             * carries an {@code errors} field, the same condition that gives the producer its
             * {@code ErrorChannel.Mapped} channel.
             */
            OUTCOME_SUCCESS
        }

        /**
         * Catalog-FK column read off the parent record. The body emits
         * {@code parent.get(fkColumn)} (or the equivalent indexed access against a
         * {@code TableRecord}) and packages the result as a {@code RowN<...>} key.
         */
        record ColumnRead() implements Reader {}

        /**
         * Typed zero-arg instance accessor on a class-backed parent's backing class
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
         * class-backed parent's backing class.
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

        /**
         * Source rows come from an upstream producer's row(s) that graphql-java is now
         * traversing. The data field's fetcher reads them off {@code env.getSource()} typed by
         * {@link SourceKey#wrap()} × {@link SourceKey#columns()} and extracts {@code SourceRow}
         * instances directly; the single-record carrier's data field is plain {@code DataFetcher},
         * not DataLoader-batched, so the paired {@link LoaderRegistration} is absent at the field
         * record level.
         *
         * <p>{@link #envelope()} records whether {@code env.getSource()} is the row(s) directly
         * ({@link SourceEnvelope#DIRECT}, DML mutation carrier) or the non-null {@code Outcome} of
         * an error-channel {@code @service} carrier ({@link SourceEnvelope#OUTCOME_SUCCESS}, whose
         * row(s) live in {@code Outcome.Success.value()}). This is the only difference between the
         * DML and {@code @service} error-channel carriers at the read site, so it rides on the
         * reader rather than being re-derived from sibling fields at emit time.
         *
         * <p>{@link SourceKey#path()} is empty by construction (target-aligned: the
         * producer's row shape IS the data table); {@link SourceKey#wrap()} is either
         * {@link Wrap.Record} (DML mutation fetcher producer emits {@code RecordN<...>})
         * or {@link Wrap.TableRecord} whose {@code className} equals
         * {@code target.recordClass()} ({@code @service} payload producer returns
         * a typed {@code XRecord} or {@code List<XRecord>} verbatim). Cardinality matches
         * the producer's output cardinality (single → {@link Cardinality#ONE}; list →
         * {@link Cardinality#MANY}). The structural invariants are enforced by
         * {@link SourceKey}'s compact constructor.
         */
        record ResultRowWalk(SourceEnvelope envelope) implements Reader {
            public ResultRowWalk {
                Objects.requireNonNull(envelope, "envelope");
            }
        }
    }
}
