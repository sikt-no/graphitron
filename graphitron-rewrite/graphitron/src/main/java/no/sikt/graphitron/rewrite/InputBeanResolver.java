package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.Rejection;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Resolves the {@link CallSiteExtraction.InputBean} arm: a {@code @service} method parameter whose
 * Java type is a consumer-authored class mirroring an SDL {@code input} type, instantiated at the
 * fetcher boundary so the service body never sees a {@code Map}. Post-processes a resolved
 * {@link MethodRef.Service} produced by {@link ServiceCatalog#reflectServiceMethod}.
 *
 * <p>Sibling to {@link EnumMappingResolver#enrichArgExtractions}, with the same pattern: walk the
 * method's parameters, rewrite the {@code CallSiteExtraction.Direct} arms that the catalog could
 * not classify in isolation (no SDL access at reflection time) into a richer extraction that
 * carries the bean instantiation plan. See R150
 * ({@code roadmap/service-method-input-bean-instantiation.md}) for the design contract.
 *
 * <p>Detection is structural: a Java parameter whose type (or element type, for {@code List<X>} /
 * {@code Set<X>}) resolves to a non-enum, consumer-authored class is paired against the SDL slot
 * type. When the SDL side is a {@code GraphQLInputObjectType}, an {@link CallSiteExtraction.InputBean}
 * is produced; when the SDL side is anything else the param is rejected structurally (the catch
 * is the missed "scalar SDL arg vs bean Java param" mismatch that surfaces today only as a runtime
 * {@code ClassCastException} inside the service body).
 *
 * <p>Bean shape supported: Java {@code record} (canonical constructor) or plain class with a
 * public no-arg constructor and JavaBean-style setters. Anything else (builders, immutable
 * value classes without a no-arg constructor, abstract bean classes) is rejected structurally.
 */
final class InputBeanResolver {

    private final BuildContext ctx;

    InputBeanResolver(BuildContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Result of enrichment. {@link Ok} carries the rewritten method (possibly equal to the input
     * when nothing matched). {@link Failed} carries a structural rejection ready for the caller
     * to surface verbatim, prefixed if the surrounding directive demands it.
     */
    sealed interface Result {
        record Ok(MethodRef.Service method) implements Result {}
        record Failed(Rejection rejection) implements Result {}
    }

    /**
     * Walks the method's {@link ParamSource.Arg} parameters; for each with
     * {@link CallSiteExtraction.Direct}, attempts bean detection. On success, swaps the extraction
     * to {@link CallSiteExtraction.InputBean}. On structural mismatch (bean Java type vs scalar
     * SDL arg, or bean class with no compatible constructor), returns {@link Result.Failed}.
     */
    Result enrich(MethodRef.Service method, GraphQLFieldDefinition fieldDef) {
        var argTypes = fieldDef.getArguments().stream()
            .collect(Collectors.toMap(
                graphql.schema.GraphQLArgument::getName,
                graphql.schema.GraphQLArgument::getType,
                (a, b) -> a,
                LinkedHashMap::new));
        var newParams = new ArrayList<MethodRef.Param>(method.params().size());
        for (var p : method.params()) {
            if (!(p.source() instanceof ParamSource.Arg arg)) {
                newParams.add(p);
                continue;
            }
            if (!(arg.extraction() instanceof CallSiteExtraction.Direct)) {
                newParams.add(p);
                continue;
            }
            // Restrict v1 to head-only paths: the spec's bean-shape case is "the Java parameter is
            // a bean mirroring the SDL input type", which is always a top-level argument binding.
            // Nested-path bindings (a param drilling one field out of an input) carry scalar leaf
            // types by construction and stay on the legacy Direct path.
            if (!arg.path().isHead()) {
                newParams.add(p);
                continue;
            }
            GraphQLInputType sdlType = argTypes.get(arg.graphqlArgName());
            // Determine the Java element type (peeled List/Set) and the list-shape flag.
            JavaElement elt = peelJavaListSet(p.typeName());
            // Bean detection requires the Java element type to load successfully and be a viable
            // bean candidate. If it isn't, leave the param alone — this isn't a bean-shaped param.
            Class<?> elementClass = tryLoad(elt.elementTypeName());
            if (elementClass == null || !looksLikeBeanCandidate(elementClass)) {
                newParams.add(p);
                continue;
            }
            // Peel SDL wrappers (NonNull / List). If the SDL side is missing or not an input
            // object, the developer's Java bean param cannot be populated; reject structurally.
            SdlElement sdl = peelSdlListNonNull(sdlType);
            if (!(sdl.elementType() instanceof GraphQLInputObjectType iot)) {
                String sdlDesc = sdlType == null ? "(no SDL arg)" : GraphQLTypeUtil.simplePrint(sdlType);
                return new Result.Failed(Rejection.structural(
                    "parameter '" + p.name() + "' on method '" + method.methodName()
                    + "' in class '" + method.className() + "' has Java type '"
                    + elementClass.getName() + "' (a consumer-authored class) but the GraphQL"
                    + " argument '" + arg.graphqlArgName() + "' has type '" + sdlDesc
                    + "' which is not an input-object type — change the Java parameter to a"
                    + " scalar/enum, or change the GraphQL argument to an input object"));
            }
            // Java list-shape and SDL list-shape must agree (List<Bean> ↔ [Input], Bean ↔ Input).
            if (elt.list() != sdl.list()) {
                return new Result.Failed(Rejection.structural(
                    "parameter '" + p.name() + "' on method '" + method.methodName()
                    + "' in class '" + method.className() + "' is "
                    + (elt.list() ? "list-shaped on Java side" : "scalar on Java side")
                    + " but the GraphQL argument '" + arg.graphqlArgName() + "' is "
                    + (sdl.list() ? "list-shaped" : "scalar")
                    + " — match the cardinalities"));
            }
            // Build the bean shape; collect any nested rejection along the way.
            var built = buildInputBean(elementClass, iot, p.name(), method.methodName(), method.className());
            if (built instanceof Built.Fail f) {
                return new Result.Failed(f.rejection());
            }
            var ib = ((Built.Ok) built).bean();
            newParams.add(new MethodRef.Param.Typed(p.name(), p.typeName(),
                new ParamSource.Arg(ib, arg.path())));
        }
        return new Result.Ok(new MethodRef.Service(method.className(), method.methodName(),
            method.returnType(), List.copyOf(newParams), method.declaredExceptions(),
            method.callShape()));
    }

    private sealed interface Built {
        record Ok(CallSiteExtraction.InputBean bean) implements Built {}
        record Fail(Rejection rejection) implements Built {}
    }

    /**
     * Recursively builds an {@link CallSiteExtraction.InputBean} for a given Java class paired with
     * an SDL {@link GraphQLInputObjectType}. Walks the SDL fields in declaration order, locating
     * the Java member on the bean and computing each leaf's transform. Records/JavaBeans are
     * supported; everything else is rejected. Nested input-object fields recurse into a nested
     * {@code InputBean} leaf.
     */
    private Built buildInputBean(Class<?> beanClass, GraphQLInputObjectType iot,
                                  String paramName, String methodName, String className) {
        CallSiteExtraction.InputBean.Target target;
        Map<String, JavaMember> javaMembersBySdlName;
        if (beanClass.isRecord()) {
            target = CallSiteExtraction.InputBean.Target.RECORD;
            javaMembersBySdlName = indexRecordComponents(beanClass);
        } else {
            String ctorReason = checkJavaBeanShape(beanClass);
            if (ctorReason != null) {
                return new Built.Fail(Rejection.structural(
                    "parameter '" + paramName + "' on method '" + methodName + "' in class '"
                    + className + "': bean class '" + beanClass.getName() + "' " + ctorReason));
            }
            target = CallSiteExtraction.InputBean.Target.JAVA_BEAN;
            javaMembersBySdlName = indexJavaBeanSetters(beanClass);
        }
        var bindings = new ArrayList<CallSiteExtraction.FieldBinding>();
        // For records, iterate the canonical-constructor parameter order; bindings must match.
        // For JavaBeans, iterating the SDL order is sufficient (setters are applied independently).
        Iterable<? extends FieldKey> order = beanClass.isRecord()
            ? recordOrder(beanClass)
            : sdlOrder(iot);
        for (var key : order) {
            String sdlFieldName = key.sdlFieldName();
            JavaMember member = javaMembersBySdlName.get(sdlFieldName);
            if (member == null) {
                // SDL has a field with no matching Java member — for records, this is a fatal
                // mismatch (canonical ctor needs every component); for JavaBeans, we skip the
                // field (the bean simply won't populate it). Records are the strict shape.
                if (beanClass.isRecord()) {
                    return new Built.Fail(Rejection.structural(
                        "parameter '" + paramName + "' on method '" + methodName + "' in class '"
                        + className + "': record '" + beanClass.getName()
                        + "' has no component named '" + sdlFieldName + "' to receive the SDL field"));
                }
                continue;
            }
            GraphQLInputObjectField sdlField = iot.getField(sdlFieldName);
            if (sdlField == null) {
                // Java member with no SDL field — JavaBean target only, skip silently. For records
                // this can't happen (the iteration order is the SDL fields when target = record).
                continue;
            }
            SdlElement sdlElt = peelSdlListNonNull(sdlField.getType());
            boolean listShape = sdlElt.list();
            String javaElementTypeName = member.elementTypeName();
            CallSiteExtraction leaf;
            if (sdlElt.elementType() instanceof GraphQLInputObjectType nestedIot) {
                Class<?> nestedClass = tryLoad(javaElementTypeName);
                if (nestedClass == null || !looksLikeBeanCandidate(nestedClass)) {
                    return new Built.Fail(Rejection.structural(
                        "parameter '" + paramName + "' on method '" + methodName + "' in class '"
                        + className + "': nested field '" + sdlFieldName + "' has SDL input-object"
                        + " type but the Java member type '" + javaElementTypeName
                        + "' is not a viable bean class"));
                }
                Built nested = buildInputBean(nestedClass, nestedIot, paramName, methodName, className);
                if (nested instanceof Built.Fail f) {
                    return f;
                }
                leaf = ((Built.Ok) nested).bean();
            } else if (sdlElt.elementType() instanceof GraphQLEnumType
                    && tryLoad(javaElementTypeName) != null
                    && tryLoad(javaElementTypeName).isEnum()) {
                leaf = new CallSiteExtraction.EnumValueOf(javaElementTypeName);
            } else {
                leaf = new CallSiteExtraction.Direct();
            }
            bindings.add(new CallSiteExtraction.FieldBinding(
                sdlFieldName, member.javaName(), leaf, listShape, javaElementTypeName));
        }
        if (bindings.isEmpty()) {
            return new Built.Fail(Rejection.structural(
                "parameter '" + paramName + "' on method '" + methodName + "' in class '"
                + className + "': bean class '" + beanClass.getName()
                + "' has no fields matching the SDL input type '" + iot.getName() + "'"));
        }
        return new Built.Ok(new CallSiteExtraction.InputBean(
            ClassName.bestGuess(beanClass.getName()), target, bindings));
    }

    // ===== Java-side helpers =====

    /**
     * Java member representing one field on the bean. {@code javaName} is the canonical component
     * name (record) or property name (JavaBean). {@code elementTypeName} is the Java element type
     * (with List<>/Set<> wrappers peeled for list-shape members).
     */
    private record JavaMember(String javaName, String elementTypeName, boolean list) {}

    private sealed interface FieldKey {
        String sdlFieldName();
        record FromSdl(String sdlFieldName) implements FieldKey {}
    }

    private Iterable<FieldKey> recordOrder(Class<?> beanClass) {
        var out = new ArrayList<FieldKey>();
        for (var rc : beanClass.getRecordComponents()) {
            out.add(new FieldKey.FromSdl(rc.getName()));
        }
        return out;
    }

    private Iterable<FieldKey> sdlOrder(GraphQLInputObjectType iot) {
        var out = new ArrayList<FieldKey>();
        for (var f : iot.getFieldDefinitions()) {
            out.add(new FieldKey.FromSdl(f.getName()));
        }
        return out;
    }

    private Map<String, JavaMember> indexRecordComponents(Class<?> beanClass) {
        var out = new LinkedHashMap<String, JavaMember>();
        for (var rc : beanClass.getRecordComponents()) {
            JavaElement elt = peelJavaListSet(rc.getGenericType().getTypeName());
            out.put(rc.getName(), new JavaMember(rc.getName(), elt.elementTypeName(), elt.list()));
        }
        return out;
    }

    /**
     * Indexes JavaBean setters: for each {@code public void setX(T v)}, the SDL field name is
     * the lowerCamel form of {@code X}, the Java element type is the parameter type (peeled for
     * {@code List<...>} / {@code Set<...>}), and the {@code javaName} is the same lowerCamel form.
     * Setters whose name doesn't follow the {@code setXxx} convention are ignored.
     */
    private Map<String, JavaMember> indexJavaBeanSetters(Class<?> beanClass) {
        var out = new LinkedHashMap<String, JavaMember>();
        for (var m : beanClass.getMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) continue;
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() != 1) continue;
            String n = m.getName();
            if (n.length() <= 3 || !n.startsWith("set")) continue;
            if (!Character.isUpperCase(n.charAt(3))) continue;
            String javaName = Character.toLowerCase(n.charAt(3)) + n.substring(4);
            JavaElement elt = peelJavaListSet(m.getGenericParameterTypes()[0].getTypeName());
            out.put(javaName, new JavaMember(javaName, elt.elementTypeName(), elt.list()));
        }
        return out;
    }

    /**
     * Returns {@code null} when the class can be populated as a JavaBean (public no-arg ctor,
     * concrete class), or a human-readable reason naming the constructor shapes the helper
     * supports.
     */
    private String checkJavaBeanShape(Class<?> beanClass) {
        int mods = beanClass.getModifiers();
        if (Modifier.isAbstract(mods) || beanClass.isInterface()) {
            return "is abstract or an interface; the helper can only instantiate concrete classes";
        }
        boolean hasNoArg = Arrays.stream(beanClass.getDeclaredConstructors())
            .filter(c -> Modifier.isPublic(c.getModifiers()))
            .anyMatch(c -> c.getParameterCount() == 0);
        if (!hasNoArg) {
            return "has no public no-arg constructor; mark the class as a record or add a public"
                + " no-arg constructor with JavaBean-style setters";
        }
        return null;
    }

    /**
     * A "looks like a populatable consumer bean" predicate. True when {@code cls} is a non-enum
     * class outside the {@code java.*}/{@code javax.*}/{@code jakarta.*}/{@code org.jooq.*}
     * package roots, AND the class is either a record or a concrete class with a public no-arg
     * constructor. The constructor check is part of candidate detection (not the structural
     * rejection path) so consumer types lacking a viable ctor fall through to the legacy
     * {@link CallSiteExtraction.Direct} arm — same runtime behaviour as before R150. Bean shapes
     * the helper cannot populate were never instantiable to begin with; promoting them just to
     * reject loud would regress existing usages that depend on the legacy passthrough.
     */
    private boolean looksLikeBeanCandidate(Class<?> cls) {
        if (cls.isPrimitive() || cls.isArray() || cls.isEnum()) return false;
        if (cls.getPackageName() == null) return false;
        String pkg = cls.getPackageName();
        boolean jdkOrJooq = pkg.equals("java") || pkg.startsWith("java.")
            || pkg.equals("javax") || pkg.startsWith("javax.")
            || pkg.equals("jakarta") || pkg.startsWith("jakarta.")
            || pkg.equals("org.jooq") || pkg.startsWith("org.jooq.");
        if (jdkOrJooq) return false;
        if (cls.isRecord()) return true;
        int mods = cls.getModifiers();
        if (Modifier.isAbstract(mods) || cls.isInterface()) return false;
        return Arrays.stream(cls.getDeclaredConstructors())
            .filter(c -> Modifier.isPublic(c.getModifiers()))
            .anyMatch(c -> c.getParameterCount() == 0);
    }

    private Class<?> tryLoad(String typeName) {
        // Strip generic params if any (we want the raw class).
        int lt = typeName.indexOf('<');
        String raw = lt < 0 ? typeName : typeName.substring(0, lt);
        try {
            return Class.forName(raw, false, ctx.codegenLoader());
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Throwable t) {
            // Some malformed type names (arrays, primitives) — treat as unloadable.
            return null;
        }
    }

    // ===== Java/SDL list peeling =====

    /** Peeled Java type: list flag + element type name. */
    private record JavaElement(boolean list, String elementTypeName) {}

    private static JavaElement peelJavaListSet(String typeName) {
        if (typeName.startsWith("java.util.List<") && typeName.endsWith(">")) {
            return new JavaElement(true,
                typeName.substring("java.util.List<".length(), typeName.length() - 1));
        }
        if (typeName.startsWith("java.util.Set<") && typeName.endsWith(">")) {
            return new JavaElement(true,
                typeName.substring("java.util.Set<".length(), typeName.length() - 1));
        }
        return new JavaElement(false, typeName);
    }

    /** Peeled SDL type: list flag + non-null/non-list element type. */
    private record SdlElement(boolean list, GraphQLInputType elementType) {}

    private static SdlElement peelSdlListNonNull(GraphQLInputType type) {
        if (type == null) return new SdlElement(false, null);
        GraphQLType t = type;
        boolean list = false;
        // Unwrap one layer of NonNull, one optional List, one inner NonNull. Deeper nesting
        // (List of List) is not supported and falls out via the bean-shape check (the Java side
        // would be List<List<X>>, which doesn't peel to a class).
        if (t instanceof GraphQLNonNull nn) t = nn.getWrappedType();
        if (t instanceof GraphQLList lst) {
            list = true;
            t = lst.getWrappedType();
            if (t instanceof GraphQLNonNull nn2) t = nn2.getWrappedType();
        }
        if (t instanceof GraphQLInputType it) {
            return new SdlElement(list, it);
        }
        return new SdlElement(list, null);
    }
}
