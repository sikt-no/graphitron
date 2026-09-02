package no.sikt.graphitron.model.schema.federation;

/**
 * Federation spec metadata used across the rewrite pipeline. Kept neutral (no pipeline timing or
 * federation-jvm runtime call) so callers at different stages can reference it without inverting
 * the pipeline ordering.
 *
 * <p>Today this is the canonical {@code @link} URL and the version-agnostic prefix that
 * recognises one.
 */
public final class FederationSpec {

    /**
     * What makes an {@code @link} a <em>federation</em> link, independent of spec version. Several
     * stages ask that question at points too far apart to share a caller (link application, tag
     * synthesis, fact capture), and they must answer it identically or a schema is federated for
     * one and not the others.
     */
    public static final String SPEC_PREFIX = "https://specs.apollo.dev/federation/";

    /**
     * The Apollo Federation 2 spec URL bundled with the
     * {@code federation-graphql-java-support} version pinned in this build. Bump alongside the
     * library when a consumer needs directives gated behind a newer spec version; verify the
     * library's {@code FederationDirectives.loadFederationSpecDefinitions(URL)} accepts it.
     */
    public static final String URL = "https://specs.apollo.dev/federation/v2.10";

    private FederationSpec() {}
}
