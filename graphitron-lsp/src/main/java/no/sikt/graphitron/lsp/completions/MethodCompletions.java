package no.sikt.graphitron.lsp.completions;

import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;

import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.model.Tables.JVM_METHOD;
import static no.sikt.graphitron.model.Tables.JVM_METHOD_PARAMETER;

/**
 * Method-name completions for any coordinate the {@link LspVocabulary}
 * overlay declares as a {@link Behavior.MethodNameBinding}. The behavior
 * arm carries the sibling class-name coordinate; this provider reads the
 * value at that coordinate (the FQN the user has filled in for
 * {@code className}) and offers the methods of that class.
 *
 * <p>If the sibling {@code className} value is missing, empty, or names a
 * class this graph's walk never met, the provider returns no
 * completions. The class-name itself is the user's previous edit; this
 * provider only acts once that has resolved.
 *
 * <p>One provider, two lists. Under {@code @externalField} the offered set narrows to the methods
 * matching that directive's contract, a lifter returning a jOOQ {@code Field<X>} from a single
 * parameter, and falls back to the whole list when the class exposes none. That used to be two
 * providers chained by a dispatch-site ordering, with the narrowing in one and the fall-through in
 * the chaining; both were reading the same census, so the split bought nothing the arm cannot state
 * for itself. It is one query either way: the shape is a predicate over the rows already fetched,
 * not a second trip.
 *
 * <p>Overloads stay distinct because {@code jvm_method} keys on the descriptor, so two methods
 * sharing a display name are two candidates with two signatures rather than one arbitrary winner.
 */
public final class MethodCompletions {

    /** Directive whose method slot narrows to the lifter shape. */
    private static final String EXTERNAL_FIELD_DIRECTIVE = "externalField";

    /** Erased display name of a jOOQ {@code Field<X>} return type. */
    private static final String FIELD_RETURN_TYPE = "Field";

    private MethodCompletions() {}

    public static List<CompletionItem> generate(
        LspVocabulary vocabulary,
        StoreHandle store,
        CompletionContext context,
        Directives.Directive directive,
        Point pos,
        byte[] source
    ) {
        var behavior = vocabulary.behaviorAt(context.coordinate());
        if (behavior.isEmpty() || !(behavior.get() instanceof Behavior.MethodNameBinding mnb)) {
            return List.of();
        }
        var classFqn = vocabulary.siblingStringAt(directive, pos, mnb.classNameCoord(), source);
        if (classFqn.isEmpty()) return List.of();

        List<Method> methods = methodsOf(store, classFqn.get());
        if (EXTERNAL_FIELD_DIRECTIVE.equals(context.directiveName())) {
            // The narrowed list wins when it has anything in it, and the whole list stands when the
            // class exposes no lifter. Deliberate: an author on a class that cannot lift a field is
            // better served by seeing what it does have than by an empty popup.
            var lifters = methods.stream().filter(Method::liftsField).toList();
            if (!lifters.isEmpty()) {
                methods = lifters;
            }
        }
        var items = new ArrayList<CompletionItem>(methods.size());
        for (Method method : methods) {
            items.add(CompletionItems.replacing(
                method.name(), CompletionItemKind.Method, context.replaceRange(), method.signature()));
        }
        return items;
    }

    /**
     * The class's methods with their parameters, in name then descriptor then position order, folded
     * from the one join those two relations answer together. The left join keeps a no-argument
     * method, whose parameter side is absent rather than empty.
     */
    private static List<Method> methodsOf(StoreHandle store, String classFqn) {
        var rows = store.dsl()
            .select(JVM_METHOD.METHOD_NAME, JVM_METHOD.DESCRIPTOR, JVM_METHOD.RETURN_TYPE,
                JVM_METHOD_PARAMETER.PARAMETER_NAME, JVM_METHOD_PARAMETER.PARAMETER_TYPE)
            .from(JVM_METHOD)
            .leftJoin(JVM_METHOD_PARAMETER)
            .on(JVM_METHOD_PARAMETER.SOURCE_NAME.eq(JVM_METHOD.SOURCE_NAME))
            .and(JVM_METHOD_PARAMETER.CLASS_NAME.eq(JVM_METHOD.CLASS_NAME))
            .and(JVM_METHOD_PARAMETER.METHOD_NAME.eq(JVM_METHOD.METHOD_NAME))
            .and(JVM_METHOD_PARAMETER.DESCRIPTOR.eq(JVM_METHOD.DESCRIPTOR))
            .where(store.reads(JVM_METHOD.SOURCE_NAME))
            .and(JVM_METHOD.CLASS_NAME.eq(classFqn))
            .orderBy(JVM_METHOD.METHOD_NAME, JVM_METHOD.DESCRIPTOR, JVM_METHOD_PARAMETER.POSITION)
            .fetch();

        var methods = new ArrayList<Method>();
        String currentKey = null;
        Method current = null;
        for (var row : rows) {
            String key = row.value1() + row.value2();
            if (!key.equals(currentKey)) {
                current = new Method(row.value1(), row.value3(), new ArrayList<>());
                methods.add(current);
                currentKey = key;
            }
            if (row.value5() != null) {
                current.parameters().add(new Parameter(row.value4(), row.value5()));
            }
        }
        return methods;
    }

    /**
     * One method as the completion detail line needs it. The LSP's own, and rendering only: the
     * store carries the declaration, and how a signature reads to an author is this surface's
     * business, not a fact anything else should inherit.
     */
    private record Method(String name, String returnType, List<Parameter> parameters) {

        /** Whether this method matches {@code @externalField}'s contract: one parameter in, a jOOQ
         * {@code Field} out. Confirming the parameter is specifically a jOOQ {@code Table} would
         * need the parameter's classified role, which no relation carries yet; the shape is the
         * approximation, and a suggested method can still fail to bind, the same best-effort
         * contract the generic list lives under. */
        boolean liftsField() {
            return parameters.size() == 1 && FIELD_RETURN_TYPE.equals(returnType);
        }

        /** Erased Java signature, {@code ReturnType name(Type arg0, ...)}. A parameter with no name
         * (the consumer compiled without {@code -parameters}) falls back to {@code arg<i>}. */
        String signature() {
            var sb = new StringBuilder();
            sb.append(returnType).append(' ').append(name).append('(');
            for (int i = 0; i < parameters.size(); i++) {
                if (i > 0) sb.append(", ");
                var p = parameters.get(i);
                sb.append(p.type()).append(' ').append(p.name() != null ? p.name() : "arg" + i);
            }
            return sb.append(')').toString();
        }
    }

    /** One parameter: its declared type, and its name where the classfile kept one. */
    private record Parameter(String name, String type) {}
}
