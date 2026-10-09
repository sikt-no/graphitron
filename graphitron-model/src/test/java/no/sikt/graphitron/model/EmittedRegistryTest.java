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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The registry the generator emits, derived from the one capture transcribed.
 *
 * <p>Every case here drives a real capture rather than seeding rows, because the subject is whether
 * the patch and the expansion agree, and seeding the expansion's own output would assume the thing
 * under test. What the fixture writes is a schema with an {@code @asConnection} on it; what the
 * store then holds is whatever {@code the emitted anchoring} decided, and what these cases assert is that
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

    /**
     * A tagged carrier beside a {@code PageInfo} its author declared. The mint of the shared page
     * info stands down to the author, so the declared type is the author's and takes no tag from
     * the carrier, while the connection and edge nobody declared do.
     */
    private static final String DECLARED_PAGE_INFO_SCHEMA = """
        directive @tag(name: String!) repeatable on FIELD_DEFINITION | OBJECT | INTERFACE | UNION \
        | ARGUMENT_DEFINITION | SCALAR | ENUM | ENUM_VALUE | INPUT_OBJECT | INPUT_FIELD_DEFINITION

        type Query {
          films: [Film!] @asConnection @tag(name: "experimental")
        }

        type Film {
          title: String!
        }

        type PageInfo {
          hasPreviousPage: Boolean!
          hasNextPage: Boolean!
          startCursor: String
          endCursor: String
        }
        """;

    /**
     * Two carriers that agree on one tag and disagree on another, and one of them
     * {@code @shareable}. The connection and edge each carrier mints are its own; the one
     * {@code PageInfo} is shared, so it carries only the tag both carry and is shareable because
     * one of them is.
     */
    private static final String DISAGREEING_CARRIERS_SCHEMA = """
        directive @tag(name: String!) repeatable on FIELD_DEFINITION | OBJECT | INTERFACE | UNION \
        | ARGUMENT_DEFINITION | SCALAR | ENUM | ENUM_VALUE | INPUT_OBJECT | INPUT_FIELD_DEFINITION
        directive @shareable repeatable on OBJECT | FIELD_DEFINITION

        type Query {
          films: [Film!] @asConnection @tag(name: "public") @tag(name: "stable") @shareable
          shorts: [Film!] @asConnection @tag(name: "public") @tag(name: "experimental")
        }

        type Film {
          title: String!
        }
        """;

    /** The federation opt-in an author writes, as the documents below carry it. */
    private static final String LINK =
        "extend schema @link(url: \"https://specs.apollo.dev/federation/v2.10\", import: [\"@key\"])\n";

    /** A node type in a graph that never linked federation. */
    private static final String UNLINKED_NODE_SCHEMA = """
        interface Node { id: ID! }

        type Query {
          film: Film
        }

        type Film implements Node @node {
          id: ID!
          title: String!
        }
        """;

    /** The same node type in a federation-linked graph, whose author declared no key. */
    private static final String LINKED_NODE_SCHEMA = LINK + UNLINKED_NODE_SCHEMA;

    /** The linked node type with the id key its author wrote. */
    private static final String LINKED_AUTHORED_KEY_SCHEMA = LINK + """
        interface Node { id: ID! }

        type Query {
          film: Film
        }

        type Film implements Node @node @key(fields: "id") {
          id: ID!
          title: String!
        }
        """;

    /** {@link #LINKED_NODE_SCHEMA} as it stands after the mint, which is not what emission starts from. */
    private static final String MINTED_KEY_SCHEMA = LINK + """
        interface Node { id: ID! }

        type Query {
          film: Film
        }

        type Film implements Node @node @key(fields: "id", resolvable: true) {
          id: ID!
          title: String!
        }
        """;

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
            connectionEmitted = emitted((connectionTranscribed),
                new StoreHandle(store.dsl(), CapturedStore.GRAPH));
        }
        try (var store = CapturedStore.of(tmp.resolve("plain"), PLAIN_SCHEMA)) {
            plainTranscribed = store.registry();
            plainEmitted = emitted((plainTranscribed),
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

    // ===== The tags a minted type inherits =====

    /**
     * Inheritance follows the mint, not the coinage. Every carrier coins a {@code PageInfo}, so
     * a fold over coinage alone tags one the author declared with every carrier's tags; the store
     * already says that mint stood down, and the fold asks it. The minted connection and edge in
     * the same schema are the control: they still inherit, so the case fails on a fix that
     * switched inheritance off rather than aimed it.
     */
    @Test
    @DisplayName("a declared PageInfo takes no tag from a carrier, and the minted connection does")
    void aDeclaredPageInfoInheritsNoTag() {
        CapturedStore.withCapturedStore(tmp.resolve("declared-page-info"),
            DECLARED_PAGE_INFO_SCHEMA, dsl -> {
                var emitted = emitted((parse(DECLARED_PAGE_INFO_SCHEMA)),
                    new StoreHandle(dsl, CapturedStore.GRAPH));

                assertThat(typeDirectives(emitted, "PageInfo"))
                    .as("the author's PageInfo, as the author wrote it")
                    .isEmpty();
                assertThat(typeDirectives(emitted, "QueryFilmsConnection"))
                    .containsExactly("@tag(name: \"experimental\")");
                assertThat(typeDirectives(emitted, "QueryFilmsConnectionEdge"))
                    .containsExactly("@tag(name: \"experimental\")");
            });
    }

    /**
     * A type several carriers mint carries the tags they all carry and not the ones only some do,
     * so a contract excluding one carrier's tag keeps it for the other. {@code @shareable} is the
     * other way round: one shareable carrier makes the shared type shareable. Each carrier's own
     * connection is the control, keeping exactly its carrier's directives.
     */
    @Test
    @DisplayName("a shared minted type carries the tags every carrier carries, and @shareable if any carrier is")
    void aSharedMintedTypeCarriesTheIntersection() {
        CapturedStore.withCapturedStore(tmp.resolve("disagreeing-carriers"),
            DISAGREEING_CARRIERS_SCHEMA, dsl -> {
                var emitted = EmittedRegistry.derive((parse(DISAGREEING_CARRIERS_SCHEMA)),
                    new StoreHandle(dsl, CapturedStore.GRAPH));
                var registry = emitted.registry();

                assertThat(typeDirectives(registry, "PageInfo"))
                    .containsExactly("@shareable", "@tag(name: \"public\")");
                assertThat(typeDirectives(registry, "QueryFilmsConnection"))
                    .containsExactly("@shareable", "@tag(name: \"public\")", "@tag(name: \"stable\")");
                assertThat(typeDirectives(registry, "QueryFilmsConnectionEdge"))
                    .containsExactly("@shareable", "@tag(name: \"public\")", "@tag(name: \"stable\")");
                assertThat(typeDirectives(registry, "QueryShortsConnection"))
                    .containsExactly("@tag(name: \"public\")", "@tag(name: \"experimental\")");

                assertThat(emitted.narrowings())
                    .as("only the shared type was narrowed")
                    .singleElement()
                    .satisfies(n -> {
                        assertThat(n.typeName()).isEqualTo("PageInfo");
                        assertThat(n.kept()).containsExactly("public");
                        assertThat(n.dropped()).containsExactly("stable", "experimental");
                        assertThat(n.carriers()).extracting(EmittedRegistry.Carrier::coordinate)
                            .containsExactly("Query.films", "Query.shorts");
                    });
            });
    }

    // ===== The keys the anchor mints =====

    /**
     * The minted {@code @key} arrives, with the arguments the anchor states rather than ones the
     * patch re-mints. Captured whole, so the rule that decides it and the patch that renders it are
     * read together over what an author wrote.
     */
    @Test
    @DisplayName("a node type in a federation-linked graph gets the key the anchor minted")
    void theMintedKeyIsApplied() {
        CapturedStore.withCapturedStore(tmp.resolve("minted-key"), LINKED_NODE_SCHEMA, dsl -> {
            var emitted = emitted((parse(LINKED_NODE_SCHEMA)),
                new StoreHandle(dsl, CapturedStore.GRAPH));

            assertThat(typeDirectives(emitted, "Film"))
                .containsExactly("@node", "@key(fields: \"id\", resolvable: true)");
        });
    }

    /** The opt-in is the whole rule, so a graph that never linked federation gets no key. */
    @Test
    @DisplayName("an unlinked graph has no key to apply")
    void nothingIsAppliedWithoutTheOptIn() {
        CapturedStore.withCapturedStore(tmp.resolve("unlinked"), UNLINKED_NODE_SCHEMA, dsl -> {
            var emitted = emitted((parse(UNLINKED_NODE_SCHEMA)),
                new StoreHandle(dsl, CapturedStore.GRAPH));

            assertThat(typeDirectives(emitted, "Film")).containsExactly("@node");
        });
    }

    /**
     * A key the author already wrote is not applied a second time. The authored application is in
     * the registry because its author wrote it there, and it is in the directive anchor's authored
     * set too; only the minted set is applied, so this fails on a patch that applies both.
     */
    @Test
    @DisplayName("an authored key is not applied a second time")
    void theAuthoredKeyIsNotReapplied() {
        CapturedStore.withCapturedStore(tmp.resolve("authored-key"), LINKED_AUTHORED_KEY_SCHEMA,
            dsl -> {
                var emitted = emitted((parse(LINKED_AUTHORED_KEY_SCHEMA)),
                    new StoreHandle(dsl, CapturedStore.GRAPH));

                assertThat(typeDirectives(emitted, "Film"))
                    .as("the author's own application, once")
                    .containsExactly("@node", "@key(fields: \"id\")");
            });
    }

    /**
     * The derivation starts from the registry before synthesis, and an application it finds there
     * already is a mint applied twice. Stated by handing it a registry that already carries the
     * minted key: the patch has to say so rather than append a second one or quietly skip.
     */
    @Test
    @DisplayName("a registry already carrying the minted key is refused as a generator defect")
    void aKeyAlreadyMintedIsRefused() {
        CapturedStore.withCapturedStore(tmp.resolve("minted-twice"), LINKED_NODE_SCHEMA, dsl ->
            assertThatThrownBy(() -> emitted((parse(MINTED_KEY_SCHEMA)),
                    new StoreHandle(dsl, CapturedStore.GRAPH)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'Film'")
                .hasMessageContaining("defect in graphitron"));
    }

    // ---------------------------------------------------------------------------------------

    /** The emitted registry for the graph, derived from {@code registry}. */
    private static TypeDefinitionRegistry emitted(TypeDefinitionRegistry registry, StoreHandle store) {
        return EmittedRegistry.derive(registry, store).registry();
    }

    private static List<String> typeDirectives(TypeDefinitionRegistry registry,
                                               String type) {
        assertThat(registry.getTypeOrNull(type)).as(type).isInstanceOf(ObjectTypeDefinition.class);
        return ((ObjectTypeDefinition) registry.getTypeOrNull(type)).getDirectives().stream()
            .map(AstPrinter::printAst).toList();
    }

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
