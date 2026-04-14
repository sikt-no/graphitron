package no.sikt.graphitron.rewrite.model;

/**
 * One argument in a condition or ordering method call, as seen from the fetcher call site.
 *
 * <p>Carries only what the fetcher generator needs to emit the call — the argument name and
 * how to extract the value from the GraphQL execution context. No column or type information
 * is included; those details belong to {@link BodyParam} and are used only by the method
 * generator ({@link no.sikt.graphitron.rewrite.generators.TypeConditionsGenerator}).
 *
 * <p>The first parameter of every condition method is the table alias and is implicit — it is
 * not represented by a {@code CallParam}. Only the arguments after the table come from this list.
 */
public record CallParam(
    String name,
    CallSiteExtraction extraction
) {}
