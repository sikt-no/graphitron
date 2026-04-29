package no.sikt.graphitron.rewrite;

import com.apollographql.federation.graphqljava.directives.LinkDirectiveProcessor;
import graphql.schema.idl.TypeDefinitionRegistry;

import java.util.Objects;

/**
 * The output of {@link GraphQLRewriteGenerator#loadAttributedRegistry}: a
 * {@link TypeDefinitionRegistry} paired with the derived {@code federationLink} boolean.
 *
 * <p>{@code federationLink} is captured once, by the pipeline orchestrator, from the
 * {@link no.sikt.graphitron.rewrite.schema.input.FederationLinkApplier#apply}'s return value;
 * downstream stages read it off the carrier instead of re-walking the registry. Tests that
 * construct a registry ad-hoc (without running the full attribution pipeline) use
 * {@link #from(TypeDefinitionRegistry)} to derive the boolean from the registry's contents.
 */
public record AttributedRegistry(TypeDefinitionRegistry registry, boolean federationLink) {

    public AttributedRegistry {
        Objects.requireNonNull(registry, "registry");
    }

    /**
     * Inspects the registry for a federation {@code @link} extension and wraps it as an
     * {@link AttributedRegistry}. Convenience for tests; production paths capture
     * {@code federationLink} directly from {@code FederationLinkApplier.apply}'s return value.
     */
    public static AttributedRegistry from(TypeDefinitionRegistry registry) {
        return new AttributedRegistry(registry,
            LinkDirectiveProcessor.loadFederationImportedDefinitions(registry) != null);
    }
}
