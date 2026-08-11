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
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.WireCoercionError;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_KEY;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_PATH;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_TYPE_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_FIELD;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_NODE_ID;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_REFERENCE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_TABLE;
import static no.sikt.graphitron.rewrite.BuildContext.argString;

/**
 * Resolves the {@link CallSiteExtraction.InputBean} arm: a {@code @service} method parameter whose
 * Java type is a consumer-authored class mirroring an SDL {@code input} type, instantiated at the
 * fetcher boundary so the service body never sees a {@code Map}. Post-processes a resolved
 * {@link MethodRef.Service} produced by {@link ServiceCatalog#reflectServiceMethod}.
 *
 * <p>Walks the method's parameters and rewrites the {@code CallSiteExtraction.Direct} arms that
 * the catalog could not classify in isolation (no SDL access at reflection time) into a richer
 * extraction that carries the bean instantiation plan.
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
     * Result of enrichment. {@link Ok} carries the rewritten method (possibly equal to the input
     * when nothing matched). {@link Failed} carries a structural rejection ready for the caller
     * to surface verbatim, prefixed if the surrounding directive demands it.
     */
    sealed interface Result {
        record Ok(MethodRef.Service method) implements Result {}
        record Failed(Rejection rejection) implements Result {}
    }

    /**
     * Walks the method's {@link ParamSource.Arg} parameters and rewrites every Direct extraction
     * whose SDL arg is an input-object into a typed {@link CallSiteExtraction.InputBean}, or a
     * {@link CallSiteExtraction.JooqRecord} when the input type classified as a jOOQ-record input.
     * Scalar SDL args keep the Direct extraction. An input-object SDL arg always arrives as a
     * {@code Map<String, Object>} from graphql-java, and only a typed instantiation plan can
     * populate the consumer's parameter without an unchecked cast that fails at first field
     * access. Every unusable pairing returns {@link Result.Failed}, never a silent fallback; the
     * rejection messages name each case.
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
            // Bean-shaped params are always top-level argument bindings; a nested-path binding
            // (a param drilling one field out of an input) carries a scalar leaf type by
            // construction and stays on the Direct path.
            if (!arg.path().isHead()) {
                newParams.add(p);
                continue;
            }
            GraphQLInputType sdlType = argTypes.get(arg.path().headName());
            SdlElement sdl = peelSdlListNonNull(sdlType);
            if (!(sdl.elementType() instanceof GraphQLInputObjectType iot)) {
                newParams.add(p);
                continue;
            }
            JavaElement elt = peelJavaListSet(p.typeName());
            Class<?> elementClass = tryLoad(elt.elementTypeName());
            if (elementClass == null) {
                return new Result.Failed(Rejection.structural(
                    "parameter '" + p.name() + "' on method '" + method.methodName()
                    + "' in class '" + method.className() + "' has Java element type '"
                    + elt.elementTypeName() + "' which is not loadable, but the GraphQL argument '"
                    + arg.path().headName() + "' is an input-object — declare a consumer-authored"
                    + " bean class (record or class with a public no-arg constructor) for the parameter"));
            }
            if (Map.class.isAssignableFrom(elementClass)) {
                return new Result.Failed(Rejection.structural(
                    "parameter '" + p.name() + "' on method '" + method.methodName()
                    + "' in class '" + method.className() + "' has Java type 'java.util.Map' for"
                    + " the GraphQL input-object argument '" + arg.path().headName() + "' (type '"
                    + GraphQLTypeUtil.simplePrint(sdlType) + "') — Map<K, V> at the service"
                    + " boundary is a permanent anti-pattern in graphitron; replace the parameter"
                    + " with a typed bean (record or class with a public no-arg constructor"
                    + " mirroring the input-object fields), or — for open-ended-JSON semantics —"
                    + " declare a custom scalar via `@scalarType` and bind its Java type instead"));
            }
            if (!looksLikeBeanCandidate(elementClass)) {
                return new Result.Failed(Rejection.structural(
                    "parameter '" + p.name() + "' on method '" + method.methodName()
                    + "' in class '" + method.className() + "' has Java element type '"
                    + elementClass.getName() + "' (JDK / jOOQ / enum / array) but the GraphQL"
                    + " argument '" + arg.path().headName() + "' has input-object type '"
                    + GraphQLTypeUtil.simplePrint(sdlType) + "' — replace the parameter type with a"
                    + " consumer-authored bean class mirroring the input-object"));
            }
            if (elt.list() != sdl.list()) {
                return new Result.Failed(Rejection.structural(
                    "parameter '" + p.name() + "' on method '" + method.methodName()
                    + "' in class '" + method.className() + "' is "
                    + (elt.list() ? "list-shaped on Java side" : "scalar on Java side")
                    + " but the GraphQL argument '" + arg.path().headName() + "' is "
                    + (sdl.list() ? "list-shaped" : "scalar")
                    + " — match the cardinalities"));
            }
            // The classifier's verdict for this SDL input is JooqTableRecordInputType, table and
            // all; read that answer rather than re-resolving. lookAheadVerdict recomputes it
            // registry-free: this runs during field classification, when the input may be a
            // not-yet-visited child of the walk. A jOOQ-record param binds on the
            // column axis (@field(name:) → ColumnRef, plus optional @nodeId identity decodes), not
            // the Java-member axis the bean path uses. Sits after the shared input-object gates
            // above so it reuses the loadable / Map / cardinality-parity checks; the walker relies
            // on that parity to read list-ness off the Java type alone.
            if (ctx.lookAheadVerdict(iot.getName()) instanceof GraphitronType.JooqTableRecordInputType jtr) {
                JooqBuilt jbuilt = buildJooqRecord(jtr, iot, p.name(), method.methodName(),
                    method.className(), arg.path().headName());
                if (jbuilt instanceof JooqBuilt.Fail jf) {
                    return new Result.Failed(jf.rejection());
                }
                var jr = ((JooqBuilt.Ok) jbuilt).record();
                var jtyped = (MethodRef.Param.Typed) p;
                newParams.add(new MethodRef.Param.Typed(jtyped.name(), jtyped.typeName(), jtyped.javaType(),
                    new ParamSource.Arg(jr, arg.path())));
                continue;
            }
            var built = buildInputBean(elementClass, iot, p.name(), method.methodName(),
                method.className(), new HashSet<>());
            if (built instanceof Built.Fail f) {
                return new Result.Failed(f.rejection());
            }
            var ib = ((Built.Ok) built).bean();
            var typed = (MethodRef.Param.Typed) p;
            newParams.add(new MethodRef.Param.Typed(typed.name(), typed.typeName(), typed.javaType(),
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

    /** Outcome of building a {@link CallSiteExtraction.JooqRecord}: the carrier or a structural fail. */
    private sealed interface JooqBuilt {
        record Ok(CallSiteExtraction.JooqRecord record) implements JooqBuilt {}
        record Fail(Rejection rejection) implements JooqBuilt {}
    }

    /**
     * Builds the {@link CallSiteExtraction.JooqRecord} for a {@code @service} param whose SDL input
     * type classified as {@link GraphitronType.JooqTableRecordInputType}. Binds each SDL field on
     * the column axis: a {@code @nodeId(typeName:)} field becomes a
     * {@link CallSiteExtraction.RecordKeyDecode} whose decoded values load into resolved target
     * columns on this record (the record's own key, or a foreign key's child columns); every other
     * field names a column through {@code @field(name:)} (a {@link CallSiteExtraction.ColumnBinding}).
     * A record may carry several {@code @nodeId} fields.
     *
     * <p>A directiveless nested grouping input flattens transparently onto the one backing table:
     * {@link #collectJooqBindings} recurses and keeps producing the same column-axis carriers, each
     * carrying the full access path from the record's own {@code Map} down to the leaf (so
     * {@code details.title} carries {@code ["details", "title"]}).
     *
     * <p>Unusable shapes reject structurally, surfacing at validate time as
     * {@code UnclassifiedField}: uncataloged record type, unresolvable {@code @nodeId}, a field
     * matching no column, a cyclic, list-shaped, or {@code @table}-carrying nested input, or two
     * plain fields on one column. The rejection messages here and in
     * {@link #collectJooqBindings} name each case.
     */
    private JooqBuilt buildJooqRecord(GraphitronType.JooqTableRecordInputType jtr,
            graphql.schema.GraphQLInputObjectType iot, String paramName, String methodName,
            String className, String slotName) {
        String where = "parameter '" + paramName + "' on method '" + methodName + "' in class '"
            + className + "' (GraphQL argument '" + slotName + "')";
        TableRef table = jtr.table();
        if (table == null) {
            return new JooqBuilt.Fail(Rejection.structural(where
                + ": param record type '" + jtr.fqClassName() + "' is not in the jOOQ catalog —"
                + " the backing class comes from a catalog not loaded at build time"));
        }
        var columnBindings = new ArrayList<CallSiteExtraction.ColumnBinding>();
        var keyDecodes = new ArrayList<CallSiteExtraction.RecordKeyDecode>();
        // Seed the cycle guard with the param record's own input type name, so an immediate
        // self-reference (a nested field typed as the outer input) is named at the first hop.
        // Cycle detection is on SDL nested-input type names (ClassifyContext's "expanding" set),
        // a different axis than buildInputBean's Set<Class<?>> visited.
        Rejection rejection = collectJooqBindings(iot, table, where, List.of(),
            ClassifyContext.root().expanding(iot.getName()), columnBindings, keyDecodes);
        if (rejection != null) {
            return new JooqBuilt.Fail(rejection);
        }
        // Two plain @field leaves (in any nested group) resolving to one column would
        // last-write-wins silently; reject, mirroring the member-axis binding-key collision
        // reject. Decode-vs-decode / decode-vs-column overlaps are intentionally NOT checked here;
        // those stay with the runtime value-agreement deferral.
        var byColumn = new LinkedHashMap<String, List<String>>();
        for (var cb : columnBindings) {
            List<String> prior = byColumn.putIfAbsent(cb.column().sqlName(), cb.path());
            if (prior != null) {
                return new JooqBuilt.Fail(Rejection.structural(where
                    + ": input fields '" + dottedPath(prior) + "' and '" + dottedPath(cb.path())
                    + "' both resolve to column '" + cb.column().sqlName() + "' on table '"
                    + table.tableName() + "' — two fields cannot populate one column; remove one, or"
                    + " point its @field(name:) at a different column"));
            }
        }
        return new JooqBuilt.Ok(new CallSiteExtraction.JooqRecord(
            table, columnBindings, List.copyOf(keyDecodes)));
    }

    /**
     * Recursively walks the SDL fields of {@code iot}, appending column-axis carriers to
     * {@code columnBindings} / {@code keyDecodes}. Each carrier's {@code path} is
     * {@code pathPrefix} (the ordered enclosing nested-input field names, empty at depth 1) plus
     * the leaf field name. Returns the first {@link Rejection} encountered, or {@code null} on
     * success.
     *
     * <p>Deliberately parallel to the member-axis recursion ({@code bindField} /
     * {@code buildInputBean}) rather than routing through {@code BuildContext.classifyInputField}:
     * that produces a different carrier family ({@code InputField.*}) on the filter axis and
     * resolves different identity semantics.
     */
    private Rejection collectJooqBindings(graphql.schema.GraphQLInputObjectType iot, TableRef table,
            String where, List<String> pathPrefix, ClassifyContext classifyCtx,
            List<CallSiteExtraction.ColumnBinding> columnBindings,
            List<CallSiteExtraction.RecordKeyDecode> keyDecodes) {
        for (var f : iot.getFieldDefinitions()) {
            List<String> path = append(pathPrefix, f.getName());
            SdlElement sdlElt = peelSdlListNonNull(f.getType());
            if (f.hasAppliedDirective(DIR_NODE_ID)) {
                // Multiple @nodeId fields are legal (an FK-reference record carries several FK
                // references). Each resolves independently to its target columns on this record;
                // two decodes targeting the same column are a data-dependent concern deferred to
                // the runtime value-agreement check (last-write-wins here).
                var built = buildRecordKeyDecode(f, path, table, where);
                if (built instanceof KeyDecodeResult.Fail kf) {
                    return kf.rejection();
                }
                keyDecodes.add(((KeyDecodeResult.Ok) built).decode());
            } else if (sdlElt.elementType() instanceof GraphQLInputObjectType nestedIot) {
                // Nested directiveless grouping input → flatten its fields onto this table.
                if (sdlElt.list()) {
                    return Rejection.structural(where
                        + ": nested input field '" + dottedPath(path) + "' is list-shaped (a list of '"
                        + nestedIot.getName() + "'), but a single backing record has one value per column"
                        + " — a list of column-groups cannot flatten onto one record. Make the field"
                        + " singular, or model the repetition as a separate list-valued mutation");
                }
                // A nested @table input is not a second DML target: @table on an input is
                // deprecated and inert, so the type is an ordinary grouping input and flattens
                // onto the parent record exactly as its directiveless twin does.
                if (classifyCtx.isExpanding(nestedIot.getName())) {
                    return Rejection.structural(where
                        + ": nested input field '" + dottedPath(path) + "' reaches input type '"
                        + nestedIot.getName() + "' which is already expanding — a cyclic input shape cannot"
                        + " flatten onto a single record (the column-axis analogue of a recursive bean)");
                }
                Rejection nested = collectJooqBindings(nestedIot, table, where, path,
                    classifyCtx.expanding(nestedIot.getName()), columnBindings, keyDecodes);
                if (nested != null) {
                    return nested;
                }
            } else {
                String key = bindingKey(f);
                var col = ctx.catalog.findColumn(table.tableName(), key);
                if (col.isEmpty()) {
                    return Rejection.structural(where
                        + ": input field '" + dottedPath(path) + "' (binding key '" + key + "') resolves to"
                        + " no column on table '" + table.tableName() + "' backing param record '"
                        + table.recordClass() + "'"
                        + BuildContext.candidateHint(key, ctx.catalog.columnSqlNamesOf(table.tableName())));
                }
                var ce = col.get();
                columnBindings.add(new CallSiteExtraction.ColumnBinding(
                    path, new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass(), ce.columnType())));
            }
        }
        return null;
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

    private sealed interface KeyDecodeResult {
        record Ok(CallSiteExtraction.RecordKeyDecode decode) implements KeyDecodeResult {}
        record Fail(Rejection rejection) implements KeyDecodeResult {}
    }

    /**
     * Resolves one {@code @nodeId(typeName:)} field of a jOOQ-record param into a
     * {@link CallSiteExtraction.RecordKeyDecode}:
     *
     * <ul>
     *   <li><b>Same table, no {@code @reference}</b>: the decode loads the record's own key
     *       columns (own-PK identity).</li>
     *   <li><b>Same table, with {@code @reference}</b>: the directive names a same-table self-FK;
     *       the node-key columns map through it to the self-FK's child columns on this record
     *       (never the record's own PK), via {@link BuildContext#resolveRecordFkTargetColumns}
     *       oriented with {@code selfRefFkOnSource=true}.</li>
     *   <li><b>Different table</b>: the node-key columns map through the foreign key (deduced when
     *       exactly one connects the two tables, else named by {@code @reference(key:)}) to the
     *       FK's child columns on this record, via the same resolver.</li>
     * </ul>
     *
     * The decode's {@code nonNull} is read off the SDL field's {@code ID!}-vs-{@code ID}
     * nullability and drives the emitter's throw-vs-conditional-set, identically for both branches.
     */
    private KeyDecodeResult buildRecordKeyDecode(graphql.schema.GraphQLInputObjectField f,
            List<String> path, TableRef table, String where) {
        var typeName = argString(f, DIR_NODE_ID, ARG_TYPE_NAME);
        if (typeName.isEmpty()) {
            return new KeyDecodeResult.Fail(Rejection.structural(where
                + ": @nodeId on field '" + f.getName() + "' must specify typeName:"
                + " explicitly (the param record type alone does not name the NodeType to decode against)"));
        }
        var resolution = ctx.resolveNodeIdRecordDecode(typeName.get());
        if (resolution instanceof BuildContext.NodeIdRecordDecode.Rejected r) {
            return new KeyDecodeResult.Fail(Rejection.structural(where
                + ": @nodeId(typeName: \"" + typeName.get() + "\") on field '" + f.getName() + "': "
                + r.message()));
        }
        var resolved = (BuildContext.NodeIdRecordDecode.Resolved) resolution;
        boolean nonNull = GraphQLTypeUtil.isNonNull(f.getType());
        List<ColumnRef> targetColumns;
        if (resolved.table().recordClass().equals(table.recordClass())
                && !f.hasAppliedDirective(DIR_REFERENCE)) {
            // Same-table identity: the decoded values are the record's own key columns.
            targetColumns = resolved.keyColumns();
        } else {
            // Cross-table FK reference, or a same-table self-FK reference: map the node-key
            // columns through the FK to this record's child columns (see the method javadoc).
            var fkTargets = ctx.resolveRecordFkTargetColumns(
                table, resolved.table().tableName(), resolved.keyColumns(), firstReferenceKey(f));
            if (fkTargets instanceof BuildContext.RecordFkTargets.Rejected fr) {
                return new KeyDecodeResult.Fail(Rejection.structural(where
                    + ": @nodeId(typeName: \"" + typeName.get() + "\") on field '" + f.getName()
                    + "': " + fr.message()));
            }
            targetColumns = ((BuildContext.RecordFkTargets.Resolved) fkTargets).targetColumns();
        }
        return new KeyDecodeResult.Ok(new CallSiteExtraction.RecordKeyDecode(
            path, resolved.encoderClass(), resolved.typeId(), targetColumns, nonNull));
    }

    /**
     * Reads the FK constraint name from the first {@code @reference(path:)} element on {@code f}, when
     * present. Only the first element is consulted for record population (later hops are a fetch/join
     * concern); an absent directive, empty path, or a first element without a {@code key:} yields empty,
     * and FK deduction then applies.
     */
    private static Optional<String> firstReferenceKey(GraphQLInputObjectField f) {
        var directive = f.getAppliedDirective(DIR_REFERENCE);
        if (directive == null) {
            return Optional.empty();
        }
        var pathArg = directive.getArgument(ARG_PATH);
        if (pathArg == null) {
            return Optional.empty();
        }
        Object value = pathArg.getValue();
        List<?> elements = value instanceof List<?> l ? l : (value == null ? List.of() : List.of(value));
        if (elements.isEmpty() || !(elements.get(0) instanceof Map<?, ?> m)) {
            return Optional.empty();
        }
        return Optional.ofNullable(m.get(ARG_KEY)).map(Object::toString).filter(s -> !s.isBlank());
    }

    /**
     * Recursively builds an {@link CallSiteExtraction.InputBean} for a given Java class paired with
     * an SDL {@link GraphQLInputObjectType}. Walks the SDL fields in declaration order, locating
     * the Java member on the bean and computing each leaf's transform. Records/JavaBeans are
     * supported; everything else is rejected. Nested input-object fields recurse into a nested
     * {@code InputBean} leaf. The {@code visited} set carries the in-flight chain of bean classes
     * so a self-referential or mutually-recursive shape fails as a structural rejection rather
     * than a {@code StackOverflowError}.
     */
    private Built buildInputBean(Class<?> beanClass, GraphQLInputObjectType iot,
                                  String paramName, String methodName, String className,
                                  Set<Class<?>> visited) {
        if (!visited.add(beanClass)) {
            return new Built.Fail(Rejection.structural(
                "parameter '" + paramName + "' on method '" + methodName + "' in class '"
                + className + "': bean class '" + beanClass.getName() + "' is recursive — input-object"
                + " shapes that reference themselves (directly or via another bean) are not supported"
                + " by the input-bean instantiation path"));
        }
        try {
            return buildInputBeanBody(beanClass, iot, paramName, methodName, className, visited);
        } finally {
            visited.remove(beanClass);
        }
    }

    private Built buildInputBeanBody(Class<?> beanClass, GraphQLInputObjectType iot,
                                      String paramName, String methodName, String className,
                                      Set<Class<?>> visited) {
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

        // Index the SDL fields by their Java-member binding key. Two SDL fields resolving to one
        // key is rejected before either arm builds a result: on the record arm the second would
        // silently win the bijection slot (order-dependent binding); on the JavaBean arm the same
        // setter would be invoked twice.
        var sdlByBindingKey = new LinkedHashMap<String, GraphQLInputObjectField>();
        for (var f : iot.getFieldDefinitions()) {
            String key = bindingKey(f);
            // A present-but-blank @field(name:) yields an empty key (GraphQL field names are never
            // empty, so only the directive can produce one). It can match no record component or
            // setter; reject the malformed directive at classify time rather than silently
            // skipping the field on the JavaBean arm.
            if (key.isEmpty()) {
                return new Built.Fail(Rejection.structural(
                    "parameter '" + paramName + "' on method '" + methodName + "' in class '"
                    + className + "': SDL input field '" + f.getName() + "' on type '" + iot.getName()
                    + "' carries @field(name:) with a blank value — give it the Java member name to"
                    + " bind (record component / JavaBean property), or drop the directive to bind by"
                    + " the field's own name"));
            }
            GraphQLInputObjectField prior = sdlByBindingKey.put(key, f);
            if (prior != null) {
                return new Built.Fail(Rejection.structural(
                    "parameter '" + paramName + "' on method '" + methodName + "' in class '"
                    + className + "': SDL input fields '" + prior.getName() + "' and '" + f.getName()
                    + "' on type '" + iot.getName() + "' both bind to Java member '" + key
                    + "' on bean class '" + beanClass.getName() + "' (via @field(name:) or a matching"
                    + " name) — two input fields cannot populate one member; rename one field or"
                    + " adjust its @field(name:)"));
            }
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
     * The Java-member binding key for an SDL input field: the {@code @field(name:)} value when the
     * directive is present, else the field's own name. The input-side mirror of the output-side
     * "{@code @field} names the Java accessor" read ({@code FieldBuilder.collectAccessorMatches}).
     * The key names the record component / JavaBean property the field binds to; the field's own
     * name stays the {@code Map} key the generated helper reads the wire value from.
     */
    private static String bindingKey(GraphQLInputObjectField f) {
        return f.hasAppliedDirective(DIR_FIELD)
            ? argString(f, DIR_FIELD, ARG_NAME).orElse(f.getName())
            : f.getName();
    }

    /**
     * Record arm: a bidirectional bijection between record components and SDL input fields.
     * <ul>
     *   <li><b>Every component must bind</b> (direction A). The canonical constructor takes every
     *       component, so a component with no SDL field bound to it fails at classify time rather
     *       than as an under-arity constructor call in the generated code.</li>
     *   <li><b>Every SDL field must be consumed</b> (direction B). A field whose binding key names
     *       no component would have its value silently dropped (it never reaches the constructor);
     *       for a record's total-mirror contract that is a hard fail, not the deliberate
     *       partial-population the JavaBean arm tolerates.</li>
     * </ul>
     * Bindings are produced in record-component (canonical-constructor) order.
     */
    private Built bindRecord(Class<?> beanClass, GraphQLInputObjectType iot,
            Map<String, JavaMember> componentsByName,
            Map<String, GraphQLInputObjectField> sdlByBindingKey,
            String paramName, String methodName, String className, Set<Class<?>> visited) {
        var bindings = new ArrayList<CallSiteExtraction.FieldBinding>();
        var consumedKeys = new HashSet<String>();
        // Direction A: every component must bind. componentsByName iterates in component order.
        for (var ce : componentsByName.entrySet()) {
            String component = ce.getKey();
            GraphQLInputObjectField sdlField = sdlByBindingKey.get(component);
            if (sdlField == null) {
                return new Built.Fail(Rejection.structural(
                    "parameter '" + paramName + "' on method '" + methodName + "' in class '"
                    + className + "': record '" + beanClass.getName() + "' component '" + component
                    + "' has no SDL input field bound to it on type '" + iot.getName() + "' — every"
                    + " record component must bind (the canonical constructor needs them all); add a"
                    + " field named '" + component + "' to the input type, or @field(name: \""
                    + component + "\") to the field that should populate it"));
            }
            consumedKeys.add(component);
            FieldResult r = bindField(sdlField, ce.getValue(), paramName, methodName, className, visited);
            if (r instanceof FieldResult.Fail f) {
                return new Built.Fail(f.rejection());
            }
            bindings.add(((FieldResult.Ok) r).binding());
        }
        // Direction B: every SDL field must be consumed by some component.
        for (var e : sdlByBindingKey.entrySet()) {
            if (!consumedKeys.contains(e.getKey())) {
                return new Built.Fail(Rejection.structural(
                    "parameter '" + paramName + "' on method '" + methodName + "' in class '"
                    + className + "': SDL input field '" + e.getValue().getName() + "' (binding key '"
                    + e.getKey() + "') on type '" + iot.getName() + "' names no component of record '"
                    + beanClass.getName() + "' — every field of a record-backed @service input must"
                    + " bind to a component (else its value is silently dropped); remove the field,"
                    + " or point its @field(name:) at a component"));
            }
        }
        return new Built.Ok(new CallSiteExtraction.InputBean(
            ClassName.bestGuess(beanClass.getName()),
            CallSiteExtraction.InputBean.Target.RECORD, bindings));
    }

    /**
     * JavaBean arm: setters are applied independently, so binding is partial by design. Each SDL
     * field whose binding key names a setter binds; a field whose key names no setter is skipped
     * (the bean simply does not populate it). The empty-bindings rejection fires only when no field,
     * by name or by {@code @field(name:)}, matches any setter — the genuine "this bean does not
     * mirror this input" case.
     */
    private Built bindJavaBean(Class<?> beanClass, GraphQLInputObjectType iot,
            Map<String, JavaMember> settersByName,
            Map<String, GraphQLInputObjectField> sdlByBindingKey,
            String paramName, String methodName, String className, Set<Class<?>> visited) {
        var bindings = new ArrayList<CallSiteExtraction.FieldBinding>();
        // sdlByBindingKey iterates in SDL declaration order (LinkedHashMap), so the bindings list
        // keeps that order; for JavaBean setters the order is not load-bearing, only stable.
        for (var e : sdlByBindingKey.entrySet()) {
            JavaMember member = settersByName.get(e.getKey());
            if (member == null) {
                continue;
            }
            FieldResult r = bindField(e.getValue(), member, paramName, methodName, className, visited);
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
            ClassName.bestGuess(beanClass.getName()),
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
     * member's Java type drives the leaf branch. The binding carries the SDL field name (the
     * {@code Map} key the helper reads) separately from the Java member name (the component /
     * property it populates), so the emitter is agnostic to <em>how</em> the member was chosen.
     */
    private FieldResult bindField(GraphQLInputObjectField sdlField, JavaMember member,
            String paramName, String methodName, String className, Set<Class<?>> visited) {
        String sdlFieldName = sdlField.getName();
        SdlElement sdlElt = peelSdlListNonNull(sdlField.getType());
        boolean listShape = sdlElt.list();
        boolean nonNull = GraphQLTypeUtil.isNonNull(sdlField.getType());
        String javaElementTypeName = member.elementTypeName();
        CallSiteExtraction leaf;
        if (sdlElt.elementType() instanceof GraphQLInputObjectType nestedIot) {
            Class<?> nestedClass = tryLoad(javaElementTypeName);
            if (nestedClass == null || !looksLikeBeanCandidate(nestedClass)) {
                return new FieldResult.Fail(Rejection.structural(
                    "parameter '" + paramName + "' on method '" + methodName + "' in class '"
                    + className + "': nested field '" + sdlFieldName + "' has SDL input-object"
                    + " type but the Java member type '" + javaElementTypeName
                    + "' is not a viable bean class"));
            }
            Built nested = buildInputBean(nestedClass, nestedIot, paramName, methodName,
                className, visited);
            if (nested instanceof Built.Fail f) {
                return new FieldResult.Fail(f.rejection());
            }
            leaf = ((Built.Ok) nested).bean();
        } else if (sdlElt.elementType() instanceof GraphQLEnumType enumSdl
                && tryLoad(javaElementTypeName) != null
                && tryLoad(javaElementTypeName).isEnum()) {
            // The declared type IS the enum and assignment succeeds, but
            // Enum.valueOf((String) ...) throws IllegalArgumentException when an SDL enum value
            // name diverges from the Java constant names. Route through the single enum-constant
            // parity home (EnumMappingResolver) so a divergence rejects loudly rather than
            // emitting a valueOf that crashes at runtime.
            var parity = new EnumMappingResolver(ctx).checkEnumConstants(enumSdl.getName(), tryLoad(javaElementTypeName));
            if (parity instanceof EnumMappingResolver.EnumConstantParity.Divergence d) {
                return new FieldResult.Fail(new WireCoercionError.EnumConstantDivergence(
                    javaElementTypeName,
                    d.mismatches().stream().map(EnumMappingResolver.EnumConstantParity.ValueMismatch::sdlValueName).toList(),
                    d.mismatches().isEmpty() ? List.of() : d.mismatches().get(0).candidates(),
                    "input-bean field '" + sdlFieldName + "' on parameter '" + paramName + "' of method '"
                        + methodName + "' in class '" + className + "'"));
            }
            leaf = new CallSiteExtraction.EnumValueOf(javaElementTypeName);
        } else {
            // Scalar SDL field. A jOOQ-record-typed member never lands on Direct: a wire ID
            // String cast to a *Record throws ClassCastException at the first request. Branch
            // to a @nodeId-decode leaf, or reject loudly.
            Class<?> memberClass = tryLoad(javaElementTypeName);
            if (memberClass != null && isJooqRecord(memberClass)) {
                RecordLeaf recordLeaf = buildJooqRecordLeaf(sdlField, sdlFieldName,
                    javaElementTypeName, nonNull, paramName, methodName, className);
                if (recordLeaf instanceof RecordLeaf.Fail rf) {
                    return new FieldResult.Fail(rf.rejection());
                }
                leaf = ((RecordLeaf.Ok) recordLeaf).leaf();
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
                    "input-bean field '" + sdlFieldName + "' on parameter '" + paramName + "' of method '"
                        + methodName + "' in class '" + className + "'");
                if (wire instanceof WireCoercionResolver.Result.Rejected rej) {
                    return new FieldResult.Fail(rej.error());
                }
                leaf = new CallSiteExtraction.Direct();
            }
        }
        return new FieldResult.Ok(new CallSiteExtraction.FieldBinding(
            sdlFieldName, member.javaName(), leaf, listShape, javaElementTypeName));
    }

    // ===== jOOQ-record member (@nodeId decode) =====

    /**
     * Classification of a jOOQ-{@code Record}-typed input-bean member: either a
     * {@link CallSiteExtraction.NodeIdDecodeRecord} decode leaf or a structural rejection. A record
     * member never falls through to {@link CallSiteExtraction.Direct}.
     */
    private sealed interface RecordLeaf {
        record Ok(CallSiteExtraction.NodeIdDecodeRecord leaf) implements RecordLeaf {}
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
    private RecordLeaf buildJooqRecordLeaf(GraphQLInputObjectField sdlField, String sdlFieldName,
            String recordTypeName, boolean nonNull,
            String paramName, String methodName, String className) {
        String where = "field '" + sdlFieldName + "' (jOOQ record '" + recordTypeName + "') on the"
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
        var resolved = (BuildContext.NodeIdRecordDecode.Resolved) resolution;
        // The NodeId for `typeName` decodes into the record of that NodeType's own @table. Loading
        // those key values into a *different* jOOQ record is unsound: the Tables.<NodeTable>.<col>
        // field references the decode helper emits are not fields of the declared record. Without
        // this gate the mismatch surfaces only as a javac "incompatible types" error in the
        // consumer's generated fetchers, not as a graphitron rejection; catch it at classification.
        String nodeTableRecord = resolved.table().recordClass().toString();
        if (!nodeTableRecord.equals(recordTypeName)) {
            return new RecordLeaf.Fail(Rejection.structural(where
                + ": the member is typed as jOOQ record '" + recordTypeName + "', but"
                + " @nodeId(typeName: \"" + typeName.get() + "\") decodes into '" + nodeTableRecord
                + "' (the record of that type's own @table). A NodeId cannot be decoded into a"
                + " different record type — declare the member as '" + nodeTableRecord + "', or point"
                + " @nodeId at the NodeType whose @table backs '" + recordTypeName + "'"));
        }
        return new RecordLeaf.Ok(new CallSiteExtraction.NodeIdDecodeRecord(
            resolved.encoderClass(), resolved.typeId(), resolved.keyColumns(),
            resolved.table(), nonNull));
    }

    /**
     * True when {@code cls} implements {@code org.jooq.Record} (transitively, e.g. via
     * {@code TableRecord} / {@code UpdatableRecord}). Matched by interface FQN rather than
     * {@code org.jooq.Record.class.isAssignableFrom(cls)} so the result does not depend on whether
     * the codegen classloader shares jOOQ's {@code Record} {@link Class} identity with the
     * generator's loader — the same classloader-agnostic discipline {@link #looksLikeBeanCandidate}
     * uses with its package-name test.
     */
    private static boolean isJooqRecord(Class<?> cls) {
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
     * Loads a class from the codegen classloader. Returns {@code null} when the type can't be
     * resolved; never swallows {@code Error}s beyond {@link LinkageError} (an unloadable type the
     * caller treats the same as a missing one).
     *
     * <p>Handles two name-shape concerns:
     * <ul>
     *   <li>Strips generic parameters: {@code List<Foo>} → {@code List}.</li>
     *   <li>Translates nested-class dots to {@code $} on retry. {@link java.lang.reflect.Type#getTypeName()}
     *       emits {@code com.example.Outer.Inner}, but {@link Class#forName(String, boolean, ClassLoader)}
     *       needs {@code com.example.Outer$Inner}. The retry walks the trailing dots one at a
     *       time, so multi-nested classes ({@code Outer.Mid.Inner}) also resolve.</li>
     * </ul>
     */
    private Class<?> tryLoad(String typeName) {
        int lt = typeName.indexOf('<');
        String raw = lt < 0 ? typeName : typeName.substring(0, lt);
        String candidate = raw;
        while (true) {
            try {
                return Class.forName(candidate, false, ctx.codegenLoader());
            } catch (ClassNotFoundException e) {
                int lastDot = candidate.lastIndexOf('.');
                if (lastDot < 0) return null;
                candidate = candidate.substring(0, lastDot) + '$' + candidate.substring(lastDot + 1);
            } catch (LinkageError e) {
                return null;
            }
        }
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
