package no.sikt.graphitron.emit;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import no.sikt.graphitron.emit.RootQuery.Choice;
import no.sikt.graphitron.emit.RootQuery.Emitted;
import no.sikt.graphitron.emit.RootQuery.Key;
import no.sikt.graphitron.render.CatalogRefs;

import javax.lang.model.element.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code @orderBy} helper of a root the store states with a choice: {@code private static OrderByResult
 * <field>OrderBy(DataFetchingEnvironment env, <Table> table)}, which reads the argument off the request and
 * hands back the sort fields and the cursor columns of the order it names, rendered from
 * {@link RootQuery.Choice} and the root's default order alone.
 *
 * <p>The argument takes one order and the helper switches on its sort field, the default order
 * answering where the argument is absent or names no order. An order whose keys all ascend follows the
 * request's {@code direction:}; one whose author fixed a direction per key keeps it. Each derived key
 * is numbered among the root's derived keys, each order's in the sort enum's order and then the
 * default's, and its cursor column is aliased by that number.
 */
public final class OrderByHelperEmitter {

    private static final ClassName ENV = ClassName.get("graphql.schema", "DataFetchingEnvironment");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName MAP = ClassName.get("java.util", "Map");
    private static final TypeName ARGUMENT_MAP = ParameterizedTypeName.get(MAP, ClassName.get(String.class),
        ClassName.get(Object.class));

    /** The table local the helper's keys read, its parameter. */
    private static final String TABLE = "table";

    private OrderByHelperEmitter() {}

    /** The names a helper is declared and returns by, each the name the emitter of that unit gives it. */
    public interface Names {
        String orderByHelper(String parentType, String fieldName);
        ClassName orderByResult();
    }

    /** One key with its number among the root's derived keys, which its cursor alias reads. */
    private record Numbered(Key key, int ordinal) {}

    /** The helper of {@code root}, which states a choice. */
    public static MethodSpec render(Emitted root, Names names) {
        var choice = root.choice();
        if (choice == null) {
            throw new IllegalStateException("Graphitron generator bug (order helper): " + root.coordinate()
                + " states no @orderBy choice; its caller asked for its helper regardless");
        }
        // Numbered as each order's keys are rendered: the choices in the sort enum's order, then the default.
        var ordinal = new int[1];
        var orders = new LinkedHashMap<String, List<Numbered>>();
        choice.orders().forEach((name, keys) -> orders.put(name, numbered(keys, ordinal)));
        var base = numbered(root.order(), ordinal);
        var result = names.orderByResult();
        var baseExpr = base.isEmpty()
            ? CodeBlock.of("new $T($T.of(), $T.of())", result, LIST, LIST)
            : CodeBlock.of("new $T($T.of($L), $T.of($L))", result, LIST, sortParts(base, null), LIST, columnParts(base));
        var method = MethodSpec.methodBuilder(names.orderByHelper(root.parentType(), root.fieldName()))
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(result)
            .addParameter(ENV, "env")
            .addParameter(CatalogRefs.tableClass(root.table()), TABLE);
        method.addCode(picked(choice, orders, baseExpr, result));
        return method.build();
    }

    private static List<Numbered> numbered(List<Key> keys, int[] ordinal) {
        return keys.stream().map(key -> new Numbered(key, key.derived() == null ? -1 : ordinal[0]++)).toList();
    }

    /** One order picked by the argument's sort field, the default where none is. */
    private static CodeBlock picked(Choice choice, Map<String, List<Numbered>> orders, CodeBlock baseExpr,
            ClassName result) {
        var code = CodeBlock.builder();
        code.addStatement("$T orderArg = env.getArgument($S)", ARGUMENT_MAP, choice.argument());
        code.add("if (orderArg == null) return $L;\n", baseExpr);
        code.addStatement("$T field = ($T) orderArg.get($S)", String.class, String.class, choice.sortField());
        code.addStatement("$T dir = ($T) orderArg.get($S)", String.class, String.class, choice.directionField());
        code.add("return switch (field) {\n").indent();
        orders.forEach((name, keys) -> code.add("case $S -> new $T($T.of($L), $T.of($L));\n", name, result, LIST,
            sortParts(keys, flip(keys, "dir")), LIST, columnParts(keys)));
        code.add("default -> $L;\n", baseExpr);
        return code.unindent().add("};\n").build();
    }

    /** The request's descending test where every key ascends, so its {@code direction:} reverses the order. */
    private static CodeBlock flip(List<Numbered> keys, String direction) {
        return keys.stream().allMatch(key -> "ASC".equals(key.key().direction()))
            ? CodeBlock.of("$S.equals($L)", "DESC", direction)
            : null;
    }

    private static CodeBlock sortParts(List<Numbered> keys, CodeBlock flip) {
        return keys.stream().map(key -> OrderKeys.sortTerm(key.key(), TABLE, flip)).collect(CodeBlock.joining(", "));
    }

    private static CodeBlock columnParts(List<Numbered> keys) {
        return keys.stream().map(key -> OrderKeys.cursorColumn(key.key(), TABLE, key.ordinal()))
            .collect(CodeBlock.joining(", "));
    }
}
