package no.sikt.graphitron.rewrite.lint;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.rewrite.BuildWarning;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.schema.federation.KeyNodeSynthesiser;
import no.sikt.graphitron.rewrite.schema.input.FederationLinkApplier;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The federation {@code @link} injector's definitions (federation/link namespaced types and
 * scalars such as {@code federation__FieldSet}, {@code link__Import}) are generator-owned surface
 * the author never wrote and cannot rename or document, so the lint engine must not flag them.
 *
 * <p>Exercises the real injection path: parse an author SDL carrying a federation {@code @link} and
 * a non-compliant author {@code @node} type, run {@link FederationLinkApplier#apply} (which injects
 * the federation definitions and returns their names) and {@link KeyNodeSynthesiser#apply} (which
 * decorates the author {@code @node} type in place with {@code @key}, exactly as
 * {@code loadAttributedRegistry} does), then lint with the injected-name exclusion. The single
 * fixture pins both halves: injected names are silent, and the real author violation still fires
 * even on the {@code @node} type that synthesis decorated. Assertions are on the typed
 * {@link LintRule} and the minimum node-identity check, never on rendered diagnostic wording.
 */
@PipelineTier
class LintInjectedFederationDefinitionsTest {

    private static final String SDL = """
        extend schema
          @link(url: "https://specs.apollo.dev/federation/v2.10", import: ["@key"])
        type lowercase @node { id: ID! }
        type Query { widget: lowercase }
        """;

    @Test
    void injectedFederationDefinitionsAreNotLinted_butAuthorNodeTypeStillIs() {
        var registry = new SchemaParser().parse(RewriteSchemaLoader.directivesSdl() + "\n" + SDL);

        Set<String> injectedNames = FederationLinkApplier.apply(registry);
        // Guard the premise: the @link really injected federation/link namespaced definitions.
        assertThat(injectedNames)
            .as("federation @link injects namespaced definitions")
            .anyMatch(n -> n.startsWith("federation__") || n.startsWith("link__"));
        // Mirror loadAttributedRegistry: synthesis decorates the author @node type in place.
        KeyNodeSynthesiser.apply(registry);

        List<BuildWarning.LintFinding> findings = LintEngine.builtIn().run(registry, injectedNames)
            .stream().map(BuildWarning.LintFinding.class::cast).toList();

        // The author @node type's lowercase name still fires, and it is the ONLY pascal-case finding
        // (were the injected names not excluded, every federation__* / link__* name would fire too).
        assertThat(findings)
            .filteredOn(f -> f.rule() == LintRule.TYPE_NAMES_PASCAL_CASE)
            .singleElement()
            .satisfies(f -> assertThat(f.message()).contains("lowercase"));

        // No finding is reported against any injected definition, whatever rule it would trip.
        assertThat(findings)
            .noneMatch(f -> injectedNames.stream().anyMatch(name -> f.message().contains(name)));
    }
}
