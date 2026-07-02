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
     * <p>Today the only such shape is a list-typed {@link CallSiteExtraction.NestedInputField} whose
     * leaf casts, which extracts as {@code (List<X>) map.get(key)} — {@code Map.get} is statically
     * {@code Object} and {@code List<X>} is not reifiable, so the cast is unchecked even though
     * graphql-java has already coerced the input-object field to {@code List<X>}. A
     * {@link CallSiteExtraction.JooqConvert} leaf does not cast (its {@code instanceof List<?>}
     * guard plus {@code DSL.val} coercion own the runtime shape), so it is carved out. If a future
     * extraction starts emitting an unchecked cast, its arm is added here once and both hosts pick
     * it up.
     */
    public boolean emitsUncheckedCast() {
        return list && extraction instanceof CallSiteExtraction.NestedInputField nif
            && !(nif.leaf() instanceof CallSiteExtraction.JooqConvert);
    }

    /**
     * True when emitting this argument under the {@code FromSelectedField} argument source (R424, the
     * two inline emission sites) produces a Java unchecked cast, so the enclosing {@code $fields}
     * method must carry {@code @SuppressWarnings("unchecked")}. This is the source-aware companion to
     * {@link #emitsUncheckedCast()}: the {@code $fields} host asks here, while the {@code Env} hosts
     * ({@code QueryConditionsGenerator}, {@code MultiTablePolymorphicEmitter}) ask
     * {@link #emitsUncheckedCast()}. Keeping both predicates on the model, keyed by source, is why
     * broadening the source-agnostic {@code emitsUncheckedCast} would wrongly stamp the {@code Env}
     * hosts (whose reads target-type and are warning-free) and break their byte-identical output.
     *
     * <p>The casts that exist only under {@code FromSelectedField} (because {@code Map.get} is
     * statically {@code Object}, unlike {@code env.getArgument}'s generic target-typing):
     * <ul>
     *   <li>a list-typed {@link CallSiteExtraction.Direct} arg — extracts as {@code (List) sf.getArguments().get(name)};</li>
     *   <li>a list-typed {@link CallSiteExtraction.JooqConvert} arg — its {@code <name>Keys} pre-lift
     *       casts to {@code (List<String>)};</li>
     *   <li>a list-typed {@link CallSiteExtraction.NestedInputField} whose leaf casts — identical to
     *       the {@code Env} case (the leaf cast is source-independent), so this one also shows up in
     *       {@link #emitsUncheckedCast()}. A {@link CallSiteExtraction.JooqConvert} leaf is carved out
     *       (its {@code instanceof List<?>} guard + {@code DSL.val} coercion own the runtime shape).</li>
     * </ul>
     * Scalar {@code Direct} / {@code EnumValueOf} casts are checked (reifiable target); the
     * {@code first} pagination read casts to {@code (Integer)}, also checked; neither counts.
     */
    public boolean emitsUncheckedCastFromSelectedField() {
        if (!list) return false;
        if (extraction instanceof CallSiteExtraction.Direct) return true;
        if (extraction instanceof CallSiteExtraction.JooqConvert) return true;
        if (extraction instanceof CallSiteExtraction.NestedInputField nif) {
            return !(nif.leaf() instanceof CallSiteExtraction.JooqConvert);
        }
        return false;
    }
}
