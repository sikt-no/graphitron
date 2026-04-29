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
 * <p>The canonical {@code @link} URL lives on
 * {@link no.sikt.graphitron.rewrite.schema.federation.FederationSpec#URL FederationSpec.URL}; this
 * class only consumes it indirectly via the registry contents.
 */
public final class FederationLinkApplier {

    private FederationLinkApplier() {}

    /**
     * Injects federation directive declarations derived from the schema's {@code @link} into
     * {@code registry}. Returns {@code true} when a federation {@code @link} was present and
     * definitions were injected; {@code false} when no federation {@code @link} was found and the
     * registry is unchanged. The pipeline orchestrator captures this return value into
     * {@link no.sikt.graphitron.rewrite.AttributedRegistry} so downstream stages do not re-walk
     * the registry to discover the same fact.
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
                var name = def instanceof graphql.language.NamedNode<?> n ? n.getName() : null;
                var ref = name != null ? "'@" + name + "'" : "a federation directive";
                throw new IllegalStateException(
                        "Your schema manually declares " + ref + ", but that directive is injected "
                        + "automatically by federation-graphql-java-support based on your @link import. "
                        + "Remove the manual " + ref + " directive definition from your schema SDL.");
            }
        });
        return true;
    }
}
