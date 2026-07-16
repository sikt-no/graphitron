package no.sikt.graphitron.rewrite;

import com.apollographql.federation.graphqljava.directives.LinkDirectiveProcessor;
import graphql.language.NamedNode;
import graphql.schema.idl.TypeDefinitionRegistry;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * The output of {@link GraphQLRewriteGenerator#loadAttributedRegistry}: a
 * {@link TypeDefinitionRegistry} paired with the names of the definitions the federation
 * {@code @link} injector added to it.
 *
 * <p>{@code injectedNames} is captured once, by the pipeline orchestrator, from
 * {@link no.sikt.graphitron.rewrite.schema.input.FederationLinkApplier#apply}'s return value;
 * downstream stages read it off the carrier instead of re-walking the registry. The
 * {@link #federationLink()} flag is derived from it ("injected anything"), so the two facts live in
 * one component rather than a parallel boolean. The lint engine excludes these names because they
 * are the generator-owned federation surface, not author input, and carry the federation spec's own
 * names with a {@code null} source. Tests that construct a registry ad-hoc (without running
 * the full attribution pipeline) use {@link #from(TypeDefinitionRegistry)} to derive the set from
 * the registry's contents.
 */
public record AttributedRegistry(TypeDefinitionRegistry registry, Set<String> injectedNames) {

    public AttributedRegistry {
        Objects.requireNonNull(registry, "registry");
        injectedNames = Set.copyOf(injectedNames);
    }

    /** True when the federation {@code @link} injector contributed any definitions. */
    public boolean federationLink() {
        return !injectedNames.isEmpty();
    }

    /**
     * Inspects the registry for a federation {@code @link} extension and wraps it as an
     * {@link AttributedRegistry}, deriving {@code injectedNames} the same way
     * {@code FederationLinkApplier.apply} collects it (the names of every definition the
     * {@code @link} import would inject). Convenience for tests; production paths capture the set
     * directly from {@code FederationLinkApplier.apply}'s return value.
     */
    public static AttributedRegistry from(TypeDefinitionRegistry registry) {
        var defs = LinkDirectiveProcessor.loadFederationImportedDefinitions(registry);
        if (defs == null) {
            return new AttributedRegistry(registry, Set.of());
        }
        var injectedNames = new LinkedHashSet<String>();
        defs.forEach(def -> {
            if (def instanceof NamedNode<?> named) {
                injectedNames.add(named.getName());
            }
        });
        return new AttributedRegistry(registry, injectedNames);
    }
}
