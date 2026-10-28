package no.sikt.graphitron.render;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;

import javax.lang.model.element.Modifier;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-class collector for the element reads of filter fields typed as a list of input objects
 * ({@link CallSiteExtraction.ElementRows}). The glue local such a filter binds is built by a private
 * static helper on the conditions class, and the call site collapses to
 * {@code <name>(<wire>, <table>)}.
 *
 * <p>The helper takes the uncast wire value and the table the cells' columns belong to (the glue's
 * own table, or the terminal table a {@code @reference} path reaches). Its shape forks on the arm:
 *
 * <ul>
 *   <li>{@link CallSiteExtraction.ElementRows.Whole}: a rows helper ({@code <stem>Rows}) returning
 *       one entry per element, {@code List<Row<N><T1, ..., TN>>} for two or more cells, each cell
 *       lifted through {@code DSL.val(value, table.COL.getDataType())} so a converter on the column
 *       runs, or {@code List<T>} for a one-cell element, which {@code table.COL.in(values)} binds
 *       itself. A wire value that is not a {@code List} yields {@code null}, which the glue's
 *       presence guard reads as no filter. An element that is not a {@code Map} (graphql-java's
 *       coercion leaves only {@code null}) yields a row of typed nulls, which matches no row; it is
 *       never dropped, since dropping it would turn {@code [null]} into an empty list that filters
 *       nothing.</li>
 *   <li>{@link CallSiteExtraction.ElementRows.Partial}: a match helper ({@code <stem>Match})
 *       returning the finished {@code Condition}, the OR over elements of the AND over the cells each
 *       element carries, compared as {@code table.COL.eq(cellN)} at the column's Java type so a
 *       converter still runs. An absent, non-list or empty wire value yields {@code null}. A
 *       non-{@code Map} element contributes {@code DSL.falseCondition()}, for the reason a whole
 *       read contributes a row of typed nulls; an element carrying no cell contributes
 *       {@code DSL.trueCondition()}, never {@code DSL.and} over an empty list, since jOOQ's
 *       {@code noCondition()} is an identity for {@code or} too and would drop the element from the
 *       disjunction.</li>
 * </ul>
 *
 * <p>A registry of its own because none of the existing ones has this key: the node-id decode
 * registry keys on a decode helper and a mode, the routine descent registry takes no table, and the
 * input-bean registry builds beans. The body is in statement form, per "statement form over
 * expression tricks" in {@code docs/architecture/principles/development-principles.adoc}, and the
 * table arrives as a parameter, per helper locality in
 * {@code docs/architecture/reference/emitter-conventions.adoc}.
 */
public final class ElementRowsHelperRegistry {

    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName ARRAY_LIST = ClassName.get("java.util", "ArrayList");
    private static final ClassName MAP = ClassName.get("java.util", "Map");
    private static final ClassName CONDITION = ClassName.get("org.jooq", "Condition");

    /**
     * Two reads share a helper when they are the same read (arm and cells) against the same table
     * class.
     */
    private record Key(TypeName tableClass, CallSiteExtraction.ElementRows rows) {}

    private final Map<Key, String> helperNames = new LinkedHashMap<>();
    private final Map<Key, MethodSpec> helpers = new LinkedHashMap<>();

    private ElementRowsHelperRegistry() {}

    /**
     * Brackets construct-register-drain so a registered helper is never silently dropped: a lost
     * drain would surface as a dangling call in the generated source and a consumer compile error.
     */
    public static void collectInto(TypeSpec.Builder classBuilder,
            java.util.function.Consumer<ElementRowsHelperRegistry> body) {
        var registry = new ElementRowsHelperRegistry();
        body.accept(registry);
        registry.emit().forEach(classBuilder::addMethod);
    }

    /**
     * Registers the element read for one list-of-input filter and returns the helper's name.
     *
     * @param stem       the filter's parameter name, which names the helper
     * @param tableClass the class of the table the cells' columns belong to, the helper's second
     *                   parameter
     * @param rows       the element read, whose arm picks the rows or the match helper
     */
    public String register(String stem, TypeName tableClass, CallSiteExtraction.ElementRows rows) {
        var key = new Key(tableClass, rows);
        String existing = helperNames.get(key);
        if (existing != null) return existing;
        String name = uniqueName(stem + switch (rows) {
            case CallSiteExtraction.ElementRows.Whole ignored -> "Rows";
            case CallSiteExtraction.ElementRows.Partial ignored -> "Match";
        });
        helperNames.put(key, name);
        helpers.put(key, switch (rows) {
            case CallSiteExtraction.ElementRows.Whole whole -> buildRowsHelper(tableClass, whole, name);
            case CallSiteExtraction.ElementRows.Partial partial -> buildMatchHelper(tableClass, partial, name);
        });
        return name;
    }

    /** All collected helper specs in registration order. */
    public Collection<MethodSpec> emit() {
        return helpers.values();
    }

    /**
     * The type the helper returns and the glue local is declared as: for a whole read
     * {@code List<T>} for one cell and {@code List<Row<N><T1, ..., TN>>} for more, computed from the
     * cells' columns; for a partial read the finished {@code Condition}.
     */
    public static TypeName localType(CallSiteExtraction.ElementRows rows) {
        return switch (rows) {
            case CallSiteExtraction.ElementRows.Whole whole -> rowsType(whole);
            case CallSiteExtraction.ElementRows.Partial ignored -> CONDITION;
        };
    }

    private static TypeName rowsType(CallSiteExtraction.ElementRows.Whole rows) {
        var columns = rows.columns();
        TypeName element = columns.size() == 1
            ? CatalogRefs.columnType(columns.get(0))
            : CatalogRefs.typedRow(columns);
        return ParameterizedTypeName.get(LIST, element);
    }

    private String uniqueName(String base) {
        String candidate = base;
        int suffix = 2;
        while (helperNames.containsValue(candidate)) {
            candidate = base + suffix++;
        }
        return candidate;
    }

    private static MethodSpec buildRowsHelper(TypeName tableClass, CallSiteExtraction.ElementRows.Whole rows,
            String name) {
        var cells = rows.cells();
        TypeName returnType = rowsType(rows);
        TypeName elementType = ((ParameterizedTypeName) returnType).typeArguments().get(0);
        boolean single = cells.size() == 1;

        var body = CodeBlock.builder();
        body.beginControlFlow("if (!(wire instanceof $T<?> elements))", LIST)
            .addStatement("return null")
            .endControlFlow();
        body.addStatement("$T rows = new $T<>(elements.size())", returnType, ARRAY_LIST);
        body.beginControlFlow("for ($T element : elements)", Object.class);
        body.beginControlFlow("if (!(element instanceof $T<?, ?> fields))", MAP);
        if (single) {
            body.addStatement("rows.add(null)");
        } else {
            body.addStatement("rows.add($L)", nullRow(cells));
        }
        body.addStatement("continue");
        body.endControlFlow();
        for (int i = 0; i < cells.size(); i++) {
            readCell(body, cells.get(i), i + 1);
        }
        if (single) {
            body.addStatement("rows.add(cell1)");
        } else {
            var row = CodeBlock.builder();
            for (int i = 0; i < cells.size(); i++) {
                if (i > 0) row.add(", ");
                row.add("$T.val(cell$L, table.$L.getDataType())", DSL, i + 1,
                    cells.get(i).column().javaName());
            }
            body.addStatement("rows.add($T.row($L))", DSL, row.build());
        }
        body.endControlFlow();
        body.addStatement("return rows");
        return MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(returnType)
            .addParameter(Object.class, "wire")
            .addParameter(tableClass, "table")
            .addCode(body.build())
            .build();
    }

    /**
     * The match helper for a partial read: per element, the AND over the cells it carries, a
     * required cell compared unguarded and an optional one only when present; the list is the OR over
     * its elements.
     */
    private static MethodSpec buildMatchHelper(TypeName tableClass, CallSiteExtraction.ElementRows.Partial rows,
            String name) {
        var cells = rows.cells();
        TypeName conditions = ParameterizedTypeName.get(LIST, CONDITION);
        boolean anyRequired = cells.stream().anyMatch(CallSiteExtraction.Cell::required);

        var body = CodeBlock.builder();
        body.beginControlFlow("if (!(wire instanceof $T<?> elements) || elements.isEmpty())", LIST)
            .addStatement("return null")
            .endControlFlow();
        body.addStatement("$T anyOf = new $T<>(elements.size())", conditions, ARRAY_LIST);
        body.beginControlFlow("for ($T element : elements)", Object.class);
        body.beginControlFlow("if (!(element instanceof $T<?, ?> fields))", MAP)
            .addStatement("anyOf.add($T.falseCondition())", DSL)
            .addStatement("continue")
            .endControlFlow();
        for (int i = 0; i < cells.size(); i++) {
            readCell(body, cells.get(i), i + 1);
        }
        body.addStatement("$T cells = new $T<>($L)", conditions, ARRAY_LIST, cells.size());
        for (int i = 0; i < cells.size(); i++) {
            var cell = cells.get(i);
            var compare = CodeBlock.of("cells.add(table.$L.eq(cell$L))", cell.column().javaName(), i + 1);
            if (cell.required()) {
                body.addStatement(compare);
            } else {
                body.beginControlFlow("if (cell$L != null)", i + 1)
                    .addStatement(compare)
                    .endControlFlow();
            }
        }
        if (anyRequired) {
            body.addStatement("anyOf.add($T.and(cells))", DSL);
        } else {
            body.addStatement("anyOf.add(cells.isEmpty() ? $T.trueCondition() : $T.and(cells))", DSL, DSL);
        }
        body.endControlFlow();
        body.addStatement("return $T.or(anyOf)", DSL);
        return MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(CONDITION)
            .addParameter(Object.class, "wire")
            .addParameter(tableClass, "table")
            .addCode(body.build())
            .build();
    }

    /** The row of typed nulls a non-{@code Map} element contributes. */
    private static CodeBlock nullRow(List<CallSiteExtraction.Cell> cells) {
        var row = CodeBlock.builder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) row.add(", ");
            row.add("$T.val(null, table.$L.getDataType())", DSL, cells.get(i).column().javaName());
        }
        return CodeBlock.of("$T.row($L)", DSL, row.build());
    }

    /**
     * Declares {@code cell<n>}, the cell's value at the column's Java type, read off the element's
     * {@code fields} map down the cell's path. A nested path descends one guarded level at a time,
     * leaving the wire value {@code null} when a level is absent.
     */
    private static void readCell(CodeBlock.Builder body, CallSiteExtraction.Cell cell, int n) {
        String wire = "cell" + n + "Wire";
        var path = cell.path();
        if (path.size() == 1) {
            body.addStatement("$T $L = fields.get($S)", Object.class, wire, path.get(0));
        } else {
            body.addStatement("$T $L = null", Object.class, wire);
            String current = "fields";
            for (int depth = 0; depth < path.size() - 1; depth++) {
                String level = "cell" + n + "Level" + (depth + 1);
                body.beginControlFlow("if ($L.get($S) instanceof $T<?, ?> $L)", current, path.get(depth),
                    MAP, level);
                current = level;
            }
            body.addStatement("$L = $L.get($S)", wire, current, path.get(path.size() - 1));
            for (int depth = 0; depth < path.size() - 1; depth++) {
                body.endControlFlow();
            }
        }
        TypeName type = CatalogRefs.columnType(cell.column());
        String local = "cell" + n;
        switch (cell.leaf()) {
            case CallSiteExtraction.JooqConvert jc ->
                body.addStatement("$T $L = $T.val($L, table.$L.getDataType()).getValue()",
                    type, local, DSL, wire, jc.columnJavaName());
            case CallSiteExtraction.EnumValueOf ev -> {
                var enumClass = ClassName.bestGuess(ev.enumClassName());
                body.addStatement("$T $L = null", enumClass, local);
                body.beginControlFlow("if ($L instanceof $T $LName)", wire, String.class, local)
                    .addStatement("$L = $T.valueOf($LName)", local, enumClass, local)
                    .endControlFlow();
            }
            default -> {
                if (type.equals(ClassName.get(Object.class))) {
                    body.addStatement("$T $L = $L", type, local, wire);
                } else {
                    body.addStatement("$T $L = ($T) $L", type, local, type, wire);
                }
            }
        }
    }
}
