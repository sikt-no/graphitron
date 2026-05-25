package no.sikt.graphitron.rewrite;

import graphql.schema.Coercing;
import graphql.schema.GraphQLScalarType;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.CoercingDeclarationKind;
import no.sikt.graphitron.rewrite.model.ScalarResolution;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Resolves a GraphQL scalar to its Java type + {@code GraphQLScalarType} constant via reflection.
 * The resolver returns a sealed {@link ScalarResolution} carrying either a
 * {@link ScalarResolution.Resolved} (with the JavaPoet {@link TypeName}, owner class, and field
 * name) or a typed {@link ScalarResolution.Rejected} arm naming the misconfiguration.
 *
 * <p>Phase 1 surfaces two entry points:
 *
 * <ul>
 *   <li>{@link #resolveBuiltIn(String)} for the five GraphQL spec built-ins
 *       ({@code Int}, {@code Float}, {@code String}, {@code Boolean}, {@code ID}). The
 *       SDL-name → Java-type binding for these is fixed by the GraphQL spec; the resolver
 *       still reflects on {@code graphql.Scalars.GraphQL{Int, Float, String, Boolean, ID}}
 *       to validate the constants and provide the owner / field-name pair for
 *       {@code additionalType} registration, but the Java type comes from a closed table.
 *       The carve-out exists because graphql-java's {@code GraphqlIDCoercing implements
 *       Coercing<Object, Object>}: routing {@code ID} through the same Coercing-reflection
 *       path consumer scalars use would mis-classify the built-in as erased.</li>
 *   <li>{@link #resolveFromConstantFqn(String, String, ClassLoader)} for the consumer-named
 *       constant path. Full reflection: looks up the {@code public static final
 *       GraphQLScalarType} on the named class via the supplied classloader, validates the
 *       field, and reflects on the {@code Coercing<I, O>} type parameters to recover the
 *       input Java type. Rejection arms (class missing, field missing, accessibility,
 *       null-at-codegen, wrong type, Coercing erased) drive per-arm LSP fix-its in a later
 *       phase. Phase 2 wires this entry point to the {@code @scalarType(scalar: "...")}
 *       directive; Phase 3 wires it to the extended-scalars convention layer.</li>
 * </ul>
 *
 * <p>Two narrowing invariants on {@link ScalarResolution.Resolved#javaType}:
 *
 * <ul>
 *   <li>Never {@code Object} from a raw / erased Coercing. For built-ins this is a closed
 *       table; for consumer scalars this is the post-condition of
 *       {@link #recoverInputType(Coercing)} guarded by the {@link ScalarResolution.Rejected.CoercingErased}
 *       arm.</li>
 *   <li>A JavaPoet {@link TypeName} ready to drop into a {@code MethodSpec} / {@code RecordSpec}
 *       declaration without further coercion.</li>
 * </ul>
 */
public final class ScalarTypeResolver {

    private ScalarTypeResolver() {}

    private static final String SCALARS_FQN = "graphql.Scalars";

    /**
     * Closed table for the five GraphQL spec built-ins. The {@code Class<?>} entries are the
     * canonical Java types graphql-java's argument-coercion path produces into input maps; the
     * field-name entries name the {@code public static final GraphQLScalarType} constant on
     * {@code graphql.Scalars}. Insertion order is preserved for diagnostic surfaces that want
     * to render the recognised set.
     */
    private static final Map<String, BuiltIn> SPEC_BUILT_INS;
    static {
        Map<String, BuiltIn> m = new LinkedHashMap<>();
        m.put("Int", new BuiltIn("GraphQLInt", Integer.class));
        m.put("Float", new BuiltIn("GraphQLFloat", Double.class));
        m.put("String", new BuiltIn("GraphQLString", String.class));
        m.put("Boolean", new BuiltIn("GraphQLBoolean", Boolean.class));
        m.put("ID", new BuiltIn("GraphQLID", String.class));
        SPEC_BUILT_INS = Map.copyOf(m);
    }

    private record BuiltIn(String fieldName, Class<?> javaType) {}

    /**
     * Federation-namespace scalars the federation-jvm transform injects after Graphitron has
     * emitted its base schema (see {@code Federation.transform()} in
     * {@code com.apollographql.federation.graphqljava}). At emit time these names appear in
     * applied-directive arguments (e.g. {@code @key(fields: "id")} where the {@code fields}
     * argument is typed {@code federation__FieldSet!}) but the scalar type itself isn't in the
     * assembled schema yet, so Graphitron emits {@link String} for the Java type and
     * {@code Scalars.GraphQLString} for the argument-type reference. The federation transform
     * later replaces the {@code GraphQLString} reference with its own scalar definition.
     *
     * <p>Pre-registering these names through the resolver replaces the previous silent fallback
     * in {@code AppliedDirectiveEmitter.emitInputType} ("unknown scalar name → GraphQLString")
     * with a typed {@link ScalarResolution.Resolved}. Phase 2 adds the recognition; Phase 3
     * routes {@code AppliedDirectiveEmitter} through the resolver to consume it.
     *
     * <p>The set tracks federation-jvm's {@code FederationDirectives.loadFederationSpecDefinitions}
     * output; bump the federation spec version in {@code FederationSpec.URL} when new namespaced
     * scalars land and extend this set in the same commit.
     */
    private static final Set<String> FEDERATION_NAMESPACE_SCALARS = Set.of(
        // Federation v2.x scalars (renamed to federation__X / link__X by the link-import pass).
        // The list tracks federation-jvm v6's definitions_fed2_12.graphqls; bump the federation
        // spec version pinned in CLAUDE.md when new scalars land and extend this set in the same
        // commit.
        "federation__FieldSet",
        "federation__Scope",
        "federation__Policy",
        "federation__ContextFieldValue",
        "_FieldSet",
        "link__Import",
        "link__Purpose"
    );

    /**
     * Resolves one of the five GraphQL spec built-ins by SDL name. Returns
     * {@link ScalarResolution.Rejected.FieldNotFound} for names not on the closed spec list
     * (caller is expected to route those through the consumer-declared / convention paths in
     * Phases 2 and 3).
     */
    public static ScalarResolution resolveBuiltIn(String scalarName) {
        BuiltIn entry = SPEC_BUILT_INS.get(scalarName);
        if (entry == null) {
            return new ScalarResolution.Rejected.FieldNotFound(SCALARS_FQN, scalarName);
        }
        ConstantCheck check = checkConstant(
            SCALARS_FQN, entry.fieldName(), ScalarTypeResolver.class.getClassLoader());
        if (check.rejection() != null) {
            return check.rejection();
        }
        return new ScalarResolution.Resolved(
            ClassName.get(entry.javaType()),
            ClassName.get("graphql", "Scalars"),
            entry.fieldName()
        );
    }

    /**
     * Looks up a {@code public static final GraphQLScalarType} on {@code classFqn} via
     * {@code loader}, validates the field, and reflects on the {@code Coercing<I, O>} type
     * parameters to recover the input Java type. Used by Phase 2's directive-read path and
     * Phase 3's convention layer.
     */
    public static ScalarResolution resolveFromConstantFqn(
        String classFqn, String fieldName, ClassLoader loader) {

        ConstantCheck check = checkConstant(classFqn, fieldName, loader);
        if (check.rejection() != null) {
            return check.rejection();
        }
        Coercing<?, ?> coercing = check.scalar().getCoercing();
        TypeName javaType = recoverInputType(coercing);
        if (javaType == null) {
            return new ScalarResolution.Rejected.CoercingErased(
                coercing.getClass().getName(),
                describeCoercingDeclaration(coercing)
            );
        }
        Class<?> owner = check.owner();
        return new ScalarResolution.Resolved(
            javaType,
            ClassName.get(owner.getPackageName(), owner.getSimpleName()),
            fieldName
        );
    }

    /**
     * Result of {@link #checkConstant}: either a typed rejection naming the misconfiguration,
     * or a {@code (owner, scalar)} pair the resolver's full path consumes. The two states are
     * disjoint: a non-null {@code rejection} pairs with a null {@code scalar}; a non-null
     * {@code scalar} pairs with a null {@code rejection} and a non-null {@code owner}.
     */
    private record ConstantCheck(
        ScalarResolution.Rejected rejection,
        Class<?> owner,
        GraphQLScalarType scalar
    ) {}

    /**
     * Validates that {@code classFqn.fieldName} exists, is public-static, is non-null, and is a
     * {@code GraphQLScalarType}.
     */
    private static ConstantCheck checkConstant(String classFqn, String fieldName, ClassLoader loader) {
        Class<?> owner;
        try {
            owner = Class.forName(classFqn, false, loader);
        } catch (ClassNotFoundException e) {
            return new ConstantCheck(new ScalarResolution.Rejected.ClassNotFound(classFqn), null, null);
        }
        Field field;
        try {
            field = owner.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            return new ConstantCheck(new ScalarResolution.Rejected.FieldNotFound(classFqn, fieldName), null, null);
        }
        boolean isPublic = Modifier.isPublic(field.getModifiers());
        boolean isStatic = Modifier.isStatic(field.getModifiers());
        if (!isPublic || !isStatic) {
            return new ConstantCheck(
                new ScalarResolution.Rejected.FieldNotAccessible(classFqn, fieldName, isPublic, isStatic),
                null, null);
        }
        Object value;
        try {
            value = field.get(null);
        } catch (IllegalAccessException e) {
            return new ConstantCheck(
                new ScalarResolution.Rejected.FieldNotAccessible(classFqn, fieldName, isPublic, isStatic),
                null, null);
        }
        if (value == null) {
            return new ConstantCheck(new ScalarResolution.Rejected.NullAtCodegen(classFqn, fieldName), null, null);
        }
        if (!(value instanceof GraphQLScalarType scalar)) {
            return new ConstantCheck(
                new ScalarResolution.Rejected.NotAScalarType(classFqn, fieldName, value.getClass().getName()),
                null, null);
        }
        return new ConstantCheck(null, owner, scalar);
    }

    /**
     * Returns the boxed Java type the Coercing's {@code I} parameter resolves to, or
     * {@code null} when {@code I} resolves to {@code Object} (raw, anonymous, or named-erased).
     * Walks the implementing type's interface list and supertype chain so a subclass like
     * {@code class MoneyCoercing extends AbstractTypedCoercing<Money, Money>} resolves through
     * the parent's generic declaration.
     */
    private static TypeName recoverInputType(Coercing<?, ?> coercing) {
        Class<?> raw = coercing.getClass();
        Class<?> input = findCoercingInputType(raw);
        if (input == null || input == Object.class) return null;
        return ClassName.get(box(input));
    }

    private static Class<?> findCoercingInputType(Class<?> startClass) {
        // BFS over interface + supertype edges, capturing the first concrete Coercing<I, ?> binding.
        Class<?> cls = startClass;
        while (cls != null && cls != Object.class) {
            for (Type iface : cls.getGenericInterfaces()) {
                Class<?> found = inputFromInterface(iface);
                if (found != null) return found;
            }
            Type sup = cls.getGenericSuperclass();
            Class<?> superInput = inputFromInterface(sup);
            if (superInput != null) return superInput;
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static Class<?> inputFromInterface(Type t) {
        if (t instanceof ParameterizedType pt && pt.getRawType() == Coercing.class) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length == 2 && args[0] instanceof Class<?> inputClass) {
                return inputClass;
            }
        }
        return null;
    }

    private static Class<?> box(Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == double.class) return Double.class;
        if (c == float.class) return Float.class;
        if (c == boolean.class) return Boolean.class;
        if (c == byte.class) return Byte.class;
        if (c == short.class) return Short.class;
        if (c == char.class) return Character.class;
        return c;
    }

    /**
     * Distinguishes the three erasure shapes a {@link CoercingDeclarationKind} arm names.
     * Order matters: anonymous-class detection beats raw/named-erased because an anonymous
     * class with an inferred-erased parameter looks like a named class to the interface walk.
     */
    private static CoercingDeclarationKind describeCoercingDeclaration(Coercing<?, ?> c) {
        Class<?> raw = c.getClass();
        if (raw.isAnonymousClass()) return CoercingDeclarationKind.ANONYMOUS_CLASS;
        for (Type iface : raw.getGenericInterfaces()) {
            if (iface == Coercing.class) return CoercingDeclarationKind.RAW_TYPE;
        }
        return CoercingDeclarationKind.ERASED_NAMED_CLASS;
    }

    /**
     * Verifies (without invoking the resolver) that a SDL name is one of the five GraphQL spec
     * built-ins. Used by callers wanting to short-circuit before constructing a rejection
     * payload (e.g., the Phase 2 directive-read path that rejects {@code @scalarType} on a
     * spec built-in name as a {@code Rejection.InvalidSchema.DirectiveConflict}).
     */
    public static boolean isSpecBuiltIn(String scalarName) {
        return SPEC_BUILT_INS.containsKey(scalarName);
    }

    /**
     * Verifies (without invoking the resolver) that a SDL name is a federation-namespace scalar
     * the federation-jvm transform injects after Graphitron has emitted its base schema. See
     * {@link #FEDERATION_NAMESPACE_SCALARS} for the rationale.
     */
    public static boolean isFederationNamespaceScalar(String scalarName) {
        return FEDERATION_NAMESPACE_SCALARS.contains(scalarName);
    }

    /**
     * Resolves a federation-namespace scalar to a {@link ScalarResolution.Resolved} binding to
     * {@code Scalars.GraphQLString} and {@link String} as the Java type. The federation-jvm
     * transform replaces the {@code GraphQLString} reference with its own scalar definition
     * after Graphitron's base schema is built; the {@link String} Java type matches the
     * runtime shape these scalars carry (a serialized selection-set, a scope string, etc.).
     */
    public static ScalarResolution resolveFederationNamespaceScalar(String scalarName) {
        if (!FEDERATION_NAMESPACE_SCALARS.contains(scalarName)) {
            return new ScalarResolution.Rejected.FieldNotFound(SCALARS_FQN, scalarName);
        }
        return new ScalarResolution.Resolved(
            ClassName.get(String.class),
            ClassName.get("graphql", "Scalars"),
            "GraphQLString"
        );
    }

    /**
     * Resolves a {@code @scalarType(scalar:)} directive value. The value is a fully-qualified
     * Java reference to a {@code public static final GraphQLScalarType} field; the resolver
     * splits at the last {@code .} via {@link #parseDirectiveValue(String)} and delegates to
     * {@link #resolveFromConstantFqn(String, String, ClassLoader)}.
     *
     * <p>A value with no usable dot (e.g. just {@code "Scalars"}, or a trailing {@code "."}) is
     * treated as a {@link ScalarResolution.Rejected.ClassNotFound} pointing at the value as
     * written, since the per-arm LSP fix-it for {@code ClassNotFound} suggests classes on the
     * classpath. A value with a usable dot that names a missing class or field surfaces the
     * appropriate downstream rejection arm.
     */
    public static ScalarResolution resolveFromDirectiveValue(String scalarFqn, ClassLoader loader) {
        return switch (parseDirectiveValue(scalarFqn)) {
            case ParsedDirectiveValue.Malformed m ->
                new ScalarResolution.Rejected.ClassNotFound(m.value());
            case ParsedDirectiveValue.Parsed p ->
                resolveFromConstantFqn(p.classFqn(), p.fieldName(), loader);
        };
    }

    /**
     * Parses a {@code @scalarType(scalar:)} directive value into its class-FQN + field-name
     * components, or surfaces a {@link ParsedDirectiveValue.Malformed} arm naming the original
     * value when the shape is wrong (no dot, leading dot, trailing dot).
     *
     * <p>Factored out so the LSP can validate the shape without constructing a classloader. Both
     * the LSP diagnostic and {@link #resolveFromDirectiveValue} switch on the same sealed result;
     * tightening the shape rule (e.g. rejecting consecutive dots later) lives in one place.
     */
    public static ParsedDirectiveValue parseDirectiveValue(String scalarFqn) {
        int dot = scalarFqn.lastIndexOf('.');
        if (dot <= 0 || dot == scalarFqn.length() - 1) {
            return new ParsedDirectiveValue.Malformed(scalarFqn);
        }
        return new ParsedDirectiveValue.Parsed(scalarFqn.substring(0, dot), scalarFqn.substring(dot + 1));
    }

    /**
     * Outcome of parsing a {@code @scalarType(scalar:)} value into its class-FQN + field-name
     * components. The {@link Malformed} arm carries the value as written; the {@link Parsed}
     * arm carries the split components ready for {@link #resolveFromConstantFqn} or a downstream
     * catalog lookup.
     */
    public sealed interface ParsedDirectiveValue {
        record Parsed(String classFqn, String fieldName) implements ParsedDirectiveValue {}
        record Malformed(String value) implements ParsedDirectiveValue {}
    }

    /**
     * Convention table: SDL scalar name → static-field FQN on the consumer's classpath.
     * Names with and without the {@code GraphQL} prefix both map to the same constant, matching
     * {@code graphql-java-extended-scalars}'s dual exposure (the library exposes
     * {@code GraphQLBigDecimal} as the type-name and {@code DateTime} as the name; consumers
     * write either form in SDL based on local idiom). The Java type for each constant is
     * recovered from the {@code Coercing} at resolution time, not hardcoded here — the table is
     * pure pointer.
     */
    private static final Map<String, String> CONVENTION_TABLE;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        String es = "graphql.scalars.ExtendedScalars";
        // Numeric primitives. The library exposes both `GraphQL`-prefixed field names (Java
        // identifier convention) and unprefixed SDL names (the `scalar.getName()` view); the
        // convention table covers both styles so consumers writing `scalar BigDecimal` or
        // `scalar GraphQLBigDecimal` both resolve.
        m.put("BigDecimal",        es + ".GraphQLBigDecimal");
        m.put("GraphQLBigDecimal", es + ".GraphQLBigDecimal");
        m.put("BigInteger",        es + ".GraphQLBigInteger");
        m.put("GraphQLBigInteger", es + ".GraphQLBigInteger");
        m.put("Long",              es + ".GraphQLLong");
        m.put("GraphQLLong",       es + ".GraphQLLong");
        m.put("Short",             es + ".GraphQLShort");
        m.put("GraphQLShort",      es + ".GraphQLShort");
        m.put("Byte",              es + ".GraphQLByte");
        m.put("GraphQLByte",       es + ".GraphQLByte");
        m.put("Char",              es + ".GraphQLChar");
        m.put("GraphQLChar",       es + ".GraphQLChar");

        // Numeric constraint scalars (one-to-one with field names).
        m.put("PositiveInt",       es + ".PositiveInt");
        m.put("PositiveFloat",     es + ".PositiveFloat");
        m.put("NegativeInt",       es + ".NegativeInt");
        m.put("NegativeFloat",     es + ".NegativeFloat");
        m.put("NonPositiveInt",    es + ".NonPositiveInt");
        m.put("NonPositiveFloat",  es + ".NonPositiveFloat");
        m.put("NonNegativeInt",    es + ".NonNegativeInt");
        m.put("NonNegativeFloat",  es + ".NonNegativeFloat");

        // Date / time / identity / locale.
        m.put("DateTime",          es + ".DateTime");
        m.put("Date",              es + ".Date");
        m.put("Time",              es + ".Time");
        m.put("LocalTime",         es + ".LocalTime");
        m.put("UUID",              es + ".UUID");
        m.put("Locale",            es + ".Locale");
        m.put("Currency",          es + ".Currency");
        m.put("CountryCode",       es + ".CountryCode");

        // URL. {@code ExtendedScalars.Object} and {@code ExtendedScalars.Json} are deliberately
        // excluded: both declare {@code Coercing<Object, Object>}, which the resolver's erasure
        // guard treats as an unbound Java type (per graphitron's principled "Object is too
        // ambiguous to drop into a generated record / Field<X>" stance). Consumers wanting
        // unstructured JSON should declare a typed wrapper and use @scalarType.
        m.put("Url",               es + ".Url");
        CONVENTION_TABLE = Map.copyOf(m);
    }

    /**
     * The full extended-scalars convention table as an immutable map from SDL scalar name to
     * static-field FQN. Callers project as needed:
     *
     * <ul>
     *   <li>"Is this name in the table?" — {@code conventionTable().containsKey(name)}.</li>
     *   <li>"What FQN does this name resolve to?" — {@code conventionTable().get(name)} (returns
     *       {@code null} for misses; callers wanting an {@code Optional} wrap at the call site).</li>
     *   <li>"What FQNs does the table cover, deduplicated?" —
     *       {@code Set.copyOf(new LinkedHashSet<>(conventionTable().values()))}.</li>
     * </ul>
     *
     * <p>One accessor, three projections; alternatives would invite a fourth accessor when the
     * next consumer needs an inverse lookup or a name-with-FQN pair. Resolution-independent: when
     * the caller wants classpath-aware resolution, route through
     * {@link #resolveByConvention(String, ClassLoader)} instead.
     */
    public static Map<String, String> conventionTable() {
        return CONVENTION_TABLE;
    }

    /**
     * Resolves an SDL scalar through the extended-scalars convention layer. Returns
     * {@link ScalarResolution.Resolved} when the SDL name is in the convention table
     * <em>and</em> the named constant is on the consumer's classpath; otherwise returns a
     * {@link ScalarResolution.Rejected} arm naming the miss.
     *
     * <p>The miss shape distinguishes "name not in convention table" (returns
     * {@link ScalarResolution.Rejected.FieldNotFound} naming
     * {@code graphql.scalars.ExtendedScalars} as the searched class) from "name in table but
     * artifact missing on classpath" (returns {@link ScalarResolution.Rejected.ClassNotFound}
     * naming the FQN the table points at). Callers that only care about hit-or-miss can pattern
     * match on {@link ScalarResolution.Resolved}; callers that want to render a hint route
     * through the rejection arm.
     */
    public static ScalarResolution resolveByConvention(String scalarName, ClassLoader loader) {
        String fqn = CONVENTION_TABLE.get(scalarName);
        if (fqn == null) {
            return new ScalarResolution.Rejected.FieldNotFound(
                "graphql.scalars.ExtendedScalars", scalarName);
        }
        return resolveFromDirectiveValue(fqn, loader);
    }

    /**
     * The Java type recovered for a spec built-in, or {@code null} for non-built-ins. A
     * convenience for callers that have already validated the SDL name is built-in and want
     * the {@link TypeName} directly without pattern-matching on
     * {@link ScalarResolution.Resolved}.
     *
     * <p>Phase 1 callers that don't yet need the {@code GraphQLScalarType} constant (the
     * lone consumer is {@code RowsMethodShape.standardScalarJavaType}) use this entry point;
     * Phase 2's {@code additionalType} registration switches to {@link #resolveBuiltIn(String)}
     * to recover the owner / field-name pair.
     */
    public static TypeName builtInJavaType(String scalarName) {
        BuiltIn entry = SPEC_BUILT_INS.get(scalarName);
        return entry == null ? null : ClassName.get(entry.javaType());
    }

    /**
     * Inverse of {@link #builtInJavaType}: true when {@code javaTypeFqn} is the Java type FQN
     * any GraphQL spec built-in scalar maps to ({@code java.lang.String},
     * {@code java.lang.Integer}, {@code java.lang.Double}, {@code java.lang.Boolean}). Used as
     * the unit-tier fallback for consumers that want to ask "is this Java type the resolution
     * of any classified GraphQL scalar?" when {@code ctx.types} is empty.
     */
    public static boolean isSpecBuiltInJavaType(String javaTypeFqn) {
        for (BuiltIn entry : SPEC_BUILT_INS.values()) {
            if (entry.javaType().getName().equals(javaTypeFqn)) return true;
        }
        return false;
    }

    /**
     * True when {@code javaTypeFqn} is the Java type FQN any classified GraphQL scalar in
     * {@code classifiedTypes} resolves to. Consults consumer-defined scalars (those registered
     * via {@code @scalarType} or the extended-scalars convention layer carry their resolved
     * Java FQN as {@link no.sikt.graphitron.rewrite.model.ScalarResolution.Resolved#javaType}),
     * then falls back to {@link #isSpecBuiltInJavaType} for callers in contexts where the
     * type registry is empty. Sibling to {@link #builtInJavaType}: the resolver owns both
     * directions of the scalar-name ↔ scalar-Java-FQN mapping so reflection code, validator
     * surfaces, and LSP quickfixes share a single source of truth.
     */
    public static boolean isClassifiedScalarJavaType(String javaTypeFqn,
            Iterable<no.sikt.graphitron.rewrite.model.GraphitronType> classifiedTypes) {
        if (classifiedTypes != null) {
            for (var t : classifiedTypes) {
                if (t instanceof no.sikt.graphitron.rewrite.model.GraphitronType.ScalarType st
                        && javaTypeFqn.equals(st.resolution().javaType().toString())) {
                    return true;
                }
            }
        }
        return isSpecBuiltInJavaType(javaTypeFqn);
    }
}
