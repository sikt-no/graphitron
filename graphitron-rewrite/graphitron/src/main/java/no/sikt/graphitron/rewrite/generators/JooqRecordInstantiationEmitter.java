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

    private JooqRecordInstantiationEmitter() {}

    /**
     * Emits {@code private static <Record> create<Record>(Map<String, Object> raw)}: null in → null
     * out, otherwise a fresh record loaded in (at most) two disjoint {@code fromArray} groups — the
     * plain {@code @field} columns (values straight off the wire {@code Map}, each coerced through its
     * column's {@code DataType}/{@code Converter}) and the {@code @nodeId} identity key columns (decoded
     * via R195's wire mechanism, then loaded). Either group may be absent. {@code fromArray} is the
     * supported, non-deprecated coercion path (no {@code DataType.convert(Object)}), so the helper needs
     * no {@code @SuppressWarnings}; the {@code Tables.<T>.<col>} references keep the real compile-tier
     * check that every bound column exists on the record's table.
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

        // Plain @field columns: batched through fromArray so each value coerces via its column's
        // DataType/Converter (no deprecated DataType.convert(Object)).
        if (!jr.columnBindings().isEmpty()) {
            CodeBlock.Builder values = CodeBlock.builder();
            CodeBlock.Builder cols = CodeBlock.builder();
            boolean first = true;
            for (var cb : jr.columnBindings()) {
                if (!first) {
                    values.add(", ");
                    cols.add(", ");
                }
                first = false;
                values.add("raw.get($S)", cb.sdlFieldName());
                cols.add("$T.$L.$L", tablesClass, tableField, cb.column().javaName());
            }
            b.addStatement("rec.fromArray(new $T[]{ $L }, $L)",
                Object.class, values.build(), cols.build());
        }

        // Identity (@nodeId) key columns: decode the wire NodeId, then load positionally. A null or
        // type-mismatched id makes decodeValues return null (or a wrong arity), which throws — the
        // record's identity always decodes the key that is the record, regardless of SDL nullability.
        if (jr.keyDecode().isPresent()) {
            var kd = jr.keyDecode().get();
            int arity = kd.keyColumns().size();
            b.addStatement("$T keyValues = $T.decodeValues($S, ($T) raw.get($S))",
                String[].class, kd.encoderClass(), kd.typeId(), String.class, kd.sdlFieldName());
            b.beginControlFlow("if (keyValues == null || keyValues.length != $L)", arity)
                .addStatement("throw $T.newErrorException().message($S).build()", GRAPHQL_ERROR,
                    "Decoded NodeId did not match the expected type for this argument")
                .endControlFlow();
            CodeBlock.Builder keyCols = CodeBlock.builder();
            for (int i = 0; i < arity; i++) {
                if (i > 0) keyCols.add(", ");
                keyCols.add("$T.$L.$L", tablesClass, tableField, kd.keyColumns().get(i).javaName());
            }
            b.addStatement("rec.fromArray(keyValues, $L)", keyCols.build());
        }

        return b.addStatement("return rec").build();
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
