package no.sikt.graphitron.render;

import no.sikt.graphitron.command.Ordering;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.WildcardTypeName;

/**
 * The two-view ordering block a paginated composition declares: {@code orderBy} (the sort view)
 * and {@code extraFields} (the cursor-column view), derived from ONE {@link Ordering} dispatch
 * so the cursor columns cannot drift from the SQL ordering. For the inline {@link
 * Ordering.Columns} arm both views render from the shared {@link OrderByFragments}; for the
 * {@link Ordering.Helper} arm both come out of the one runtime {@code OrderByResult} the emitted
 * helper returns. One home for the sync, read by the launcher renderer and the legacy connection
 * fetcher builder alike through the migration window.
 */
public final class OrderingBlock {

    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName SORT_FIELD = ClassName.get("org.jooq", "SortField");
    private static final ClassName JOOQ_FIELD = ClassName.get("org.jooq", "Field");
    private static final TypeName SORT_FIELD_LIST = ParameterizedTypeName.get(
        LIST, ParameterizedTypeName.get(SORT_FIELD, WildcardTypeName.subtypeOf(Object.class)));
    private static final TypeName FIELD_LIST = ParameterizedTypeName.get(
        LIST, ParameterizedTypeName.get(JOOQ_FIELD, WildcardTypeName.subtypeOf(Object.class)));

    private OrderingBlock() {}

    /**
     * Declares the sort view alone ({@code orderBy}), for the non-paginating compositions: an
     * unpaginated ORDER BY needs no cursor-column view. The {@link Ordering.Helper} arm's call is
     * unqualified, the launcher and its emitted helper sharing the fetchers class by the naming
     * vocabulary.
     */
    public static CodeBlock declareSortView(Ordering ordering, String tableLocal) {
        return switch (ordering) {
            case Ordering.Columns columns -> CodeBlock.builder()
                .addStatement("$T orderBy = $T.of($L)", SORT_FIELD_LIST, LIST,
                    OrderByFragments.fixedSortParts(columns.spec(), tableLocal))
                .build();
            case Ordering.Helper helper -> CodeBlock.builder()
                .addStatement("$T orderBy = $L(env, $L).sortFields()",
                    SORT_FIELD_LIST, helper.method().methodName(), tableLocal)
                .build();
        };
    }

    /** Declares {@code orderBy} and {@code extraFields} from one dispatch over the ordering. */
    public static CodeBlock declareBothViews(Ordering ordering, String tableLocal) {
        var code = CodeBlock.builder();
        switch (ordering) {
            case Ordering.Columns columns -> {
                code.addStatement("$T orderBy = $T.of($L)", SORT_FIELD_LIST, LIST,
                    OrderByFragments.fixedSortParts(columns.spec(), tableLocal));
                code.addStatement("$T extraFields = $T.of($L)", FIELD_LIST, LIST,
                    OrderByFragments.fixedColumnParts(columns.spec(), tableLocal));
            }
            case Ordering.Helper helper -> {
                code.addStatement("$T ordering = $L(env, $L)",
                    ClassName.get(helper.resultType().packageName(), helper.resultType().simpleName()),
                    helper.method().methodName(), tableLocal);
                code.addStatement("$T orderBy = ordering.sortFields()", SORT_FIELD_LIST);
                code.addStatement("$T extraFields = ordering.columns()", FIELD_LIST);
            }
        }
        return code.build();
    }
}
