package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;
import graphql.schema.FieldCoordinates;

import java.util.List;

/**
 * Classifies every named GraphQL type. Determines what Graphitron generates for a type
 * and is the authoritative source of source context for all fields defined on it.
 */
public sealed interface GraphitronType
    permits GraphitronType.TableBackedType, GraphitronType.ResultType, GraphitronType.RootType,
            GraphitronType.InterfaceType, GraphitronType.UnionType, GraphitronType.ErrorType,
            GraphitronType.InputType, GraphitronType.TableInputType, GraphitronType.UnclassifiedType {

    String name();

    /** SDL source location, or {@code null} for runtime-wired types with no SDL definition. */
    SourceLocation location();

    /**
     * A GraphQL type backed by a resolved jOOQ table.
     * All permitted sub-types carry a {@link TableRef} and generate SQL (SELECT or DML).
     */
    sealed interface TableBackedType extends GraphitronType
        permits GraphitronType.TableType, GraphitronType.NodeType, GraphitronType.TableInterfaceType {

        TableRef table();
    }

    /**
     * A GraphQL object type annotated with {@code @table}, without {@code @node}.
     * Full SQL generation applies.
     *
     * <p>{@code table} is the resolved jOOQ table (always present — a type whose {@code @table}
     * name cannot be matched is classified as {@link UnclassifiedType} instead).
     */
    record TableType(
        String name,
        SourceLocation location,
        TableRef table,
        List<FieldCoordinates> fieldCoordinates
    ) implements TableBackedType {}

    /**
     * A GraphQL object type annotated with both {@code @table} and {@code @node}.
     * Full SQL generation applies, plus Relay Global Object Identification.
     *
     * <p>{@code typeId} is the value of the {@code typeId} argument on the {@code @node}
     * directive, or {@code null} when the argument was omitted.
     *
     * <p>{@code nodeKeyColumns} is the resolved list of {@code keyColumns} argument entries.
     * An empty list means the argument was omitted, in which case the primary key is used
     * at code-generation time.
     *
     * <p>A {@code @node} type with an unresolvable key column is classified as
     * {@link UnclassifiedType} instead.
     */
    record NodeType(
        String name,
        SourceLocation location,
        TableRef table,
        String typeId,
        List<ColumnRef> nodeKeyColumns,
        List<FieldCoordinates> fieldCoordinates
    ) implements TableBackedType {}

    /**
     * A type annotated with {@code @record}. Runtime wiring only — no SQL until a new scope starts.
     */
    record ResultType(
        String name,
        SourceLocation location,
        List<FieldCoordinates> fieldCoordinates
    ) implements GraphitronType {}

    /**
     * A root operation type (Query or Mutation). Unmapped — no source context, no SQL until
     * a scope is entered via a child field.
     */
    record RootType(
        String name,
        SourceLocation location,
        List<FieldCoordinates> fieldCoordinates
    ) implements GraphitronType {}

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
    ) implements TableBackedType {}

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
     * A GraphQL input object type with no {@code @table} binding.
     * The developer supplies the backing Java class (record, POJO, Map, JSON, etc.);
     * Graphitron does not generate DML for it.
     * This is the input-side counterpart of {@link ReturnTypeRef.OtherReturnType}.
     * A backing-class discriminator will be added here when input-type code generation
     * is implemented.
     */
    record InputType(
        String name,
        SourceLocation location
    ) implements GraphitronType {}

    /**
     * A GraphQL input object type annotated with {@code @table}.
     * Graphitron owns the DML — fields are resolved against the jOOQ table so that
     * INSERT/UPDATE/DELETE statements can be generated directly.
     * This is the input-side counterpart of {@link ReturnTypeRef.TableBoundReturnType}.
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
