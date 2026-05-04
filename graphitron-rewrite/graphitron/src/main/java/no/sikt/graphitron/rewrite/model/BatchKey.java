package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The batch-key strategy for a DataLoader SOURCES parameter.
 *
 * <p>Seven permits across two axis sub-hierarchies:
 *
 * <ul>
 *   <li>{@link ParentKeyed} (catalog-resolvable): {@link RowKeyed}, {@link RecordKeyed},
 *       {@link MappedRowKeyed}, {@link MappedRecordKeyed}, {@link TableRecordKeyed},
 *       {@link MappedTableRecordKeyed}. The cross-product of three key shapes
 *       ({@code RowN<...>} via {@code DSL.row(...)}, {@code RecordN<...>} via
 *       {@code record.into(col, ...)}, typed {@code TableRecord} subtype via
 *       {@code record.into(Table)}) and two containers ({@code List<...>} positional vs.
 *       {@code Set<...>} mapped). Each variant exposes
 *       {@link ParentKeyed#parentKeyColumns()}: PK/FK columns on the parent side.</li>
 *   <li>{@link RecordParentBatchKey} ({@code @record}-parent permits): {@link RowKeyed},
 *       {@link LifterRowKeyed}, {@link AccessorKeyedSingle}, {@link AccessorKeyedMany}.
 *       Used as the parameter type for {@code GeneratorUtils.buildRecordParentKeyExtraction};
 *       mis-routing a {@code @service}-only permit there is a compile error rather than a
 *       runtime {@code IllegalStateException}.</li>
 * </ul>
 *
 * <p>{@link RowKeyed} and {@link RecordKeyed} drive
 * {@code DataLoaderFactory.newDataLoader(BatchLoaderWithContext)};
 * {@link MappedRowKeyed} and {@link MappedRecordKeyed} drive
 * {@code DataLoaderFactory.newMappedDataLoader(MappedBatchLoaderWithContext)}.
 * {@link LifterRowKeyed} and {@link AccessorKeyedSingle} drive the same column-keyed
 * DataLoader path as {@link RowKeyed}; the key-extraction call site (developer-supplied lifter
 * vs. typed accessor on the parent backing class) and the join-path identity
 * ({@link JoinStep.LiftedHop} instead of {@link JoinStep.FkJoin}) differ.
 * {@link AccessorKeyedMany} drives {@code loader.loadMany(keys, env)} with loader value type
 * {@code Record} (one record per element-PK key); each parent contributes N keys via the
 * accessor's {@code List<X>} / {@code Set<X>} return.
 *
 * <p>Side-aware accessors live on the sub-interfaces: {@link ParentKeyed#parentKeyColumns()}
 * for the four catalog variants; {@link LifterRowKeyed#targetKeyColumns()} for the lifter
 * variant. The interface-level accessor would have variant-dependent meaning (parent-side
 * columns vs. target-side columns), so it does not exist; readers either switch on identity
 * or take a sub-interface-typed parameter.
 *
 * <p>Element-shape <strong>is</strong> preserved on the variant: {@code Set<RowN<...>>}
 * classifies as {@link MappedRowKeyed}, {@code Set<RecordN<...>>} as
 * {@link MappedRecordKeyed}, and {@code Set<X>} where {@code X extends TableRecord} as
 * {@link MappedTableRecordKeyed}; the {@code List} arms route to the corresponding
 * positional permits. {@code keyElementType()} reflects the developer's choice exactly,
 * which propagates through {@code RowsMethodShape.outerRowsReturnType} so the validator's
 * expected outer return type and the rows-method emitter's parameter shapes match the
 * developer's signature without conversion.
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
     * The JavaPoet {@link TypeName} for the DataLoader key element type corresponding to this
     * variant. Pure function over the variant's column shape; the container axis (positional
     * {@code List} vs. mapped {@code Set}) lives at each call site, not here.
     *
     * <ul>
     *   <li>{@link RowKeyed} / {@link MappedRowKeyed} / {@link LifterRowKeyed} →
     *       {@code RowN<A, B, ...>} — the developer-facing shape on the @service-source
     *       classification path (Row source declarations classify here) and the framework's
     *       FK-derived shape on @record parents.</li>
     *   <li>{@link RecordKeyed} / {@link MappedRecordKeyed} / {@link AccessorKeyedSingle} /
     *       {@link AccessorKeyedMany} → {@code RecordN<A, B, ...>} — the developer-facing
     *       shape on the @service-source Record path, and the auto-derived shape on
     *       accessor-keyed @record parents (no developer-facing source on those arms).</li>
     *   <li>{@link TableRecordKeyed} / {@link MappedTableRecordKeyed} → the typed
     *       {@code TableRecord} subtype the developer wrote (e.g. {@code FilmRecord}). The
     *       DataLoader key, the rows-method's outer {@code Map} key, and the SOURCES element
     *       all share that single typed class.</li>
     * </ul>
     *
     * <p>R61 added Record support without displacing Row: developers continue to choose
     * {@code Set<Row1<…>>} (Row surface, no {@code value1()}) or {@code Set<Record1<…>>}
     * (Record surface, with {@code value1()} value access). The classifier in
     * {@code ServiceCatalog} routes the source declaration to the matching variant; each
     * variant's {@code keyElementType()} reflects the developer's choice.
     */
    default TypeName keyElementType() {
        return switch (this) {
            case RowKeyed rk                  -> rowNType(rk.parentKeyColumns());
            case MappedRowKeyed mrk           -> rowNType(mrk.parentKeyColumns());
            case LifterRowKeyed lrk           -> rowNType(lrk.targetKeyColumns());
            case AccessorKeyedSingle ars      -> recordNType(ars.targetKeyColumns());
            case AccessorKeyedMany arm        -> recordNType(arm.targetKeyColumns());
            case RecordKeyed rk               -> recordNType(rk.parentKeyColumns());
            case MappedRecordKeyed mrk        -> recordNType(mrk.parentKeyColumns());
            case TableRecordKeyed trk         -> ClassName.get(trk.elementClass());
            case MappedTableRecordKeyed mtrk  -> ClassName.get(mtrk.elementClass());
        };
    }

    private static TypeName rowNType(List<ColumnRef> keyColumns) {
        if (keyColumns.isEmpty()) return ClassName.get("org.jooq", "Row");
        ClassName rowNClass = ClassName.get("org.jooq", "Row" + keyColumns.size());
        TypeName[] typeArgs = keyColumns.stream()
            .map(c -> (TypeName) ClassName.bestGuess(c.columnClass()))
            .toArray(TypeName[]::new);
        return ParameterizedTypeName.get(rowNClass, typeArgs);
    }

    private static TypeName recordNType(List<ColumnRef> keyColumns) {
        if (keyColumns.isEmpty()) return ClassName.get("org.jooq", "Record");
        ClassName recordNClass = ClassName.get("org.jooq", "Record" + keyColumns.size());
        TypeName[] typeArgs = keyColumns.stream()
            .map(c -> (TypeName) ClassName.bestGuess(c.columnClass()))
            .toArray(TypeName[]::new);
        return ParameterizedTypeName.get(recordNClass, typeArgs);
    }

    /**
     * Sub-hierarchy: keys whose columns name the parent-side PK/FK. The four catalog-resolvable
     * permits live here. {@code parentKeyColumns} names the side explicitly so polymorphic
     * consumers cannot confuse it with target-side columns supplied by a lifter.
     */
    sealed interface ParentKeyed extends BatchKey
            permits RowKeyed, RecordKeyed, MappedRowKeyed, MappedRecordKeyed,
                    TableRecordKeyed, MappedTableRecordKeyed {

        /**
         * PK/FK columns from the parent table. May be empty when the parent type is a root
         * operation type (no backing table).
         */
        List<ColumnRef> parentKeyColumns();
    }

    /**
     * Sub-hierarchy: keys produced for {@code @record} (non-table) parents. Permits
     * {@link RowKeyed} (catalog FK on a record parent), {@link LifterRowKeyed}
     * (developer-supplied lifter), {@link AccessorKeyedSingle} (auto-derived from a
     * single-cardinality typed accessor on the parent backing class), and
     * {@link AccessorKeyedMany} (auto-derived from a list / set typed accessor). This is
     * the input type for {@code GeneratorUtils.buildRecordParentKeyExtraction}; any future
     * caller routing a {@link RecordKeyed} or mapped variant here is a compile error rather
     * than a runtime {@code IllegalStateException}.
     */
    sealed interface RecordParentBatchKey extends BatchKey
            permits RowKeyed, LifterRowKeyed, AccessorKeyedSingle, AccessorKeyedMany {

        /**
         * Side-aware key columns for the rows-method prelude's parent-input VALUES table:
         * parent-side PK/FK columns on the catalog-FK arm ({@link RowKeyed}), target-side
         * columns on the lifter / accessor arms ({@link LifterRowKeyed},
         * {@link AccessorKeyedSingle}, {@link AccessorKeyedMany}). {@link RowKeyed} and
         * {@link LifterRowKeyed} produce {@code RowN<...>} keys of the same Java types as the
         * JOIN target columns; {@link AccessorKeyedSingle} and {@link AccessorKeyedMany}
         * produce {@code RecordN<...>} keys (no developer-facing source on these auto-derived
         * arms). For the lifter permit the column tuple is the lifter contract; for the
         * accessor permits the columns are the element table's PK by construction.
         *
         * <p>This capability lives on {@code RecordParentBatchKey} rather than on
         * {@link BatchKey} because the {@code @service}-only permits ({@link RecordKeyed},
         * {@link MappedRowKeyed}, {@link MappedRecordKeyed}) never reach the prelude:
         * {@code SplitRowsMethodEmitter} only handles {@code @splitQuery} and
         * {@code @record}-parent paths, which carry {@link RowKeyed} or
         * {@link RecordParentBatchKey} respectively. The four prelude-reachable variants are
         * exactly the {@code RecordParentBatchKey} permit list, so this method is a typed
         * accessor instead of a switch with a {@code default -> throw} arm.
         */
        List<ColumnRef> preludeKeyColumns();

        /**
         * The DataLoader dispatch shape this variant produces:
         * {@link LoaderDispatch#LOAD_ONE} for the three single-key arms ({@link RowKeyed},
         * {@link LifterRowKeyed}, {@link AccessorKeyedSingle}) — emit
         * {@code loader.load(key, env)} with loader value type {@code Record} or
         * {@code List<Record>} depending on field cardinality;
         * {@link LoaderDispatch#LOAD_MANY} for {@link AccessorKeyedMany} — emit
         * {@code loader.loadMany(keys, env)} with loader value type {@code Record} (one record
         * per element-PK key, regardless of the field's GraphQL cardinality).
         *
         * <p>The two emit sites that fork on this projection are
         * {@code TypeFetcherGenerator.buildRecordBasedDataFetcher} (loader value type and
         * dispatch call shape) and {@code GeneratorUtils.buildRecordParentKeyExtraction} (the
         * emitted key local's name: {@code key} for single, {@code keys} for many).
         */
        LoaderDispatch dispatch();
    }

    /**
     * The DataLoader dispatch a {@link RecordParentBatchKey} variant produces. Used by the two
     * emit sites that fork on whether the batched loader call is {@code loader.load(key, env)}
     * (one key, one value per key) or {@code loader.loadMany(keys, env)} (multiple keys per
     * parent, one record value per key).
     *
     * <p>An enum is appropriate here rather than a sealed hierarchy because the two arms carry
     * no per-arm data: the dispatch is a pure binary classification and consumers fork on
     * identity, not on captured fields.
     */
    enum LoaderDispatch { LOAD_ONE, LOAD_MANY }

    /**
     * Column-based batch key using {@code DSL.row(...)} key construction with a positional
     * {@code List<RowN<...>>} sources parameter; drives {@code newDataLoader(...)}. Only
     * {@code List<RowN<...>>} classifies here; {@code List<X extends TableRecord>} routes to
     * {@link TableRecordKeyed}.
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
        @Override
        public List<ColumnRef> preludeKeyColumns() {
            return parentKeyColumns;
        }
        @Override
        public LoaderDispatch dispatch() {
            return LoaderDispatch.LOAD_ONE;
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
     * Mapped variant of {@link RowKeyed}: column-based batch key using {@code DSL.row(...)} key
     * construction with a {@code Set<RowN<...>>} sources parameter; drives
     * {@code newMappedDataLoader(...)}. Only {@code Set<RowN<...>>} classifies here;
     * {@code Set<X extends TableRecord>} routes to {@link MappedTableRecordKeyed}
     * (variant identity tracks the developer's source shape).
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
     * Positional variant for the typed {@code TableRecord} source shape: developer signs
     * {@code List<X>} where {@code X extends TableRecord}, the rows-method's outer return is
     * {@code List<V>} (single) or {@code List<List<V>>} (list). Drives
     * {@code newDataLoader(...)} with {@code K = X}; key extraction projects through
     * {@code Table<R>.into(...)} so the application-side key carries the developer's typed
     * record.
     *
     * <p>{@code parentKeyColumns} stays the parent's PK/FK column tuple (used by the rows-method
     * VALUES prelude where applicable); {@code elementClass} is the developer-declared
     * {@code TableRecord} subtype that propagates through {@link #keyElementType()} into the
     * outer return type and the rows-method parameter shape.
     */
    record TableRecordKeyed(
            List<ColumnRef> parentKeyColumns,
            Class<? extends org.jooq.TableRecord<?>> elementClass) implements ParentKeyed {
        @Override
        public String javaTypeName() {
            return "java.util.List<" + elementClass.getName() + ">";
        }
    }

    /**
     * Mapped variant of {@link TableRecordKeyed}: developer signs {@code Set<X>} where
     * {@code X extends TableRecord}, the rows-method's outer return is {@code Map<X, V>}.
     * Drives {@code newMappedDataLoader(...)} with {@code K = X}.
     */
    record MappedTableRecordKeyed(
            List<ColumnRef> parentKeyColumns,
            Class<? extends org.jooq.TableRecord<?>> elementClass) implements ParentKeyed {
        @Override
        public String javaTypeName() {
            return "java.util.Set<" + elementClass.getName() + ">";
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

        @Override
        public List<ColumnRef> preludeKeyColumns() {
            return hop.targetColumns();
        }

        @Override
        public LoaderDispatch dispatch() {
            return LoaderDispatch.LOAD_ONE;
        }
    }

    /**
     * Column-based batch key for a single-cardinality child field on a {@code @record} parent
     * whose backing class exposes a typed zero-arg instance accessor returning a single concrete
     * jOOQ {@code TableRecord}. Auto-derived by
     * {@code FieldBuilder.classifyChildFieldOnResultType} when no FK is available in the catalog
     * but the parent class's accessor matches the field's {@code @table} return.
     *
     * <p>Drives {@code loader.load(key, env)}; loader value type is {@code Record}, identical
     * to the single-field {@link RowKeyed} / {@link LifterRowKeyed} dispatch. Single-key
     * variant; the join-path identity is {@link JoinStep.LiftedHop} (not {@link JoinStep.FkJoin}),
     * so the rows-method prelude reads {@code targetTable} and {@code targetColumns}
     * polymorphically through {@link JoinStep.WithTarget}.
     *
     * <p>Sibling of {@link AccessorKeyedMany} (list / set accessor) and {@link LifterRowKeyed}
     * (developer-supplied static lifter producing a {@code RowN<...>}). The cardinality split
     * between {@code Single} and {@code Many} is in the type system rather than a stored enum so
     * the dispatch in {@code TypeFetcherGenerator.buildRecordBasedDataFetcher} reads variant
     * identity, not a discriminator field.
     */
    record AccessorKeyedSingle(JoinStep.LiftedHop hop, AccessorRef accessor) implements RecordParentBatchKey {

        /**
         * Target-side key columns: the element table's PK. Delegates to
         * {@code hop.targetColumns()}; the {@link JoinStep.LiftedHop} is the single source of
         * truth so the DataLoader-key column tuple cannot diverge from the JOIN target columns.
         */
        public List<ColumnRef> targetKeyColumns() {
            return hop.targetColumns();
        }

        @Override
        public String javaTypeName() {
            return containerType("List", "Record", hop.targetColumns());
        }

        @Override
        public List<ColumnRef> preludeKeyColumns() {
            return hop.targetColumns();
        }

        @Override
        public LoaderDispatch dispatch() {
            return LoaderDispatch.LOAD_ONE;
        }
    }

    /**
     * Column-based batch key for a list-cardinality child field on a {@code @record} parent
     * whose backing class exposes a typed zero-arg instance accessor returning {@code List<X>}
     * or {@code Set<X>} for some concrete {@code X extends TableRecord}. Each parent contributes
     * N keys (one per element); drives {@code loader.loadMany(keys, env)} with loader value type
     * {@code Record}, so {@code loadMany} returns {@code CompletableFuture<List<Record>>} that
     * already matches the list-field shape. The rows-method's batching contract is unchanged
     * from the single-key sibling: it receives a {@code List<RowN>} (the union across parents)
     * and returns one record per key.
     *
     * <p>The {@code List<X>} vs {@code Set<X>} split the parent class declares is not preserved
     * on the variant: {@code GeneratorUtils.buildAccessorKeyMany} iterates any {@code Iterable}
     * via a typed for-loop and the loader dispatch is the same regardless. Should a future feature
     * fork emission on the container (preserving order, dedupe, parallel iteration), introduce
     * the split at that point with a real emit divergence behind it.
     *
     * <p>Auto-derived by {@code FieldBuilder.classifyChildFieldOnResultType} when no FK is
     * available in the catalog but the parent class's list / set accessor matches the field's
     * {@code @table} return. The classifier guarantees
     * {@code field.returnType().wrapper().isList() == true} for every {@code AccessorKeyedMany}
     * it produces (paired {@link no.sikt.graphitron.rewrite.model.LoadBearingClassifierCheck}
     * key {@code accessor-rowkey-cardinality-matches-field}).
     */
    record AccessorKeyedMany(JoinStep.LiftedHop hop, AccessorRef accessor)
            implements RecordParentBatchKey {

        /**
         * Target-side key columns: the element table's PK. Delegates to
         * {@code hop.targetColumns()}; the {@link JoinStep.LiftedHop} is the single source of
         * truth so the DataLoader-key column tuple cannot diverge from the JOIN target columns.
         */
        public List<ColumnRef> targetKeyColumns() {
            return hop.targetColumns();
        }

        @Override
        public String javaTypeName() {
            return containerType("List", "Record", hop.targetColumns());
        }

        @Override
        public List<ColumnRef> preludeKeyColumns() {
            return hop.targetColumns();
        }

        @Override
        public LoaderDispatch dispatch() {
            return LoaderDispatch.LOAD_MANY;
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
