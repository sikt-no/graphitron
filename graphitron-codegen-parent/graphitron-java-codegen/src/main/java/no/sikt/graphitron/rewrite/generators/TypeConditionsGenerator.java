package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.SqlGeneratingField;

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

    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");

    public static List<TypeSpec> generate(GraphitronSchema schema) {
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
            .map(e -> generateConditionsClass(e.getKey(), e.getValue()))
            .toList();
    }

    private static Optional<GeneratedConditionFilter> extractGeneratedConditionFilter(GraphitronField field) {
        if (!(field instanceof SqlGeneratingField sgf)) return Optional.empty();
        return sgf.filters().stream()
            .filter(f -> f instanceof GeneratedConditionFilter)
            .map(f -> (GeneratedConditionFilter) f)
            .findFirst();
    }

    private static TypeSpec generateConditionsClass(String fqClassName, List<GeneratedConditionFilter> filters) {
        // Class simple name is the last segment of the fully qualified name
        String simpleName = fqClassName.substring(fqClassName.lastIndexOf('.') + 1);
        var builder = TypeSpec.classBuilder(simpleName)
            .addModifiers(Modifier.PUBLIC);

        for (var gcf : filters) {
            builder.addMethod(buildConditionMethod(gcf));
            for (var bp : gcf.bodyParams()) {
                if (bp.extraction() instanceof CallSiteExtraction.TextMapLookup tl) {
                    builder.addField(buildTextEnumMapField(tl));
                }
            }
        }

        return builder.build();
    }

    static MethodSpec buildConditionMethod(GeneratedConditionFilter gcf) {
        var tableRef = gcf.tableRef();
        var jooqTableClass = ClassName.get(
            GeneratorConfig.getGeneratedJooqPackage() + ".tables",
            tableRef.javaClassName());

        var builder = MethodSpec.methodBuilder(gcf.methodName())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(ClassName.get("org.jooq", "Condition"))
            .addParameter(jooqTableClass, "table");

        for (var bp : gcf.bodyParams()) {
            builder.addParameter(ClassName.bestGuess(bp.javaType()), bp.name());
        }

        builder.addStatement("var condition = $T.noCondition()", DSL);
        for (var bp : gcf.bodyParams()) {
            String col = bp.column().javaName();
            if (bp.nonNull()) {
                builder.addStatement("condition = condition.and(table.$L.eq($T.val($L, table.$L)))",
                    col, DSL, bp.name(), col);
            } else {
                builder.addStatement("if ($L != null) condition = condition.and(table.$L.eq($T.val($L, table.$L)))",
                    bp.name(), col, DSL, bp.name(), col);
            }
        }
        builder.addStatement("return condition");
        return builder.build();
    }

    private static FieldSpec buildTextEnumMapField(CallSiteExtraction.TextMapLookup tl) {
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
            .addModifiers(Modifier.STATIC, Modifier.FINAL)
            .initializer("$T.of($L)", MAP, mapEntries.build())
            .build();
    }
}
