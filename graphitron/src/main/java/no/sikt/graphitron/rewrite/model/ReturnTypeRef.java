package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.model.jooq.TableRef;


/**
 * Outcome of resolving the return type name of a field against the classified
 * {@link no.sikt.graphitron.rewrite.GraphitronSchema}, combined with the
 * {@link FieldWrapper} that describes how the element type is wrapped (single, list, or connection).
 */
public sealed interface ReturnTypeRef
    permits ReturnTypeRef.TableBoundReturnType, ReturnTypeRef.ResultReturnType,
            ReturnTypeRef.ScalarReturnType, ReturnTypeRef.PolymorphicReturnType {

    String returnTypeName();

    /** The wrapper around the element type: {@link FieldWrapper.Single}, {@link FieldWrapper.List}, or {@link FieldWrapper.Connection}. */
    FieldWrapper wrapper();

    /**
     * Graphitron generates the SQL query. The named type is a table-backed type
     * or the field inherits its parent's table context.
     * {@code table} is the outcome of resolving the type's {@code @table} directive and is
     * always fully resolved: when the name is not found in the jOOQ catalog, the builder
     * classifies the containing field as {@link GraphitronField.UnclassifiedField} instead of
     * emitting this variant.
     */
    record TableBoundReturnType(String returnTypeName, TableRef table, FieldWrapper wrapper) implements ReturnTypeRef {}

    /**
     * The return type is a result-mapped, class-backed type: a Java class derived by reflection
     * from its producer's return type. No SQL is generated; the generator accesses properties on the
     * parent result object.
     *
     * <p>{@code fqClassName} is copied verbatim from the corresponding
     * {@link no.sikt.graphitron.rewrite.model.GraphitronType.ResultType#fqClassName()} at build
     * time, so it is {@code null} exactly when the source component is: for the stand-in
     * population of {@link no.sikt.graphitron.rewrite.model.GraphitronType.JooqTableRecordType},
     * whose runtime source is a projected table row with no reflected backing class. A consumer
     * forking on the null is asking "is there a reflected backing class to work against". The
     * copied string stands in for carrying the source arm's identity, so it shares the source
     * slot's caveat: the null is a stand-in marker, not a designed contract.
     *
     * <p>{@code table} is the resolved jOOQ table when the named type classified as a
     * {@link no.sikt.graphitron.rewrite.model.GraphitronType.JooqTableRecordType} that resolved
     * one, and {@code null} otherwise. It is the fact that decides whether a producer over this
     * return hands down a typed jOOQ table record ({@link DomainReturnType#claimForResultReturn}),
     * and it is carried rather than re-derived per leaf: {@code fqClassName}'s nullity is a
     * statement about result-axis grounding only, and both {@code JooqTableRecordType}
     * populations (reflected class name, and the class-less stand-in) put a typed record at
     * {@code env.getSource()}.
     */
    record ResultReturnType(String returnTypeName, FieldWrapper wrapper, String fqClassName, TableRef table)
        implements ReturnTypeRef {}

    /**
     * The return type is a scalar, enum, or a type name that does not resolve to any classified
     * schema type (e.g., directive-argument type names such as those used by
     * {@code @nodeId(typeName:)}). No SQL is generated.
     */
    record ScalarReturnType(String returnTypeName, FieldWrapper wrapper) implements ReturnTypeRef {}

    /**
     * Multi-table polymorphic return: a GraphQL interface or union whose member types are each
     * backed by separate tables, or a Relay/Federation built-in field ({@code node},
     * {@code _entities}). Interface and union fetchers are emitted by
     * {@link no.sikt.graphitron.rewrite.generators.MultiTablePolymorphicEmitter}.
     */
    record PolymorphicReturnType(String returnTypeName, FieldWrapper wrapper) implements ReturnTypeRef {}
}
