package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Inertness of {@code @table} on a <em>nested</em> input, the half of the contract the top-level
 * fixtures cannot reach. Three separate main-source sites were gated on the directive's presence,
 * each on a different path, so deleting one does not fix another:
 *
 * <ul>
 *   <li>{@code BuildContext.classifyInputField}'s nesting arm, the plain filter-input path
 *     exercised here. Its descent used to require the absence of {@code @table}; with the
 *     directive present control fell through to the column-lookup path below and resolved the
 *     whole group as a column named after the nested field.</li>
 *   <li>{@code InputBeanResolver.collectJooqBindings}, the jOOQ-record param path, pinned by
 *     {@link no.sikt.graphitron.rewrite.generators.JooqRecordServiceParamPipelineTest}'s
 *     nested-flattening equivalence case.</li>
 *   <li>{@code MutationInputResolver.rejectInputFieldDirectives}, whose recursion was gated the
 *     same way. Unlike the other two this failed <em>open</em>: a {@code @condition} buried inside a
 *     nested {@code @table} group escaped an admission scan its directiveless twin trips, with no
 *     build error to notice. The {@code @condition} case here is the only assertion that catches
 *     it.</li>
 * </ul>
 *
 * <p>The plain-path equivalence is asserted on generated output rather than by walking the model.
 * The two fixtures differ only in the nested type's directive, so a flattening that ignores it
 * emits byte-identical fetchers and one that does not cannot; the pre-fix behaviour on this
 * fixture was silently divergent emitted code rather than a diagnostic, which an
 * absence-of-diagnostics assertion would have missed entirely.
 */
@PipelineTier
class TableOnInputIgnoredNestingTest {

    private static final String NESTED_WITH_TABLE = """
        type Film @table(name: "film") { title: String }
        input FilmFilter { nested: NestedGroup }
        input NestedGroup @table(name: "language") { title: String @field(name: "title") }
        type Query { films(filter: FilmFilter): [Film!]! }
        """;

    private static final String NESTED_DIRECTIVELESS = """
        type Film @table(name: "film") { title: String }
        input FilmFilter { nested: NestedGroup }
        input NestedGroup { title: String @field(name: "title") }
        type Query { films(filter: FilmFilter): [Film!]! }
        """;

    @Test
    void nestedTableGroupOnFilterInput_resolvesLikeItsDirectivelessTwin() {
        var schema = TestSchemaHelper.buildSchema(NESTED_WITH_TABLE);

        assertThat(schema.diagnostics())
            .as("the group nests rather than resolving as a column named after the nested field, "
                + "so no unresolvable-column diagnostic fires")
            .isEmpty();
        assertThat(schema.type("NestedGroup"))
            .as("and the nested type itself classifies plain")
            .isInstanceOf(GraphitronType.PojoInputType.class);
        assertThat(fetchers(NESTED_WITH_TABLE))
            .as("identical generated fetchers: the nested @table changed nothing about how the "
                + "group's column binds")
            .isEqualTo(fetchers(NESTED_DIRECTIVELESS));
        assertThat(schema.warnings())
            .as("the nested type earns its own deprecation advisory")
            .anyMatch(w -> w.message().contains("NestedGroup") && w.message().contains("was ignored"));
    }

    @Test
    void nestedTableGroupOnMutationInput_stillRejectsConditionOnItsFields() {
        // The fail-open site. @condition on a mutation input field is inadmissible; burying the
        // field inside a nested @table group must not smuggle it past the scan.
        var reason = insertRejectionReason("""
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") title: String }
            type Query { x: String }
            input FilmInsertInput { nested: NestedTableGroup }
            input NestedTableGroup @table(name: "language") {
                title: String @field(name: "title")
                    @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "syntheticNameCondition"}, override: false)
            }
            type Mutation { createFilm(in: FilmInsertInput!): Film @mutation(typeName: INSERT) }
            """);

        assertThat(reason)
            .as("the buried @condition is caught, as it is in a directiveless nested group")
            .contains("@condition")
            .contains("mutations write values");
    }

    @Test
    void nestedTableGroupOnMutationInput_stillRejectsLookupKeyOnItsFields() {
        // The other admission rule, for completeness rather than as a pin: @lookupKey inside a
        // nested @table group is also caught downstream of rejectInputFieldDirectives, so unlike
        // the @condition case above this one stays green even with the recursion gated. Kept
        // because the rule is part of the contract, not because it guards the deletion.
        var reason = insertRejectionReason("""
            type Film @table(name: "film") { filmId: Int! @field(name: "film_id") title: String }
            type Query { x: String }
            input FilmInsertInput { nested: NestedTableGroup }
            input NestedTableGroup @table(name: "language") {
                title: String @field(name: "title") @lookupKey
            }
            type Mutation { createFilm(in: FilmInsertInput!): Film @mutation(typeName: INSERT) }
            """);

        assertThat(reason).contains("@lookupKey");
    }

    private static String insertRejectionReason(String sdl) {
        var field = TestSchemaHelper.buildSchema(sdl).field("Mutation", "createFilm");
        assertThat(field)
            .as("the admission scan descends into the nested @table group and rejects")
            .isInstanceOf(UnclassifiedField.class);
        return ((UnclassifiedField) field).reason();
    }

    /** Every generated fetcher class, rendered, so two schemas can be compared as emitted output. */
    private static String fetchers(String sdl) {
        return TypeFetcherGenerator.generate(TestSchemaHelper.buildSchema(sdl), DEFAULT_OUTPUT_PACKAGE)
            .stream()
            .map(TypeSpec::toString)
            .reduce("", String::concat);
    }
}
