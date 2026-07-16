package no.sikt.graphitron.mcp;

import no.sikt.graphitron.rewrite.catalog.CatalogFacts;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.FieldClassification;
import no.sikt.graphitron.rewrite.catalog.TypeClassification;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Computes the forward edges of one {@link NodeRef} from the live
 * projections. The field- and type-node arms are exhaustive {@code switch}es over the
 * {@link FieldClassification} / {@link TypeClassification} sealed permits with <b>no
 * {@code default}</b>, mirroring {@code SchemaView.mapFieldClassification}: a new permit fails this
 * to compile, the desired cross-module drift guard. Every existing permit that produces no edge
 * lands in an explicit arm, never swept by a {@code default}; the {@link #NO_EDGE_FIELDS} /
 * {@link #NO_EDGE_TYPES} sets make those deliberate no-edge choices reviewable (and the
 * {@code EdgeCoverageTest} pins that the sets partition the permit space).
 *
 * <p>Owns the two reconciliation gaps the classifier leaves open: a <em>bare</em> SQL table name is
 * resolved to the schema-qualified {@code CatalogFacts} key through {@link CatalogFacts#resolve},
 * and a {@code (class, name)} method pair (the classifier carries no arity) is reconciled to
 * arity-bearing refs by looking the pair up in the external-reference scan and emitting one edge per
 * matching overload. No new arity is invented; the edge names exactly the method refs the code tools
 * emit.
 */
final class EdgeProducer {

    private EdgeProducer() {}

    /**
     * The live projections one edge computation reads: the frozen catalog facts (table / column ID
     * resolution and FK structure) and the external-reference scan (method-arity reconciliation).
     */
    record Context(CatalogFacts facts, List<CompletionData.ExternalReference> externalReferences) {}

    // ---- field-node forward edges (exhaustive over FieldClassification permits) ----

    static List<Edge> fieldEdges(FieldClassification c, Context ctx) {
        var edges = new ArrayList<Edge>();
        switch (c) {
            case FieldClassification.Column f ->
                backsColumn(edges, f.tableName(), f.columnName(), ctx);
            case FieldClassification.ColumnReference f -> {
                backsColumn(edges, f.tableName(), f.columnName(), ctx);
                referencesTable(edges, f.tableName(), f.joinPath(), ctx);
            }
            case FieldClassification.CompositeColumn f -> {
                for (var col : f.columnNames()) backsColumn(edges, f.tableName(), col, ctx);
            }
            case FieldClassification.CompositeColumnReference f -> {
                for (var col : f.columnNames()) backsColumn(edges, f.tableName(), col, ctx);
                referencesTable(edges, f.tableName(), f.joinPath(), ctx);
            }
            case FieldClassification.ParticipantCrossTable f ->
                backsColumn(edges, f.targetTableName(), f.columnName(), ctx);
            case FieldClassification.TableTarget f ->
                tableTargetEdge(edges, f.tableName(), f.joinPath(), ctx);
            case FieldClassification.RecordTableTarget f ->
                tableTargetEdge(edges, f.tableName(), f.joinPath(), ctx);
            case FieldClassification.TableMethod f -> {
                resolvesMethod(edges, f.methodClassName(), f.methodName(), ctx);
                targetsTable(edges, f.tableName(), ctx);
            }
            case FieldClassification.TableInterface f -> {
                backsColumn(edges, f.tableName(), f.discriminatorColumn(), ctx);
                participates(edges, f.participantTypeNames());
            }
            case FieldClassification.Polymorphic f -> participates(edges, f.participantTypeNames());
            case FieldClassification.ServiceBacked f -> {
                resolvesMethod(edges, f.methodClassName(), f.methodName(), ctx);
                if (f.tableBound()) targetsTable(edges, f.tableName(), ctx);
            }
            case FieldClassification.Computed f ->
                resolvesMethod(edges, f.methodClassName(), f.methodName(), ctx);
            case FieldClassification.InputUnbound f ->
                // Edge-bearing only when an explicit @condition populated the method pair; the
                // bare cascade-admitted case carries no method and produces no edge.
                resolvesMethod(edges, f.methodClassName(), f.methodName(), ctx);
            case FieldClassification.SingleRecordId f -> targetsTable(edges, f.tableName(), ctx);
            case FieldClassification.QueryTable f -> targetsTable(edges, f.tableName(), ctx);
            case FieldClassification.QueryTableMethod f -> {
                resolvesMethod(edges, f.methodClassName(), f.methodName(), ctx);
                targetsTable(edges, f.tableName(), ctx);
            }
            case FieldClassification.QueryTableInterface f -> {
                targetsTable(edges, f.tableName(), ctx);
                backsColumn(edges, f.tableName(), f.discriminatorColumn(), ctx);
                participates(edges, f.participantTypeNames());
            }
            case FieldClassification.QueryPolymorphic f -> participates(edges, f.participantTypeNames());
            case FieldClassification.QueryService f -> {
                resolvesMethod(edges, f.methodClassName(), f.methodName(), ctx);
                if (f.tableBound()) targetsTable(edges, f.tableName(), ctx);
            }
            case FieldClassification.DmlMutation f -> targetsTable(edges, f.tableName(), ctx);
            case FieldClassification.MutationService f -> {
                resolvesMethod(edges, f.methodClassName(), f.methodName(), ctx);
                if (f.tableBound()) targetsTable(edges, f.tableName(), ctx);
            }
            case FieldClassification.DmlRecord f -> targetsTable(edges, f.tableName(), ctx);

            // ---- deliberate no-edge arms (kept explicit; never swept by a default) ----
            case FieldClassification.Nesting ignored -> { }
            case FieldClassification.RecordOrProperty ignored -> { }
            case FieldClassification.Errors ignored -> { }
            case FieldClassification.SingleRecordIdFromReturning ignored -> { }
            case FieldClassification.QueryNode ignored -> { }
            case FieldClassification.Unclassified ignored -> { }
        }
        return edges;
    }

    // ---- type-node forward edges (exhaustive over TypeClassification permits) ----

    static List<Edge> typeEdges(TypeClassification c, Context ctx) {
        var edges = new ArrayList<Edge>();
        switch (c) {
            case TypeClassification.Table t -> targetsTable(edges, t.tableName(), ctx);
            case TypeClassification.Node n -> targetsTable(edges, n.tableName(), ctx);
            case TypeClassification.TableInterface ti -> {
                targetsTable(edges, ti.tableName(), ctx);
                backsColumn(edges, ti.tableName(), ti.discriminatorColumn(), ctx);
                participates(edges, ti.participantTypeNames());
            }
            case TypeClassification.Interface i -> participates(edges, i.participantTypeNames());
            case TypeClassification.Union u -> participates(edges, u.participantTypeNames());
            case TypeClassification.JooqTableRecord r -> targetsTable(edges, r.tableName(), ctx);
            case TypeClassification.JooqTableRecordInput r -> targetsTable(edges, r.tableName(), ctx);
            case TypeClassification.TableInput t -> targetsTable(edges, t.tableName(), ctx);

            // ---- deliberate no-edge arms ----
            case TypeClassification.JavaRecord ignored -> { }
            case TypeClassification.JavaRecordInput ignored -> { }
            case TypeClassification.JooqRecord ignored -> { }
            case TypeClassification.JooqRecordInput ignored -> { }
            case TypeClassification.PojoResult ignored -> { }
            case TypeClassification.PojoInput ignored -> { }
            case TypeClassification.Root ignored -> { }
            case TypeClassification.Connection ignored -> { }
            case TypeClassification.Edge ignored -> { }
            case TypeClassification.PageInfo ignored -> { }
            case TypeClassification.Error ignored -> { }
            case TypeClassification.Enum ignored -> { }
            case TypeClassification.Scalar ignored -> { }
            case TypeClassification.PlainObject ignored -> { }
            case TypeClassification.Unclassified ignored -> { }
        }
        return edges;
    }

    // ---- table-node forward edges: outgoing FKs (table -> table) ----

    /** A table's outbound FK edges: one {@code REFERENCES} per outgoing FK, target the FK's table. */
    static List<Edge> outgoingFkEdges(CatalogFacts.Table table) {
        var edges = new ArrayList<Edge>();
        for (var fk : table.foreignKeys().outgoing()) {
            var target = tableNodeFromQualified(fk.targetTable());
            edges.add(new Edge(EdgeKind.REFERENCES, target,
                List.of(new FieldClassification.FkStep(fk.targetTable(), fk.constraintName()))));
        }
        return edges;
    }

    /** A table's inbound FK edges: one {@code REFERENCES} per incoming FK, target the source table. */
    static List<Edge> incomingFkEdges(CatalogFacts.Table table) {
        var edges = new ArrayList<Edge>();
        for (var fk : table.foreignKeys().incoming()) {
            var target = tableNodeFromQualified(fk.sourceTable());
            edges.add(new Edge(EdgeKind.REFERENCES, target,
                List.of(new FieldClassification.FkStep(fk.sourceTable(), fk.constraintName()))));
        }
        return edges;
    }

    // ---- edge builders ----

    private static void backsColumn(List<Edge> edges, String bareTable, String column, Context ctx) {
        if (bareTable == null || column == null) return;
        edges.add(Edge.direct(EdgeKind.BACKS, new NodeRef.ColumnNode(resolveTable(bareTable, ctx), column)));
    }

    private static void targetsTable(List<Edge> edges, String bareTable, Context ctx) {
        if (bareTable == null) return;
        edges.add(Edge.direct(EdgeKind.TARGETS, resolveTable(bareTable, ctx)));
    }

    /**
     * A table-target field: the {@code TARGETS} / {@code REFERENCES} split falls out of the
     * classifier's join-path distinction. A direct bind (empty {@code joinPath}) is {@code TARGETS};
     * a table reached through FK hops is {@code REFERENCES} carrying those hops.
     */
    private static void tableTargetEdge(List<Edge> edges, String bareTable, List<FieldClassification.FkStep> joinPath, Context ctx) {
        if (bareTable == null) return;
        var target = resolveTable(bareTable, ctx);
        if (joinPath.isEmpty()) {
            edges.add(Edge.direct(EdgeKind.TARGETS, target));
        } else {
            edges.add(new Edge(EdgeKind.REFERENCES, target, joinPath));
        }
    }

    /** A {@code REFERENCES} edge to the terminal table the reference's join path reaches. */
    private static void referencesTable(List<Edge> edges, String terminalTable, List<FieldClassification.FkStep> joinPath, Context ctx) {
        if (terminalTable == null) return;
        edges.add(new Edge(EdgeKind.REFERENCES, resolveTable(terminalTable, ctx), joinPath));
    }

    private static void participates(List<Edge> edges, List<String> participantTypeNames) {
        for (var name : participantTypeNames) {
            edges.add(Edge.direct(EdgeKind.PARTICIPATES, new NodeRef.TypeNode(name)));
        }
    }

    /**
     * Reconciles the classifier's arity-free {@code (class, name)} method pair against the
     * external-reference scan, emitting one {@code RESOLVES} edge per matching arity-distinct
     * overload. A pair with no match in the scan (the class was not scanned) produces no edge rather
     * than an invented arity.
     */
    private static void resolvesMethod(List<Edge> edges, String className, String methodName, Context ctx) {
        if (className == null || methodName == null) return;
        var arities = new LinkedHashSet<Integer>();
        for (var ref : ctx.externalReferences()) {
            if (!ref.className().equals(className)) continue;
            for (var m : ref.methods()) {
                if (m.name().equals(methodName)) arities.add(m.parameters().size());
            }
        }
        for (int arity : arities) {
            edges.add(Edge.direct(EdgeKind.RESOLVES, new NodeRef.MethodNode(className, methodName, arity)));
        }
    }

    // ---- bare-name reconciliation ----

    /**
     * Resolves a bare SQL table name to the schema-qualified {@link NodeRef.TableNode} the
     * catalog tools accept, via {@link CatalogFacts#resolve}. A name that resolves to zero or more
     * than one catalog table degrades to an unqualified {@code TableNode} (the producer cannot
     * choose a schema); the {@code edges} tool surfaces that gap on the <em>queried</em> node through
     * the {@code ambiguous} / {@code notFound} arms, but forward edges off a field still land on a
     * best-effort node so the traversal is not silently dropped.
     */
    private static NodeRef.TableNode resolveTable(String bareTable, Context ctx) {
        return switch (ctx.facts().resolve(bareTable, Optional.empty())) {
            case CatalogFacts.TableResolution.Resolved r ->
                new NodeRef.TableNode(r.table().schema(), r.table().name());
            case CatalogFacts.TableResolution.Ambiguous ignored -> new NodeRef.TableNode("", bareTable);
            case CatalogFacts.TableResolution.NotFound ignored -> new NodeRef.TableNode("", bareTable);
        };
    }

    /** Splits a schema-qualified {@code schema.table} catalog ID back into a {@link NodeRef.TableNode}. */
    private static NodeRef.TableNode tableNodeFromQualified(String qualified) {
        int dot = qualified.indexOf('.');
        return dot < 0
            ? new NodeRef.TableNode("", qualified)
            : new NodeRef.TableNode(qualified.substring(0, dot), qualified.substring(dot + 1));
    }

    // ---- coverage-pin partitions (reviewable companion to the no-default switches) ----

    /** {@link FieldClassification} permits whose arm can produce at least one edge. */
    static final Set<Class<? extends FieldClassification>> EDGE_BEARING_FIELDS = Set.of(
        FieldClassification.Column.class,
        FieldClassification.ColumnReference.class,
        FieldClassification.CompositeColumn.class,
        FieldClassification.CompositeColumnReference.class,
        FieldClassification.ParticipantCrossTable.class,
        FieldClassification.TableTarget.class,
        FieldClassification.RecordTableTarget.class,
        FieldClassification.TableMethod.class,
        FieldClassification.TableInterface.class,
        FieldClassification.Polymorphic.class,
        FieldClassification.ServiceBacked.class,
        FieldClassification.Computed.class,
        FieldClassification.InputUnbound.class,
        FieldClassification.SingleRecordId.class,
        FieldClassification.QueryTable.class,
        FieldClassification.QueryTableMethod.class,
        FieldClassification.QueryTableInterface.class,
        FieldClassification.QueryPolymorphic.class,
        FieldClassification.QueryService.class,
        FieldClassification.DmlMutation.class,
        FieldClassification.MutationService.class,
        FieldClassification.DmlRecord.class);

    /** {@link FieldClassification} permits deliberately mapped to no edge. */
    static final Set<Class<? extends FieldClassification>> NO_EDGE_FIELDS = Set.of(
        FieldClassification.Nesting.class,
        FieldClassification.RecordOrProperty.class,
        FieldClassification.Errors.class,
        FieldClassification.SingleRecordIdFromReturning.class,
        FieldClassification.QueryNode.class,
        FieldClassification.Unclassified.class);

    /** {@link TypeClassification} permits whose arm can produce at least one edge. */
    static final Set<Class<? extends TypeClassification>> EDGE_BEARING_TYPES = Set.of(
        TypeClassification.Table.class,
        TypeClassification.Node.class,
        TypeClassification.TableInterface.class,
        TypeClassification.Interface.class,
        TypeClassification.Union.class,
        TypeClassification.JooqTableRecord.class,
        TypeClassification.JooqTableRecordInput.class,
        TypeClassification.TableInput.class);

    /** {@link TypeClassification} permits deliberately mapped to no edge. */
    static final Set<Class<? extends TypeClassification>> NO_EDGE_TYPES = Set.of(
        TypeClassification.JavaRecord.class,
        TypeClassification.JavaRecordInput.class,
        TypeClassification.JooqRecord.class,
        TypeClassification.JooqRecordInput.class,
        TypeClassification.PojoResult.class,
        TypeClassification.PojoInput.class,
        TypeClassification.Root.class,
        TypeClassification.Connection.class,
        TypeClassification.Edge.class,
        TypeClassification.PageInfo.class,
        TypeClassification.Error.class,
        TypeClassification.Enum.class,
        TypeClassification.Scalar.class,
        TypeClassification.PlainObject.class,
        TypeClassification.Unclassified.class);
}
