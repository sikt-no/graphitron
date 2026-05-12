package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;

import java.util.List;
import java.util.Map;

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
 *   <li>{@link TextMapLookup} — {@code ConditionsClass.MAP_FIELD.get(env.<String>getArgument("name"))},
 *       null-guarded when the argument is nullable. The map is a generated {@code static final}
 *       field on the {@code *Conditions} class.</li>
 *   <li>{@link ContextArg} — {@code graphitronContext(env).getContextArgument(env, "name")}</li>
 *   <li>{@link NestedInputField} — traverse a nested input-object graph starting from a
 *       top-level argument Map and descending through {@code path} keys, null-safe at every
 *       level; used for {@code @condition} on {@code INPUT_FIELD_DEFINITION}.</li>
 * </ul>
 */
public sealed interface CallSiteExtraction
        permits CallSiteExtraction.Direct, CallSiteExtraction.EnumValueOf,
                CallSiteExtraction.TextMapLookup, CallSiteExtraction.ContextArg,
                CallSiteExtraction.JooqConvert, CallSiteExtraction.NestedInputField,
                CallSiteExtraction.NodeIdDecodeKeys, CallSiteExtraction.InputBean {

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
     * Look up the database string for a GraphQL enum value via a generated static map:
     * {@code ConditionsClass.MAP_FIELD.get(env.<String>getArgument("name"))}.
     *
     * <p>{@code mapFieldName} is the name of the generated {@code static final Map<String,String>}
     * field on the {@code *Conditions} class (e.g. {@code "FILMS_TEXTRATING_MAP"}).
     *
     * <p>{@code valueMapping} is the pre-resolved mapping from GraphQL enum value names to
     * database string values, used by {@link no.sikt.graphitron.rewrite.generators.TypeConditionsGenerator}
     * to generate the map's initializer.
     */
    record TextMapLookup(String mapFieldName, Map<String, String> valueMapping) implements CallSiteExtraction {}

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
     * it is the element type, not the wrapping {@code List}.
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
}
