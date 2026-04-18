package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.RewriteConfig;
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
 * nested type, wrapped as {@code DSL.multiset(...)} (list cardinality) or
 * {@code DSL.field(DSL.select(DSL.row(...)))} (single cardinality).
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
     * @param parentTable  the parent type's {@link TableRef} (used for FK-direction branching)
     * @param parentAlias  the local variable name for the parent alias in the generated code
     *                     (currently always {@code "table"} — {@link TypeClassGenerator}'s
     *                     {@code $fields} signature parameter)
     */
    public static CodeBlock buildSwitchArmBody(ChildField.TableField tf, TableRef parentTable, String parentAlias) {
        if (JoinPathEmitter.hasConditionJoin(tf.joinPath())) {
            return CodeBlock.builder()
                .addStatement("throw new $T($S)",
                    UnsupportedOperationException.class,
                    "Inline TableField '" + tf.parentTypeName() + "." + tf.name() + "' with a condition-join step "
                    + "cannot be emitted until classification-vocabulary item 5 resolves condition-method target tables")
                .build();
        }
        return buildFkOnlyArm(tf, parentTable, parentAlias);
    }

    private static CodeBlock buildFkOnlyArm(ChildField.TableField tf, TableRef parentTable, String parentAlias) {
        List<JoinStep> path = tf.joinPath();
        TableRef terminalTable = tf.returnType().table();
        List<String> aliases = JoinPathEmitter.generateAliases(path, terminalTable);
        String terminalAlias = aliases.get(aliases.size() - 1);
        ClassName tablesClass = ClassName.get(RewriteConfig.getGeneratedJooqPackage(), "Tables");
        ClassName keysClass = ClassName.get(RewriteConfig.getGeneratedJooqPackage(), "Keys");
        ClassName typeClass = ClassName.get(
            RewriteConfig.outputPackage() + ".rewrite.types",
            tf.returnType().returnTypeName());

        var code = CodeBlock.builder();

        // Declare aliased jOOQ tables for each hop.
        for (int i = 0; i < path.size(); i++) {
            JoinStep.FkJoin fk = (JoinStep.FkJoin) path.get(i);
            ClassName jooqTableClass = ClassName.get(
                RewriteConfig.getGeneratedJooqPackage() + ".tables",
                fk.targetTable().javaClassName());
            code.addStatement("$T $L = $T.$L.as($S)",
                jooqTableClass, aliases.get(i), tablesClass, fk.targetTable().javaFieldName(), aliases.get(i));
        }

        // Assemble the inner SELECT.
        CodeBlock innerSelect = buildInnerSelect(tf, path, aliases, terminalAlias, typeClass, keysClass, parentAlias, parentTable);

        // Wrap by cardinality.
        FieldWrapper wrapper = tf.returnType().wrapper();
        if (wrapper instanceof FieldWrapper.List) {
            code.addStatement("fields.add($T.multiset($L).as($S))", DSL, innerSelect, tf.name());
        } else {
            // Single — DSL.field(DSL.select(DSL.row(...))).as("name")
            code.addStatement("fields.add($T.field($L).as($S))", DSL, innerSelect, tf.name());
        }
        return code.build();
    }

    /**
     * Builds the inner correlated subquery expression: {@code DSL.select(...).from(...).join(...)
     * .where(...).orderBy(...).limit(...)}. For list cardinality the outer caller wraps this in
     * {@code DSL.multiset(...)}; for single cardinality, in {@code DSL.field(...)} with an
     * explicit {@code DSL.row(...)} projection inside the SELECT.
     */
    private static CodeBlock buildInnerSelect(ChildField.TableField tf, List<JoinStep> path,
            List<String> aliases, String terminalAlias, ClassName typeClass, ClassName keysClass,
            String parentAlias, TableRef parentTable) {
        boolean singleCardinality = tf.returnType().wrapper() instanceof FieldWrapper.Single;

        var sel = CodeBlock.builder();
        // SELECT projection: either DSL.row($fields(...)) for single, or $fields(...) unwrapped for multiset.
        if (singleCardinality) {
            sel.add("$T.select($T.row($T.$$fields(sf.getSelectionSet(), $L, env)))",
                DSL, DSL, typeClass, terminalAlias);
        } else {
            sel.add("$T.select($T.$$fields(sf.getSelectionSet(), $L, env))",
                DSL, typeClass, terminalAlias);
        }

        // FROM: terminal hop's aliased table.
        sel.add("\n        .from($L)", terminalAlias);

        // JOIN chain: walking from terminal back towards step 0. Each bridging step's FK connects
        // the next hop's alias in.
        for (int i = path.size() - 1; i >= 1; i--) {
            JoinStep.FkJoin bridging = (JoinStep.FkJoin) path.get(i);
            String prevAlias = aliases.get(i - 1);
            sel.add("\n        .join($L).onKey($T.$L)",
                prevAlias, keysClass, bridging.fkJavaConstant());
        }

        // WHERE: step 0's correlation against parent, then whereFilter methods, then user filters.
        JoinStep.FkJoin first = (JoinStep.FkJoin) path.get(0);
        String firstAlias = aliases.get(0);
        CodeBlock correlation = JoinPathEmitter.emitCorrelationWhere(first, firstAlias, parentAlias, parentTable);
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
                ArgCallEmitter.buildCallArgs(f.callParams(), f.className()));
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

        // LIMIT (from pagination.first — treated as optional runtime arg).
        if (tf.pagination() != null && tf.pagination().first() != null) {
            sel.add("\n        .limit(env.getArgument($S) == null ? $T.MAX_VALUE : ($T) env.getArgument($S))",
                tf.pagination().first().name(),
                Integer.class, Integer.class, tf.pagination().first().name());
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
