package no.sikt.graphitron.model.schema.input;

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
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.model.diagnostics.ValidationFailedException;
import no.sikt.graphitron.model.schema.federation.FederationSpec;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * When {@code <schemaInput tag>} is configured (i.e. any {@link SchemaInput} carries a
 * non-empty {@code tag}), this synthesiser ensures the registry has a federation
 * {@code @link} that imports {@code "@tag"}. It runs before {@link FederationLinkApplier} in the
 * {@link LoadingRewrites#apply} composition so the synthesised extension is processed by the
 * same {@code LinkDirectiveProcessor} call as author-written {@code @link}s.
 *
 * <p>Three outcomes:
 * <ol>
 *   <li>No {@code <schemaInput tag>} configured: no-op.</li>
 *   <li>No existing federation {@code @link}: synthesise
 *       {@code extend schema @link(url: FederationSpec.URL, import: ["@tag"])}
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

    /**
     * The source name stamped on the {@code extend schema @link(import: ["@tag"])} this class
     * synthesises. Public because it is one of the two generator-injected source names no
     * {@link SchemaInput} produced, so capture's stamp lookup has to name it to tolerate the miss
     * instead of absorbing it in a filesystem probe.
     */
    public static final String SYNTHESISED_SOURCE_NAME = "<graphitron-synthesised:tag-link>";
    private static final String FEDERATION_SPEC_PREFIX = no.sikt.graphitron.model.schema.federation.FederationSpec.SPEC_PREFIX;
    private static final String TAG_IMPORT_NAME = "@tag";

    private TagLinkSynthesiser() {}

    /**
     * Applies tag-link synthesis or validation to {@code registry} based on whether any
     * entry in {@code bySource} carries a tag, throwing the refusal's exception where it refuses.
     */
    public static void apply(TypeDefinitionRegistry registry, Map<String, SchemaInput> bySource) {
        if (synthesise(registry, bySource) instanceof Result.Refused refused) {
            throw refused.refusal().exception();
        }
    }

    /** What the synthesiser made of a registry. */
    sealed interface Result {

        /** No tag is configured, or the author's federation {@code @link} already imports {@code @tag}. */
        record Untouched() implements Result {}

        /** A tag is configured and the author wrote no federation {@code @link}, so one was added. */
        record Synthesised() implements Result {}

        /** The author's {@code @link} does not import {@code @tag}, or the registry refused the one added. */
        record Refused(LoadingRewrites.Refusal refusal) implements Result {}
    }

    /**
     * {@link #apply} with the outcome handed back rather than thrown, for {@link LoadingRewrites},
     * whose callers decide whether a refusal fails anything and record whether a link was added.
     */
    static Result synthesise(TypeDefinitionRegistry registry, Map<String, SchemaInput> bySource) {
        boolean anyTagged = bySource.values().stream().anyMatch(i -> i.tag().isPresent());
        if (!anyTagged) {
            return new Result.Untouched();
        }

        Optional<Directive> federationLink = findFederationLink(registry);
        if (federationLink.isEmpty()) {
            return synthesise(registry);
        }
        var link = federationLink.get();
        if (tagIsImported(link)) {
            return new Result.Untouched();
        }
        var loc = link.getSourceLocation();
        String locDesc = loc != null
                ? loc.getSourceName() + ":" + loc.getLine()
                : "(unknown location)";
        String message = "<schemaInput tag> is configured but '@tag' is not in the @link import list"
                + " at " + locDesc + ". Add \"@tag\" to the import array.";
        return new Result.Refused(new LoadingRewrites.Refusal.TagNotImported(message, loc,
                new ValidationFailedException(List.of(new ValidationError(
                        null, Rejection.invalidSchema(message), loc)))));
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

    private static Result synthesise(TypeDefinitionRegistry registry) {
        var linkDirective = Directive.newDirective()
                .name("link")
                .argument(Argument.newArgument()
                        .name("url")
                        .value(new StringValue(FederationSpec.URL))
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

        return registry.add(extension).<Result>map(error -> {
            String message = "Failed to inject synthesised federation @link: " + error.getMessage();
            return new Result.Refused(new LoadingRewrites.Refusal.SynthesisedLinkRefused(message,
                    new IllegalStateException(message)));
        }).orElseGet(Result.Synthesised::new);
    }
}
