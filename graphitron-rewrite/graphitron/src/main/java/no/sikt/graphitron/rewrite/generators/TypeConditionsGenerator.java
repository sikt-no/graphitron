package no.sikt.graphitron.rewrite.generators;


import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.generators.util.NodeIdEncoderClassGenerator;
import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.LookupField;
import no.sikt.graphitron.rewrite.model.SqlGeneratingField;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.*;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * Generates one {@code <TypeName>Conditions.java} per type that has fields with a
 * {@link GeneratedConditionFilter}.
 *
 * <p>Each condition method is a pure function: it takes the jOOQ table alias and typed argument
 * values, and returns a {@code Condition}. No dependency on graphql-java runtime types.
 *
 * <p>Static {@code Map<String,String>} lookup fields for text-enum arguments are generated on the
 * same class, driven by {@link CallSiteExtraction.TextMapLookup} entries in each
 * {@link BodyParam}.
 */
public class TypeConditionsGenerator {

    // CONDITION and DSL come from GeneratorUtils via static import.

    public static List<TypeSpec> generate(GraphitronSchema schema, String outputPackage, String jooqPackage) {
        // Collect GeneratedConditionFilters grouped by their conditions class name
        var filtersByClass = new LinkedHashMap<String, List<GeneratedConditionFilter>>();
        for (var type : schema.types().values()) {
            for (var field : schema.fieldsOf(type.name())) {
                extractGeneratedConditionFilter(field).ifPresent(gcf ->
                    filtersByClass
                        .computeIfAbsent(gcf.className(), k -> new ArrayList<>())
                        .add(gcf));
            }
        }

        return filtersByClass.entrySet().stream()
            .sorted(Comparator.comparing(e -> e.getKey()))
            .map(e -> generateConditionsClass(e.getKey(), e.getValue(), outputPackage, jooqPackage))
            .toList();
    }

    private static Optional<GeneratedConditionFilter> extractGeneratedConditionFilter(GraphitronField field) {
        // LookupField variants have their lookup-key args emitted via VALUES + JOIN by
        // LookupValuesJoinEmitter; they do not need a generated condition method. This explicit
        // skip self-documents the decoupling introduced in docs/argument-resolution.md Phase 1.
        // Note: a lookup field with a mixed non-lookup-key column filter is not yet supported
        // (no such schema exists today); that case would need to emit the non-key filter here.
        if (field instanceof LookupField) return Optional.empty();
        if (!(field instanceof SqlGeneratingField sgf)) return Optional.empty();
        return sgf.filters().stream()
            .filter(f -> f instanceof GeneratedConditionFilter)
            .map(f -> (GeneratedConditionFilter) f)
            .findFirst();
    }

    private static TypeSpec generateConditionsClass(String fqClassName, List<GeneratedConditionFilter> filters,
                                                    String outputPackage, String jooqPackage) {
        // Class simple name is the last segment of the fully qualified name
        String simpleName = fqClassName.substring(fqClassName.lastIndexOf('.') + 1);
        var builder = TypeSpec.classBuilder(simpleName)
            .addModifiers(Modifier.PUBLIC);

        for (var gcf : filters) {
            builder.addMethod(buildConditionMethod(gcf, outputPackage, jooqPackage));
            for (var bp : gcf.bodyParams()) {
                if (bp.extraction() instanceof CallSiteExtraction.TextMapLookup tl) {
                    builder.addField(buildTextEnumMapField(tl));
                }
            }
        }

        return builder.build();
    }

    static MethodSpec buildConditionMethod(GeneratedConditionFilter gcf, String outputPackage, String jooqPackage) {
        var tableRef = gcf.tableRef();
        var jooqTableClass = GeneratorUtils.ResolvedTableNames.ofTable(tableRef, jooqPackage).jooqTableClass();
        var nodeIdEncoder = ClassName.get(outputPackage + ".util", NodeIdEncoderClassGenerator.CLASS_NAME);

        var builder = MethodSpec.methodBuilder(gcf.methodName())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(CONDITION)
            .addParameter(jooqTableClass, "table");

        for (var bp : gcf.bodyParams()) {
            var paramType = bp.list()
                ? ParameterizedTypeName.get(LIST, ClassName.bestGuess(bp.javaType()))
                : ClassName.bestGuess(bp.javaType());
            builder.addParameter(paramType, bp.name());
        }

        builder.addStatement("$T condition = $T.noCondition()", CONDITION, DSL);
        for (var bp : gcf.bodyParams()) {
            switch (bp) {
                case BodyParam.Eq eq -> {
                    String col = eq.column().javaName();
                    if (eq.nonNull()) {
                        builder.addStatement("condition = condition.and(table.$L.eq($T.val($L, table.$L)))",
                            col, DSL, eq.name(), col);
                    } else {
                        builder.addStatement("if ($L != null) condition = condition.and(table.$L.eq($T.val($L, table.$L)))",
                            eq.name(), col, DSL, eq.name(), col);
                    }
                }
                case BodyParam.In in -> {
                    String col = in.column().javaName();
                    if (in.nonNull()) {
                        builder.addStatement("condition = condition.and(table.$L.in($L))", col, in.name());
                    } else {
                        builder.addStatement("if ($L != null) condition = condition.and(table.$L.in($L))",
                            in.name(), col, in.name());
                    }
                }
                case BodyParam.RowEq req -> {
                    // DSL.row(table.c1, ..., table.cN).eq(DSL.row(v1, ..., vN))
                    var cols = CodeBlock.builder();
                    for (int i = 0; i < req.columns().size(); i++) {
                        if (i > 0) cols.add(", ");
                        cols.add("table.$L", req.columns().get(i).javaName());
                    }
                    // Composite scalar values are not yet produced by any classifier route in the
                    // shipped phases — phase (e) wires the input-side classification that emits
                    // RowEq. Until then this arm is unreachable; emit a runtime stub that fails
                    // loudly if a generator regression produces it.
                    builder.addStatement("throw new $T($S)",
                        UnsupportedOperationException.class,
                        "BodyParam.RowEq emission is not yet wired (R50 phase e). Param '" + req.name() + "'.");
                }
                case BodyParam.RowIn rin -> {
                    // DSL.row(table.c1, ..., table.cN).in(rows)
                    // Same situation as RowEq — phase (e) wires the input-side route. Until then,
                    // unreachable; emit a loud runtime stub.
                    builder.addStatement("throw new $T($S)",
                        UnsupportedOperationException.class,
                        "BodyParam.RowIn emission is not yet wired (R50 phase e). Param '" + rin.name() + "'.");
                }
                case BodyParam.NodeIdIn ni -> {
                    var keyColArgs = CodeBlock.builder();
                    for (int i = 0; i < ni.nodeKeyColumns().size(); i++) {
                        if (i > 0) keyColArgs.add(", ");
                        keyColArgs.add("table.$L", ni.nodeKeyColumns().get(i).javaName());
                    }
                    builder.addStatement(
                        "condition = condition.and($L == null || $L.isEmpty() ? $T.noCondition() : $T.hasIds($S, $L, $L))",
                        ni.name(), ni.name(), DSL,
                        nodeIdEncoder, ni.nodeTypeId(), ni.name(), keyColArgs.build());
                }
            }
        }
        builder.addStatement("return condition");
        return builder.build();
    }

    static FieldSpec buildTextEnumMapField(CallSiteExtraction.TextMapLookup tl) {
        var MAP = ClassName.get(java.util.Map.class);
        var mapType = ParameterizedTypeName.get(MAP, ClassName.get(String.class), ClassName.get(String.class));
        var mapEntries = CodeBlock.builder();
        boolean first = true;
        for (var entry : tl.valueMapping().entrySet()) {
            if (!first) mapEntries.add(", ");
            mapEntries.add("$S, $S", entry.getKey(), entry.getValue());
            first = false;
        }
        return FieldSpec.builder(mapType, tl.mapFieldName())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .initializer("$T.of($L)", MAP, mapEntries.build())
            .build();
    }
}
