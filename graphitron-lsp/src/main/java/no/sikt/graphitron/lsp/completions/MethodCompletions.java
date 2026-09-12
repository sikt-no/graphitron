package no.sikt.graphitron.lsp.completions;

import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.facts.ClasspathMethods;
import no.sikt.graphitron.lsp.facts.ExternalFieldLifters;
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
 * that directive may name, and falls back to the whole list when the class exposes none. That used
 * to be two providers chained by a dispatch-site ordering, with the narrowing in one and the
 * fall-through in the chaining; both were reading the same census, so the split bought nothing the
 * arm cannot state for itself.
 *
 * <p>What narrows is {@link ExternalFieldLifters}, which is the relation that admits a lifter rather
 * than this provider's reading of a shape. The reading it replaces compared an arity to one and a
 * return type's simple name to {@code Field}, which is as far as the census goes: nothing there says
 * whether a method is static, and nothing resolves {@code Field} to jOOQ's. So a one-argument method
 * returning any type named {@code Field} was offered as a lifter, and a suggestion could fail to
 * bind at build. Both clauses are the arm's now, with the parameter-is-a-table clause the census
 * could not reach at all, so what is offered is what the generator would accept.
 *
 * <p>Two reads where there was one, and the second is the point rather than a cost: the census
 * answers what the class declares and the arm answers which of those a directive may name, and no
 * amount of reading the first produces the second.
 *
 * <p>Overloads stay distinct because both relations key on the descriptor, so two methods sharing a
 * display name are two candidates with two signatures rather than one arbitrary winner, and the
 * narrowing admits them one at a time.
 *
 * <p>The census read itself is {@link ClasspathMethods}, shared with hover's method arm, which asks
 * the same question of the same two relations under one name instead of all of them.
 */
public final class MethodCompletions {

    /** Directive whose method slot narrows to what the lifter arm admitted. */
    private static final String EXTERNAL_FIELD_DIRECTIVE = "externalField";

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
            var admitted = ExternalFieldLifters.descriptorsOf(store, classFqn.get());
            var lifters = methods.stream().filter(m -> admitted.contains(m.descriptor())).toList();
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
}
