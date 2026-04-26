package no.sikt.graphitron.rewrite.model;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The batch-key strategy for a DataLoader SOURCES parameter.
 *
 * <p>Two variants, each answering "what Java type does the generator use for the key list,
 * and how is the key extracted from the parent record?"
 *
 * <ul>
 *   <li>{@link RowKeyed} — column-based; key type is {@code Row1<T>}, {@code Row2<T,U>}, etc.
 *       derived from {@code keyColumns}. Generator emits
 *       {@code DSL.row(record.get(TABLE.COL1), ...)}.</li>
 *   <li>{@link RecordKeyed} — column-based; key type is {@code Record1<T>}, {@code Record2<T,U>}, etc.
 *       derived from {@code keyColumns}. Generator emits
 *       {@code record.into(TABLE.COL1, ...)}.</li>
 * </ul>
 *
 * <p>{@code keyColumns} always comes from the parent type's
 * {@link TableRef#primaryKeyColumns()} — never from reflection. Parent types without a
 * {@link TableRef} cannot produce a {@link BatchKey} and fail classification upstream.
 */
public sealed interface BatchKey permits BatchKey.RowKeyed, BatchKey.RecordKeyed {

    /**
     * Returns the fully qualified generic Java type name for the {@code List<?>} parameter
     * corresponding to this {@link BatchKey} variant.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code RowKeyed([film_id:Integer])} →
     *       {@code "java.util.List<org.jooq.Row1<java.lang.Integer>>"}</li>
     *   <li>{@code RecordKeyed([film_id:Integer, actor_id:Integer])} →
     *       {@code "java.util.List<org.jooq.Record2<java.lang.Integer, java.lang.Integer>>"}</li>
     * </ul>
     */
    String javaTypeName();

    /**
     * Column-based batch key using {@code DSL.row()} key construction.
     *
     * <p>{@code keyColumns} is the ordered list of PK columns from the parent
     * {@link TableRef#primaryKeyColumns()}. May be empty when the parent type is a root
     * operation type (no backing table).
     */
    record RowKeyed(List<ColumnRef> keyColumns) implements BatchKey {
        @Override
        public String javaTypeName() {
            if (keyColumns.isEmpty()) return "java.util.List<?>";
            var typeArgs = keyColumns.stream()
                .map(ColumnRef::columnClass)
                .collect(Collectors.joining(", "));
            return "java.util.List<org.jooq.Row" + keyColumns.size() + "<" + typeArgs + ">>";
        }
    }

    /**
     * Column-based batch key using {@code record.into()} key construction.
     *
     * <p>{@code keyColumns} is the ordered list of PK columns from the parent
     * {@link TableRef#primaryKeyColumns()}. May be empty when the parent type is a root
     * operation type (no backing table).
     */
    record RecordKeyed(List<ColumnRef> keyColumns) implements BatchKey {
        @Override
        public String javaTypeName() {
            if (keyColumns.isEmpty()) return "java.util.List<?>";
            var typeArgs = keyColumns.stream()
                .map(ColumnRef::columnClass)
                .collect(Collectors.joining(", "));
            return "java.util.List<org.jooq.Record" + keyColumns.size() + "<" + typeArgs + ">>";
        }
    }

}
