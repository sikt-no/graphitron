package no.sikt.graphitron.rewrite.model;

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
                CallSiteExtraction.NodeIdDecodeKeys {

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
     * <p>Traversal is null-safe: if any intermediate step is not a {@code Map} or is
     * {@code null}, the result is {@code null}. The condition method is still invoked; if it
     * receives {@code null}, it is responsible for returning a no-op filter.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code outerArgName="filter"}, {@code path=["filmId"]} for a direct
     *       {@code ColumnField.filmId} condition on a top-level input arg.</li>
     *   <li>{@code outerArgName="filter"}, {@code path=["where", "filmId"]} for a
     *       {@code ColumnField.filmId} inside a {@code NestingField.where}.</li>
     * </ul>
     */
    record NestedInputField(String outerArgName, List<String> path) implements CallSiteExtraction {
        public NestedInputField {
            if (path == null || path.isEmpty()) {
                throw new IllegalArgumentException("NestedInputField path must be non-empty");
            }
            path = List.copyOf(path);
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
     * federated _entities) and does not appear here as a carrier arm — see the R50 spec's
     * "Failure-mode contract" section.
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
}
