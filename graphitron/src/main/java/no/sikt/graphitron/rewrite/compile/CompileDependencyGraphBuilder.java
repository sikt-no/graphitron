package no.sikt.graphitron.rewrite.compile;

import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.CallSiteCompaction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.SqlGeneratingField;

import java.util.List;

/**
 * R410 slice 2 — produces the {@link CompileDependencyGraph} by coarsening the classified model.
 * This is the model-sourced arm of the sourcing seam: the one method
 * ({@link #fromModel(GraphitronSchema, String)}) that turns the classified {@link GraphitronSchema}
 * into a file-level graph, mirroring {@code CatalogBuilder.projectFieldClassification}'s discipline
 * of a pure exhaustive-switch projection over the same leaves the emitter reads (no emitter-reference
 * logic reconstructed here).
 *
 * <p>Two exhaustive switches carry the one-model drift guard: {@link #addTypeNodes} over the
 * {@link GraphitronType} leaves (a new type variant fails to compile until its file contribution is
 * declared) and {@link #addFieldEdges} over the {@link GraphitronField} leaves (a new field variant
 * fails to compile until its reference contribution is declared). The R333 step (a later item)
 * re-targets these switches onto the method graph without changing this consumer.
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
            case QueryField.QueryNodeField ignored -> addNodeIdEncoderEdge(fetcher);
            case QueryField.QueryNodesField ignored -> addNodeIdEncoderEdge(fetcher);
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
     * <p>Known slice-2 boundary: the DML {@code ProjectedSingle}/{@code ProjectedList} arms that
     * project the {@code @table} type inline in the mutation fetcher itself (no payload child field)
     * do not yet contribute the direct {@code fetcher → typeClass} edge, because the target type name
     * lives behind {@link no.sikt.graphitron.rewrite.model.DmlReturnExpression} rather than a
     * {@code returnType} slot. The {@link TypeSpecReferenceWalk} completeness oracle (slice 5) is
     * where that residual is falsified and closed.
     */
    private void addRootFieldEdges(String fetcher, String parent, MutationField field) {
        switch (field) {
            case MutationField.DmlTableField ignored -> { addConditionsEdge(fetcher, parent); addNodeIdEncoderEdge(fetcher); }
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
     * singleton, and (b) the wiring-hub edges: the schema class references every schema-shape unit and
     * every fetcher it registers, each schema-shape unit references its own fetcher, and the facade
     * references the schema and context. These are correct superset edges whose targets are the
     * hub / frozen scaffolding, so they never harm pruning (see {@link UtilSingleton}).
     */
    private void addBlanketAndWiringEdges() {
        String schemaClass = units.singleton(GeneratedUnits.SUB_SCHEMA, "GraphitronSchema");
        String facade = units.rootUnit("Graphitron");
        String context = units.singleton(GeneratedUnits.SUB_SCHEMA, "GraphitronContext");
        String schemaShapePrefix = units.fqcn(GeneratedUnits.SUB_SCHEMA, "");

        for (String fetcher : currentFetcherNodes()) {
            for (var singleton : UtilSingleton.ALL) {
                if (singleton instanceof UtilSingleton.FrozenScaffold fs) {
                    acc.addEdge(fetcher, units.singleton(fs.subPackage(), fs.simpleName()));
                }
            }
            // The schema class wires every fetcher; a fetcher ABI change recompiles the schema class.
            acc.addEdge(schemaClass, fetcher);
        }
        // The schema class also wires every schema-shape unit; the facade builds off the schema class.
        for (String node : acc.nodesSnapshot()) {
            if (node.startsWith(schemaShapePrefix) && node.endsWith(GeneratedUnits.SCHEMA_SHAPE_SUFFIX)) {
                acc.addEdge(schemaClass, node);
            }
        }
        acc.addEdge(facade, schemaClass);
        acc.addEdge(facade, context);
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
    }

    private String nodeIdEncoderFqcn() {
        return units.singleton(UtilSingleton.NODE_ID_ENCODER.subPackage(), UtilSingleton.NODE_ID_ENCODER.simpleName());
    }

    private List<String> currentFetcherNodes() {
        String fetchersPrefix = units.fqcn(GeneratedUnits.SUB_FETCHERS, "");
        return acc.nodesSnapshot().stream()
            .filter(n -> n.startsWith(fetchersPrefix) && n.endsWith(GeneratedUnits.FETCHERS_SUFFIX))
            .toList();
    }
}
