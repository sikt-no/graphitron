package no.sikt.graphitron.mcp;

/**
 * R374 (D-D) — the sealed node-ID model the {@code edges} tool walks. One permit per stable-ID
 * grammar the structured tools already emit and accept: a schema coordinate ({@code Type} /
 * {@code Type.field}), a schema-qualified table ID ({@code schema.table}), a column-of-table ID
 * ({@code schema.table:column}), a method ref ({@code fqcn#method/arity}), and a class FQN
 * ({@code fqcn}).
 *
 * <p>This is "generation-thinking" and "wire-format encoding is a boundary concern": the producer
 * already holds each ID's parts as resolved values (a resolved schema + table name off
 * {@code CatalogFacts}, a {@code (className, method, arity)} triple off the external-reference
 * scan), so the model carries them <em>structured</em> and composes the wire string only here, at
 * the boundary, through the shared {@link McpWire} composers ({@link McpWire#methodRef} /
 * {@link McpWire#columnId}). An agent reads a neighbour's {@link #wireId()} off one result and
 * re-selects it by {@link #kind()} on the next {@code edges} call.
 */
sealed interface NodeRef
    permits NodeRef.TypeNode, NodeRef.FieldNode, NodeRef.TableNode,
            NodeRef.ColumnNode, NodeRef.MethodNode, NodeRef.ClassNode {

    /** The stable string an agent sees and can pass back on the next call. */
    String wireId();

    /** The selector kind an agent re-selects this node by: {@code type} / {@code field} / etc. */
    String kind();

    /** A schema type, by its SDL name. Produced and accepted by the {@code schema} tool. */
    record TypeNode(String typeName) implements NodeRef {
        @Override public String wireId() { return typeName; }
        @Override public String kind() { return "type"; }
    }

    /** A schema field, by its {@code Type.field} coordinate. Produced by the {@code schema} tool. */
    record FieldNode(String coordinate) implements NodeRef {
        @Override public String wireId() { return coordinate; }
        @Override public String kind() { return "field"; }
    }

    /**
     * A catalog table, carried as its resolved {@code (schema, name)} parts; the {@code wireId()} is
     * the schema-qualified {@code schema.table} ID {@code catalog.describe} accepts. A blank schema
     * (a bare name the producer could not uniquely resolve) degrades the wire form to the bare name.
     */
    record TableNode(String schema, String name) implements NodeRef {
        @Override public String wireId() {
            return schema == null || schema.isBlank() ? name : schema + "." + name;
        }
        @Override public String kind() { return "table"; }
    }

    /**
     * A column on a table, carried as its {@link TableNode} + bare SQL column name; the
     * {@code wireId()} composes {@code schema.table:column} through {@link McpWire#columnId}.
     */
    record ColumnNode(TableNode table, String sqlColumn) implements NodeRef {
        @Override public String wireId() {
            return McpWire.columnId(table.wireId(), sqlColumn);
        }
        @Override public String kind() { return "column"; }
    }

    /**
     * A consumer method, carried as its {@code (fqcn, method, arity)} triple; the {@code wireId()}
     * composes {@code fqcn#method/arity} through {@link McpWire#methodRef}, matching the ref the
     * {@code services} / {@code conditions} tools emit exactly.
     */
    record MethodNode(String fqcn, String method, int arity) implements NodeRef {
        @Override public String wireId() {
            return McpWire.methodRef(fqcn, method, arity);
        }
        @Override public String kind() { return "method"; }
    }

    /** A consumer class, by its FQN. Produced by the {@code services} / {@code records} tools. */
    record ClassNode(String fqcn) implements NodeRef {
        @Override public String wireId() { return fqcn; }
        @Override public String kind() { return "class"; }
    }
}
