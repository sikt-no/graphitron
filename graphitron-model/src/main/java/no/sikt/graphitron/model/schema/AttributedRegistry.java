package no.sikt.graphitron.model.schema;

import com.apollographql.federation.graphqljava.directives.LinkDirectiveProcessor;
import graphql.language.NamedNode;
import graphql.schema.idl.TypeDefinitionRegistry;

import no.sikt.graphitron.model.capture.document.GraphQLAssemblyCapture;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.grammar.NodeDeclaration;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.schema.federation.KeyNodeSynthesiser;
import no.sikt.graphitron.model.schema.input.LoadingRewrites;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaSource;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The output of {@link #of}: a
 * {@link TypeDefinitionRegistry} paired with the names of the definitions the federation
 * {@code @link} injector added to it, plus the handle on the same registry as it stood before the
 * synthesis rewrites.
 *
 * <p>{@code preSynthesisRegistry} exists because {@code KeyNodeSynthesiser} rewrites in place: a
 * consumer that wants the schema an author wrote plus the loading rewrites, and not what synthesis
 * made of it, has nothing to read once the rewrite has run. It is what the generator's own verdict
 * assembles. Capture does not take this handle: its assembly composes the corpus with the same
 * {@link LoadingRewrites} and judges that, so the two verdicts are over one schema, and its macro
 * expansion is the thing that mints the federation keys rather than finding them already there.
 * Emission does take it: {@link EmittedRegistry#of} patches this handle with what the store says
 * synthesis added, which on the other handle would be applied a second time. Loading rewrites are
 * on both sides of the cut, so the configured tags and appended notes are on this handle too; only
 * synthesis is on one.
 *
 * <p>{@code injectedNames} is captured once, by the pipeline orchestrator, from
 * {@link LoadingRewrites#apply}'s outcome;
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
                                 SchemaLoader.PerSourceParse read) {

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
            new SchemaLoader.PerSourceParse(registry, List.of(), List.of()));
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
     * directly from {@link LoadingRewrites#apply}'s outcome.
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
     * The registry the run classifies, from the schema capture assembled rather than from a second
     * reading of the documents.
     *
     * <p>Capture has already parsed, reduced and composed the corpus, and recorded what each stage
     * refused; what it does not do is synthesis, which is all this adds. A refusal of the loading
     * rewrites throws here, the exception each rewrite has always thrown, and the parser's and the
     * reduce's refusals ride along in {@link #read} for the run to pronounce on.
     *
     * <p>The reading is not edited. Synthesis runs on a copy of its composition, so one capture can
     * serve every pass a caller runs over it.
     */
    public static AttributedRegistry of(GraphQLAssemblyCapture.AssemblyReading reading,
                                        JooqCatalog jooq) {
        var composed = applied(reading.composition());
        // Replayed through the reduce rather than TypeDefinitionRegistry.merge, which rebuilds the
        // parse order kind by kind and so hands back the same definitions in a different order.
        var copy = SchemaLoader.merge(List.of(composed.registry())).registry();
        return synthesised(reading.read(), copy, composed.injectedNames(), jooq);
    }

    /** The composed registry, or the exception the loading rewrites refused with. */
    private static LoadingRewrites.Outcome.Applied applied(LoadingRewrites.Outcome composition) {
        return switch (composition) {
            case LoadingRewrites.Outcome.Applied applied -> applied;
            case LoadingRewrites.Outcome.Refused refused -> throw refused.refusal().exception();
        };
    }

    /** Synthesises over {@code registry} in place, keeping a read-only copy of it from before. */
    private static AttributedRegistry synthesised(SchemaLoader.PerSourceParse read,
                                                  TypeDefinitionRegistry registry,
                                                  Set<String> injectedNames, JooqCatalog jooq) {
        // The line the pre-synthesis handle is cut on: every loading rewrite is behind it, TagApplier
        // and DescriptionNoteApplier included, and synthesis is ahead of it. Capture composes
        // through the same rewrites, so its assembly and this handle are one schema.
        var preSynthesis = registry.readOnly();
        if (!injectedNames.isEmpty()) {
            KeyNodeSynthesiser.apply(registry, new NodeDeclaration(jooq));
        }
        return new AttributedRegistry(registry, preSynthesis, injectedNames, read);
    }
}
