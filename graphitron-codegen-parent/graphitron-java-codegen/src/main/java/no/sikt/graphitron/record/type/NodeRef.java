package no.sikt.graphitron.record.type;

import java.util.List;

/**
 * Represents whether a {@link GraphitronType.TableType} carries a {@code @node} directive.
 *
 * <p>The sealed hierarchy distinguishes two states:
 * <ul>
 *   <li>{@link NoNode} — no {@code @node} directive on the type.</li>
 *   <li>{@link NodeDirective} — {@code @node} is present; carries the optional
 *       {@code typeId} and the list of key columns, each resolved against the
 *       jOOQ table via a {@link KeyColumnRef}.</li>
 * </ul>
 */
public sealed interface NodeRef permits NodeRef.NoNode, NodeRef.NodeDirective {

    /**
     * A {@link NodeRef} indicating that the type has no {@code @node} directive.
     */
    record NoNode() implements NodeRef {}

    /**
     * A {@link NodeRef} indicating that the type carries a {@code @node} directive.
     *
     * <p>{@code typeId} is the value of the {@code typeId} argument, or {@code null} when
     * the argument was omitted.
     *
     * <p>{@code keyColumns} is the resolved list of {@code keyColumns} argument entries. Each
     * entry is either a {@link KeyColumnRef.ResolvedKeyColumn} (column found in the jOOQ table)
     * or a {@link KeyColumnRef.UnresolvedKeyColumn} (column name could not be matched). An empty
     * list means the argument was omitted, in which case the primary key is used at
     * code-generation time.
     */
    record NodeDirective(String typeId, List<KeyColumnRef> keyColumns) implements NodeRef {}
}
