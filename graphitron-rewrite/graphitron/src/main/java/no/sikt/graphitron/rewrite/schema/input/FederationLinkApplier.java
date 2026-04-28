package no.sikt.graphitron.rewrite.schema.input;

import com.apollographql.federation.graphqljava.directives.LinkDirectiveProcessor;
import graphql.schema.idl.TypeDefinitionRegistry;

/**
 * Injects federation directive declarations into the registry when the schema contains an
 * {@code extend schema @link(url: "https://specs.apollo.dev/federation/v2.x", import: [...])}
 * extension. Delegates to {@link LinkDirectiveProcessor#loadFederationImportedDefinitions} from
 * {@code federation-graphql-java-support}, which tracks the federation spec and gates each
 * directive on its minimum spec version.
 *
 * <p>Runs in the {@code loadAttributedRegistry} pipeline before {@link TagApplier}, so the
 * injected declarations are available when {@code SchemaGenerator.makeExecutableSchema} validates
 * directive uses. Must run after any schema-extension synthesiser (e.g. {@link TagLinkSynthesiser})
 * that injects a {@code @link} the author omitted.
 *
 * <p>{@link #DEFAULT_FEDERATION_SPEC_URL} is the baseline URL used by {@link TagLinkSynthesiser}
 * when synthesising a {@code @link} for a consumer who sets {@code <schemaInput tag>} without
 * writing their own {@code @link}. It is pinned to the latest version supported by the
 * {@code federation-graphql-java-support} library bundled with this release; bump when a
 * consumer needs directives gated behind a newer spec version.
 */
public final class FederationLinkApplier {

    public static final String DEFAULT_FEDERATION_SPEC_URL =
            "https://specs.apollo.dev/federation/v2.10";

    private FederationLinkApplier() {}

    /**
     * Returns {@code true} if the registry contains a federation {@code @link} extension
     * (regardless of whether {@link #apply} has been called). Safe to call after {@link #apply}
     * has already processed the same registry; it does not re-add definitions.
     */
    public static boolean hasFederationLink(TypeDefinitionRegistry registry) {
        return LinkDirectiveProcessor.loadFederationImportedDefinitions(registry) != null;
    }

    /**
     * Injects federation directive declarations derived from the schema's {@code @link} into
     * {@code registry}. Returns {@code true} when a federation {@code @link} was present and
     * definitions were injected; {@code false} when no federation {@code @link} was found and the
     * registry is unchanged.
     *
     * <p>Throws {@link com.apollographql.federation.graphqljava.exceptions.MultipleFederationLinksException}
     * if the schema contains more than one federation {@code @link}, and
     * {@link com.apollographql.federation.graphqljava.exceptions.UnsupportedFederationVersionException}
     * if the {@code @link} URL names a federation spec version the library does not yet support.
     * Both are programming errors in the consumer SDL and are treated as hard build failures.
     */
    public static boolean apply(TypeDefinitionRegistry registry) {
        var defs = LinkDirectiveProcessor.loadFederationImportedDefinitions(registry);
        if (defs == null) {
            return false;
        }
        defs.forEach(def -> {
            var error = registry.add(def);
            if (error.isPresent()) {
                throw new IllegalStateException(
                        "federation-graphql-java-support injected a duplicate definition: "
                        + error.get().getMessage());
            }
        });
        return true;
    }
}
