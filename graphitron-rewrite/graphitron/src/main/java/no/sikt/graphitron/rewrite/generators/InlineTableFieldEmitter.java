package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.WhereFilter;

import java.util.List;

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
 * <p>{@link JoinStep.ConditionJoin} anywhere in the path triggers a runtime-throwing stub arm —
 * target-table resolution for condition joins is owned by classification-vocabulary item 5.
 * Compilation and schema-classifier coverage land in G5; runtime execution lands with item 5.
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
    public static CodeBlock buildSwitchArmBody(ChildField.TableField tf, String parentAlias, String sfName, String outputPackage) {
        if (JoinPathEmitter.hasConditionJoin(tf.joinPath())) {
            return CodeBlock.builder()
                .addStatement("throw new $T($S)",
                    UnsupportedOperationException.class,
                    "Inline TableField '" + tf.parentTypeName() + "." + tf.name() + "' with a condition-join step "
                    + "cannot be emitted until classification-vocabulary item 5 resolves condition-method target tables")
                .build();
        }
        return buildFkOnlyArm(tf, parentAlias, sfName, outputPackage);
    }

    private static CodeBlock buildFkOnlyArm(ChildField.TableField tf, String parentAlias, String sfName, String outputPackage) {
        List<JoinStep> path = tf.joinPath();
        TableRef terminalTable = tf.returnType().table();
        List<String> aliases = JoinPathEmitter.generateAliases(path, terminalTable);
        String terminalAlias = aliases.get(aliases.size() - 1);
        ClassName typeClass = ClassName.get(outputPackage + ".types", tf.returnType().returnTypeName());

        var code = CodeBlock.builder();

        // Declare aliased jOOQ tables for each hop. Alias strings are prefixed with the parent
        // alias's runtime name (via the jOOQ parent table's {@code getName()}) so recursive /
        // self-referential subselects never shadow each other's aliases. For the base (outermost)
        // call, parent.getName() is the raw table name; each nested call accumulates the prefix,
        // giving globally unique aliases at every depth.
        for (int i = 0; i < path.size(); i++) {
            JoinStep.FkJoin fk = (JoinStep.FkJoin) path.get(i);
            ClassName jooqTableClass = fk.targetTable().tableClass();
            code.addStatement("$T $L = $T.$L.as($L.getName() + $S)",
                jooqTableClass, aliases.get(i), fk.targetTable().constantsClass(), fk.targetTable().javaFieldName(),
                parentAlias, "_" + aliases.get(i));
        }

        // Assemble the inner SELECT.
        CodeBlock innerSelect = buildInnerSelect(tf, path, aliases, terminalAlias, typeClass, parentAlias, sfName);

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
            String parentAlias, String sfName) {
        boolean singleCardinality = tf.returnType().wrapper() instanceof FieldWrapper.Single;

        var sel = CodeBlock.builder();
        // SELECT projection: always unwrapped $fields(...) fed into DSL.multiset at the outer wrap.
        sel.add("$T.select($T.$$fields($L.getSelectionSet(), $L, env))",
            DSL, typeClass, sfName, terminalAlias);

        // FROM: terminal hop's aliased table.
        sel.add("\n        .from($L)", terminalAlias);

        // JOIN chain: walking from terminal back towards step 0. Each bridging step's FK connects
        // the next hop's alias in.
        for (int i = path.size() - 1; i >= 1; i--) {
            JoinStep.FkJoin bridging = (JoinStep.FkJoin) path.get(i);
            String prevAlias = aliases.get(i - 1);
            sel.add("\n        .join($L).onKey($T.$L)",
                prevAlias, bridging.fk().keysClass(), bridging.fk().constantName());
        }

        // WHERE: step 0's correlation against parent, then whereFilter methods, then user filters.
        JoinStep.FkJoin first = (JoinStep.FkJoin) path.get(0);
        String firstAlias = aliases.get(0);
        CodeBlock correlation = JoinPathEmitter.emitCorrelationWhere(first, firstAlias, parentAlias);
        var where = CodeBlock.builder().add("$L", correlation);
        for (JoinStep step : path) {
            if (step instanceof JoinStep.FkJoin fk && fk.whereFilter() != null) {
                String srcAlias = resolveSourceAlias(path, aliases, fk, parentAlias);
                where.add(".and($L)",
                    JoinPathEmitter.emitTwoArgMethodCall(fk.whereFilter(), srcAlias, aliasForStep(path, aliases, fk)));
            }
        }
        for (WhereFilter f : tf.filters()) {
            where.add(".and($T.$L($L))",
                ClassName.bestGuess(f.className()), f.methodName(),
                ArgCallEmitter.buildCallArgs(new TypeFetcherEmissionContext(), f.callParams(), f.className(), terminalAlias));
        }
        sel.add("\n        .where($L)", where.build());

        // ORDER BY (Fixed only for C3; Argument/None have no output).
        if (tf.orderBy() instanceof OrderBySpec.Fixed fixed && !fixed.columns().isEmpty()) {
            var orderParts = CodeBlock.builder();
            for (int i = 0; i < fixed.columns().size(); i++) {
                if (i > 0) orderParts.add(", ");
                orderParts.add("$L.$L.$L()",
                    terminalAlias, fixed.columns().get(i).column().javaName(), fixed.jooqMethodName());
            }
            sel.add("\n        .orderBy($L)", orderParts.build());
        }

        // LIMIT: single cardinality always caps at 1 (single-record unwrap). Otherwise honour
        // pagination.first as an optional runtime argument.
        if (singleCardinality) {
            sel.add("\n        .limit(1)");
        } else if (tf.pagination() != null && tf.pagination().first() != null) {
            sel.add("\n        .limit(env.getArgument($S) == null ? $T.MAX_VALUE : ($T) env.getArgument($S))",
                "first",
                Integer.class, Integer.class, "first");
        }

        return sel.build();
    }

    /** Source alias for a hop's whereFilter call: the previous hop's alias, or the parent alias for step 0. */
    private static String resolveSourceAlias(List<JoinStep> path, List<String> aliases, JoinStep.FkJoin step, String parentAlias) {
        int idx = path.indexOf(step);
        return idx == 0 ? parentAlias : aliases.get(idx - 1);
    }

    /** The alias of a specific hop — lookup by object identity within the path list. */
    private static String aliasForStep(List<JoinStep> path, List<String> aliases, JoinStep step) {
        int idx = path.indexOf(step);
        return aliases.get(idx);
    }
}
