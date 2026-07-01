package no.sikt.graphitron.rewrite.schema.federation;

/**
 * Federation spec metadata used across the rewrite pipeline. Kept neutral (no pipeline timing or
 * federation-jvm runtime call) so callers at different stages can reference it without inverting
 * the pipeline ordering.
 *
 * <p>Today this is just the canonical {@code @link} URL. Three callers reach for it:
 * {@link no.sikt.graphitron.rewrite.schema.input.TagLinkSynthesiser} when synthesising a
 * {@code @link} for a consumer that set {@code <schemaInput tag>} but no explicit {@code @link};
 * {@link no.sikt.graphitron.rewrite.schema.input.FederationLinkApplier} when documenting which
 * spec version is bundled; and {@code GraphitronSchemaBuilder} when loading the federation
 * directive name set for recipe-error rewriting.
 */
public final class FederationSpec {

    /**
     * The Apollo Federation 2 spec URL bundled with the
     * {@code federation-graphql-java-support} version pinned in this build. Bump alongside the
     * library when a consumer needs directives gated behind a newer spec version; verify the
     * library's {@code FederationDirectives.loadFederationSpecDefinitions(URL)} accepts it.
     */
    public static final String URL = "https://specs.apollo.dev/federation/v2.10";

    private FederationSpec() {}
}
