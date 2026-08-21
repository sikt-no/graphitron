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
 * parameter position. Sibling to {@link InputBeanInstantiationEmitter}: same "instantiate the
 * consumer's typed parameter at the fetcher boundary" goal, but on the column / identity axis rather
 * than the Java-member axis.
 *
 * <p>Driven by the distinct {@link CallSiteExtraction.JooqRecord} binding shapes collected in
 * {@link TypeFetcherGenerator}. Dedup and naming are keyed on the full binding <em>shape</em> rather
 * than the record class (via {@link JooqRecordHelperNames}): two {@code @service} fields taking one
 * record through different input shapes emit distinct helpers, while identical shapes collapse to one.
 * The helper name is resolved through the same {@link JooqRecordHelperNames} the call sites consult,
 * so a call and its helper always agree.
 *
 * <p>Helper signatures:
 * <pre>
 *   private static FilmRecord createFilmRecord(Map&lt;String, Object&gt; raw);
 *   private static List&lt;FilmRecord&gt; createFilmRecordList(Object raw);
 * </pre>
 *
 * <p>The singular helper holds the construction; the plural one is emitted alongside it
 * unconditionally and delegates per element, as {@link InputBeanInstantiationEmitter} does.
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
     * semantics split on the field's nullability; see {@link #emitKeyDecode}. A binding whose
     * path descends through a nested grouping input wraps its load in a parent-{@code Map} descent,
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

        var b = MethodSpec.methodBuilder(names.singularName(jr))
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(recordType)
            .addParameter(mapStringObject, "raw");
        // A contended helper (one of several shapes for one record class) carries a one-line
        // javadoc naming the columns it binds, so a reader maps helper → mutation without decoding the
        // ordinal. Uncontended helpers stay javadoc-free, so their output is unchanged.
        String javadoc = names.javadocFor(jr);
        if (javadoc != null) {
            b.addJavadoc("$L\n", javadoc);
        }
        b.addStatement("if (raw == null) return null")
            .addStatement("$T rec = new $T()", recordType, recordType);

        // Per-column overlap analysis. When two or more writers (plain @field columns or
        // @nodeId decodes) land on one column, agreeing writers are harmless (the sequential loads
        // last-write-wins to the same value) but disagreeing ones would silently drop a caller value.
        // Detect the overlap once and, when present, route through the agreement-checked emission.
        var writers = writersOf(jr);
        var plan = ColumnOverlap.groupByColumn(writers.stream().map(WriterView::new).toList());
        if (plan.stream().noneMatch(OverlapColumn::shared)) {
            // No overlap: byte-identical to the plain two-loop form (pay-for-what-you-use).
            for (var cb : jr.columnBindings()) {
                emitColumnBinding(b, cb, tablesClass, tableField);
            }
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
     * marked touched and written as {@code NULL} (jOOQ's {@code fromArray} has null-skip
     * semantics that would leave it unchanged); a present value goes through {@code fromArray} so it
     * coerces via the column's {@code DataType}/{@code Converter} (no deprecated {@code DataType.convert}).
     * The touched flag on a null-set column survives only until a later {@link CallSiteExtraction.RecordKeyDecode}
     * runs; see the flag-reset note on {@link #emitKeyDecode}.
     *
     * <p>For a nested binding (path depth &gt; 1) the guard is wrapped in a parent-{@code Map}
     * descent ({@link #openDescent}): an absent / null / non-{@code Map} enclosing group skips the column
     * entirely (it stays {@code changed=false}). At depth 1 no wrapping block is emitted and the output is
     * byte-identical to the non-nested form.
     *
     * <p>For a non-null ({@code !}) field the {@code containsKey} guard is vacuously true (graphql-java
     * enforces presence at the boundary), so no nullability split is needed here, unlike
     * {@link #emitKeyDecode}.
     *
     * <p>A binding carrying several read paths (a declared {@code @deprecated}-alias group the
     * classifier merged) routes through {@link #emitMultiPathRead} + {@link #emitPlainLoad} instead:
     * the paths are tried in the binding's precedence order and the first present one wins. A
     * single-path binding, the common case, keeps the inline form above so its generated output is
     * unchanged.
     */
    private static void emitColumnBinding(MethodSpec.Builder b, CallSiteExtraction.ColumnBinding cb,
            ClassName tablesClass, String tableField) {
        if (cb.paths().size() > 1) {
            String base = localBase(cb.path());
            emitMultiPathRead(b, base, cb.paths());
            emitPlainLoad(b, base, cb.column(), tablesClass, tableField);
            return;
        }
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
     * Reads a plain writer's wire value into {@code <base>Present} / {@code <base>Value}, trying
     * {@code paths} in precedence order and taking the first <em>present</em> one, where present is the
     * enclosing {@code Map}'s {@code containsKey}. Each path after the first is guarded on
     * {@code !<base>Present}, so a higher-precedence path that carried an explicit {@code null} keeps
     * the win and the column is written {@code NULL} rather than falling through to a lower-precedence
     * alias. Every path gets its own {@link #openDescent}, so aliases declared at different nesting
     * depths read independently. A one-element {@code paths} emits exactly the single-path form the
     * agreement prepare phase emitted before read paths became a list.
     */
    private static void emitMultiPathRead(MethodSpec.Builder b, String base, List<List<String>> paths) {
        b.addStatement("boolean $LPresent = false", base);
        b.addStatement("$T $LValue = null", Object.class, base);
        for (int i = 0; i < paths.size(); i++) {
            List<String> path = paths.get(i);
            if (i > 0) {
                b.beginControlFlow("if (!$LPresent)", base);
            }
            String parentMap = openDescent(b, path);
            b.beginControlFlow("if ($L.containsKey($S))", parentMap, path.get(path.size() - 1));
            b.addStatement("$LPresent = true", base);
            b.addStatement("$LValue = $L.get($S)", base, parentMap, path.get(path.size() - 1));
            b.endControlFlow();
            closeDescent(b, path);
            if (i > 0) {
                b.endControlFlow();
            }
        }
    }

    /**
     * Writes a plain writer's resolved locals onto the record: an explicit null through
     * {@code set(col, null)} (reliable changed-flag), a present value through {@code fromArray}
     * (DataType coercion), nothing at all when no read path was present (the column stays
     * {@code changed=false}). Shared by the overlap-free multi-read-path emission and the
     * agreement-checked load phase, so both write a column the same way.
     */
    private static void emitPlainLoad(MethodSpec.Builder b, String base, ColumnRef column,
            ClassName tablesClass, String tableField) {
        b.beginControlFlow("if ($LPresent)", base);
        b.beginControlFlow("if ($LValue == null)", base);
        b.addStatement("rec.set($T.$L.$L, null)", tablesClass, tableField, column.javaName());
        b.nextControlFlow("else");
        b.addStatement("rec.fromArray(new $T[]{ $LValue }, $T.$L.$L)",
            Object.class, base, tablesClass, tableField, column.javaName());
        b.endControlFlow();
        b.endControlFlow();
    }

    /**
     * Emits one {@link CallSiteExtraction.RecordKeyDecode}. A non-null ({@code ID!}) decode
     * always loads and throws on a null / wrong-arity decode. A nullable ({@code ID}) decode is
     * guarded on the wire key being present: omitted → target columns left unwritten (changed=false),
     * present-{@code null} → columns set to {@code NULL}, present-value → decoded and loaded (a
     * wrong-type decode still throws). The split is on {@code nonNull} alone, not on whether the decode
     * is a same-table identity or a cross-table FK reference — both load {@code targetColumns} the same way.
     *
     * <p>For a nested decode (path depth &gt; 1) the whole body is wrapped in a parent-{@code Map}
     * descent ({@link #openDescent}). This is what makes a non-null identity field inside an
     * <em>absent</em> nullable group skip rather than throw: the descent block is never entered, so the
     * decode throw in its body never runs. A malformed id in a <em>present</em> group still throws (the body
     * runs). At depth 1 no wrapping block is emitted and the output is byte-identical to the non-nested form.
     *
     * <p>Flag-reset side effect: the decode loads key columns via {@code Record.fromArray}, and jOOQ's
     * {@code from()} null-skip semantics reset the touched flag of every null-valued column
     * record-wide. Key decodes are emitted after the column bindings, so a running decode erases any
     * explicit-null column write made earlier in the helper (a non-null value survives). The
     * execution tier pins both halves: {@code GraphQLQueryTest}'s
     * {@code customerUpsert_explicitNullNestedLeaf_collapsesToOmitted} (decode present, NULL write
     * erased) and {@code customerUpsert_explicitNullNestedLeaf_noIdentityDecode_writesNull} (no
     * decode, NULL write survives).
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

    // ===== Column overlap analysis + agreement-checked emission =====

    /**
     * One writer of the record: a plain {@code @field} {@link CallSiteExtraction.ColumnBinding} or a
     * {@code @nodeId} {@link CallSiteExtraction.RecordKeyDecode}, paired with a collision-free local
     * name {@code base} (e.g. {@code "cb0"} / {@code "kd1"}) the prepare / agreement / load phases all
     * derive their locals from ({@code <base>Present}, {@code <base>Value} / {@code <base>Keys}).
     *
     * <p>{@link #path()} is the writer's <em>primary</em> path: a decode has exactly one, and a plain
     * binding may carry several read paths (a merged alias group), of which
     * {@link CallSiteExtraction.ColumnBinding#path()} names the highest-precedence one. Every read
     * path is honored by {@link #emitPrepare}; the primary is what names the writer in generated
     * output and in author-facing messages.
     */
    private record Writer(CallSiteExtraction.ColumnBinding plain,
                          CallSiteExtraction.RecordKeyDecode decode, String base) {
        boolean isDecode() { return decode != null; }
        List<String> path() { return plain != null ? plain.path() : decode.path(); }
    }

    /** Adapts a {@link Writer} into the shared {@link ColumnOverlap.ColumnWriter} view so the
     *  per-column overlap grouping is the {@link ColumnOverlap#groupByColumn} the DML write-path
     *  sites share. A plain field's single column or a decode's target columns (already in decode-record
     *  slot order); the label is the dotted <em>primary</em> access path, so a merged alias group names
     *  its live field in the agreement message rather than listing every alias. The emission downcasts
     *  {@code Contributor.writer()} back to this view to reach the wrapped {@link Writer} (its base
     *  local and decode shape). */
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
     * The agreement-checked emission for a record with at least one overlapping column. Reads every
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
     *
     * <p>A plain writer reads every one of its binding's read paths, first-present wins
     * ({@link #emitMultiPathRead}), so the value a merged alias group contributes to the agreement
     * check is the same first-present value it would write on its own.
     */
    private static void emitPrepare(MethodSpec.Builder b, Writer w, ClassName tablesClass, String tableField) {
        String base = w.base();
        List<String> path = w.path();
        String leaf = path.get(path.size() - 1);
        if (!w.isDecode()) {
            emitMultiPathRead(b, base, w.plain().paths());
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
                // the decode and its throw, matching emitKeyDecode).
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
     *  prepare arms, reading the {@code <base>Keys} local. */
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
            emitPlainLoad(b, base, w.plain().column(), tablesClass, tableField);
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
        // An overlap reaching the agreement check always has at least one decode, so a NodeIdEncoder
        // class is always available for the call. Not because an all-plain overlap rejects (a declared
        // @deprecated-alias group is admitted): the classifier folds every plain writer of a column
        // into one ColumnBinding, so at most one plain writer per column reaches this emitter and a
        // shared column must have gained its second writer from a decode.
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
     * Opens the parent-{@code Map} descent for a binding's access path: one
     * {@code if (<current>.get("<elem>") instanceof Map<?, ?> <local>)} block per non-leaf path element,
     * so an absent / null / non-{@code Map} ancestor short-circuits the whole per-binding body and leaves
     * the columns under it untouched. Returns the innermost {@code Map} local the body reads the leaf key
     * from ({@code "raw"} for a single-element top-level path, where no block is emitted). The caller
     * must call {@link #closeDescent} with the same path after emitting the body.
     *
     * <p>Nested present-{@code null}: graphql-java coercion retains an explicit-null field at
     * every nesting depth, on the variables and inline-literal paths alike, so a descended
     * {@code Map} distinguishes present-null from omitted exactly as the top-level argument
     * {@code Map} does and the emitted guard is the same three-way at every depth. The narrowing
     * observed in practice is not here: a key decode emitted after the column bindings can erase
     * an explicit-null column write via jOOQ's {@code from()} flag reset; see the flag-reset note
     * on {@link #emitKeyDecode}.
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
     * nested groups sharing a leaf name emit distinct locals. A single-element path yields the leaf
     * name verbatim; deeper paths camel-join the elements ({@code ["details", "title"] -> "detailsTitle"}).
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
        String pluralName = names.pluralName(jr);
        String singularName = names.singularName(jr);
        var b = MethodSpec.methodBuilder(pluralName)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(listOfRecord)
            .addParameter(Object.class, "raw");
        // Mirror the singular helper's contended javadoc so both helpers of a contended pair are
        // self-describing; uncontended helpers stay javadoc-free.
        String javadoc = names.javadocFor(jr);
        if (javadoc != null) {
            b.addJavadoc("$L\n", javadoc);
        }
        return b
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
