package no.sikt.graphitron.render;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.TypeName;

import java.util.List;
import java.util.Map;

/**
 * Reads a value out of the nested {@code Map} graphql-java hands an argument, as one expression:
 * a chain of {@code instanceof Map<?, ?>} ternaries from a root down to a named leaf, evaluating
 * to {@code null} at any level that is absent or is not a map.
 *
 * <p>The single home for that emission. Two call sites want it, the service-call argument list
 * ({@link no.sikt.graphitron.rewrite.generators.ArgCallEmitter}) and the condition glue's binding
 * locals ({@link ConditionGlueRenderer}), and they used to carry a private copy each. The copies
 * emitted the same characters, which is the tell: the shape is one decision about generated code
 * and not two, and two spellings of it are two places a fix has to land. The third emission of
 * this descent, {@link ArgPathHelperRegistry}, deliberately does not route here: it emits the
 * statement form the development principles prefer, hoisted onto a named helper method, and
 * converging the two would be a change to what is emitted rather than to where it is spelled.
 *
 * <p>A list-aware variant of this same descent lives at
 * {@code ArgCallEmitter.walkSegments}: it lifts a list-shaped segment into a
 * {@code stream().map(...)} and numbers its bindings off one shared counter, so it is a superset of
 * this shape rather than a copy of it and folding this one into it would renumber every binding
 * both call sites here emit. Named so a reader who finds one finds the family.
 *
 * @see ArgPathHelperRegistry the statement-form sibling, and the form to grow towards
 */
public final class WireMapChain {

    private static final ClassName MAP = ClassName.get(Map.class);

    private WireMapChain() {}

    /**
     * Builds the whole descent.
     *
     * @param root        the expression the head is read out of, evaluated once at depth 0 and
     *                    only when {@code liftedLocal} is null
     * @param path        the segment names to descend, depth-ordered and non-empty; the last is
     *                    the leaf
     * @param leafType    the type to cast the leaf read to, or {@code null} to hand the raw
     *                    {@code Object} back so a consumer can apply its own runtime guard (what
     *                    the decode and column-coercion leaves want)
     * @param liftedLocal names a local that is already known to be a {@code Map<?, ?>}, letting
     *                    depth 0 guard with {@code != null} instead of rebinding; {@code null}
     *                    when the root is an expression to test
     */
    public static CodeBlock of(CodeBlock root, List<String> path, TypeName leafType,
            String liftedLocal) {
        return at(root, path, 0, leafType, liftedLocal);
    }

    private static CodeBlock at(CodeBlock currentExpr, List<String> path, int depth,
            TypeName leafType, String liftedLocal) {
        String key = path.get(depth);
        boolean isLeaf = depth == path.size() - 1;
        boolean liftedHead = liftedLocal != null && depth == 0;
        String binding = liftedHead ? liftedLocal : "map" + (depth + 1);

        if (isLeaf) {
            if (liftedHead) {
                return leafType == null
                    ? CodeBlock.of("$L != null ? $L.get($S) : null", binding, binding, key)
                    : CodeBlock.of("$L != null ? ($T) $L.get($S) : null",
                        binding, leafType, binding, key);
            }
            return leafType == null
                ? CodeBlock.of("$L instanceof $T<?, ?> $L ? $L.get($S) : null",
                    currentExpr, MAP, binding, binding, key)
                : CodeBlock.of("$L instanceof $T<?, ?> $L ? ($T) $L.get($S) : null",
                    currentExpr, MAP, binding, leafType, binding, key);
        }
        CodeBlock next = CodeBlock.of("$L.get($S)", binding, key);
        if (liftedHead) {
            return CodeBlock.of("$L != null ? ($L) : null",
                binding, at(next, path, depth + 1, leafType, null));
        }
        return CodeBlock.of("$L instanceof $T<?, ?> $L ? ($L) : null",
            currentExpr, MAP, binding, at(next, path, depth + 1, leafType, null));
    }

    /**
     * The erasure of a rendered Java type name, {@code List<Film>} down to {@code List}. Both call
     * sites carry a declared type as a string at some point and need the raw head to build a cast
     * target; the split lived beside each chain and follows it here.
     */
    public static String rawComponent(String typeName) {
        int lt = typeName.indexOf('<');
        return lt < 0 ? typeName : typeName.substring(0, lt);
    }
}
