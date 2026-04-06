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
            GraphitronType.ErrorType, GraphitronType.InputType, GraphitronType.UnclassifiedType {

    String name();

    /** SDL source location, or {@code null} for runtime-wired types with no SDL definition. */
    SourceLocation location();

    /**
     * A type annotated with {@code @table}. Full SQL generation applies.
     *
     * <p>{@code table} is the outcome of resolving the {@code @table} directive's SQL name against
     * the jOOQ catalog: {@link TableRef.ResolvedTable} when the table was found (carrying the SQL
     * name, Java field name and the jOOQ {@link org.jooq.Table} instance),
     * {@link TableRef.UnresolvedTable} when it was not (carrying the SQL name that failed to
     * resolve). The {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports an error
     * for {@code UnresolvedTable}.
     *
     * <p>{@code node} captures whether a {@code @node} directive is present: {@link NodeRef.NoNode} when
     * absent, {@link NodeRef.NodeDirective} when present (carrying the optional {@code typeId} and the
     * list of key columns, each resolved against the jOOQ table via a {@link KeyColumnRef}).
     * {@code @node} is only permitted on types that also carry {@code @table}, which is why it
     * lives here rather than on a separate type variant. The
     * {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports an error for each
     * {@link KeyColumnRef.UnresolvedKeyColumn} in the list.
     */
    record TableType(
        String name,
        SourceLocation location,
        TableRef table,
        NodeRef node
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
