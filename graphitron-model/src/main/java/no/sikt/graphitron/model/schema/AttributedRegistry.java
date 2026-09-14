package no.sikt.graphitron.model.schema;

import com.apollographql.federation.graphqljava.directives.LinkDirectiveProcessor;
import graphql.language.NamedNode;
import graphql.schema.idl.TypeDefinitionRegistry;

import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.grammar.NodeDeclaration;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.schema.federation.KeyNodeSynthesiser;
import no.sikt.graphitron.model.schema.input.DescriptionNoteApplier;
import no.sikt.graphitron.model.schema.input.FederationLinkApplier;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaInputAttribution;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import no.sikt.graphitron.model.schema.input.TagApplier;
import no.sikt.graphitron.model.schema.input.TagLinkSynthesiser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The output of {@link #load}: a
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
 * {@link no.sikt.graphitron.model.schema.input.FederationLinkApplier#apply}'s return value;
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

    /**
     * Reads the run's schema sources and applies every rewrite that stands between a file on disk
     * and the registry the rest of the run sees.
     *
     * <p>Every source is read and none is refused on another's behalf: a source that will not parse
     * costs its own declarations and nothing else, and a declaration the registry will not admit
     * costs itself. The refusals ride along in {@link #read} rather than being thrown here, so the
     * run's verdict on them is pronounced by whoever asked for the read, after the facts about the
     * files beside them have been recorded.
     *
     * <p>The catalog is reached only when the federation {@code @link} injector produced names,
     * {@code @key} synthesis resolving its node declarations against it. Taking it as a parameter
     * is what keeps a run to one load of the generated classes rather than one per caller that
     * wants them.
     *
     * <p>This is the capture layer's because it is nobody else's: it parses, applies four
     * configuration-driven rewrites, and synthesises, and a validator or a language server wants
     * exactly that without wanting a generator. It used to live on the generator, which is why a
     * goal whose whole job is capture had to construct one.
     */
    public static AttributedRegistry load(RunContext ctx, JooqCatalog jooq) {
        var bySource = SchemaInputAttribution.build(ctx.schemaInputs());
        var read = SchemaLoader.parsePerSource(loadableSources(ctx.schemaInputs()));
        var registry = read.registry();
        TagLinkSynthesiser.apply(registry, bySource);
        var injectedNames = FederationLinkApplier.apply(registry);
        TagApplier.apply(registry, bySource);
        DescriptionNoteApplier.apply(registry, bySource);
        // Everything above is a loading rewrite and everything below is synthesis, which is the
        // line the capture handle is cut on. TagApplier and DescriptionNoteApplier sit above it
        // deliberately: their @tag applications and appended notes are in the emitted schema, and
        // the store owes a round trip, so capture has to see them.
        var preSynthesis = registry.readOnly();
        if (!injectedNames.isEmpty()) {
            KeyNodeSynthesiser.apply(registry, new NodeDeclaration(jooq));
        }
        return new AttributedRegistry(registry, preSynthesis, injectedNames, read);
    }

    /**
     * {@link #load} over a catalog the caller has not built, for one that has no use for a catalog
     * beyond what synthesis might want of it.
     */
    public static AttributedRegistry load(RunContext ctx) {
        return load(ctx, new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader()));
    }

    /**
     * The sources a parse can be attempted on. A label carries no file, so it is refused here with
     * the loader's own words rather than silently shortening the corpus: no context in the tree
     * carries a label this far, which makes this a guard against a new source kind rather than a
     * live path.
     */
    private static List<SchemaSource.File> loadableSources(List<SchemaInput> inputs) {
        var sources = new ArrayList<SchemaSource.File>(inputs.size());
        for (SchemaInput input : inputs) {
            switch (input.source()) {
                case SchemaSource.File file -> sources.add(file);
                case SchemaSource.Named named ->
                    throw new RuntimeException("Schema file not found: " + named.label());
            }
        }
        return sources;
    }
}
