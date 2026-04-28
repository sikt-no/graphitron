package no.sikt.graphitron.rewrite.schema.input;

import graphql.language.Argument;
import graphql.language.ArrayValue;
import graphql.language.Directive;
import graphql.language.ObjectValue;
import graphql.language.SchemaDefinition;
import graphql.language.SchemaExtensionDefinition;
import graphql.language.SourceLocation;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.rewrite.RejectionKind;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.ValidationFailedException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * When {@code <schemaInput tag>} is configured (i.e. any {@link SchemaInput} carries a
 * non-empty {@code tag}), this synthesiser ensures the registry has a federation
 * {@code @link} that imports {@code "@tag"}. It runs before {@link FederationLinkApplier} in the
 * {@code loadAttributedRegistry} pipeline so the synthesised extension is processed by the
 * same {@code LinkDirectiveProcessor} call as author-written {@code @link}s.
 *
 * <p>Three outcomes:
 * <ol>
 *   <li>No {@code <schemaInput tag>} configured: no-op.</li>
 *   <li>No existing federation {@code @link}: synthesise
 *       {@code extend schema @link(url: DEFAULT_FEDERATION_SPEC_URL, import: ["@tag"])}
 *       with a sentinel source name so downstream error messages identify it
 *       as generator-produced.</li>
 *   <li>Existing federation {@code @link} with {@code "@tag"} (or an alias) in
 *       {@code import}: no-op.</li>
 *   <li>Existing federation {@code @link} without {@code "@tag"} in {@code import}:
 *       fatal {@link ValidationError} pointing at the {@code @link} directive's
 *       source location.</li>
 * </ol>
 */
public final class TagLinkSynthesiser {

    static final String SYNTHESISED_SOURCE_NAME = "<graphitron-synthesised:tag-link>";
    private static final String FEDERATION_SPEC_PREFIX = "https://specs.apollo.dev/federation/";
    private static final String TAG_IMPORT_NAME = "@tag";

    private TagLinkSynthesiser() {}

    /**
     * Applies tag-link synthesis or validation to {@code registry} based on whether any
     * entry in {@code bySource} carries a tag.
     */
    public static void apply(TypeDefinitionRegistry registry, Map<String, SchemaInput> bySource) {
        boolean anyTagged = bySource.values().stream().anyMatch(i -> i.tag().isPresent());
        if (!anyTagged) {
            return;
        }

        Optional<Directive> federationLink = findFederationLink(registry);
        if (federationLink.isEmpty()) {
            synthesise(registry);
        } else {
            var link = federationLink.get();
            if (!tagIsImported(link)) {
                var loc = link.getSourceLocation();
                String locDesc = loc != null
                        ? loc.getSourceName() + ":" + loc.getLine()
                        : "(unknown location)";
                throw new ValidationFailedException(List.of(new ValidationError(
                        RejectionKind.INVALID_SCHEMA,
                        null,
                        "<schemaInput tag> is configured but '@tag' is not in the @link import list"
                        + " at " + locDesc + ". Add \"@tag\" to the import array.",
                        loc)));
            }
            // @tag is imported; no synthesis needed.
        }
    }

    private static Optional<Directive> findFederationLink(TypeDefinitionRegistry registry) {
        return Stream.concat(
                        registry.schemaDefinition()
                                .map(sd -> sd.getDirectives("link").stream())
                                .orElse(Stream.empty()),
                        registry.getSchemaExtensionDefinitions().stream()
                                .flatMap(ext -> ext.getDirectives("link").stream()))
                .filter(TagLinkSynthesiser::isFederationLink)
                .findFirst();
    }

    private static boolean isFederationLink(Directive directive) {
        Argument urlArg = directive.getArgument("url");
        if (urlArg == null) return false;
        Value<?> urlVal = urlArg.getValue();
        return urlVal instanceof StringValue sv
                && sv.getValue().startsWith(FEDERATION_SPEC_PREFIX);
    }

    private static boolean tagIsImported(Directive link) {
        Argument importArg = link.getArgument("import");
        if (importArg == null) return false;
        if (!(importArg.getValue() instanceof ArrayValue arr)) return false;
        for (Value<?> item : arr.getValues()) {
            if (item instanceof StringValue sv && TAG_IMPORT_NAME.equals(sv.getValue())) {
                return true;
            }
            if (item instanceof ObjectValue ov) {
                Optional<String> nameVal = ov.getObjectFields().stream()
                        .filter(f -> "name".equals(f.getName()))
                        .map(f -> f.getValue() instanceof StringValue s ? s.getValue() : null)
                        .findFirst();
                if (TAG_IMPORT_NAME.equals(nameVal.orElse(null))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void synthesise(TypeDefinitionRegistry registry) {
        var linkDirective = Directive.newDirective()
                .name("link")
                .argument(Argument.newArgument()
                        .name("url")
                        .value(new StringValue(FederationLinkApplier.DEFAULT_FEDERATION_SPEC_URL))
                        .build())
                .argument(Argument.newArgument()
                        .name("import")
                        .value(ArrayValue.newArrayValue()
                                .value(new StringValue(TAG_IMPORT_NAME))
                                .build())
                        .build())
                .build();

        var extension = SchemaExtensionDefinition.newSchemaExtensionDefinition()
                .sourceLocation(new SourceLocation(1, 1, SYNTHESISED_SOURCE_NAME))
                .directive(linkDirective)
                .build();

        var error = registry.add(extension);
        if (error.isPresent()) {
            throw new IllegalStateException(
                    "Failed to inject synthesised federation @link: " + error.get().getMessage());
        }
    }
}
