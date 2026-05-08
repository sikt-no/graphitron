package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.AccessorResolution;
import no.sikt.graphitron.rewrite.model.LoadBearingClassifierCheck;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves an SDL output field's accessor against its parent's backing Java class.
 *
 * <p>Mirrors graphql-java's {@code PropertyDataFetcher} candidate-name lookup so a consumer
 * migrating from {@code PropertyDataFetcher} to graphitron's {@code @record}-emitted fetcher gets
 * matching name resolution. Candidate order: {@code get<Camel>} (POJO bean getter), {@code
 * is<Camel>} (when the SDL field's resolved Java type is {@code boolean} / {@code Boolean}),
 * bare {@code <camel>} (Java record component / fluent accessor), and finally a public field
 * read on {@code <camel>}.
 *
 * <p>Joins the rewrite's reflection roster ({@code ServiceCatalog}, {@code FieldBuilder}'s
 * payload-errors path, {@code SourceRowDirectiveResolver}) — see
 * {@code docs/rewrite-design-principles.adoc} for the boundary rule.
 */
public final class ClassAccessorResolver {

    private static final Class<?> DATA_FETCHING_ENVIRONMENT;
    static {
        try {
            DATA_FETCHING_ENVIRONMENT = Class.forName("graphql.schema.DataFetchingEnvironment");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("graphql-java DataFetchingEnvironment not on classpath", e);
        }
    }

    private ClassAccessorResolver() {}

    /**
     * Candidate-list ordering. {@code POJO_FIRST} tries {@code get<Camel>} / {@code is<Camel>}
     * before bare-name, matching graphql-java's POJO-bean conventions.
     * {@code RECORD_FIRST} tries bare-name first (the canonical Java record component
     * accessor), then {@code get<Camel>} / {@code is<Camel>} as fallback for hand-rolled
     * methods on the record body. The classifier picks the order from the parent's
     * {@code ResultType} variant; the resolver does not introspect {@code backingClass.isRecord()}
     * itself, so the type variant remains the single source of truth for the candidate-order fork.
     */
    public enum CandidateOrder { POJO_FIRST, RECORD_FIRST }

    /**
     * The two graphql-java argument-injection forms a candidate accessor may match.
     */
    public sealed interface ParamShape permits FullEnvironment, PerArgument {}

    /** The accessor takes a single {@code DataFetchingEnvironment} parameter. */
    public record FullEnvironment() implements ParamShape {}

    /**
     * The accessor takes one parameter per SDL argument, in declared order. {@code List.of()}
     * for SDL fields with no arguments — a zero-param method is the per-arg form's empty case.
     */
    public record PerArgument(List<ArgShape> args) implements ParamShape {
        public PerArgument { args = List.copyOf(args); }
    }

    /**
     * One SDL argument's projection onto the resolver's parameter-assignability check:
     * {@code name} is the SDL argument name (used by the emitter as the key for
     * {@code env.getArgument(name)} when emitting the accessor call); {@code javaType} is the
     * argument's resolved Java type (used here to check the candidate method's declared parameter
     * is type-compatible).
     */
    public record ArgShape(String name, Type javaType) {}

    /**
     * Resolves the accessor on {@code backingClass} that matches {@code accessorBaseName}'s
     * candidate-name set, parameter shape, and return type.
     *
     * @return a {@link AccessorResolution.Resolved} arm carrying the resolved
     *     {@link Method} / {@link Field}, or {@link AccessorResolution.Rejected} with an
     *     actionable diagnostic listing each candidate tried and why it was rejected.
     */
    @LoadBearingClassifierCheck(
        key = "class-accessor-resolver-shape-guarantee",
        description = "AccessorResolution.Resolved guarantees a @record-backed SDL field's accessor "
            + "exists on the backing class with a return type assignable to the SDL field's Java "
            + "type and a param shape matching the SDL field's argument list. FieldBuilder routes a "
            + "Rejected outcome through UnclassifiedField rather than attaching it to PropertyField/"
            + "RecordField, so the slot the emitter consumes is statically AccessorResolution.Resolved "
            + "(or null, for parents that don't run reflective resolution at all). The "
            + "RecordFieldAccessorValidationTest pipeline tier exercises every Resolved arm and "
            + "every Rejected reason this method produces.")
    public static AccessorResolution resolve(
            Class<?> backingClass,
            String accessorBaseName,
            Type expectedReturnJavaType,
            ParamShape expectedArgs,
            CandidateOrder order) {

        String camel = accessorBaseName;
        String capitalised = Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
        String getterName = "get" + capitalised;
        String isName = "is" + capitalised;

        boolean booleanReturn = isBooleanType(expectedReturnJavaType);

        // Candidate accessors in resolution order. Each entry: candidate method name + arm factory.
        record Candidate(String name, java.util.function.Function<Method, AccessorResolution.Resolved> arm) {}
        var candidates = new ArrayList<Candidate>();
        if (order == CandidateOrder.RECORD_FIRST) {
            candidates.add(new Candidate(camel, AccessorResolution.BareName::new));
            candidates.add(new Candidate(getterName, AccessorResolution.GetterPrefixed::new));
            if (booleanReturn) candidates.add(new Candidate(isName, AccessorResolution.GetterPrefixed::new));
        } else {
            candidates.add(new Candidate(getterName, AccessorResolution.GetterPrefixed::new));
            if (booleanReturn) candidates.add(new Candidate(isName, AccessorResolution.GetterPrefixed::new));
            candidates.add(new Candidate(camel, AccessorResolution.BareName::new));
        }

        // First pass: find the first candidate name+shape+return that matches end-to-end.
        var perCandidateRejections = new ArrayList<String>();
        Method closestByName = null;
        String closestByNameDiag = null;
        for (Candidate cand : candidates) {
            Method byName = null;
            for (Method m : backingClass.getMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                if (!m.getName().equals(cand.name())) continue;
                byName = m;
                if (!paramsMatch(m, expectedArgs)) {
                    if (closestByName == null) {
                        closestByName = m;
                        closestByNameDiag = "found `" + describeMethod(m) + "`, but parameter shape does not match "
                            + describeParamShape(expectedArgs);
                    }
                    continue;
                }
                if (!isAssignable(expectedReturnJavaType, m.getGenericReturnType())) {
                    if (closestByName == null) {
                        closestByName = m;
                        closestByNameDiag = "found `" + describeMethod(m) + "`, but return type "
                            + simpleTypeName(m.getGenericReturnType())
                            + " is not assignable to " + simpleTypeName(expectedReturnJavaType);
                    }
                    continue;
                }
                return cand.arm().apply(m);
            }
            if (byName == null) {
                perCandidateRejections.add("no method named `" + cand.name() + "`");
            }
        }

        // Public-field fallback: only when the SDL field has no arguments (a public field cannot
        // accept arguments, so an SDL field with arguments that finds no method match falls
        // through even if a same-named public field exists).
        if (expectedArgs instanceof PerArgument pa && pa.args().isEmpty()) {
            for (Field f : backingClass.getFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!f.getName().equals(camel)) continue;
                if (!isAssignable(expectedReturnJavaType, f.getGenericType())) {
                    perCandidateRejections.add("public field `" + camel + "` of type "
                        + simpleTypeName(f.getGenericType()) + " is not assignable to "
                        + simpleTypeName(expectedReturnJavaType));
                    continue;
                }
                return new AccessorResolution.FieldRead(f);
            }
        }

        // Build the diagnostic.
        var diag = new StringBuilder();
        diag.append("no accessor on `").append(backingClass.getName())
            .append("` matched SDL field — tried: ");
        if (closestByNameDiag != null) {
            diag.append(closestByNameDiag);
            if (!perCandidateRejections.isEmpty()) {
                diag.append("; ").append(String.join("; ", perCandidateRejections));
            }
        } else {
            diag.append(String.join("; ", perCandidateRejections));
        }
        return new AccessorResolution.Rejected(diag.toString());
    }

    /**
     * A method's parameter list matches {@code expected} if either: it is exactly one
     * {@code DataFetchingEnvironment} (full-environment injection), or it has the same arity as
     * {@code expected.args()} with each declared parameter type assignable from the corresponding
     * {@link ArgShape#javaType()} (per-argument injection).
     */
    private static boolean paramsMatch(Method m, ParamShape expected) {
        var params = m.getGenericParameterTypes();
        // Full-env form: always acceptable.
        if (params.length == 1 && rawClass(params[0]) == DATA_FETCHING_ENVIRONMENT) {
            return true;
        }
        if (!(expected instanceof PerArgument pa)) return false;
        if (params.length != pa.args().size()) return false;
        for (int i = 0; i < params.length; i++) {
            if (!isAssignable(params[i], pa.args().get(i).javaType())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBooleanType(Type t) {
        Class<?> raw = rawClass(t);
        return raw == boolean.class || raw == Boolean.class;
    }

    /**
     * Whether a value of type {@code source} can flow into a slot of type {@code target}. The
     * resolver uses this in two directions: (a) the candidate method's return type vs. the SDL
     * field's expected Java type, and (b) the candidate method's parameter types vs. the SDL
     * argument's expected Java type. Both reduce to "is the source's raw class assignable from
     * the target's raw class" — the spec's argument-resolution rules are nominal-type-based, not
     * generic-aware, so we erase to raw classes here.
     */
    private static boolean isAssignable(Type target, Type source) {
        Class<?> targetRaw = rawClass(target);
        Class<?> sourceRaw = rawClass(source);
        if (targetRaw == null || sourceRaw == null) return true;
        if (targetRaw == Object.class) return true;
        if (targetRaw.isAssignableFrom(sourceRaw)) return true;
        // Primitive-vs-boxed equivalence: graphql-java boxes scalars, but a method may declare
        // a primitive parameter or return type. Treat them as compatible.
        return primitiveBoxedEquivalent(targetRaw, sourceRaw);
    }

    private static boolean primitiveBoxedEquivalent(Class<?> a, Class<?> b) {
        return box(a) == box(b);
    }

    private static Class<?> box(Class<?> c) {
        if (c == boolean.class) return Boolean.class;
        if (c == byte.class) return Byte.class;
        if (c == short.class) return Short.class;
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == float.class) return Float.class;
        if (c == double.class) return Double.class;
        if (c == char.class) return Character.class;
        return c;
    }

    private static Class<?> rawClass(Type t) {
        if (t instanceof Class<?> c) return c;
        if (t instanceof java.lang.reflect.ParameterizedType pt && pt.getRawType() instanceof Class<?> c) return c;
        return null;
    }

    private static String simpleTypeName(Type t) {
        Class<?> raw = rawClass(t);
        return raw != null ? raw.getSimpleName() : t.toString();
    }

    private static String describeMethod(Method m) {
        var sb = new StringBuilder();
        sb.append(m.getName()).append("(");
        var params = m.getGenericParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(simpleTypeName(params[i]));
        }
        sb.append("): ").append(simpleTypeName(m.getGenericReturnType()));
        return sb.toString();
    }

    private static String describeParamShape(ParamShape s) {
        return switch (s) {
            case FullEnvironment ignored -> "single-DataFetchingEnvironment";
            case PerArgument pa -> {
                if (pa.args().isEmpty()) yield "zero arguments";
                var sb = new StringBuilder("(");
                for (int i = 0; i < pa.args().size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(simpleTypeName(pa.args().get(i).javaType()));
                }
                sb.append(")");
                yield sb.toString();
            }
        };
    }
}
