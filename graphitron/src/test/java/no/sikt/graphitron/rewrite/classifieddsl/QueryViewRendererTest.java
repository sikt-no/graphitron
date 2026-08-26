package no.sikt.graphitron.rewrite.classifieddsl;

import no.sikt.graphitron.rewrite.classifieddsl.CorpusDocuments.Document;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A check for {@link QueryViewRenderer}: a query selects a closure of the annotated corpus, and the
 * renderer regenerates real SDL for it, pruned to the selected fields (job 3) with the test-only
 * directives stripped (job 2) but real Graphitron directives retained.
 *
 * <p>The pre-migration hardening (Spec §"Pre-migration hardening" item 3) adds two closure expansions
 * exercised below over the real corpus-only fixtures, the proof that the verdicts which landed
 * corpus-only because the renderer could not show their closure are now renderable: the input-object
 * closure from a kept field's arguments, and fragment selection (inline {@code ... on Type} and a bare
 * top-level {@code fragment on Type}) for the polymorphic and type-display cases. Leaf vocabulary
 * (scalars, enums) is referenced by name but never expanded.
 */
@PipelineTier
class QueryViewRendererTest {

    private static String sdlOf(String id) {
        return CorpusDocuments.documents().stream()
            .filter(e -> e.id().equals(id))
            .map(Document::sdl)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no corpus document with id '" + id + "'"));
    }

    private static final String FIXTURE = """
        extend type Query {
          film: Film @classified(source: Query, operations: [Select], target: Single, targetShape: Table)
          actor: Actor @classified(source: Query, operations: [Select], target: Single, targetShape: Table)
        }

        type Film @table(name: "film") @classifiedType(as: TableType) {
          title: String @classified(source: Child, operations: [Select], target: Single, targetShape: Column)
          releaseYear: Int @field(name: "release_year") @classified(source: Child, operations: [Select], target: Single, targetShape: Column)
        }

        type Actor @table(name: "actor") {
          firstName: String @field(name: "first_name") @classified(source: Child, operations: [Select], target: Single, targetShape: Column)
        }
        """;

    @Test
    void rendersSelectedFieldsWithTestDirectivesStripped() {
        String out = QueryViewRenderer.render(FIXTURE, "{ film { releaseYear } }");

        // Job 1 (import what's relevant): the selected closure (Query entry + Film) is rendered, and a
        // real Graphitron type directive (@table) survives.
        assertThat(out).contains("type Query");
        assertThat(out).contains("type Film");
        assertThat(out).contains("releaseYear");
        assertThat(out).contains("@table");

        // Job 2 (strip the test directives): the internal directives never print, but a real
        // Graphitron directive on a rendered field (@field) survives regeneration.
        assertThat(out).doesNotContain("@classified");
        assertThat(out).doesNotContain("classifiedType");
        assertThat(out).contains("@field");

        // Job 3 (bound the snippet): a sibling field of a rendered type (Film.title) is pruned to the
        // selection, the unselected root field (Query.actor) is dropped, and a sibling type the query
        // never reached (Actor) is not dragged in.
        assertThat(out).doesNotContain("title");
        assertThat(out).doesNotContain("actor");
        assertThat(out).doesNotContain("type Actor");
    }

    @Test
    void mutationInputObjectIsExpandedFromTheFieldArgument() {
        String out = QueryViewRenderer.render(sdlOf("dml"), "mutation { createFilm { title } }");

        assertThat(out)
            .as("the @mutation field renders with its real directive and input argument")
            .contains("createFilm(in: FilmInput!): Film")
            .contains("@mutation(typeName: INSERT)")
            .doesNotContain("@classified");
        assertThat(out)
            .as("the input object reached from the argument is emitted in full")
            .contains("input FilmInput");
    }

    @Test
    void everyInputObjectFromAMutationsArgumentsRenders() {
        String out = QueryViewRenderer.render(sdlOf("mutation-roots"),
            "mutation { updateFilm { title } deleteFilm { title } createFilmPayload { film { title } } }");

        assertThat(out)
            .as("each selected mutation pulls in exactly its own argument's input object")
            .contains("input FilmUpdateInput")
            .contains("input FilmKeyInput")
            .contains("input FilmCreateInput")
            .contains("filmId: Int!");
        assertThat(out)
            .as("an input object no selected mutation references is not dragged in")
            .doesNotContain("FilmTitleInput")
            .doesNotContain("@classified");
    }

    @Test
    void unionRendersThroughInlineFragments() {
        String out = QueryViewRenderer.render(sdlOf("union"),
            "{ filmActor { related { ... on Film { title } ... on Actor { firstName } } } }");

        assertThat(out)
            .as("the union declaration and both fragment-selected members render")
            .contains("union FilmOrActor = Film | Actor")
            .contains("related: FilmOrActor")
            .contains("title: String")
            .contains("firstName: String")
            .doesNotContain("@classified");
    }

    @Test
    void relayNodeRendersInterfaceAndScalarArgumentWithoutExpandingTheScalar() {
        String out = QueryViewRenderer.render(sdlOf("relay-node"),
            "{ node(id: \"1\") { ... on Film { id title } } }");

        assertThat(out)
            .as("the polymorphic root, its scalar argument, and the interface declaration render. "
                + "The field and its argument are matched separately because AstPrinter breaks the "
                + "argument list onto its own line once the field carries a description, which the "
                + "corpus document gives this coordinate")
            .contains("\"Relay's own entry point: one global id at a time.\"")
            .contains("id: ID!): Node")
            .contains("interface Node")
            .contains("type Film implements Node")
            .doesNotContain("@classified");
        assertThat(out)
            .as("a scalar argument type is named but not expanded as a definition")
            .doesNotContain("scalar ID");
    }

    @Test
    void topLevelFragmentDisplaysAnOutputTypeNoQueryReaches() {
        String out = QueryViewRenderer.render(sdlOf("error-type"),
            "fragment e on ExtraFieldError { path message severity }");

        assertThat(out)
            .as("the @error type renders directly from a fragment on it, real directive kept")
            .contains("type ExtraFieldError @error")
            .contains("path: [String!]!")
            .doesNotContain("@classifiedType")
            .doesNotContain("@classified(");
        assertThat(out)
            .as("an enum the fixture declares and the excerpt names renders too: a leaf the "
                + "excerpt mentions and never shows is what closure honesty forbids")
            .contains("enum Severity");
    }

    @Test
    void aFieldsOwnDescriptionSurvivesRegeneration() {
        String out = QueryViewRenderer.render("""
            extend type Query { film: Film }

            type Film @table(name: "film") {
              "The film's display title."
              title: String @classified(source: Child, operations: [Select], target: Single, targetShape: Column)
            }
            """, "{ film { title } }");

        assertThat(out)
            .as("a description a document writes on a coordinate reaches the rendered block, which is "
                + "what makes the document the one home for the prose")
            .contains("\"The film's display title.\"")
            .contains("title: String");
    }

    @Test
    void aTypesOwnDescriptionSurvivesRegeneration() {
        String out = QueryViewRenderer.render("""
            extend type Query { film: Film }

            "A film in the catalog."
            type Film @table(name: "film") {
              title: String @classified(source: Child, operations: [Select], target: Single, targetShape: Column)
            }
            """, "{ film { title } }");

        assertThat(out)
            .as("a type description renders on the pruned type, so a document can say what a type is")
            .contains("\"A film in the catalog.\"")
            .contains("type Film @table");
    }

    @Test
    void aFixtureWithoutDescriptionsRendersNone() {
        String out = QueryViewRenderer.render(FIXTURE, "{ film { releaseYear } }");

        // The no-regression pin: a fixture that describes nothing renders no description, so no block
        // string appears and no rendered line is a bare SDL description (a leading quoted string). The
        // only quotes in this excerpt are directive argument values (@table(name: "film")), which never
        // start a line.
        assertThat(out).doesNotContain("\"\"\"");
        assertThat(out.lines().map(String::strip))
            .as("no line in a description-free excerpt is a bare SDL description string")
            .noneMatch(line -> line.startsWith("\""));
        assertThat(out)
            .contains("type Film @table")
            .contains("releaseYear: Int @field");
    }

    @Test
    void lookupKeyScalarArgumentRendersWithoutInputExpansion() {
        String out = QueryViewRenderer.render(sdlOf("split-lookup"),
            "{ store { customers { firstName } } }");

        assertThat(out)
            .as("the @lookupKey scalar argument and @splitQuery field render")
            .contains("customers(customer_id: ID! @lookupKey)")
            .contains("@splitQuery")
            .doesNotContain("@classified");
        assertThat(out)
            .as("a scalar @lookupKey argument pulls in no input object")
            .doesNotContain("input ");
    }
}
