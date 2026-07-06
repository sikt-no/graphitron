package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnOverlap;
import no.sikt.graphitron.rewrite.model.ColumnOverlap.OverlapColumn;
import no.sikt.graphitron.rewrite.model.ColumnRef;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Emits the {@code create<Record>} / {@code create<Record>List} helper methods that construct a
 * generated jOOQ {@code TableRecord} from a GraphQL input-object {@code Map} at a {@code @service}
 * parameter position (R311). Sibling to {@link InputBeanInstantiationEmitter}: same "instantiate the
 * consumer's typed parameter at the fetcher boundary" goal, but on the column / identity axis rather
 * than the Java-member axis.
 *
 * <p>Driven by a {@link CallSiteExtraction.JooqRecord} dedup queue keyed by binding <em>shape</em>
 * (record class + ordered column bindings + ordered key decodes; see {@link JooqRecordHelperNames}),
 * collected in {@link TypeFetcherGenerator} by the same dual walk the {@code InputBean} helper queue uses
 * (the {@code MethodBackedField.callParams()} walk for the child / root-permit coordinate and the
 * {@code ServiceField}-carrier walk for the root {@code ValueShape}). Each distinct shape emits its helper
 * exactly once, and its shape-aware name (bare {@code create<Record>} for a record class with a single
 * shape, ordinal-suffixed when a class carries several) is supplied by the shared {@link JooqRecordHelperNames}
 * so the call site and the helper never drift (R437).
 *
 * <p>Helper signatures:
 * <pre>
 *   private static FilmRecord createFilmRecord(Map&lt;String, Object&gt; raw);
 *   private static List&lt;FilmRecord&gt; createFilmRecordList(Object raw);
 * </pre>
 *
 * <p>The singular helper holds the construction; the plural one is emitted alongside it
 * unconditionally and delegates per element (exactly as {@link InputBeanInstantiationEmitter} and
 * R195's {@code decode<Record>List} do). Statement form, explicit types, named locals (per "generated
 * code is read and debugged").
 */
final class JooqRecordInstantiationEmitter {

    private static final ClassName MAP = ClassName.get(Map.class);
    private static final ClassName LIST = ClassName.get(List.class);
    private static final ClassName ARRAY_LIST = ClassName.get(ArrayList.class);
    private static final ClassName GRAPHQL_ERROR = ClassName.get("graphql", "GraphqlErrorException");
    private static final String DECODE_MISMATCH_MSG =
        "Decoded NodeId did not match the expected type for this argument";

    private JooqRecordInstantiationEmitter() {}

    /**
     * Emits {@code private static <Record> create<Record>(Map<String, Object> raw)}: null in → null
     * out, otherwise a fresh record populated by a per-binding conditional load. Each plain
     * {@code @field} column and each {@code @nodeId} decode loads independently, so omitted-vs-null-vs-set
     * is honored per field (the jOOQ {@code changed}-flag contract: an omitted nullable field stays
     * {@code changed=false} and is excluded from the INSERT/UPDATE the {@code @service} runs). Null
     * semantics split on the field's nullability (R315, D4); see {@link #emitKeyDecode}. A binding whose
     * path descends through a nested grouping input (R336) wraps its load in a parent-{@code Map} descent,
     * so a nested field and a top-level field backing the same column behave identically. {@code fromArray}
     * is the supported, non-deprecated coercion path (no {@code DataType.convert(Object)}), so the helper
     * needs no {@code @SuppressWarnings}; the {@code Tables.<T>.<col>} references keep the real
     * compile-tier check that every bound column exists on the record's table.
     */
    static MethodSpec buildSingularHelper(CallSiteExtraction.JooqRecord jr, JooqRecordHelperNames names) {
        ClassName recordType = jr.table().recordClass();
        ClassName tablesClass = jr.table().constantsClass();
        String tableField = jr.table().javaFieldName();
        TypeName mapStringObject = ParameterizedTypeName.get(MAP,
            ClassName.get(String.class), ClassName.get(Object.class));

        var b = MethodSpec.methodBuilder(names.singular(jr))
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(recordType)
            .addParameter(mapStringObject, "raw")
            .addStatement("if (raw == null) return null")
            .addStatement("$T rec = new $T()", recordType, recordType);

        // D1 (R322): per-column overlap analysis. When two or more writers (plain @field columns or
        // @nodeId decodes) land on one column, agreeing writers are harmless (the sequential loads
        // last-write-wins to the same value) but disagreeing ones would silently drop a caller value.
        // Detect the overlap once and, when present, route through the agreement-checked emission.
        var writers = writersOf(jr);
        var plan = ColumnOverlap.groupByColumn(writers.stream().map(WriterView::new).toList());
        if (plan.stream().noneMatch(OverlapColumn::shared)) {
            // No overlap: byte-identical to the pre-R322 two-loop form (pay-for-what-you-use).
            // Plain @field columns: each loaded only when its wire key is present, so an omitted
            // nullable column stays changed=false (excluded from INSERT/UPDATE). A non-null (`!`) field
            // is always present (graphql-java enforces it at the boundary), so the guard is always-true.
            for (var cb : jr.columnBindings()) {
                emitColumnBinding(b, cb, tablesClass, tableField);
            }
            // @nodeId decodes (identity or FK reference): decode the wire NodeId, then load the decoded
            // values into the decode's resolved target columns. Null semantics split on nullability (D4),
            // applied identically to identity and FK-reference decodes.
            for (var kd : jr.keyDecodes()) {
                emitKeyDecode(b, kd, tablesClass, tableField);
            }
        } else {
            emitWithAgreement(b, writers, plan, tablesClass, tableField);
        }

        return b.addStatement("return rec").build();
    }

    /**
     * Emits one plain {@code @field} {@link CallSiteExtraction.ColumnBinding}, guarded on the wire key
     * being present. An explicit {@code null} is loaded via {@code set(field, null)} so the column is
     * reliably marked changed and written as {@code NULL} (jOOQ's {@code fromArray} has null-skip
     * semantics that would leave it unchanged); a present value goes through {@code fromArray} so it
     * coerces via the column's {@code DataType}/{@code Converter} (no deprecated {@code DataType.convert}).
     *
     * <p>For a nested binding (path depth &gt; 1, R336) the guard is wrapped in a parent-{@code Map}
     * descent ({@link #openDescent}): an absent / null / non-{@code Map} enclosing group skips the column
     * entirely (it stays {@code changed=false}). At depth 1 no wrapping block is emitted and the output is
     * byte-identical to the pre-R336 form.
     */
    private static void emitColumnBinding(MethodSpec.Builder b, CallSiteExtraction.ColumnBinding cb,
            ClassName tablesClass, String tableField) {
        String parentMap = openDescent(b, cb.path());
        String leaf = cb.leaf();
        String valVar = localBase(cb.path()) + "Value";
        b.beginControlFlow("if ($L.containsKey($S))", parentMap, leaf);
        b.addStatement("$T $L = $L.get($S)", Object.class, valVar, parentMap, leaf);
        b.beginControlFlow("if ($L == null)", valVar);
        b.addStatement("rec.set($T.$L.$L, null)", tablesClass, tableField, cb.column().javaName());
        b.nextControlFlow("else");
        b.addStatement("rec.fromArray(new $T[]{ $L }, $T.$L.$L)",
            Object.class, valVar, tablesClass, tableField, cb.column().javaName());
        b.endControlFlow();
        b.endControlFlow();
        closeDescent(b, cb.path());
    }

    /**
     * Emits one {@link CallSiteExtraction.RecordKeyDecode} (R315, D4). A non-null ({@code ID!}) decode
     * always loads and throws on a null / wrong-arity decode (R195). A nullable ({@code ID}) decode is
     * guarded on the wire key being present: omitted → target columns left unwritten (changed=false),
     * present-{@code null} → columns set to {@code NULL}, present-value → decoded and loaded (a
     * wrong-type decode still throws). The split is on {@code nonNull} alone, not on whether the decode
     * is a same-table identity or a cross-table FK reference — both load {@code targetColumns} the same way.
     *
     * <p>For a nested decode (path depth &gt; 1, R336) the whole body is wrapped in a parent-{@code Map}
     * descent ({@link #openDescent}). This is what makes a non-null identity field inside an
     * <em>absent</em> nullable group skip rather than throw: the descent block is never entered, so the
     * R195 throw in its body never runs. A malformed id in a <em>present</em> group still throws (the body
     * runs). At depth 1 no wrapping block is emitted and the output is byte-identical to the pre-R336 form.
     */
    private static void emitKeyDecode(MethodSpec.Builder b, CallSiteExtraction.RecordKeyDecode kd,
            ClassName tablesClass, String tableField) {
        int arity = kd.targetColumns().size();
        CodeBlock cols = targetColumnsExpr(kd, tablesClass, tableField);
        String base = localBase(kd.path());
        String leaf = kd.leaf();
        String keyVar = base + "Keys";
        String parentMap = openDescent(b, kd.path());
        if (kd.nonNull()) {
            b.addStatement("$T $L = $T.decodeValues($S, ($T) $L.get($S))",
                String[].class, keyVar, kd.encoderClass(), kd.typeId(), String.class, parentMap, leaf);
            b.beginControlFlow("if ($L == null || $L.length != $L)", keyVar, keyVar, arity)
                .addStatement("throw $T.newErrorException().message($S).build()", GRAPHQL_ERROR, DECODE_MISMATCH_MSG)
                .endControlFlow();
            b.addStatement("rec.fromArray($L, $L)", keyVar, cols);
            closeDescent(b, kd.path());
            return;
        }
        String rawVar = base + "Raw";
        b.beginControlFlow("if ($L.containsKey($S))", parentMap, leaf);
        b.addStatement("$T $L = $L.get($S)", Object.class, rawVar, parentMap, leaf);
        b.beginControlFlow("if ($L == null)", rawVar);
        // Present null → set each target column to NULL. set() (not fromArray) so the column is reliably
        // marked changed regardless of type; fromArray's null-skip semantics would leave it unchanged.
        for (var col : kd.targetColumns()) {
            b.addStatement("rec.set($T.$L.$L, null)", tablesClass, tableField, col.javaName());
        }
        b.nextControlFlow("else");
        b.addStatement("$T $L = $T.decodeValues($S, ($T) $L)",
            String[].class, keyVar, kd.encoderClass(), kd.typeId(), String.class, rawVar);
        b.beginControlFlow("if ($L == null || $L.length != $L)", keyVar, keyVar, arity)
            .addStatement("throw $T.newErrorException().message($S).build()", GRAPHQL_ERROR, DECODE_MISMATCH_MSG)
            .endControlFlow();
        b.addStatement("rec.fromArray($L, $L)", keyVar, cols);
        b.endControlFlow();
        b.endControlFlow();
        closeDescent(b, kd.path());
    }

    // ===== R322: column overlap analysis (D1) + agreement-checked emission (D4) =====

    /**
     * One writer of the record: a plain {@code @field} {@link CallSiteExtraction.ColumnBinding} or a
     * {@code @nodeId} {@link CallSiteExtraction.RecordKeyDecode}, paired with a collision-free local
     * name {@code base} (e.g. {@code "cb0"} / {@code "kd1"}) the prepare / agreement / load phases all
     * derive their locals from ({@code <base>Present}, {@code <base>Value} / {@code <base>Keys}).
     */
    private record Writer(CallSiteExtraction.ColumnBinding plain,
                          CallSiteExtraction.RecordKeyDecode decode, String base) {
        boolean isDecode() { return decode != null; }
        List<String> path() { return plain != null ? plain.path() : decode.path(); }
    }

    /** R356: adapts a {@link Writer} into the shared {@link ColumnOverlap.ColumnWriter} view so the
     *  per-column overlap grouping is the one {@link ColumnOverlap#groupByColumn} the six DML write-path
     *  sites share. A plain field's single column or a decode's target columns (already in decode-record
     *  slot order); the label is the dotted access path. The emission downcasts {@code Contributor.writer()}
     *  back to this view to reach the wrapped {@link Writer} (its base local and decode shape). */
    private record WriterView(Writer w) implements ColumnOverlap.ColumnWriter {
        @Override public List<ColumnRef> targetColumns() {
            return w.isDecode() ? w.decode().targetColumns() : List.of(w.plain().column());
        }
        @Override public boolean decode() { return w.isDecode(); }
        @Override public String label() { return dottedPath(w.path()); }
    }

    /** The record's writers in emission order: plain {@code @field} columns first, then {@code @nodeId}
     *  decodes, each tagged with a collision-free local-name base. */
    private static List<Writer> writersOf(CallSiteExtraction.JooqRecord jr) {
        var out = new ArrayList<Writer>();
        int i = 0;
        for (var cb : jr.columnBindings()) {
            out.add(new Writer(cb, null, "cb" + (i++)));
        }
        int j = 0;
        for (var kd : jr.keyDecodes()) {
            out.add(new Writer(null, kd, "kd" + (j++)));
        }
        return out;
    }

    /**
     * D4: the agreement-checked emission for a record with at least one overlapping column. Reads every
     * writer's wire value(s) into named locals (prepare), checks the present writers on each overlapping
     * column agree (agreement), then loads every present writer onto the record (load). Because the loads
     * last-write-wins to the same value once agreement holds, load order is immaterial; building all
     * writers' locals up front is what makes the plain-field value and the decode {@code String[]} slot
     * co-available for the per-column check. The shared {@link ColumnOverlap} plan keeps every column
     * (size-one included); the agreement check fires only on the {@code shared()} ones.
     */
    private static void emitWithAgreement(MethodSpec.Builder b, List<Writer> writers,
            List<OverlapColumn> plan, ClassName tablesClass, String tableField) {
        for (var w : writers) {
            emitPrepare(b, w, tablesClass, tableField);
        }
        int ci = 0;
        for (var oc : plan) {
            if (oc.shared()) {
                emitAgreement(b, oc, tablesClass, tableField, "agree" + (ci++));
            }
        }
        for (var w : writers) {
            emitLoadPrepared(b, w, tablesClass, tableField);
        }
    }

    /**
     * Prepare phase: declares {@code <base>Present} plus {@code <base>Value} (plain) or {@code <base>Keys}
     * (decode), set by reading / decoding the wire value under the writer's access-path descent. Mirrors
     * {@link #emitColumnBinding} / {@link #emitKeyDecode} read semantics exactly (the same nesting descent,
     * the same null / arity throw on a malformed id, the same nullable three-way), but stores the result in
     * locals instead of loading the record, so the values are available for the agreement check.
     */
    private static void emitPrepare(MethodSpec.Builder b, Writer w, ClassName tablesClass, String tableField) {
        String base = w.base();
        List<String> path = w.path();
        String leaf = path.get(path.size() - 1);
        if (!w.isDecode()) {
            b.addStatement("boolean $LPresent = false", base);
            b.addStatement("$T $LValue = null", Object.class, base);
            String parentMap = openDescent(b, path);
            b.beginControlFlow("if ($L.containsKey($S))", parentMap, leaf);
            b.addStatement("$LPresent = true", base);
            b.addStatement("$LValue = $L.get($S)", base, parentMap, leaf);
            b.endControlFlow();
            closeDescent(b, path);
            return;
        }
        var kd = w.decode();
        int arity = kd.targetColumns().size();
        if (kd.nonNull()) {
            if (path.size() == 1) {
                // Top-level non-null id is always present (graphql-java enforces ID! at the boundary).
                b.addStatement("boolean $LPresent = true", base);
                b.addStatement("$T $LKeys = $T.decodeValues($S, ($T) raw.get($S))",
                    String[].class, base, kd.encoderClass(), kd.typeId(), String.class, leaf);
                emitArityThrow(b, base, arity);
            } else {
                // Nested non-null id: present iff the enclosing group is present (an absent group skips
                // the decode and its R195 throw, matching emitKeyDecode).
                b.addStatement("boolean $LPresent = false", base);
                b.addStatement("$T $LKeys = null", String[].class, base);
                String parentMap = openDescent(b, path);
                b.addStatement("$LPresent = true", base);
                b.addStatement("$LKeys = $T.decodeValues($S, ($T) $L.get($S))",
                    base, kd.encoderClass(), kd.typeId(), String.class, parentMap, leaf);
                emitArityThrow(b, base, arity);
                closeDescent(b, path);
            }
            return;
        }
        // Nullable id: omitted → absent; present-null → present with a null value; present-value → decoded.
        b.addStatement("boolean $LPresent = false", base);
        b.addStatement("boolean $LPresentNull = false", base);
        b.addStatement("$T $LKeys = null", String[].class, base);
        String parentMap = openDescent(b, path);
        b.beginControlFlow("if ($L.containsKey($S))", parentMap, leaf);
        b.addStatement("$LPresent = true", base);
        b.addStatement("$T $LRaw = $L.get($S)", Object.class, base, parentMap, leaf);
        b.beginControlFlow("if ($LRaw == null)", base);
        b.addStatement("$LPresentNull = true", base);
        b.nextControlFlow("else");
        b.addStatement("$LKeys = $T.decodeValues($S, ($T) $LRaw)",
            base, kd.encoderClass(), kd.typeId(), String.class, base);
        emitArityThrow(b, base, arity);
        b.endControlFlow();
        b.endControlFlow();
        closeDescent(b, path);
    }

    /** The {@code if (keys == null || keys.length != arity) throw …} arity guard shared by the decode
     *  prepare arms (R195), reading the {@code <base>Keys} local. */
    private static void emitArityThrow(MethodSpec.Builder b, String base, int arity) {
        b.beginControlFlow("if ($LKeys == null || $LKeys.length != $L)", base, base, arity)
            .addStatement("throw $T.newErrorException().message($S).build()", GRAPHQL_ERROR, DECODE_MISMATCH_MSG)
            .endControlFlow();
    }

    /**
     * Load phase: writes a prepared writer onto the record from its locals, guarded on {@code <base>Present}.
     * Same record-write semantics as {@link #emitColumnBinding} / {@link #emitKeyDecode}: an explicit null
     * goes through {@code set(col, null)} (reliable changed-flag), a present value through {@code fromArray}
     * (DataType coercion), and a nullable decode's present-null sets each target column to NULL.
     */
    private static void emitLoadPrepared(MethodSpec.Builder b, Writer w, ClassName tablesClass, String tableField) {
        String base = w.base();
        if (!w.isDecode()) {
            var col = w.plain().column();
            b.beginControlFlow("if ($LPresent)", base);
            b.beginControlFlow("if ($LValue == null)", base);
            b.addStatement("rec.set($T.$L.$L, null)", tablesClass, tableField, col.javaName());
            b.nextControlFlow("else");
            b.addStatement("rec.fromArray(new $T[]{ $LValue }, $T.$L.$L)",
                Object.class, base, tablesClass, tableField, col.javaName());
            b.endControlFlow();
            b.endControlFlow();
            return;
        }
        var kd = w.decode();
        CodeBlock cols = targetColumnsExpr(kd, tablesClass, tableField);
        b.beginControlFlow("if ($LPresent)", base);
        if (kd.nonNull()) {
            b.addStatement("rec.fromArray($LKeys, $L)", base, cols);
        } else {
            b.beginControlFlow("if ($LPresentNull)", base);
            for (var col : kd.targetColumns()) {
                b.addStatement("rec.set($T.$L.$L, null)", tablesClass, tableField, col.javaName());
            }
            b.nextControlFlow("else");
            b.addStatement("rec.fromArray($LKeys, $L)", base, cols);
            b.endControlFlow();
        }
        b.endControlFlow();
    }

    /**
     * Agreement phase for one overlapping column: gathers the present writers' values into a list, then
     * pairwise-checks them against the first present value through {@link #GRAPHQL_ERROR}-throwing
     * {@code requireColumnAgreement} (coerced via the column's {@code DataType}). Presence-guarded: only
     * actually-present writers enter the list, so an omitted nullable writer cannot conflict, and the check
     * fires only when two or more are present. The label names the conflicting SDL fields, not the column.
     */
    private static void emitAgreement(MethodSpec.Builder b, OverlapColumn oc,
            ClassName tablesClass, String tableField, String listName) {
        String label = "input fields " + oc.contributors().stream()
            .map(c -> "'" + c.writer().label() + "'")
            .distinct()
            .collect(Collectors.joining(", "));
        // An overlap reaching the agreement check always has at least one decode (the all-plain overlap
        // is the build-time reject), so a NodeIdEncoder class is always available for the call.
        ClassName encoderClass = oc.contributors().stream()
            .map(c -> ((WriterView) c.writer()).w())
            .filter(Writer::isDecode)
            .map(w -> w.decode().encoderClass())
            .findFirst().orElseThrow();
        ColumnRef col = oc.column();
        b.addStatement("$T<$T> $L = new $T<>()", LIST, Object.class, listName, ARRAY_LIST);
        for (var c : oc.contributors()) {
            Writer w = ((WriterView) c.writer()).w();
            b.beginControlFlow("if ($LPresent)", w.base());
            b.addStatement("$L.add($L)", listName, agreeValueExpr(w, c.slot()));
            b.endControlFlow();
        }
        String idx = listName + "Idx";
        b.beginControlFlow("for (int $L = 1; $L < $L.size(); $L++)", idx, idx, listName, idx)
            .addStatement("$T.requireColumnAgreement($S, $T.$L.$L.getDataType(), $L.get(0), $L.get($L))",
                encoderClass, label, tablesClass, tableField, col.javaName(), listName, listName, idx)
            .endControlFlow();
    }

    /** The value a writer contributes to a shared column, read from its prepared locals: a plain field's
     *  raw value, a non-null decode's slot, or a nullable decode's null (present-null) / slot. */
    private static CodeBlock agreeValueExpr(Writer w, int slot) {
        String base = w.base();
        if (!w.isDecode()) {
            return CodeBlock.of("$LValue", base);
        }
        if (w.decode().nonNull()) {
            return CodeBlock.of("$LKeys[$L]", base, slot);
        }
        return CodeBlock.of("$LPresentNull ? null : $LKeys[$L]", base, base, slot);
    }

    /** Renders an access path as a dotted SDL field reference (e.g. {@code details.title}) for the
     *  agreement label, matching the {@code InputBeanResolver} reject-message form. */
    private static String dottedPath(List<String> path) {
        return String.join(".", path);
    }

    /**
     * Opens the parent-{@code Map} descent for a binding's access path (D4): one
     * {@code if (<current>.get("<elem>") instanceof Map<?, ?> <local>)} block per non-leaf path element,
     * so an absent / null / non-{@code Map} ancestor short-circuits the whole per-binding body and leaves
     * the columns under it untouched. Returns the innermost {@code Map} local the body reads the leaf key
     * from — {@code "raw"} for a single-element (top-level) path, where no block is emitted. The descent
     * reuses the {@code instanceof Map<?, ?>} chain idiom from {@code ArgCallEmitter}, generalised here to
     * the statement form for paths of any depth. The caller must call {@link #closeDescent} with the same
     * path after emitting the body.
     *
     * <p>graphql-java constraint on nested present-{@code null}: unlike the top-level argument {@code Map}
     * (which retains an explicit-null field, so {@code raw.containsKey(leaf)} distinguishes present-null
     * from omitted), graphql-java's coercion <em>drops</em> an explicit-null field from a nested
     * input-object value. So inside a descended {@code Map} a present-null leaf is indistinguishable from
     * an omitted one — {@code containsKey} is false either way — and both leave the column untouched. The
     * top-level present-null → {@code NULL} three-way therefore narrows to a nested two-way; this is a
     * graphql-java coercion limitation, not a choice in the emitted code, and it is consistent with the
     * sibling rule that a null nested <em>group</em> is treated as absent.
     */
    private static String openDescent(MethodSpec.Builder b, List<String> path) {
        String current = "raw";
        for (int i = 0; i < path.size() - 1; i++) {
            String mapLocal = mapLocalName(path, i);
            b.beginControlFlow("if ($L.get($S) instanceof $T<?, ?> $L)",
                current, path.get(i), Map.class, mapLocal);
            current = mapLocal;
        }
        return current;
    }

    /** Closes the {@link #openDescent} blocks: one {@code endControlFlow} per non-leaf path element. */
    private static void closeDescent(MethodSpec.Builder b, List<String> path) {
        for (int i = 0; i < path.size() - 1; i++) {
            b.endControlFlow();
        }
    }

    /**
     * The collision-free local-name base for a binding, derived from the <em>full</em> access path so two
     * nested groups sharing a leaf name emit distinct locals (D4). A single-element path yields the leaf
     * name verbatim (byte-identical to the pre-R336 {@code sdlFieldName}-derived locals); deeper paths
     * camel-join the elements ({@code ["details", "title"] -> "detailsTitle"}).
     */
    private static String localBase(List<String> path) {
        return camelJoin(path);
    }

    /**
     * The descent local for the parent {@code Map} bound at path level {@code levelInclusive}: the
     * camel-joined cumulative path + {@code "Map"} (e.g. {@code ["a", "b", leaf]} at level 1 → {@code "aBMap"}).
     */
    private static String mapLocalName(List<String> path, int levelInclusive) {
        return camelJoin(path.subList(0, levelInclusive + 1)) + "Map";
    }

    /** Camel-joins path elements: the first verbatim, each subsequent capitalised and concatenated. */
    private static String camelJoin(List<String> parts) {
        StringBuilder sb = new StringBuilder(parts.get(0));
        for (int i = 1; i < parts.size(); i++) {
            String p = parts.get(i);
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    /** The {@code Tables.<T>.<col>, …} field-reference list for a decode's resolved target columns. */
    private static CodeBlock targetColumnsExpr(CallSiteExtraction.RecordKeyDecode kd,
            ClassName tablesClass, String tableField) {
        CodeBlock.Builder cols = CodeBlock.builder();
        var targetColumns = kd.targetColumns();
        for (int i = 0; i < targetColumns.size(); i++) {
            if (i > 0) cols.add(", ");
            cols.add("$T.$L.$L", tablesClass, tableField, targetColumns.get(i).javaName());
        }
        return cols.build();
    }

    /**
     * Emits {@code private static List<<Record>> create<Record>List(Object raw)}: null in → null out,
     * otherwise downcast the {@code Object} to {@code List<?>} (the wire value for a {@code [Input!]}
     * arg is a {@code List<Map<String, Object>>}), reject null elements, and map each through the
     * singular helper. Burying the {@code List<Map>} downcast here keeps the call site cast-free; the
     * per-element unchecked cast carries the same {@code @SuppressWarnings("unchecked")} the
     * {@code InputBean} plural helper does.
     */
    static MethodSpec buildPluralHelper(CallSiteExtraction.JooqRecord jr, JooqRecordHelperNames names) {
        ClassName recordType = jr.table().recordClass();
        TypeName listOfRecord = ParameterizedTypeName.get(LIST, recordType);
        String pluralName = names.plural(jr);
        String singularName = names.singular(jr);
        return MethodSpec.methodBuilder(pluralName)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(listOfRecord)
            .addParameter(Object.class, "raw")
            .addStatement("if (raw == null) return null")
            .addStatement("$T<?> list = ($T<?>) raw", LIST, LIST)
            .addStatement("return list.stream().map(e -> {\n"
                + "  if (e == null) throw new IllegalArgumentException(\"$L: null element not allowed in list argument\");\n"
                + "  @SuppressWarnings(\"unchecked\")\n"
                + "  $T<$T, $T> m = ($T<$T, $T>) e;\n"
                + "  return $L(m);\n"
                + "}).toList()",
                pluralName,
                MAP, ClassName.get(String.class), ClassName.get(Object.class),
                MAP, ClassName.get(String.class), ClassName.get(Object.class),
                singularName)
            .build();
    }
}
