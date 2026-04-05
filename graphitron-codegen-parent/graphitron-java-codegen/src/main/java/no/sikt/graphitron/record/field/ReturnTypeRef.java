package no.sikt.graphitron.record.field;

import no.sikt.graphitron.record.type.TableRef;

/**
 * Outcome of resolving the return type name of a field against the classified
 * {@link no.sikt.graphitron.record.GraphitronSchema}.
 *
 * <p>{@link TableBoundReturnType} — the named type exists and is a
 * {@link no.sikt.graphitron.record.type.GraphitronType.TableType}. {@code table} carries the
 * outcome of resolving the type's {@code @table} directive: a
 * {@link no.sikt.graphitron.record.type.TableRef.ResolvedTable} when the table was found in the
 * jOOQ catalog, or an {@link no.sikt.graphitron.record.type.TableRef.UnresolvedTable} when it was
 * not. An unresolved table is reported by the type validator; field validators skip checks that
 * require a resolved table.
 *
 * <p>{@link OtherReturnType} — the named type exists but is not a
 * {@link no.sikt.graphitron.record.type.GraphitronType.TableType} (e.g. a result type,
 * interface, or union).
 *
 * <p>{@link UnresolvedReturnType} — the named type does not exist in the schema. The
 * {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports an error.
 */
public sealed interface ReturnTypeRef permits ReturnTypeRef.TableBoundReturnType, ReturnTypeRef.OtherReturnType, ReturnTypeRef.UnresolvedReturnType {

    String returnTypeName();

    /**
     * The return type was found in the schema as a
     * {@link no.sikt.graphitron.record.type.GraphitronType.TableType}.
     * {@code table} is the outcome of resolving the type's {@code @table} directive.
     */
    record TableBoundReturnType(String returnTypeName, TableRef table) implements ReturnTypeRef {}

    /**
     * The return type was found in the schema but is not a table-backed type.
     */
    record OtherReturnType(String returnTypeName) implements ReturnTypeRef {}

    /**
     * The return type name does not correspond to any classified type in the schema.
     * The validator reports an error.
     */
    record UnresolvedReturnType(String returnTypeName) implements ReturnTypeRef {}
}
