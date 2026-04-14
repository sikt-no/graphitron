package no.sikt.graphitron.rewrite.model;

import java.util.Map;

/**
 * How to extract one argument value from a GraphQL execution context at the fetcher call site.
 *
 * <p>Used in {@link CallParam} to tell the fetcher generator exactly what code to emit for each
 * argument passed to a condition or ordering method. The generator switches on this type once and
 * emits the corresponding expression — no other decisions are made at generation time.
 *
 * <ul>
 *   <li>{@link Direct} — {@code env.getArgument("name")}</li>
 *   <li>{@link EnumValueOf} — {@code EnumClass.valueOf(env.<String>getArgument("name"))},
 *       null-guarded when the argument is nullable.</li>
 *   <li>{@link TextMapLookup} — {@code ConditionsClass.MAP_FIELD.get(env.<String>getArgument("name"))},
 *       null-guarded when the argument is nullable. The map is a generated {@code static final}
 *       field on the {@code *Conditions} class.</li>
 *   <li>{@link ContextArg} — {@code graphitronContext(env).getContextArgument(env, "name")}</li>
 * </ul>
 */
public sealed interface CallSiteExtraction
        permits CallSiteExtraction.Direct, CallSiteExtraction.EnumValueOf,
                CallSiteExtraction.TextMapLookup, CallSiteExtraction.ContextArg {

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
}
