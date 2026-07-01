package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;

/**
 * One argument in a condition or ordering method call, as seen from the fetcher call site.
 *
 * <p>Carries what the fetcher generator needs to emit the call: the argument name, how to extract
 * the value from the GraphQL execution context, whether the argument is a list, and the Java type
 * of the extracted value. No column information is included beyond what is embedded in the
 * {@link CallSiteExtraction} variant (e.g. {@link CallSiteExtraction.JooqConvert#columnJavaName()}).
 *
 * <p>{@code typeName} is the fully qualified Java class name for declaring a typed local variable
 * (e.g. {@code "java.lang.String"}, {@code "java.lang.Integer"},
 * {@code "no.example.jooq.enums.MpaaRating"}). Used by generators that assign the extracted value
 * to a local before passing it to the method call. Must be a simple class name; no generic
 * parameters.
 *
 * <p>{@code javaType} is the structured JavaPoet {@link TypeName} mirror of {@code typeName}, used
 * for the {@code $T.class}-style cast literals where a structural type is preferred over a string
 * round-trip through {@link ClassName#bestGuess(String)}. Production paths thread the reflected
 * {@link TypeName} from {@link MethodRef.Param.Typed#javaType()}; the 4-arg convenience
 * constructor below preserves the legacy behaviour for test scaffolding and body-param paths that
 * have only a string typeName.
 *
 * <p>The first parameter of every condition method is the table alias and is implicit; it is not
 * represented by a {@code CallParam}. Only the arguments after the table come from this list.
 */
public record CallParam(
    String name,
    CallSiteExtraction extraction,
    boolean list,
    String typeName,
    TypeName javaType
) {

    /**
     * Convenience constructor that derives {@code javaType} from the string {@code typeName} via
     * {@link ClassName#bestGuess(String)}, stripping any generic parameters first. Production
     * code that has a reflected {@link TypeName} (e.g. {@link MethodRef#callParams()}) passes it
     * through the canonical five-arg constructor; this overload covers test fixtures and body-param
     * paths whose backing data carries only the string form.
     */
    public CallParam(String name, CallSiteExtraction extraction, boolean list, String typeName) {
        this(name, extraction, list, typeName, deriveJavaType(typeName));
    }

    private static TypeName deriveJavaType(String typeName) {
        int lt = typeName.indexOf('<');
        String raw = lt < 0 ? typeName : typeName.substring(0, lt);
        return ClassName.bestGuess(raw);
    }

    /**
     * True when emitting this argument's extraction produces a Java unchecked cast, so the enclosing
     * generated method must carry {@code @SuppressWarnings("unchecked")}. The single source of truth
     * for this fact: every generator that hosts a condition-method call (the single-table
     * {@code QueryConditionsGenerator} method and the multitable {@code MultiTablePolymorphicEmitter}
     * fetcher) folds over its {@link CallParam}s and asks here, rather than each re-deriving the same
     * {@code list() && extraction instanceof …} predicate (Generation-thinking: a fact two consumers
     * branch on belongs on the model).
     *
     * <p>Today the only such shape is a list-typed {@link CallSiteExtraction.NestedInputField}, which
     * extracts as {@code (List<X>) map.get(key)} — {@code Map.get} is statically {@code Object} and
     * {@code List<X>} is not reifiable, so the cast is unchecked even though graphql-java has already
     * coerced the input-object field to {@code List<X>}. When a future extraction (e.g. R384's
     * {@code JooqConvert} lift) starts emitting an unchecked cast, its arm is added here once and both
     * hosts pick it up.
     */
    public boolean emitsUncheckedCast() {
        return list && extraction instanceof CallSiteExtraction.NestedInputField;
    }
}
