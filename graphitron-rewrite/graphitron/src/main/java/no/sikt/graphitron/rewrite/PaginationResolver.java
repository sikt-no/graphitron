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
 * <p>Three responsibilities cluster here, all pure pagination semantics:
 *
 * <ul>
 *   <li>{@link #resolve} — projects classified {@link ArgumentRef.PaginationArgRef} entries into
 *       a {@link PaginationSpec}. When {@code @asConnection} is present without explicit
 *       pagination args, synthesizes forward-pagination defaults ({@code first}/{@code after}).
 *       Returns {@code null} when no pagination args are present and {@code @asConnection} is
 *       not declared.</li>
 *   <li>{@link #isPaginationArg} — recognises the four reserved Connection arg names
 *       ({@code first}, {@code last}, {@code after}, {@code before}). Used during argument
 *       classification to route an arg into {@link ArgumentRef.PaginationArgRef}.</li>
 *   <li>{@link #resolveDefaultFirstValue} — reads the {@code @asConnection.defaultFirstValue}
 *       directive argument; falls back to {@link FieldWrapper#DEFAULT_PAGE_SIZE} when the
 *       directive is absent or the argument is unset. Used by {@link FieldBuilder#buildWrapper}
 *       to populate the per-carrier page size on a {@link FieldWrapper.Connection} wrapper.</li>
 * </ul>
 *
 * <p>The projection is total: every input shape produces either a {@link PaginationSpec} or
 * {@code null}. There are no rejection paths — pagination args have already been validated by
 * the classifier (their {@code role()} is set, type/nullability captured), and the
 * {@code @asConnection} default-synthesis is unconditional. So the resolver returns
 * {@link PaginationSpec} (nullable) directly without the sealed {@code Resolved} wrapper used by
 * {@link OrderByResolver}.
 */
final class PaginationResolver {

    PaginationResolver() {}

    /**
     * Projects the classified {@code refs} into a {@link PaginationSpec}.
     *
     * <p>Reads {@link ArgumentRef.PaginationArgRef} entries (one per pagination arg) and sets the
     * matching slot ({@code first} / {@code last} / {@code after} / {@code before}). When the
     * field has {@code @asConnection} and none of the four slots are populated explicitly,
     * synthesizes forward-pagination defaults ({@code first: Int}, {@code after: String}) so the
     * connection works without the author declaring those args. Returns {@code null} when no
     * pagination args are present and {@code @asConnection} is not declared.
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

        // @asConnection without explicit pagination args: synthesize forward-pagination defaults
        if (first == null && last == null && after == null && before == null
                && fieldDef.hasAppliedDirective(DIR_AS_CONNECTION)) {
            first = new PaginationSpec.PaginationArg("Int", false);
            after = new PaginationSpec.PaginationArg("String", false);
        }

        if (first == null && last == null && after == null && before == null) return null;
        return new PaginationSpec(first, last, after, before);
    }

    /**
     * Returns {@code true} iff {@code argName} is one of the four reserved Connection-pagination
     * names ({@code first}, {@code last}, {@code after}, {@code before}). The classifier uses
     * this to route an argument into {@link ArgumentRef.PaginationArgRef} ahead of the
     * scalar-column resolution path.
     */
    boolean isPaginationArg(String argName) {
        return "first".equals(argName) || "last".equals(argName)
            || "after".equals(argName) || "before".equals(argName);
    }

    /**
     * Resolves the per-carrier-site default page size for a {@code @asConnection} field. Reads
     * the {@code defaultFirstValue} argument on the directive; falls back to
     * {@link FieldWrapper#DEFAULT_PAGE_SIZE} when the directive is absent or the argument is
     * unset. Accepts {@link IntValue} (raw schema AST literal) and {@link Number} (already-parsed
     * value) shapes — graphql-java surfaces directive arguments in either form depending on the
     * resolution path.
     */
    int resolveDefaultFirstValue(GraphQLFieldDefinition fieldDef) {
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
