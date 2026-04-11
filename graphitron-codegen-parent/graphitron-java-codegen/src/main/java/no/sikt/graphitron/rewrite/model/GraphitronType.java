package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;
import graphql.schema.FieldCoordinates;

import java.util.List;

/**
 * Classifies every named GraphQL type. Determines what Graphitron generates for a type
 * and is the authoritative source of source context for all fields defined on it.
 */
public sealed interface GraphitronType
    permits GraphitronType.OutputType, GraphitronType.TableInterfaceType, GraphitronType.InterfaceType,
            GraphitronType.UnionType, GraphitronType.ErrorType, GraphitronType.InputType,
            GraphitronType.TableInputType, GraphitronType.UnclassifiedType {

    String name();

    /** SDL source location, or {@code null} for runtime-wired types with no SDL definition. */
    SourceLocation location();

    /**
     * A GraphQL object type that owns output fields. Permitted subtypes are {@link TableType},
     * {@link ResultType}, and {@link RootType}.
     *
     * <p>{@code fieldCoordinates} holds the {@link FieldCoordinates} of every field defined on
     * this type in the schema, in declaration order. Use {@link GraphitronSchema#fieldsOf} to
     * obtain the classified {@link GraphitronField} instances.
     */
    sealed interface OutputType extends GraphitronType
        permits GraphitronType.TableType, GraphitronType.ResultType, GraphitronType.RootType {

        List<FieldCoordinates> fieldCoordinates();
    }

    /**
     * A type annotated with {@code @table}. Full SQL generation applies.
     *
     * <p>{@code table} is the resolved jOOQ table (always present — a type whose {@code @table}
     * name cannot be matched is classified as {@link UnclassifiedType} instead).
     *
     * <p>{@code node} carries the {@code @node} directive properties ({@code typeId} and key
     * columns) when the type also has {@code @node}, or {@code null} when it does not. A type
     * with {@code @node} but with an unresolvable key column is classified as
     * {@link UnclassifiedType} instead.
     */
    record TableType(
        String name,
        SourceLocation location,
        TableRef table,
        NodeRef node,
        List<FieldCoordinates> fieldCoordinates
    ) implements OutputType {}

    /**
     * A type annotated with {@code @record}. Runtime wiring only — no SQL until a new scope starts.
     */
    record ResultType(
        String name,
        SourceLocation location,
        List<FieldCoordinates> fieldCoordinates
    ) implements OutputType {}

    /**
     * A root operation type (Query or Mutation). Unmapped — no source context, no SQL until
     * a scope is entered via a child field.
     */
    record RootType(
        String name,
        SourceLocation location,
        List<FieldCoordinates> fieldCoordinates
    ) implements OutputType {}

    /**
     * An interface annotated with {@code @table} and {@code @discriminate}, where implementing
     * types have {@code @table} and {@code @discriminator}. Single-table interface pattern.
     *
     * <p>{@code table} is the resolved jOOQ table (always present — failure to resolve produces
     * {@link UnclassifiedType}).
     *
     * <p>{@code participants} holds one {@link ParticipantRef} per implementing type. Any
     * unbound participant causes classification to fail with {@link UnclassifiedType}.
     */
    record TableInterfaceType(
        String name,
        SourceLocation location,
        String discriminatorColumn,
        TableRef table,
        List<ParticipantRef> participants
    ) implements GraphitronType {}

    /**
     * An interface with no directives whose implementing types each have {@code @table}.
     * Multi-table interface pattern.
     *
     * <p>{@code participants} holds one {@link ParticipantRef} per implementing type. Any
     * unbound participant causes classification to fail with {@link UnclassifiedType}.
     */
    record InterfaceType(
        String name,
        SourceLocation location,
        List<ParticipantRef> participants
    ) implements GraphitronType {}

    /**
     * A union type whose member types all have {@code @table}.
     *
     * <p>{@code participants} holds one {@link ParticipantRef} per member type. Any
     * unbound participant causes classification to fail with {@link UnclassifiedType}.
     */
    record UnionType(
        String name,
        SourceLocation location,
        List<ParticipantRef> participants
    ) implements GraphitronType {}

    /**
     * An object type annotated with {@code @error}. Maps Java exceptions to GraphQL error responses.
     *
     * <p>{@code handlers} holds one {@link ErrorHandlerSpec} per entry in the {@code handlers}
     * argument of the {@code @error} directive.
     */
    record ErrorType(
        String name,
        SourceLocation location,
        List<ErrorHandlerSpec> handlers
    ) implements GraphitronType {}

    /**
     * A GraphQL input object type. Carries the field list that generators and validators inspect.
     *
     * <p>{@code inputFields} holds one {@link InputFieldSpec} per field in the input type, including
     * directive markers ({@code @orderBy}) that generators need. Fields
     * annotated with {@code @notGenerated} are excluded.
     *
     * <p>The {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports an error for each
     * field whose {@link InputFieldSpec#typeName()} does not resolve to a known type in the schema.
     */
    record InputType(
        String name,
        SourceLocation location,
        List<InputFieldSpec> inputFields
    ) implements GraphitronType {}

    /**
     * A GraphQL input object type annotated with {@code @table}. Fields are resolved against the
     * jOOQ table, enabling use of this input type in generated lookup queries.
     *
     * <p>{@code table} is the resolved jOOQ table (always present — failure to resolve produces
     * {@link UnclassifiedType}). All {@code inputFields} are fully resolved {@link InputFieldRef}
     * instances; any field whose column cannot be matched causes the whole type to be classified
     * as {@link UnclassifiedType}.
     */
    record TableInputType(
        String name,
        SourceLocation location,
        TableRef table,
        List<InputFieldRef> inputFields
    ) implements GraphitronType {}

    /**
     * A type that could not be classified because mutually exclusive directives were found together.
     * A schema containing unclassified types is invalid — the
     * {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports an error with the
     * {@code reason} explaining which directives conflict.
     */
    record UnclassifiedType(
        String name,
        SourceLocation location,
        String reason
    ) implements GraphitronType {}
}
