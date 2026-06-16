package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;

import java.util.List;
import java.util.Optional;

/**
 * How to extract one argument value from a GraphQL execution context at the fetcher call site.
 *
 * <p>Used in {@link CallParam} to tell the fetcher generator exactly what code to emit for each
 * argument passed to a condition or ordering method. The generator switches on this type once and
 * emits the corresponding expression, no other decisions are made at generation time.
 *
 * <ul>
 *   <li>{@link Direct} — {@code env.getArgument("name")}</li>
 *   <li>{@link EnumValueOf} — {@code EnumClass.valueOf(env.<String>getArgument("name"))},
 *       null-guarded when the argument is nullable.</li>
 *   <li>{@link ContextArg} — {@code graphitronContext(env).getContextArgument(env, "name")}</li>
 *   <li>{@link NestedInputField} — traverse a nested input-object graph starting from a
 *       top-level argument Map and descending through {@code path} keys, null-safe at every
 *       level; used for {@code @condition} on {@code INPUT_FIELD_DEFINITION}.</li>
 * </ul>
 *
 * <p>R229 retired the {@code TextMapLookup} permit: graphql-java's
 * {@code GraphQLEnumValueDefinition.value(...)} now carries the {@code @field(name:)} runtime
 * form, so graphql-java does the wire-form → runtime-form translation at the boundary and the
 * Java-side map became an identity lookup. Text-mapped enum args route through {@link Direct}.
 */
public sealed interface CallSiteExtraction
        permits CallSiteExtraction.Direct, CallSiteExtraction.EnumValueOf,
                CallSiteExtraction.ContextArg,
                CallSiteExtraction.JooqConvert, CallSiteExtraction.NestedInputField,
                CallSiteExtraction.NodeIdDecodeKeys, CallSiteExtraction.NodeIdDecodeRecord,
                CallSiteExtraction.InputBean, CallSiteExtraction.JooqRecord {

    /** Pass the argument directly: {@code env.getArgument("name")}. */
    record Direct() implements CallSiteExtraction {}

    /**
     * Convert a GraphQL String argument to a jOOQ enum:
     * {@code EnumClass.valueOf(env.<String>getArgument("name"))}.
     *
     * <p>{@code enumClassName} is the fully qualified Java enum class name
     * (e.g. {@code "no.example.jooq.enums.MpaaRating"}).
     */
    record EnumValueOf(String enumClassName) implements CallSiteExtraction {}

    /**
     * Retrieve a context argument: {@code graphitronContext(env).getContextArgument(env, "name")}.
     */
    record ContextArg() implements CallSiteExtraction {}

    /**
     * Coerce a GraphQL {@code ID} scalar (delivered as {@code String} by GraphQL-Java) to the
     * column Java type via jOOQ's {@code DataType.convert()}.
     *
     * <p>{@code columnJavaName} is the jOOQ field constant name (e.g. {@code "FILM_ID"}) used to
     * reach the target {@code DataType} from the table alias:
     * <ul>
     *   <li>Scalar: {@code table.FILM_ID.getDataType().convert((String) env.getArgument("film_id"))}</li>
     *   <li>List (with {@link CallParam#list()} / {@link BodyParam#list()}): local variable
     *       {@code List<String> filmIdKeys = env.getArgument("film_id")} is declared before the
     *       condition call, then passed as
     *       {@code filmIdKeys.stream().map(table.FILM_ID.getDataType()::convert).toList()}.</li>
     * </ul>
     */
    record JooqConvert(String columnJavaName) implements CallSiteExtraction {}

    /**
     * Traverse a nested input-object graph starting from the top-level argument named
     * {@code outerArgName} and descending through {@code path} keys to retrieve a value stored
     * inside the argument Map. Used by {@code @condition} on {@code INPUT_FIELD_DEFINITION}:
     * the condition method's parameter is the field value, but the field is not a top-level
     * argument, so the generator must emit Map traversal from the outer arg down to the leaf.
     *
     * <p>{@code path} is the ordered list of keys from the outer argument Map to the leaf value.
     * Non-empty; the last element is the SDL input-field name whose condition is being evaluated
     * (matches {@link CallParam#name()}).
     *
     * <p>{@code leaf} is the per-leaf transform applied to the wire-format value returned by the
     * Map traversal. {@link Direct} (the default for column-equality paths) leaves the value as
     * is. {@link NodeIdDecodeKeys} arms are used for {@code [ID!] @nodeId(typeName: T)} input
     * fields whose wire-format {@code List<String>} of base64 ids is decoded element-by-element
     * via the {@code decode<TypeName>} helper into typed key values before the predicate body
     * fires. Other leaves are reserved for future arms; the validator should enforce that a leaf
     * is not itself a {@code NestedInputField} (no recursion).
     *
     * <p>Traversal is null-safe: if any intermediate step is not a {@code Map} or is
     * {@code null}, the result is {@code null}. The condition method is still invoked; if it
     * receives {@code null}, it is responsible for returning a no-op filter.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code outerArgName="filter"}, {@code path=["filmId"]}, {@code leaf=Direct} for a
     *       direct {@code ColumnField.filmId} condition on a top-level input arg.</li>
     *   <li>{@code outerArgName="filter"}, {@code path=["where", "filmId"]}, {@code leaf=Direct}
     *       for a {@code ColumnField.filmId} inside a {@code NestingField.where}.</li>
     *   <li>{@code outerArgName="filter"}, {@code path=["filmIds"]},
     *       {@code leaf=NodeIdDecodeKeys.SkipMismatchedElement} for a
     *       {@code [ID!] @nodeId(typeName: "Film")} input filter.</li>
     * </ul>
     */
    record NestedInputField(String outerArgName, List<String> path, CallSiteExtraction leaf)
            implements CallSiteExtraction {
        public NestedInputField {
            if (path == null || path.isEmpty()) {
                throw new IllegalArgumentException("NestedInputField path must be non-empty");
            }
            path = List.copyOf(path);
            if (leaf instanceof NestedInputField) {
                throw new IllegalArgumentException("NestedInputField leaf must not be another NestedInputField");
            }
        }

        /** Convenience constructor that defaults {@code leaf} to {@link Direct}. */
        public NestedInputField(String outerArgName, List<String> path) {
            this(outerArgName, path, new Direct());
        }
    }

    /**
     * Decode a base64 NodeId at the call-site root into typed key values. Sealed sub-taxonomy with
     * two arms differing only in how a {@code null} return from
     * {@link HelperRef.Decode decode<TypeName>} (malformed input or typeId mismatch) surfaces:
     *
     * <ul>
     *   <li>{@link SkipMismatchedElement} — input-side filters
     *       ({@code [ID!] @nodeId(typeName: T)} on an input-object field, or the same shape as a
     *       top-level field-argument). A {@code null} return short-circuits the bad element to
     *       "no row matches"; never throws. Empty list and null arg follow existing
     *       column-equality behavior (predicate omitted when arg is absent, {@code falseCondition()}
     *       when present-but-empty).</li>
     *   <li>{@link ThrowOnMismatch} — {@code @nodeId} on a top-level scalar / list argument used
     *       as a lookup or mutation key. A {@code null} return is an authored-input error and
     *       surfaces as a {@code GraphqlErrorException}-shaped error, not silent null; a
     *       wrong-type id at a lookup key is a contract violation rather than "no match."</li>
     * </ul>
     *
     * <p>The third failure mode (NullOnMismatch) is dispatcher-driven (Query.node, Query.nodes,
     * federated _entities) and does not appear here as a carrier arm; see
     * {@code EntityFetcherDispatchClassGenerator}.
     *
     * <p>Each arm carries the pre-resolved {@link HelperRef.Decode} for the target NodeType, so
     * the call-site emitter reaches the per-Node {@code decode<TypeName>} helper through a typed
     * structural reference rather than reconstructing names from a typeId string.
     */
    sealed interface NodeIdDecodeKeys extends CallSiteExtraction permits SkipMismatchedElement, ThrowOnMismatch {

        /** The pre-resolved {@code decode<TypeName>} helper reference. */
        HelperRef.Decode decodeMethod();
    }

    /**
     * Skip the bad element on a {@code null} decode return. Used by
     * {@code [ID!] @nodeId(typeName: T)} input-object-field filters and the equivalent top-level
     * field-argument filter shape.
     */
    record SkipMismatchedElement(HelperRef.Decode decodeMethod) implements NodeIdDecodeKeys {}

    /**
     * Throw a {@code GraphqlErrorException} on a {@code null} decode return. Used by
     * {@code @nodeId} on a top-level scalar / list argument bound to a lookup or mutation key.
     */
    record ThrowOnMismatch(HelperRef.Decode decodeMethod) implements NodeIdDecodeKeys {}

    /**
     * Decode a base64 NodeId into a jOOQ {@code TableRecord} for a {@code @service} input-bean
     * member field typed as a generated {@code *Record}. Sibling to {@link NodeIdDecodeKeys}: same
     * NodeId wire decode at the root, opposite downstream projection. Where {@link NodeIdDecodeKeys}
     * <em>decomposes</em> the decoded key tuple into scalar column values for a predicate / SET
     * body (and so needs the typed {@code RecordN} projection of {@link HelperRef.Decode}), this arm
     * <em>materialises a {@link org.jooq.TableRecord}</em>: it calls
     * {@code encoderClass.decodeValues(typeId, nodeId)} for the raw {@code String[]} and loads those
     * values positionally onto the target record's key columns with a single
     * {@code record.fromArray(values, Tables.<T>.<col1>, Tables.<T>.<col2>, ...)} call. No throwaway
     * {@code RecordN}, no {@code fromMap(intoMap())} name round-trip, and no deprecated-for-removal
     * {@code DataType.convert(Object)}: {@code fromArray} coerces each value through the column's
     * {@code DataType} / registered {@code Converter} and keeps the real compile-tier check (the
     * {@code Tables.<T>.<col>} field references must exist on the record, which the
     * {@code graphitron-sakila-example} compile tier verifies).
     *
     * <p>The leaf therefore carries no {@code decode<Type>} method name (it never calls
     * {@code decode<Type>}): {@code encoderClass} reaches {@code decodeValues}, {@code typeId} is its
     * first argument, {@code keyColumns} names the {@code Tables.<T>.<col>} fields passed to
     * {@code fromArray}, and {@code table} supplies the record class ({@code new <T>Record()}) and the
     * {@code Tables} constants class. {@code keyColumns} is kept
     * separate from {@code table.primaryKeyColumns()} because an {@code @node(keyColumns:)} key may
     * differ from the PK.
     *
     * <p>Produced only by {@code InputBeanResolver} when an input-bean field's loaded Java type is
     * assignable to {@code org.jooq.Record} and the SDL field carries {@code @nodeId(typeName:)};
     * consumed only by {@code InputBeanInstantiationEmitter}, which emits a per-record-type
     * {@code decode<RecordType>Record} helper (plus a {@code …RecordList} variant for list-valued
     * members) on the enclosing {@code *Fetchers} class. Any other exhaustive
     * {@link CallSiteExtraction} switch treats this arm as unreachable-by-construction.
     *
     * <p>Both arities (single-column and composite key) and both shapes (scalar and list-valued
     * member) flow through this one leaf: arity is {@code keyColumns.size()} (each column named as a
     * positional field in the one {@code fromArray} call), and list-ness is carried on the enclosing
     * {@link FieldBinding#list()}, not
     * duplicated here. {@code nonNull} reflects the SDL field's nullability ({@code ID!} vs
     * {@code ID}); a type-mismatch decode (the helper returns {@code null}) is always an
     * authored-input error and throws, while a {@code null}/absent wire value follows graphql-java's
     * non-null enforcement at the boundary, so a nullable field yields a {@code null} member.
     */
    record NodeIdDecodeRecord(ClassName encoderClass, String typeId,
                              List<ColumnRef> keyColumns, TableRef table,
                              boolean nonNull)
            implements CallSiteExtraction {
        public NodeIdDecodeRecord {
            if (encoderClass == null) {
                throw new IllegalArgumentException("NodeIdDecodeRecord encoderClass must be non-null");
            }
            if (typeId == null || typeId.isEmpty()) {
                throw new IllegalArgumentException("NodeIdDecodeRecord typeId must be non-empty");
            }
            if (keyColumns == null || keyColumns.isEmpty()) {
                throw new IllegalArgumentException("NodeIdDecodeRecord keyColumns must be non-empty");
            }
            keyColumns = List.copyOf(keyColumns);
            if (table == null) {
                throw new IllegalArgumentException("NodeIdDecodeRecord table must be non-null");
            }
        }
    }

    /**
     * Instantiate a consumer-authored Java bean class from a GraphQL input-object Map. Used by
     * {@code @service} methods whose Java parameter (or list element type) is a class mirroring
     * an SDL {@code input} type living in the service package.
     *
     * <p>The call site emits {@code create<TypeName>(env.getArgument("name"))} for a singular bean
     * parameter and {@code create<TypeName>s(env.getArgument("name"))} for a {@code List<Bean>}
     * parameter. The emitted helper(s) live on the enclosing {@code *Fetchers} class; helpers are
     * deduplicated per bean class. The helper itself walks the Map field-by-field, populating a
     * fresh instance — either via the record canonical constructor (when {@code target} is
     * {@link Target#RECORD}) or no-arg constructor + JavaBean setters
     * (when {@code target} is {@link Target#JAVA_BEAN}).
     *
     * <p>{@code fields} is the per-SDL-field binding, in SDL declaration order. For record targets
     * this order is also the canonical-constructor argument order: the bindings must match the
     * record's component order. For JavaBean targets the order is irrelevant (each binding is
     * applied via its named setter independently).
     *
     * <p>Cycle-prevention invariant (R94): the helper references only JDK types and service-layer
     * types. {@code beanClass} is a consumer-authored type, never a graphitron-emitted record.
     */
    record InputBean(ClassName beanClass, Target target, List<FieldBinding> fields)
            implements CallSiteExtraction {
        public InputBean {
            if (beanClass == null) {
                throw new IllegalArgumentException("InputBean beanClass must be non-null");
            }
            if (target == null) {
                throw new IllegalArgumentException("InputBean target must be non-null");
            }
            if (fields == null || fields.isEmpty()) {
                throw new IllegalArgumentException("InputBean fields must be non-empty");
            }
            fields = List.copyOf(fields);
        }

        /** Constructor shape the helper uses to instantiate the bean. */
        public enum Target {
            /** Java record — populate positionally via the canonical constructor. */
            RECORD,
            /** Plain class with a public no-arg constructor and JavaBean setters. */
            JAVA_BEAN
        }
    }

    /**
     * One field on an {@link InputBean}. {@code sdlFieldName} is the GraphQL input field name and
     * is the {@code Map} key the helper reads from. {@code javaFieldName} is the corresponding
     * member name on the consumer-authored bean: for a record target this is the canonical
     * component name; for a JavaBean target this is the property name (the setter is named
     * {@code set<Capitalised javaFieldName>}).
     *
     * <p>{@code leaf} is the per-field transform. {@link Direct} populates from the raw Map value
     * (typed via Java cast). {@link EnumValueOf} routes through the corresponding enum's
     * {@code valueOf}. {@link InputBean} recurses into a nested bean instantiation. Other arms are
     * not yet supported on the leaf — the validator rejects them at classify time.
     *
     * <p>{@code list} indicates whether the field is list-shaped (Java type {@code List<X>} on the
     * bean). When {@code true} with a non-{@link Direct} leaf, the helper streams the inner list
     * through the leaf transform.
     *
     * <p>{@code javaElementTypeName} is the fully qualified name of the leaf scalar/enum Java type
     * (or the bean class name for nested {@link InputBean} leaves), used to emit casts. For lists
     * it is the element type, not the wrapping {@code List}. Invariant: always a real class name,
     * never a Java primitive literal — primitives are boxed to their wrapper FQN at the resolver
     * boundary so that the {@link no.sikt.graphitron.javapoet.ClassName#bestGuess(String)} consumers
     * in {@code InputBeanInstantiationEmitter} can rely on the string being a class name.
     */
    record FieldBinding(String sdlFieldName, String javaFieldName,
                        CallSiteExtraction leaf, boolean list,
                        String javaElementTypeName) {
        public FieldBinding {
            if (sdlFieldName == null || sdlFieldName.isEmpty()) {
                throw new IllegalArgumentException("FieldBinding sdlFieldName must be non-empty");
            }
            if (javaFieldName == null || javaFieldName.isEmpty()) {
                throw new IllegalArgumentException("FieldBinding javaFieldName must be non-empty");
            }
            if (leaf == null) {
                throw new IllegalArgumentException("FieldBinding leaf must be non-null");
            }
            if (javaElementTypeName == null || javaElementTypeName.isEmpty()) {
                throw new IllegalArgumentException("FieldBinding javaElementTypeName must be non-empty");
            }
        }
    }

    /**
     * Construct a generated jOOQ {@link org.jooq.TableRecord} from a GraphQL input-object {@code Map}
     * at a {@code @service} parameter position (R311). Sibling to {@link InputBean}: same "instantiate
     * the consumer's typed parameter at the fetcher boundary so the service body never sees a
     * {@code Map}" goal, opposite binding axis. Where {@link InputBean} binds SDL fields on the
     * Java-<em>member</em> axis ({@link FieldBinding#javaFieldName()}), a {@code JooqRecord} binds them
     * on the <em>column</em> axis: each plain field names a jOOQ column through {@code @field(name:)}
     * (carried as a resolved {@link ColumnBinding}), and an optional {@code @nodeId} field decodes the
     * record's scalar key columns (carried as a {@link RecordKeyDecode}).
     *
     * <p>{@code table} is read straight off the param's classified
     * {@link GraphitronType.JooqTableRecordInputType}; the record class is {@code table.recordClass()}
     * (the two name the same class by construction, so no separate component is carried, mirroring
     * {@link NodeIdDecodeRecord} which derives its record class from {@code table} too). The two binding
     * axes are orthogonal — {@code columnBindings} is the SET payload, {@code keyDecode} is the identity
     * — and either may be empty (a record built from only an identity, or only plain columns), but not
     * both.
     *
     * <p>Produced only by {@code InputBeanResolver} (the SDL-aware post-processor that already holds the
     * classified type), every {@code JooqRecord} is fully resolved: a non-null {@code table}, resolved
     * {@code ColumnRef}s on every binding, and a present-or-absent (never partial) {@code keyDecode}.
     * Consumed by {@code JooqRecordInstantiationEmitter} (the {@code create<Record>} /
     * {@code create<Record>List} helper pair, reached from either the root-coordinate
     * {@code ServiceMethodCallEmitter} via {@link no.sikt.graphitron.rewrite.model.ValueShape.JooqRecordInput}
     * or the child-coordinate {@code ArgCallEmitter} directly), and registered into the helper queue by
     * {@code TypeFetcherGenerator}'s dual walk. It is a top-level {@code param.extraction()}, like
     * {@link InputBean} and unlike {@link NodeIdDecodeRecord}; it is never an {@link InputBean} field
     * leaf, so the bean-field-leaf switch ({@code InputBeanInstantiationEmitter.perFieldValueExpr})
     * treats it as unreachable-by-construction.
     */
    record JooqRecord(TableRef table,
                      List<ColumnBinding> columnBindings,
                      Optional<RecordKeyDecode> keyDecode) implements CallSiteExtraction {
        public JooqRecord {
            if (table == null) {
                throw new IllegalArgumentException("JooqRecord table must be non-null");
            }
            columnBindings = List.copyOf(columnBindings);
            if (keyDecode == null) {
                throw new IllegalArgumentException("JooqRecord keyDecode must be non-null (use Optional.empty())");
            }
            // At-least-one-binding floor: an input with neither a SET column nor an identity decode
            // would construct an empty record, the column-axis analogue of InputBean's empty-bindings
            // rejection. Producers reject such an input before reaching the constructor; this is the
            // structural backstop.
            if (columnBindings.isEmpty() && keyDecode.isEmpty()) {
                throw new IllegalArgumentException(
                    "JooqRecord must carry at least one column binding or a key decode");
            }
        }
    }

    /**
     * One plain ({@code @field}) input field bound on the column axis. {@code sdlFieldName} is the
     * GraphQL input field name and the {@code Map} key the {@code create<Record>} helper reads the wire
     * value from; {@code column} is the <em>resolved</em> {@link ColumnRef} (not a raw {@code @field(name:)}
     * string) on the enclosing {@link JooqRecord#table()}, so the emitter reaches the typed
     * {@code Tables.<T>.<col>} field with no re-parsing. Column-axis sibling to the member-axis
     * {@link FieldBinding}; a genuinely different axis, hence a separate record. No list flag: a scalar
     * column cannot take a list value, and the absence documents that.
     */
    record ColumnBinding(String sdlFieldName, ColumnRef column) {
        public ColumnBinding {
            if (sdlFieldName == null || sdlFieldName.isEmpty()) {
                throw new IllegalArgumentException("ColumnBinding sdlFieldName must be non-empty");
            }
            if (column == null) {
                throw new IllegalArgumentException("ColumnBinding column must be non-null");
            }
        }
    }

    /**
     * The single {@code @nodeId} identity field of a {@link JooqRecord}: the decoded key columns that
     * <em>are</em> the record's identity. The wire mechanism is R195's
     * ({@code encoderClass.decodeValues(typeId, nodeId)} then a positional
     * {@code record.fromArray(values, Tables.<T>.<keyCol>...)}); only the projection target differs from
     * {@link NodeIdDecodeRecord}, so this is a distinct carrier rather than a reuse.
     *
     * <p>Unlike {@link NodeIdDecodeRecord} (which rides as a {@link FieldBinding} leaf and inherits its
     * {@code Map} key from {@link FieldBinding#sdlFieldName()}), a {@code RecordKeyDecode} is a bare
     * {@link Optional} on {@link JooqRecord} with no enclosing {@code FieldBinding}, so it must carry its
     * own {@code sdlFieldName} — the {@code Map} key the helper writes {@code raw.get("<idField>")} for.
     * It carries no {@code nonNull}: a {@code @nodeId} at the record's identity always decodes the key
     * that is the record, so a null or type-mismatched id throws (R195 {@code ThrowOnMismatch}) whether
     * the SDL field is {@code ID!} or {@code ID}, leaving nothing for a nullability flag to vary.
     * {@code keyColumns} is the resolved key (one entry for a single-key table, N for a composite key),
     * each a {@link ColumnRef} on the enclosing {@link JooqRecord#table()}.
     */
    record RecordKeyDecode(String sdlFieldName, ClassName encoderClass, String typeId,
                           List<ColumnRef> keyColumns) {
        public RecordKeyDecode {
            if (sdlFieldName == null || sdlFieldName.isEmpty()) {
                throw new IllegalArgumentException("RecordKeyDecode sdlFieldName must be non-empty");
            }
            if (encoderClass == null) {
                throw new IllegalArgumentException("RecordKeyDecode encoderClass must be non-null");
            }
            if (typeId == null || typeId.isEmpty()) {
                throw new IllegalArgumentException("RecordKeyDecode typeId must be non-empty");
            }
            if (keyColumns == null || keyColumns.isEmpty()) {
                throw new IllegalArgumentException("RecordKeyDecode keyColumns must be non-empty");
            }
            keyColumns = List.copyOf(keyColumns);
        }
    }
}
