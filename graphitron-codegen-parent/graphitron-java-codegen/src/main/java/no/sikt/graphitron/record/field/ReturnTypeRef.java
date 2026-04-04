package no.sikt.graphitron.record.field;

import org.jooq.Table;

/**
 * Outcome of resolving the return type name of a {@link QueryField.TableQueryField}
 * against the classified {@link no.sikt.graphitron.record.GraphitronSchema}.
 *
 * <p>{@link ResolvedReturnType} — the named type exists. {@code table} is the jOOQ
 * {@link Table} when the return type is a
 * {@link no.sikt.graphitron.record.type.GraphitronType.TableType} with a
 * {@link no.sikt.graphitron.record.type.TableRef.ResolvedTable}, or {@code null} when
 * the table is unresolved. A null table skips the deterministic-ordering PK check;
 * the unresolved table is reported by the type validator instead.
 *
 * <p>{@link UnresolvedReturnType} — the named type does not exist in the schema.
 * The {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports an error.
 */
public sealed interface ReturnTypeRef permits ReturnTypeRef.ResolvedReturnType, ReturnTypeRef.UnresolvedReturnType {

    String returnTypeName();

    /**
     * The return type was found in the schema. {@code table} is non-null when its jOOQ table
     * is resolved; null when the {@code TableType} carries an {@code UnresolvedTable}.
     */
    record ResolvedReturnType(String returnTypeName, Table<?> table) implements ReturnTypeRef {}

    /**
     * The return type name does not correspond to any classified type in the schema.
     * The validator reports an error.
     */
    record UnresolvedReturnType(String returnTypeName) implements ReturnTypeRef {}
}
