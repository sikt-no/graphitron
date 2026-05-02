package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLObjectType;

import java.util.List;
import java.util.Optional;

/**
 * Classifies every named GraphQL type. Determines what Graphitron generates for a type
 * and is the authoritative source of source context for all fields defined on it.
 */
public sealed interface GraphitronType
    permits GraphitronType.TableBackedType, GraphitronType.ResultType, GraphitronType.RootType,
            GraphitronType.InterfaceType, GraphitronType.UnionType, GraphitronType.ErrorType,
            GraphitronType.InputType, GraphitronType.TableInputType,
            GraphitronType.ConnectionType, GraphitronType.EdgeType, GraphitronType.PageInfoType,
            GraphitronType.PlainObjectType, GraphitronType.EnumType, GraphitronType.UnclassifiedType {

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
        TableRef table
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
     * <p>{@code encodeMethod} / {@code decodeMethod} are pre-resolved structural references to the
     * per-type {@code encode<TypeName>} / {@code decode<TypeName>} helpers emitted by
     * {@link no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator}. They are the
     * single source of truth for the helper references; emitters consume them through
     * {@link HelperRef} rather than reconstructing class + method strings from the typeId. Every
     * call site that wraps wire-format encode/decode reads from these slots so the encoder
     * generator and the call-site emitters cannot drift on naming.
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
        HelperRef.Encode encodeMethod,
        HelperRef.Decode decodeMethod
    ) implements TableBackedType {}

    /**
     * A type annotated with {@code @record}. Runtime wiring only — no SQL until a new scope starts.
     *
     * <p>The sub-type identifies the backing Java representation. The builder reflects on the class
     * named in {@code @record(record: {className: ...})} at build time. When no {@code className}
     * is provided, the type is classified as {@link PojoResultType} with a {@code null} backing class.
     */
    sealed interface ResultType extends GraphitronType
        permits GraphitronType.JavaRecordType, GraphitronType.PojoResultType,
                GraphitronType.JooqRecordType, GraphitronType.JooqTableRecordType {

        /** The binary class name of the backing Java class, or {@code null} when not specified. */
        String fqClassName();
    }

    /**
     * A {@code @record} type backed by a Java {@code record} class.
     * {@code fqClassName} is the binary class name, e.g. {@code "com.example.FilmDto"}.
     */
    record JavaRecordType(
        String name,
        SourceLocation location,
        String fqClassName
    ) implements ResultType {}

    /**
     * A {@code @record} type backed by a plain Java class (POJO), or one whose backing class
     * was not specified in the directive ({@code fqClassName} is {@code null} in that case).
     */
    record PojoResultType(
        String name,
        SourceLocation location,
        String fqClassName
    ) implements ResultType {}

    /**
     * A {@code @record} type backed by a jOOQ {@code Record<?>} (not table-bound).
     * {@code fqClassName} is the binary class name of the jOOQ record class.
     */
    record JooqRecordType(
        String name,
        SourceLocation location,
        String fqClassName
    ) implements ResultType {}

    /**
     * A {@code @record} type backed by a jOOQ {@code TableRecord<?>}.
     *
     * <p>{@code fqClassName} is the binary class name of the jOOQ table-record class.
     *
     * <p>{@code table} is the resolved jOOQ table this record is bound to, found by matching
     * {@link org.jooq.Table#getRecordType()} against the backing class. May be {@code null}
     * when the backing class comes from a catalog not loaded at build time.
     */
    record JooqTableRecordType(
        String name,
        SourceLocation location,
        String fqClassName,
        TableRef table
    ) implements ResultType {}

    /**
     * A root operation type (Query or Mutation). Unmapped — no source context, no SQL until
     * a scope is entered via a child field.
     */
    record RootType(
        String name,
        SourceLocation location
    ) implements GraphitronType {}

    /**
     * An interface annotated with {@code @table} and {@code @discriminate}, where implementing
     * types have {@code @table} and {@code @discriminator}. Single-table interface pattern.
     *
     * <p>{@code table} is the resolved jOOQ table (always present — failure to resolve produces
     * {@link UnclassifiedType}).
     *
     * <p>{@code participants} holds one {@link ParticipantRef} per implementing type. Unbound participants (e.g. {@code @error} types) are recorded as {@link ParticipantRef.Unbound}.
     */
    record TableInterfaceType(
        String name,
        SourceLocation location,
        String discriminatorColumn,
        TableRef table,
        List<ParticipantRef> participants
    ) implements TableBackedType {}

    /**
     * An interface with no directives. Participating types may be table-bound ({@link ParticipantRef.TableBound})
     * or unbound ({@link ParticipantRef.Unbound}). Unbound participants include {@code @error} types and nesting
     * types — object types with no {@code @table} whose fields map to columns on whichever parent table embeds them.
     * Only implementing types that are {@link UnclassifiedType} (failed {@code @table} resolution) produce an error.
     */
    record InterfaceType(
        String name,
        SourceLocation location,
        List<ParticipantRef> participants
    ) implements GraphitronType {}

    /**
     * A union type whose member types all have {@code @table}.
     *
     * <p>{@code participants} holds one {@link ParticipantRef} per member type. Unbound participants (e.g. {@code @error} types) are recorded as {@link ParticipantRef.Unbound}.
     */
    record UnionType(
        String name,
        SourceLocation location,
        List<ParticipantRef> participants
    ) implements GraphitronType {}

    /**
     * An object type annotated with {@code @error}. Maps Java exceptions to GraphQL error responses.
     *
     * <p>{@code handlers} holds one {@link Handler} per entry in the {@code handlers}
     * argument of the {@code @error} directive, lifted into the variant whose discriminator
     * the matcher keys off (exception class identity, SQL state, vendor code, validation kind).
     *
     * <p>An {@code @error} type has no developer-supplied Java backing class. The runtime source
     * for an entry in the payload's errors list is the matched object itself (the exception
     * for GENERIC/DATABASE handlers, the {@code GraphQLError} for VALIDATION); graphql-java's
     * {@code PropertyDataFetcher} reads each declared SDL field directly from that source.
     */
    record ErrorType(
        String name,
        SourceLocation location,
        List<Handler> handlers
    ) implements GraphitronType {

        /**
         * One entry in the {@code handlers} argument of the {@code @error} directive. Sealed by
         * matcher discriminator; the parser lifts each SDL entry into one variant per the
         * {@code error-handling-parity} spec's parse-time table.
         */
        public sealed interface Handler
            permits ExceptionHandler, SqlStateHandler, VendorCodeHandler, ValidationHandler {

            /** Optional substring filter on the matched exception's {@code getMessage()}. */
            Optional<String> matches();

            /** Optional user-facing description. Falls back to the exception's message at runtime. */
            Optional<String> description();
        }

        /**
         * Matches by exception class identity, walking the cause chain.
         * Lift target for {@code {handler: GENERIC, className: ...}} and the no-discriminator
         * {@code {handler: DATABASE}} (which lifts to {@code ExceptionHandler(java.sql.SQLException)}).
         */
        public record ExceptionHandler(
            String exceptionClassName,
            Optional<String> matches,
            Optional<String> description
        ) implements Handler {}

        /**
         * Matches any {@link java.sql.SQLException} in the cause chain whose
         * {@code getSQLState()} equals {@code sqlState}.
         * Lift target for {@code {handler: DATABASE, sqlState: ...}}.
         */
        public record SqlStateHandler(
            String sqlState,
            Optional<String> matches,
            Optional<String> description
        ) implements Handler {}

        /**
         * Matches any {@link java.sql.SQLException} in the cause chain whose
         * {@code getErrorCode()} string-equals {@code vendorCode}.
         * Lift target for {@code {handler: DATABASE, code: ...}}.
         */
        public record VendorCodeHandler(
            String vendorCode,
            Optional<String> matches,
            Optional<String> description
        ) implements Handler {}

        /**
         * Marks the channel for native Jakarta validation: the wrapper invokes
         * {@code jakarta.validation.Validator.validate(input)} before the body runs and
         * routes each {@code ConstraintViolation} into the payload's errors slot as a
         * {@code GraphQLError}. Lift target for {@code {handler: VALIDATION}}. Carries no
         * dispatch criteria; it is a wrapper-side flag, never reached by the dispatcher.
         */
        public record ValidationHandler(
            Optional<String> description
        ) implements Handler {
            @Override public Optional<String> matches() { return Optional.empty(); }
        }
    }

    /**
     * A GraphQL input object type with no {@code @table} binding.
     * The developer supplies the backing Java class; Graphitron does not generate DML for it.
     * This is the input-side counterpart of {@link ReturnTypeRef.ResultReturnType} and
     * {@link ReturnTypeRef.ScalarReturnType}.
     *
     * <p>The sub-type identifies the backing Java representation. The builder reflects on the class
     * named in {@code @record(record: {className: ...})} at build time. When no {@code className}
     * is provided the type is classified as {@link PojoInputType} with a {@code null} backing class.
     */
    sealed interface InputType extends GraphitronType
        permits GraphitronType.JavaRecordInputType, GraphitronType.PojoInputType,
                GraphitronType.JooqRecordInputType, GraphitronType.JooqTableRecordInputType {

        /**
         * The graphql-java form of this input type from the assembled schema.
         * Emission reads field list, descriptions, and applied directives through it.
         * May be {@code null} when the record is constructed outside the classifier (e.g. in tests).
         */
        GraphQLInputObjectType schemaType();
    }

    /**
     * A non-table input type backed by a Java {@code record} class.
     * {@code fqClassName} is the binary class name.
     */
    record JavaRecordInputType(
        String name,
        SourceLocation location,
        String fqClassName,
        GraphQLInputObjectType schemaType
    ) implements InputType {}

    /**
     * A non-table input type backed by a plain Java class (POJO), or one whose backing class
     * was not specified in the directive ({@code fqClassName} is {@code null} in that case).
     */
    record PojoInputType(
        String name,
        SourceLocation location,
        String fqClassName,
        GraphQLInputObjectType schemaType
    ) implements InputType {}

    /**
     * A non-table input type backed by a jOOQ {@code Record<?>} (not table-bound).
     * {@code fqClassName} is the binary class name of the jOOQ record class.
     */
    record JooqRecordInputType(
        String name,
        SourceLocation location,
        String fqClassName,
        GraphQLInputObjectType schemaType
    ) implements InputType {}

    /**
     * A non-table input type backed by a jOOQ {@code TableRecord<?>}.
     *
     * <p>{@code fqClassName} is the binary class name. {@code table} is the resolved jOOQ table
     * found by matching {@link org.jooq.Table#getRecordType()} against the backing class.
     * May be {@code null} when the backing class comes from a catalog not loaded at build time.
     */
    record JooqTableRecordInputType(
        String name,
        SourceLocation location,
        String fqClassName,
        TableRef table,
        GraphQLInputObjectType schemaType
    ) implements InputType {}

    /**
     * A GraphQL input object type annotated with {@code @table}.
     * Graphitron owns the DML — fields are resolved against the jOOQ table so that
     * INSERT/UPDATE/DELETE statements can be generated directly.
     * This is the input-side counterpart of {@link ReturnTypeRef.TableBoundReturnType}.
     *
     * <p>{@code table} is the resolved jOOQ table (always present — failure to resolve produces
     * {@link UnclassifiedType}). All {@code inputFields} are fully resolved {@link InputField}
     * instances ({@link InputField.ColumnField} for fields on the type's own table,
     * {@link InputField.ColumnReferenceField} for fields that navigate via {@code @reference});
     * any field whose column cannot be matched causes the whole type to be classified as
     * {@link UnclassifiedType}.
     */
    record TableInputType(
        String name,
        SourceLocation location,
        TableRef table,
        List<InputField> inputFields,
        GraphQLInputObjectType schemaType
    ) implements GraphitronType {}

    /**
     * A plain SDL object type — no {@code @table}, {@code @record}, {@code @error}, or other
     * domain directive, and not a root operation type. Typically a DTO nested under a parent
     * that carries the table context, or a standalone return type the developer wires manually.
     *
     * <p>No SQL is generated for the type itself. The classifier records it so
     * {@code schema.types()} is complete — every emittable type has an entry — and so the
     * schema emitters can iterate the model without falling back to the assembled
     * {@link graphql.schema.GraphQLSchema}.
     *
     * <p>{@code schemaType} is the graphql-java object referenced from the assembled schema at
     * classification time. Emission reads field list, description, and applied directives
     * through it.
     */
    record PlainObjectType(
        String name,
        SourceLocation location,
        GraphQLObjectType schemaType
    ) implements GraphitronType {}

    /**
     * A GraphQL enum type. Classifier records it so {@code schema.types()} is complete for
     * emission. Per-value directives and deprecation survive through {@code schemaType} — the
     * emitter reads them directly at generation time.
     */
    record EnumType(
        String name,
        SourceLocation location,
        GraphQLEnumType schemaType
    ) implements GraphitronType {}

    /**
     * A type that could not be classified — examples include an unresolvable {@code @table}
     * name, mutually exclusive directives co-occurring, malformed {@code @node} key columns,
     * a participant-list error on an interface or union, and federation-side {@code @key}
     * rejections from {@link no.sikt.graphitron.rewrite.schema.federation.EntityResolutionBuilder}.
     * A schema containing unclassified types is invalid — the
     * {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports a
     * {@link no.sikt.graphitron.rewrite.ValidationError} whose kind is projected from the
     * {@link Rejection} variant via {@link no.sikt.graphitron.rewrite.RejectionKind#of}.
     *
     * <p>{@link #reason()} is a convenience accessor that delegates to
     * {@link Rejection#message()} for log formatters that don't need the structured data.
     */
    record UnclassifiedType(
        String name,
        SourceLocation location,
        Rejection rejection
    ) implements GraphitronType {
        public String reason() { return rejection.message(); }
    }

    /**
     * A Relay connection object type — the outer wrapper whose fields are
     * {@code edges: [EdgeType!]!}, {@code nodes: [ElementType]!}, and {@code pageInfo: PageInfo!}.
     *
     * <p>Classifier produces this variant for two inputs that are semantically the same:
     * <ul>
     *   <li><b>Directive-driven:</b> a field carrying {@code @asConnection} on a bare list.
     *       The classifier synthesises the Connection type (it is not declared in the SDL).</li>
     *   <li><b>Structural:</b> a hand-written object type whose field shape matches the Relay
     *       connection pattern (an {@code edges} field of a type that has a {@code node} field).
     *       The classifier recognises the pattern and routes the declared type here.</li>
     * </ul>
     *
     * <p>{@code schemaType} is the graphql-java {@link GraphQLObjectType} that rebuilds the
     * connection's schema shape at runtime. For directive-driven connections the classifier
     * builds it programmatically; for structural connections the classifier references the
     * already-built value from the assembled schema.
     */
    record ConnectionType(
        String name,
        SourceLocation location,
        String elementTypeName,
        String edgeTypeName,
        boolean itemNullable,
        boolean shareable,
        GraphQLObjectType schemaType
    ) implements GraphitronType {}

    /**
     * A Relay edge object type — the inner wrapper whose fields are {@code cursor: String!} and
     * {@code node: ElementType}. Produced by the classifier whenever a {@link ConnectionType} is
     * produced; directive-driven and structural paths both route here.
     *
     * <p>{@code schemaType} is the graphql-java {@link GraphQLObjectType} for this edge — built
     * programmatically for directive-driven connections, referenced from the assembled schema
     * for structural ones.
     */
    record EdgeType(
        String name,
        SourceLocation location,
        String elementTypeName,
        boolean itemNullable,
        boolean shareable,
        GraphQLObjectType schemaType
    ) implements GraphitronType {}

    /**
     * The Relay {@code PageInfo} object type. Exactly one instance per schema; the classifier
     * reuses the SDL-declared type when present and synthesises one when at least one
     * {@link ConnectionType} exists and the SDL does not already declare {@code PageInfo}.
     *
     * <p>{@code shareable} is {@code true} when any connection in the schema carries the
     * {@code @shareable} directive; the synthesised page-info propagates that so federation
     * consumers see a consistent contract.
     */
    record PageInfoType(
        String name,
        SourceLocation location,
        boolean shareable,
        GraphQLObjectType schemaType
    ) implements GraphitronType {}
}
