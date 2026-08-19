package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;

/**
 * What a graph's capture says the directive vocabulary <em>is</em>: which directives are defined, what
 * formal arguments each declares, and the input-object tree those arguments' types open onto. The
 * shape a cursor position is resolved against, with nothing about what any of it means.
 *
 * <p>Read out of the same relations an author's own declarations land in. Capture parses graphitron's
 * bundled {@code directives.graphqls} like any other schema file, so the definitions the language
 * server used to carry as a separately parsed registry are rows, and the bundled-versus-user split
 * that registry imposed collapses: an author who defines a directive of their own gets the same
 * descent through it that {@code @reference} gets.
 *
 * <p><b>Loaded whole, once.</b> The alternative shape, a query per question at the point of asking,
 * is the one the diagnostics walk cannot have: that walk reads nothing, by construction, so that a
 * whole recalculation resolves its questions in one statement per graph rather than one per value an
 * author wrote. Resolving a cursor to a coordinate is a question about the vocabulary rather than
 * about the document, and the vocabulary does not change between captures, so it is held rather than
 * re-asked.
 *
 * <p><b>A lookup table, with no ordering contract.</b> Every question asked of it is a point lookup,
 * so nothing here preserves the order a definition declares its members in. A surface that wants to
 * <em>list</em> members for an author to read wants declaration order and asks the store for it,
 * which is why {@link no.sikt.graphitron.lsp.completions.ArgNameCompletions} keeps its own ordered
 * queries while resolving each nesting step through this table.
 *
 * <p>Wrapping is discarded on the way in. Every consumer of this surface wants the type a written
 * expression bottoms out in, which the store decodes into {@code named_type} at capture; the list and
 * non-null columns beside it answer a different question that no coordinate walk asks.
 */
public record DirectiveSurface(
    Set<String> directives,
    Map<String, Map<String, String>> directiveArguments,
    Set<String> inputObjects,
    Map<String, Map<String, String>> inputFields
) {

    /** The {@code graphql_type.kind} value naming a type a directive argument can descend into. */
    private static final String INPUT_OBJECT_KIND = "INPUT_OBJECT";

    public DirectiveSurface {
        directives = Set.copyOf(directives);
        directiveArguments = copyNested(directiveArguments);
        inputObjects = Set.copyOf(inputObjects);
        inputFields = copyNested(inputFields);
    }

    /**
     * What a session with no store knows about the vocabulary, which is nothing. Every cursor
     * resolves to no coordinate and every directive walk emits no leaf, so the surfaces keyed on a
     * coordinate stay silent rather than guessing. That is the same answer a session before its first
     * build gives to every other store-backed question.
     */
    public static DirectiveSurface empty() {
        return new DirectiveSurface(Set.of(), Map.of(), Set.of(), Map.of());
    }

    /**
     * The whole vocabulary of {@code store}'s graph, in four statements: the directive definitions,
     * their formal arguments, the input objects, and those objects' fields. Four rather than one
     * because the relations share no key and a union would have to invent one; this runs once per
     * session, off the request path.
     */
    public static DirectiveSurface load(StoreHandle store) {
        return new DirectiveSurface(
            directiveNames(store), argumentsByDirective(store),
            inputObjectNames(store), fieldsByInputObject(store));
    }

    /** Whether the graph defines a directive by this name. */
    public boolean declaresDirective(String name) {
        return directives.contains(name);
    }

    /** Whether the graph declares a type of this name as an input object. */
    public boolean declaresInputObject(String type) {
        return inputObjects.contains(type);
    }

    /** Whether {@code directive}'s definition declares a formal argument by this name. */
    public boolean declaresArgument(String directive, String argument) {
        return argumentNamedType(directive, argument).isPresent();
    }

    /** Whether {@code type} is an input object declaring a field by this name. */
    public boolean declaresInputField(String type, String field) {
        return inputFieldNamedType(type, field).isPresent();
    }

    /**
     * The type {@code directive}'s formal argument bottoms out in, whatever wrapping it was written
     * with. Empty where the directive is undefined or declares no such argument, which a caller
     * treats the same way: a value written there keys no coordinate.
     */
    public Optional<String> argumentNamedType(String directive, String argument) {
        return lookup(directiveArguments, directive, argument);
    }

    /**
     * The type one field of an input object bottoms out in. Empty where {@code type} is not an input
     * object or declares no such field, which is the guard a descent needs: output fields sit in the
     * same relation under the same shape, and only the kind tells them apart.
     */
    public Optional<String> inputFieldNamedType(String type, String field) {
        return lookup(inputFields, type, field);
    }

    private static Optional<String> lookup(
        Map<String, Map<String, String>> byOwner, String owner, String member
    ) {
        var members = byOwner.get(owner);
        return members == null ? Optional.empty() : Optional.ofNullable(members.get(member));
    }

    private static Set<String> directiveNames(StoreHandle store) {
        return new HashSet<>(store.dsl()
            .select(GRAPHQL_DIRECTIVE.DIRECTIVE_NAME)
            .from(GRAPHQL_DIRECTIVE)
            .where(GRAPHQL_DIRECTIVE.GRAPH_NAME.eq(store.graphName()))
            .fetch(GRAPHQL_DIRECTIVE.DIRECTIVE_NAME));
    }

    private static Map<String, Map<String, String>> argumentsByDirective(StoreHandle store) {
        var out = new HashMap<String, Map<String, String>>();
        store.dsl()
            .select(GRAPHQL_DIRECTIVE_ARGUMENT.DIRECTIVE_NAME,
                GRAPHQL_DIRECTIVE_ARGUMENT.ARGUMENT_NAME,
                GRAPHQL_DIRECTIVE_ARGUMENT.NAMED_TYPE)
            .from(GRAPHQL_DIRECTIVE_ARGUMENT)
            .where(GRAPHQL_DIRECTIVE_ARGUMENT.GRAPH_NAME.eq(store.graphName()))
            .forEach(row -> out
                .computeIfAbsent(row.value1(), ignored -> new HashMap<>())
                .put(row.value2(), row.value3()));
        return out;
    }

    private static Set<String> inputObjectNames(StoreHandle store) {
        return new HashSet<>(store.dsl()
            .select(GRAPHQL_TYPE.TYPE_NAME)
            .from(GRAPHQL_TYPE)
            .where(GRAPHQL_TYPE.GRAPH_NAME.eq(store.graphName()))
            .and(GRAPHQL_TYPE.KIND.eq(INPUT_OBJECT_KIND))
            .fetch(GRAPHQL_TYPE.TYPE_NAME));
    }

    private static Map<String, Map<String, String>> fieldsByInputObject(StoreHandle store) {
        var out = new HashMap<String, Map<String, String>>();
        store.dsl()
            .select(GRAPHQL_FIELD.TYPE_NAME, GRAPHQL_FIELD.FIELD_NAME, GRAPHQL_FIELD.NAMED_TYPE)
            .from(GRAPHQL_FIELD)
            .join(GRAPHQL_TYPE).on(GRAPHQL_TYPE.GRAPH_NAME.eq(GRAPHQL_FIELD.GRAPH_NAME)
                .and(GRAPHQL_TYPE.TYPE_NAME.eq(GRAPHQL_FIELD.TYPE_NAME)))
            .where(GRAPHQL_FIELD.GRAPH_NAME.eq(store.graphName()))
            .and(GRAPHQL_TYPE.KIND.eq(INPUT_OBJECT_KIND))
            .forEach(row -> out
                .computeIfAbsent(row.value1(), ignored -> new HashMap<>())
                .put(row.value2(), row.value3()));
        return out;
    }

    private static Map<String, Map<String, String>> copyNested(Map<String, Map<String, String>> source) {
        var out = new HashMap<String, Map<String, String>>(source.size());
        source.forEach((owner, members) -> out.put(owner, Map.copyOf(members)));
        return Map.copyOf(out);
    }
}
