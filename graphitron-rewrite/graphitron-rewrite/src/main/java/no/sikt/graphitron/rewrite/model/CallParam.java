package no.sikt.graphitron.rewrite.model;

/**
 * One argument in a condition or ordering method call, as seen from the fetcher call site.
 *
 * <p>Carries what the fetcher generator needs to emit the call — the argument name, how to
 * extract the value from the GraphQL execution context, whether the argument is a list, and
 * the Java type of the extracted value. No column information is included beyond what is
 * embedded in the {@link CallSiteExtraction} variant (e.g.
 * {@link CallSiteExtraction.JooqConvert#columnJavaName()}).
 *
 * <p>{@code typeName} is the fully qualified Java class name for declaring a typed local
 * variable (e.g. {@code "java.lang.String"}, {@code "java.lang.Integer"},
 * {@code "no.example.jooq.enums.MpaaRating"}). Used by generators that assign the extracted
 * value to a local before passing it to the method call. Must be a simple class name —
 * no generic parameters.
 *
 * <p>The first parameter of every condition method is the table alias and is implicit — it is
 * not represented by a {@code CallParam}. Only the arguments after the table come from this list.
 */
public record CallParam(
    String name,
    CallSiteExtraction extraction,
    boolean list,
    String typeName
) {}
