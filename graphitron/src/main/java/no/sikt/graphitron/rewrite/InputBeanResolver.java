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
 * extraction that carries the bean instantiation plan. See R150
 * ({@code roadmap/service-method-input-bean-instantiation.md}) for the design contract.
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
 * {@code public} — generated fetchers live in a separate {@code .generated.fetchers} package and
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
     * whose SDL arg is an input-object into a typed {@link CallSiteExtraction.InputBean}. Scalar
     * SDL args (including custom scalars wired via {@code @scalarType}) keep the Direct
     * extraction: graphql-java coerces the wire value to the declared Java type at runtime, and
     * the generator trusts that wiring. An input-object SDL arg always returns a
     * {@code Map<String, Object>} from graphql-java, and only the InputBean path can populate the
     * consumer's typed parameter without an unchecked cast that fails at first field access.
     *
     * <p>Rejections (returned as {@link Result.Failed}, never silent fallbacks):
     * <ul>
     *   <li>SDL is input-object but the Java element type can't be loaded.</li>
     *   <li>SDL is input-object but the Java type is {@code java.util.Map} — a dedicated arm with
     *       a sharper message: Map at the service boundary is a permanent anti-pattern; use a
     *       typed bean, or declare a custom scalar via {@code @scalarType} for open-ended JSON.</li>
     *   <li>SDL is input-object but the Java element type is in the JDK / {@code org.jooq.*}, or
     *       is an enum / primitive / array — i.e. not a populatable consumer bean.</li>
     *   <li>SDL list-shape and Java list-shape disagree.</li>
     *   <li>The bean class is not {@code public} (generated fetchers live in a different package
     *       and cannot reach package-private classes).</li>
     *   <li>The bean class has no record / public no-arg-ctor construction strategy.</li>
     *   <li>For records, the component/field correspondence is not a total bijection: a component
     *       binds to no SDL field (under-arity), or an SDL field binds to no component (silent drop).
     *       Member binding honors {@code @field(name:)} (the input-side mirror of R191), so the
     *       correspondence is by binding key, not raw name. JavaBeans tolerate partial population
     *       (unmatched fields are skipped); the empty-bindings case is the only JavaBean rejection.</li>
     *   <li>Two SDL fields resolving to the same Java-member binding key (a {@code @field(name:)}
     *       collision, or a value colliding with another field's plain name) — ambiguous binding.</li>
     *   <li>The bean shape is recursive (the same class appears nested inside itself, directly or
     *       transitively). Recursive shapes are not supported in v1 — the helper would emit
     *       mutually-recursive method calls with no terminating leaf.</li>
     * </ul>
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
            SdlElement sdl = peelSdlListNonNull(sdlType);
            // Direct is reserved for GraphQL scalar SDL args (including custom scalars wired via
            // `@scalarType`). graphql-java coerces the incoming value to the Java type the
            // consumer declared for that scalar — the generator trusts that wiring and emits
            // `env.getArgument(name)` unchanged. Only GraphQL input-object SDL args trigger the
            // InputBean classification; anything else stays on the existing Direct extraction.
            if (!(sdl.elementType() instanceof GraphQLInputObjectType iot)) {
                newParams.add(p);
                continue;
            }
            // SDL says input-object → the Java parameter MUST be a populatable bean. Map / JDK /
            // jOOQ / enum / array shapes are rejected loudly. Map<K, V> is a permanent rejection
            // (anti-pattern at the service boundary, not a v1 deferral): consumers wanting
            // open-ended-JSON semantics declare a custom scalar with `@scalarType` and a Map-typed
            // Java binding — that lives in Direct, not InputBean.
            JavaElement elt = peelJavaListSet(p.typeName());
            Class<?> elementClass = tryLoad(elt.elementTypeName());
            if (elementClass == null) {
                return new Result.Failed(Rejection.structural(
                    "parameter '" + p.name() + "' on method '" + method.methodName()
                    + "' in class '" + method.className() + "' has Java element type '"
                    + elt.elementTypeName() + "' which is not loadable, but the GraphQL argument '"
                    + arg.graphqlArgName() + "' is an input-object — declare a consumer-authored"
                    + " bean class (record or class with a public no-arg constructor) for the parameter"));
            }
            if (Map.class.isAssignableFrom(elementClass)) {
                return new Result.Failed(Rejection.structural(
                    "parameter '" + p.name() + "' on method '" + method.methodName()
                    + "' in class '" + method.className() + "' has Java type 'java.util.Map' for"
                    + " the GraphQL input-object argument '" + arg.graphqlArgName() + "' (type '"
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
                    + " argument '" + arg.graphqlArgName() + "' has input-object type '"
                    + GraphQLTypeUtil.simplePrint(sdlType) + "' — replace the parameter type with a"
                    + " consumer-authored bean class mirroring the input-object"));
            }
            if (elt.list() != sdl.list()) {
                return new Result.Failed(Rejection.structural(
                    "parameter '" + p.name() + "' on method '" + method.methodName()
                    + "' in class '" + method.className() + "' is "
                    + (elt.list() ? "list-shaped on Java side" : "scalar on Java side")
                    + " but the GraphQL argument '" + arg.graphqlArgName() + "' is "
                    + (sdl.list() ? "list-shaped" : "scalar")
                    + " — match the cardinalities"));
            }
            // R311: the param's Java type is a generated jOOQ TableRecord (singular or List<…>). The
            // type pass already classified the SDL input type as JooqTableRecordInputType, table and
            // all; read that answer rather than re-resolving, and bind on the COLUMN axis (@field(name:)
            // → ColumnRef) plus an optional @nodeId identity decode, not the Java-member axis the bean
            // path uses. Sits after the shared input-object gates (loadable / Map / cardinality-parity)
            // so a jOOQ record reuses them; only the member-axis bean instantiation below is replaced.
            // Cardinality parity is inherited from the :elt.list() != sdl.list() check above, which the
            // walker relies on to read list-ness off the Java type alone.
            if (ctx.types != null
                    && ctx.types.get(iot.getName()) instanceof GraphitronType.JooqTableRecordInputType jtr) {
                JooqBuilt jbuilt = buildJooqRecord(jtr, iot, p.name(), method.methodName(),
                    method.className(), arg.graphqlArgName());
                if (jbuilt instanceof JooqBuilt.Fail jf) {
                    return new Result.Failed(jf.rejection());
                }
                var jr = ((JooqBuilt.Ok) jbuilt).record();
                var jtyped = (MethodRef.Param.Typed) p;
                newParams.add(new MethodRef.Param.Typed(jtyped.name(), jtyped.typeName(), jtyped.javaType(),
                    new ParamSource.Arg(jr, arg.path())));
                continue;
            }
            // R315 (D2): an @table on the input classifies it as a TableInputType (the
            // "Graphitron owns the DML" contract), which contradicts a jOOQ-record @service param
            // (the service owns the DML, R311). Without this arm a TableRecord param against a
            // @table-present input falls through to the bean path and dies on the misleading "bean
            // class … has no fields matching"; reject it honestly instead, so the binding/error
            // behavior converges with the @table-absent path. isTableRecord is narrower than
            // isJooqRecord on purpose: a non-table Record has no TableRef and keeps falling through
            // to the bean path rather than reaching this (TableRef-less) reject.
            if (ctx.types != null
                    && isTableRecord(elementClass)
                    && ctx.types.get(iot.getName()) instanceof GraphitronType.TableInputType) {
                return new Result.Failed(Rejection.structural(
                    "parameter '" + p.name() + "' on method '" + method.methodName() + "' in class '"
                    + method.className() + "' is jOOQ record '" + elementClass.getName() + "', but the"
                    + " GraphQL input '" + iot.getName() + "' carries @table — drop @table; this input"
                    + " feeds a @service param, so the service owns record construction (an @table input"
                    + " means Graphitron owns the DML, which contradicts a jOOQ-record @service param)"));
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
     * type classified as {@link GraphitronType.JooqTableRecordInputType} (R311, generalized by R315).
     * Walks the SDL fields binding each on the column axis: every {@code @nodeId(typeName:)} field is a
     * {@link CallSiteExtraction.RecordKeyDecode} (R195's wire-decode mechanism) whose decoded values
     * load into resolved target columns on this record — the record's own key (same-table identity,
     * R311) or a foreign key's child columns (cross-table reference, R315); every other plain field names
     * a column through {@code @field(name:)} (a resolved {@link CallSiteExtraction.ColumnBinding}). A
     * record may carry several {@code @nodeId} fields.
     *
     * <p>A field whose SDL type is itself a directiveless nested grouping input flattens transparently
     * onto the one backing table (R336): {@link #collectJooqBindings} recurses into the nested type's
     * fields and keeps producing the same column-axis carriers, each carrying the full access path from
     * the record's own {@code Map} down to the leaf (so {@code details.title} carries
     * {@code ["details", "title"]}). This is the column-axis analogue of the {@code @table}-input nesting
     * the filter axis already flattens.
     *
     * <p>Rejections are R195/R97-shaped and surface at validate time as {@code UnclassifiedField} — the
     * honest replacement for the bean path's misleading "has no fields matching":
     * <ul>
     *   <li>the param record type is not in the jOOQ catalog;</li>
     *   <li>a {@code @nodeId} field whose {@code typeName:} / {@code @reference} cannot resolve to target
     *       columns on this record (R195/R315, see {@link #buildRecordKeyDecode});</li>
     *   <li>a plain {@code @field} (at any nesting depth) resolving to no column on the table;</li>
     *   <li>a nested grouping input that reaches itself (cyclic shape) — a single record cannot represent
     *       a recursive input;</li>
     *   <li>a list-shaped nested grouping field — a single record has one value per column, so a list of
     *       column-groups is a cardinality contradiction;</li>
     *   <li>a nested input carrying {@code @table} — a second DML target, not a column group to flatten
     *       (compound multi-table mutations are R122's scope);</li>
     *   <li>two plain {@code @field} leaves (in any nested group) resolving to the same column — two
     *       fields cannot populate one column. Decode-vs-decode / decode-vs-column on a shared column stay
     *       with R322's runtime value-agreement deferral (last-write-wins), unchanged.</li>
     * </ul>
     */
    private JooqBuilt buildJooqRecord(GraphitronType.JooqTableRecordInputType jtr,
            graphql.schema.GraphQLInputObjectType iot, String paramName, String methodName,
            String className, String graphqlArgName) {
        String where = "parameter '" + paramName + "' on method '" + methodName + "' in class '"
            + className + "' (GraphQL argument '" + graphqlArgName + "')";
        TableRef table = jtr.table();
        if (table == null) {
            return new JooqBuilt.Fail(Rejection.structural(where
                + ": param record type '" + jtr.fqClassName() + "' is not in the jOOQ catalog —"
                + " the backing class comes from a catalog not loaded at build time"));
        }
        var columnBindings = new ArrayList<CallSiteExtraction.ColumnBinding>();
        var keyDecodes = new ArrayList<CallSiteExtraction.RecordKeyDecode>();
        // Seed the cycle guard with the param record's own input type name, so an immediate
        // self-reference (a nested field typed as the outer input) is named at the first hop. The
        // recursion is on SDL nested-input type names onto this one table, so it threads the
        // SDL-type-name "expanding" discipline (ClassifyContext) rather than buildInputBean's
        // Set<Class<?>> visited (D2): a different carrier family on a parallel axis.
        Rejection rejection = collectJooqBindings(iot, table, where, List.of(),
            ClassifyContext.root().expanding(iot.getName()), columnBindings, keyDecodes);
        if (rejection != null) {
            return new JooqBuilt.Fail(rejection);
        }
        // Plain-column collision (D3): two @field leaves (in any nested group) resolving to one column
        // would last-write-wins silently. Reject, mirroring the member-axis binding-key collision reject.
        // Decode-vs-decode / decode-vs-column overlaps are intentionally NOT checked here — those stay
        // with R322's value-agreement deferral.
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
     * Recursively walks the SDL fields of {@code iot} — the param record's input type at depth 1, or a
     * nested directiveless grouping input deeper — appending column-axis carriers to {@code columnBindings}
     * / {@code keyDecodes}. {@code pathPrefix} is the ordered enclosing nested-input field names (empty at
     * depth 1); each carrier's {@code path} is {@code pathPrefix} + the leaf field name. {@code classifyCtx}
     * carries the SDL-type-name "expanding" set for cycle detection (D2), the same idiom
     * {@code BuildContext.classifyInputField} threads for the {@code @table}-input nesting. Returns the
     * first {@link Rejection} encountered, or {@code null} on success (the bindings are accumulated into
     * the caller-supplied lists).
     *
     * <p>This stays parallel to the member-axis recursion ({@code bindField → buildInputBean}) rather than
     * routing through {@code BuildContext.classifyInputField}: that produces a different carrier family
     * ({@code InputField.*}) on the filter axis and resolves different identity semantics. The two axes are
     * the existing intentional split; this recursion is the column axis catching up at the nested level.
     */
    private Rejection collectJooqBindings(graphql.schema.GraphQLInputObjectType iot, TableRef table,
            String where, List<String> pathPrefix, ClassifyContext classifyCtx,
            List<CallSiteExtraction.ColumnBinding> columnBindings,
            List<CallSiteExtraction.RecordKeyDecode> keyDecodes) {
        for (var f : iot.getFieldDefinitions()) {
            List<String> path = append(pathPrefix, f.getName());
            SdlElement sdlElt = peelSdlListNonNull(f.getType());
            if (f.hasAppliedDirective(DIR_NODE_ID)) {
                // R315: multiple @nodeId fields are legal (an FK-reference record carries several FK
                // references). Each resolves independently to its target columns on this record; when
                // two decodes target the same column their runtime value-agreement is a data-dependent
                // concern deferred to R322 (last-write-wins here). The legacy single-@nodeId gate is gone.
                var built = buildRecordKeyDecode(f, path, table, where);
                if (built instanceof KeyDecodeResult.Fail kf) {
                    return kf.rejection();
                }
                keyDecodes.add(((KeyDecodeResult.Ok) built).decode());
            } else if (sdlElt.elementType() instanceof GraphQLInputObjectType nestedIot) {
                // Nested directiveless grouping input → flatten its fields onto this table (R336).
                if (sdlElt.list()) {
                    return Rejection.structural(where
                        + ": nested input field '" + dottedPath(path) + "' is list-shaped (a list of '"
                        + nestedIot.getName() + "'), but a single backing record has one value per column"
                        + " — a list of column-groups cannot flatten onto one record. Make the field"
                        + " singular, or model the repetition as a separate list-valued mutation");
                }
                if (nestedIot.hasAppliedDirective(DIR_TABLE)) {
                    return Rejection.structural(where
                        + ": nested input field '" + dottedPath(path) + "' is typed '" + nestedIot.getName()
                        + "' which carries @table — a nested @table input is a second DML target, not a"
                        + " column group to flatten onto the param record's table (compound multi-table"
                        + " mutations are R122's scope). Drop @table to flatten this group's columns onto"
                        + " the parent, or model it as a separate mutation");
                }
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
     * {@link CallSiteExtraction.RecordKeyDecode}, branching on whether the referenced NodeType's table
     * is the param record's own table (R311 identity) or a different table (R315 FK reference):
     *
     * <ul>
     *   <li><b>Same table, no {@code @reference}</b> (node table == record table) → the decode loads
     *       the record's own key columns (own-PK identity).</li>
     *   <li><b>Same table, with {@code @reference}</b> → a self-FK reference (R328): the
     *       {@code @reference} names a same-table foreign key, and the node-key columns map through
     *       it to the self-FK's child columns on this record (never the record's own PK), via
     *       {@link BuildContext#resolveRecordFkTargetColumns} oriented with
     *       {@code selfRefFkOnSource=true}.</li>
     *   <li><b>Different table</b> → the cross-table FK-reference case (R315): the node-key columns
     *       map through the foreign key (deduced when exactly one connects the two tables, else named
     *       by {@code @reference(key:)}) to the FK's child columns on this record, via the same
     *       {@link BuildContext#resolveRecordFkTargetColumns}.</li>
     * </ul>
     *
     * The decode's {@code nonNull} is read off the SDL field's {@code ID!}-vs-{@code ID} nullability and
     * drives the emitter's throw-vs-conditional-set (D4), set identically for both branches.
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
            // Same-table identity (R311): the decoded values are the record's own key columns.
            targetColumns = resolved.keyColumns();
        } else {
            // Cross-table FK reference (R315), or a same-table self-FK reference (R328): map the
            // node-key columns through the FK to the record's child columns. A same-table @nodeId
            // *with* @reference names a self-FK — resolveRecordFkTargetColumns orients it through
            // resolveFkSlots(selfRefFkOnSource=true), landing the decoded keys on the self-FK's
            // child columns on this record, never the record's own PK. Same-table *without*
            // @reference is the identity branch above.
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

        // Index the SDL fields by their Java-member binding key (the @field(name:) value, or the
        // field's own name when the directive is absent). Two SDL fields resolving to one key is a
        // structural ambiguity on either target: on the record arm the second would silently win the
        // bijection slot (order-dependent binding); on the JavaBean arm the same setter would be
        // invoked twice. Reject it here, before either arm builds a result, so neither can produce an
        // order-dependent or double-bound bean.
        var sdlByBindingKey = new LinkedHashMap<String, GraphQLInputObjectField>();
        for (var f : iot.getFieldDefinitions()) {
            String key = bindingKey(f);
            // A present-but-blank @field(name:) yields an empty key (GraphQL field names are never
            // empty, so this is reachable only via the directive). It can match no record component
            // or setter; rather than silently skipping it on the JavaBean arm (a silent fallback the
            // directive newly opens), reject the malformed directive at classify time.
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

        // Records are positional and total (the canonical constructor needs every component, and a
        // leftover SDL field would be silently dropped); JavaBean setters are independent and
        // partial. The two arms encode that invariant difference; they share the binding-key index
        // above and the per-field leaf classification (bindField).
        return switch (target) {
            case RECORD -> bindRecord(beanClass, iot, javaMembersByName, sdlByBindingKey,
                paramName, methodName, className, visited);
            case JAVA_BEAN -> bindJavaBean(beanClass, iot, javaMembersByName, sdlByBindingKey,
                paramName, methodName, className, visited);
        };
    }

    /**
     * The Java-member binding key for an SDL input field: the {@code @field(name:)} value when the
     * directive is present, else the field's own name. This is the input-side mirror of R191's
     * output-side "{@code @field} names the Java accessor" read
     * ({@code FieldBuilder.collectAccessorMatches}) and uses the same {@code argString(...).orElse(name)}
     * idiom as the column-axis read in {@code BuildContext}. The key names the record component /
     * JavaBean property the field binds to; the field's own name stays the {@code Map} key the
     * generated helper reads the wire value from.
     */
    private static String bindingKey(GraphQLInputObjectField f) {
        return f.hasAppliedDirective(DIR_FIELD)
            ? argString(f, DIR_FIELD, ARG_NAME).orElse(f.getName())
            : f.getName();
    }

    /**
     * Record arm: a bidirectional bijection between record components and SDL input fields. A
     * record's correspondence to its SDL input type is total in both directions, so the reduction
     * checks both:
     * <ul>
     *   <li><b>Every component must bind</b> (direction A). The canonical constructor takes every
     *       component, so a component with no SDL field bound to it (none named after it, none
     *       carrying {@code @field(name: "<component>")}) is a hard fail at classify time, rather
     *       than the under-arity constructor call the old loop emitted downstream.</li>
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
     * Member resolution has already happened (the directive selected which member binds); the
     * member's Java type now drives the leaf branch (nested {@code InputBean} / {@code EnumValueOf} /
     * R195's {@code NodeIdDecodeRecord} / {@code Direct}), unchanged from before. The binding carries
     * the SDL field name (the {@code Map} key the helper reads) separately from the Java member name
     * (the component / property it populates), so the emitter is agnostic to <em>how</em> the member
     * was chosen, the same property R191 relies on on the output side.
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
            // Site E (R261): the declared type IS the enum and assignment succeeds, but
            // Enum.valueOf((String) ...) throws IllegalArgumentException when an SDL enum value name
            // diverges from the Java constant names. Route through the single enum-constant parity
            // home (EnumMappingResolver, D3) — a divergence rejects loudly rather than emitting a
            // valueOf that crashes at runtime. This closes the asymmetry where the @service enum
            // producer built EnumValueOf with no parity check while the column/arg enum path did.
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
            // String cast to a *Record throws ClassCastException at the first request (the
            // R150/R195 bug). Branch to a @nodeId-decode leaf, or reject loudly — never fall
            // through to Direct with a record member.
            Class<?> memberClass = tryLoad(javaElementTypeName);
            if (memberClass != null && isJooqRecord(memberClass)) {
                RecordLeaf recordLeaf = buildJooqRecordLeaf(sdlField, sdlFieldName,
                    javaElementTypeName, nonNull, paramName, methodName, className);
                if (recordLeaf instanceof RecordLeaf.Fail rf) {
                    return new FieldResult.Fail(rf.rejection());
                }
                leaf = ((RecordLeaf.Ok) recordLeaf).leaf();
            } else {
                // Site A (R261): a scalar SDL field bound to a consumer-declared Java type only
                // lands on Direct once the wire-coercion predicate confirms graphql-java's coercion
                // output for the SDL scalar is assignable to that declared type. This widens R195's
                // narrow jOOQ-record-only rejection to the full wire-incompatible family (numeric
                // width, ID-as-numeric, domain types). The predicate is the sole producer of Direct.
                var wire = WireCoercionResolver.checkScalar(sdlElt.elementType(), javaElementTypeName,
                    ctx.types == null ? null : ctx.types.values(),
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
     * member has exactly these two outcomes; it never falls through to
     * {@link CallSiteExtraction.Direct} (R195).
     */
    private sealed interface RecordLeaf {
        record Ok(CallSiteExtraction.NodeIdDecodeRecord leaf) implements RecordLeaf {}
        record Fail(Rejection rejection) implements RecordLeaf {}
    }

    /**
     * Builds the {@link CallSiteExtraction.NodeIdDecodeRecord} leaf for a jOOQ-record-typed bean
     * member, reading {@code @nodeId(typeName:)} off the SDL field and resolving the decode
     * materialization data through {@link BuildContext#resolveNodeIdRecordDecode}. Handles
     * <strong>every</strong> record-member shape: single-column and composite key (arity is the
     * resolved key-column count, one typed {@code set} per column), scalar and list-valued (the
     * caller carries list-ness on the enclosing {@link CallSiteExtraction.FieldBinding}, which
     * drives the scalar-vs-list emitter variant). The leaf is arity- and shape-agnostic.
     *
     * <p>Rejects (rather than falling to {@link CallSiteExtraction.Direct}) only for
     * <em>malformed-directive</em> cases: no {@code @nodeId} on the member, {@code @nodeId} without
     * {@code typeName:}, or a {@code typeName:} that resolves to no known NodeType. There are no
     * shape gates: a composite-key or list-valued member is supported, not deferred.
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
        // The NodeId for `typeName` decodes into the record of that type's own @table. Loading those
        // key values into a *different* jOOQ record is unsound: the Tables.<NodeTable>.<col> field
        // references the decode helper emits are not fields of the declared record, and the helper
        // would return the node-table record into a bean field of the declared type. Without this
        // gate that surfaces only downstream as a javac "incompatible types" error in the consumer's
        // *Fetchers (the opptak List<SoknadSoknadsbehandlingTaggRecord> vs List<SoknadsbehandlingTaggRecord>
        // case), not as a graphitron rejection. Catch it at classification: the member's declared
        // record type must equal the node table's record type.
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

    /**
     * True when {@code cls} implements {@code org.jooq.TableRecord} (transitively). Strictly narrower
     * than {@link #isJooqRecord}: a non-table {@code Record} (e.g. {@code Record1}) implements
     * {@code org.jooq.Record} but not {@code org.jooq.TableRecord} and has no backing {@code TableRef},
     * so the R315 {@code @table}-on-input reject (D2) gates on this — a non-table record keeps falling
     * through to the bean path rather than reaching a reject that assumes a table. Same FQN-based,
     * classloader-agnostic discipline as {@link #isJooqRecord}.
     */
    private static boolean isTableRecord(Class<?> cls) {
        if (cls == null) return false;
        if (cls.getName().equals("org.jooq.TableRecord")) return true;
        for (Class<?> i : cls.getInterfaces()) {
            if (isTableRecord(i)) return true;
        }
        return isTableRecord(cls.getSuperclass());
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
     * CallSiteExtraction.Direct} would re-introduce the runtime {@code ClassCastException} R150
     * exists to eliminate.
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
