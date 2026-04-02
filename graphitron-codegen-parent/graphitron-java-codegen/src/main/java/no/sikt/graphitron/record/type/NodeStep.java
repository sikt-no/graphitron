package no.sikt.graphitron.record.type;

/**
 * Represents whether a {@link TableType} carries a {@code @node} directive.
 *
 * <p>The sealed hierarchy distinguishes two states:
 * <ul>
 *   <li>{@link NoNode} — no {@code @node} directive on the type.</li>
 *   <li>{@link NodeDirective} — {@code @node} is present; carries the optional
 *       {@code typeId} and the list of key columns, each resolved against the
 *       jOOQ table via a {@link KeyColumnStep}.</li>
 * </ul>
 */
public sealed interface NodeStep permits NoNode, NodeDirective {}
