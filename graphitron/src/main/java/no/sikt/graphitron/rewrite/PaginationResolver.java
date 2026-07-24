package no.sikt.graphitron.rewrite;

import graphql.language.IntValue;
import graphql.schema.GraphQLFieldDefinition;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.PaginationSpec;

import java.util.List;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_DEFAULT_FIRST_VALUE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_AS_CONNECTION;

/**
 * Resolves the pagination concern for a list/connection field. Sibling to
 * {@link OrderByResolver} and {@link LookupMappingResolver}.
 *
 * <p>The projection is total: pagination args are already validated by the classifier, and the
 * {@code @asConnection} default-synthesis is unconditional, so there are no rejection paths.
 * {@link #resolve} therefore returns a nullable {@link PaginationSpec} directly, without the
 * sealed {@code Resolved} wrapper used by {@link OrderByResolver}.
 */
final class PaginationResolver {

    PaginationResolver() {}

    /**
     * Projects the classified {@link ArgumentRef.PaginationArgRef} entries into a
     * {@link PaginationSpec}, or {@code null} when no pagination applies. When the field has
     * {@code @asConnection} and no slot is populated explicitly, synthesizes forward-pagination
     * defaults ({@code first: Int}, {@code after: String}) so the connection works without the
     * author declaring those args.
     */
    PaginationSpec resolve(List<ArgumentRef> refs, GraphQLFieldDefinition fieldDef) {
        PaginationSpec.PaginationArg first = null, last = null, after = null, before = null;
        for (var ref : refs) {
            if (!(ref instanceof ArgumentRef.PaginationArgRef p)) continue;
            var paginationArg = new PaginationSpec.PaginationArg(p.typeName(), p.nonNull());
            switch (p.role()) {
                case FIRST  -> first  = paginationArg;
                case LAST   -> last   = paginationArg;
                case AFTER  -> after  = paginationArg;
                case BEFORE -> before = paginationArg;
            }
        }

        if (first == null && last == null && after == null && before == null
                && fieldDef.hasAppliedDirective(DIR_AS_CONNECTION)) {
            first = new PaginationSpec.PaginationArg("Int", false);
            after = new PaginationSpec.PaginationArg("String", false);
        }

        if (first == null && last == null && after == null && before == null) return null;
        return new PaginationSpec(first, last, after, before);
    }

    /**
     * Recognises the four reserved Connection-pagination arg names. The classifier uses this to
     * route an argument into {@link ArgumentRef.PaginationArgRef} ahead of the scalar-column
     * resolution path.
     */
    boolean isPaginationArg(String argName) {
        return "first".equals(argName) || "last".equals(argName)
            || "after".equals(argName) || "before".equals(argName);
    }

    /**
     * Resolves the per-carrier-site default page size for an {@code @asConnection} field from
     * the {@code defaultFirstValue} directive argument, falling back to
     * {@link FieldWrapper#DEFAULT_PAGE_SIZE}. Accepts both {@link IntValue} (raw schema AST
     * literal) and {@link Number} (already-parsed value): graphql-java surfaces directive
     * arguments in either form depending on the resolution path.
     */
    static int resolveDefaultFirstValue(GraphQLFieldDefinition fieldDef) {
        var dir = fieldDef.getAppliedDirective(DIR_AS_CONNECTION);
        if (dir == null) return FieldWrapper.DEFAULT_PAGE_SIZE;
        var arg = dir.getArgument(ARG_DEFAULT_FIRST_VALUE);
        if (arg == null || arg.getValue() == null) return FieldWrapper.DEFAULT_PAGE_SIZE;
        Object val = arg.getValue();
        if (val instanceof IntValue iv) return iv.getValue().intValueExact();
        if (val instanceof Number n) return n.intValue();
        return FieldWrapper.DEFAULT_PAGE_SIZE;
    }
}
