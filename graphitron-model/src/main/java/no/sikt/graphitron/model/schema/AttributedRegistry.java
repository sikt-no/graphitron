package no.sikt.graphitron.model.schema;

import com.apollographql.federation.graphqljava.directives.LinkDirectiveProcessor;
import graphql.language.NamedNode;
import graphql.schema.idl.TypeDefinitionRegistry;

import no.sikt.graphitron.model.capture.document.GraphQLAssemblyCapture;
import no.sikt.graphitron.model.grammar.NodeDeclaration;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.schema.federation.KeyNodeSynthesiser;
import no.sikt.graphitron.model.schema.input.LoadingRewrites;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_VOUCHER_FIELD;


/**
 * The walk's input: the schema capture composed as written, less the fields a voucher absorbs, with
 * the federation keys {@link KeyNodeSynthesiser} synthesises over it. It goes with the walk, the emitted schema being
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
 *
 * <p>{@code storeOwned} are the fields the store owns whole: the voucher field Graphitron mints on each
 * type a voucher marker sits on, as {@code graphitron_voucher_field} states them. They are added to the
 * registry so the walk's schema is the one the subgraph serves, an issuing type keeping a field once
 * its written ones are absorbed, and the walk's verdict on them stands down, the store's defects being
 * the only refusals they have. Read here, at the one point the walk is handed its registry, as the
 * membership removal is, so no consumer of the walk decides it for itself.
 */
public record AttributedRegistry(TypeDefinitionRegistry registry,
                                 Set<String> injectedNames,
                                 SchemaLoader.PerSourceParse read,
                                 Set<String> storeOwned) {

    public AttributedRegistry {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(read, "read");
        injectedNames = Set.copyOf(injectedNames);
        storeOwned = Set.copyOf(storeOwned);
    }

    /** A registry the store owns no field of. */
    public AttributedRegistry(TypeDefinitionRegistry registry, Set<String> injectedNames,
                              SchemaLoader.PerSourceParse read) {
        this(registry, injectedNames, read, Set.of());
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
     * this withholds the voucher members, by the removal the emitted schema takes
     * ({@code EmittedRegistry}'s, reading {@code graphitron_voucher_member}), adds the voucher fields
     * Graphitron mints, and adds the key synthesis, and nothing else. A refusal of the loading rewrites throws, and
     * the parser's and the reduce's ride along in {@link #read}.
     *
     * <p>The reading is not edited: synthesis runs on a copy of its composition, so one capture can
     * serve every pass a caller runs over it.
     */
    public static AttributedRegistry of(GraphQLAssemblyCapture.AssemblyReading reading,
                                        JooqCatalog jooq, StoreHandle store) {
        var composed = switch (reading.composition()) {
            case LoadingRewrites.Outcome.Applied applied -> applied;
            case LoadingRewrites.Outcome.Refused refused -> throw refused.refusal().exception();
        };
        // Replayed through the reduce rather than TypeDefinitionRegistry.merge, which rebuilds the
        // parse order kind by kind and so hands back the same definitions in a different order.
        var registry = SchemaLoader.merge(List.of(composed.registry())).registry();
        EmittedRegistry.withhold(registry, store);
        EmittedRegistry.mintVoucherFields(registry, store);
        if (!composed.injectedNames().isEmpty()) {
            KeyNodeSynthesiser.apply(registry, new NodeDeclaration(jooq));
        }
        return new AttributedRegistry(registry, composed.injectedNames(), reading.read(), voucherFields(store));
    }

    /** Every minted voucher field's coordinate, {@code Type.internalGraphitronVoucher}, on either side. */
    static Set<String> voucherFields(StoreHandle store) {
        var v = GRAPHITRON_VOUCHER_FIELD;
        return new TreeSet<>(store.dsl().select(v.COORDINATE).from(v)
            .where(v.GRAPH_NAME.eq(store.graphName()))
            .fetch(v.COORDINATE));
    }
}
