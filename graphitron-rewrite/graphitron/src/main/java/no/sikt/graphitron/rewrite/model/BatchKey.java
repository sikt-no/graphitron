package no.sikt.graphitron.rewrite.model;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The batch-key strategy for a DataLoader SOURCES parameter.
 *
 * <p>Five permits across two axis sub-hierarchies:
 *
 * <ul>
 *   <li>{@link ParentKeyed} (catalog-resolvable): {@link RowKeyed}, {@link RecordKeyed},
 *       {@link MappedRowKeyed}, {@link MappedRecordKeyed}. The cross-product of two independent
 *       axes — container ({@code List<...>} positional vs. {@code Set<...>} mapped) and key
 *       shape ({@code RowN<...>} via {@code DSL.row(...)} vs. {@code RecordN<...>} via
 *       {@code record.into(...)}). Each variant exposes
 *       {@link ParentKeyed#parentKeyColumns()}: PK/FK columns on the parent side.</li>
 *   <li>{@link RecordParentBatchKey} ({@code @record}-parent permits): {@link RowKeyed} and
 *       {@link LifterRowKeyed}. Used as the parameter type for
 *       {@code GeneratorUtils.buildRecordParentKeyExtraction}; mis-routing a {@code @service}-only
 *       permit there is a compile error rather than a runtime
 *       {@code IllegalStateException}.</li>
 * </ul>
 *
 * <p>{@link RowKeyed} and {@link RecordKeyed} drive
 * {@code DataLoaderFactory.newDataLoader(BatchLoaderWithContext)};
 * {@link MappedRowKeyed} and {@link MappedRecordKeyed} drive
 * {@code DataLoaderFactory.newMappedDataLoader(MappedBatchLoaderWithContext)}.
 * {@link LifterRowKeyed} drives the same column-keyed DataLoader path as {@link RowKeyed}; only
 * the key-extraction call site (developer-supplied lifter) and the join-path identity
 * ({@link JoinStep.LiftedHop} instead of {@link JoinStep.FkJoin}) differ.
 *
 * <p>Side-aware accessors live on the sub-interfaces: {@link ParentKeyed#parentKeyColumns()}
 * for the four catalog variants; {@link LifterRowKeyed#targetKeyColumns()} for the lifter
 * variant. The interface-level accessor would have variant-dependent meaning (parent-side
 * columns vs. target-side columns), so it does not exist; readers either switch on identity
 * or take a sub-interface-typed parameter.
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
        permits BatchKey.ParentKeyed, BatchKey.RecordParentBatchKey {

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
     *   <li>{@code LifterRowKeyed(hop, lifter)} →
     *       {@code "java.util.List<org.jooq.Row1<java.lang.Long>>"} (using
     *       {@code hop.targetColumns()})</li>
     * </ul>
     */
    String javaTypeName();

    /**
     * Sub-hierarchy: keys whose columns name the parent-side PK/FK. The four catalog-resolvable
     * permits live here. {@code parentKeyColumns} names the side explicitly so polymorphic
     * consumers cannot confuse it with target-side columns supplied by a lifter.
     */
    sealed interface ParentKeyed extends BatchKey
            permits RowKeyed, RecordKeyed, MappedRowKeyed, MappedRecordKeyed {

        /**
         * PK/FK columns from the parent table. May be empty when the parent type is a root
         * operation type (no backing table).
         */
        List<ColumnRef> parentKeyColumns();
    }

    /**
     * Sub-hierarchy: keys produced for {@code @record} (non-table) parents. Permits
     * {@link RowKeyed} (catalog FK on a record parent) and {@link LifterRowKeyed}
     * (developer-supplied lifter). This is the input type for
     * {@code GeneratorUtils.buildRecordParentKeyExtraction}; any future caller routing a
     * {@link RecordKeyed} or mapped variant here is a compile error rather than a runtime
     * {@code IllegalStateException}.
     */
    sealed interface RecordParentBatchKey extends BatchKey
            permits RowKeyed, LifterRowKeyed {}

    /**
     * Column-based batch key using {@code DSL.row()} key construction with a positional
     * {@code List<RowN<...>>} sources parameter; drives {@code newDataLoader(...)}.
     *
     * <p>The only catalog-resolvable permit that participates in both sub-hierarchies: it
     * carries parent-side PK/FK columns ({@link ParentKeyed}) and is reachable from
     * {@code @record} parents whose backing class has a catalog FK ({@link RecordParentBatchKey}).
     */
    record RowKeyed(List<ColumnRef> parentKeyColumns) implements ParentKeyed, RecordParentBatchKey {
        @Override
        public String javaTypeName() {
            return containerType("List", "Row", parentKeyColumns);
        }
    }

    /**
     * Column-based batch key using {@code record.into()} key construction with a positional
     * {@code List<RecordN<...>>} sources parameter; drives {@code newDataLoader(...)}.
     */
    record RecordKeyed(List<ColumnRef> parentKeyColumns) implements ParentKeyed {
        @Override
        public String javaTypeName() {
            return containerType("List", "Record", parentKeyColumns);
        }
    }

    /**
     * Mapped variant of {@link RowKeyed}: column-based batch key using {@code DSL.row()} key
     * construction with a {@code Set<RowN<...>>} sources parameter; drives
     * {@code newMappedDataLoader(...)}. Both {@code Set<RowN<...>>} and
     * {@code Set<TableRecord>} classify here; the DataLoader key type stays {@code RowN}
     * regardless of the user's declared element type.
     */
    record MappedRowKeyed(List<ColumnRef> parentKeyColumns) implements ParentKeyed {
        @Override
        public String javaTypeName() {
            return containerType("Set", "Row", parentKeyColumns);
        }
    }

    /**
     * Mapped variant of {@link RecordKeyed}: column-based batch key using {@code record.into()}
     * key construction with a {@code Set<RecordN<...>>} sources parameter; drives
     * {@code newMappedDataLoader(...)}.
     */
    record MappedRecordKeyed(List<ColumnRef> parentKeyColumns) implements ParentKeyed {
        @Override
        public String javaTypeName() {
            return containerType("Set", "Record", parentKeyColumns);
        }
    }

    /**
     * Column-based batch key produced by a {@code @batchKeyLifter} directive on a child field
     * whose {@code @record} parent has no catalog FK. The {@link JoinStep.LiftedHop} held
     * directly on this record is the single source of truth for the target-side column tuple
     * AND the single-hop invariant: there is nowhere for a second hop to live on the lifter
     * path, because the field is not a {@code List<LiftedHop>}.
     *
     * <p>Drives the same column-keyed DataLoader path as {@link RowKeyed}; only the
     * key-extraction call site (a static lifter on the parent's backing class) and the
     * join-path identity ({@link JoinStep.LiftedHop} instead of {@link JoinStep.FkJoin}) differ.
     *
     * <p>Target-side columns the lifter materialises are exposed via
     * {@link #targetKeyColumns()}, named distinctly from
     * {@link ParentKeyed#parentKeyColumns()} because the SQL identity is different (target
     * table, not parent). Both produce {@code RowN<...>} of the same Java types — that is the
     * lifter contract enforced by the resolver's arity / column-class match check.
     */
    record LifterRowKeyed(JoinStep.LiftedHop hop, LifterRef lifter) implements RecordParentBatchKey {

        /**
         * Target-side columns the lifter materialises. Delegates to {@code hop.targetColumns()};
         * the {@link JoinStep.LiftedHop} is the single source of truth so the DataLoader-key
         * column tuple cannot diverge from the JOIN target columns.
         */
        public List<ColumnRef> targetKeyColumns() {
            return hop.targetColumns();
        }

        @Override
        public String javaTypeName() {
            return containerType("List", "Row", hop.targetColumns());
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
