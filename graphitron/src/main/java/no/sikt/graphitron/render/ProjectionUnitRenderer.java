package no.sikt.graphitron.render;

import no.sikt.graphitron.command.Arity;
import no.sikt.graphitron.command.CallWrap;
import no.sikt.graphitron.command.Contribution;
import no.sikt.graphitron.command.GlueCall;
import no.sikt.graphitron.command.ProjectionCommand;
import no.sikt.graphitron.command.SelectTerm;
import no.sikt.graphitron.command.TermAlias;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.On;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ParentCorrelation;
import no.sikt.graphitron.rewrite.model.TableRef;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Renders one projection unit per {@link ProjectionCommand} row: a class carrying the unit's
 * {@code $project} method (grouped selection in, SELECT list out) plus any per-field
 * VALUES-rows helpers its lookup contributions declare. Total over the command's sealed arm
 * sets with no default arm, so a new contribution kind, wrap or term fails compilation here
 * until it has an emission.
 *
 * <p>The emitted method walks the result-key-grouped selection with a {@code for}/{@code switch}
 * over field names — arms per field, iteration per result-key bucket — accumulating into a
 * {@code LinkedHashSet} that dedupes by jOOQ {@code Field} identity while preserving insertion
 * order (two arms adding the same raw {@code table.X} collapse to one term; aliased
 * {@code .as(...)} emissions never collide because each call mints a fresh {@code Field}).
 * Nesting is a cross-unit call ({@link CallWrap.Splice}), not recursion into the same body, so
 * the loop locals are always {@code entry}/{@code sf} — the depth-suffix shadowing dodge retired
 * with the inlining.
 */
public final class ProjectionUnitRenderer {

    private static final ClassName FIELD = ClassName.get("org.jooq", "Field");
    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName MAP = ClassName.get("java.util", "Map");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName SET = ClassName.get("java.util", "Set");
    private static final ClassName ARRAY_LIST = ClassName.get(ArrayList.class);
    private static final ClassName LINKED_HASH_SET = ClassName.get(LinkedHashSet.class);
    private static final ClassName ENV = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName SELECTED_FIELD = ClassName.get("graphql.schema", "SelectedField");
    private static final String RESERVED_RK_ALIAS_PREFIX =
        no.sikt.graphitron.command.ReservedAliases.RESULT_KEY_PREFIX;
    private static final String ROW_PRESENT_SENTINEL = "__row_present__";

    private ProjectionUnitRenderer() {}

    public static List<TypeSpec> render(List<ProjectionCommand> rows, String outputPackage) {
        return rows.stream().map(row -> renderUnit(row, outputPackage)).toList();
    }

    private static TypeSpec renderUnit(ProjectionCommand row, String outputPackage) {
        var builder = TypeSpec.classBuilder(row.unit().simpleName())
            .addModifiers(Modifier.PUBLIC);
        switch (row) {
            case ProjectionCommand.AnchorUnit a ->
                builder.addMethod(buildTableContextMethod(a.table(), a.contributions(), outputPackage));
            case ProjectionCommand.NestedUnit n ->
                builder.addMethod(buildTableContextMethod(n.table(), n.contributions(), outputPackage));
            case ProjectionCommand.PivotUnit p ->
                builder.addMethod(buildPivotMethod(p));
        }
        // Per-field VALUES-rows helpers for this unit's own lookup contributions; the switch arm
        // calls the helper unqualified, so it lives on the same class ("helper locality").
        for (var contribution : row.contributions()) {
            if (contribution instanceof Contribution.Call call
                    && call.wrap() instanceof CallWrap.LookupMultiset lm) {
                builder.addMethod(LookupRows.buildInputRowsMethod(lm.mapping(),
                    lm.inputRowsHelper().methodName(), lm.terminalTable().tableClass(),
                    LookupRows.ArgSource.SELECTED_FIELD,
                    row.unit().simpleName() + "." + contribution.field()));
            }
        }
        return builder.build();
    }

    // ------------------------------------------------------------------------------------------
    // The table-context $project method (anchor and nested units)
    // ------------------------------------------------------------------------------------------

    private static MethodSpec buildTableContextMethod(TableRef tableRef,
            List<Contribution> contributions,
            String outputPackage) {
        var fieldWildcard = fieldWildcard();
        var builder = MethodSpec.methodBuilder(ProjectionCall.METHOD_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(listOfFieldWildcard())
            .addParameter(groupedType(), "grouped")
            .addParameter(tableRef.tableClass(), "table")
            .addParameter(ENV, "env")
            .addStatement("$T<$T> fields = new $T<>()", LINKED_HASH_SET, fieldWildcard, LINKED_HASH_SET);

        var selectionOccurrences = ProjectionCall.selectionOccurrencesClass(outputPackage);
        builder.addCode("for ($T $L : grouped.entrySet()) {\n", entryType(), "entry");
        builder.addCode("    $T sf = $T.canonical(entry.getKey(), entry.getValue());\n",
            SELECTED_FIELD, selectionOccurrences);
        builder.addCode("    switch (sf.getName()) {\n");
        for (var contribution : contributions) {
            emitArm(builder, contribution, outputPackage);
        }
        builder.addCode("        default -> { } // unhandled fields\n");
        builder.addCode("    }\n");
        builder.addCode("}\n");

        // Every contribution is selection-gated, so a selection projecting nothing (only
        // __typename, or only fields with no arm) yields an empty set; an empty select list
        // would have jOOQ project every known column, silently reinstating over-projection.
        // Answer it the way the pivot body answers a slot-less selection: one inline sentinel,
        // so the statement stays deterministic and one-column.
        builder.addCode("if (fields.isEmpty()) {\n");
        builder.addCode("    fields.add($T.inline(1).as($S));\n", DSL, ROW_PRESENT_SENTINEL);
        builder.addCode("}\n");
        builder.addStatement("return new $T<>(fields)", ARRAY_LIST);
        return builder.build();
    }

    /** One switch arm per contribution, total over the {@link Contribution} and wrap/term arms. */
    private static void emitArm(MethodSpec.Builder builder, Contribution contribution, String outputPackage) {
        switch (contribution) {
            case Contribution.Project p -> emitProjectArm(builder, p);
            case Contribution.Call c -> {
                switch (c.wrap()) {
                    case CallWrap.Splice ignored ->
                        builder.addCode("        case $S -> fields.addAll($L);\n", c.field(),
                            ProjectionCall.fromOccurrences(c.callee(),
                                CodeBlock.of("entry.getValue()"), CodeBlock.of("table"), outputPackage));
                    case CallWrap.Multiset m -> {
                        builder.addCode("        case $S -> {\n", c.field());
                        builder.addCode("$L", multisetArmBody(c, m, outputPackage));
                        builder.addCode("        }\n");
                    }
                    case CallWrap.LookupMultiset lm -> {
                        builder.addCode("        case $S -> {\n", c.field());
                        builder.addCode("$L", lookupMultisetArmBody(c, lm, outputPackage));
                        builder.addCode("        }\n");
                    }
                    case CallWrap.PivotMultiset pm -> {
                        builder.addCode("        case $S -> {\n", c.field());
                        builder.addCode("$L", pivotMultisetArmBody(c, pm, outputPackage));
                        builder.addCode("        }\n");
                    }
                }
            }
        }
    }

    private static void emitProjectArm(MethodSpec.Builder builder, Contribution.Project p) {
        if (p.terms().size() == 1) {
            var pre = CodeBlock.builder();
            var expr = termExpression(p.terms().get(0), pre);
            var preamble = pre.build();
            if (preamble.isEmpty()) {
                builder.addCode("        case $S -> fields.add($L);\n", p.field(), expr);
            } else {
                // A subselect term declares its hop aliases first, so the arm needs a block.
                builder.addCode("        case $S -> {\n", p.field());
                builder.addCode("$L", preamble);
                builder.addCode("            fields.add($L);\n", expr);
                builder.addCode("        }\n");
            }
            return;
        }
        // Multi-term arms today are exactly the composite column carriers; a braced block with
        // one add per term keeps the emitted shape legible for any future mixed-term arm too.
        builder.addCode("        case $S -> {\n", p.field());
        for (var term : p.terms()) {
            var pre = CodeBlock.builder();
            var expr = termExpression(term, pre);
            builder.addCode("$L", pre.build());
            builder.addCode("            fields.add($L);\n", expr);
        }
        builder.addCode("        }\n");
    }

    /**
     * The term's projected-field expression. {@code preamble} receives any statements the
     * expression needs in scope first (a subselect's hop-alias declarations).
     */
    private static CodeBlock termExpression(SelectTerm term, CodeBlock.Builder preamble) {
        return switch (term) {
            case SelectTerm.Column c -> switch (c.alias()) {
                case BY_COLUMN_IDENTITY -> CodeBlock.of("table.$L", c.column().javaName());
                case BY_RESULT_KEY -> CodeBlock.of("table.$L.as($S + entry.getKey())",
                    c.column().javaName(), RESERVED_RK_ALIAS_PREFIX);
            };
            case SelectTerm.HelperCall h ->
                // Alias by the runtime result key so aliased duplicate selections stay distinct.
                CodeBlock.of("$T.$L(table).as($S + entry.getKey())",
                    ClassName.bestGuess(h.method().className()), h.method().methodName(),
                    RESERVED_RK_ALIAS_PREFIX);
            case SelectTerm.ScalarSubselect s -> {
                var aliases = PathFragments.generateAliases(s.path());
                declareHopAliases(preamble, s.path(), aliases, "            ");
                yield CodeBlock.of("$T.field($L).as($S + entry.getKey())",
                    DSL, scalarInnerSelect(s, aliases), RESERVED_RK_ALIAS_PREFIX);
            }
            case SelectTerm.Aggregate a ->
                CodeBlock.of("$T.max(table.$L).filterWhere(table.$L.eq($T.inline($S))).as($S)",
                    DSL, a.value().javaName(), a.discriminator().javaName(), DSL, a.token(), a.asName());
        };
    }

    /**
     * The correlated single-column subselect of a {@link SelectTerm.ScalarSubselect}: JOIN chain
     * walked terminal-first, step-0 correlation off the borrowed {@link ParentCorrelation},
     * per-hop filters, {@code .limit(1)}.
     */
    private static CodeBlock scalarInnerSelect(SelectTerm.ScalarSubselect s, List<String> aliases) {
        var path = s.path();
        String terminalAlias = aliases.get(aliases.size() - 1);
        var sel = CodeBlock.builder();
        sel.add("$T.select($L.$L)", DSL, terminalAlias, s.terminal().javaName());
        sel.add("\n        .from($L)", terminalAlias);
        for (int i = path.size() - 1; i >= 1; i--) {
            switch (path.get(i)) {
                case JoinStep.Hop hop -> sel.add("\n        $L",
                    PathFragments.emitBackwardBridging(hop, aliases.get(i - 1), aliases.get(i), "column-reference"));
            }
        }
        var where = CodeBlock.builder();
        where.add("$L", correlationWhere(s.correlation(), aliases.get(0), "column-reference"));
        appendHopFilters(where, path, aliases, ".and($L)");
        sel.add("\n        .where($L)", where.build());
        sel.add("\n        .limit(1)");
        return sel.build();
    }

    // ------------------------------------------------------------------------------------------
    // Multiset arms
    // ------------------------------------------------------------------------------------------

    private static CodeBlock multisetArmBody(Contribution.Call c, CallWrap.Multiset m, String outputPackage) {
        var code = CodeBlock.builder();
        // Occurrence argument guard: the arm reads runtime state off the canonical
        // SelectedField, so all occurrences in the result-key bucket must agree on
        // getArguments(); serving the canonical occurrence's arguments otherwise is silent
        // wrong data. Gated on the producer's fact so a carrying-but-not-consuming arm cannot
        // false-positive.
        if (m.guardArguments()) {
            code.addStatement("$T.requireConsistentArguments(entry.getKey(), entry.getValue())",
                ProjectionCall.selectionOccurrencesClass(outputPackage));
        }

        String terminalAlias;
        if (m.path().isEmpty()) {
            // Standalone shape: start table == target table, no FK chain; a conditions-only
            // correlated subquery against the field's own table.
            terminalAlias = "t0";
            code.addStatement("$T $L = $T.$L.as($L.getName() + $S)",
                m.terminalTable().tableClass(), terminalAlias,
                m.terminalTable().constantsClass(), m.terminalTable().javaFieldName(),
                "table", "_" + terminalAlias);
        } else {
            var aliases = PathFragments.generateAliases(m.path());
            terminalAlias = aliases.get(aliases.size() - 1);
            declareHopAliases(code, m.path(), aliases, "");
        }

        code.addStatement("fields.add($T.multiset($L).as($S + entry.getKey()))",
            DSL, multisetInnerSelect(c, m, terminalAlias, outputPackage), RESERVED_RK_ALIAS_PREFIX);
        return code.build();
    }

    /**
     * The inner correlated subquery of a {@link CallWrap.Multiset}: nested {@code $project}
     * SELECT list (the whole result-key bucket descends, so the callee projects the union of
     * every occurrence's sub-selection), start-first JOIN chain (a lateral routine hop's call
     * arguments reference the previous alias, which SQL LATERAL scoping requires to its left),
     * step-0 parent correlation, hop filters, glue filter, fixed ordering, and the
     * cardinality/pagination limit. {@code env} threads onward unchanged: each level's context
     * reads legitimately see the ancestor environment, while the field's own runtime argument
     * reads route through the canonical {@code SelectedField} in this arm.
     */
    private static CodeBlock multisetInnerSelect(Contribution.Call c, CallWrap.Multiset m,
            String terminalAlias, String outputPackage) {
        var path = m.path();
        var aliases = path.isEmpty() ? List.<String>of() : PathFragments.generateAliases(path);
        var sel = CodeBlock.builder();
        sel.add("$T.select($L)", DSL,
            ProjectionCall.fromOccurrences(c.callee(), CodeBlock.of("entry.getValue()"),
                CodeBlock.of("$L", terminalAlias), outputPackage));
        sel.add("\n        .from($L)", path.isEmpty() ? terminalAlias : aliases.get(0));
        for (int i = 1; i < path.size(); i++) {
            switch (path.get(i)) {
                case JoinStep.Hop hop -> sel.add("\n        $L",
                    PathFragments.emitForwardBridging(hop, aliases.get(i - 1), aliases.get(i)));
            }
        }

        var where = CodeBlock.builder();
        if (path.isEmpty()) {
            where.add("$T.noCondition()", DSL);
        } else {
            where.add("$L", correlationWhere(m.correlation(), aliases.get(0), "inline table"));
        }
        appendHopFilters(where, path, aliases, "\n        .and($L)");
        if (m.filter() != null) {
            // The glue call reads the argument map off the inline field's own SelectedField
            // (the ancestor env has no such arguments); a context-reading coordinate appends
            // the ancestor env itself, which is correct for request-global context.
            where.add("\n        .and($L)", glueExpression(m.filter(), terminalAlias,
                CodeBlock.of("sf.getArguments()")));
        }
        sel.add("\n        .where($L)", where.build());

        if (m.orderBy() instanceof OrderBySpec.Fixed fixed && !fixed.columns().isEmpty()) {
            var orderParts = CodeBlock.builder();
            for (int i = 0; i < fixed.columns().size(); i++) {
                if (i > 0) orderParts.add(", ");
                var col = fixed.columns().get(i);
                orderParts.add("$L.$L.$L()",
                    terminalAlias, col.column().javaName(), col.direction().jooqMethodName());
            }
            sel.add("\n        .orderBy($L)", orderParts.build());
        }

        if (m.arity() == Arity.SINGLE) {
            sel.add("\n        .limit(1)");
        } else if (m.limitByFirstArgument()) {
            // Read `first` off the inline field's own SelectedField, not the ancestor env
            // (which has no such argument). The (Integer) cast is checked; no unchecked warning.
            sel.add("\n        .limit(sf.getArguments().get($S) == null ? $T.MAX_VALUE : ($T) sf.getArguments().get($S))",
                "first", Integer.class, Integer.class, "first");
        }
        return sel.build();
    }

    private static CodeBlock lookupMultisetArmBody(Contribution.Call c, CallWrap.LookupMultiset lm,
            String outputPackage) {
        var code = CodeBlock.builder();
        // Unconditional occurrence guard: the @lookupKey argument read is structural to this arm.
        code.addStatement("$T.requireConsistentArguments(entry.getKey(), entry.getValue())",
            ProjectionCall.selectionOccurrencesClass(outputPackage));

        var path = lm.path();
        List<String> aliases;
        String terminalAlias;
        if (path.isEmpty()) {
            aliases = List.of();
            terminalAlias = "lk0";
            code.addStatement("$T $L = $T.$L.as($L.getName() + $S)",
                lm.terminalTable().tableClass(), terminalAlias,
                lm.terminalTable().constantsClass(), lm.terminalTable().javaFieldName(),
                "table", "_" + c.field() + "_" + terminalAlias);
        } else {
            aliases = PathFragments.generateAliases(path);
            terminalAlias = aliases.get(aliases.size() - 1);
            declareHopAliases(code, path, aliases, "");
        }

        String diagnostic = c.callee().simpleName() + "." + c.field();
        code.addStatement("$T rows = $L(sf, $L)",
            LookupRows.rowArrayType(lm.mapping(), diagnostic),
            lm.inputRowsHelper().methodName(), terminalAlias);

        // Empty input short-circuits in Java, not SQL (jOOQ rejects DSL.values([])): a
        // falseCondition multiset keeps the aliased slot on the parent record for the reader.
        code.beginControlFlow("if (rows.length == 0)");
        code.addStatement("fields.add($T.multiset($T.select($L).from($L).where($T.falseCondition())).as($S + entry.getKey()))",
            DSL, DSL,
            ProjectionCall.fromOccurrences(c.callee(), CodeBlock.of("entry.getValue()"),
                CodeBlock.of("$L", terminalAlias), outputPackage),
            terminalAlias, DSL, RESERVED_RK_ALIAS_PREFIX);
        code.nextControlFlow("else");

        // VALUES derived-table alias: "idx" + one column per lookup key, labelled by SQL name so
        // the ON predicate resolves (PostgreSQL treats quoted identifiers case-sensitively).
        code.addStatement("$T input = $T.values(rows).as($L)",
            LookupRows.inputTableType(lm.mapping(), diagnostic), DSL,
            LookupRows.aliasArgs(lm.mapping(), c.field() + "Input", diagnostic));

        // Explicit ON clause against the VALUES derived table (USING would be ambiguous when
        // the FK chain traverses a junction table sharing a lookup-key column name).
        var onCondition = CodeBlock.builder();
        var lookupCols = lm.mapping().slotColumns();
        for (int i = 0; i < lookupCols.size(); i++) {
            if (i > 0) onCondition.add(".and(");
            var col = lookupCols.get(i);
            onCondition.add("$L.$L.eq(input.field($L.$L))",
                terminalAlias, col.javaName(), terminalAlias, col.javaName());
            if (i > 0) onCondition.add(")");
        }

        code.addStatement("fields.add($T.multiset($L).as($S + entry.getKey()))",
            DSL, lookupInnerSelect(c, lm, aliases, terminalAlias, onCondition.build(), outputPackage),
            RESERVED_RK_ALIAS_PREFIX);
        code.endControlFlow();
        return code.build();
    }

    private static CodeBlock lookupInnerSelect(Contribution.Call c, CallWrap.LookupMultiset lm,
            List<String> aliases, String terminalAlias, CodeBlock onCondition, String outputPackage) {
        var path = lm.path();
        var sel = CodeBlock.builder();
        sel.add("$T.select($L)", DSL,
            ProjectionCall.fromOccurrences(c.callee(), CodeBlock.of("entry.getValue()"),
                CodeBlock.of("$L", terminalAlias), outputPackage));
        sel.add("\n        .from($L)", terminalAlias);
        // JOIN chain from the terminal hop back towards step 0; no-op when the path is empty.
        for (int i = path.size() - 1; i >= 1; i--) {
            switch (path.get(i)) {
                case JoinStep.Hop hop -> sel.add("\n        $L",
                    PathFragments.emitBackwardBridging(hop, aliases.get(i - 1), aliases.get(i), "lookup"));
            }
        }
        sel.add("\n        .join(input).on($L)", onCondition);

        var where = CodeBlock.builder();
        if (path.isEmpty()) {
            where.add("$T.noCondition()", DSL);
        } else {
            where.add("$L", correlationWhere(lm.correlation(), aliases.get(0), "lookup"));
        }
        appendHopFilters(where, path, aliases, "\n        .and($L)");
        if (lm.filter() != null) {
            where.add("\n        .and($L)", glueExpression(lm.filter(), terminalAlias,
                CodeBlock.of("sf.getArguments()")));
        }
        sel.add("\n        .where($L)", where.build());
        sel.add("\n        .orderBy(input.field($S))", "idx");
        return sel.build();
    }

    private static CodeBlock pivotMultisetArmBody(Contribution.Call c, CallWrap.PivotMultiset pm,
            String outputPackage) {
        var code = CodeBlock.builder();
        String aliasVar = c.field() + "PivotAlias";
        // Alias string prefixed with the parent alias's runtime name so recursive /
        // self-referential subselects never shadow each other, like every multiset arm.
        code.addStatement("$T $L = $T.$L.as($L.getName() + $S)",
            pm.attributeTable().tableClass(), aliasVar,
            pm.attributeTable().constantsClass(), pm.attributeTable().javaFieldName(),
            "table", "_" + c.field() + "_pv");

        // Correlation: AND-chain over the single FK hop's column pairs — target side on the
        // attribute alias, source side on the parent alias. Arity-generic.
        var correlation = CodeBlock.builder();
        for (int i = 0; i < pm.correlation().slotCount(); i++) {
            if (i > 0) correlation.add(".and(");
            var target = pm.correlation().targetSideColumns().get(i);
            var source = pm.correlation().sourceSideColumns().get(i);
            correlation.add("$L.$L.eq(table.$L)", aliasVar, target.javaName(), source.javaName());
            if (i > 0) correlation.add(")");
        }
        // Uniform multiset envelope (single row: the aggregate over the correlated set
        // collapses on its own), aliased by the runtime result key; the reader unwraps
        // Result.get(0).
        code.addStatement("fields.add($T.multiset($T.select($L).from($L).where($L)).as($S + entry.getKey()))",
            DSL, DSL,
            ProjectionCall.fromOccurrences(c.callee(), CodeBlock.of("entry.getValue()"),
                CodeBlock.of("$L", aliasVar), outputPackage),
            aliasVar, correlation.build(), RESERVED_RK_ALIAS_PREFIX);
        return code.build();
    }

    // ------------------------------------------------------------------------------------------
    // The pivot unit's $project method
    // ------------------------------------------------------------------------------------------

    /**
     * The pivot unit's body deviates from the table-context skeleton in one way its unit kind
     * dictates: selected slots dedupe <em>by name</em> before the switch (one projected column
     * serves every alias of a slot, since the read side addresses the fixed slot name, and
     * per-occurrence emission would mint duplicate identically-named aggregates), and a
     * selection carrying no slot (introspection-only) appends the one-record sentinel so the
     * enclosing subselect stays well-formed and the one-record-per-parent invariant holds.
     */
    private static MethodSpec buildPivotMethod(ProjectionCommand.PivotUnit p) {
        var builder = MethodSpec.methodBuilder(ProjectionCall.METHOD_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(listOfFieldWildcard())
            .addParameter(groupedType(), "grouped")
            .addParameter(p.table().tableClass(), "table")
            .addParameter(ENV, "env");

        builder.addStatement("$T<String> slots = new $T<>()", SET, LINKED_HASH_SET);
        builder.beginControlFlow("for ($T occ : grouped.values())",
            ParameterizedTypeName.get(LIST, SELECTED_FIELD));
        builder.addStatement("slots.add(occ.get(0).getName())");
        builder.endControlFlow();
        builder.addStatement("$T<$T> fields = new $T<>()", LIST, fieldWildcard(), ARRAY_LIST);
        builder.beginControlFlow("for (String slot : slots)");
        builder.addCode("switch (slot) {\n");
        for (var contribution : p.contributions()) {
            var project = (Contribution.Project) contribution;
            for (var term : project.terms()) {
                builder.addCode("    case $S -> fields.add($L);\n", project.field(),
                    termExpression(term, CodeBlock.builder()));
            }
        }
        builder.addCode("    default -> { } // non-slot selections (__typename) project nothing\n");
        builder.addCode("}\n");
        builder.endControlFlow();
        builder.beginControlFlow("if (fields.isEmpty())");
        builder.addStatement("fields.add($T.inline(1).as($S))", DSL, "__pivot_present__");
        builder.endControlFlow();
        builder.addStatement("return fields");
        return builder.build();
    }

    // ------------------------------------------------------------------------------------------
    // Shared fragments
    // ------------------------------------------------------------------------------------------

    /**
     * Declares one aliased jOOQ table per hop. Alias strings are prefixed with the parent
     * alias's runtime name (via the jOOQ parent table's {@code getName()}) so recursive /
     * self-referential subselects never shadow each other's aliases: the outermost call's
     * prefix is the raw table name, and each nested {@code $project} call accumulates the
     * prefix through the table instance it receives. Materialization routes through the shared
     * {@link PathFragments#emitTableExpression} switch: a catalog hop declares {@code Tables.X},
     * a routine hop declares {@code Routines.m(<bound args>)} reading the previous node's alias
     * (the parent at hop 0) and runtime arguments off the canonical {@code SelectedField}.
     */
    private static void declareHopAliases(CodeBlock.Builder code, List<JoinStep> path,
            List<String> aliases, String indent) {
        for (int i = 0; i < path.size(); i++) {
            JoinStep.HasTargetTable ht = (JoinStep.HasTargetTable) path.get(i);
            String previousAlias = i == 0 ? "table" : aliases.get(i - 1);
            code.add(indent);
            code.addStatement("$T $L = $L.as($L.getName() + $S)",
                ht.targetTable().tableClass(), aliases.get(i),
                PathFragments.emitTableExpression(path.get(i),
                    new PreviousNodeRef.TypedAlias(previousAlias),
                    new ArgumentValueSource.FromSelectedField("sf")),
                "table", "_" + aliases.get(i));
        }
    }

    /**
     * Step-0 correlation against the parent: the sealed dispatch on the borrowed
     * {@link ParentCorrelation}. A filtered or condition-join first hop folds its correlation
     * into the two-argument condition-method call; a lateral routine first hop correlates
     * through its call arguments (the step-0 WHERE contributes nothing).
     */
    private static CodeBlock correlationWhere(ParentCorrelation correlation, String firstAlias,
            String pathKindLabel) {
        return switch (correlation) {
            case ParentCorrelation.OnFkSlots fk ->
                JoinFragments.emitCorrelationWhere(fk.slots(), firstAlias, "table");
            case ParentCorrelation.OnParentJoin pj -> switch (pj.firstHop().on()) {
                case On.ColumnPairs cp -> JoinFragments.emitCorrelationWhere(cp, firstAlias, "table");
                case On.Predicate pred -> PathFragments.emitTwoArgMethodCall(pred.condition(), "table", firstAlias);
                case On.Lateral ignored -> throw new IllegalStateException(
                    "ParentCorrelation.OnParentJoin cannot wrap a lateral hop");
            };
            case ParentCorrelation.OnLateralArgs ignored -> CodeBlock.of("$T.noCondition()", DSL);
            case ParentCorrelation.OnLiftedSlots ignored -> throw new IllegalStateException(
                "ParentCorrelation.OnLiftedSlots never reaches a " + pathKindLabel + " projection "
                + "arm; the pre-keyed lifted shape is DataLoader-batched through the rows methods");
        };
    }

    /** Per-hop {@code filter()} condition-method calls appended to the enclosing WHERE. */
    private static void appendHopFilters(CodeBlock.Builder where, List<JoinStep> path,
            List<String> aliases, String andFormat) {
        for (int i = 0; i < path.size(); i++) {
            if (path.get(i) instanceof JoinStep.Hop hop && hop.filter() != null) {
                String srcAlias = i == 0 ? "table" : aliases.get(i - 1);
                where.add(andFormat,
                    PathFragments.emitTwoArgMethodCall(hop.filter(), srcAlias, aliases.get(i)));
            }
        }
    }

    /** {@code <Owner>.<method>(<alias>, <argsMap>[, env])}: the condition glue call. */
    private static CodeBlock glueExpression(GlueCall glue, String tableAlias, CodeBlock argsMapExpr) {
        var owner = ClassName.get(glue.method().owner().packageName(), glue.method().owner().simpleName());
        return glue.takesEnv()
            ? CodeBlock.of("$T.$L($L, $L, env)", owner, glue.method().methodName(), tableAlias, argsMapExpr)
            : CodeBlock.of("$T.$L($L, $L)", owner, glue.method().methodName(), tableAlias, argsMapExpr);
    }

    private static ParameterizedTypeName fieldWildcard() {
        return ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class));
    }

    private static ParameterizedTypeName listOfFieldWildcard() {
        return ParameterizedTypeName.get(LIST, fieldWildcard());
    }

    private static ParameterizedTypeName groupedType() {
        return ParameterizedTypeName.get(MAP,
            ClassName.get(String.class),
            ParameterizedTypeName.get(LIST, SELECTED_FIELD));
    }

    private static ParameterizedTypeName entryType() {
        return ParameterizedTypeName.get(
            ClassName.get("java.util", "Map", "Entry"),
            ClassName.get(String.class),
            ParameterizedTypeName.get(LIST, SELECTED_FIELD));
    }
}
