package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;

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
            GraphitronType.FacetsType, GraphitronType.FacetValueType,
            GraphitronType.NestingType, GraphitronType.EnumType, GraphitronType.ScalarType,
            GraphitronType.UnclassifiedType {

    String name();

    /** SDL source location, or {@code null} for runtime-wired types with no SDL definition. */
    SourceLocation location();

    /**
     * A GraphQL type backed by a resolved jOOQ table.
     * All permitted sub-types carry a {@link TableRef} and generate SQL (SELECT or DML).
     */
    sealed interface TableBackedType extends GraphitronType, EmitsPerTypeFile
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
     * A class-backed, result-mapped type whose backing comes from its producer's reflected
     * return type. Runtime wiring only; no SQL until a new scope starts.
     *
     * <p>The sub-type identifies the backing Java representation. The builder reflects on the
     * producer's return type (a {@code @service} method return, {@code @tableMethod} return, or a
     * parent-accessor chain) at build time. When no backing class can be resolved, the type is
     * classified as {@link PojoResultType} with a {@code null} backing class.
     */
    sealed interface ResultType extends GraphitronType, EmitsPerTypeFile
        permits GraphitronType.JavaRecordType, GraphitronType.PojoResultType,
                GraphitronType.JooqRecordType, GraphitronType.JooqTableRecordType {

        /** The binary class name of the backing Java class, or {@code null} when not specified. */
        String fqClassName();
    }

    /**
     * A result type whose producer's reflected return is a Java {@code record} class.
     * {@code fqClassName} is the binary class name, e.g. {@code "com.example.FilmDto"}.
     */
    record JavaRecordType(
        String name,
        SourceLocation location,
        String fqClassName
    ) implements ResultType {}

    /**
     * A result type whose producer's reflected return is a plain Java class (POJO).
     *
     * <p>The sole permitted sub-type is {@link Backed}: a payload carrier whose backing class was
     * reflected from its producer's return type. The {@link ResultType#fqClassName()}
     * method returns the non-null backing class name. Sites identify the case from the permit
     * identity ({@code instanceof PojoResultType.Backed}) rather than from a nullable.
     */
    sealed interface PojoResultType extends ResultType
        permits PojoResultType.Backed {

        /**
         * R75 Phase 1: a payload carrier whose backing class was reflected from its
         * producer's return type. {@code fqClassName} is non-null.
         */
        record Backed(
            String name,
            SourceLocation location,
            String fqClassName
        ) implements PojoResultType {
            public Backed {
                java.util.Objects.requireNonNull(fqClassName, "PojoResultType.Backed requires non-null fqClassName");
            }
        }
    }

    /**
     * A result type whose producer's reflected return is a jOOQ {@code Record<?>} (not table-bound).
     * {@code fqClassName} is the binary class name of the jOOQ record class.
     */
    record JooqRecordType(
        String name,
        SourceLocation location,
        String fqClassName
    ) implements ResultType {}

    /**
     * A result type whose producer's reflected return is a jOOQ {@code TableRecord<?>}.
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
    ) implements GraphitronType, EmitsPerTypeFile {}

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
    ) implements GraphitronType, EmitsPerTypeFile {}

    /**
     * A union type whose member types all have {@code @table}.
     *
     * <p>{@code participants} holds one {@link ParticipantRef} per member type. Unbound participants (e.g. {@code @error} types) are recorded as {@link ParticipantRef.Unbound}.
     */
    record UnionType(
        String name,
        SourceLocation location,
        List<ParticipantRef> participants
    ) implements GraphitronType, EmitsPerTypeFile {}

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
    ) implements GraphitronType, EmitsPerTypeFile {

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
     * <p>The sub-type identifies the backing Java representation. The builder reflects on the method
     * parameter type the input flows into (or {@code @table}) at build time. When no backing class
     * can be resolved the type is classified as {@link PojoInputType} with a {@code null} backing class.
     */
    sealed interface InputType extends GraphitronType, EmitsPerTypeFile
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
        GraphQLInputObjectType schemaType,
        InputRecordShape recordShape
    ) implements InputType, HasInputRecordShape {}

    /**
     * A non-table input type backed by a plain Java class (POJO), or one whose backing class
     * was not specified in the directive ({@code fqClassName} is {@code null} in that case).
     */
    record PojoInputType(
        String name,
        SourceLocation location,
        String fqClassName,
        GraphQLInputObjectType schemaType,
        InputRecordShape recordShape
    ) implements InputType, HasInputRecordShape {}

    /**
     * A non-table input type backed by a jOOQ {@code Record<?>} (not table-bound).
     * {@code fqClassName} is the binary class name of the jOOQ record class.
     */
    record JooqRecordInputType(
        String name,
        SourceLocation location,
        String fqClassName,
        GraphQLInputObjectType schemaType,
        InputRecordShape recordShape
    ) implements InputType, HasInputRecordShape {}

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
        GraphQLInputObjectType schemaType,
        InputRecordShape recordShape
    ) implements InputType, HasInputRecordShape {}

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
        GraphQLInputObjectType schemaType,
        InputRecordShape recordShape
    ) implements GraphitronType, EmitsPerTypeFile, HasInputRecordShape {}

    /**
     * A nesting projection: a directiveless SDL object type embedded under a {@code @table}-bound
     * parent through a {@code ChildField.NestingField}. The nested SDL type inherits the parent's
     * {@code @table} and maps to the same database row; its fields are columns on the embedding
     * table (the same column may appear under several nested names, sharing one SELECT term).
     *
     * <p>This classification is assigned <em>only</em> at the embedding edge, when the field-first walk
     * builds a {@code NestingField} and registers its element type (see {@code GraphitronSchemaBuilder
     * .registerNestingTypesIn}); the type pass leaves a directiveless object unclassified because it
     * cannot yet know whether anything nests it. The invariant {@code NestingType} ⟺ a corresponding
     * {@code NestingField} therefore holds by construction. A directiveless object that nothing
     * nests is left unclassified (an orphan) and the field that returns it classifies as
     * {@code UnclassifiedField}.
     *
     * <p>No standalone SQL or fetcher is generated for the type itself: its fields inline into the
     * embedding parent's query and read off the parent row through the {@code NestingField}. The
     * registry entry exists so {@code schema.types()} is complete for the schema emitters.
     *
     * <p>{@code schemaType} is the graphql-java object referenced from the assembled schema at
     * classification time. Emission reads field list, description, and applied directives
     * through it.
     */
    record NestingType(
        String name,
        SourceLocation location,
        GraphQLObjectType schemaType
    ) implements GraphitronType, EmitsPerTypeFile {}

    /**
     * A GraphQL enum type. Classifier records it so {@code schema.types()} is complete for
     * emission. {@link #values} is the pre-resolved per-value spec list: at classify time the
     * classifier walks each {@code GraphQLEnumValueDefinition} once and lifts
     * {@code @field(name:)} into {@link EnumValueSpec#runtimeValue} so the schema emitter and
     * the filter-axis resolver read the runtime string off the same record component.
     * {@code schemaType} stays for type-level applied-directive emission
     * ({@code AppliedDirectiveEmitter.emitApplications} on the enum container); per-value
     * applied directives walk {@link EnumValueSpec#source} instead.
     */
    record EnumType(
        String name,
        SourceLocation location,
        List<EnumValueSpec> values,
        GraphQLEnumType schemaType
    ) implements GraphitronType, EmitsPerTypeFile {}

    /**
     * A GraphQL scalar type whose Java type and {@code GraphQLScalarType} constant (or inline
     * synthesised form) have been resolved by
     * {@link no.sikt.graphitron.rewrite.ScalarTypeResolver}. Carries the
     * {@link ScalarResolution.Successful} so emitters (notably
     * {@code GraphitronSchemaClassGenerator}'s scalar-registration loop) can dispatch on the
     * variant ({@link ScalarResolution.Resolved} → {@code .additionalType(Owner.FIELD)};
     * {@link ScalarResolution.Synthesised} → inline {@code GraphQLScalarType.newScalar()...build()})
     * without re-running reflection.
     *
     * <p>The classifier produces this variant for:
     * <ul>
     *   <li>The five GraphQL spec built-ins ({@code Int}, {@code Float}, {@code String},
     *       {@code Boolean}, {@code ID}) — always resolved through the resolver's closed
     *       built-in table to a {@code Resolved} arm.</li>
     *   <li>Federation-namespace scalars ({@code federation__FieldSet} etc.) — resolved to a
     *       {@code Synthesised} arm so the schema registers them under their SDL names with
     *       {@code _Any.type.getCoercing()} borrowed, since federation-jvm exposes no
     *       public-static-final constant for the renamed forms.</li>
     *   <li>Consumer-declared scalars carrying a {@code @scalarType(scalar: "...")} directive
     *       that resolves cleanly. A directive that fails to resolve (unknown class, missing
     *       field, erased Coercing) surfaces as {@link UnclassifiedType} instead.</li>
     * </ul>
     *
     * <p>Consumer scalars without {@code @scalarType} (and not federation-namespaced) are not
     * classified — the classifier returns {@code null} for them, mirroring pre-R101 behaviour, so
     * the build stays green for consumers who haven't reached for the directive.
     *
     * <p>{@code schemaType} is the graphql-java object referenced from the assembled schema at
     * classification time; emitters read directive applications through it (when they need to).
     */
    record ScalarType(
        String name,
        SourceLocation location,
        ScalarResolution.Successful resolution,
        GraphQLScalarType schemaType
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
     *
     * <p>{@code facets} is the resolved {@code @asFacet} view for this connection (R13): one
     * {@link FacetSpec} per marked field on the carrier's filter input, empty when none are marked
     * (and always empty on the structural path — facet synthesis applies only to directive-driven
     * carriers whose Connection shape Graphitron owns). This entry is a contained denormalized view
     * carrier, not the fact's normalized home; when R314 lowers the connection unit, it re-sources
     * onto the facet operation fact with the rest of the view (see the R13 roadmap item's
     * <em>Contained approach</em>).
     */
    record ConnectionType(
        String name,
        SourceLocation location,
        String elementTypeName,
        String edgeTypeName,
        boolean itemNullable,
        boolean shareable,
        List<FacetSpec> facets,
        GraphQLObjectType schemaType
    ) implements GraphitronType, EmitsPerTypeFile {}

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
    ) implements GraphitronType, EmitsPerTypeFile {}

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
    ) implements GraphitronType, EmitsPerTypeFile {}

    /**
     * The synthesised per-connection facets container (R13), e.g.
     * {@code QueryFilmerConnectionFacets}: one nullable {@code [<Scalar>FacetValue!]} field per
     * {@code @asFacet}-marked filter-input field on the owning connection's carrier. Synthesised by
     * {@code ConnectionPromoter} alongside the Connection / Edge forms whenever the carrier's
     * filter input marks at least one facet; never SDL-declared.
     *
     * <p>Each per-facet field is deliberately nullable (a facet is a best-effort aggregate; a
     * failing facet degrades to null rather than propagating through GraphQL non-null bubbling),
     * while the list elements stay non-null.
     *
     * <p>{@code connectionName} names the owning {@link ConnectionType} entry, whose
     * {@link ConnectionType#facets()} list carries the resolved column bindings this type's fields
     * surface.
     */
    record FacetsType(
        String name,
        SourceLocation location,
        String connectionName,
        GraphQLObjectType schemaType
    ) implements GraphitronType, EmitsPerTypeFile {}

    /**
     * A synthesised, cross-schema-reusable facet value type (R13), e.g.
     * {@code MpaaRatingFacetValue { value: MpaaRating! count: Int! }}. One entry per distinct
     * (value scalar, element nullability) pair encountered across the whole schema, named by
     * {@link FacetNaming#facetValueTypeName(String, boolean)}; never SDL-declared.
     *
     * <p>{@code value} mirrors the annotated filter-input field's element type exactly — same
     * scalar <em>and</em> same nullability — so a client can feed {@code facetValue.value} straight
     * back into the filter with no coercion. {@code valueNullable} records the nullability half of
     * the dedup key.
     */
    record FacetValueType(
        String name,
        SourceLocation location,
        String valueTypeName,
        boolean valueNullable,
        GraphQLObjectType schemaType
    ) implements GraphitronType, EmitsPerTypeFile {}
}
