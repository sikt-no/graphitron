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
import no.sikt.graphitron.render.CatalogRefs;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnOverlap;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.model.diagnostics.JooqRecordInputError;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.jooq.TableRef;
import no.sikt.graphitron.model.diagnostics.WireCoercionError;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
 * {@link MethodRef.Service} produced by {@link ServiceCatalog#bindServiceMethod}.
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
     * <p>Several plain leaves may name one column, which is the rename-deprecation pattern: they
     * merge into one {@link CallSiteExtraction.ColumnBinding} with ordered read paths when all but at
     * most one carry {@code @deprecated} (see {@link #foldColumnBindings}). Two live leaves on one
     * column stay an author error.
     *
     * <p>Unusable shapes reject, surfacing at validate time as {@code UnclassifiedField}: uncataloged
     * record type, unresolvable {@code @nodeId}, a field matching no column, a cyclic, list-shaped, or
     * {@code @table}-carrying nested input, or two live plain fields on one column (the typed
     * {@link JooqRecordInputError.LiveColumnCollision}). The rejection messages here and in
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
        var plainLeaves = new ArrayList<PlainLeaf>();
        var keyDecodes = new ArrayList<CallSiteExtraction.RecordKeyDecode>();
        // Seed the cycle guard with the param record's own input type name, so an immediate
        // self-reference (a nested field typed as the outer input) is named at the first hop.
        // Cycle detection is on SDL nested-input type names (ClassifyContext's "expanding" set),
        // a different axis than buildInputBean's Set<Class<?>> visited.
        Rejection rejection = collectJooqBindings(iot, table, where, List.of(),
            ClassifyContext.root().expanding(iot.getName()), plainLeaves, keyDecodes);
        if (rejection != null) {
            return new JooqBuilt.Fail(rejection);
        }
        var folded = foldColumnBindings(plainLeaves, keyDecodes, paramName, methodName, className,
            slotName, table);
        if (folded instanceof ColumnFold.Fail cf) {
            return new JooqBuilt.Fail(cf.rejection());
        }
        return new JooqBuilt.Ok(new CallSiteExtraction.JooqRecord(
            table, ((ColumnFold.Ok) folded).columnBindings(), List.copyOf(keyDecodes)));
    }

    /**
     * One plain ({@code @field}) leaf gathered by {@link #collectJooqBindings}, before the per-column
     * fold decides how many bindings the leaves become. Carries the leaf's resolved column and its
     * native {@code @deprecated} status alongside the access path; the deprecation flag exists only
     * to answer the alias-admission question in {@link #foldColumnBindings} and dies here rather than
     * riding into {@link CallSiteExtraction.ColumnBinding}, whose ordered read paths already encode
     * the answer.
     */
    private record PlainLeaf(List<String> path, ColumnRef column, boolean deprecated) {}

    /** Outcome of {@link #foldColumnBindings}: the folded bindings or a typed collision reject. */
    private sealed interface ColumnFold {
        record Ok(List<CallSiteExtraction.ColumnBinding> columnBindings) implements ColumnFold {}
        record Fail(Rejection rejection) implements ColumnFold {}
    }

    /**
     * Folds the gathered plain leaves into one {@link CallSiteExtraction.ColumnBinding} per written
     * column, through the {@link ColumnOverlap#groupByColumn} the mutation write paths and
     * {@code JooqRecordInstantiationEmitter} already read, so the classifier's admission decision and
     * the emitter's overlap dispatch consume one grouping by construction.
     *
     * <p>Per column: a single plain leaf passes through as a single-read-path binding. Several plain
     * leaves are a declared alias group when all but at most one carry {@code @deprecated} (the
     * rename-deprecation pattern: the author said "one column, several names"), and merge into one
     * binding whose ordered read paths are the live path first, then the deprecated paths in reverse
     * declaration order. Two or more <em>live</em> leaves reject as
     * {@link JooqRecordInputError.LiveColumnCollision}. Deprecation is the native {@code @deprecated}
     * directive on the SDL leaf, the only spelling this surface has.
     *
     * <p>The key decodes join the fold so its grouping is the emitter's, but a decode among a column's
     * writers changes nothing about the plain-subset decision: the plain leaves are admitted or
     * rejected on their own count, and a decode-vs-plain overlap keeps its existing deferral to the
     * runtime value-agreement check. The consequence is the invariant the emitter relies on: at most
     * one plain writer per column ever reaches it, so every overlap it sees involves a decode.
     */
    private static ColumnFold foldColumnBindings(List<PlainLeaf> plainLeaves,
            List<CallSiteExtraction.RecordKeyDecode> keyDecodes, String paramName, String methodName,
            String className, String slotName, TableRef table) {
        var writers = new ArrayList<ColumnOverlap.ColumnWriter>(plainLeaves.size() + keyDecodes.size());
        // Plain leaves first, so a column written by any of them is keyed in plain-declaration order
        // and the emitted binding list keeps the SDL order it had before the fold.
        plainLeaves.forEach(leaf -> writers.add(new PlainLeafWriter(leaf)));
        keyDecodes.forEach(kd -> writers.add(new KeyDecodeWriter(kd)));

        var columnBindings = new ArrayList<CallSiteExtraction.ColumnBinding>();
        for (var oc : ColumnOverlap.groupByColumn(writers)) {
            var group = oc.contributors().stream()
                .map(ColumnOverlap.Contributor::writer)
                .filter(PlainLeafWriter.class::isInstance)
                .map(w -> ((PlainLeafWriter) w).leaf())
                .toList();
            if (group.isEmpty()) {
                continue; // a column only a @nodeId decode writes carries no plain binding
            }
            if (group.size() == 1) {
                var only = group.get(0);
                columnBindings.add(CallSiteExtraction.ColumnBinding.of(only.path(), only.column()));
                continue;
            }
            long live = group.stream().filter(leaf -> !leaf.deprecated()).count();
            if (live >= 2) {
                return new ColumnFold.Fail(new JooqRecordInputError.LiveColumnCollision(
                    paramName, methodName, className, slotName,
                    group.stream()
                        .map(leaf -> new JooqRecordInputError.CollidingField(
                            dottedPath(leaf.path()), leaf.deprecated()))
                        .toList(),
                    group.get(0).column().sqlName(), table.tableName()));
            }
            columnBindings.add(new CallSiteExtraction.ColumnBinding(
                aliasReadPaths(group), group.get(0).column()));
        }
        return new ColumnFold.Ok(List.copyOf(columnBindings));
    }

    /**
     * The read paths of an admitted alias group, in precedence order: the live path first (a group has
     * at most one, or none while every alias in a rename chain is still awaiting its removal date),
     * then the deprecated paths in reverse declaration order so the latest-declared alias outranks the
     * one it superseded. Declaration order alone is not the rule, because a reformat or a field
     * reorder would silently change which value a client sees.
     */
    private static List<List<String>> aliasReadPaths(List<PlainLeaf> group) {
        var ordered = new ArrayList<List<String>>(group.size());
        group.stream().filter(leaf -> !leaf.deprecated()).map(PlainLeaf::path).forEach(ordered::add);
        var deprecated = group.stream().filter(PlainLeaf::deprecated).map(PlainLeaf::path)
            .collect(Collectors.toCollection(ArrayList::new));
        Collections.reverse(deprecated);
        ordered.addAll(deprecated);
        return ordered;
    }

    /** Adapts a gathered {@link PlainLeaf} into the shared per-column grouping view: one column, no
     *  decode, the dotted access path as the label. The fold downcasts back to reach the leaf. */
    private record PlainLeafWriter(PlainLeaf leaf) implements ColumnOverlap.ColumnWriter {
        @Override public List<ColumnOverlap.SlotColumn> targetColumns() {
            return List.of(new ColumnOverlap.SlotColumn(0, leaf.column()));
        }
        @Override public boolean decode() { return false; }
        @Override public String label() { return dottedPath(leaf.path()); }
    }

    /** Adapts a {@link CallSiteExtraction.RecordKeyDecode} into the shared per-column grouping view:
     *  its resolved target columns, which are one whole decode record, so the slots are contiguous. */
    private record KeyDecodeWriter(CallSiteExtraction.RecordKeyDecode keyDecode)
            implements ColumnOverlap.ColumnWriter {
        @Override public List<ColumnOverlap.SlotColumn> targetColumns() {
            return ColumnOverlap.SlotColumn.contiguous(keyDecode.targetColumns());
        }
        @Override public boolean decode() { return true; }
        @Override public String label() { return dottedPath(keyDecode.path()); }
    }

    /**
     * Recursively walks the SDL fields of {@code iot}, appending gathered {@link PlainLeaf}s to
     * {@code plainLeaves} and column-axis decode carriers to {@code keyDecodes}. Each leaf / carrier
     * path is {@code pathPrefix} (the ordered enclosing nested-input field names, empty at depth 1)
     * plus the leaf field name. Returns the first {@link Rejection} encountered, or {@code null} on
     * success. The plain leaves are per-leaf gathering state, one per SDL field: the per-column fold
     * in {@link #foldColumnBindings} turns them into the record's {@code ColumnBinding}s, which is
     * where several leaves naming one column collapse or reject.
     *
     * <p>Deliberately parallel to the member-axis recursion ({@code bindField} /
     * {@code buildInputBean}) rather than routing through {@code BuildContext.classifyInputField}:
     * that produces a different carrier family ({@code InputField.*}) on the filter axis and
     * resolves different identity semantics.
     */
    private Rejection collectJooqBindings(graphql.schema.GraphQLInputObjectType iot, TableRef table,
            String where, List<String> pathPrefix, ClassifyContext classifyCtx,
            List<PlainLeaf> plainLeaves,
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
                    classifyCtx.expanding(nestedIot.getName()), plainLeaves, keyDecodes);
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
                        + CatalogRefs.recordClass(table) + "'"
                        + BuildContext.candidateHint(key, ctx.catalog.columnSqlNamesOf(table.tableName())));
                }
                var ce = col.get();
                // f.isDeprecated() is the native @deprecated directive on the SDL leaf, the same read
                // InputTypeGenerator makes when it re-emits the marker onto the generated input type.
                plainLeaves.add(new PlainLeaf(path,
                    new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass()),
                    f.isDeprecated()));
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
        if (CatalogRefs.recordClass(resolved.table()).equals(CatalogRefs.recordClass(table))
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
     * supported; everything else is rejected. A nested input-object field whose binding key names a
     * member recurses into a nested {@code InputBean} leaf; one that names no member is a
     * <em>grouping</em> input whose own fields flatten onto this bean, see {@link #indexSdlFields}.
     * The {@code visited} set carries the in-flight chain of bean classes
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

        // Index the SDL fields by their Java-member binding key, descending through grouping inputs
        // (a nested input field matching no member) so their leaves are hoisted to peers of this
        // type's own fields. Two SDL fields resolving to one key is rejected before either arm
        // builds a result: on the record arm the second would silently win the bijection slot
        // (order-dependent binding); on the JavaBean arm the same setter would be invoked twice.
        var sdlByBindingKey = new LinkedHashMap<String, IndexEntry>();
        Rejection indexRejection = indexSdlFields(iot, javaMembersByName, List.of(),
            ClassifyContext.root().expanding(iot.getName()), beanClass, paramName, methodName,
            className, sdlByBindingKey);
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
    private record IndexEntry(List<String> path, GraphQLInputObjectField field) {}

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
     *   <li><b>No member matches, and {@code g} carries neither {@code @field(name:)} nor
     *       {@code @nodeId}.</b> {@code g} is a grouping input: its own fields are hoisted into this
     *       index under the access path {@code ["g", <leaf>]} and bind against the enclosing bean's
     *       members by the normal rules. Recursive, so a hoisted group descends again.</li>
     *   <li><b>No member matches, and {@code g} carries {@code @field(name:)} or {@code @nodeId}.</b>
     *       Reject. Both directives are an authored claim that the field binds to a named Java
     *       member; flattening past a claim that does not resolve would turn a typo into
     *       silently-different behaviour.</li>
     * </ol>
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
            String key = bindingKey(f);
            // A present-but-blank @field(name:) yields an empty key (GraphQL field names are never
            // empty, so only the directive can produce one). It can match no record component or
            // setter; reject the malformed directive at classify time rather than silently
            // skipping the field on the JavaBean arm.
            if (key.isEmpty()) {
                return Rejection.structural(where + ": SDL input field '" + dottedPath(path)
                    + "' on type '" + iot.getName() + "' carries @field(name:) with a blank value —"
                    + " give it the Java member name to bind (record component / JavaBean property),"
                    + " or drop the directive to bind by the field's own name");
            }
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
                    classifyCtx.expanding(nestedIot.getName()), beanClass, paramName, methodName,
                    className, out);
                if (nested != null) {
                    return nested;
                }
                continue;
            }
            IndexEntry prior = out.put(key, new IndexEntry(path, f));
            if (prior != null) {
                return Rejection.structural(where + ": SDL input fields '"
                    + dottedPath(prior.path()) + "' and '" + dottedPath(path)
                    + "' both bind to Java member '" + key + "' on bean class '" + beanClass.getName()
                    + "' (via @field(name:) or a matching name) — two input fields cannot populate one"
                    + " member; rename one field or adjust its @field(name:)");
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
        if (f.hasAppliedDirective(DIR_FIELD) || f.hasAppliedDirective(DIR_NODE_ID)) {
            String directive = f.hasAppliedDirective(DIR_FIELD) ? "@field(name:)" : "@nodeId";
            return Rejection.structural(where + ": nested input field '" + dottedPath(path)
                + "' carries " + directive + ", binding it to Java member '" + bindingKey(f)
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
                + " member named '" + bindingKey(f) + "' typed as a list of a bean mirroring '"
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
            Map<String, IndexEntry> sdlByBindingKey,
            String paramName, String methodName, String className, Set<Class<?>> visited) {
        var bindings = new ArrayList<CallSiteExtraction.FieldBinding>();
        var consumedKeys = new HashSet<String>();
        // Direction A: every component must bind. componentsByName iterates in component order.
        for (var ce : componentsByName.entrySet()) {
            String component = ce.getKey();
            IndexEntry entry = sdlByBindingKey.get(component);
            if (entry == null) {
                return new Built.Fail(Rejection.structural(
                    "parameter '" + paramName + "' on method '" + methodName + "' in class '"
                    + className + "': record '" + beanClass.getName() + "' component '" + component
                    + "' has no SDL input field bound to it on type '" + iot.getName() + "' — every"
                    + " record component must bind (the canonical constructor needs them all); add a"
                    + " field named '" + component + "' to the input type, or @field(name: \""
                    + component + "\") to the field that should populate it"));
            }
            consumedKeys.add(component);
            FieldResult r = bindField(entry.field(), entry.path(), ce.getValue(),
                paramName, methodName, className, visited);
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
     *
     * <p>That tolerance no longer extends to nested input-object fields: an unmatched one is a
     * grouping input and either flattens or rejects, decided in the index walk above before this arm
     * runs. Only an unmatched <em>scalar</em> field is still skipped in silence. The asymmetry is
     * deliberate: flattening requires looking inside a nested field, so this arm cannot stay blind
     * there, while diagnosing a dropped scalar needs machinery this arm does not have.
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
            FieldResult r = bindField(e.getValue().field(), e.getValue().path(), member,
                paramName, methodName, className, visited);
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
     * member's Java type drives the leaf branch. The binding carries the SDL access path (whose last
     * element is the {@code Map} key the helper reads) separately from the Java member name (the
     * component / property it populates), so the emitter is agnostic to <em>how</em> the member was
     * chosen and to whether the field was declared on the bean's own input type or hoisted out of a
     * grouping input. A hoisted leaf's transform is computed exactly as an unflattened one's:
     * flattening moves where the value is read from, not what is done to it.
     */
    private FieldResult bindField(GraphQLInputObjectField sdlField, List<String> accessPath,
            JavaMember member,
            String paramName, String methodName, String className, Set<Class<?>> visited) {
        // Messages name the dotted access path, so a hoisted leaf points at the SDL the author
        // wrote rather than at a bare field name that appears nowhere on the enclosing input type.
        String fieldPath = dottedPath(accessPath);
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
                    + className + "': nested field '" + fieldPath + "' has SDL input-object"
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
                    "input-bean field '" + fieldPath + "' on parameter '" + paramName + "' of method '"
                        + methodName + "' in class '" + className + "'"));
            }
            leaf = new CallSiteExtraction.EnumValueOf(javaElementTypeName);
        } else {
            // Scalar SDL field. A jOOQ-record-typed member never lands on Direct: a wire ID
            // String cast to a *Record throws ClassCastException at the first request. Branch
            // to a @nodeId-decode leaf, or reject loudly.
            Class<?> memberClass = tryLoad(javaElementTypeName);
            if (memberClass != null && isJooqRecord(memberClass)) {
                RecordLeaf recordLeaf = buildJooqRecordLeaf(sdlField, fieldPath,
                    javaElementTypeName, nonNull, paramName, methodName, className);
                if (recordLeaf instanceof RecordLeaf.Fail rf) {
                    return new FieldResult.Fail(rf.rejection());
                }
                leaf = ((RecordLeaf.Ok) recordLeaf).leaf();
            } else if (sdlField.hasAppliedDirective(DIR_NODE_ID)) {
                return new FieldResult.Fail(singleValuedMemberDeferral(fieldPath, paramName,
                    methodName, className));
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
            accessPath, member.javaName(), leaf, listShape, javaElementTypeName));
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
                // nameability: exempt (signature-derived type name, not a name anyone wrote)
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
