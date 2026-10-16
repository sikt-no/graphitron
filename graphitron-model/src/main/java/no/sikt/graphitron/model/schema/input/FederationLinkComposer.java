package no.sikt.graphitron.model.schema.input;

import graphql.language.Argument;
import graphql.language.ArrayValue;
import graphql.language.Directive;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.SchemaDefinition;
import graphql.language.SchemaExtensionDefinition;
import graphql.language.SourceLocation;
import graphql.language.StringValue;
import graphql.schema.idl.ScalarInfo;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.schema.federation.FederationSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Puts the federation link a graph's capture settled on into the registry the composition hands
 * federation-jvm: the author's {@code @link} with its imports as the store states them, or, where
 * only the configuration opts in, a link of graphitron's own.
 *
 * <p>The imports are the store's, {@code graphitron_federation_link_import}: what the author wrote,
 * and after it what graphitron applies itself, so the emitted schema never uses a federation
 * directive its link does not import. The author's link is rewritten rather than joined by a second
 * one, federation allowing one link per schema, and it keeps the position the author wrote it at.
 */
public final class FederationLinkComposer {

    /**
     * The source name stamped on the link this class adds where only the configuration opts in.
     * Public because it is one of the generator-injected source names no {@link SchemaInput}
     * produced, which capture's stamp lookup and the lint's authored positions have to name.
     */
    public static final String CONFIGURED_SOURCE_NAME = "<graphitron-configured:federation-link>";

    private FederationLinkComposer() {}

    /**
     * The link the store settled on for one graph.
     *
     * @param configured whether the configuration rather than an author opts the graph in
     * @param imports    what the link imports, in order
     */
    public record Link(boolean configured, List<Import> imports) {
        public Link {
            imports = List.copyOf(imports);
        }
    }

    /** One import: the name imported, and the local name an author bound it to, or null. */
    public record Import(String name, String alias) {
        public Import {
            Objects.requireNonNull(name, "name");
        }
    }

    /** What the composer made of a registry. */
    sealed interface Result {

        /** The registry to compose from, the one handed in or one rebuilt around a rewritten link. */
        record Composed(TypeDefinitionRegistry registry) implements Result {}

        /** The registry refused the link added for the configuration. */
        record Refused(LoadingRewrites.Refusal refusal) implements Result {}
    }

    static Result compose(TypeDefinitionRegistry registry, Optional<Link> settled) {
        if (settled.isEmpty()) {
            return new Result.Composed(registry);
        }
        var link = settled.get();
        if (link.configured()) {
            return configured(registry, link);
        }
        var written = federationLink(registry);
        if (written.isEmpty()) {
            return new Result.Composed(registry);
        }
        var old = written.get();
        var rewritten = old.transform(builder -> builder.arguments(Stream.concat(
                old.getArguments().stream().filter(argument -> !"import".equals(argument.getName())),
                Stream.of(importArgument(link)))
            .toList()));
        return new Result.Composed(replacing(registry, old, rewritten));
    }

    private static Result configured(TypeDefinitionRegistry registry, Link link) {
        var directive = Directive.newDirective()
            .name("link")
            .argument(Argument.newArgument().name("url").value(new StringValue(FederationSpec.URL)).build())
            .argument(importArgument(link))
            .build();
        var extension = SchemaExtensionDefinition.newSchemaExtensionDefinition()
            .sourceLocation(new SourceLocation(1, 1, CONFIGURED_SOURCE_NAME))
            .directive(directive)
            .build();
        return registry.add(extension).<Result>map(error -> {
            String message = "Failed to add the federation @link the configuration opts in with: "
                + error.getMessage();
            return new Result.Refused(new LoadingRewrites.Refusal.ConfiguredLinkRefused(message,
                new IllegalStateException(message)));
        }).orElseGet(() -> new Result.Composed(registry));
    }

    private static Argument importArgument(Link link) {
        var list = ArrayValue.newArrayValue();
        for (var entry : link.imports()) {
            list.value(entry.alias() == null
                ? new StringValue(entry.name())
                : ObjectValue.newObjectValue()
                    .objectField(new ObjectField("name", new StringValue(entry.name())))
                    .objectField(new ObjectField("as", new StringValue(entry.alias())))
                    .build());
        }
        return Argument.newArgument().name("import").value(list.build()).build();
    }

    /** The first federation {@code @link}, on the schema definition and then on each extension. */
    private static Optional<Directive> federationLink(TypeDefinitionRegistry registry) {
        return Stream.concat(
                registry.schemaDefinition().map(sd -> sd.getDirectives("link").stream())
                    .orElse(Stream.empty()),
                registry.getSchemaExtensionDefinitions().stream()
                    .flatMap(extension -> extension.getDirectives("link").stream()))
            .filter(directive -> directive.getArgument("url") != null
                && directive.getArgument("url").getValue() instanceof StringValue url
                && url.getValue() != null
                && url.getValue().startsWith(FederationSpec.SPEC_PREFIX))
            .findFirst();
    }

    /**
     * {@code registry} with the schema-level directive {@code old} replaced by {@code replacement},
     * on whichever schema definition or extension carries it. The registry offers no way to remove a
     * schema extension, so this one is rebuilt with every other definition carried across as it
     * stands; the built-in scalars its own reading adds are left out, the registry supplying them.
     */
    private static TypeDefinitionRegistry replacing(TypeDefinitionRegistry registry, Directive old,
                                                    Directive replacement) {
        var extensions = registry.getSchemaExtensionDefinitions().stream()
            .map(extension -> extension.getDirectives().stream().anyMatch(d -> d == old)
                ? extension.transformExtension(builder -> builder.directives(swap(extension.getDirectives(), old, replacement)))
                : extension)
            .toList();
        SchemaDefinition schema = registry.schemaDefinition()
            .map(definition -> definition.getDirectives().stream().anyMatch(d -> d == old)
                ? definition.transform(builder -> builder.directives(swap(definition.getDirectives(), old, replacement)))
                : definition)
            .orElse(null);
        var scalars = new LinkedHashMap<>(registry.scalars());
        ScalarInfo.GRAPHQL_SPECIFICATION_SCALARS_DEFINITIONS.forEach(scalars::remove);
        return new TypeDefinitionRegistry(
            registry.objectTypeExtensions(), registry.interfaceTypeExtensions(),
            registry.unionTypeExtensions(), registry.enumTypeExtensions(),
            registry.scalarTypeExtensions(), registry.inputObjectTypeExtensions(),
            registry.types(), scalars, registry.getDirectiveDefinitions(),
            new ArrayList<>(extensions), schema, registry.getParseOrder()) {};
    }

    private static List<Directive> swap(List<Directive> directives, Directive old, Directive replacement) {
        return directives.stream().map(d -> d == old ? replacement : d).toList();
    }
}
