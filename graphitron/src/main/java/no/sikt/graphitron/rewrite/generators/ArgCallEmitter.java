package no.sikt.graphitron.rewrite.generators;

import com.palantir.javapoet.CodeBlock;

import java.util.List;

/**
 * The nested argument-map read the mutation emitters share ({@link #nestedMapValueExpr}). The
 * condition-method call surface this class once held retired with call-site convergence:
 * condition extraction renders inside the conditions classes
 * ({@link no.sikt.graphitron.emit.ConditionEmitter}).
 */
public final class ArgCallEmitter {

    private ArgCallEmitter() {}

    /**
     * A null-safe nested-Map value descent reading from a local that already holds a
     * {@code Map<?, ?>} (the mutation emitters' {@code in} / {@code row} argument-value maps). For a
     * single-segment {@code path} the result is {@code mapLocal.get(key)}; for a
     * deeper path it is the {@code instanceof Map<?, ?>} ternary chain
     * {@link no.sikt.graphitron.render.WireMapChain}
     * produces, yielding {@code null} if any intermediate level is absent or not a {@code Map}. The
     * descent applies no leaf cast (the value flows into {@code DSL.val(value, dataType)} / a decode
     * helper that takes {@code Object}), so it is shared by the SET-value, WHERE-value, INSERT-cell
     * and NodeId-decode-source reads alike.
     *
     * <p>Both results are primary expressions, so a caller may splice one into any operand slot
     * without wrapping: {@code mapLocal.get(key)} already is one, and
     * {@link no.sikt.graphitron.render.WireMapChain#of} parenthesises the deeper chain. That
     * matters because the decode-locals and value-agreement consumers splice it into an
     * {@code instanceof} pattern test and a {@code == null} comparison, both of which bind tighter
     * than {@code ?:}.
     */
    public static CodeBlock nestedMapValueExpr(String mapLocal, List<String> path) {
        if (path.size() == 1) {
            return CodeBlock.of("$L.get($S)", mapLocal, path.get(0));
        }
        return no.sikt.graphitron.render.WireMapChain.of(CodeBlock.of("$L", mapLocal), path, /* leafType= */ null, mapLocal);
    }
}
