package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.model.CallSiteCompaction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.On;
import no.sikt.graphitron.rewrite.model.ParentCorrelation;

import java.util.List;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.DSL;

/**
 * Builds the switch-arm body for one inline {@link ChildField.ColumnReferenceField} in
 * {@link TypeClassGenerator}'s {@code $fields} method. Emits a correlated subquery projecting a
 * single column on the terminal joined table, wrapped as a scalar {@code DSL.field(<select>)} term.
 *
 * <p>Mirrors {@link InlineTableFieldEmitter}'s shape with the inner SELECT collapsed to a
 * single column — the result is a scalar value, not a row/multiset. Handles both
 * {@link JoinStep.FkJoin} and {@link JoinStep.ConditionJoin} hops; step-0 parent correlation
 * reads {@link ChildField.ColumnReferenceField#parentCorrelation()} via a sealed switch.
 *
 * <p>{@link CallSiteCompaction.NodeIdEncodeKeys} (rooted-at-parent NodeId reference) is
 * classifier-validated unreachable here (deferred to
 * {@code nodeidreferencefield-join-projection-form}); the defensive guard below throws
 * {@link IllegalStateException} so a classifier regression fails loudly rather than producing
 * runtime-stub SQL.
 */
public final class InlineColumnReferenceFieldEmitter {

    private InlineColumnReferenceFieldEmitter() {}

    /**
     * Returns the {@code {...}} body to place inside a switch arm. Does <em>not</em> include the
     * {@code case "name" ->} prefix — the caller composes that.
     *
     * @param crf          the column-reference field to emit
     * @param parentAlias  the local variable name for the parent alias in the generated code
     *                     ({@link TypeClassGenerator}'s {@code $fields} signature parameter,
     *                     the literal {@code "table"})
     * @param sfName       the caller-scope {@code SelectedField} variable name in scope at the
     *                     emit site. Unused by this emitter (a scalar projection has no nested
     *                     selection set) but kept in the signature for symmetry with
     *                     {@link InlineTableFieldEmitter}.
     */
    public static CodeBlock buildSwitchArmBody(ChildField.ColumnReferenceField crf, String parentAlias,
            String sfName, String outputPackage) {
        if (crf.compaction() instanceof CallSiteCompaction.NodeIdEncodeKeys) {
            throw new IllegalStateException(
                "Inline ColumnReferenceField '" + crf.parentTypeName() + "." + crf.name()
                + "' with NodeIdEncodeKeys compaction must be rejected by the validator before emission");
        }
        List<JoinStep> path = crf.joinPath();
        if (path.isEmpty()) {
            // Standalone shape: an empty joinPath means start table == target table, so the
            // referenced column lives on the parent row's own table (parentCorrelation is null per
            // ParentCorrelation.checkCarrierInvariant). No join, correlation, or subquery is needed
            // — project the column directly off the parent alias, aliased to the GraphQL field name.
            var direct = CodeBlock.builder();
            direct.addStatement("fields.add($L.$L.as($S))", parentAlias, crf.column().javaName(), crf.name());
            return direct.build();
        }
        List<String> aliases = JoinPathEmitter.generateAliases(path, null);
        String terminalAlias = aliases.get(aliases.size() - 1);

        var code = CodeBlock.builder();

        // Declare aliased jOOQ tables for each hop. Alias strings are prefixed with the parent
        // alias's runtime name so recursive / self-referential subselects never shadow each other's
        // aliases — same pattern as InlineTableFieldEmitter. HasTargetTable folds the FkJoin /
        // ConditionJoin permits onto one targetTable read.
        for (int i = 0; i < path.size(); i++) {
            JoinStep.HasTargetTable ht = (JoinStep.HasTargetTable) path.get(i);
            ClassName jooqTableClass = ht.targetTable().tableClass();
            code.addStatement("$T $L = $T.$L.as($L.getName() + $S)",
                jooqTableClass, aliases.get(i), ht.targetTable().constantsClass(), ht.targetTable().javaFieldName(),
                parentAlias, "_" + aliases.get(i));
        }

        CodeBlock innerSelect = buildInnerSelect(crf, path, aliases, terminalAlias, parentAlias);

        // Wrap as a scalar field with DSL.field(<subquery>) — single-column SELECT, not multiset.
        code.addStatement("fields.add($T.field($L).as($S))", DSL, innerSelect, crf.name());
        return code.build();
    }

    private static CodeBlock buildInnerSelect(ChildField.ColumnReferenceField crf, List<JoinStep> path,
            List<String> aliases, String terminalAlias, String parentAlias) {
        var sel = CodeBlock.builder();
        // SELECT projection: the single terminal column on the terminal alias.
        sel.add("$T.select($L.$L)", DSL, terminalAlias, crf.column().javaName());

        // FROM: terminal hop's aliased table.
        sel.add("\n        .from($L)", terminalAlias);

        // JOIN chain: walk from terminal back towards step 0. Dispatches on step type so
        // condition joins emit .on(method(...)) and FK joins keep .onKey(FK).
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
                    }
                }
                case JoinStep.FkJoin fk -> sel.add("\n        .join($L).onKey($T.$L)",
                    prevAlias, fk.fk().keysClass(), fk.fk().constantName());
                case JoinStep.ConditionJoin cj -> sel.add("\n        .join($L).on($L)",
                    prevAlias, JoinPathEmitter.emitTwoArgMethodCall(cj.condition(), prevAlias, aliases.get(i)));
                case JoinStep.LiftedHop ignored -> throw new IllegalStateException(
                    "LiftedHop should not appear in an @reference-composed path");
            }
        }

        // WHERE: step-0 correlation reads parentCorrelation via a sealed switch (OnFkSlots
        // emits the slot-based predicate; OnConditionJoin emits the condition method call).
        // Then per-hop whereFilter methods append.
        String firstAlias = aliases.get(0);
        var where = CodeBlock.builder();
        switch (crf.parentCorrelation()) {
            case ParentCorrelation.OnFkSlots fk ->
                where.add("$L", JoinPathEmitter.emitCorrelationWhere((JoinStep.FkJoin) fk.firstHop(), firstAlias, parentAlias));
            case ParentCorrelation.OnConditionJoin cj ->
                where.add("$L", JoinPathEmitter.emitTwoArgMethodCall(cj.firstHop().condition(), parentAlias, firstAlias));
        }
        for (JoinStep step : path) {
            if (step instanceof JoinStep.FkJoin fk && fk.whereFilter() != null) {
                String srcAlias = resolveSourceAlias(path, aliases, fk, parentAlias);
                where.add(".and($L)",
                    JoinPathEmitter.emitTwoArgMethodCall(fk.whereFilter(), srcAlias, aliasForStep(path, aliases, fk)));
            }
        }
        sel.add("\n        .where($L)", where.build());

        // Single-column scalar: always cap at 1 row.
        sel.add("\n        .limit(1)");

        return sel.build();
    }

    private static String resolveSourceAlias(List<JoinStep> path, List<String> aliases, JoinStep.FkJoin step, String parentAlias) {
        int idx = path.indexOf(step);
        return idx == 0 ? parentAlias : aliases.get(idx - 1);
    }

    private static String aliasForStep(List<JoinStep> path, List<String> aliases, JoinStep step) {
        int idx = path.indexOf(step);
        return aliases.get(idx);
    }
}
