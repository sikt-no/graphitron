package no.sikt.graphitron.render;

import no.sikt.graphitron.command.GlueCall;
import no.sikt.graphitron.command.PresenceProbe;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import no.sikt.graphitron.model.jooq.ColumnRef;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a {@link PresenceProbe} as one projected column:
 * {@code DSL.when(DSL.and(<every column leaf absent>, DSL.or(<term>, ...)), DSL.inline(1))
 * .as(<alias>)}. A {@code CASE} with no {@code ELSE} rather than a boolean select column, which
 * some dialects lack; the fetcher reads any non-null value as "present". An existence term is a
 * {@link PathFragments#reachExists} over the probed field's own reach, correlated on the row the
 * object is read from; a null term calls the {@code @externalField} method over that row
 * ({@link ExternalFieldCall}). Shared by the projection units' carriers and the batched rows method.
 */
public final class PresenceProbeFragments {

    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");

    private PresenceProbeFragments() {}

    /**
     * Declares the probe's hop-alias locals into {@code code}, each line prefixed by
     * {@code indent}, and returns the probe column's expression. Only an existence term declares
     * locals; {@code correlatedAlias} is typed as the row's generated table class, which a null
     * term's {@code @externalField} method takes. The locals are named
     * {@code presence<term>_<hop>} and their SQL aliases derive from {@code correlatedAlias}'s
     * name, so they cannot meet the correlated row's own alias; sibling {@code EXISTS} scopes may
     * reuse them freely. The caller declares at most one probe per Java block.
     */
    public static CodeBlock declare(CodeBlock.Builder code, PresenceProbe probe, String correlatedAlias,
            String indent) {
        var terms = new ArrayList<CodeBlock>();
        for (int t = 0; t < probe.terms().size(); t++) {
            terms.add(switch (probe.terms().get(t)) {
                case PresenceProbe.ProbeTerm.Exists exists -> exists(code, exists, t, correlatedAlias, indent);
                case PresenceProbe.ProbeTerm.NotNull notNull ->
                    CodeBlock.of("$L.isNotNull()", ExternalFieldCall.of(notNull.method(), correlatedAlias));
            });
        }
        var conjuncts = new ArrayList<CodeBlock>();
        for (var leaf : probe.columnLeaves()) {
            conjuncts.add(leafAbsent(leaf, correlatedAlias));
        }
        conjuncts.add(CodeBlock.of("$T.or($L)", DSL, join(terms)));
        var condition = conjuncts.size() == 1
            ? conjuncts.get(0)
            : CodeBlock.of("$T.and($L)", DSL, join(conjuncts));
        return CodeBlock.of("$T.when($L, $T.inline(1)).as($S)", DSL, condition, DSL, probe.alias());
    }

    /** The {@code t}-th term's correlated {@code EXISTS}, its hop-alias locals declared into {@code code}. */
    private static CodeBlock exists(CodeBlock.Builder code, PresenceProbe.ProbeTerm.Exists term, int t,
            String correlatedAlias, String indent) {
        var hopAliases = new ArrayList<String>(term.reach().size());
        for (int h = 0; h < term.reach().size(); h++) {
            var target = term.reach().hop(h).targetTable();
            String local = "presence" + t + "_" + h;
            code.add(indent);
            code.addStatement("$T $L = $T.$L.as($L.getName() + $S)",
                CatalogRefs.tableClass(target), local,
                CatalogRefs.constantsClass(target), target.javaFieldName(),
                correlatedAlias, "_p" + t + "_" + h);
            hopAliases.add(local);
        }
        String terminal = hopAliases.get(hopAliases.size() - 1);
        var inner = term.targetColumns().isEmpty()
            ? CodeBlock.of("$T.noCondition()", DSL)
            : CodeBlock.of("$T.or($L)", DSL, join(term.targetColumns().stream()
                .map(c -> CodeBlock.of("$L.$L.isNotNull()", terminal, c.javaName())).toList()));
        if (term.filter() != null) {
            inner = CodeBlock.of("$T.and($L, $L)", DSL, inner, glueCall(term.filter(), terminal));
        }
        return PathFragments.reachExists(term.reach(), hopAliases, correlatedAlias, inner);
    }

    /** A column leaf is absent when any of its columns is null. */
    private static CodeBlock leafAbsent(List<ColumnRef> leaf, String alias) {
        var nulls = leaf.stream().map(c -> CodeBlock.of("$L.$L.isNull()", alias, c.javaName())).toList();
        return nulls.size() == 1 ? nulls.get(0) : CodeBlock.of("$T.or($L)", DSL, join(nulls));
    }

    /**
     * The probed field's condition glue call against the probe's terminal alias. Its filters bind
     * request context only (an unselected field has no arguments to serve), so the argument map
     * is empty and a context-reading glue takes the ambient {@code env}.
     */
    private static CodeBlock glueCall(GlueCall glue, String alias) {
        var owner = ClassName.get(glue.method().owner().packageName(), glue.method().owner().simpleName());
        return CodeBlock.of("$T.$L($L, $T.of()$L$L)", owner, glue.method().methodName(), alias,
            ClassName.get("java.util", "Map"), glue.takesEnv() ? ", env" : "", glue.takesDsl() ? ", dsl" : "");
    }

    private static CodeBlock join(List<CodeBlock> parts) {
        return CodeBlock.join(parts, ", ");
    }
}
