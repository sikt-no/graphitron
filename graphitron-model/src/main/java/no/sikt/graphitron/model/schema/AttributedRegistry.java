package no.sikt.graphitron.model.schema;

import com.apollographql.federation.graphqljava.directives.LinkDirectiveProcessor;
import graphql.language.NamedNode;
import graphql.schema.idl.TypeDefinitionRegistry;

import no.sikt.graphitron.model.capture.document.GraphQLAssemblyCapture;
import no.sikt.graphitron.model.grammar.NodeDeclaration;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.schema.federation.KeyNodeSynthesiser;
import no.sikt.graphitron.model.schema.input.LoadingRewrites;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The walk's input: the schema capture composed as written, with the federation keys
 * {@link KeyNodeSynthesiser} synthesises over it. It goes with the walk, the emitted schema being
 * capture's own and the store minting the keys it carries.
 *
 * <p>{@code injectedNames} are the definitions the federation {@code @link} injector added, taken
 * from {@link LoadingRewrites#apply}'s outcome; {@link #federationLink()} is whether there were
 * any. The lint engine excludes these names, being the generator-owned federation surface rather
 * than author input. A test building a registry ad hoc derives them with
 * {@link #from(TypeDefinitionRegistry)}.
 *
 * <p>{@code read} is what the parser and the reduce refused on the way. It rides along because the
 * registry alone cannot say what is missing from it: a type absent here may be one nobody declared
 * or one whose file did not parse, and only the refusals tell them apart.
 */
public record AttributedRegistry(TypeDefinitionRegistry registry,
                                 Set<String> injectedNames,
                                 SchemaLoader.PerSourceParse read) {

    public AttributedRegistry {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(read, "read");
        injectedNames = Set.copyOf(injectedNames);
    }

    /**
     * A registry whose stages refused nothing, which is what a caller that built one itself should
     * get: no stage of the loader ran on the way here, so nothing was refused on the way here.
     */
    public AttributedRegistry(TypeDefinitionRegistry registry, Set<String> injectedNames) {
        this(registry, injectedNames, new SchemaLoader.PerSourceParse(registry, List.of(), List.of()));
    }

    /** True when the federation {@code @link} injector contributed any definitions. */
    public boolean federationLink() {
        return !injectedNames.isEmpty();
    }

    /**
     * Inspects the registry for a federation {@code @link} extension and wraps it, deriving
     * {@code injectedNames} the way {@code FederationLinkApplier.apply} collects them. For tests;
     * production takes the set from {@link LoadingRewrites#apply}'s outcome.
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

    /**
     * The walk's input over the schema capture assembled.
     *
     * <p>Capture has parsed, reduced and composed the corpus and recorded what each stage refused;
     * this adds the key synthesis and nothing else. A refusal of the loading rewrites throws, and
     * the parser's and the reduce's ride along in {@link #read}.
     *
     * <p>The reading is not edited: synthesis runs on a copy of its composition, so one capture can
     * serve every pass a caller runs over it.
     */
    public static AttributedRegistry of(GraphQLAssemblyCapture.AssemblyReading reading,
                                        JooqCatalog jooq) {
        var composed = switch (reading.composition()) {
            case LoadingRewrites.Outcome.Applied applied -> applied;
            case LoadingRewrites.Outcome.Refused refused -> throw refused.refusal().exception();
        };
        // Replayed through the reduce rather than TypeDefinitionRegistry.merge, which rebuilds the
        // parse order kind by kind and so hands back the same definitions in a different order.
        var registry = SchemaLoader.merge(List.of(composed.registry())).registry();
        if (!composed.injectedNames().isEmpty()) {
            KeyNodeSynthesiser.apply(registry, new NodeDeclaration(jooq));
        }
        return new AttributedRegistry(registry, composed.injectedNames(), reading.read());
    }
}
