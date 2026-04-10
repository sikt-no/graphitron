package no.sikt.graphitron.rewrite.type;

import graphql.language.SourceLocation;

import java.util.List;

/**
 * Classifies every named GraphQL type. Determines what Graphitron generates for a type
 * and is the authoritative source of source context for all fields defined on it.
 */
public sealed interface GraphitronType
    permits GraphitronType.TableType, GraphitronType.ResultType, GraphitronType.RootType,
            GraphitronType.TableInterfaceType, GraphitronType.InterfaceType, GraphitronType.UnionType,
            GraphitronType.ErrorType, GraphitronType.InputType, GraphitronType.TableInputType,
            GraphitronType.UnclassifiedType {

    String name();

    /** SDL source location, or {@code null} for runtime-wired types with no SDL definition. */
    SourceLocation location();

    /**
     * A type annotated with {@code @table}. Full SQL generation applies.
     *
     * <p>{@code table} is the outcome of resolving the {@code @table} directive's SQL name against
     * the jOOQ catalog. When the table was found it is a {@link TableRef.ResolvedTable}; if the
     * owning type also carries {@code @node} it is further specialised as
     * {@link TableRef.ResolvedTable.WithNode} (carrying the optional {@code typeId} and the list of
     * key columns, each resolved against the jOOQ table via a {@link KeyColumnRef}). When the SQL
     * name could not be matched it is a {@link TableRef.UnresolvedTable}.
     *
     * <p>{@code @node} is only permitted on types that also carry {@code @table}, which is why the
     * node information lives on the {@link TableRef} rather than in a separate field.
     * The {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports an error for
     * {@code UnresolvedTable} and for each {@link KeyColumnRef.UnresolvedKeyColumn} inside a
     * {@code WithNode} table.
     */
    record TableType(
        String name,
        SourceLocation location,
        TableRef table
    ) implements GraphitronType {}

    /**
     * A type annotated with {@code @record}. Runtime wiring only — no SQL until a new scope starts.
     */
    record ResultType(String name, SourceLocation location) implements GraphitronType {}

    /**
     * A root operation type (Query or Mutation). Unmapped — no source context, no SQL until
     * a scope is entered via a child field.
     */
    record RootType(String name, SourceLocation location) implements GraphitronType {}

    /**
     * An interface annotated with {@code @table} and {@code @discriminate}, where implementing
     * types have {@code @table} and {@code @discriminator}. Single-table interface pattern.
     *
     * <p>{@code table} is the outcome of resolving the {@code @table} directive's SQL name against
     * the jOOQ catalog: {@link TableRef.ResolvedTable} when the table was found,
     * {@link TableRef.UnresolvedTable} when it was not. The SQL name is always available via
     * {@link TableRef#tableName()}. The
     * {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports an error for
     * {@code UnresolvedTable}.
     *
     * <p>{@code participants} holds one {@link ParticipantRef} per implementing type.
     * The {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports an error for every
     * {@link ParticipantRef.UnboundParticipant}.
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
     * <p>{@code participants} holds one {@link ParticipantRef} per implementing type.
     * The {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports an error for every
     * {@link ParticipantRef.UnboundParticipant}.
     */
    record InterfaceType(
        String name,
        SourceLocation location,
        List<ParticipantRef> participants
    ) implements GraphitronType {}

    /**
     * A union type whose member types all have {@code @table}.
     *
     * <p>{@code participants} holds one {@link ParticipantRef} per member type.
     * The {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports an error for every
     * {@link ParticipantRef.UnboundParticipant}.
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
     * <p>{@code fields} holds one {@link InputFieldSpec} per field in the input type, including
     * directive markers ({@code @orderBy}) that generators need. Fields
     * annotated with {@code @notGenerated} are excluded.
     *
     * <p>The {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports an error for each
     * field whose {@link InputFieldSpec#typeName()} does not resolve to a known type in the schema.
     */
    record InputType(
        String name,
        SourceLocation location,
        List<InputFieldSpec> fields
    ) implements GraphitronType {}

    /**
     * A GraphQL input object type annotated with {@code @table}. Fields are resolved against the
     * jOOQ table, enabling use of this input type in generated lookup queries.
     *
     * <p>{@code table} is the outcome of resolving the {@code @table} directive's SQL name against
     * the jOOQ catalog: {@link TableRef.ResolvedTable} when found, {@link TableRef.UnresolvedTable}
     * when not. The {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports an error
     * for {@code UnresolvedTable}.
     *
     * <p>{@code fields} holds one {@link InputFieldRef} per field in the input type (excluding
     * {@code @notGenerated} fields). Each field is either a {@link InputFieldRef.TableInputField}
     * (column resolved) or an {@link InputFieldRef.UnresolvedInputField} (column not found in the
     * jOOQ table). The validator reports an error for every {@code UnresolvedInputField}.
     */
    record TableInputType(
        String name,
        SourceLocation location,
        TableRef table,
        List<InputFieldRef> fields
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
