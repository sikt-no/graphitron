package no.sikt.graphitron.rewrite.schema.input;

import com.apollographql.federation.graphqljava.directives.LinkDirectiveProcessor;
import com.apollographql.federation.graphqljava.exceptions.MultipleFederationLinksException;
import graphql.language.Argument;
import graphql.language.ArrayValue;
import graphql.language.Directive;
import graphql.language.DirectiveDefinition;
import graphql.language.NamedNode;
import graphql.language.ObjectValue;
import graphql.language.SDLDefinition;
import graphql.language.SourceLocation;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.idl.TypeDefinitionRegistry;

import java.util.ArrayList;
import java.util.Objects;
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
                    throw new IllegalStateException(buildCollisionMessage(registry, def));
                }
            });
            return true;
        } catch (MultipleFederationLinksException e) {
            // Drop the cause: its message is a raw Directive{...}Directive{...} dump that
            // Maven appends to ours. buildMultipleLinksMessage produces a developer-friendly
            // replacement that already names every offending @link, its url, and its imports.
            throw new IllegalStateException(buildMultipleLinksMessage(registry));
        }
    }

    /**
     * Builds the error message for a definition that the federation library is injecting but a
     * matching name already exists in the registry. Two distinct causes need different remediation:
     * a hand-written declaration in the consumer's SDL (carries a {@link SourceLocation} with a
     * file path) gets a "remove the manual declaration at file:line" message; a source-name-less
     * existing definition is the federation library injecting the same directive twice, which is
     * a known bug in {@code federation-graphql-java-support} v6.0.0's bundled
     * {@code definitions_fed2_6.graphqls} and {@code definitions_fed2_7.graphqls} (each declares
     * {@code directive @tag} twice). The remediation there is to bump the consumer's {@code @link}
     * URL to a non-buggy spec version, not to touch graphitron or any consumer code.
     */
    private static String buildCollisionMessage(TypeDefinitionRegistry registry, SDLDefinition<?> def) {
        String name = def instanceof NamedNode<?> n ? n.getName() : null;
        boolean isDirective = def instanceof DirectiveDefinition;
        String kind = isDirective ? "directive" : "type";
        String ref = name != null
                ? "'" + (isDirective ? "@" : "") + name + "'"
                : "a federation " + kind;
        SourceLocation existingLoc = findExistingDeclarationLocation(registry, def, name);
        boolean hasSourceFile = existingLoc != null && existingLoc.getSourceName() != null;

        if (hasSourceFile) {
            String at = existingLoc.getSourceName() + ":" + existingLoc.getLine();
            return "Your schema declares " + ref + " at " + at + ", but that " + kind + " is injected "
                    + "automatically by federation-graphql-java-support based on your @link import. "
                    + "Remove the manual " + ref + " " + kind + " definition from your schema SDL.";
        }

        // No source file means the existing entry was not parsed from any .graphqls; it was added
        // by federation-graphql-java-support itself. The library's own SDL for federation v2.6 and
        // v2.7 declares `directive @tag` twice in the same file (once with the v2.0 location set,
        // again with the v2.4 location set that adds SCHEMA), so loadFederationImportedDefinitions
        // returns @tag twice and the second registry.add fails. The fix is to move off the buggy
        // spec version, since v2.8+ and v2.5- declare each directive exactly once.
        String version = federationLinkVersion(registry);
        if ("v2.6".equals(version) || "v2.7".equals(version)) {
            return "Your @link URL points at federation " + version + ", whose SDL bundled with "
                    + "federation-graphql-java-support 6.0.0 declares " + ref + " twice. The library "
                    + "returns the duplicate to graphitron, which then fails on the second registry "
                    + "add. This is a federation-graphql-java-support bug, not a problem with your "
                    + "schema or with graphitron.\n\n"
                    + "Workaround: change your @link URL to federation v2.8 or later, or to v2.5 or "
                    + "earlier. v2.6 and v2.7 are the only affected spec versions.";
        }
        return "federation-graphql-java-support returned " + ref + " twice from "
                + "loadFederationImportedDefinitions, so the second registry add collided with the "
                + "first. This is a library bug; the federation @link URL in your schema is "
                + (version != null ? "'" + version + "'" : "(could not be determined)")
                + ". Try a different federation spec version.";
    }

    private static SourceLocation findExistingDeclarationLocation(TypeDefinitionRegistry registry, SDLDefinition<?> def, String name) {
        if (name == null) return null;
        return def instanceof DirectiveDefinition
                ? registry.getDirectiveDefinition(name).map(DirectiveDefinition::getSourceLocation).orElse(null)
                : registry.getType(name).map(t -> t.getSourceLocation()).orElse(null);
    }

    /**
     * Returns the {@code v2.X} suffix from the schema's federation {@code @link} URL, or
     * {@code null} when no federation {@code @link} is found. Used to pinpoint the
     * {@code federation-graphql-java-support} double-declaration bug to a specific spec version.
     */
    private static String federationLinkVersion(TypeDefinitionRegistry registry) {
        return Stream.concat(
                        registry.schemaDefinition()
                                .map(sd -> sd.getDirectives("link").stream())
                                .orElse(Stream.empty()),
                        registry.getSchemaExtensionDefinitions().stream()
                                .flatMap(ext -> ext.getDirectives("link").stream()))
                .map(FederationLinkApplier::federationUrl)
                .filter(Objects::nonNull)
                .map(url -> {
                    int slash = url.lastIndexOf('/');
                    return slash >= 0 ? url.substring(slash + 1) : url;
                })
                .findFirst()
                .orElse(null);
    }

    /**
     * Builds a developer-readable message replacing the federation library's raw
     * {@code Directive{...}Directive{...}} dump. Walks the schema definition and every schema
     * extension for {@code @link} directives whose {@code url} argument starts with
     * {@link #FEDERATION_SPEC_PREFIX}, and lists each one with its source file, line, url, and
     * imports so the developer can see exactly what to merge. Falls back to a bare message when
     * no federation {@code @link}s are visible (defensive: should not happen if the federation
     * library raised the exception).
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

        var sb = new StringBuilder("Your schema declares more than one federation @link, ");
        sb.append("but federation-graphql-java-support allows only one. ");
        sb.append("Merge the imports below into a single @link on a single federation spec version, ");
        sb.append("and remove the others.");
        if (!entries.isEmpty()) {
            sb.append("\n\nFederation @link declarations found:");
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
        var sb = new StringBuilder(where);
        sb.append("\n      url:    ").append(url);
        String imports = formatImports(directive);
        if (imports != null) {
            sb.append("\n      import: ").append(imports);
        }
        return sb.toString();
    }

    private static String formatImports(Directive directive) {
        Argument importArg = directive.getArgument("import");
        if (importArg == null || !(importArg.getValue() instanceof ArrayValue arr)) {
            return null;
        }
        var names = new ArrayList<String>();
        for (Value<?> item : arr.getValues()) {
            if (item instanceof StringValue sv) {
                names.add(sv.getValue());
            } else if (item instanceof ObjectValue ov) {
                String name = objectFieldString(ov, "name");
                String alias = objectFieldString(ov, "as");
                if (name != null && alias != null) {
                    names.add(name + " as " + alias);
                } else if (name != null) {
                    names.add(name);
                }
            }
        }
        return "[" + String.join(", ", names) + "]";
    }

    private static String objectFieldString(ObjectValue ov, String fieldName) {
        return ov.getObjectFields().stream()
                .filter(f -> fieldName.equals(f.getName()))
                .map(f -> f.getValue() instanceof StringValue s ? s.getValue() : null)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }
}
