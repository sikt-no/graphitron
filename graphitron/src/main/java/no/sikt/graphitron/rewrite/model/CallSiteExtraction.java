package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;

import java.util.List;

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
     * column Java type by binding it through the column's {@code DataType} (and any registered
     * {@code Converter}) via {@code DSL.val(Object, DataType<T>).getValue()} — the non-deprecated
     * replacement for {@code DataType.convert(Object)} (forRemoval in jOOQ 3.20; R384 phase a).
     *
     * <p>{@code columnJavaName} is the jOOQ field constant name (e.g. {@code "FILM_ID"}) used to
     * reach the target {@code DataType} from the table alias:
     * <ul>
     *   <li>Scalar: {@code DSL.val(env.getArgument("film_id"), table.FILM_ID.getDataType()).getValue()}</li>
     *   <li>List (with {@link CallParam#list()} / {@link BodyParam#list()}): local variable
     *       {@code List<String> filmIdKeys = env.getArgument("film_id")} is declared before the
     *       condition call, then passed as
     *       {@code filmIdKeys.stream().map(k -> DSL.val(k, table.FILM_ID.getDataType()).getValue()).toList()}.</li>
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
     *   <li>{@link SkipMismatchedElement} — a {@code null} return short-circuits the bad element to
     *       "no row matches"; never throws. After R378 this arm is produced <em>only</em> by the
     *       legacy {@code __NODE_*} synthesis shims (on the {@code retire-synthesis-shims} track);
     *       authored {@code @nodeId} filters no longer use it.</li>
     *   <li>{@link ThrowOnMismatch} — every authored {@code @nodeId} argument or input-object-field
     *       filter ({@code [ID!] @nodeId(typeName: T)} and the scalar analogue), plus {@code @nodeId}
     *       lookup / mutation keys. A {@code null} return is a client mistake and surfaces as a
     *       {@code GraphitronClientException} (a {@code GraphQLError}), not a silent drop; the decode
     *       helper's message distinguishes a structurally-malformed id from a well-formed wrong-type
     *       id (R378). For filters this gives up the Relay heterogeneous-id-source pattern by design:
     *       one bad element fails the whole field rather than narrowing the set.</li>
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
     * Skip the bad element on a {@code null} decode return. After R378 this arm is produced only by
     * the legacy {@code __NODE_*} synthesis shims ({@code retire-synthesis-shims} track); authored
     * {@code @nodeId} filters classify to {@link ThrowOnMismatch} instead.
     */
    record SkipMismatchedElement(HelperRef.Decode decodeMethod) implements NodeIdDecodeKeys {}

    /**
     * Throw the generated {@code GraphitronClientException} (a {@code GraphQLError}) on a
     * {@code null} decode return, with a message distinguishing malformed input from a well-formed
     * wrong-type id (R378). Used by every authored {@code @nodeId} filter (argument or
     * input-object-field) and by {@code @nodeId} lookup / mutation keys.
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
     * (carried as a resolved {@link ColumnBinding}), and each {@code @nodeId} field decodes a node
     * identity into resolved target columns on this record (carried as a {@link RecordKeyDecode}). A
     * record may carry several {@code @nodeId} fields (R315): a same-table identity decode loads the
     * record's own key columns (R311), a cross-table FK-reference decode loads the foreign key's child
     * columns on this record (the common "status / history / junction row" shape).
     *
     * <p>A field whose SDL type is itself a directiveless nested grouping input flattens transparently
     * onto this one table (R336): the resolver recurses into the nested type's fields and keeps producing
     * {@link ColumnBinding} / {@link RecordKeyDecode} carriers, each carrying the full access
     * {@code path} from the record's own {@code Map} down to the leaf. The binding lists are therefore a
     * flat projection of an arbitrarily-nested input onto the table's columns, with depth recorded only on
     * the per-binding path; there is no nested-record sub-structure here.
     *
     * <p>{@code table} is read straight off the param's classified
     * {@link GraphitronType.JooqTableRecordInputType}; the record class is {@code table.recordClass()}
     * (the two name the same class by construction, so no separate component is carried, mirroring
     * {@link NodeIdDecodeRecord} which derives its record class from {@code table} too). The two binding
     * axes are orthogonal — {@code columnBindings} is the SET payload, {@code keyDecodes} are the
     * {@code @nodeId}-decoded identities / FK references — and either may be empty (a record built from
     * only identities/references, or only plain columns), but not both.
     *
     * <p>Produced only by {@code InputBeanResolver} (the SDL-aware post-processor that already holds the
     * classified type), every {@code JooqRecord} is fully resolved: a non-null {@code table}, resolved
     * {@code ColumnRef}s on every binding, and a fully-resolved (possibly empty) {@code keyDecodes} list.
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
                      List<RecordKeyDecode> keyDecodes) implements CallSiteExtraction {
        public JooqRecord {
            if (table == null) {
                throw new IllegalArgumentException("JooqRecord table must be non-null");
            }
            columnBindings = List.copyOf(columnBindings);
            if (keyDecodes == null) {
                throw new IllegalArgumentException("JooqRecord keyDecodes must be non-null (use List.of())");
            }
            keyDecodes = List.copyOf(keyDecodes);
            // At-least-one-binding floor: an input with neither a SET column nor a key/reference decode
            // would construct an empty record, the column-axis analogue of InputBean's empty-bindings
            // rejection. Producers reject such an input before reaching the constructor; this is the
            // structural backstop.
            if (columnBindings.isEmpty() && keyDecodes.isEmpty()) {
                throw new IllegalArgumentException(
                    "JooqRecord must carry at least one column binding or a key decode");
            }
        }
    }

    /**
     * One plain ({@code @field}) input field bound on the column axis. {@code path} is the ordered,
     * non-empty access path from the record's own input {@code Map} down to the leaf field: the last
     * element is the leaf SDL field name (the {@code Map} key the {@code create<Record>} helper reads the
     * wire value from), and any earlier elements are the enclosing nested-grouping-input field names a
     * flatten descends through (R336). A top-level binding carries a single-element path; the nested
     * {@code details.title} carries {@code ["details", "title"]}. This adopts the access-path
     * representation {@link NestedInputField} settled (R186), scoped to the keys from the record's own
     * {@code Map} down to the leaf (the outer argument name stays on the enclosing
     * {@link no.sikt.graphitron.rewrite.model.ValueShape.JooqRecordInput}, not duplicated here).
     *
     * <p>{@code column} is the <em>resolved</em> {@link ColumnRef} (not a raw {@code @field(name:)} string)
     * on the enclosing {@link JooqRecord#table()}, so the emitter reaches the typed {@code Tables.<T>.<col>}
     * field with no re-parsing. Column-axis sibling to the member-axis {@link FieldBinding}; a genuinely
     * different axis, hence a separate record. No list flag: a scalar column cannot take a list value, and
     * the absence documents that.
     */
    record ColumnBinding(List<String> path, ColumnRef column) {
        public ColumnBinding {
            if (path == null || path.isEmpty()) {
                throw new IllegalArgumentException("ColumnBinding path must be non-empty");
            }
            for (var element : path) {
                if (element == null || element.isEmpty()) {
                    throw new IllegalArgumentException("ColumnBinding path elements must be non-empty");
                }
            }
            path = List.copyOf(path);
            if (column == null) {
                throw new IllegalArgumentException("ColumnBinding column must be non-null");
            }
        }

        /** The leaf SDL field name: the last path element, the {@code Map} key for the wire value. */
        public String leaf() {
            return path.get(path.size() - 1);
        }
    }

    /**
     * One {@code @nodeId} decode of a {@link JooqRecord}: the wire NodeId is decoded
     * ({@code encoderClass.decodeValues(typeId, nodeId)}) and the decoded values load into
     * {@code targetColumns} on the enclosing {@link JooqRecord#table()}. Only the projection target
     * differs from {@link NodeIdDecodeRecord}, so this is a distinct carrier rather than a reuse.
     *
     * <p>Unlike {@link NodeIdDecodeRecord} (which rides as a {@link FieldBinding} leaf and inherits its
     * {@code Map} key from {@link FieldBinding#sdlFieldName()}), a {@code RecordKeyDecode} sits directly
     * on {@link JooqRecord} with no enclosing {@code FieldBinding}, so it carries its own {@code path} —
     * the ordered, non-empty access path from the record's own {@code Map} down to the {@code @nodeId}
     * field. The last element is the leaf field name (the {@code Map} key the helper decodes
     * {@code parentMap.get("<idField>")} from); earlier elements are enclosing nested-grouping-input field
     * names a flatten descends through (R336). A top-level decode carries a single-element path; the same
     * representation {@link ColumnBinding} uses, and the {@code @table}-input precedent {@link NestedInputField}
     * settled (R186).
     *
     * <p>{@code targetColumns} is the resolved list of columns <em>on this record</em> the decoded
     * values load into, in node-key (decode) order (one entry for a single-key NodeType, N for a
     * composite key). For a same-table identity decode (R311) these are the record's own key columns;
     * for a cross-table FK-reference decode (R315) they are the FK's child columns on this record,
     * resolved by FK-constraint pairing in {@code BuildContext}. That identity-vs-FK distinction lives
     * <em>only in the resolver</em>; the carrier holds the resolved target either way (see the R315
     * spec, D1: no {@code KeyProjection} sub-axis, because both arms load the columns identically).
     *
     * <p>{@code nonNull} reflects the SDL field's nullability ({@code ID!} vs {@code ID}) and drives
     * the emitter's null semantics (R315, D4), applied identically to identity and FK-reference decodes:
     * a {@code nonNull} ({@code ID!}) decode always loads, throwing on a null / type-mismatched id (R195);
     * a nullable ({@code ID}) decode is conditional on the wire key being present (omitted → columns left
     * unwritten / {@code changed=false}, present-{@code null} → columns set to {@code NULL},
     * present-value → decoded-and-loaded, a wrong-type decode still throwing). The {@code @service}
     * method owns the insert/update, so the framework does not force even a same-table identity to be
     * non-null; a nullable identity is a legitimate service-side upsert input.
     */
    record RecordKeyDecode(List<String> path, ClassName encoderClass, String typeId,
                           List<ColumnRef> targetColumns, boolean nonNull) {
        public RecordKeyDecode {
            if (path == null || path.isEmpty()) {
                throw new IllegalArgumentException("RecordKeyDecode path must be non-empty");
            }
            for (var element : path) {
                if (element == null || element.isEmpty()) {
                    throw new IllegalArgumentException("RecordKeyDecode path elements must be non-empty");
                }
            }
            path = List.copyOf(path);
            if (encoderClass == null) {
                throw new IllegalArgumentException("RecordKeyDecode encoderClass must be non-null");
            }
            if (typeId == null || typeId.isEmpty()) {
                throw new IllegalArgumentException("RecordKeyDecode typeId must be non-empty");
            }
            if (targetColumns == null || targetColumns.isEmpty()) {
                throw new IllegalArgumentException("RecordKeyDecode targetColumns must be non-empty");
            }
            targetColumns = List.copyOf(targetColumns);
        }

        /** The leaf SDL field name: the last path element, the {@code Map} key for the wire id. */
        public String leaf() {
            return path.get(path.size() - 1);
        }
    }
}
