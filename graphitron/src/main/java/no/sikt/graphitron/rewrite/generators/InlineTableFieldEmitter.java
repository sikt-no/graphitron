package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.On;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ParentCorrelation;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.WhereFilter;

import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.DSL;

/**
 * Builds the switch-arm body for one inline {@link ChildField.TableField} in
 * {@link TypeClassGenerator}'s {@code $fields} method. Emits a correlated subquery projecting the
 * nested type, uniformly wrapped as {@code DSL.multiset(...)} for both cardinalities. Single
 * cardinality adds {@code .limit(1)} inside the subquery and is unwrapped on the read side by a
 * lambda {@code DataFetcher} registered in wiring.
 *
 * <p>Uniform multiset is a deliberate deviation from the G5 plan's two-shape fork — jOOQ 3.20's
 * {@code DSL.row(Collection)} flattens nested aliased fields at render time, breaking depth-2
 * self-referential projections. See plan history iteration 7 for the empirical findings.
 *
 * <p>Relies on the C1 invariant {@code TableField.returnType().wrapper() != Connection}.
 *
 * <p>Handles both {@link On.ColumnPairs FK-derived} and {@link On.Predicate condition-join} hops uniformly:
 * targets are pre-resolved on {@link JoinStep.HasTargetTable} so alias declarations read
 * through one capability, and the JOIN chain dispatches on step type to emit either
 * {@code .join(alias).onKey(FK)} or {@code .join(alias).on(condition(prevAlias, alias))}.
 * Step-0 parent correlation reads {@link ChildField.TableField#parentCorrelation()} via a
 * sealed switch; FK-derived first-hops produce a WHERE-clause correlation, condition-join
 * first-hops fold their correlation into the JOIN's ON clause.
 */
public final class InlineTableFieldEmitter {

    private InlineTableFieldEmitter() {}

    /**
     * Returns the {@code {...}} body to place inside a switch arm. Does <em>not</em> include the
     * {@code case "name" ->} prefix — the caller composes that.
     *
     * @param tf           the table field to emit
     * @param parentAlias  the local variable name for the parent alias in the generated code
     *                     ({@link TypeClassGenerator}'s {@code $fields} signature parameter,
     *                     the literal {@code "table"})
     * @param sfName       the caller-scope {@code SelectedField} variable name that is in
     *                     scope at the site where this body is emitted. Threaded through so
     *                     the emitter substitutes the caller's depth-specific variable rather
     *                     than a hardcoded literal — required for {@code NestingField}
     *                     recursion, where each nesting level declares its own
     *                     {@code SelectedField} local to avoid JLS §14.4.2 shadowing.
     */
    public static CodeBlock buildSwitchArmBody(ChildField.TableField tf, String parentAlias, String sfName,
            String outputPackage, CompositeDecodeHelperRegistry registry) {
        return buildArm(tf, parentAlias, sfName, outputPackage, registry);
    }

    private static CodeBlock buildArm(ChildField.TableField tf, String parentAlias, String sfName,
            String outputPackage, CompositeDecodeHelperRegistry registry) {
        List<JoinStep> path = tf.joinPath();
        TableRef terminalTable = tf.returnType().table();
        List<String> aliases = JoinPathEmitter.generateAliases(path, terminalTable);
        ClassName typeClass = ClassName.get(outputPackage + ".types", tf.returnType().returnTypeName());

        var code = CodeBlock.builder();

        String terminalAlias;
        if (path.isEmpty()) {
            // Standalone-lookup shape: an empty joinPath means start table == target table, so
            // parentCorrelation is null (ParentCorrelation.checkCarrierInvariant) and there is no FK
            // chain to walk. Synthesize a single alias for the field's own (terminal) table; the
            // inner SELECT emits a conditions-only correlated subquery against it with no key
            // projection, mirroring InlineLookupTableFieldEmitter's empty-path arm.
            terminalAlias = "t0";
            code.addStatement("$T $L = $T.$L.as($L.getName() + $S)",
                terminalTable.tableClass(), terminalAlias, terminalTable.constantsClass(), terminalTable.javaFieldName(),
                parentAlias, "_" + terminalAlias);
        } else {
            terminalAlias = aliases.get(aliases.size() - 1);
            // Declare aliased jOOQ tables for each hop. Alias strings are prefixed with the parent
            // alias's runtime name (via the jOOQ parent table's {@code getName()}) so recursive /
            // self-referential subselects never shadow each other's aliases. For the base (outermost)
            // call, parent.getName() is the raw table name; each nested call accumulates the prefix,
            // giving globally unique aliases at every depth. Every Hop (and the
            // unreachable-here LiftedHop) exposes targetTable() through HasTargetTable.
            // Invariant (R379): the terminal hop's targetTable equals the field return type's
            // @table, and every condition method's concretely-typed parameters match the aliases
            // passed here — both asserted at build time in BuildContext.parsePath (Check 1 / 2),
            // so terminalAlias feeds a $fields overload typed for the right table and
            // emitTwoArgMethodCall never hands a mistyped alias to a concrete parameter.
            for (int i = 0; i < path.size(); i++) {
                JoinStep.HasTargetTable ht = (JoinStep.HasTargetTable) path.get(i);
                ClassName jooqTableClass = ht.targetTable().tableClass();
                // Materialization routes through the shared TableExpr switch (R435): a catalog
                // hop declares Tables.X, a routine hop declares Routines.m(<bound args>) — the
                // correlated call reads the previous node's alias (the parent at hop 0).
                String previousAlias = i == 0 ? parentAlias : aliases.get(i - 1);
                code.addStatement("$T $L = $L.as($L.getName() + $S)",
                    jooqTableClass, aliases.get(i),
                    JoinPathEmitter.emitTableExpression(path.get(i), previousAlias,
                        new ArgumentValueSource.FromSelectedField(sfName)),
                    parentAlias, "_" + aliases.get(i));
            }
        }

        // R330: declare an aliased FK-target table local per join hop for every FK-target
        // @nodeId override @condition among the user filters, so buildInnerSelect can emit each as
        // a correlated EXISTS against that alias instead of mis-passing the field's own table. This
        // arm recurses (self-referential nested projections), so the SQL alias is runtime-prefixed
        // onto the terminal alias's getName(), matching the hop aliases above.
        Map<WhereFilter, List<String>> fkTargetAliases =
            FkTargetConditionEmitter.declareAliases(code, tf.filters(), terminalAlias, true);

        // R424: pre-lift any converter-backed list filter arg into a `<name>Keys` local (read by the
        // JooqConvert list arm), routed through the field's own SelectedField. Without this the arm
        // would reference an undeclared local; the (List<String>) cast is why the $fields host stamps
        // @SuppressWarnings (see TypeClassGenerator).
        ArgCallEmitter.emitJooqConvertKeyLifts(code, tf.filters(), new ArgumentValueSource.FromSelectedField(sfName));

        // Assemble the inner SELECT.
        CodeBlock innerSelect = buildInnerSelect(tf, path, aliases, terminalAlias, typeClass, parentAlias, sfName, registry, fkTargetAliases);

        // Both cardinalities use DSL.multiset(...) uniformly. The single-cardinality path adds
        // .limit(1) to the inner SELECT (inside buildInnerSelect) and the registered DataFetcher
        // unwraps the Result to its first record (or null). Using DSL.multiset over DSL.row avoids
        // jOOQ's alias-reference-flattening behavior on nested aliased fields inside RowN values.
        code.addStatement("fields.add($T.multiset($L).as($S))", DSL, innerSelect, tf.name());
        return code.build();
    }

    /**
     * Builds the inner correlated subquery expression: {@code DSL.select(...).from(...).join(...)
     * .where(...).orderBy(...).limit(...)}. The outer caller wraps this uniformly in
     * {@code DSL.multiset(...)} for both cardinalities; single cardinality caps at {@code .limit(1)}
     * here and is unwrapped on the read side.
     */
    private static CodeBlock buildInnerSelect(ChildField.TableField tf, List<JoinStep> path,
            List<String> aliases, String terminalAlias, ClassName typeClass,
            String parentAlias, String sfName, CompositeDecodeHelperRegistry registry,
            Map<WhereFilter, List<String>> fkTargetAliases) {
        boolean singleCardinality = tf.returnType().wrapper() instanceof FieldWrapper.Single;

        var sel = CodeBlock.builder();
        // SELECT projection: always unwrapped $fields(...) fed into DSL.multiset at the outer wrap.
        // Invariant (R424): `env` is threaded onward into the nested $fields call unchanged — that is
        // correct. Each nested level re-derives its own SelectedField (the switch loop's `sfN` local),
        // and env is needed there only for request-scoped context reads (ContextArg), which
        // legitimately read the ancestor env. Do NOT "fix" this env to the SelectedField: only the
        // field's own runtime *argument* reads route through the SelectedField (via ArgumentValueSource
        // .FromSelectedField), and those are emitted here in this arm, not down the recursion.
        sel.add("$T.select($T.$$fields($L.getSelectionSet(), $L, env))",
            DSL, typeClass, sfName, terminalAlias);

        // FROM: terminal hop's aliased table.
        sel.add("\n        .from($L)", terminalAlias);

        // JOIN chain: walking from terminal back towards step 0. Bridging steps dispatch on the
        // hop's identity — FK hops use .onKey(FK), condition hops use .on(method(prevAlias, alias)).
        // The alias being joined IN is the previous-step's alias (the one closer to step 0).
        for (int i = path.size() - 1; i >= 1; i--) {
            JoinStep bridging = path.get(i);
            String prevAlias = aliases.get(i - 1);
            switch (bridging) {
                case JoinStep.Hop hop -> {
                    switch (hop.on()) {
                        case On.ColumnPairs cp -> sel.add("\n        .join($L).onKey($T.$L)",
                            prevAlias, cp.fk().keysClass(), cp.fk().constantName());
                        case On.Predicate pred -> sel.add("\n        .join($L).on($L)",
                            prevAlias, JoinPathEmitter.emitTwoArgMethodCall(pred.condition(), prevAlias, aliases.get(i)));
                        // A lateral routine hop at bridging position is the multi-node chain
                        // (routine-then-hops / sandwich), which classifies as typed Deferred
                        // (R435); only the single-node chain (hop 0) emits today.
                        case On.Lateral ignored -> throw new IllegalStateException(
                            "a lateral routine hop cannot appear at bridging position; "
                            + "multi-node routine chains classify as typed Deferred (R435)");
                    }
                }
                case JoinStep.LiftedHop ignored -> throw new IllegalStateException(
                    "LiftedHop should not appear in an @reference-composed path; this path is "
                    + "reserved for the single-hop @sourceRow shape consumed by SplitRowsMethodEmitter");
            }
        }

        // WHERE: step 0's correlation against parent (sealed switch on parentCorrelation),
        // then whereFilter methods, then user filters. OnFkSlots: emit the slot-based
        // predicate. OnConditionJoin: the condition method is the correlation predicate
        // (SQL-equivalent to an ON clause for the bridging step that joined firstAlias in).
        var where = CodeBlock.builder();
        if (path.isEmpty()) {
            // Standalone shape: no parent correlation (parentCorrelation is null here). Seed
            // noCondition() so the user filters compose via .and(); short-circuit before the
            // exhaustive parentCorrelation switch, which has no empty-path arm.
            where.add("$T.noCondition()", DSL);
        } else {
            String firstAlias = aliases.get(0);
            switch (tf.parentCorrelation()) {
                case ParentCorrelation.OnFkSlots fk ->
                    where.add("$L", JoinPathEmitter.emitCorrelationWhere(fk.slots(), firstAlias, parentAlias));
                case ParentCorrelation.OnConditionJoin cj ->
                    where.add("$L", JoinPathEmitter.emitTwoArgMethodCall(cj.condition(), parentAlias, firstAlias));
                // R435: the lateral routine call is correlated through its arguments (the
                // alias-declaration loop rendered the parent columns into the call), so the
                // step-0 WHERE contributes nothing.
                case ParentCorrelation.OnLateralArgs ignored ->
                    where.add("$T.noCondition()", DSL);
            }
        }
        for (JoinStep step : path) {
            if (step instanceof JoinStep.Hop hop && hop.filter() != null) {
                String srcAlias = resolveSourceAlias(path, aliases, hop, parentAlias);
                where.add("\n        .and($L)",
                    JoinPathEmitter.emitTwoArgMethodCall(hop.filter(), srcAlias, aliasForStep(path, aliases, hop)));
            }
        }
        for (WhereFilter f : tf.filters()) {
            where.add("\n        .and($L)",
                FkTargetConditionEmitter.emitTerm(new TypeFetcherEmissionContext(), f, terminalAlias, registry, null, fkTargetAliases,
                    new ArgumentValueSource.FromSelectedField(sfName)));
        }
        sel.add("\n        .where($L)", where.build());

        // ORDER BY (Fixed only for C3; Argument/None have no output).
        if (tf.orderBy() instanceof OrderBySpec.Fixed fixed && !fixed.columns().isEmpty()) {
            var orderParts = CodeBlock.builder();
            for (int i = 0; i < fixed.columns().size(); i++) {
                if (i > 0) orderParts.add(", ");
                var col = fixed.columns().get(i);
                orderParts.add("$L.$L.$L()",
                    terminalAlias, col.column().javaName(), col.direction().jooqMethodName());
            }
            sel.add("\n        .orderBy($L)", orderParts.build());
        }

        // LIMIT: single cardinality always caps at 1 (single-record unwrap). Otherwise honour
        // pagination.first as an optional runtime argument.
        if (singleCardinality) {
            sel.add("\n        .limit(1)");
        } else if (tf.pagination() != null && tf.pagination().first() != null) {
            // R424: read `first` off the inline field's own SelectedField, not the ancestor env
            // (which has no such argument, so the old env.getArgument read silently dropped the
            // pagination limit). The (Integer) cast is checked — no unchecked warning.
            sel.add("\n        .limit($L.getArguments().get($S) == null ? $T.MAX_VALUE : ($T) $L.getArguments().get($S))",
                sfName, "first",
                Integer.class, Integer.class, sfName, "first");
        }

        return sel.build();
    }

    /** Source alias for a hop's filter call: the previous hop's alias, or the parent alias for step 0. */
    private static String resolveSourceAlias(List<JoinStep> path, List<String> aliases, JoinStep.Hop step, String parentAlias) {
        int idx = path.indexOf(step);
        return idx == 0 ? parentAlias : aliases.get(idx - 1);
    }

    /** The alias of a specific hop — lookup by object identity within the path list. */
    private static String aliasForStep(List<JoinStep> path, List<String> aliases, JoinStep step) {
        int idx = path.indexOf(step);
        return aliases.get(idx);
    }
}
