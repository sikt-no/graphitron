package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.render.CompositeDecodeHelperRegistry;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.On;
import no.sikt.graphitron.rewrite.model.LookupMapping;
import no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping;
import no.sikt.graphitron.rewrite.model.ParentCorrelation;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.WhereFilter;

import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.DSL;
import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.RESERVED_RK_ALIAS_PREFIX;

/**
 * Builds the switch-arm body for one inline {@link ChildField.LookupTableField} in
 * {@link TypeClassGenerator}'s {@code $fields} method: a correlated subquery narrowed by both
 * the FK-path parent correlation and a VALUES + JOIN keyset over the {@code @lookupKey} input rows.
 *
 * <p>The VALUES join uses an explicit {@code ON} predicate, not {@code USING}: the inner FK
 * chain may traverse a junction table whose column names collide with the lookup-key target
 * columns (e.g. {@code film_actor.actor_id} alongside {@code actor.actor_id}), and {@code USING}
 * requires the column to appear exactly once on each side. {@code ON} dereferences the VALUES
 * column via {@code input.field(terminal.COL)} and stays unambiguous.
 *
 * <p>Relies on a classifier invariant:
 * {@code LookupTableField.returnType().wrapper()} is neither {@link no.sikt.graphitron.rewrite.model.FieldWrapper.Connection}
 * nor {@link no.sikt.graphitron.rewrite.model.FieldWrapper.Single} (Single-cardinality
 * {@code @lookupKey} is rejected as a schema bug).
 *
 * <p>Threads the nested-alias parameter through every emitted Table-bound helper call; see
 * "Helper-locality" in {@code docs/architecture/reference/emitter-conventions.adoc}.
 */
public final class InlineLookupTableFieldEmitter {

    private static final ClassName TABLE = ClassName.get("org.jooq", "Table");

    private InlineLookupTableFieldEmitter() {}

    /**
     * Returns the {@code {...}} body to place inside a switch arm. Does <em>not</em> include the
     * {@code case "name" ->} prefix; the caller composes that.
     *
     * @param lf           the lookup-table field to emit
     * @param parentAlias  the local variable name for the parent alias in the generated code
     *                     ({@link TypeClassGenerator}'s {@code $fields} signature parameter,
     *                     the literal {@code "table"})
     * @param sfName       the caller-scope {@code SelectedField} variable name in scope at the
     *                     emission site. Threaded through rather than hardcoded because
     *                     {@code NestingField} recursion declares a fresh depth-specific
     *                     {@code SelectedField} local per nesting level to avoid JLS §14.4.2
     *                     shadowing.
     * @param entryName    the caller-scope {@code Map.Entry<String, List<SelectedField>>}
     *                     variable name holding the full result-key bucket ({@code sfName} is its
     *                     canonical first occurrence); depth-suffixed like {@code sfName}.
     */
    public static CodeBlock buildSwitchArmBody(ChildField.LookupTableField lf, String parentAlias, String sfName,
            String entryName, String outputPackage, CompositeDecodeHelperRegistry registry) {
        return buildArm(lf, parentAlias, sfName, entryName, outputPackage, registry);
    }

    private static CodeBlock buildArm(ChildField.LookupTableField lf, String parentAlias, String sfName,
            String entryName, String outputPackage, CompositeDecodeHelperRegistry registry) {
        List<JoinStep> path = lf.joinPath();
        TableRef terminalTable = lf.returnType().table();
        ClassName typeClass = ClassName.get(outputPackage + ".types", lf.returnType().returnTypeName());

        var code = CodeBlock.builder();

        // Occurrence argument guard: the @lookupKey argument values are read off the canonical
        // SelectedField, so every occurrence in the result-key bucket must agree on
        // getArguments(); otherwise serving the canonical occurrence's arguments for every path
        // is silent wrong data. Unconditional, unlike InlineTableFieldEmitter's predicate-gated
        // guard, because the @lookupKey read is structural to this arm.
        code.addStatement("$T.requireConsistentArguments($L.getKey(), $L.getValue())",
            TypeClassGenerator.selectionOccurrencesClass(outputPackage), entryName, entryName);

        List<String> aliases;
        String terminalAlias;

        // Empty joinPath: no FK chain, no parent correlation; the lookup runs standalone against
        // the target table (the classifier requires no @reference for LookupTableField).
        if (path.isEmpty()) {
            aliases = List.of();
            terminalAlias = "lk0";
            ClassName jooqTableClass = terminalTable.tableClass();
            code.addStatement("$T $L = $T.$L.as($L.getName() + $S)",
                jooqTableClass, terminalAlias, terminalTable.constantsClass(), terminalTable.javaFieldName(),
                parentAlias, "_" + lf.name() + "_" + terminalAlias);
        } else {
            aliases = JoinPathEmitter.generateAliases(path, terminalTable);
            terminalAlias = aliases.get(aliases.size() - 1);
            // Alias strings are prefixed with the parent alias's runtime name so recursive /
            // self-referential subselects never shadow each other's aliases.
            for (int i = 0; i < path.size(); i++) {
                JoinStep.HasTargetTable ht = (JoinStep.HasTargetTable) path.get(i);
                ClassName jooqTableClass = ht.targetTable().tableClass();
                // Routine hops never reach the lookup path (the classifier lands typed
                // Deferred); the OnLateralArgs / On.Lateral arms below throw if that slips.
                code.addStatement("$T $L = $L.as($L.getName() + $S)",
                    jooqTableClass, aliases.get(i),
                    JoinPathEmitter.emitTableExpression(path.get(i),
                        new PreviousNodeRef.TypedAlias(i == 0 ? parentAlias : aliases.get(i - 1)),
                        new ArgumentValueSource.FromSelectedField(sfName)),
                    parentAlias, "_" + aliases.get(i));
            }
        }

        // VALUES rows come from the per-field helper method (emitted by TypeClassGenerator),
        // typed Row<M+1> for idx + @lookupKey columns to match the helper's return type.
        // DSL.values(Row<M+1>...) yields Table<Record<M+1>>, so input.field(...) stays typed.
        ColumnMapping cm = (ColumnMapping) lf.lookupMapping();
        List<no.sikt.graphitron.rewrite.model.ColumnRef> lookupCols = cm.slotColumns();
        int lookupArity = lookupCols.size() + 1;
        TypeName[] lookupTypeArgs = new TypeName[lookupArity];
        lookupTypeArgs[0] = ClassName.get(Integer.class);
        for (int i = 0; i < lookupCols.size(); i++) {
            lookupTypeArgs[i + 1] = lookupCols.get(i).columnType();
        }
        TypeName lookupRowType = ParameterizedTypeName.get(
            ClassName.get("org.jooq", "Row" + lookupArity), lookupTypeArgs);
        TypeName lookupInputTableType = ParameterizedTypeName.get(TABLE,
            ParameterizedTypeName.get(ClassName.get("org.jooq", "Record" + lookupArity), lookupTypeArgs));

        String inputRowsName = LookupValuesJoinEmitter.inputRowsMethodName(lf);
        code.addStatement("$T[] rows = $L($L, $L)", lookupRowType, inputRowsName, sfName, terminalAlias);

        // Empty input short-circuits in Java, not SQL (jOOQ rejects DSL.values([])): a
        // falseCondition multiset keeps the aliased slot on the parent record for the DataFetcher.
        code.beginControlFlow("if (rows.length == 0)");
        code.addStatement(
            "fields.add($T.multiset($T.select($T.$$fields($L.getValue(), $L, env)).from($L).where($T.falseCondition())).as($S + $L.getKey()))",
            DSL, DSL, typeClass, entryName, terminalAlias, terminalAlias, DSL, RESERVED_RK_ALIAS_PREFIX, entryName);
        code.nextControlFlow("else");

        // VALUES derived-table alias: "idx" + one column per lookup key. Labels must match the
        // target column's SQL name (not the Java field name) so input.field(terminal.COL) resolves.
        var aliasArgs = CodeBlock.builder();
        aliasArgs.add("$S, $S", lf.name() + "Input", "idx");
        for (var col : lookupCols) {
            aliasArgs.add(", $S", col.sqlName());
        }
        code.addStatement("$T input = $T.values(rows).as($L)", lookupInputTableType, DSL, aliasArgs.build());

        // Explicit ON clause against the VALUES derived table; see the class javadoc for why
        // USING cannot be used here.
        var onCondition = CodeBlock.builder();
        for (int i = 0; i < lookupCols.size(); i++) {
            if (i > 0) onCondition.add(".and(");
            var col = lookupCols.get(i);
            onCondition.add("$L.$L.eq(input.field($L.$L))",
                terminalAlias, col.javaName(),
                terminalAlias, col.javaName());
            if (i > 0) onCondition.add(")");
        }

        // Aliased FK-target table locals for FK-target @nodeId override @condition filters, so
        // buildInnerSelect emits each as a correlated EXISTS rather than mis-passing the lookup's
        // own table. Runtime-prefixed SQL alias (this arm recurses), like the hop aliases above.
        Map<WhereFilter, List<String>> fkTargetAliases =
            FkTargetConditionEmitter.declareAliases(code, lf.filters(), terminalAlias, true);

        // Pre-lift any converter-backed list filter arg into a `<name>Keys` local (read by the
        // JooqConvert list arm), routed through the field's own SelectedField, matching
        // InlineTableFieldEmitter. Declared inside the non-empty (else) branch since only the
        // inner SELECT references it.
        ArgCallEmitter.emitJooqConvertKeyLifts(code, lf.filters(), new ArgumentValueSource.FromSelectedField(sfName));

        CodeBlock innerSelect = buildInnerSelect(lf, path, aliases, terminalAlias, typeClass,
            parentAlias, onCondition.build(), sfName, entryName, registry, fkTargetAliases);
        code.addStatement("fields.add($T.multiset($L).as($S + $L.getKey()))",
            DSL, innerSelect, RESERVED_RK_ALIAS_PREFIX, entryName);
        code.endControlFlow();

        return code.build();
    }

    /**
     * Builds the inner correlated subquery expression with both the FK-path JOIN chain and the
     * VALUES join keyset. AND-composed with the parent correlation in the WHERE clause;
     * ORDER BY {@code input.idx} preserves input-row order.
     */
    private static CodeBlock buildInnerSelect(ChildField.LookupTableField lf, List<JoinStep> path,
            List<String> aliases, String terminalAlias, ClassName typeClass,
            String parentAlias, CodeBlock onCondition, String sfName, String entryName,
            CompositeDecodeHelperRegistry registry,
            Map<WhereFilter, List<String>> fkTargetAliases) {
        var sel = CodeBlock.builder();
        // The descent hands over the whole result-key bucket so the target projects the union of
        // all occurrences' sub-selections (edges.node vs nodes divergence); each path's readers
        // ignore columns they didn't ask for.
        // Invariant: threading `env` into the nested $fields call is correct. Each nested level
        // re-derives its own SelectedField; env is only needed there for request-scoped context
        // reads, and only this arm's own runtime argument reads route through the SelectedField.
        // Do not "fix" env to the SelectedField.
        sel.add("$T.select($T.$$fields($L.getValue(), $L, env))",
            DSL, typeClass, entryName, terminalAlias);

        sel.add("\n        .from($L)", terminalAlias);

        // JOIN chain from the terminal hop back towards step 0; no-op when path is empty.
        for (int i = path.size() - 1; i >= 1; i--) {
            JoinStep bridging = path.get(i);
            String prevAlias = aliases.get(i - 1);
            switch (bridging) {
                case JoinStep.Hop hop -> sel.add("\n        $L",
                    JoinPathEmitter.emitBackwardBridging(hop, prevAlias, aliases.get(i), "lookup"));
            }
        }

        sel.add("\n        .join(input).on($L)", onCondition);

        // WHERE: step 0's correlation against parent, then hop filters, then user filters. A
        // DSL.noCondition() anchor stands in when there is no correlation so the subsequent
        // .and(...) chain still type-checks.
        var where = CodeBlock.builder();
        if (path.isEmpty()) {
            where.add("$T.noCondition()", ClassName.get("org.jooq.impl", "DSL"));
        } else {
            String firstAlias = aliases.get(0);
            switch (lf.parentCorrelation()) {
                case ParentCorrelation.OnFkSlots fk ->
                    where.add("$L", JoinPathEmitter.emitCorrelationWhere(fk.slots(), firstAlias, parentAlias));
                case ParentCorrelation.OnParentJoin pj ->
                    where.add("$L", switch (pj.firstHop().on()) {
                        case On.ColumnPairs cp -> JoinPathEmitter.emitCorrelationWhere(cp, firstAlias, parentAlias);
                        case On.Predicate pred -> JoinPathEmitter.emitTwoArgMethodCall(pred.condition(), parentAlias, firstAlias);
                        case On.Lateral ignored -> throw new IllegalStateException(
                            "ParentCorrelation.OnParentJoin cannot wrap a lateral hop");
                    });
                case ParentCorrelation.OnLateralArgs ignored -> throw new IllegalStateException(
                    "a lateral routine hop cannot head a lookup path; @lookupKey on routine "
                    + "chains classifies as typed Deferred");
            case ParentCorrelation.OnLiftedSlots ignored -> throw new IllegalStateException(
                "ParentCorrelation.OnLiftedSlots never reaches the inline emitters; the "
                + "pre-keyed lifted shape is DataLoader-batched through SplitRowsMethodEmitter");
            }
        }
        for (JoinStep step : path) {
            if (step instanceof JoinStep.Hop hop && hop.filter() != null) {
                String srcAlias = resolveSourceAlias(path, aliases, hop, parentAlias);
                where.add("\n        .and($L)",
                    JoinPathEmitter.emitTwoArgMethodCall(hop.filter(), srcAlias, aliasForStep(path, aliases, hop)));
            }
        }
        for (WhereFilter f : lf.filters()) {
            where.add("\n        .and($L)",
                FkTargetConditionEmitter.emitTerm(new TypeFetcherEmissionContext(), f, terminalAlias, registry, null, fkTargetAliases,
                    new ArgumentValueSource.FromSelectedField(sfName)));
        }
        sel.add("\n        .where($L)", where.build());

        sel.add("\n        .orderBy(input.field($S))", "idx");
        return sel.build();
    }

    /** Source alias for a hop's filter call: the previous hop's alias, or the parent alias for step 0. */
    private static String resolveSourceAlias(List<JoinStep> path, List<String> aliases, JoinStep.Hop step, String parentAlias) {
        int idx = path.indexOf(step);
        return idx == 0 ? parentAlias : aliases.get(idx - 1);
    }

    /** The alias of a specific hop, looked up by object identity within the path list. */
    private static String aliasForStep(List<JoinStep> path, List<String> aliases, JoinStep step) {
        int idx = path.indexOf(step);
        return aliases.get(idx);
    }
}
