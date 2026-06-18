package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Map;

/**
 * Emits the {@code create<Record>} / {@code create<Record>List} helper methods that construct a
 * generated jOOQ {@code TableRecord} from a GraphQL input-object {@code Map} at a {@code @service}
 * parameter position (R311). Sibling to {@link InputBeanInstantiationEmitter}: same "instantiate the
 * consumer's typed parameter at the fetcher boundary" goal, but on the column / identity axis rather
 * than the Java-member axis.
 *
 * <p>Driven by a {@link CallSiteExtraction.JooqRecord} dedup queue keyed by record class, collected in
 * {@link TypeFetcherGenerator} by the same dual walk the {@code InputBean} helper queue uses (the
 * {@code MethodBackedField.callParams()} walk for the child / root-permit coordinate and the
 * {@code ServiceField}-carrier walk for the root {@code ValueShape}). A record reached by either
 * coordinate emits its helper exactly once.
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
     * semantics split on the field's nullability (R315, D4); see {@link #emitKeyDecode}. {@code fromArray}
     * is the supported, non-deprecated coercion path (no {@code DataType.convert(Object)}), so the helper
     * needs no {@code @SuppressWarnings}; the {@code Tables.<T>.<col>} references keep the real
     * compile-tier check that every bound column exists on the record's table.
     */
    static MethodSpec buildSingularHelper(CallSiteExtraction.JooqRecord jr) {
        ClassName recordType = jr.table().recordClass();
        ClassName tablesClass = jr.table().constantsClass();
        String tableField = jr.table().javaFieldName();
        TypeName mapStringObject = ParameterizedTypeName.get(MAP,
            ClassName.get(String.class), ClassName.get(Object.class));

        var b = MethodSpec.methodBuilder(singularName(recordType))
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(recordType)
            .addParameter(mapStringObject, "raw")
            .addStatement("if (raw == null) return null")
            .addStatement("$T rec = new $T()", recordType, recordType);

        // Plain @field columns: each loaded only when its wire key is present, so an omitted nullable
        // column stays changed=false (excluded from INSERT/UPDATE). A non-null (`!`) field is always
        // present (graphql-java enforces it at the boundary), so the guard is always-true for it.
        for (var cb : jr.columnBindings()) {
            emitColumnBinding(b, cb, tablesClass, tableField);
        }

        // @nodeId decodes (identity or FK reference): decode the wire NodeId, then load the decoded
        // values into the decode's resolved target columns. Null semantics split on nullability (D4),
        // applied identically to identity and FK-reference decodes.
        for (var kd : jr.keyDecodes()) {
            emitKeyDecode(b, kd, tablesClass, tableField);
        }

        return b.addStatement("return rec").build();
    }

    /**
     * Emits one plain {@code @field} {@link CallSiteExtraction.ColumnBinding}, guarded on the wire key
     * being present. An explicit {@code null} is loaded via {@code set(field, null)} so the column is
     * reliably marked changed and written as {@code NULL} (jOOQ's {@code fromArray} has null-skip
     * semantics that would leave it unchanged); a present value goes through {@code fromArray} so it
     * coerces via the column's {@code DataType}/{@code Converter} (no deprecated {@code DataType.convert}).
     */
    private static void emitColumnBinding(MethodSpec.Builder b, CallSiteExtraction.ColumnBinding cb,
            ClassName tablesClass, String tableField) {
        String valVar = cb.sdlFieldName() + "Value";
        b.beginControlFlow("if (raw.containsKey($S))", cb.sdlFieldName());
        b.addStatement("$T $L = raw.get($S)", Object.class, valVar, cb.sdlFieldName());
        b.beginControlFlow("if ($L == null)", valVar);
        b.addStatement("rec.set($T.$L.$L, null)", tablesClass, tableField, cb.column().javaName());
        b.nextControlFlow("else");
        b.addStatement("rec.fromArray(new $T[]{ $L }, $T.$L.$L)",
            Object.class, valVar, tablesClass, tableField, cb.column().javaName());
        b.endControlFlow();
        b.endControlFlow();
    }

    /**
     * Emits one {@link CallSiteExtraction.RecordKeyDecode} (R315, D4). A non-null ({@code ID!}) decode
     * always loads and throws on a null / wrong-arity decode (R195). A nullable ({@code ID}) decode is
     * guarded on the wire key being present: omitted → target columns left unwritten (changed=false),
     * present-{@code null} → columns set to {@code NULL}, present-value → decoded and loaded (a
     * wrong-type decode still throws). The split is on {@code nonNull} alone, not on whether the decode
     * is a same-table identity or a cross-table FK reference — both load {@code targetColumns} the same way.
     */
    private static void emitKeyDecode(MethodSpec.Builder b, CallSiteExtraction.RecordKeyDecode kd,
            ClassName tablesClass, String tableField) {
        int arity = kd.targetColumns().size();
        CodeBlock cols = targetColumnsExpr(kd, tablesClass, tableField);
        String keyVar = kd.sdlFieldName() + "Keys";
        if (kd.nonNull()) {
            b.addStatement("$T $L = $T.decodeValues($S, ($T) raw.get($S))",
                String[].class, keyVar, kd.encoderClass(), kd.typeId(), String.class, kd.sdlFieldName());
            b.beginControlFlow("if ($L == null || $L.length != $L)", keyVar, keyVar, arity)
                .addStatement("throw $T.newErrorException().message($S).build()", GRAPHQL_ERROR, DECODE_MISMATCH_MSG)
                .endControlFlow();
            b.addStatement("rec.fromArray($L, $L)", keyVar, cols);
            return;
        }
        String rawVar = kd.sdlFieldName() + "Raw";
        b.beginControlFlow("if (raw.containsKey($S))", kd.sdlFieldName());
        b.addStatement("$T $L = raw.get($S)", Object.class, rawVar, kd.sdlFieldName());
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
    static MethodSpec buildPluralHelper(CallSiteExtraction.JooqRecord jr) {
        ClassName recordType = jr.table().recordClass();
        TypeName listOfRecord = ParameterizedTypeName.get(LIST, recordType);
        String pluralName = pluralName(recordType);
        String singularName = singularName(recordType);
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

    private static String singularName(ClassName recordType) {
        return "create" + recordType.simpleName();
    }

    private static String pluralName(ClassName recordType) {
        return "create" + recordType.simpleName() + "List";
    }
}
