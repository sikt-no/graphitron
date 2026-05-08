package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The batch-key strategy for a DataLoader SOURCES parameter.
 *
 * <p>Eleven permits across two axis sub-hierarchies (ten unique class names; {@code RowKeyed}
 * appears once on each axis):
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
 *       {@link LifterLeafKeyed}, {@link LifterPathKeyed}, {@link AccessorKeyedSingle},
 *       {@link AccessorKeyedMany}. Used as the parameter type for
 *       {@code GeneratorUtils.buildRecordParentKeyExtraction}; mis-routing a
 *       {@code @service}-only permit there is a compile error rather than a runtime
 *       {@code IllegalStateException}. The two lifter permits split on the @sourceRow
 *       composition shape: {@link LifterLeafKeyed} carries a single {@link JoinStep.LiftedHop}
 *       and tracks the no-{@code @reference} leaf-PK case; {@link LifterPathKeyed} carries a
 *       resolved {@link JoinStep.FkJoin} chain and tracks the {@code @reference}-composed case.</li>
 * </ul>
 *
 * <p>{@link RowKeyed} and {@link RecordKeyed} drive
 * {@code DataLoaderFactory.newDataLoader(BatchLoaderWithContext)};
 * {@link MappedRowKeyed} and {@link MappedRecordKeyed} drive
 * {@code DataLoaderFactory.newMappedDataLoader(MappedBatchLoaderWithContext)}.
 * {@link LifterLeafKeyed}, {@link LifterPathKeyed}, and {@link AccessorKeyedSingle} drive the
 * same column-keyed DataLoader path as {@link RowKeyed}; the key-extraction call site
 * (developer-supplied lifter vs. typed accessor on the parent backing class) and the
 * join-path identity ({@link JoinStep.LiftedHop} or a chain rooted in {@link JoinStep.FkJoin}
 * instead of a single {@link JoinStep.FkJoin}) differ. {@link AccessorKeyedMany} drives
 * {@code loader.loadMany(keys, env)} with loader value type {@code Record} (one record per
 * element-PK key); each parent contributes N keys via the accessor's {@code List<X>} /
 * {@code Set<X>} return.
 *
 * <p>Side-aware accessors live on the sub-interfaces: {@link ParentKeyed#parentKeyColumns()}
 * for the six catalog variants; {@link RecordParentBatchKey#parentSideColumns()} for the
 * record-parent variants (delegates to the first hop's source-side columns on the lifter /
 * accessor permits and to the FK source-side columns on the {@link RowKeyed} arm). The
 * interface-level accessor would have variant-dependent meaning (parent-side columns vs.
 * target-side columns), so it does not exist; readers either switch on identity or take a
 * sub-interface-typed parameter.
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
     *   <li>{@code LifterLeafKeyed(hop, lifter)} →
     *       {@code "java.util.List<org.jooq.Row1<java.lang.Long>>"} (using
     *       {@code hop.targetSideColumns()})</li>
     *   <li>{@code LifterPathKeyed(path, lifter)} →
     *       {@code "java.util.List<org.jooq.Row1<java.lang.Integer>>"} (using
     *       {@code path.getFirst().sourceSideColumns()})</li>
     * </ul>
     */
    String javaTypeName();

    /**
     * The JavaPoet {@link TypeName} for the DataLoader key element type corresponding to this
     * variant. Pure function over the variant's column shape; the container axis (positional
     * {@code List} vs. mapped {@code Set}) lives at each call site, not here.
     *
     * <ul>
     *   <li>{@link RowKeyed} / {@link MappedRowKeyed} / {@link LifterLeafKeyed} /
     *       {@link LifterPathKeyed} → {@code RowN<A, B, ...>} — the developer-facing shape on
     *       the @service-source classification path (Row source declarations classify here)
     *       and the framework's FK-derived shape on @record parents.</li>
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
     * <p>Record support coexists with Row support: developers choose
     * {@code Set<Row1<…>>} (Row surface, no {@code value1()}) or {@code Set<Record1<…>>}
     * (Record surface, with {@code value1()} value access). The classifier in
     * {@code ServiceCatalog} routes the source declaration to the matching variant; each
     * variant's {@code keyElementType()} reflects the developer's choice.
     */
    default TypeName keyElementType() {
        return switch (this) {
            case RowKeyed rk                  -> rowNType(rk.parentKeyColumns());
            case MappedRowKeyed mrk           -> rowNType(mrk.parentKeyColumns());
            case LifterLeafKeyed llk          -> rowNType(llk.parentSideColumns());
            case LifterPathKeyed lpk          -> rowNType(lpk.parentSideColumns());
            case AccessorKeyedSingle ars      -> recordNType(ars.targetKeyColumns());
            case AccessorKeyedMany arm        -> recordNType(arm.targetKeyColumns());
            case RecordKeyed rk               -> recordNType(rk.parentKeyColumns());
            case MappedRecordKeyed mrk        -> recordNType(mrk.parentKeyColumns());
            case TableRecordKeyed trk         -> ClassName.get(trk.elementClass());
            case MappedTableRecordKeyed mtrk  -> ClassName.get(mtrk.elementClass());
        };
    }

    private static TypeName rowNType(List<ColumnRef> keyColumns) {
        ClassName rowNClass = ClassName.get("org.jooq", "Row" + keyColumns.size());
        TypeName[] typeArgs = keyColumns.stream()
            .map(c -> (TypeName) ClassName.bestGuess(c.columnClass()))
            .toArray(TypeName[]::new);
        return ParameterizedTypeName.get(rowNClass, typeArgs);
    }

    private static TypeName recordNType(List<ColumnRef> keyColumns) {
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
         * PK/FK columns from the parent table. Non-empty by canonical-constructor invariant on
         * every permit: a {@link BatchKey} without key columns is not a coherent batch-key
         * declaration and no producer in the codebase legitimately constructs one. Validator
         * rejection of empty-PK parents (e.g.
         * {@code GraphitronSchemaValidator.validateChildMultiTableParentPk}) keeps the
         * canonical-constructor IAE unreachable on the fields it gates.
         */
        List<ColumnRef> parentKeyColumns();
    }

    /**
     * Sub-hierarchy: keys produced for {@code @record} (non-table) parents. Permits
     * {@link RowKeyed} (catalog FK on a record parent), {@link LifterLeafKeyed} (developer-
     * supplied {@code @sourceRow} lifter, no {@code @reference}; key tuple equals the leaf
     * target's PK), {@link LifterPathKeyed} (developer-supplied {@code @sourceRow} lifter
     * composed with {@code @reference}; key tuple equals the first hop's source-side
     * columns), {@link AccessorKeyedSingle} (auto-derived from a single-cardinality typed
     * accessor on the parent backing class), and {@link AccessorKeyedMany} (auto-derived
     * from a list / set typed accessor). This is the input type for
     * {@code GeneratorUtils.buildRecordParentKeyExtraction}; any future caller routing a
     * {@link RecordKeyed} or mapped variant here is a compile error rather than a runtime
     * {@code IllegalStateException}.
     */
    sealed interface RecordParentBatchKey extends BatchKey
            permits RowKeyed, LifterKeyed, AccessorKeyedSingle, AccessorKeyedMany {

        /**
         * Side-aware key columns for the rows-method prelude's parent-input VALUES table:
         * parent-side PK/FK columns on the catalog-FK arm ({@link RowKeyed}), the lifter's
         * declared parent-side tuple on the lifter arms ({@link LifterLeafKeyed},
         * {@link LifterPathKeyed}), and the element-PK columns on the accessor arms
         * ({@link AccessorKeyedSingle}, {@link AccessorKeyedMany}). {@link RowKeyed},
         * {@link LifterLeafKeyed}, and {@link LifterPathKeyed} produce {@code RowN<...>} keys
         * of the same Java types as the JOIN target columns;
         * {@link AccessorKeyedSingle} and {@link AccessorKeyedMany} produce
         * {@code RecordN<...>} keys (no developer-facing source on these auto-derived arms).
         * For the leaf-keyed lifter permit the column tuple is the leaf table's PK; for the
         * path-keyed lifter permit it is the first hop's source-side tuple; for the accessor
         * permits the columns are the element table's PK by construction.
         *
         * <p>This capability lives on {@code RecordParentBatchKey} rather than on
         * {@link BatchKey} because the {@code @service}-only permits ({@link RecordKeyed},
         * {@link MappedRowKeyed}, {@link MappedRecordKeyed}) never reach the prelude:
         * {@code SplitRowsMethodEmitter} only handles {@code @splitQuery} and
         * {@code @record}-parent paths, which carry {@link RowKeyed} or
         * {@link RecordParentBatchKey} respectively. The five prelude-reachable variants are
         * exactly the {@code RecordParentBatchKey} permit list, so this method is a typed
         * accessor instead of a switch with a {@code default -> throw} arm.
         */
        List<ColumnRef> preludeKeyColumns();

        /**
         * The DataLoader dispatch shape this variant produces:
         * {@link LoaderDispatch#LOAD_ONE} for the four single-key arms ({@link RowKeyed},
         * {@link LifterLeafKeyed}, {@link LifterPathKeyed}, {@link AccessorKeyedSingle}) —
         * emit {@code loader.load(key, env)} with loader value type {@code Record} or
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
     * Sub-hierarchy of {@link RecordParentBatchKey}: keys produced from a
     * {@code @sourceRow}-declared static lifter on the parent's backing class. The two
     * permits split on whether the directive composes with {@code @reference}:
     *
     * <ul>
     *   <li>{@link LifterLeafKeyed} — no {@code @reference}. The lifter's {@code RowN} matches
     *       the leaf target table's PK; the join is a single column-equality hop. Holds a
     *       single {@link JoinStep.LiftedHop}.</li>
     *   <li>{@link LifterPathKeyed} — composed with {@code @reference}. The lifter's
     *       {@code RowN} matches the first FK hop's source-side columns; subsequent hops
     *       walk the catalog FK chain. Holds the resolved {@link JoinStep.FkJoin} list.</li>
     * </ul>
     *
     * <p>The shared accessors {@link #path()}, {@link #parentSideColumns()}, and
     * {@link #lifter()} let consumers (rows-method prelude, key-extraction emitter, validator
     * diagnostics) walk both shapes uniformly without forking on permit identity. The split
     * lives in the type system so resolver dispatch (different diagnostic templates) stays
     * exhaustive across the two shapes.
     */
    sealed interface LifterKeyed extends RecordParentBatchKey
            permits LifterLeafKeyed, LifterPathKeyed {

        /**
         * The full join path from the parent's frame of reference to the leaf target table.
         * {@link LifterLeafKeyed#path()} returns {@code List.of(hop)} (a single
         * {@link JoinStep.LiftedHop} carrying the leaf table and its PK columns);
         * {@link LifterPathKeyed#path()} returns the resolved {@link JoinStep.FkJoin} chain.
         * Used by the rows-method prelude and bridging-hop loop without per-permit forking.
         */
        List<JoinStep> path();

        /**
         * Parent-side columns the lifter's {@code RowN} aligns with (and the rows-method's
         * {@code JOIN parentInput ... ON ...} predicate matches against on the source side of
         * the first hop). {@link LifterLeafKeyed} returns the leaf target's PK columns (the
         * single column-equality hop has source-side == target-side by construction);
         * {@link LifterPathKeyed} returns {@code path.getFirst().sourceSideColumns()}.
         * Today this aliases {@link #preludeKeyColumns()}; the two methods stay distinct so
         * future variants can diverge on prelude-vs-JOIN tuple shape.
         */
        List<ColumnRef> parentSideColumns();

        /** The static lifter method captured at classification time. */
        LifterRef lifter();
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
        public RowKeyed {
            if (parentKeyColumns.isEmpty()) {
                throw new IllegalArgumentException(
                    "BatchKey.RowKeyed requires a non-empty parentKeyColumns list");
            }
            parentKeyColumns = List.copyOf(parentKeyColumns);
        }
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
        public RecordKeyed {
            if (parentKeyColumns.isEmpty()) {
                throw new IllegalArgumentException(
                    "BatchKey.RecordKeyed requires a non-empty parentKeyColumns list");
            }
            parentKeyColumns = List.copyOf(parentKeyColumns);
        }
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
        public MappedRowKeyed {
            if (parentKeyColumns.isEmpty()) {
                throw new IllegalArgumentException(
                    "BatchKey.MappedRowKeyed requires a non-empty parentKeyColumns list");
            }
            parentKeyColumns = List.copyOf(parentKeyColumns);
        }
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
        public MappedRecordKeyed {
            if (parentKeyColumns.isEmpty()) {
                throw new IllegalArgumentException(
                    "BatchKey.MappedRecordKeyed requires a non-empty parentKeyColumns list");
            }
            parentKeyColumns = List.copyOf(parentKeyColumns);
        }
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
        public TableRecordKeyed {
            if (parentKeyColumns.isEmpty()) {
                throw new IllegalArgumentException(
                    "BatchKey.TableRecordKeyed requires a non-empty parentKeyColumns list");
            }
            parentKeyColumns = List.copyOf(parentKeyColumns);
        }
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
        public MappedTableRecordKeyed {
            if (parentKeyColumns.isEmpty()) {
                throw new IllegalArgumentException(
                    "BatchKey.MappedTableRecordKeyed requires a non-empty parentKeyColumns list");
            }
            parentKeyColumns = List.copyOf(parentKeyColumns);
        }
        @Override
        public String javaTypeName() {
            return "java.util.Set<" + elementClass.getName() + ">";
        }
    }

    /**
     * Column-based batch key produced by a {@code @sourceRow} directive without
     * {@code @reference} composition: the lifter's {@code RowN} matches the leaf target
     * table's PK columns and the join is a single column-equality hop. The
     * {@link JoinStep.LiftedHop} held directly on this record is the single source of truth
     * for the JOIN target tuple; LifterSlot collapses source-side and target-side onto the
     * same column by construction (the DataLoader key tuple IS the leaf-PK tuple).
     *
     * <p>Drives the same column-keyed DataLoader path as {@link RowKeyed}; only the
     * key-extraction call site (a static lifter on the parent's backing class) and the
     * join-path identity ({@link JoinStep.LiftedHop} instead of {@link JoinStep.FkJoin}) differ.
     *
     * <p>Sibling of {@link LifterPathKeyed}, which carries the {@code @sourceRow +
     * @reference} composition shape; the variant identity tracks "leaf-PK column-equality" vs
     * "first-hop source-side columns" without per-instance branching at consumer sites.
     */
    record LifterLeafKeyed(JoinStep.LiftedHop hop, LifterRef lifter) implements LifterKeyed {

        @Override
        public List<JoinStep> path() {
            return List.of(hop);
        }

        @Override
        public List<ColumnRef> parentSideColumns() {
            return hop.targetSideColumns();
        }

        @Override
        public String javaTypeName() {
            return containerType("List", "Row", hop.targetSideColumns());
        }

        @Override
        public List<ColumnRef> preludeKeyColumns() {
            return hop.targetSideColumns();
        }

        @Override
        public LoaderDispatch dispatch() {
            return LoaderDispatch.LOAD_ONE;
        }
    }

    /**
     * Column-based batch key produced by a {@code @sourceRow} directive composed with
     * {@code @reference}: the lifter's {@code RowN} matches the first FK hop's source-side
     * columns and subsequent hops walk the catalog FK chain to the leaf target. The resolved
     * {@link JoinStep.FkJoin} list is held directly on this record so the rows-method emitter
     * can emit the bridging hops without re-entering {@code BuildContext.parsePath}.
     *
     * <p>Compact-constructor invariant: {@code path} is non-empty. An empty path collapses
     * the variant's whole reason to exist (the {@code @reference}-driven multi-hop shape) and
     * cannot represent a valid composition; the resolver routes that case to
     * {@link LifterLeafKeyed} instead.
     *
     * <p>Sibling of {@link LifterLeafKeyed}; see that record for the variant-axis rationale.
     */
    record LifterPathKeyed(List<JoinStep> path, LifterRef lifter) implements LifterKeyed {

        public LifterPathKeyed {
            if (path.isEmpty()) {
                throw new IllegalArgumentException(
                    "BatchKey.LifterPathKeyed requires a non-empty path; the @reference-composed "
                    + "shape cannot represent an empty chain (use LifterLeafKeyed for the "
                    + "no-@reference leaf-PK case).");
            }
            path = List.copyOf(path);
        }

        @Override
        public List<ColumnRef> parentSideColumns() {
            JoinStep first = path.getFirst();
            if (first instanceof JoinStep.WithTarget wt) {
                return wt.sourceSideColumns();
            }
            throw new IllegalStateException(
                "BatchKey.LifterPathKeyed first hop must implement JoinStep.WithTarget (FkJoin "
                + "or LiftedHop); got " + first.getClass().getSimpleName());
        }

        @Override
        public String javaTypeName() {
            return containerType("List", "Row", parentSideColumns());
        }

        @Override
        public List<ColumnRef> preludeKeyColumns() {
            return parentSideColumns();
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
     * to the single-field {@link RowKeyed} / {@link LifterLeafKeyed} dispatch. Single-key
     * variant; the join-path identity is {@link JoinStep.LiftedHop} (not {@link JoinStep.FkJoin}),
     * so the rows-method prelude reads {@code targetTable} and {@code targetColumns}
     * polymorphically through {@link JoinStep.WithTarget}.
     *
     * <p>Sibling of {@link AccessorKeyedMany} (list / set accessor) and the
     * {@link LifterLeafKeyed} / {@link LifterPathKeyed} pair (developer-supplied static
     * lifter producing a {@code RowN<...>}). The cardinality split
     * between {@code Single} and {@code Many} is in the type system rather than a stored enum so
     * the dispatch in {@code TypeFetcherGenerator.buildRecordBasedDataFetcher} reads variant
     * identity, not a discriminator field.
     */
    record AccessorKeyedSingle(JoinStep.LiftedHop hop, AccessorRef accessor) implements RecordParentBatchKey {

        /**
         * Target-side key columns: the element table's PK. Delegates to
         * {@code hop.targetSideColumns()}; the {@link JoinStep.LiftedHop} is the single source of
         * truth so the DataLoader-key column tuple cannot diverge from the JOIN target columns.
         */
        public List<ColumnRef> targetKeyColumns() {
            return hop.targetSideColumns();
        }

        @Override
        public String javaTypeName() {
            return containerType("List", "Record", hop.targetSideColumns());
        }

        @Override
        public List<ColumnRef> preludeKeyColumns() {
            return hop.targetSideColumns();
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
         * {@code hop.targetSideColumns()}; the {@link JoinStep.LiftedHop} is the single source of
         * truth so the DataLoader-key column tuple cannot diverge from the JOIN target columns.
         */
        public List<ColumnRef> targetKeyColumns() {
            return hop.targetSideColumns();
        }

        @Override
        public String javaTypeName() {
            return containerType("List", "Record", hop.targetSideColumns());
        }

        @Override
        public List<ColumnRef> preludeKeyColumns() {
            return hop.targetSideColumns();
        }

        @Override
        public LoaderDispatch dispatch() {
            return LoaderDispatch.LOAD_MANY;
        }
    }

    private static String containerType(String container, String shape, List<ColumnRef> cols) {
        var typeArgs = cols.stream()
            .map(ColumnRef::columnClass)
            .collect(Collectors.joining(", "));
        return "java.util." + container + "<org.jooq." + shape + cols.size() + "<" + typeArgs + ">>";
    }
}
