package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLFieldDefinition;
import no.sikt.graphitron.facts.PaginationFacts;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.PaginationSpec;

/**
 * The pagination fact's resolved views. The populations (the authored name-keyed pagination
 * arguments, the inferred connection-directive default) are gathered once by
 * {@link no.sikt.graphitron.facts.PaginationFactVisitor}; this class computes the model-facing
 * values over the gathered rows with no traversal of its own, following the model's rule that a
 * resolved value is always a view over the authored and inferred populations. Sibling to
 * {@link OrderByResolver} and {@link LookupMappingResolver}.
 *
 * <p>The projection is total: the gathered populations carry no rejection paths, so
 * {@link #resolve} returns a nullable {@link PaginationSpec} directly, without the sealed
 * {@code Resolved} wrapper used by {@link OrderByResolver}.
 */
final class PaginationResolver {

    PaginationResolver() {}

    /**
     * Projects the coordinate's gathered pagination row into a {@link PaginationSpec}, or
     * {@code null} when no pagination applies. Authored arguments fill their role slots; a
     * connection-directive row with no authored slot synthesizes the forward-pagination
     * defaults ({@code first: Int}, {@code after: String}) so the connection works without the
     * author declaring those args.
     */
    PaginationSpec resolve(PaginationFacts facts, GraphQLFieldDefinition fieldDef) {
        var row = facts.rowFor(fieldDef).orElse(null);
        if (row == null) {
            return null;
        }
        PaginationSpec.PaginationArg first = null, last = null, after = null, before = null;
        for (var arg : row.args()) {
            var paginationArg = new PaginationSpec.PaginationArg(arg.typeName(), arg.nonNull());
            switch (arg.role()) {
                case FIRST  -> first  = paginationArg;
                case LAST   -> last   = paginationArg;
                case AFTER  -> after  = paginationArg;
                case BEFORE -> before = paginationArg;
            }
        }

        if (first == null && last == null && after == null && before == null && row.asConnection()) {
            first = new PaginationSpec.PaginationArg("Int", false);
            after = new PaginationSpec.PaginationArg("String", false);
        }

        if (first == null && last == null && after == null && before == null) return null;
        return new PaginationSpec(first, last, after, before);
    }

    /**
     * The per-carrier-site default page size: the gathered {@code defaultFirstValue} when the
     * author wrote one, {@link FieldWrapper#DEFAULT_PAGE_SIZE} otherwise. Both the wrapper
     * classification and the connection carrier rewrite read this one view, so the two emitted
     * materialisations of the default cannot drift.
     */
    static int defaultPageSize(PaginationFacts facts, GraphQLFieldDefinition fieldDef) {
        return facts.rowFor(fieldDef)
            .map(row -> row.authoredDefaultFirst().orElse(FieldWrapper.DEFAULT_PAGE_SIZE))
            .orElse(FieldWrapper.DEFAULT_PAGE_SIZE);
    }
}
