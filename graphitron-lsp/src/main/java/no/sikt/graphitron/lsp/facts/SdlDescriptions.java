package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.lsp.parsing.SchemaCoordinate;
import no.sikt.graphitron.model.read.StoreHandle;

import java.util.Optional;

import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;

/**
 * The SDL description the graph's own capture holds for a schema coordinate. One relation per
 * coordinate arm, and one row each: every arm's key is exactly the relation's primary key, so a
 * coordinate either has a description or has no row.
 *
 * <p>One read for graphitron's bundled directives and an author's own alike. Capture parses the
 * bundled {@code directives.graphqls} like any other schema file, so the definitions the language
 * server used to carry as a parsed registry beside the projections are rows in the same relations a
 * user's own declarations land in, and the bundled-versus-user split the incumbent kept collapses.
 *
 * <p>Keyed on {@link SchemaCoordinate} rather than on a caller, so the switch is exhaustive over the
 * sealed coordinate family: a fifth coordinate arm fails to compile here until it names the relation
 * that describes it. The input-type arm is answered rather than declined even though no cursor
 * position produces that coordinate today ({@code LspVocabulary.locateAt} keys a cursor as a
 * directive argument or an input field, never as the input type itself), because what a named type's
 * description is has an answer whether or not a trigger asks.
 */
public final class SdlDescriptions {

    private SdlDescriptions() {}

    /**
     * The description at {@code coord}, or empty when no row carries one. Blank is absence: a
     * description column holding whitespace renders as nothing, and a surface would have to filter it
     * anyway.
     *
     * <p>The two type-keyed arms look up by name without checking the type's kind, because a GraphQL
     * name identifies one type per graph whatever kind it is. {@code ArgNameCompletions}' kind guard
     * is about a descent stopping at a type that has no input fields, which is a different question
     * from what a named type's own description is.
     */
    public static Optional<String> of(StoreHandle store, SchemaCoordinate coord) {
        return text(switch (coord) {
            case SchemaCoordinate.Directive d -> store.dsl()
                .select(GRAPHQL_DIRECTIVE.DESCRIPTION)
                .from(GRAPHQL_DIRECTIVE)
                .where(GRAPHQL_DIRECTIVE.GRAPH_NAME.eq(store.graphName()))
                .and(GRAPHQL_DIRECTIVE.DIRECTIVE_NAME.eq(d.name()))
                .fetchOne(GRAPHQL_DIRECTIVE.DESCRIPTION);
            case SchemaCoordinate.DirectiveArg da -> store.dsl()
                .select(GRAPHQL_DIRECTIVE_ARGUMENT.DESCRIPTION)
                .from(GRAPHQL_DIRECTIVE_ARGUMENT)
                .where(GRAPHQL_DIRECTIVE_ARGUMENT.GRAPH_NAME.eq(store.graphName()))
                .and(GRAPHQL_DIRECTIVE_ARGUMENT.DIRECTIVE_NAME.eq(da.directive()))
                .and(GRAPHQL_DIRECTIVE_ARGUMENT.ARGUMENT_NAME.eq(da.arg()))
                .fetchOne(GRAPHQL_DIRECTIVE_ARGUMENT.DESCRIPTION);
            case SchemaCoordinate.InputType t -> store.dsl()
                .select(GRAPHQL_TYPE.DESCRIPTION)
                .from(GRAPHQL_TYPE)
                .where(GRAPHQL_TYPE.GRAPH_NAME.eq(store.graphName()))
                .and(GRAPHQL_TYPE.TYPE_NAME.eq(t.name()))
                .fetchOne(GRAPHQL_TYPE.DESCRIPTION);
            case SchemaCoordinate.InputField f -> store.dsl()
                .select(GRAPHQL_FIELD.DESCRIPTION)
                .from(GRAPHQL_FIELD)
                .where(GRAPHQL_FIELD.GRAPH_NAME.eq(store.graphName()))
                .and(GRAPHQL_FIELD.TYPE_NAME.eq(f.type()))
                .and(GRAPHQL_FIELD.FIELD_NAME.eq(f.field()))
                .fetchOne(GRAPHQL_FIELD.DESCRIPTION);
        });
    }

    private static Optional<String> text(String description) {
        return description == null || description.isBlank() ? Optional.empty() : Optional.of(description);
    }
}
