package no.sikt.graphitron.record.field;

import no.sikt.graphitron.record.type.TableRef;

/**
 * Outcome of resolving the return type name of a field against the classified
 * {@link no.sikt.graphitron.record.GraphitronSchema}, combined with the
 * {@link FieldWrapper} that describes how the element type is wrapped (single, list, or connection).
 *
 * <p>Together, {@code returnTypeName} + {@code wrapper} fully describe a field's declared GraphQL
 * return type: e.g. {@code [Film!]!} is
 * {@code TableBoundReturnType("Film", filmTable, List(false, false, ...))} and
 * {@code Film} is {@code TableBoundReturnType("Film", filmTable, Single(true))}.
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
 */
public sealed interface ReturnTypeRef permits ReturnTypeRef.TableBoundReturnType, ReturnTypeRef.OtherReturnType {

    String returnTypeName();

    /** The wrapper around the element type — {@link FieldWrapper.Single}, {@link FieldWrapper.List}, or {@link FieldWrapper.Connection}. */
    FieldWrapper wrapper();

    /**
     * The return type was found in the schema as a
     * {@link no.sikt.graphitron.record.type.GraphitronType.TableType}.
     * {@code table} is the outcome of resolving the type's {@code @table} directive.
     */
    record TableBoundReturnType(String returnTypeName, TableRef table, FieldWrapper wrapper) implements ReturnTypeRef {}

    /**
     * The return type was found in the schema but is not a table-backed type.
     */
    record OtherReturnType(String returnTypeName, FieldWrapper wrapper) implements ReturnTypeRef {}

}
