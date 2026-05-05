package no.sikt.graphitron.rewrite.generators;


import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
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

    public static List<TypeSpec> generate(GraphitronSchema schema, String outputPackage) {
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
            .map(e -> generateConditionsClass(e.getKey(), e.getValue(), outputPackage))
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
                                                    String outputPackage) {
        // Class simple name is the last segment of the fully qualified name
        String simpleName = fqClassName.substring(fqClassName.lastIndexOf('.') + 1);
        var builder = TypeSpec.classBuilder(simpleName)
            .addModifiers(Modifier.PUBLIC);

        for (var gcf : filters) {
            builder.addMethod(buildConditionMethod(gcf, outputPackage));
            for (var bp : gcf.bodyParams()) {
                if (bp.extraction() instanceof CallSiteExtraction.TextMapLookup tl) {
                    builder.addField(buildTextEnumMapField(tl));
                }
            }
        }

        return builder.build();
    }

    static MethodSpec buildConditionMethod(GeneratedConditionFilter gcf, String outputPackage) {
        var tableRef = gcf.tableRef();
        var jooqTableClass = GeneratorUtils.ResolvedTableNames.ofTable(tableRef).jooqTableClass();

        var builder = MethodSpec.methodBuilder(gcf.methodName())
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(CONDITION)
            .addParameter(jooqTableClass, "table");

        for (var bp : gcf.bodyParams()) {
            builder.addParameter(paramType(bp), bp.name());
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
                    // DSL.row(table.c1, ..., table.cN).eq(arg) — the typed Field<T> overload
                    // produces a Row<N><T1, ..., TN> matching the method parameter exactly.
                    var cols = buildTypedCols(req.columns());
                    if (req.nonNull()) {
                        builder.addStatement("condition = condition.and($T.row($L).eq($L))",
                            DSL, cols, req.name());
                    } else {
                        builder.addStatement("if ($L != null) condition = condition.and($T.row($L).eq($L))",
                            req.name(), DSL, cols, req.name());
                    }
                }
                case BodyParam.RowIn rin -> {
                    // DSL.row(table.c1, ..., table.cN).in(rows) — typed Row<N>.in takes
                    // Collection<? extends Row<N><T1, ..., TN>>.
                    var cols = buildTypedCols(rin.columns());
                    if (rin.nonNull()) {
                        builder.addStatement("condition = condition.and($T.row($L).in($L))",
                            DSL, cols, rin.name());
                    } else {
                        builder.addStatement("if ($L != null) condition = condition.and($T.row($L).in($L))",
                            rin.name(), DSL, cols, rin.name());
                    }
                }
            }
        }
        builder.addStatement("return condition");
        return builder.build();
    }

    /**
     * Computes the method parameter type for a {@link BodyParam}. Eq/In use their stored
     * {@code javaType}; row-shape variants build {@code Row<N><T1, ..., TN>} (or {@code List<...>}
     * for IN) from the column tuple so that {@code DSL.row(Field<T1>, ..., Field<TN>).eq/.in}
     * matches without coercion.
     */
    private static TypeName paramType(BodyParam bp) {
        return switch (bp) {
            case BodyParam.Eq eq -> ClassName.bestGuess(eq.javaType());
            case BodyParam.In in -> ParameterizedTypeName.get(LIST, ClassName.bestGuess(in.javaType()));
            case BodyParam.RowEq req -> rowTypeName(req.columns());
            case BodyParam.RowIn rin -> ParameterizedTypeName.get(LIST, rowTypeName(rin.columns()));
        };
    }

    /** Builds {@code Row<N><T1, ..., TN>} from a column tuple. */
    private static ParameterizedTypeName rowTypeName(List<ColumnRef> columns) {
        int n = columns.size();
        ClassName rowN = ClassName.get("org.jooq", "Row" + n);
        TypeName[] typeArgs = new TypeName[n];
        for (int i = 0; i < n; i++) {
            typeArgs[i] = ClassName.bestGuess(columns.get(i).columnClass());
        }
        return ParameterizedTypeName.get(rowN, typeArgs);
    }

    /** Comma-separated {@code table.c1, ..., table.cN} for {@code DSL.row(Field<T>...)}. */
    private static CodeBlock buildTypedCols(List<ColumnRef> columns) {
        var cells = CodeBlock.builder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) cells.add(", ");
            cells.add("table.$L", columns.get(i).javaName());
        }
        return cells.build();
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
