package no.sikt.graphitron.rewrite.model;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The batch-key strategy for a DataLoader SOURCES parameter.
 *
 * <p>Three variants, each answering "what Java type does the generator use for the key list,
 * and how is the key extracted from the parent record?"
 *
 * <ul>
 *   <li>{@link RowKeyed} — column-based; key type is {@code Row1<T>}, {@code Row2<T,U>}, etc.
 *       derived from {@code keyColumns}. Generator emits
 *       {@code DSL.row(record.get(TABLE.COL1), ...)}.</li>
 *   <li>{@link RecordKeyed} — column-based; key type is {@code Record1<T>}, {@code Record2<T,U>}, etc.
 *       derived from {@code keyColumns}. Generator emits
 *       {@code record.into(TABLE.COL1, ...)}.</li>
 *   <li>{@link ObjectBased} — object-based; the whole parent record or DTO is the key.
 *       Generator emits {@code (CastType) env.getSource()}.</li>
 * </ul>
 *
 * <p>For column-based variants, {@code keyColumns} always comes from the parent type's
 * {@link TableRef#primaryKeyColumns()} — never from reflection. When the parent type has no
 * primary key (e.g. the parent is a root operation type), {@code keyColumns} is empty.
 */
public sealed interface BatchKey permits BatchKey.RowKeyed, BatchKey.RecordKeyed, BatchKey.ObjectBased {

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
     *   <li>{@code ObjectBased("...FilmRecord")} →
     *       {@code "java.util.List<...FilmRecord>"}</li>
     * </ul>
     */
    String javaTypeName();

    /**
     * Returns the name of the method to call on the generated table class when batch-loading
     * multiple result lists keyed by this {@link BatchKey} type.
     *
     * <p>E.g. {@code "selectManyByRowKeys"} for {@link RowKeyed},
     * {@code "selectManyByRecordKeys"} for {@link RecordKeyed}.
     */
    String selectManyMethodName();

    /**
     * Returns the name of the method to call on the generated table class when batch-loading
     * a single result keyed by this {@link BatchKey} type.
     *
     * <p>E.g. {@code "selectOneByRowKeys"} for {@link RowKeyed},
     * {@code "selectOneByRecordKeys"} for {@link RecordKeyed}.
     */
    String selectOneMethodName();

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
        @Override public String selectManyMethodName() { return "selectManyByRowKeys"; }
        @Override public String selectOneMethodName()  { return "selectOneByRowKeys"; }
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
        @Override public String selectManyMethodName() { return "selectManyByRecordKeys"; }
        @Override public String selectOneMethodName()  { return "selectOneByRecordKeys"; }
    }

    /**
     * Object-based batch key. The whole parent record or result DTO is passed as the key.
     * Generator emits {@code (fqClassName) env.getSource()}.
     *
     * <p>Covers both jOOQ {@code TableRecord} parents and result-mapped DTO parents.
     * {@code fqClassName} is the binary class name of the parent type.
     */
    record ObjectBased(String fqClassName) implements BatchKey {
        @Override
        public String javaTypeName() {
            return "java.util.List<" + fqClassName + ">";
        }
        @Override public String selectManyMethodName() { throw new UnsupportedOperationException("ObjectBased batch loading is not yet implemented"); }
        @Override public String selectOneMethodName()  { throw new UnsupportedOperationException("ObjectBased batch loading is not yet implemented"); }
    }
}
