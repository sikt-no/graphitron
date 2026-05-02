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
                    throw new IllegalStateException(buildManualDeclarationMessage(registry, def, error.get().getMessage()));
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
     * matching name already exists in the registry. The "matching name already exists" can come
     * from two places: a hand-written {@code directive @<name>} or {@code scalar <name>} in the
     * consumer's SDL, or a programmatic injection elsewhere in the build (another loader stage,
     * a Maven plugin, an SDK that pre-populates the registry). The two are diagnosed differently:
     * a user-typed declaration carries a {@link SourceLocation} with the file path and line so we
     * can point the developer straight at the offending text; a programmatic injection has a
     * source-name-less location and needs a different remediation, namely "go find the code that
     * registered this".
     */
    private static String buildManualDeclarationMessage(TypeDefinitionRegistry registry, SDLDefinition<?> def, String registryErrorMessage) {
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

        // No source file means the existing definition wasn't typed into a .graphqls file. The
        // most common cause is another stage of graphitron (or a build plugin layered on top of
        // graphitron) injecting the definition into the registry programmatically before
        // FederationLinkApplier runs. The federation library's own injection runs through this
        // same applier, so a self-collision here points at a third party.
        return "Federation directive injection collided with an existing " + kind + " " + ref
                + " in the registry, but that " + kind + " has no source file location, "
                + "so it was added programmatically rather than declared in a schema file. "
                + "Search your schema-loading pipeline (and any Maven plugins layered on top of "
                + "graphitron-maven) for code that adds " + ref + " before FederationLinkApplier "
                + "runs; then remove that programmatic injection so federation-graphql-java-support "
                + "can inject the canonical " + kind + " on its own. "
                + "(Underlying registry error: " + registryErrorMessage + ")";
    }

    private static SourceLocation findExistingDeclarationLocation(TypeDefinitionRegistry registry, SDLDefinition<?> def, String name) {
        if (name == null) return null;
        return def instanceof DirectiveDefinition
                ? registry.getDirectiveDefinition(name).map(DirectiveDefinition::getSourceLocation).orElse(null)
                : registry.getType(name).map(t -> t.getSourceLocation()).orElse(null);
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
