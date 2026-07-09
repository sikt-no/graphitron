package no.sikt.graphitron.rewrite.compile;

import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.CallSiteCompaction;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.DmlReturnExpression;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.SqlGeneratingField;
import no.sikt.graphitron.rewrite.model.WhereFilter;

import java.util.List;

/**
 * R410 slice 2 — produces the {@link CompileDependencyGraph} by coarsening the classified model.
 * This is the model-sourced arm of the sourcing seam: the one method
 * ({@link #fromModel(GraphitronSchema, String)}) that turns the classified {@link GraphitronSchema}
 * into a file-level graph, mirroring {@code CatalogBuilder.projectFieldClassification}'s discipline
 * of a pure exhaustive-switch projection over the same leaves the emitter reads (no emitter-reference
 * logic reconstructed here).
 *
 * <p>Three exhaustive switches carry the one-model drift guard: {@link #addTypeNodes} over the
 * {@link GraphitronType} leaves (a new type variant fails to compile until its file contribution is
 * declared), {@link #addFieldEdges} over the {@link GraphitronField} leaves (a new field variant
 * fails to compile until its reference contribution is declared), and {@link #addProjectionChildEdges}
 * over the {@link ChildField} leaves (a new inline-projecting field variant fails to compile until its
 * type-to-type projection edge is declared). The R333 step (a later item) re-targets these switches
 * onto the method graph without changing this consumer.
 *
 * <p>Edge policy (see {@link CompileDependencyGraph}'s superset contract and {@link UtilSingleton}):
 * per-type structural edges are projected precisely from the leaves; the ABI-frozen runtime
 * singletons are reached by a blanket over-approximation; {@code NodeIdEncoder} (the one per-type
 * growing singleton) is reached precisely from the NodeId-encoding leaves.
 */
public final class CompileDependencyGraphBuilder {

    private final GraphitronSchema schema;
    private final GeneratedUnits units;
    private final MapCompileDependencyGraph.Accumulator acc = new MapCompileDependencyGraph.Accumulator();

    private CompileDependencyGraphBuilder(GraphitronSchema schema, String outputPackage) {
        this.schema = schema;
        this.units = new GeneratedUnits(outputPackage);
    }

    /**
     * Builds the file-level compile-dependency graph for {@code schema}, with generated units named
     * under {@code outputPackage}. The single entry point of the sourcing seam.
     */
    public static CompileDependencyGraph fromModel(GraphitronSchema schema, String outputPackage) {
        var builder = new CompileDependencyGraphBuilder(schema, outputPackage);
        builder.build();
        return builder.acc.build();
    }

    private void build() {
        addSingletonNodes();
        for (var type : schema.types().values()) {
            addTypeNodes(type);
        }
        for (var field : schema.fields().values()) {
            addFieldEdges(field);
        }
        addTypeProjectionEdges();
        addBlanketAndWiringEdges();
    }

    // ------------------------------------------------------------------------------------------------
    // Nodes: exhaustive switch over the GraphitronType leaves.
    // ------------------------------------------------------------------------------------------------

    /**
     * Registers the file nodes a single classified type contributes. Exhaustive over the
     * {@link GraphitronType} permits: the compiler forces a file-contribution decision for any new
     * type variant. {@link GraphitronType.ScalarType} and {@link GraphitronType.UnclassifiedType}
     * contribute no per-type file (they omit {@code EmitsPerTypeFile}), so their arms are empty.
     */
    private void addTypeNodes(GraphitronType type) {
        switch (type) {
            // Catalog-backed object types: jOOQ projection class (types/), data fetcher, schema shape.
            case GraphitronType.TableType t -> {
                acc.addNode(units.typeClass(t.name()));
                acc.addNode(units.fetchers(t.name()));
                acc.addNode(units.schemaShape(t.name()));
                addConditionsNodeIfSql(t.name());
            }
            case GraphitronType.NodeType t -> {
                acc.addNode(units.typeClass(t.name()));
                acc.addNode(units.fetchers(t.name()));
                acc.addNode(units.schemaShape(t.name()));
                addConditionsNodeIfSql(t.name());
                // EntityFetcherDispatch's select<Type>Alt<N> methods project this node type's
                // $fields, so the dispatch unit recompiles when the projection's ABI moves.
                acc.addEdge(entityFetcherDispatchFqcn(), units.typeClass(t.name()));
            }
            case GraphitronType.TableInterfaceType t -> {
                acc.addNode(units.fetchers(t.name()));
                acc.addNode(units.schemaShape(t.name()));
            }
            // Result (record-backed) types: data fetcher + schema shape, no jOOQ projection class.
            case GraphitronType.ResultType t -> {
                acc.addNode(units.fetchers(t.name()));
                acc.addNode(units.schemaShape(t.name()));
            }
            // Root operation types: data fetcher + schema shape + a root conditions class if it has
            // any SQL-generating field.
            case GraphitronType.RootType t -> {
                acc.addNode(units.fetchers(t.name()));
                acc.addNode(units.schemaShape(t.name()));
                addConditionsNodeIfSql(t.name());
            }
            case GraphitronType.InterfaceType t -> acc.addNode(units.schemaShape(t.name()));
            case GraphitronType.UnionType t -> acc.addNode(units.schemaShape(t.name()));
            // @error types: per-type fetcher (ErrorTypeFetcherClassGenerator) + schema shape.
            case GraphitronType.ErrorType t -> {
                acc.addNode(units.fetchers(t.name()));
                acc.addNode(units.schemaShape(t.name()));
            }
            // Input types: input record (inputs/) + schema shape.
            case GraphitronType.InputType t -> {
                acc.addNode(units.inputRecord(t.name()));
                acc.addNode(units.schemaShape(t.name()));
            }
            case GraphitronType.TableInputType t -> {
                acc.addNode(units.inputRecord(t.name()));
                acc.addNode(units.schemaShape(t.name()));
            }
            // Relay connection: connection + edge fetchers + schema shape.
            case GraphitronType.ConnectionType t -> {
                acc.addNode(units.fetchers(t.name()));
                acc.addNode(units.fetchers(t.edgeTypeName()));
                acc.addNode(units.schemaShape(t.name()));
            }
            case GraphitronType.EdgeType t -> acc.addNode(units.schemaShape(t.name()));
            case GraphitronType.PageInfoType t -> acc.addNode(units.schemaShape(t.name()));
            // Nesting projections inline into their parent; they contribute a schema shape only.
            case GraphitronType.NestingType t -> acc.addNode(units.schemaShape(t.name()));
            case GraphitronType.EnumType t -> acc.addNode(units.schemaShape(t.name()));
            // No per-type file: handled by the scalar resolver / already diagnosed.
            case GraphitronType.ScalarType ignored -> { }
            case GraphitronType.UnclassifiedType ignored -> { }
        }
    }

    private void addConditionsNodeIfSql(String typeName) {
        if (hasSqlGeneratingField(typeName)) {
            acc.addNode(units.conditions(typeName));
        }
    }

    private boolean hasSqlGeneratingField(String typeName) {
        return schema.fieldsOf(typeName).stream().anyMatch(f -> f instanceof SqlGeneratingField);
    }

    // ------------------------------------------------------------------------------------------------
    // Edges: exhaustive switch over the GraphitronField leaves.
    // ------------------------------------------------------------------------------------------------

    /**
     * Registers the reference edges a single classified field contributes, all sourced from its
     * emitting {@code <parent>Fetchers} unit. Exhaustive over the {@link GraphitronField} permits
     * (mirroring {@code projectFieldClassification}): a table-navigating field references its target
     * type's projection class and its parent's conditions class; a NodeId-encoding field references
     * {@code NodeIdEncoder} precisely; scalar / record / passthrough leaves add no cross-type edge
     * (they read off the arrived source). Root fields delegate to {@link #addRootFieldEdges}.
     */
    private void addFieldEdges(GraphitronField field) {
        String fetcher = units.fetchers(field.parentTypeName());
        switch (field) {
            // Table-navigating child fields: reference the target type's projection + parent conditions.
            case ChildField.TableTargetField f -> {
                addTypeClassEdge(fetcher, f.returnType());
                addConditionsEdge(fetcher, field.parentTypeName());
            }
            case ChildField.TableMethodField f -> addTypeClassEdge(fetcher, f.returnType());
            case ChildField.RecordTableMethodField f -> addTypeClassEdge(fetcher, f.returnType());
            case ChildField.NestingField f -> addTypeClassEdge(fetcher, f.returnType());
            case ChildField.InterfaceField f -> addParticipantTypeClassEdges(fetcher, f.participants());
            case ChildField.UnionField f -> addParticipantTypeClassEdges(fetcher, f.participants());
            // NodeId-encoded carriers: precise NodeIdEncoder edge (the one per-type-growing singleton).
            case ChildField.CompositeColumnField ignored -> addNodeIdEncoderEdge(fetcher);
            case ChildField.CompositeColumnReferenceField ignored -> addNodeIdEncoderEdge(fetcher);
            case ChildField.SingleRecordIdField ignored -> addNodeIdEncoderEdge(fetcher);
            case ChildField.SingleRecordIdFieldFromReturning ignored -> addNodeIdEncoderEdge(fetcher);
            case ChildField.ColumnField f -> addNodeIdEncoderEdgeIfEncoded(fetcher, f.compaction());
            case ChildField.ColumnReferenceField f -> addNodeIdEncoderEdgeIfEncoded(fetcher, f.compaction());
            // Scalar / record / passthrough / cross-table-participant leaves: read off the arrived
            // source, no cross-type projection edge. (Frozen runtime helpers reached via the blanket.)
            case ChildField.ParticipantColumnReferenceField ignored -> { }
            case ChildField.RecordField ignored -> { }
            case ChildField.RecordCompositeField ignored -> { }
            case ChildField.PropertyField ignored -> { }
            case ChildField.ComputedField ignored -> { }
            case ChildField.ServiceRecordField ignored -> { }
            case ChildField.ErrorsField ignored -> { }
            // Root query / mutation fields.
            case QueryField qf -> addRootFieldEdges(fetcher, field.parentTypeName(), qf);
            case MutationField mf -> addRootFieldEdges(fetcher, field.parentTypeName(), mf);
            // Input fields own no fetcher; their input-record edges are added at the type level.
            case InputField ignored -> { }
            // Never reaches emission (diagnosed at validate time).
            case GraphitronField.UnclassifiedField ignored -> { }
        }
    }

    /**
     * Root-query edge contributions. Exhaustive over {@link QueryField}: catalog / table-method /
     * routine / lookup reads reference the target type's projection and the root conditions; node
     * lookups reference {@code NodeIdEncoder}; polymorphic reads reference each participant's
     * projection; service reads reference the target projection only for the table-bound arms.
     */
    private void addRootFieldEdges(String fetcher, String parent, QueryField field) {
        switch (field) {
            case QueryField.QueryTableField f -> { addTypeClassEdge(fetcher, f.returnType()); addConditionsEdge(fetcher, parent); }
            case QueryField.QueryLookupTableField f -> { addTypeClassEdge(fetcher, f.returnType()); addConditionsEdge(fetcher, parent); }
            case QueryField.QueryTableInterfaceField f -> { addTypeClassEdge(fetcher, f.returnType()); addConditionsEdge(fetcher, parent); }
            case QueryField.QueryTableMethodTableField f -> addTypeClassEdge(fetcher, f.returnType());
            case QueryField.QueryRoutineTableField f -> addTypeClassEdge(fetcher, f.returnType());
            case QueryField.QueryServiceTableField f -> addTypeClassEdge(fetcher, f.returnType());
            case QueryField.QueryInterfaceField ignored -> { }
            case QueryField.QueryUnionField ignored -> { }
            case QueryField.QueryServicePolymorphicField ignored -> { }
            case QueryField.QueryServiceTableInterfaceField ignored -> { }
            case QueryField.QueryServiceRecordField ignored -> { }
            // node(id:) / nodes(ids:) fields delegate to the QueryNodeFetcher dispatcher.
            case QueryField.QueryNodeField ignored -> { addNodeIdEncoderEdge(fetcher); addQueryNodeFetcherEdge(fetcher); }
            case QueryField.QueryNodesField ignored -> { addNodeIdEncoderEdge(fetcher); addQueryNodeFetcherEdge(fetcher); }
        }
    }

    /**
     * Root-mutation edge contributions. Exhaustive over {@link MutationField}. Only the two
     * table-bound service arms expose a {@link ReturnTypeRef.TableBoundReturnType} directly; the DML
     * and payload carriers reach their target-table projection through the {@code @table}-element data
     * <em>child</em> field they carry (whose edge is added by the {@link ChildField} arms above), so
     * here they contribute their root conditions and, where they encode a PK, the precise
     * {@code NodeIdEncoder} edge.
     *
     * <p>Slice-5 closes the slice-2 residual: the DML {@code ProjectedSingle}/{@code ProjectedList}
     * (and {@code Discriminated*}) arms project the {@code @table} type inline in the mutation fetcher
     * itself (no payload child field), so their {@code fetcher → typeClass} edge is now sourced from
     * the {@link no.sikt.graphitron.rewrite.model.DmlReturnExpression} the field carries (see
     * {@link #addDmlProjectionEdges}), model-sourced exactly like {@link #addTypeClassEdge}. The
     * {@link TypeSpecReferenceWalk} oracle is what falsified the omission.
     */
    private void addRootFieldEdges(String fetcher, String parent, MutationField field) {
        switch (field) {
            case MutationField.DmlTableField f -> {
                addConditionsEdge(fetcher, parent);
                addNodeIdEncoderEdge(fetcher);
                addDmlProjectionEdges(fetcher, f.returnExpression());
            }
            // R451: the routine-write fetcher projects the terminus type inline in step 2's
            // post-commit SELECT (Type.$fields(...)), exactly like the query routine fetcher.
            case MutationField.MutationRoutineWriteField f -> addTypeClassEdge(fetcher, f.returnType());
            case MutationField.MutationServiceTableField f -> addTypeClassEdge(fetcher, f.returnType());
            case MutationField.MutationServiceTableInterfaceField f -> addTypeClassEdge(fetcher, f.returnType());
            case MutationField.MutationServiceRecordField ignored -> { }
            case MutationField.MutationServicePolymorphicField ignored -> { }
            case MutationField.MutationDmlRecordField ignored -> addNodeIdEncoderEdge(fetcher);
            case MutationField.MutationBulkDmlRecordField ignored -> addNodeIdEncoderEdge(fetcher);
            case MutationField.MutationUpdatePayloadField ignored -> addConditionsEdge(fetcher, parent);
            case MutationField.MutationBulkUpdatePayloadField ignored -> addConditionsEdge(fetcher, parent);
            case MutationField.MutationDeletePayloadField ignored -> addConditionsEdge(fetcher, parent);
            case MutationField.MutationBulkDeletePayloadField ignored -> addConditionsEdge(fetcher, parent);
        }
    }

    /**
     * The DML mutation fetcher's inline projection edge, sourced from the {@link DmlReturnExpression}
     * the {@link MutationField.DmlTableField} carries. Exhaustive over the return-shape arms (the
     * drift guard extends to any new DML return shape): {@code Projected*} references the target
     * {@code @table} type's projection class; {@code Discriminated*} references each participant's
     * projection (single-table discriminated interface); {@code Encoded*} reaches only
     * {@code NodeIdEncoder}, already added for the DML arm, so it contributes no projection edge here.
     */
    private void addDmlProjectionEdges(String fetcher, DmlReturnExpression expr) {
        switch (expr) {
            case DmlReturnExpression.ProjectedSingle p -> acc.addEdge(fetcher, units.typeClass(p.returnTypeName()));
            case DmlReturnExpression.ProjectedList p -> acc.addEdge(fetcher, units.typeClass(p.returnTypeName()));
            case DmlReturnExpression.DiscriminatedSingle d -> addParticipantTypeClassEdges(fetcher, d.participants());
            case DmlReturnExpression.DiscriminatedList d -> addParticipantTypeClassEdges(fetcher, d.participants());
            case DmlReturnExpression.EncodedSingle ignored -> { }
            case DmlReturnExpression.EncodedList ignored -> { }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Type-to-type projection composition: a top-down walk mirroring TypeClassGenerator's emit seam.
    // ------------------------------------------------------------------------------------------------

    /**
     * Projection-composition edges attributed to the {@code types.<Type>} class, mirroring
     * {@link no.sikt.graphitron.rewrite.generators.TypeClassGenerator}'s emit seam exactly: only
     * {@link GraphitronType.TableType} / {@link GraphitronType.NodeType} parents get a type class
     * (the same {@code generate()} filter), and that class's {@code $fields} method composes each
     * inline {@link ChildField.TableField} / {@link ChildField.LookupTableField}'s target projection
     * via {@code Target.$fields(...)} (see
     * {@link no.sikt.graphitron.rewrite.generators.TypeClassGenerator#emitSelectionSwitch} /
     * {@link no.sikt.graphitron.rewrite.generators.InlineTableFieldEmitter}). This walk is kept
     * <em>separate</em> from {@link #addFieldEdges}: that switch keys edges off each field's immediate
     * {@code parentTypeName()} and cannot recover the hosting type class for nesting-hosted fields
     * without re-deriving nesting ancestry, whereas {@code emitSelectionSwitch} emits a nested inline
     * field's projection into the <em>outer</em> table type's class. Attributing here from the top of
     * the type is the only way to match the emitter's actual host.
     *
     * <p>The {@link no.sikt.graphitron.rewrite.generators.CompositeDecodeHelperRegistry} shared
     * "does this child inline-project a type class, and what target" predicate is a capability-lift
     * candidate ({@code InlineProjectingField} with {@code projectedTypeName()}, implemented by
     * {@code TableField}/{@code LookupTableField}, read by this walk and {@code emitSelectionSwitch});
     * named here as the collapse target, not blocking this item.
     */
    private void addTypeProjectionEdges() {
        for (var type : schema.types().values()) {
            String host = switch (type) {
                case GraphitronType.TableType t -> t.name();
                case GraphitronType.NodeType t -> t.name();
                default -> null;
            };
            if (host == null) {
                continue;
            }
            String hostClass = units.typeClass(host);
            // Blanket frozen-scaffold edge: a THROW-mode @nodeId decode helper lifted onto this
            // class (CompositeDecodeHelperRegistry) references GraphitronClientException. Blanketed
            // rather than gated on the helper because the target is a UtilSingleton.FrozenScaffold,
            // so an over-approximated edge onto it never fires a recompile (its ABI is schema-invariant).
            acc.addEdge(hostClass, graphitronClientExceptionFqcn());
            for (var field : schema.fieldsOf(host)) {
                if (field instanceof ChildField cf) {
                    addProjectionChildEdges(hostClass, cf);
                }
            }
        }
    }

    /**
     * Per-child projection dispatch, a no-{@code default} exhaustive switch over the {@link ChildField}
     * leaves (following {@link #addFieldEdges}' drift-guard discipline rather than
     * {@code emitSelectionSwitch}'s {@code default -> {}}): a future inline-projecting leaf fails to
     * compile here until its edge contribution is declared. Today the projecting set is exactly
     * {@code {TableField, LookupTableField}} plus {@link ChildField.NestingField} recursion; every
     * other leaf gets an explicit empty arm.
     *
     * <p>Edges attribute to {@code hostClass} (the hosting type class), not the field's
     * {@code parentTypeName()}: a {@code NestingField}'s children emit into the outer table type's
     * {@code $fields} method, so {@code hostClass} is threaded through the recursion unchanged.
     */
    private void addProjectionChildEdges(String hostClass, ChildField field) {
        switch (field) {
            // Inline-projecting leaves: the $fields switch arm composes Target.$fields(...) inline,
            // so this type class references the target's projection class. Its inline filters add
            // further edges (generated condition class, NodeIdEncoder decode-helper lift).
            case ChildField.TableField f -> {
                acc.addEdge(hostClass, units.typeClass(f.returnType().returnTypeName()));
                addInlineFilterEdges(hostClass, f.filters());
            }
            case ChildField.LookupTableField f -> {
                acc.addEdge(hostClass, units.typeClass(f.returnType().returnTypeName()));
                addInlineFilterEdges(hostClass, f.filters());
            }
            // Nesting projections inline into the same outer type class; recurse with hostClass fixed.
            case ChildField.NestingField f -> {
                for (var child : f.nestedFields()) {
                    addProjectionChildEdges(hostClass, child);
                }
            }
            // Scalar column subquery (InlineColumnReferenceFieldEmitter, no $fields) and the computed
            // arm (external user class via ClassName.bestGuess) reference no generated unit.
            case ChildField.ColumnField ignored -> { }
            case ChildField.ColumnReferenceField ignored -> { }
            case ChildField.ParticipantColumnReferenceField ignored -> { }
            case ChildField.CompositeColumnField ignored -> { }
            case ChildField.CompositeColumnReferenceField ignored -> { }
            case ChildField.ComputedField ignored -> { }
            // DataLoader-backed / record-parent / polymorphic / service leaves are not emitted inline
            // into this $fields method (TypeClassGenerator collects only TableField/LookupTableField);
            // their projection edges are sourced from their own <parent>Fetchers unit in addFieldEdges.
            case ChildField.SplitTableField ignored -> { }
            case ChildField.SplitLookupTableField ignored -> { }
            case ChildField.TableInterfaceField ignored -> { }
            case ChildField.ServiceTableField ignored -> { }
            case ChildField.RecordTableField ignored -> { }
            case ChildField.RecordLookupTableField ignored -> { }
            case ChildField.TableMethodField ignored -> { }
            case ChildField.RecordTableMethodField ignored -> { }
            case ChildField.InterfaceField ignored -> { }
            case ChildField.UnionField ignored -> { }
            case ChildField.ServiceRecordField ignored -> { }
            case ChildField.RecordField ignored -> { }
            case ChildField.RecordCompositeField ignored -> { }
            case ChildField.PropertyField ignored -> { }
            case ChildField.SingleRecordIdField ignored -> { }
            case ChildField.SingleRecordIdFieldFromReturning ignored -> { }
            case ChildField.ErrorsField ignored -> { }
        }
    }

    /**
     * The edges an inline {@link ChildField.TableField} / {@link ChildField.LookupTableField}'s
     * filters contribute to the hosting type class, mirroring what
     * {@code InlineTableFieldEmitter} / {@code FkTargetConditionEmitter.emitTerm} emit inline into the
     * {@code $fields} switch arm:
     *
     * <ul>
     *   <li>a Graphitron-<em>generated</em> condition method call ({@link GeneratedConditionFilter},
     *       whose {@code className()} is the generated {@code <Type>Conditions} unit) references that
     *       conditions class. Developer-supplied {@code ConditionFilter} /
     *       {@code FkTargetConditionFilter} name a consumer-authored class (not a generated unit), so
     *       they contribute no edge;</li>
     *   <li>a {@code @nodeId}-decoding filter argument lifts a decode helper onto this class, reaching
     *       {@code NodeIdEncoder} precisely (the one per-type-growing singleton).</li>
     * </ul>
     */
    private void addInlineFilterEdges(String hostClass, List<WhereFilter> filters) {
        for (var filter : filters) {
            if (filter instanceof GeneratedConditionFilter gcf) {
                acc.addEdge(hostClass, gcf.className());
            }
        }
        if (filtersDecodeNodeId(filters)) {
            addNodeIdEncoderEdge(hostClass);
        }
    }

    /**
     * True when any of {@code filters} carries a call param whose extraction decodes a {@code @nodeId}
     * argument, exactly the model predicate {@code CompositeDecodeHelperRegistry} lifts a decode helper
     * on (see {@code ArgCallEmitter.buildNodeIdDecodeExtraction}). A multi-segment path wraps the leaf
     * in a {@link CallSiteExtraction.NestedInputField}, so the leaf is inspected through it.
     */
    private static boolean filtersDecodeNodeId(List<WhereFilter> filters) {
        return filters.stream()
            .flatMap(f -> f.callParams().stream())
            .anyMatch(p -> isNodeIdDecode(p.extraction()));
    }

    private static boolean isNodeIdDecode(CallSiteExtraction extraction) {
        return extraction instanceof CallSiteExtraction.NodeIdDecodeKeys
            || (extraction instanceof CallSiteExtraction.NestedInputField nif
                && nif.leaf() instanceof CallSiteExtraction.NodeIdDecodeKeys);
    }

    private String graphitronClientExceptionFqcn() {
        return units.singleton(GeneratedUnits.SUB_SCHEMA, "GraphitronClientException");
    }

    private void addTypeClassEdge(String fetcher, ReturnTypeRef returnType) {
        if (returnType instanceof ReturnTypeRef.TableBoundReturnType t) {
            acc.addEdge(fetcher, units.typeClass(t.returnTypeName()));
        }
    }

    private void addParticipantTypeClassEdges(String fetcher, List<ParticipantRef> participants) {
        for (var p : participants) {
            // TableBacked covers both single-table (TableBound) and joined-table participants; both
            // carry the participant type name whose projection class the polymorphic fetcher reads.
            if (p instanceof ParticipantRef.TableBacked tb) {
                acc.addEdge(fetcher, units.typeClass(tb.typeName()));
            }
        }
    }

    private void addConditionsEdge(String fetcher, String parentTypeName) {
        if (hasSqlGeneratingField(parentTypeName)) {
            acc.addEdge(fetcher, units.conditions(parentTypeName));
        }
    }

    private void addNodeIdEncoderEdge(String fetcher) {
        acc.addEdge(fetcher, nodeIdEncoderFqcn());
    }

    private void addQueryNodeFetcherEdge(String fetcher) {
        acc.addEdge(fetcher, queryNodeFetcherFqcn());
    }

    private void addNodeIdEncoderEdgeIfEncoded(String fetcher, CallSiteCompaction compaction) {
        if (compaction instanceof CallSiteCompaction.NodeIdEncodeKeys) {
            addNodeIdEncoderEdge(fetcher);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Blanket (frozen singletons) and wiring-hub edges.
    // ------------------------------------------------------------------------------------------------

    /**
     * Adds (a) the blanket over-approximation edges from every fetcher into each ABI-frozen runtime
     * singleton and into the {@code GraphitronContext} interface every fetcher takes, and (b) the
     * wiring-hub edges: the schema class references every schema-shape unit and every fetcher it
     * registers; each schema-shape {@code <Type>Type} wiring class references its own {@code <Type>Fetchers}
     * (it registers that data fetcher) and {@code LightFetcher}; the facade references the schema and
     * context. These are correct superset edges whose targets are the hub / frozen scaffolding (the
     * {@code LightFetcher} / {@code GraphitronContext} edges are over-approximated onto every shape and
     * fetcher rather than only the ones that structurally use them), so they never harm pruning: the
     * targets are ABI-frozen, so slice-3 ABI-gating never fires on them. The {@link TypeSpecReferenceWalk}
     * oracle pins that these cover the real emitted wiring references.
     */
    private void addBlanketAndWiringEdges() {
        String schemaClass = units.singleton(GeneratedUnits.SUB_SCHEMA, "GraphitronSchema");
        String facade = units.rootUnit("Graphitron");
        String context = units.singleton(GeneratedUnits.SUB_SCHEMA, "GraphitronContext");
        String lightFetcher = units.singleton(GeneratedUnits.SUB_UTIL, "LightFetcher");
        String schemaShapePrefix = units.fqcn(GeneratedUnits.SUB_SCHEMA, "");
        var fetcherNodes = new java.util.LinkedHashSet<>(currentFetcherNodes());

        for (String fetcher : fetcherNodes) {
            for (var singleton : UtilSingleton.ALL) {
                if (singleton instanceof UtilSingleton.FrozenScaffold fs) {
                    acc.addEdge(fetcher, units.singleton(fs.subPackage(), fs.simpleName()));
                }
            }
            // Every fetcher method takes a GraphitronContext (a frozen interface).
            acc.addEdge(fetcher, context);
            // The schema class wires every fetcher; a fetcher ABI change recompiles the schema class.
            acc.addEdge(schemaClass, fetcher);
        }
        // The schema class wires every schema-shape unit; each shape registers its own data fetcher and
        // uses LightFetcher; the facade builds off the schema class.
        for (String node : acc.nodesSnapshot()) {
            if (node.startsWith(schemaShapePrefix) && node.endsWith(GeneratedUnits.SCHEMA_SHAPE_SUFFIX)) {
                acc.addEdge(schemaClass, node);
                String typeName = node.substring(schemaShapePrefix.length(),
                    node.length() - GeneratedUnits.SCHEMA_SHAPE_SUFFIX.length());
                String ownFetcher = units.fetchers(typeName);
                if (fetcherNodes.contains(ownFetcher)) {
                    acc.addEdge(node, ownFetcher);
                }
                acc.addEdge(node, lightFetcher);
            }
        }
        acc.addEdge(facade, schemaClass);
        acc.addEdge(facade, context);
        // R429 connection-lifecycle runtime: the facade builds the runtime, which owns the pinned
        // connection and the session-hook seam. These units are schema-invariant (ABI-frozen), so the
        // edges never drive a recompile; they are modelled precisely rather than blanketed because no
        // fetcher references them. The TypeSpecReferenceWalk oracle pins this wiring.
        String graphitronRuntime = units.singleton(GeneratedUnits.SUB_SCHEMA, "GraphitronRuntime");
        String pinnedConnection = units.singleton(GeneratedUnits.SUB_SCHEMA, "PinnedConnection");
        String sessionHook = units.singleton(GeneratedUnits.SUB_SCHEMA, "SessionHook");
        acc.addEdge(facade, graphitronRuntime);
        acc.addEdge(graphitronRuntime, pinnedConnection);
        acc.addEdge(graphitronRuntime, sessionHook);
        acc.addEdge(pinnedConnection, sessionHook);
        // R429 slice 2: the operation-typed transaction seam. The runtime's newGraphQL(schema) attaches
        // the instrumentation, which binds a DSLContext through the transaction provider over the pinned
        // connection. Same schema-invariant, ABI-frozen character as the slice-1 substrate.
        String transactionProvider = units.singleton(GeneratedUnits.SUB_SCHEMA, "GraphitronTransactionProvider");
        String connectionInstrumentation = units.singleton(GeneratedUnits.SUB_SCHEMA, "GraphitronConnectionInstrumentation");
        acc.addEdge(graphitronRuntime, connectionInstrumentation);
        acc.addEdge(connectionInstrumentation, graphitronRuntime);
        acc.addEdge(connectionInstrumentation, pinnedConnection);
        acc.addEdge(connectionInstrumentation, transactionProvider);
        addNodeLookupAndEntityDispatchEdges(schemaClass, context);
    }

    /**
     * The node-lookup / federation dispatch wiring. {@code QueryNodeFetcher} branches per typeId
     * through {@code EntityFetcherDispatch}'s select methods after peeking the id via
     * {@code NodeIdEncoder}; {@code EntityFetcherDispatch} decodes reps via {@code NodeIdEncoder} and
     * projects each node type's {@code $fields} (the per-node-type edge is added from the
     * {@link GraphitronType.NodeType} arm) plus each federation entity type's (added here from
     * {@code entitiesByType}); the schema class registers the node fetcher. Superset-safe when the
     * schema has no node or entity types: the units are then never emitted and the recompile render
     * skips them.
     */
    private void addNodeLookupAndEntityDispatchEdges(String schemaClass, String context) {
        String queryNodeFetcher = queryNodeFetcherFqcn();
        String entityDispatch = entityFetcherDispatchFqcn();
        acc.addEdge(queryNodeFetcher, nodeIdEncoderFqcn());
        acc.addEdge(queryNodeFetcher, entityDispatch);
        acc.addEdge(queryNodeFetcher, context);
        acc.addEdge(entityDispatch, nodeIdEncoderFqcn());
        acc.addEdge(entityDispatch, context);
        acc.addEdge(schemaClass, queryNodeFetcher);
        for (String entityTypeName : schema.entitiesByType().keySet()) {
            acc.addEdge(entityDispatch, units.typeClass(entityTypeName));
        }
    }

    private void addSingletonNodes() {
        // Always-emitted runtime singletons.
        for (var s : UtilSingleton.ALL) {
            acc.addNode(units.singleton(s.subPackage(), s.simpleName()));
        }
        acc.addNode(units.singleton(GeneratedUnits.SUB_UTIL, "EntityFetcherDispatch"));
        acc.addNode(units.singleton(GeneratedUnits.SUB_UTIL, "NodeIdEncoder"));
        acc.addNode(units.singleton(GeneratedUnits.SUB_SCHEMA, "GraphitronSchema"));
        acc.addNode(units.singleton(GeneratedUnits.SUB_SCHEMA, "GraphitronContext"));
        acc.addNode(units.singleton(GeneratedUnits.SUB_FETCHERS, "QueryNodeFetcher"));
        acc.addNode(units.rootUnit("Graphitron"));
        // R429: the always-emitted connection-lifecycle runtime substrate.
        acc.addNode(units.singleton(GeneratedUnits.SUB_SCHEMA, "GraphitronRuntime"));
        acc.addNode(units.singleton(GeneratedUnits.SUB_SCHEMA, "PinnedConnection"));
        acc.addNode(units.singleton(GeneratedUnits.SUB_SCHEMA, "SessionHook"));
        // R429 slice 2: the operation-typed transaction seam.
        acc.addNode(units.singleton(GeneratedUnits.SUB_SCHEMA, "GraphitronTransactionProvider"));
        acc.addNode(units.singleton(GeneratedUnits.SUB_SCHEMA, "GraphitronConnectionInstrumentation"));
    }

    private String nodeIdEncoderFqcn() {
        return units.singleton(UtilSingleton.NODE_ID_ENCODER.subPackage(), UtilSingleton.NODE_ID_ENCODER.simpleName());
    }

    private String queryNodeFetcherFqcn() {
        return units.singleton(GeneratedUnits.SUB_FETCHERS, "QueryNodeFetcher");
    }

    private String entityFetcherDispatchFqcn() {
        return units.singleton(GeneratedUnits.SUB_UTIL, "EntityFetcherDispatch");
    }

    private List<String> currentFetcherNodes() {
        String fetchersPrefix = units.fqcn(GeneratedUnits.SUB_FETCHERS, "");
        return acc.nodesSnapshot().stream()
            .filter(n -> n.startsWith(fetchersPrefix) && n.endsWith(GeneratedUnits.FETCHERS_SUFFIX))
            .toList();
    }
}
