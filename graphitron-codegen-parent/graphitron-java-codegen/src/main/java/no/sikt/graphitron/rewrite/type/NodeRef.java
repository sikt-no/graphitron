package no.sikt.graphitron.rewrite.type;

import no.sikt.graphitron.rewrite.field.ColumnRef;

import java.util.List;

/**
 * The {@code @node} directive decoration on a table-backed GraphQL type.
 *
 * <p>A {@code NodeRef} is only constructed when all {@code keyColumns} entries can be resolved
 * against the jOOQ table. When any key column cannot be matched the containing type is classified
 * as {@link GraphitronType.UnclassifiedType} at build time.
 *
 * <p>{@code typeId} is the value of the {@code typeId} argument on the {@code @node} directive,
 * or {@code null} when the argument was omitted.
 *
 * <p>{@code keyColumns} is the resolved list of {@code keyColumns} argument entries. Each entry is
 * a {@link ColumnRef} whose {@code sqlName} is the column name as written in the directive.
 * An empty list means the argument was omitted, in which case the primary key is used at
 * code-generation time.
 *
 * <p>The table that this node decoration belongs to is carried separately on
 * {@link GraphitronType.TableType} alongside this {@code NodeRef}.
 */
public record NodeRef(String typeId, List<ColumnRef> keyColumns) {}
