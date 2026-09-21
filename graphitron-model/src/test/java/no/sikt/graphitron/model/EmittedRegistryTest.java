package no.sikt.graphitron.model;

import graphql.language.AstPrinter;
import graphql.language.FieldDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.schema.EmittedRegistry;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.model.test.CapturedStore;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static no.sikt.graphitron.model.test.SeededStore.seedFederationKey;
import static no.sikt.graphitron.model.test.SeededStore.seedLink;
import static no.sikt.graphitron.model.test.SeededStore.seedNode;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registry the generator emits, derived from the one capture transcribed.
 *
 * <p>Every case here drives a real capture rather than seeding rows, because the subject is whether
 * the patch and the expansion agree, and seeding the expansion's own output would assume the thing
 * under test. What the fixture writes is a schema with an {@code @asConnection} on it; what the
 * store then holds is whatever {@code MacroCapture} decided, and what these cases assert is that
 * the emitted registry says the same.
 *
 * <p>The control case matters as much as the connection ones. A patch that added the machinery and
 * also quietly rewrote something nobody asked it to would pass every assertion about connections,
 * so one schema here carries no macro at all and the emitted registry has to be the transcribed
 * one.
 *
 * <p>Two captures for the whole class, not one per case, and the reason is a budget rather than
 * speed. This class cannot run on {@code SeededStore.withSeededStore}: its subject is what a
 * capture writes, so it has to run one, and {@code CapturedStore} owns the store it captures into.
 * Every case booting its own put the module past {@code ThreadConfinedStore.BOOT_BUDGET}, which
 * fails every funnelled case that runs after it rather than this one. The derived registries are
 * plain AST and outlive the stores, so the capture happens once per schema and the stores close
 * immediately.
 */
class EmittedRegistryTest {

    /**
     * A carrier on the base declaration and a second on an extension, because a type is its
     * definition and its extensions and the anchors are neither: {@code graphitron_field} is merged
     * across declaration sites, so one row stands for a field whichever site declared it. A patch
     * reading only the base declares every extension's field a second time, which assembly refuses.
     * Having both in one fixture is what makes {@link #theEmittedRegistryAssembles} bite on that.
     */
    private static final String CONNECTION_SCHEMA = """
        type Query {
          films: [Film!] @asConnection
        }

        extend type Query {
          shorts: [Film!]! @asConnection
        }

        type Film {
          title: String!
        }
        """;

    private static final String PLAIN_SCHEMA = """
        type Query {
          films: [Film!]
        }

        type Film {
          title: String!
        }
        """;

    private static final String NODE_SCHEMA = """
        interface Node { id: ID! }

        type Query {
          film: Film
        }

        type Film implements Node {
          id: ID!
          title: String!
        }
        """;

    private static final String AUTHORED_KEY_SCHEMA = """
        interface Node { id: ID! }

        type Query {
          film: Film
        }

        type Film implements Node @key(fields: "id") {
          id: ID!
          title: String!
        }
        """;

    private static final String FEDERATION_GRAPH = "federation";

    /** The federation spec prefix as a url a real {@code @link} carries. */
    private static final String FEDERATION_URL = "https://specs.apollo.dev/federation/v2.10";

    @TempDir
    static Path tmp;

    private static TypeDefinitionRegistry connectionTranscribed;
    private static TypeDefinitionRegistry connectionEmitted;
    private static TypeDefinitionRegistry plainTranscribed;
    private static TypeDefinitionRegistry plainEmitted;

    @BeforeAll
    static void capture() {
        try (var store = CapturedStore.of(tmp.resolve("connection"), CONNECTION_SCHEMA)) {
            connectionTranscribed = store.registry();
            connectionEmitted = EmittedRegistry.of(connectionTranscribed,
                new StoreHandle(store.dsl(), CapturedStore.GRAPH));
        }
        try (var store = CapturedStore.of(tmp.resolve("plain"), PLAIN_SCHEMA)) {
            plainTranscribed = store.registry();
            plainEmitted = EmittedRegistry.of(plainTranscribed,
                new StoreHandle(store.dsl(), CapturedStore.GRAPH));
        }
    }

    @Test
    @DisplayName("the machinery no author wrote is in the emitted registry and not in the transcribed one")
    void theMintedTypesArrive() {
        assertThat(connectionTranscribed.getTypeOrNull("QueryFilmsConnection")).isNull();
        assertThat(connectionEmitted.getTypeOrNull("QueryFilmsConnection"))
            .isInstanceOf(ObjectTypeDefinition.class);
        assertThat(connectionEmitted.getTypeOrNull("PageInfo"))
            .isInstanceOf(ObjectTypeDefinition.class);
    }

    /**
     * The rewrite, which is the half a reader is most likely to assume rather than check: the
     * carrier keeps its coordinate and changes what it returns. The authored expression stays where
     * the author wrote it, which is the property the transcription assertion here pins.
     */
    @Test
    @DisplayName("the carrier's type expression is the connection, and the author's is untouched")
    void theCarrierIsRetyped() {
        assertThat(returnTypeOf(connectionTranscribed, "Query", "films")).isEqualTo("[Film!]");
        assertThat(returnTypeOf(connectionEmitted, "Query", "films"))
            .isEqualTo("QueryFilmsConnection");
    }

    /**
     * The expansion replaces what a field returns and says nothing about whether the field may be
     * null, so the outer non-null the author wrote survives it. The fixture carries one carrier of
     * each nullability for this: {@code films} is written {@code [Film!]} and {@code shorts} is
     * written {@code [Film!]!}, and the two have to come out differently.
     *
     * <p>Not a preference. An output field that loses its non-null is a breaking change to every
     * consumer reading it, so a row saying otherwise would describe a schema the run does not emit.
     */
    @Test
    @DisplayName("the carrier keeps the outer non-null its author wrote")
    void theCarrierKeepsTheAuthorsOuterNonNull() {
        assertThat(extensionFieldType(connectionEmitted, "Query"))
            .isEqualTo("QueryShortsConnection!");
        assertThat(returnTypeOf(connectionEmitted, "Query", "films"))
            .as("and the nullable carrier beside it stays nullable")
            .isEqualTo("QueryFilmsConnection");
    }

    /**
     * A carrier declared on an extension is rewritten where it was declared. The row for it is the
     * same shape as one for a base-declared field, the anchors being merged across sites, so what
     * this pins is that the patch routes it back to the site that has it rather than treating every
     * row as the base's.
     */
    @Test
    @DisplayName("a carrier on an extension is rewritten on the extension")
    void theExtensionCarrierIsRetypedInPlace() {
        var base = (ObjectTypeDefinition) connectionEmitted.getTypeOrNull("Query");
        assertThat(base.getFieldDefinitions())
            .as("the extension's field does not migrate onto the base declaration")
            .extracting(FieldDefinition::getName)
            .containsExactly("films");

        assertThat(extensionFieldType(connectionEmitted, "Query"))
            .isEqualTo("QueryShortsConnection!");
    }

    /** The printed type of the single field on the single extension of {@code type}. */
    private static String extensionFieldType(TypeDefinitionRegistry registry, String type) {
        var extensions = registry.objectTypeExtensions().get(type);
        assertThat(extensions).as("one extension of %s", type).hasSize(1);
        var fields = extensions.getFirst().getFieldDefinitions();
        assertThat(fields).as("one field on the extension of %s", type).hasSize(1);
        return AstPrinter.printAst(fields.getFirst().getType());
    }

    @Test
    @DisplayName("the pagination arguments the expansion appends reach the carrier")
    void thePaginationArgumentsArrive() {
        assertThat(fieldOf(connectionTranscribed, "Query", "films").getInputValueDefinitions())
            .isEmpty();
        assertThat(fieldOf(connectionEmitted, "Query", "films").getInputValueDefinitions())
            .extracting(v -> v.getName())
            .contains("first", "after");
    }

    /**
     * The correctness the move buys, stated as its own case. The incumbent expansion mints its
     * types downstream of assembly, so nothing it produces meets the specification's structural
     * rules; a registry patched before assembly does, and this is the assertion that says so.
     *
     * <p>Stated plainly because it was checked, twice, and the second answer is better than the
     * first. It does not fail on a patch that does nothing, the transcribed registry assembling
     * perfectly well on its own. It does fail on a patch that produces a schema the specification
     * refuses, and that is not hypothetical: reading field presence off the base declaration alone
     * declares every extension's field a second time, and this case is one of the two that catch
     * it. The fixture's {@code extend type Query} is what gives it something to catch.
     */
    @Test
    @DisplayName("the emitted registry assembles, so the minted types meet the specification's rules")
    void theEmittedRegistryAssembles() {
        assertThat(SchemaAssembly.of(connectionEmitted))
            .isInstanceOf(SchemaAssembly.Assembled.class);
    }

    @Test
    @DisplayName("the registry handed in is not the one patched")
    void theInputIsLeftAlone() {
        assertThat(connectionEmitted).isNotSameAs(connectionTranscribed);
        assertThat(connectionTranscribed.types()).doesNotContainKey("QueryFilmsConnection");
    }

    /**
     * The control. With nothing to expand, the emitted registry is the transcribed one, and the
     * check is on the whole type population rather than on the absence of a connection: a patch
     * that invented a type here would be as wrong as one that failed to invent a connection above.
     */
    @Test
    @DisplayName("a schema with no macro emits the population it declared")
    void nothingIsInventedWithoutAMacro() {
        assertThat(plainEmitted.types().keySet()).isEqualTo(plainTranscribed.types().keySet());
        assertThat(returnTypeOf(plainEmitted, "Query", "films")).isEqualTo("[Film!]");
    }

    // ===== The keys the rule derives =====

    /**
     * The derived {@code @key} arrives, and it arrives with the constants the relation states
     * rather than ones this patch re-mints.
     *
     * <p>These cases seed the rule's inputs and let the view derive, which is a different bargain
     * from the capture-driven ones above and the right one here. What is under test is whether the
     * patch applies what {@code graphitron_synthesized_federation_key} says, not whether that relation
     * decides correctly; deciding is the relation's own subject and is pinned where it lives. So
     * seeding a link and a node is seeding inputs, not seeding the answer.
     *
     * <p>They also cost no store boot, running on the funnel, which the capture-driven cases above
     * cannot.
     */
    @Test
    @DisplayName("a node type in a federation-linked graph gets the key the rule derived")
    void theDerivedKeyIsApplied() {
        withSeededStore(FEDERATION_GRAPH, dsl -> {
            seedLink(dsl, FEDERATION_GRAPH, 0, FEDERATION_URL);
            seedNode(dsl, FEDERATION_GRAPH, "Film");

            var emitted = EmittedRegistry.of(parse(NODE_SCHEMA),
                new StoreHandle(dsl, FEDERATION_GRAPH));

            var film = (ObjectTypeDefinition) emitted.getTypeOrNull("Film");
            assertThat(film.getDirectives())
                .extracting(AstPrinter::printAst)
                .containsExactly("@key(fields: \"id\", resolvable: true)");
        });
    }

    /**
     * The opt-in is the whole rule, so a graph that never linked federation gets no key. Here to
     * catch a patch that reached for the composed relation or invented the application itself: both
     * would put a key on this type, and the correct read cannot.
     */
    @Test
    @DisplayName("an unlinked graph has no key to apply")
    void nothingIsAppliedWithoutTheOptIn() {
        withSeededStore(FEDERATION_GRAPH, dsl -> {
            seedNode(dsl, FEDERATION_GRAPH, "Film");

            var emitted = EmittedRegistry.of(parse(NODE_SCHEMA),
                new StoreHandle(dsl, FEDERATION_GRAPH));

            assertThat(((ObjectTypeDefinition) emitted.getTypeOrNull("Film")).getDirectives())
                .isEmpty();
        });
    }

    /**
     * The authored key the author already wrote is not applied a second time, and this is the case
     * that pins how the derived rows are found rather than what is done with them.
     *
     * <p>An authored {@code @key} is in the registry because its author wrote it there, so anything
     * that applies it again duplicates it. Reading the composed {@code intent_federation_key} is
     * exactly that mistake, and it is available: the composition holds this authored row. What
     * makes it unreachable is naming the derived relation instead, so this case fails on a patch
     * that reaches for the composition and passes on one that does not, whatever it then does with
     * what it read.
     */
    @Test
    @DisplayName("an authored key is not applied a second time")
    void theAuthoredKeyIsNotReapplied() {
        withSeededStore(FEDERATION_GRAPH, dsl -> {
            seedLink(dsl, FEDERATION_GRAPH, 0, FEDERATION_URL);
            seedNode(dsl, FEDERATION_GRAPH, "Film");
            seedFederationKey(dsl, FEDERATION_GRAPH, "Film", 0, "id", true, "id");

            var emitted = EmittedRegistry.of(parse(AUTHORED_KEY_SCHEMA),
                new StoreHandle(dsl, FEDERATION_GRAPH));

            assertThat(((ObjectTypeDefinition) emitted.getTypeOrNull("Film")).getDirectives())
                .as("the author's own application, once")
                .extracting(AstPrinter::printAst)
                .containsExactly("@key(fields: \"id\")");
        });
    }

    /**
     * A derived row naming a type the registry does not declare is skipped rather than throwing.
     * The rule reads node metadata off generated classes as well as the SDL, so the two corpora can
     * disagree, and a run whose classes name a type its documents no longer do should not die in
     * the patch: the disagreement is a fact for a gate to state.
     */
    @Test
    @DisplayName("a derived key for a type the document does not declare is skipped")
    void aKeyForAnUndeclaredTypeIsSkipped() {
        withSeededStore(FEDERATION_GRAPH, dsl -> {
            seedLink(dsl, FEDERATION_GRAPH, 0, FEDERATION_URL);
            seedNode(dsl, FEDERATION_GRAPH, "Ghost");

            var emitted = EmittedRegistry.of(parse(NODE_SCHEMA),
                new StoreHandle(dsl, FEDERATION_GRAPH));

            assertThat(emitted.getTypeOrNull("Ghost")).isNull();
            assertThat(((ObjectTypeDefinition) emitted.getTypeOrNull("Film")).getDirectives())
                .isEmpty();
        });
    }

    // ---------------------------------------------------------------------------------------

    private static TypeDefinitionRegistry parse(String sdl) {
        return new SchemaParser().parse(sdl);
    }

    private static String returnTypeOf(TypeDefinitionRegistry registry, String type, String field) {
        return AstPrinter.printAst(fieldOf(registry, type, field).getType());
    }

    private static FieldDefinition fieldOf(TypeDefinitionRegistry registry, String type,
                                           String field) {
        assertThat(registry.getTypeOrNull(type)).isInstanceOf(ObjectTypeDefinition.class);
        var object = (ObjectTypeDefinition) registry.getTypeOrNull(type);
        return object.getFieldDefinitions().stream()
            .filter(f -> f.getName().equals(field))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "no field " + type + "." + field + " in the registry under test"));
    }
}
