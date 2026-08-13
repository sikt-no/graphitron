package no.sikt.graphitron.lsp.completions;

import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.facts.ClasspathMethods;
import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;

import java.util.ArrayList;
import java.util.List;

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
 *
 * <p>The census read itself is {@link ClasspathMethods}, shared with hover's method arm, which asks
 * the same question of the same two relations under one name instead of all of them.
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

        List<ClasspathMethods.Method> methods = ClasspathMethods.of(store, classFqn.get());
        if (EXTERNAL_FIELD_DIRECTIVE.equals(context.directiveName())) {
            // The narrowed list wins when it has anything in it, and the whole list stands when the
            // class exposes no lifter. Deliberate: an author on a class that cannot lift a field is
            // better served by seeing what it does have than by an empty popup.
            var lifters = methods.stream().filter(MethodCompletions::liftsField).toList();
            if (!lifters.isEmpty()) {
                methods = lifters;
            }
        }
        var items = new ArrayList<CompletionItem>(methods.size());
        for (var method : methods) {
            items.add(CompletionItems.replacing(
                method.name(), CompletionItemKind.Method, context.replaceRange(), method.signature()));
        }
        return items;
    }

    /**
     * Whether a method matches {@code @externalField}'s contract: one parameter in, a jOOQ
     * {@code Field} out. This directive's own rule rather than anything the census states, which is
     * why it sits here and not beside the shared read. Confirming the parameter is specifically a
     * jOOQ {@code Table} would need the parameter's classified role, which no relation carries yet;
     * the shape is the approximation, and a suggested method can still fail to bind, the same
     * best-effort contract the generic list lives under.
     */
    private static boolean liftsField(ClasspathMethods.Method method) {
        return method.arity() == 1 && FIELD_RETURN_TYPE.equals(method.returnType());
    }
}
