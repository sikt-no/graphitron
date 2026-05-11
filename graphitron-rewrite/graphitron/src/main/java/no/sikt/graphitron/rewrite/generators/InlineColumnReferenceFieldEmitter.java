package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.model.CallSiteCompaction;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.JoinStep;

import java.util.List;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.DSL;

/**
 * Builds the switch-arm body for one inline {@link ChildField.ColumnReferenceField} in
 * {@link TypeClassGenerator}'s {@code $fields} method. Emits a correlated subquery projecting a
 * single column on the terminal joined table, wrapped as a scalar {@code DSL.field(<select>)} term.
 *
 * <p>Mirrors {@link InlineTableFieldEmitter}'s FK-only path with the inner SELECT collapsed to a
 * single column — the result is a scalar value, not a row/multiset.
 *
 * <p>Two shapes are classifier-validated unreachable here:
 * {@link CallSiteCompaction.NodeIdEncodeKeys} (rooted-at-parent NodeId reference, deferred to
 * {@code nodeidreferencefield-join-projection-form}) and any
 * {@link JoinStep.ConditionJoin} step in the path (deferred to
 * {@code column-reference-on-scalar-field-condition-join}); both are surfaced as
 * {@code Rejection.Deferred} by the validator. The defensive guards below throw
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
        if (JoinPathEmitter.hasConditionJoin(crf.joinPath())) {
            throw new IllegalStateException(
                "Inline ColumnReferenceField '" + crf.parentTypeName() + "." + crf.name()
                + "' with a condition-join step must be rejected by the validator before emission");
        }
        if (crf.compaction() instanceof CallSiteCompaction.NodeIdEncodeKeys) {
            throw new IllegalStateException(
                "Inline ColumnReferenceField '" + crf.parentTypeName() + "." + crf.name()
                + "' with NodeIdEncodeKeys compaction must be rejected by the validator before emission");
        }
        List<JoinStep> path = crf.joinPath();
        List<String> aliases = JoinPathEmitter.generateAliases(path, null);
        String terminalAlias = aliases.get(aliases.size() - 1);

        var code = CodeBlock.builder();

        // Declare aliased jOOQ tables for each hop. Alias strings are prefixed with the parent
        // alias's runtime name so recursive / self-referential subselects never shadow each other's
        // aliases — same pattern as InlineTableFieldEmitter.
        for (int i = 0; i < path.size(); i++) {
            JoinStep.FkJoin fk = (JoinStep.FkJoin) path.get(i);
            ClassName jooqTableClass = fk.targetTable().tableClass();
            code.addStatement("$T $L = $T.$L.as($L.getName() + $S)",
                jooqTableClass, aliases.get(i), fk.targetTable().constantsClass(), fk.targetTable().javaFieldName(),
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

        // JOIN chain: walk from terminal back towards step 0. Same shape as InlineTableFieldEmitter.
        for (int i = path.size() - 1; i >= 1; i--) {
            JoinStep.FkJoin bridging = (JoinStep.FkJoin) path.get(i);
            String prevAlias = aliases.get(i - 1);
            sel.add("\n        .join($L).onKey($T.$L)",
                prevAlias, bridging.fk().keysClass(), bridging.fk().constantName());
        }

        // WHERE: step 0 correlates against the parent alias, then per-hop whereFilter methods.
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
