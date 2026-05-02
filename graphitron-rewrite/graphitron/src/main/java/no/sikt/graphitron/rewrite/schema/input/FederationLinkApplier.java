package no.sikt.graphitron.rewrite.schema.input;

import com.apollographql.federation.graphqljava.directives.LinkDirectiveProcessor;
import com.apollographql.federation.graphqljava.exceptions.MultipleFederationLinksException;
import graphql.language.Argument;
import graphql.language.Directive;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.idl.TypeDefinitionRegistry;

import java.util.ArrayList;
import java.util.stream.Stream;

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

    private static final String FEDERATION_SPEC_PREFIX = "https://specs.apollo.dev/federation/";

    private FederationLinkApplier() {}

    /**
     * Injects federation directive declarations derived from the schema's {@code @link} into
     * {@code registry}. Returns {@code true} when a federation {@code @link} was present and
     * definitions were injected; {@code false} when no federation {@code @link} was found and the
     * registry is unchanged. The pipeline orchestrator captures this return value into
     * {@link no.sikt.graphitron.rewrite.AttributedRegistry} so downstream stages do not re-walk
     * the registry to discover the same fact.
     *
     * <p>Throws an {@link IllegalStateException} wrapping
     * {@link MultipleFederationLinksException} if the schema contains more than one federation
     * {@code @link}; the wrapper's message lists every offending {@code @link}'s source file and
     * line so the developer can find them. Throws
     * {@link com.apollographql.federation.graphqljava.exceptions.UnsupportedFederationVersionException}
     * if the {@code @link} URL names a federation spec version the library does not yet support.
     * Both are programming errors in the consumer SDL and are treated as hard build failures.
     */
    public static boolean apply(TypeDefinitionRegistry registry) {
        try {
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
        } catch (MultipleFederationLinksException e) {
            throw new IllegalStateException(buildMultipleLinksMessage(registry), e);
        }
    }

    /**
     * Builds a developer-readable message replacing the federation library's raw
     * {@code Directive{...}Directive{...}} dump. Walks the schema definition and every schema
     * extension for {@code @link} directives whose {@code url} argument starts with
     * {@link #FEDERATION_SPEC_PREFIX}, and lists each one with its source file and line so the
     * developer can find and merge them. Falls back to the original message when no federation
     * {@code @link}s are visible (defensive: should not happen if the federation library raised
     * the exception).
     */
    private static String buildMultipleLinksMessage(TypeDefinitionRegistry registry) {
        var entries = new ArrayList<String>();
        Stream.concat(
                        registry.schemaDefinition()
                                .map(sd -> sd.getDirectives("link").stream())
                                .orElse(Stream.empty()),
                        registry.getSchemaExtensionDefinitions().stream()
                                .flatMap(ext -> ext.getDirectives("link").stream()))
                .filter(FederationLinkApplier::isFederationLink)
                .forEach(d -> entries.add(formatLinkLocation(d)));

        var sb = new StringBuilder("Schema declares more than one federation @link. ");
        sb.append("federation-graphql-java-support requires exactly one federation @link per schema; ");
        sb.append("merge the imports into a single declaration on a single federation spec version.");
        if (!entries.isEmpty()) {
            sb.append("\nFederation @link declarations found:");
            entries.forEach(line -> sb.append("\n  - ").append(line));
        }
        return sb.toString();
    }

    private static boolean isFederationLink(Directive directive) {
        return federationUrl(directive) != null;
    }

    private static String federationUrl(Directive directive) {
        Argument urlArg = directive.getArgument("url");
        if (urlArg == null) return null;
        Value<?> v = urlArg.getValue();
        if (v instanceof StringValue sv && sv.getValue() != null
                && sv.getValue().startsWith(FEDERATION_SPEC_PREFIX)) {
            return sv.getValue();
        }
        return null;
    }

    private static String formatLinkLocation(Directive directive) {
        var loc = directive.getSourceLocation();
        String url = federationUrl(directive);
        String where = (loc != null && loc.getSourceName() != null)
                ? loc.getSourceName() + ":" + loc.getLine()
                : "(unknown location)";
        return where + " (url: " + url + ")";
    }
}
