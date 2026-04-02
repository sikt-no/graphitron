package no.sikt.graphitron.record.type;

import java.util.List;

/**
 * A {@link NodeStep} indicating that the type carries a {@code @node} directive.
 *
 * <p>{@code typeId} is the value of the {@code typeId} argument, or {@code null} when
 * the argument was omitted.
 *
 * <p>{@code keyColumns} is the resolved list of {@code keyColumns} argument entries. Each
 * entry is either a {@link ResolvedKeyColumn} (column found in the jOOQ table) or an
 * {@link UnresolvedKeyColumn} (column name could not be matched). An empty list means the
 * argument was omitted, in which case the primary key is used at code-generation time.
 */
public record NodeDirective(String typeId, List<KeyColumnStep> keyColumns) implements NodeStep {}
