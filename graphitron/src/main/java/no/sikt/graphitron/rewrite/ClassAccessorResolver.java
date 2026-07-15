package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.AccessorProbe;
import no.sikt.graphitron.rewrite.model.AccessorResolution;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves an SDL field's accessor against its parent's backing Java class. The single home for the
 * SDL-field-to-Java-accessor name rules, the {@code is}-prefix gate, the member filter, and the
 * per-kind parameter-shape rules (R461 consolidation): every call site that used to re-implement
 * these now shares the {@link #enumerate} candidate model, so a fix to a rule lands once.
 *
 * <p>Mirrors graphql-java's {@code PropertyDataFetcher} candidate-name lookup so a consumer
 * migrating from {@code PropertyDataFetcher} to graphitron's class-backed fetcher gets
 * matching name resolution. Candidate order: {@code get<Camel>} (POJO bean getter), {@code
 * is<Camel>} (only when the candidate member's own return type is {@code boolean} / {@code
 * Boolean}), bare {@code <camel>} (Java record component / fluent accessor), and finally a public
 * field read on {@code <camel>}.
 *
 * <p>Two reductions consume one candidate model:
 * <ul>
 *   <li>{@link #resolve} — the property-read reduction: given an expected return type and argument
 *       shape, the first name+shape+return match wins.</li>
 *   <li>{@link #probe} — the discovery-direction reduction: no expected return, the first
 *       name+shape match grounds a backing class. Used by the R96 binding walk.</li>
 * </ul>
 * The record-source reduction ({@code FieldBuilder.collectAccessorMatches}) consumes the same
 * {@link #enumerate} directly, requesting zero-arg methods only.
 *
 * <p>Joins the rewrite's reflection roster ({@code ServiceCatalog}, {@code FieldBuilder}'s
 * payload-errors path, {@code SourceRowDirectiveResolver}) — see
 * {@code docs/architecture/explanation/development-principles.adoc} ("The parse boundary is a containment invariant") for the boundary rule.
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
     * The binding walk, which runs before any {@code ResultType} exists, derives the order from
     * {@link #forBackingClass} instead; a meta-test pins the two derivations equal.
     */
    public enum CandidateOrder { POJO_FIRST, RECORD_FIRST }

    /**
     * The single place the class-shape-to-candidate-order rule lives, for the phase (the R96 binding
     * walk) that has a backing {@link Class} but no {@code ResultType} variant to read the order
     * from. A Java record gets {@code RECORD_FIRST} (bare component accessor first); anything else
     * gets {@code POJO_FIRST} (bean getter first). {@code TypeBuilder.buildResultTypeFromClass}
     * produces a {@code JavaRecordType} exactly when {@code cls.isRecord()}, so this equals the
     * emission-side order derived from the variant; the equivalence is pinned by a meta-test rather
     * than left to coincidence of predicates.
     */
    public static CandidateOrder forBackingClass(Class<?> cls) {
        return cls.isRecord() ? CandidateOrder.RECORD_FIRST : CandidateOrder.POJO_FIRST;
    }

    /**
     * The candidate kinds a consumer of {@link #enumerate} accepts. The enumeration is parametric in
     * this set so each reduction sees only the arms it can consume: the property-read reduction and
     * the probe accept all three, while the record-source reduction accepts
     * {@link #PER_ARGUMENT_METHOD} only (a field, env-taking, or per-argument candidate is
     * unrepresentable in its view — {@code KeyLift.Accessor} emits {@code parent.method()}
     * with no environment or arguments), so a candidate it structurally cannot emit is never produced
     * rather than filtered out by a local rule that could drift.
     */
    public enum CandidateKind {
        /** A method whose parameters match the expected per-argument shape (arity + per-argument
         *  assignability). A zero-argument method is the empty case of this kind. */
        PER_ARGUMENT_METHOD,
        /** A method taking a single {@code DataFetchingEnvironment}. */
        FULL_ENVIRONMENT_METHOD,
        /** A public-field read (graphql-java's {@code PropertyDataFetcher} last-resort fallback);
         *  offered only when the SDL field has no arguments. */
        PUBLIC_FIELD
    }

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
     * is type-compatible). An {@link Object} {@code javaType} marks the argument as unresolvable at
     * the calling phase and degrades that position to an arity-only check (see {@link #paramsMatch}).
     */
    public record ArgShape(String name, Type javaType) {}

    /**
     * One entry in the ordered candidate stream {@link #enumerate} produces. {@link Accepted} is a
     * member that passed the name rule, the {@code is}-gate, the member filter, and the parameter-shape
     * rule for one of the requested {@link CandidateKind}s. {@link NearMiss} is a member that
     * name-matched (and passed the member filter) but failed a gate — the reason feeds
     * {@link #resolve}'s diagnostic and {@link #probe}'s no-match reason ledger. {@link Absent} is a
     * bare/getter name with no member at all.
     */
    public sealed interface Candidate permits Candidate.Accepted, Candidate.NearMiss, Candidate.Absent {
        /**
         * {@code member} is the resolved {@link Method} or {@link Field}; {@code memberName} its name;
         * {@code prefixed} is {@code true} for a {@code get}/{@code is} bean accessor and {@code false}
         * for a bare accessor or field (drives the {@link AccessorResolution} arm); {@code genericReturnType}
         * is the member's generic return / field type.
         */
        record Accepted(Member member, String memberName, boolean prefixed, Type genericReturnType) implements Candidate {}
        record NearMiss(String reason) implements Candidate {}
        record Absent(String reason) implements Candidate {}
    }

    /**
     * The shared candidate enumeration: given a backing class, an accessor base name, the candidate
     * order, the accepted kinds, and the expected argument shape, produces the ordered stream of
     * candidates under the unified rule set. The name rules, the {@code is}-gate (an {@code is<Name>}
     * member is a candidate only when its own return type is {@code boolean}/{@code Boolean}), the
     * member filter (public, non-static, non-bridge, non-synthetic, not declared by {@code Object}),
     * and the per-kind parameter-shape rule are single-sourced here; each consumer's only per-call
     * input is its accepted kind set. Return-type assignability is <em>not</em> applied here (it is
     * {@link #resolve}'s expectation-checked concern).
     */
    public static List<Candidate> enumerate(
            Class<?> backingClass,
            String accessorBaseName,
            CandidateOrder order,
            Set<CandidateKind> kinds,
            ParamShape expectedArgs) {

        // An empty base name (e.g. an empty @field(name:) override) can name no member; there are no
        // candidates and no gated near-miss.
        if (accessorBaseName.isEmpty()) return List.of();

        String camel = accessorBaseName;
        String capitalised = Character.toUpperCase(camel.charAt(0)) + camel.substring(1);
        String getterName = "get" + capitalised;
        String isName = "is" + capitalised;

        // One name slot: the candidate name, whether it is a bean-prefixed accessor, and whether it
        // is the is<Name> slot (which the is-gate applies to).
        record Slot(String name, boolean prefixed, boolean isSlot) {}
        List<Slot> slots = order == CandidateOrder.RECORD_FIRST
            ? List.of(new Slot(camel, false, false), new Slot(getterName, true, false), new Slot(isName, true, true))
            : List.of(new Slot(getterName, true, false), new Slot(isName, true, true), new Slot(camel, false, false));

        var out = new ArrayList<Candidate>();
        for (Slot slot : slots) {
            boolean anyNameMatch = false;
            for (Method m : backingClass.getMethods()) {
                if (!passesMemberFilter(m)) continue;
                if (!m.getName().equals(slot.name())) continue;
                if (slot.isSlot() && !isBooleanType(m.getGenericReturnType())) {
                    // is-gate: a non-boolean is<Name> is a gated near-miss, recorded so a walk
                    // tightening that drops such a grounding surfaces the boolean gate as the reason.
                    out.add(new Candidate.NearMiss("found `" + describeMethod(m)
                        + "`, but an is<Name> accessor must return boolean/Boolean"));
                    continue;
                }
                anyNameMatch = true;
                if (kinds.contains(CandidateKind.FULL_ENVIRONMENT_METHOD) && isFullEnvironment(m)) {
                    out.add(new Candidate.Accepted(m, m.getName(), slot.prefixed(), m.getGenericReturnType()));
                    continue;
                }
                if (kinds.contains(CandidateKind.PER_ARGUMENT_METHOD) && paramsMatch(m, expectedArgs)) {
                    out.add(new Candidate.Accepted(m, m.getName(), slot.prefixed(), m.getGenericReturnType()));
                    continue;
                }
                out.add(new Candidate.NearMiss("found `" + describeMethod(m)
                    + "`, but parameter shape does not match " + describeParamShape(expectedArgs)));
            }
            // Absent line only for the bare / getter slots. graphql-java tries is<Name> only for
            // boolean fields, so a missing is<Name> is not reported as an absence.
            if (!slot.isSlot() && !anyNameMatch) {
                out.add(new Candidate.Absent("no method named `" + slot.name() + "`"));
            }
        }

        // Public-field fallback: last-resort, and only when the SDL field has no arguments (a public
        // field cannot accept arguments). A same-named field on an argument-bearing SDL field is a
        // gated near-miss, not a match.
        if (kinds.contains(CandidateKind.PUBLIC_FIELD)) {
            boolean noArgs = expectedArgs instanceof PerArgument pa && pa.args().isEmpty();
            for (Field f : backingClass.getFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!f.getName().equals(camel)) continue;
                if (noArgs) {
                    out.add(new Candidate.Accepted(f, f.getName(), false, f.getGenericType()));
                } else {
                    out.add(new Candidate.NearMiss("public field `" + camel
                        + "` exists but the SDL field declares arguments; public-field fallback "
                        + "applies only to no-argument fields"));
                }
            }
        }
        return out;
    }

    /**
     * Resolves the accessor on {@code backingClass} that matches {@code accessorBaseName}'s
     * candidate-name set, parameter shape, and return type. First name+shape+return match wins.
     *
     * @return a {@link AccessorResolution.Resolved} arm carrying the resolved
     *     {@link Method} / {@link Field}, or {@link AccessorResolution.Rejected} with an
     *     actionable diagnostic listing each candidate tried and why it was rejected.
     */
    public static AccessorResolution resolve(
            Class<?> backingClass,
            String accessorBaseName,
            Type expectedReturnJavaType,
            ParamShape expectedArgs,
            CandidateOrder order) {

        var candidates = enumerate(backingClass, accessorBaseName, order,
            EnumSet.of(CandidateKind.PER_ARGUMENT_METHOD, CandidateKind.FULL_ENVIRONMENT_METHOD,
                CandidateKind.PUBLIC_FIELD),
            expectedArgs);

        String returnMismatch = null;
        var otherReasons = new ArrayList<String>();
        for (Candidate c : candidates) {
            switch (c) {
                case Candidate.Accepted a -> {
                    if (a.member() instanceof Field f) {
                        if (isAssignable(expectedReturnJavaType, f.getGenericType())) {
                            return new AccessorResolution.FieldRead(f);
                        }
                        otherReasons.add("public field `" + f.getName() + "` of type "
                            + simpleTypeName(f.getGenericType()) + " is not assignable to "
                            + simpleTypeName(expectedReturnJavaType));
                    } else {
                        Method m = (Method) a.member();
                        if (isAssignable(expectedReturnJavaType, m.getGenericReturnType())) {
                            return a.prefixed()
                                ? new AccessorResolution.GetterPrefixed(m)
                                : new AccessorResolution.BareName(m);
                        }
                        if (returnMismatch == null) {
                            returnMismatch = "found `" + describeMethod(m) + "`, but return type "
                                + simpleTypeName(m.getGenericReturnType())
                                + " is not assignable to " + simpleTypeName(expectedReturnJavaType);
                        }
                    }
                }
                case Candidate.NearMiss nm -> otherReasons.add(nm.reason());
                case Candidate.Absent ab -> otherReasons.add(ab.reason());
            }
        }

        var parts = new ArrayList<String>();
        if (returnMismatch != null) parts.add(returnMismatch);
        parts.addAll(otherReasons);
        return new AccessorResolution.Rejected("no accessor on `" + backingClass.getName()
            + "` matched SDL field — tried: " + String.join("; ", parts));
    }

    /**
     * The discovery-direction reduction: resolves which member the SDL field reads on
     * {@code backingClass} without an expected return type. First name+shape match in candidate
     * order grounds. Used by the R96 binding walk to ground a child backing class and to name the
     * resolved accessor on {@code ProducerBinding.ParentAccessor}, and as a pure presence probe for
     * the R329 carrier discrimination.
     *
     * @return {@link AccessorProbe.Grounded} carrying the resolved member and its generic return
     *     type, or {@link AccessorProbe.NoMatch} whose reason names the closest gated near-miss
     *     (arity, boolean-{@code is}, field-fallback-with-arguments) when one exists.
     */
    public static AccessorProbe probe(
            Class<?> backingClass,
            String accessorBaseName,
            ParamShape expectedArgs,
            CandidateOrder order) {

        var candidates = enumerate(backingClass, accessorBaseName, order,
            EnumSet.of(CandidateKind.PER_ARGUMENT_METHOD, CandidateKind.FULL_ENVIRONMENT_METHOD,
                CandidateKind.PUBLIC_FIELD),
            expectedArgs);

        var nearMisses = new ArrayList<String>();
        for (Candidate c : candidates) {
            switch (c) {
                case Candidate.Accepted a ->
                    { return new AccessorProbe.Grounded(a.member(), a.memberName(), a.genericReturnType()); }
                case Candidate.NearMiss nm -> nearMisses.add(nm.reason());
                case Candidate.Absent ignored -> { /* plain absence is not a gated near-miss */ }
            }
        }
        String reason = nearMisses.isEmpty()
            ? "no accessor named `" + accessorBaseName + "` (or get/is variant) on `"
                + backingClass.getName() + "`"
            : "on `" + backingClass.getName() + "`, " + String.join("; ", nearMisses);
        return new AccessorProbe.NoMatch(reason, !nearMisses.isEmpty());
    }

    /**
     * Public, non-static, non-bridge, non-synthetic, not declared by {@code Object}. {@code
     * getMethods()} already restricts to public members, so this adds the remaining exclusions;
     * skipping bridge/synthetic members closes a latent bug where a covariant-return bridge method's
     * erased return type could win the name match.
     */
    private static boolean passesMemberFilter(Method m) {
        if (Modifier.isStatic(m.getModifiers())) return false;
        if (m.isBridge() || m.isSynthetic()) return false;
        return m.getDeclaringClass() != Object.class;
    }

    private static boolean isFullEnvironment(Method m) {
        var params = m.getParameterTypes();
        return params.length == 1 && params[0] == DATA_FETCHING_ENVIRONMENT;
    }

    /**
     * A method's parameter list matches the {@code expected} per-argument shape when it has the same
     * arity as {@code expected.args()} with each declared parameter type assignable from the
     * corresponding {@link ArgShape#javaType()}. An {@link Object} {@code javaType} (an argument the
     * calling phase could not resolve to a concrete Java type) degrades that position to an arity-only
     * check: arity stays authoritative in both phases, while per-argument type assignability is
     * best-effort in the walk and strictest at emission. The full-environment form is a separate
     * {@link CandidateKind}, matched in {@link #enumerate}, not here.
     */
    private static boolean paramsMatch(Method m, ParamShape expected) {
        if (!(expected instanceof PerArgument pa)) return false;
        var params = m.getGenericParameterTypes();
        if (params.length != pa.args().size()) return false;
        for (int i = 0; i < params.length; i++) {
            Type argType = pa.args().get(i).javaType();
            if (rawClass(argType) == Object.class) continue; // unresolvable argument: arity-only
            if (!isAssignable(params[i], argType)) {
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
     * argument's expected Java type. Both reduce to "is the source's raw class assignable to
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
