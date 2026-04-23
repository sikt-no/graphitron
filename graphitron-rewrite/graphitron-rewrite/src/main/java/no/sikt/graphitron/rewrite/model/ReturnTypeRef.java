package no.sikt.graphitron.rewrite.model;


/**
 * Outcome of resolving the return type name of a field against the classified
 * {@link no.sikt.graphitron.rewrite.GraphitronSchema}, combined with the
 * {@link FieldWrapper} that describes how the element type is wrapped (single, list, or connection).
 *
 * <p>{@link TableBoundReturnType} — Graphitron generates the SQL query. The named type is a
 * {@link no.sikt.graphitron.rewrite.model.GraphitronType.TableType} or
 * {@link no.sikt.graphitron.rewrite.model.GraphitronType.TableInterfaceType}, or the field
 * inherits its parent's table context ({@code NestingField}). {@code table} is always a fully
 * resolved {@link no.sikt.graphitron.rewrite.model.TableRef}; when the table name in
 * {@code @table} cannot be found in the jOOQ catalog the builder classifies the containing
 * field as {@link UnclassifiedField} instead of emitting a {@link TableBoundReturnType}.
 *
 * <p>{@link ResultReturnType} — the return type is a {@code @record}-annotated type. No SQL is
 * generated; the generator accesses properties on the parent result object. The specific backing
 * Java representation is known from the parent
 * {@link no.sikt.graphitron.rewrite.model.GraphitronType.ResultType} sub-type.
 *
 * <p>{@link ScalarReturnType} — the return type is a scalar, enum, or a type name that does not
 * resolve to any classified schema type (e.g., directive-argument type names used by
 * {@code @nodeId(typeName:)}). No SQL is generated.
 *
 * <p>{@link PolymorphicReturnType} — stub for multi-table polymorphic returns (GraphQL interfaces
 * and unions whose members are each backed by separate tables, and Relay/Federation built-in
 * fields). Code generation for this case is not yet implemented.
 */
public sealed interface ReturnTypeRef
    permits ReturnTypeRef.TableBoundReturnType, ReturnTypeRef.ResultReturnType,
            ReturnTypeRef.ScalarReturnType, ReturnTypeRef.PolymorphicReturnType {

    String returnTypeName();

    /** The wrapper around the element type — {@link FieldWrapper.Single}, {@link FieldWrapper.List}, or {@link FieldWrapper.Connection}. */
    FieldWrapper wrapper();

    /**
     * Graphitron generates the SQL query. The named type is a table-backed type
     * or the field inherits its parent's table context.
     * {@code table} is the outcome of resolving the type's {@code @table} directive.
     */
    record TableBoundReturnType(String returnTypeName, TableRef table, FieldWrapper wrapper) implements ReturnTypeRef {}

    /**
     * The return type is a {@code @record}-annotated type — a result-mapped Java class provided
     * by the developer. No SQL is generated; the generator accesses properties on the parent
     * result object.
     *
     * <p>{@code fqClassName} is the binary class name of the backing Java class, taken directly
     * from the corresponding {@link no.sikt.graphitron.rewrite.model.GraphitronType.ResultType}
     * at build time. May be {@code null} when the backing class was not specified in the directive.
     */
    record ResultReturnType(String returnTypeName, FieldWrapper wrapper, String fqClassName) implements ReturnTypeRef {}

    /**
     * The return type is a scalar, enum, or a type name that does not resolve to any classified
     * schema type (e.g., directive-argument type names such as those used by
     * {@code @nodeId(typeName:)}). No SQL is generated.
     */
    record ScalarReturnType(String returnTypeName, FieldWrapper wrapper) implements ReturnTypeRef {}

    /**
     * Stub for multi-table polymorphic returns: GraphQL interfaces and unions whose member types
     * are each backed by separate tables, and Relay/Federation built-in fields ({@code node},
     * {@code _entities}). Code generation for this case is not yet implemented.
     */
    record PolymorphicReturnType(String returnTypeName, FieldWrapper wrapper) implements ReturnTypeRef {}
}
