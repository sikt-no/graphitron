package no.sikt.graphitron.rewrite.model;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The batch-key strategy for a DataLoader SOURCES parameter.
 *
 * <p>Four variants, the cross-product of two independent axes:
 *
 * <ul>
 *   <li>Container: {@code List<...>} (positional batch loader, results align with input order)
 *       vs {@code Set<...>} (mapped batch loader, results returned as a {@code Map}
 *       keyed by the input keys).</li>
 *   <li>Key shape: {@code RowN<...>} (constructed via {@code DSL.row(...)}) vs
 *       {@code RecordN<...>} (constructed via {@code record.into(...)}).</li>
 * </ul>
 *
 * <p>{@link RowKeyed} and {@link RecordKeyed} drive
 * {@code DataLoaderFactory.newDataLoader(BatchLoaderWithContext)};
 * {@link MappedRowKeyed} and {@link MappedRecordKeyed} drive
 * {@code DataLoaderFactory.newMappedDataLoader(MappedBatchLoaderWithContext)}.
 *
 * <p>{@code keyColumns} always comes from the parent type's
 * {@link TableRef#primaryKeyColumns()} — never from reflection. Parent types without a
 * {@link TableRef} cannot produce a {@link BatchKey} and fail classification upstream.
 *
 * <p>Element-shape (whether the user wrote {@code Set<TableRecord>} or
 * {@code Set<RowN<...>>}) is <strong>not</strong> preserved on the variant; both classify
 * as {@code MappedRowKeyed}, mirroring how {@code List<TableRecord>} and
 * {@code List<RowN<...>>} both classify as {@code RowKeyed}. The DataLoader key type is
 * always {@code RowN}/{@code RecordN} for hashing reasons; element-shape recovery (for
 * the future rows-method body) happens at the body emitter via re-reflection of the
 * service method.
 */
public sealed interface BatchKey
        permits BatchKey.RowKeyed, BatchKey.RecordKeyed,
                BatchKey.MappedRowKeyed, BatchKey.MappedRecordKeyed {

    /**
     * PK columns from the parent table. May be empty when the parent type is a root
     * operation type (no backing table).
     */
    List<ColumnRef> keyColumns();

    /**
     * Returns the fully qualified generic Java type name for the SOURCES parameter
     * corresponding to this {@link BatchKey} variant.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code RowKeyed([film_id:Integer])} →
     *       {@code "java.util.List<org.jooq.Row1<java.lang.Integer>>"}</li>
     *   <li>{@code RecordKeyed([film_id:Integer, actor_id:Integer])} →
     *       {@code "java.util.List<org.jooq.Record2<java.lang.Integer, java.lang.Integer>>"}</li>
     *   <li>{@code MappedRowKeyed([film_id:Integer])} →
     *       {@code "java.util.Set<org.jooq.Row1<java.lang.Integer>>"}</li>
     *   <li>{@code MappedRecordKeyed([film_id:Integer])} →
     *       {@code "java.util.Set<org.jooq.Record1<java.lang.Integer>>"}</li>
     * </ul>
     */
    String javaTypeName();

    /**
     * Column-based batch key using {@code DSL.row()} key construction with a positional
     * {@code List<RowN<...>>} sources parameter; drives {@code newDataLoader(...)}.
     */
    record RowKeyed(List<ColumnRef> keyColumns) implements BatchKey {
        @Override
        public String javaTypeName() {
            return containerType("List", "Row", keyColumns);
        }
    }

    /**
     * Column-based batch key using {@code record.into()} key construction with a positional
     * {@code List<RecordN<...>>} sources parameter; drives {@code newDataLoader(...)}.
     */
    record RecordKeyed(List<ColumnRef> keyColumns) implements BatchKey {
        @Override
        public String javaTypeName() {
            return containerType("List", "Record", keyColumns);
        }
    }

    /**
     * Mapped variant of {@link RowKeyed}: column-based batch key using {@code DSL.row()} key
     * construction with a {@code Set<RowN<...>>} sources parameter; drives
     * {@code newMappedDataLoader(...)}. Both {@code Set<RowN<...>>} and
     * {@code Set<TableRecord>} classify here; the DataLoader key type stays {@code RowN}
     * regardless of the user's declared element type.
     */
    record MappedRowKeyed(List<ColumnRef> keyColumns) implements BatchKey {
        @Override
        public String javaTypeName() {
            return containerType("Set", "Row", keyColumns);
        }
    }

    /**
     * Mapped variant of {@link RecordKeyed}: column-based batch key using {@code record.into()}
     * key construction with a {@code Set<RecordN<...>>} sources parameter; drives
     * {@code newMappedDataLoader(...)}.
     */
    record MappedRecordKeyed(List<ColumnRef> keyColumns) implements BatchKey {
        @Override
        public String javaTypeName() {
            return containerType("Set", "Record", keyColumns);
        }
    }

    private static String containerType(String container, String shape, List<ColumnRef> cols) {
        if (cols.isEmpty()) return "java.util." + container + "<?>";
        var typeArgs = cols.stream()
            .map(ColumnRef::columnClass)
            .collect(Collectors.joining(", "));
        return "java.util." + container + "<org.jooq." + shape + cols.size() + "<" + typeArgs + ">>";
    }
}
