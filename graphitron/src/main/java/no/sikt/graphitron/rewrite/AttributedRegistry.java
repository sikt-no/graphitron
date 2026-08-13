package no.sikt.graphitron.rewrite;

import com.apollographql.federation.graphqljava.directives.LinkDirectiveProcessor;
import graphql.language.NamedNode;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The output of {@link GraphQLRewriteGenerator#loadAttributedRegistry}: a
 * {@link TypeDefinitionRegistry} paired with the names of the definitions the federation
 * {@code @link} injector added to it, plus the handle on the same registry as it stood before the
 * synthesis rewrites.
 *
 * <p>{@code preSynthesisRegistry} exists because {@code KeyNodeSynthesiser} rewrites in place: a
 * consumer that wants the schema an author wrote plus the loading rewrites, and not what synthesis
 * made of it, has nothing to read once the rewrite has run. It is the handle the fact-capture loads
 * take, so their macro expansion is the thing that mints the federation keys rather than finding
 * them already there. Loading rewrites are on both sides of it; only synthesis is on one.
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
 *
 * <p>{@code read} is the outcome of the two stages that produced the registry: which sources the
 * parser refused, and which declarations the registry refused to admit. It rides along because the
 * registry alone cannot say what is missing from it. A type absent here has two very different
 * explanations, that nobody declared it and that the file declaring it did not parse, and only the
 * refusal list tells them apart; any consumer that would otherwise read the absence as the author's
 * intent needs it.
 */
public record AttributedRegistry(TypeDefinitionRegistry registry,
                                 TypeDefinitionRegistry preSynthesisRegistry,
                                 Set<String> injectedNames,
                                 RewriteSchemaLoader.PerSourceParse read) {

    public AttributedRegistry {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(preSynthesisRegistry, "preSynthesisRegistry");
        Objects.requireNonNull(read, "read");
        injectedNames = Set.copyOf(injectedNames);
    }

    /**
     * A registry no synthesis has run over yet, for callers that build one without the pipeline:
     * the two handles are the same object, which is what "before synthesis" means when nothing
     * synthesised.
     */
    public AttributedRegistry(TypeDefinitionRegistry registry, Set<String> injectedNames) {
        this(registry, registry, injectedNames);
    }

    /**
     * A registry whose stages refused nothing, which is what a caller that built one itself should
     * get: no stage of the loader ran on the way here, so nothing was refused on the way here.
     */
    public AttributedRegistry(TypeDefinitionRegistry registry,
                              TypeDefinitionRegistry preSynthesisRegistry,
                              Set<String> injectedNames) {
        this(registry, preSynthesisRegistry, injectedNames,
            new RewriteSchemaLoader.PerSourceParse(registry, List.of(), List.of()));
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
