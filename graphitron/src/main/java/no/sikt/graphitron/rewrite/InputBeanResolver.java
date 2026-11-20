package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import com.palantir.javapoet.ClassName;
import no.sikt.graphitron.model.catalog.SchemaCoordinateSyntax;
import no.sikt.graphitron.render.CatalogRefs;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.diagnostics.WireCoercionError;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_TYPE_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_NODE_ID;
import static no.sikt.graphitron.rewrite.BuildContext.argString;

/**
 * Resolves the {@link CallSiteExtraction.InputBean} arm: a {@code @condition} method parameter
 * whose Java type is a consumer-authored class mirroring an SDL {@code input} type, instantiated at
 * the fetcher boundary so the condition body never sees a {@code Map}. The per-slot decision is
 * {@link #decideCarrier}; {@code ServiceCatalog}'s condition path states the policy over its arms
 * and builds a decided bean through {@link #buildBean}.
 *
 * <p>Classification rule (SDL-driven): {@link CallSiteExtraction.Direct} is reserved for GraphQL
 * scalar SDL arguments, including custom scalars wired via {@code @scalarType}. graphql-java's
 * scalar coercion delivers the consumer's declared Java type for those slots. GraphQL
 * input-object SDL arguments are classified as {@link CallSiteExtraction.InputBean} or rejected
 * loudly at generation time. {@code Map<K, V>} as a Java type for an input-object SDL slot is a
 * permanent rejection, not a v1 deferral.
 *
 * <p>Bean shape supported: Java {@code record} (canonical constructor) or plain class with a
 * public no-arg constructor and JavaBean-style setters. The bean class itself must be
 * {@code public}: generated fetchers live in a separate {@code .generated.fetchers} package and
 * cannot reach package-private types. Anything else (builders, immutable value classes without a
 * no-arg constructor, abstract bean classes, recursive shapes) is rejected structurally.
 */
final class InputBeanResolver {

    private final BuildContext ctx;

    InputBeanResolver(BuildContext ctx) {
        this.ctx = ctx;
    }

    /**
     * The parameter a carrier decision is about, as its messages name it: the parameter, the method
     * and class declaring it, and the GraphQL slot it is bound to (an argument name, or a dotted
     * path into one).
     */
    record SlotSite(String paramName, String methodName, String className, String slotName) {
        String where() {
            return "parameter '" + paramName + "' on method '" + methodName + "' in class '"
                + className + "'";
        }
    }

    /**
     * The per-slot carrier decision for a parameter bound to a GraphQL input-object slot, before
     * anything is built. Each caller states its own policy over the arms rather than passing a
     * flag in: the decision is what a declared Java type <em>is</em> against what graphql-java
     * delivers, and which of those a directive admits is the directive's.
     *
     * <ul>
     *   <li>{@link MapPassThrough}: the declared type is one graphql-java's value is assignable to,
     *       at matching cardinality: {@code Map} or {@code Object} on a single slot, a
     *       {@code List} of either (or a type a {@code List} is assignable to) on a list slot. The
     *       raw cast is wire-safe.</li>
     *   <li>{@link Bean}: a consumer class to build from the map, through {@link #buildBean}.</li>
     *   <li>{@link JooqRecordInput}: the input type classified as a jOOQ-record input, a carrier
     *       bound on the column axis.</li>
     *   <li>{@link Rejected}: the declared type is no carrier for an input object at all (not
     *       loadable, a JDK / jOOQ / enum / array type, a {@code Map} shape graphql-java does not
     *       deliver, or a list-versus-single mismatch).</li>
     * </ul>
     *
     * <p>Building is a second step because it is not side-effect free: a bean's {@code @nodeId}
     * members and a jOOQ record's key decodes are transcribed into the decode ledger as they
     * resolve, so a caller that refuses an arm must not build it first.
     */
    sealed interface SlotCarrier {
        record MapPassThrough() implements SlotCarrier {}
        record Bean(Class<?> beanClass, GraphQLInputObjectType inputType) implements SlotCarrier {}
        record JooqRecordInput(GraphitronType.JooqTableRecordInputType verdict,
                               GraphQLInputObjectType inputType) implements SlotCarrier {}
        record Rejected(Rejection rejection) implements SlotCarrier {}
    }

    /**
     * The input-object type {@code sdlType} names once list and non-null wrappers are peeled, or
     * {@code null} when it names anything else. The precondition of {@link #decideCarrier}.
     */
    static GraphQLInputObjectType inputObjectLeaf(GraphQLInputType sdlType) {
        return peelSdlListNonNull(sdlType).elementType() instanceof GraphQLInputObjectType iot
            ? iot : null;
    }

    /**
     * Decides what a parameter declared {@code declaredTypeName} is when bound to the input-object
     * slot {@code sdlType}; see {@link SlotCarrier}. {@code sdlType} must satisfy
     * {@link #inputObjectLeaf}.
     */
    SlotCarrier decideCarrier(SlotSite site, String declaredTypeName, GraphQLInputType sdlType) {
        SdlElement sdl = peelSdlListNonNull(sdlType);
        if (!(sdl.elementType() instanceof GraphQLInputObjectType iot)) {
            throw new IllegalArgumentException("decideCarrier called on a slot that is not an input"
                + " object: " + GraphQLTypeUtil.simplePrint(sdlType));
        }
        JavaElement elt = peelJavaListSet(declaredTypeName);
        Class<?> elementClass;
        if (elt.elementTypeName().equals("?")) {
            // List<?>: the unbounded wildcard reads as Object, which a Map is assignable to.
            elementClass = Object.class;
        } else {
            var elementLoad = tryLoad(elt.elementTypeName());
            if (elementLoad instanceof ConsumerClassLoad.Unlinkable unlinkable) {
                return new SlotCarrier.Rejected(unlinkable.rejection());
            }
            elementClass = elementLoad.loadedOrNull();
        }
        if (elementClass == null) {
            return new SlotCarrier.Rejected(Rejection.structural(site.where() + " has Java element"
                + " type '" + elt.elementTypeName() + "' which is not loadable, but the GraphQL"
                + " argument '" + site.slotName() + "' is an input-object — declare a"
                + " consumer-authored bean class (record or class with a public no-arg constructor)"
                + " for the parameter"));
        }
        if (isWireMapShape(declaredTypeName, elt, elementClass, sdl.list())) {
            return new SlotCarrier.MapPassThrough();
        }
        if (Map.class.isAssignableFrom(elementClass) || elementClass.isAssignableFrom(Map.class)) {
            String delivered = sdl.list() ? "java.util.List<java.util.Map<String, Object>>"
                : "java.util.Map<String, Object>";
            return new SlotCarrier.Rejected(Rejection.structural(site.where() + " has Java type '"
                + declaredTypeName + "' for the GraphQL input-object argument '" + site.slotName()
                + "' (type '" + GraphQLTypeUtil.simplePrint(sdlType) + "'), but graphql-java"
                + " delivers '" + delivered + "' there — a map-shaped parameter must be a supertype"
                + " of what is delivered, at the slot's cardinality"));
        }
        if (!looksLikeBeanCandidate(elementClass)) {
            return new SlotCarrier.Rejected(Rejection.structural(site.where() + " has Java element"
                + " type '" + elementClass.getName() + "' (JDK / jOOQ / enum / array) but the GraphQL"
                + " argument '" + site.slotName() + "' has input-object type '"
                + GraphQLTypeUtil.simplePrint(sdlType) + "' — replace the parameter type with a"
                + " consumer-authored bean class mirroring the input-object"));
        }
        if (elt.list() != sdl.list()) {
            return new SlotCarrier.Rejected(Rejection.structural(site.where() + " is "
                + (elt.list() ? "list-shaped on Java side" : "scalar on Java side")
                + " but the GraphQL argument '" + site.slotName() + "' is "
                + (sdl.list() ? "list-shaped" : "scalar")
                + " — match the cardinalities"));
        }
        // The classifier's verdict for this SDL input is JooqTableRecordInputType, table and
        // all; read that answer rather than re-resolving. lookAheadVerdict recomputes it
        // registry-free: this runs during field classification, when the input may be a
        // not-yet-visited child of the walk. A jOOQ-record param binds on the column axis, not
        // the Java-member axis the bean path uses. Sits after the shared input-object gates
        // above so it reuses the loadable / Map / cardinality-parity checks.
        if (ctx.lookAheadVerdict(iot.getName()) instanceof GraphitronType.JooqTableRecordInputType jtr) {
            return new SlotCarrier.JooqRecordInput(jtr, iot);
        }
        return new SlotCarrier.Bean(elementClass, iot);
    }

    /**
     * Whether the declared type is one graphql-java's input-object value is assignable to, at the
     * slot's cardinality: on a single slot a supertype of {@code Map}; on a list slot a
     * {@code java.util.List} whose element is a supertype of {@code Map}, or a single type a
     * {@code List} is assignable to ({@code Object}, {@code Collection}). A {@code Set} is not:
     * graphql-java delivers a {@code List}.
     */
    private static boolean isWireMapShape(String declaredTypeName, JavaElement elt,
            Class<?> elementClass, boolean sdlList) {
        if (!sdlList) {
            return !elt.list() && elementClass.isAssignableFrom(Map.class);
        }
        if (!elt.list()) {
            return elementClass.isAssignableFrom(List.class);
        }
        return declaredTypeName.startsWith("java.util.List<") && elementClass.isAssignableFrom(Map.class);
    }

    /** Outcome of building a decided carrier: the extraction to install, or why it cannot be built. */
    sealed interface CarrierBuild {
        record Ok(CallSiteExtraction extraction) implements CarrierBuild {}
        record Fail(Rejection rejection) implements CarrierBuild {}
    }

    /**
     * Builds the {@link CallSiteExtraction.InputBean} a {@link SlotCarrier.Bean} decided on. A bean
     * that cannot be built (not public, abstract, recursive, no no-arg constructor, a field set with
     * no bijection to the SDL fields) fails with the message naming that case.
     */
    CarrierBuild buildBean(SlotCarrier.Bean bean, SlotSite site, ClassifyContext.UseSite useSite) {
        return switch (buildInputBean(bean.beanClass(), bean.inputType(), site.paramName(),
                site.methodName(), site.className(), new HashSet<>(), useSite)) {
            case Built.Ok ok -> new CarrierBuild.Ok(ok.bean());
            case Built.Fail f -> new CarrierBuild.Fail(f.rejection());
        };
    }

    private sealed interface Built {
        record Ok(CallSiteExtraction.InputBean bean) implements Built {}
        record Fail(Rejection rejection) implements Built {}
    }

    /** Appends {@code element} to {@code prefix}, returning a new immutable list (the carrier's path). */
    private static List<String> append(List<String> prefix, String element) {
        var out = new ArrayList<String>(prefix.size() + 1);
        out.addAll(prefix);
        out.add(element);
        return List.copyOf(out);
    }

    /** Renders an access path as a dotted SDL field reference (e.g. {@code details.title}) for messages. */
    private static String dottedPath(List<String> path) {
        return String.join(".", path);
    }

    /**
     * Recursively builds an {@link CallSiteExtraction.InputBean} for a given Java class paired with
     * an SDL {@link GraphQLInputObjectType}. Walks the SDL fields in declaration order, locating
     * the Java member on the bean and computing each leaf's transform. Records/JavaBeans are
     * supported; everything else is rejected. A nested input-object field whose binding key names a
     * member recurses into a nested {@code InputBean} leaf; one that names no member is a
     * <em>grouping</em> input whose own fields flatten onto this bean, see {@link #indexSdlFields}.
     * The {@code visited} set carries the in-flight chain of bean classes
     * so a self-referential or mutually-recursive shape fails as a structural rejection rather
     * than a {@code StackOverflowError}.
     */
    private Built buildInputBean(Class<?> beanClass, GraphQLInputObjectType iot,
                                  String paramName, String methodName, String className,
                                  Set<Class<?>> visited, ClassifyContext.UseSite useSite) {
        if (!visited.add(beanClass)) {
            return new Built.Fail(Rejection.structural(
                "parameter '" + paramName + "' on method '" + methodName + "' in class '"
                + className + "': bean class '" + beanClass.getName() + "' is recursive — input-object"
                + " shapes that reference themselves (directly or via another bean) are not supported"
                + " by the input-bean instantiation path"));
        }
        try {
            return buildInputBeanBody(beanClass, iot, paramName, methodName, className, visited,
                useSite);
        } finally {
            visited.remove(beanClass);
        }
    }

    private Built buildInputBeanBody(Class<?> beanClass, GraphQLInputObjectType iot,
                                      String paramName, String methodName, String className,
                                      Set<Class<?>> visited, ClassifyContext.UseSite useSite) {
        if (!Modifier.isPublic(beanClass.getModifiers())) {
            return new Built.Fail(Rejection.structural(
                "parameter '" + paramName + "' on method '" + methodName + "' in class '"
                + className + "': bean class '" + beanClass.getName() + "' is not public; the"
                + " generated fetcher lives in a different package and needs public access to"
                + " construct the bean — mark the class public"));
        }
        CallSiteExtraction.InputBean.Target target;
        Map<String, JavaMember> javaMembersByName;
        if (beanClass.isRecord()) {
            target = CallSiteExtraction.InputBean.Target.RECORD;
            javaMembersByName = indexRecordComponents(beanClass);
        } else {
            String ctorReason = checkJavaBeanShape(beanClass);
            if (ctorReason != null) {
                return new Built.Fail(Rejection.structural(
                    "parameter '" + paramName + "' on method '" + methodName + "' in class '"
                    + className + "': bean class '" + beanClass.getName() + "' " + ctorReason));
            }
            target = CallSiteExtraction.InputBean.Target.JAVA_BEAN;
            javaMembersByName = indexJavaBeanSetters(beanClass);
        }

        // Index the SDL fields by their Java-member binding key, descending through grouping inputs
        // (a nested input field matching no member) so their leaves are hoisted to peers of this
        // type's own fields. Two SDL fields resolving to one key is rejected before either arm
        // builds a result: on the record arm the second would silently win the bijection slot
        // (order-dependent binding); on the JavaBean arm the same setter would be invoked twice.
        var sdlByBindingKey = new LinkedHashMap<String, IndexEntry>();
        Rejection indexRejection = indexSdlFields(iot, javaMembersByName, List.of(),
            ClassifyContext.under(useSite).expanding(iot.getName()), beanClass, paramName,
            methodName, className, sdlByBindingKey);
        if (indexRejection != null) {
            return new Built.Fail(indexRejection);
        }

        // Records are positional and total; JavaBean setters are independent and partial. The two
        // arms encode that invariant difference and share the binding-key index above and the
        // per-field leaf classification (bindField).
        return switch (target) {
            case RECORD -> bindRecord(beanClass, iot, javaMembersByName, sdlByBindingKey,
                paramName, methodName, className, visited);
            case JAVA_BEAN -> bindJavaBean(beanClass, iot, javaMembersByName, sdlByBindingKey,
                paramName, methodName, className, visited);
        };
    }

    /**
     * One entry of the binding-key index: the SDL field that binds, plus the bean-local access path
     * from the bean's own wire {@code Map} down to it. A field declared directly on the bean's input
     * type has a one-element path; a field hoisted out of a grouping input carries the group's field
     * names ahead of its own.
     */
    private record IndexEntry(List<String> path, GraphQLInputObjectField field, String definition,
                              ClassifyContext.UseSite at) {}

    /**
     * Fills {@code out} with one entry per SDL field that binds against {@code javaMembersByName},
     * descending through <em>grouping</em> inputs. Returns the first {@link Rejection}, or
     * {@code null} on success.
     *
     * <p>Three-way rule per nested input-object field {@code g}, decided against the bean's member
     * set (known before any field binds):
     *
     * <ol>
     *   <li><b>A member matches {@code g}'s binding key.</b> {@code g} indexes as an ordinary field
     *       and {@link #bindField} recurses into a nested bean leaf, exactly as before. Wins
     *       unconditionally, including when the matched member is not a viable bean class (that
     *       stays {@code bindField}'s rejection rather than becoming a flatten).</li>
     *   <li><b>No member matches, and {@code g} carries no {@code @nodeId}.</b> {@code g} is a grouping input: its own fields are hoisted into this
     *       index under the access path {@code ["g", <leaf>]} and bind against the enclosing bean's
     *       members by the normal rules. Recursive, so a hoisted group descends again.</li>
     *   <li><b>No member matches, and {@code g} carries {@code @nodeId}.</b>
     *       Reject. The directive is an authored claim that the field binds to a named Java
     *       member; flattening past a claim that does not resolve would turn a typo into
     *       silently-different behaviour.</li>
     * </ol>
     *
     * <p>The binding key is the field's own GraphQL name: on the condition's input
     * {@code @field(name:)} names a column, so only {@code @nodeId} is a member claim, and two fields
     * can collide on one key only by sharing a GraphQL name across a hoist.
     *
     * <p>Hoisting makes a leaf a peer of the enclosing type's own fields, so a hoisted leaf collides
     * with a top-level field, and with another group's hoisted leaf, by exactly the rule that already
     * governs two top-level fields: one duplicate-binding-key rejection against the one shared index,
     * not a variant per pairing. The access path is carried for the message and the emitter's
     * {@code Map} descent only; it is never part of the identity that decides a collision.
     *
     * <p>{@code @table} on a grouping input is not a gate: the directive is deprecated and inert on
     * an input, so such a type is an ordinary grouping input and flattens exactly as its
     * directiveless twin does (the column axis reads it the same way).
     */
    private Rejection indexSdlFields(GraphQLInputObjectType iot,
            Map<String, JavaMember> javaMembersByName, List<String> pathPrefix,
            ClassifyContext classifyCtx, Class<?> beanClass, String paramName, String methodName,
            String className, Map<String, IndexEntry> out) {
        String where = "parameter '" + paramName + "' on method '" + methodName + "' in class '"
            + className + "'";
        for (var f : iot.getFieldDefinitions()) {
            List<String> path = append(pathPrefix, f.getName());
            String key = f.getName();
            SdlElement sdlElt = peelSdlListNonNull(f.getType());
            boolean groups = sdlElt.elementType() instanceof GraphQLInputObjectType
                && !javaMembersByName.containsKey(key);
            if (groups) {
                var nestedIot = (GraphQLInputObjectType) sdlElt.elementType();
                Rejection gate = groupingGate(f, path, nestedIot, sdlElt.list(), classifyCtx,
                    beanClass, where);
                if (gate != null) {
                    return gate;
                }
                Rejection nested = indexSdlFields(nestedIot, javaMembersByName, path,
                    classifyCtx.expanding(nestedIot.getName())
                        .descending(iot.getName(), f.getName()),
                    beanClass, paramName, methodName, className, out);
                if (nested != null) {
                    return nested;
                }
                continue;
            }
            IndexEntry prior = out.put(key, new IndexEntry(path, f,
                SchemaCoordinateSyntax.ofField(iot.getName(), f.getName()),
                classifyCtx.useSite() == null
                    ? null
                    : classifyCtx.useSite().descending(iot.getName(), f.getName())));
            if (prior != null) {
                return Rejection.structural(where + ": SDL input fields '"
                    + dottedPath(prior.path()) + "' and '" + dottedPath(path)
                    + "' both bind to Java member '" + key + "' on bean class '" + beanClass.getName()
                    + "' by name; two input fields cannot populate one member, so rename one field");
            }
        }
        return null;
    }

    /**
     * The rejections a grouping input can hit before its fields are hoisted: an authored binding
     * claim that resolves to no member (binding rule 3), a list-shaped group, and a cyclic one.
     * Returns {@code null} when the group may be descended into.
     */
    private Rejection groupingGate(GraphQLInputObjectField f, List<String> path,
            GraphQLInputObjectType nestedIot, boolean list, ClassifyContext classifyCtx,
            Class<?> beanClass, String where) {
        // @field(name:) names a column on the condition's input, not a member, so only @nodeId is
        // a binding claim here.
        if (f.hasAppliedDirective(DIR_NODE_ID)) {
            return Rejection.structural(where + ": nested input field '" + dottedPath(path)
                + "' carries @nodeId, binding it to Java member '" + f.getName()
                + "', but bean class '" + beanClass.getName() + "' has no such member — a nested"
                + " input field with no matching member flattens onto the bean only when it makes no"
                + " binding claim of its own. Add the member, correct the directive, or drop the"
                + " directive to let the field flatten as a grouping input");
        }
        if (list) {
            return Rejection.structural(where + ": nested input field '" + dottedPath(path)
                + "' is list-shaped (a list of '" + nestedIot.getName() + "') and matches no member of"
                + " bean class '" + beanClass.getName() + "', so it would flatten onto the bean — but"
                + " a list of groups has no flat member to land on. Make the field singular, or add a"
                + " member named '" + f.getName() + "' typed as a list of a bean mirroring '"
                + nestedIot.getName() + "'");
        }
        // Cycle detection is on SDL grouping-input type names, not the Java axis buildInputBean's
        // Set<Class<?>> visited guards: a flattened group contributes no Java class, so without this
        // an input type reaching itself through directiveless nesting recurses until the stack dies.
        if (classifyCtx.isExpanding(nestedIot.getName())) {
            return Rejection.structural(where + ": nested input field '" + dottedPath(path)
                + "' reaches input type '" + nestedIot.getName() + "' which is already expanding — a"
                + " cyclic grouping input cannot flatten onto bean class '" + beanClass.getName()
                + "' (there is no member to bind the recursion to)");
        }
        return null;
    }

    /**
     * Record arm: a bidirectional bijection between record components and SDL input fields.
     * <ul>
     *   <li><b>Every component must bind</b> (direction A). The canonical constructor takes every
     *       component, so a component with no SDL field bound to it is the store's
     *       {@code MEMBER_UNFILLED}, whose verdict stops the build before this runs.</li>
     *   <li><b>Every SDL field must be consumed</b> (direction B). A field whose binding key names
     *       no component would have its value silently dropped (it never reaches the constructor);
     *       for a record's total-mirror contract that is a hard fail.</li>
     * </ul>
     * Bindings are produced in record-component (canonical-constructor) order.
     */
    private Built bindRecord(Class<?> beanClass, GraphQLInputObjectType iot,
            Map<String, JavaMember> componentsByName,
            Map<String, IndexEntry> sdlByBindingKey,
            String paramName, String methodName, String className, Set<Class<?>> visited) {
        var bindings = new ArrayList<CallSiteExtraction.FieldBinding>();
        var consumedKeys = new HashSet<String>();
        // Direction A: every component binds, a component nothing fills being the store's
        // MEMBER_UNFILLED. componentsByName iterates in component order.
        for (var ce : componentsByName.entrySet()) {
            String component = ce.getKey();
            IndexEntry entry = sdlByBindingKey.get(component);
            if (entry == null) {
                continue;
            }
            consumedKeys.add(component);
            FieldResult r = bindField(entry.field(), entry.path(), entry.definition(), ce.getValue(),
                paramName, methodName, className, visited, entry.at());
            if (r instanceof FieldResult.Fail f) {
                return new Built.Fail(f.rejection());
            }
            bindings.add(((FieldResult.Ok) r).binding());
        }
        // Direction B: every SDL field must be consumed by some component. A grouping input never
        // appears as a key in its own right, so this reads only leaves — including hoisted ones,
        // whose value would otherwise be dropped on the way to the canonical constructor.
        for (var e : sdlByBindingKey.entrySet()) {
            if (!consumedKeys.contains(e.getKey())) {
                return new Built.Fail(Rejection.structural(
                    "parameter '" + paramName + "' on method '" + methodName + "' in class '"
                    + className + "': SDL input field '" + dottedPath(e.getValue().path())
                    + "' (binding key '"
                    + e.getKey() + "') on type '" + iot.getName() + "' names no component of record '"
                    + beanClass.getName() + "': every field of a record-backed"
                    + " @condition parameter's input must bind to a component (else its value is"
                    + " silently dropped); remove the field, or add a component named after it"));
            }
        }
        return new Built.Ok(new CallSiteExtraction.InputBean(
            ClassName.get(beanClass),
            CallSiteExtraction.InputBean.Target.RECORD, bindings));
    }

    /**
     * JavaBean arm: setters are applied independently. Each SDL field whose binding key names a
     * setter binds. The empty-bindings rejection fires only when no field matches any setter by
     * name, the "this bean does not mirror this input" case.
     *
     * <p>An input field nothing takes is a defect (Alf, 2026-11-17), but this arm does not judge
     * it: the fact store refuses it as {@code MEMBER_UNKNOWN}, once at the outermost unbound field,
     * and a graph the store refuses is never classified, so a field skipped here never reaches
     * generation. A nested input-object field matching no setter is a grouping input, flattened or
     * rejected in the index walk above.
     */
    private Built bindJavaBean(Class<?> beanClass, GraphQLInputObjectType iot,
            Map<String, JavaMember> settersByName,
            Map<String, IndexEntry> sdlByBindingKey,
            String paramName, String methodName, String className, Set<Class<?>> visited) {
        var bindings = new ArrayList<CallSiteExtraction.FieldBinding>();
        // sdlByBindingKey iterates in SDL declaration order (LinkedHashMap), so the bindings list
        // keeps that order; for JavaBean setters the order is not load-bearing, only stable.
        for (var e : sdlByBindingKey.entrySet()) {
            JavaMember member = settersByName.get(e.getKey());
            if (member == null) {
                continue;
            }
            FieldResult r = bindField(e.getValue().field(), e.getValue().path(),
                e.getValue().definition(), member,
                paramName, methodName, className, visited, e.getValue().at());
            if (r instanceof FieldResult.Fail f) {
                return new Built.Fail(f.rejection());
            }
            bindings.add(((FieldResult.Ok) r).binding());
        }
        if (bindings.isEmpty()) {
            return new Built.Fail(Rejection.structural(
                "parameter '" + paramName + "' on method '" + methodName + "' in class '"
                + className + "': bean class '" + beanClass.getName()
                + "' has no fields matching the SDL input type '" + iot.getName() + "'"));
        }
        return new Built.Ok(new CallSiteExtraction.InputBean(
            ClassName.get(beanClass),
            CallSiteExtraction.InputBean.Target.JAVA_BEAN, bindings));
    }

    /** Outcome of classifying one SDL-field / Java-member pair (a {@link CallSiteExtraction.FieldBinding} or a fail). */
    private sealed interface FieldResult {
        record Ok(CallSiteExtraction.FieldBinding binding) implements FieldResult {}
        record Fail(Rejection rejection) implements FieldResult {}
    }

    /**
     * Classifies one SDL-field / Java-member pair into a {@link CallSiteExtraction.FieldBinding}.
     * Member resolution has already happened (the binding key selected which member binds); the
     * member's Java type drives the leaf branch. The binding carries the SDL access path (whose last
     * element is the {@code Map} key the helper reads) separately from the Java member name (the
     * component / property it populates), so the emitter is agnostic to <em>how</em> the member was
     * chosen and to whether the field was declared on the bean's own input type or hoisted out of a
     * grouping input. A hoisted leaf's transform is computed exactly as an unflattened one's:
     * flattening moves where the value is read from, not what is done to it.
     */
    private FieldResult bindField(GraphQLInputObjectField sdlField, List<String> accessPath,
            String definition, JavaMember member,
            String paramName, String methodName, String className, Set<Class<?>> visited,
            ClassifyContext.UseSite at) {
        // Messages name the dotted access path, so a hoisted leaf points at the SDL the author
        // wrote rather than at a bare field name that appears nowhere on the enclosing input type.
        String fieldPath = dottedPath(accessPath);
        SdlElement sdlElt = peelSdlListNonNull(sdlField.getType());
        boolean listShape = sdlElt.list();
        boolean nonNull = GraphQLTypeUtil.isNonNull(sdlField.getType());
        String javaElementTypeName = member.elementTypeName();
        CallSiteExtraction leaf;
        if (sdlElt.elementType() instanceof GraphQLInputObjectType nestedIot) {
            var nestedLoad = tryLoad(javaElementTypeName);
            if (nestedLoad instanceof ConsumerClassLoad.Unlinkable unlinkable) {
                return new FieldResult.Fail(unlinkable.rejection());
            }
            Class<?> nestedClass = nestedLoad.loadedOrNull();
            if (nestedClass == null || !looksLikeBeanCandidate(nestedClass)) {
                return new FieldResult.Fail(Rejection.structural(
                    "parameter '" + paramName + "' on method '" + methodName + "' in class '"
                    + className + "': nested field '" + fieldPath + "' has SDL input-object"
                    + " type but the Java member type '" + javaElementTypeName
                    + "' is not a viable bean class"));
            }
            Built nested = buildInputBean(nestedClass, nestedIot, paramName, methodName,
                className, visited, at);
            if (nested instanceof Built.Fail f) {
                return new FieldResult.Fail(f.rejection());
            }
            leaf = ((Built.Ok) nested).bean();
        } else if (sdlElt.elementType() instanceof GraphQLEnumType enumSdl
                && tryLoad(javaElementTypeName).loadedOrNull() != null
                && tryLoad(javaElementTypeName).loadedOrNull().isEnum()) {
            // The declared type IS the enum and assignment succeeds, but
            // Enum.valueOf((String) ...) throws IllegalArgumentException when an SDL enum value
            // name diverges from the Java constant names. Route through the single enum-constant
            // parity home (EnumMappingResolver) so a divergence rejects loudly rather than
            // emitting a valueOf that crashes at runtime.
            var parity = new EnumMappingResolver(ctx).checkEnumConstants(enumSdl.getName(), tryLoad(javaElementTypeName).loadedOrNull());
            if (parity instanceof EnumMappingResolver.EnumConstantParity.Divergence d) {
                return new FieldResult.Fail(new WireCoercionError.EnumConstantDivergence(
                    javaElementTypeName,
                    d.mismatches().stream().map(EnumMappingResolver.EnumConstantParity.ValueMismatch::sdlValueName).toList(),
                    d.mismatches().isEmpty() ? List.of() : d.mismatches().get(0).candidates(),
                    "input-bean field '" + fieldPath + "' on parameter '" + paramName + "' of method '"
                        + methodName + "' in class '" + className + "'"));
            }
            leaf = new CallSiteExtraction.EnumValueOf(javaElementTypeName);
        } else {
            // Scalar SDL field. A jOOQ-record-typed member never lands on Direct: a wire ID
            // String cast to a *Record throws ClassCastException at the first request. Branch
            // to a @nodeId-decode leaf, or reject loudly.
            var memberLoad = tryLoad(javaElementTypeName);
            if (memberLoad instanceof ConsumerClassLoad.Unlinkable unlinkable) {
                return new FieldResult.Fail(unlinkable.rejection());
            }
            Class<?> memberClass = memberLoad.loadedOrNull();
            if (memberClass != null && isJooqRecord(memberClass)) {
                RecordLeaf recordLeaf = buildJooqRecordLeaf(sdlField, fieldPath,
                    javaElementTypeName, nonNull, paramName, methodName, className);
                if (recordLeaf instanceof RecordLeaf.Fail rf) {
                    if (at != null && sdlField.hasAppliedDirective(DIR_NODE_ID)) {
                        ctx.decodeLedger().recordRefusal(at.here(), rf.rejection());
                    }
                    return new FieldResult.Fail(rf.rejection());
                }
                leaf = ((RecordLeaf.Ok) recordLeaf).leaf();
                if (at != null) {
                    ctx.decodeLedger().recordSlot(at.here(), leaf);
                }
            } else if (sdlField.hasAppliedDirective(DIR_NODE_ID)) {
                var deferral = singleValuedMemberDeferral(fieldPath, paramName, methodName, className);
                if (at != null) {
                    ctx.decodeLedger().recordRefusal(at.here(), deferral);
                }
                return new FieldResult.Fail(deferral);
            } else {
                // A scalar SDL field bound to a consumer-declared Java type lands on Direct only
                // once the wire-coercion predicate confirms graphql-java's coercion output for the
                // SDL scalar is assignable to that declared type (numeric width, ID-as-numeric,
                // and domain-type mismatches all reject). The predicate is the sole producer of
                // Direct here.
                // The scalar fixed point, not the live registry view: this runs during field
                // classification, when a reachable scalar may be a not-yet-visited child of the
                // walk.
                var wire = WireCoercionResolver.checkScalar(sdlElt.elementType(), javaElementTypeName,
                    ctx.scalarVerdicts.values(),
                    "input-bean field '" + fieldPath + "' on parameter '" + paramName + "' of method '"
                        + methodName + "' in class '" + className + "'");
                if (wire instanceof WireCoercionResolver.Result.Rejected rej) {
                    return new FieldResult.Fail(rej.error());
                }
                leaf = new CallSiteExtraction.Direct();
            }
        }
        return new FieldResult.Ok(new CallSiteExtraction.FieldBinding(
            accessPath, member.javaName(), leaf, listShape, javaElementTypeName, definition));
    }

    /**
     * The refusal for {@code @nodeId} on a bean member the decoded value cannot be loaded into yet:
     * a member that is not a jOOQ record, so the tuple would have to be projected onto a single
     * value. Deferred rather than structural because the schema is one graphitron means to carry
     * out. At a one-column key the projection is well defined and is the same one a producer
     * parameter already receives; what is missing is the emitter, on this side of the bean boundary,
     * and not the author's understanding.
     *
     * <p>Refusing rather than resolving is the point. The arm this replaces let the member reach
     * {@code CallSiteExtraction.Direct}, which handed the bean the opaque id and the consumer a
     * value it never asked for, with nothing in the build saying so. Both remedies below keep the
     * invariant that a consumer never receives the wire format, and the deferral names them rather
     * than leaving an author to find one.
     */
    private static Rejection singleValuedMemberDeferral(String fieldPath, String paramName,
            String methodName, String className) {
        return Rejection.deferred("field '" + fieldPath + "' on the bean for parameter '" + paramName
            + "' of method '" + methodName + "' in class '" + className + "' carries @nodeId, and"
            + " decoding into a member that is not a jOOQ record does not emit yet; until it does,"
            + " declare the member as the generated record of that node type's own table, which"
            + " takes the whole decoded key, or take the id at the producer's own parameter, where"
            + " one key column's value is what the parameter receives");
    }

    // ===== jOOQ-record member (@nodeId decode) =====

    /**
     * Classification of a jOOQ-{@code Record}-typed input-bean member: either a
     * decode leaf, single-type or polymorphic, or a structural rejection. A record member never
     * falls through to {@link CallSiteExtraction.Direct}.
     */
    private sealed interface RecordLeaf {
        record Ok(CallSiteExtraction leaf) implements RecordLeaf {}
        record Fail(Rejection rejection) implements RecordLeaf {}
    }

    /**
     * Builds the {@link CallSiteExtraction.NodeIdDecodeRecord} leaf for a jOOQ-record-typed bean
     * member, reading {@code @nodeId(typeName:)} off the SDL field and resolving the decode
     * materialization data through {@link BuildContext#resolveNodeIdRecordDecode}. The leaf is
     * arity- and shape-agnostic: composite keys and list-valued members are supported (list-ness
     * rides on the enclosing {@link CallSiteExtraction.FieldBinding}), so the only rejections are
     * malformed-directive cases: no {@code @nodeId} on the member, {@code @nodeId} without
     * {@code typeName:}, or a {@code typeName:} naming no known NodeType.
     */
    private RecordLeaf buildJooqRecordLeaf(GraphQLInputObjectField sdlField, String fieldPath,
            String recordTypeName, boolean nonNull,
            String paramName, String methodName, String className) {
        String where = "field '" + fieldPath + "' (jOOQ record '" + recordTypeName + "') on the"
            + " bean for parameter '" + paramName + "' of method '" + methodName + "' in class '"
            + className + "'";
        if (!sdlField.hasAppliedDirective(DIR_NODE_ID)) {
            return new RecordLeaf.Fail(Rejection.structural(where
                + ": a jOOQ-record-typed input-bean member must carry @nodeId(typeName:) so the"
                + " wire-format ID can be decoded into the record — add @nodeId(typeName: \"<NodeType>\")"
                + " to the SDL field"));
        }
        var typeName = argString(sdlField, DIR_NODE_ID, ARG_TYPE_NAME);
        if (typeName.isEmpty()) {
            return new RecordLeaf.Fail(Rejection.structural(where
                + ": @nodeId on a jOOQ-record-typed member must specify typeName: explicitly (the"
                + " record type alone does not name the NodeType to decode against)"));
        }
        var resolution = ctx.resolveNodeIdRecordDecode(typeName.get());
        if (resolution instanceof BuildContext.NodeIdRecordDecode.Rejected r) {
            return new RecordLeaf.Fail(Rejection.structural(where + ": " + r.message()));
        }
        if (resolution instanceof BuildContext.NodeIdRecordDecode.Polymorphic poly) {
            return polymorphicRecordLeaf(poly, recordTypeName, nonNull, where);
        }
        var resolved = (BuildContext.NodeIdRecordDecode.Resolved) resolution;
        // The NodeId for `typeName` decodes into the record of that NodeType's own @table. Loading
        // those key values into a *different* jOOQ record is unsound: the Tables.<NodeTable>.<col>
        // field references the decode helper emits are not fields of the declared record. Without
        // this gate the mismatch surfaces only as a javac "incompatible types" error in the
        // consumer's generated fetchers, not as a graphitron rejection; catch it at classification.
        String nodeTableRecord = CatalogRefs.recordClass(resolved.table()).toString();
        if (!nodeTableRecord.equals(recordTypeName)) {
            return new RecordLeaf.Fail(Rejection.structural(where
                + ": the member is typed as jOOQ record '" + recordTypeName + "', but"
                + " @nodeId(typeName: \"" + typeName.get() + "\") decodes into '" + nodeTableRecord
                + "' (the record of that type's own @table). A NodeId cannot be decoded into a"
                + " different record type — declare the member as '" + nodeTableRecord + "', or point"
                + " @nodeId at the NodeType whose @table backs '" + recordTypeName + "'"));
        }
        return new RecordLeaf.Ok(new CallSiteExtraction.NodeIdDecodeRecord(
            resolved.encoderClass(), resolved.typeName(), resolved.typeId(),
            resolved.keyColumns(), resolved.table(), nonNull));
    }

    /**
     * The leaf for a member whose {@code @nodeId(typeName:)} named a multitable container: the
     * member's declared type has to be one every candidate's generated record is, and the decode then
     * dispatches on the wire id's own type prefix.
     *
     * <p>The assignability question is {@link BuildContext#admitPolymorphicSlotType}'s, asked of the
     * member's declared type, so the walk and the store's own {@code SLOT_NOT_SUPERTYPE_OF_MEMBER}
     * read one rule. A member typed as one candidate's record is refused there rather than resolved:
     * that is the single-type behaviour the author declined by naming the container.
     */
    private RecordLeaf polymorphicRecordLeaf(BuildContext.NodeIdRecordDecode.Polymorphic poly,
            String recordTypeName, boolean nonNull, String where) {
        var admission = ctx.admitPolymorphicSlotType(poly, recordTypeName);
        if (admission instanceof BuildContext.PolymorphicSlotAdmission.Refused refused) {
            return new RecordLeaf.Fail(Rejection.structural(where + ": " + refused.message()));
        }
        if (admission instanceof BuildContext.PolymorphicSlotAdmission.Unlinkable unlinkable) {
            return new RecordLeaf.Fail(unlinkable.rejection());
        }
        var admitted = (BuildContext.PolymorphicSlotAdmission.Admitted) admission;
        return new RecordLeaf.Ok(polymorphicLeaf(poly, admitted.slotType(), nonNull));
    }

    /**
     * The polymorphic decode leaf built from a resolution and an admitted slot type. Here rather than
     * inline at the call site so the leaf has one spelling.
     */
    static CallSiteExtraction.NodeIdDecodePolymorphicRecord polymorphicLeaf(
            BuildContext.NodeIdRecordDecode.Polymorphic poly,
            CallSiteExtraction.AdmittedSlotType slotType, boolean nonNull) {
        return new CallSiteExtraction.NodeIdDecodePolymorphicRecord(
            poly.candidates().getFirst().encoderClass(), poly.containerName(),
            poly.candidates().stream()
                .map(c -> new CallSiteExtraction.PolymorphicCandidate(
                    c.typeName(), c.typeId(), c.keyColumns(), c.table()))
                .toList(),
            slotType, nonNull);
    }

    /**
     * True when {@code cls} implements {@code org.jooq.Record} (transitively, e.g. via
     * {@code TableRecord} / {@code UpdatableRecord}). Matched by interface FQN rather than
     * {@code org.jooq.Record.class.isAssignableFrom(cls)} so the result does not depend on whether
     * the codegen classloader shares jOOQ's {@code Record} {@link Class} identity with the
     * generator's loader — the same classloader-agnostic discipline {@link #looksLikeBeanCandidate}
     * uses with its package-name test.
     */
    static boolean isJooqRecord(Class<?> cls) {
        if (cls == null) return false;
        if (cls.getName().equals("org.jooq.Record")) return true;
        for (Class<?> i : cls.getInterfaces()) {
            if (isJooqRecord(i)) return true;
        }
        return isJooqRecord(cls.getSuperclass());
    }

    // ===== Java-side helpers =====

    /**
     * Java member representing one field on the bean. {@code javaName} is the canonical component
     * name (record) or property name (JavaBean). {@code elementTypeName} is the Java element type
     * (with List<>/Set<> wrappers peeled for list-shape members).
     */
    private record JavaMember(String javaName, String elementTypeName, boolean list) {}

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
     * Detects "this is a consumer-authored class the developer expects to receive populated" —
     * i.e. anything outside the JDK / {@code org.jooq.*} that isn't a primitive, array, or enum.
     * Detection is deliberately permissive: once a candidate is paired with an input-object SDL
     * slot, the strict shape check in {@link #checkJavaBeanShape} runs, and a class lacking a
     * viable construction strategy is rejected loudly. Silent fallback to {@link
     * CallSiteExtraction.Direct} would re-introduce the runtime {@code ClassCastException} the
     * input-bean path exists to eliminate.
     */
    private boolean looksLikeBeanCandidate(Class<?> cls) {
        if (cls.isPrimitive() || cls.isArray() || cls.isEnum()) return false;
        if (cls.getPackageName() == null) return false;
        String pkg = cls.getPackageName();
        boolean jdkOrJooq = pkg.equals("java") || pkg.startsWith("java.")
            || pkg.equals("javax") || pkg.startsWith("javax.")
            || pkg.equals("jakarta") || pkg.startsWith("jakarta.")
            || pkg.equals("org.jooq") || pkg.startsWith("org.jooq.");
        return !jdkOrJooq;
    }

    /**
     * Loads a type named the way a signature spells it from the codegen classloader, through
     * {@link ConsumerClassLoad#loadSignatureType}, which strips generic arguments and retries a
     * nested class with {@code $}. A type that is there but cannot be linked comes back
     * {@link ConsumerClassLoad.Unlinkable}, which callers surface as its typed rejection rather
     * than reading it as a missing type.
     */
    private ConsumerClassLoad tryLoad(String typeName) {
        // nameability: exempt (signature-derived type name, not a name anyone wrote)
        return ConsumerClassLoad.loadSignatureType(typeName, ctx.codegenLoader());
    }

    // ===== Java/SDL list peeling =====

    /** Peeled Java type: list flag + element type name. */
    private record JavaElement(boolean list, String elementTypeName) {}

    /**
     * Peels {@code List<X>} / {@code Set<X>} to {@code X} and boxes a primitive scalar type name
     * (e.g. {@code "int"}) to its wrapper FQN (e.g. {@code "java.lang.Integer"}). This is the
     * single point at which {@link java.lang.reflect.Type#getTypeName()} enters the model, so the
     * boxing here is what guarantees the {@link CallSiteExtraction.FieldBinding#javaElementTypeName}
     * invariant ("real class name, never a primitive literal") that the emitter relies on. The list
     * branches do not box: Java disallows {@code List<int>}, so the generic argument is always
     * already a reference type.
     */
    private static JavaElement peelJavaListSet(String typeName) {
        if (typeName.startsWith("java.util.List<") && typeName.endsWith(">")) {
            return new JavaElement(true,
                typeName.substring("java.util.List<".length(), typeName.length() - 1));
        }
        if (typeName.startsWith("java.util.Set<") && typeName.endsWith(">")) {
            return new JavaElement(true,
                typeName.substring("java.util.Set<".length(), typeName.length() - 1));
        }
        return new JavaElement(false, boxPrimitive(typeName));
    }

    static String boxPrimitive(String name) {
        return switch (name) {
            case "int"     -> "java.lang.Integer";
            case "long"    -> "java.lang.Long";
            case "boolean" -> "java.lang.Boolean";
            case "double"  -> "java.lang.Double";
            case "float"   -> "java.lang.Float";
            case "short"   -> "java.lang.Short";
            case "byte"    -> "java.lang.Byte";
            case "char"    -> "java.lang.Character";
            default        -> name;
        };
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
