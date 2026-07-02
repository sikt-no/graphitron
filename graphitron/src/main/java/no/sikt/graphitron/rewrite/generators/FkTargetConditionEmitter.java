package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.model.FkTargetConditionFilter;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.WhereFilter;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.rewrite.generators.GeneratorUtils.DSL;

/**
 * Shared emission for {@code @condition} filter terms, factored out so every WHERE-emitting site
 * (the {@link QueryConditionsGenerator} shim plus the inline fetcher emitters) treats an FK-target
 * {@code @nodeId} override condition identically (R330).
 *
 * <p>A plain {@link WhereFilter} hands the developer method the caller's own table local. An
 * {@link FkTargetConditionFilter} cannot: its method expects the FK-<em>target</em> table {@code X}
 * reached through a catalog foreign key, not the input's own table. Passing the own table compiles
 * to {@code SomeConditions.method(ownTable, ...)} where {@code method(X, ...)} is declared, which
 * fails at the consumer's javac. Instead the term is emitted as a correlated
 * {@code DSL.exists(DSL.selectOne().from(X).where(<correlation> AND method(X, ...)))} so the method
 * receives an alias for {@code X} while the outer row set is preserved.
 *
 * <p>jOOQ requires a declared Java local to reference an aliased table's columns, so the EXISTS
 * cannot introduce its target alias inline; callers first declare the aliases as statements via
 * {@link #declareAliases} (mirroring how the inline emitters already declare their join-hop
 * aliases), then read them back through the returned map when composing the WHERE expression with
 * {@link #emitTerm}. Sites that recurse (self-referential nested projections) pass
 * {@code runtimePrefixedSqlAlias = true} so the SQL alias is suffixed onto the base alias's runtime
 * {@code getName()}, keeping it unique across depth the same way the hop aliases are.
 */
public final class FkTargetConditionEmitter {

    private FkTargetConditionEmitter() {}

    /**
     * Declares one aliased jOOQ table local per FK-target join hop, for every
     * {@link FkTargetConditionFilter} in {@code filters}, into {@code stmts}. Plain filters
     * contribute nothing. Returns a map from each FK-target filter to its per-hop Java local names
     * (terminal hop last), consumed by {@link #emitTerm}.
     *
     * @param stmts                  statement builder of the enclosing method/arm; alias
     *                               declarations are appended here, before the WHERE expression
     * @param filters                the filter list being emitted
     * @param baseAliasLocal         the Java local for the table the methods would otherwise be
     *                               called against; used to prefix Java local names (uniqueness
     *                               within the enclosing method) and, when requested, the SQL alias
     * @param runtimePrefixedSqlAlias when {@code true}, the SQL alias is
     *                               {@code <baseAliasLocal>.getName() + "_<suffix>"} (unique across
     *                               recursion depth); when {@code false}, the SQL alias is the
     *                               static suffix (sufficient for non-recursive method scopes)
     */
    public static Map<WhereFilter, List<String>> declareAliases(
            CodeBlock.Builder stmts, List<? extends WhereFilter> filters,
            String baseAliasLocal, boolean runtimePrefixedSqlAlias) {
        var aliasesByFilter = new IdentityHashMap<WhereFilter, List<String>>();
        int filterIndex = 0;
        for (var filter : filters) {
            if (filter instanceof FkTargetConditionFilter fk) {
                var path = fk.joinPath();
                var hopAliases = new ArrayList<String>(path.size());
                for (int i = 0; i < path.size(); i++) {
                    var ht = (JoinStep.HasTargetTable) path.get(i);
                    String javaLocal = baseAliasLocal + "_fkt" + filterIndex + "_" + i;
                    String sqlSuffix = "fkt" + filterIndex + "_" + i;
                    if (runtimePrefixedSqlAlias) {
                        stmts.addStatement("$T $L = $T.$L.as($L.getName() + $S)",
                            ht.targetTable().tableClass(), javaLocal,
                            ht.targetTable().constantsClass(), ht.targetTable().javaFieldName(),
                            baseAliasLocal, "_" + sqlSuffix);
                    } else {
                        stmts.addStatement("$T $L = $T.$L.as($S)",
                            ht.targetTable().tableClass(), javaLocal,
                            ht.targetTable().constantsClass(), ht.targetTable().javaFieldName(),
                            javaLocal);
                    }
                    hopAliases.add(javaLocal);
                }
                aliasesByFilter.put(filter, hopAliases);
                filterIndex++;
            }
        }
        return aliasesByFilter;
    }

    /**
     * Emits one composed WHERE term. The plain arm hands the developer method {@code baseAlias};
     * the FK-target arm emits the correlated {@code EXISTS} against the alias declared by
     * {@link #declareAliases} (read from {@code fkTargetAliases}).
     *
     * <p>{@code source} (R424) routes the runtime argument-value reads inside the composed call:
     * root/{@code @splitQuery} sites pass {@link ArgumentValueSource.Env} (byte-identical output);
     * the two inline emitters pass {@link ArgumentValueSource.FromSelectedField} so the arguments
     * are read off the inline field's own {@code SelectedField}, not the ancestor fetcher's env.
     */
    public static CodeBlock emitTerm(TypeFetcherEmissionContext ctx, WhereFilter filter,
            String baseAlias, CompositeDecodeHelperRegistry registry,
            Map<String, String> liftedOuters, Map<WhereFilter, List<String>> fkTargetAliases,
            ArgumentValueSource source) {
        if (filter instanceof FkTargetConditionFilter fk) {
            return emitFkTargetExists(ctx, fk, baseAlias, registry, liftedOuters, fkTargetAliases.get(fk), source);
        }
        var callArgs = ArgCallEmitter.buildCallArgs(
            ctx, filter.callParams(), filter.className(), baseAlias, registry, liftedOuters, source);
        return CodeBlock.of("$T.$L($L)",
            ClassName.bestGuess(filter.className()), filter.methodName(), callArgs);
    }

    /**
     * Emits the correlated {@code EXISTS} for an FK-target {@code @nodeId} override condition:
     * {@code DSL.exists(DSL.selectOne().from(X).where(<correlation>.and(<method>(X, args))))}.
     * The correlation ties the first hop's target alias to {@code baseAlias}; multi-hop paths walk
     * the FK chain back from the terminal target alias (mirroring {@link InlineTableFieldEmitter}'s
     * JOIN chain). The developer method runs against the terminal (FK-target) alias.
     */
    private static CodeBlock emitFkTargetExists(TypeFetcherEmissionContext ctx,
            FkTargetConditionFilter fk, String baseAlias, CompositeDecodeHelperRegistry registry,
            Map<String, String> liftedOuters, List<String> hopAliases, ArgumentValueSource source) {
        var path = fk.joinPath();
        String terminalAlias = hopAliases.get(hopAliases.size() - 1);

        var sel = CodeBlock.builder();
        sel.add("$T.selectOne()", DSL);
        sel.add("\n        .from($L)", terminalAlias);
        for (int i = path.size() - 1; i >= 1; i--) {
            var bridging = path.get(i);
            String prevAlias = hopAliases.get(i - 1);
            if (bridging instanceof JoinStep.FkJoin fkHop) {
                sel.add("\n        .join($L).onKey($T.$L)",
                    prevAlias, fkHop.fk().keysClass(), fkHop.fk().constantName());
            } else {
                throw new IllegalStateException(
                    "FK-target @nodeId override join hop " + i + " on '" + fk.methodName()
                    + "' is not an FkJoin (" + bridging.getClass().getSimpleName()
                    + "); the validator must reject unresolved FK-target overrides before emission");
            }
        }
        if (!(path.get(0) instanceof JoinStep.FkJoin firstHop)) {
            throw new IllegalStateException(
                "FK-target @nodeId override first hop on '" + fk.methodName()
                + "' is not an FkJoin; the validator must reject unresolved FK-target overrides before emission");
        }
        var correlation = JoinPathEmitter.emitCorrelationWhere(firstHop, hopAliases.get(0), baseAlias);
        var callArgs = ArgCallEmitter.buildCallArgs(
            ctx, fk.callParams(), fk.className(), terminalAlias, registry, liftedOuters, source);
        var devCall = CodeBlock.of("$T.$L($L)", ClassName.bestGuess(fk.className()), fk.methodName(), callArgs);
        sel.add("\n        .where($L.and($L))", correlation, devCall);

        return CodeBlock.of("$T.exists($L)", DSL, sel.build());
    }
}
