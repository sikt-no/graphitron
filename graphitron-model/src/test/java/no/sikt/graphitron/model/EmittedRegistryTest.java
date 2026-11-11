package no.sikt.graphitron.model;

import graphql.language.AstPrinter;
import graphql.language.Directive;
import graphql.language.FieldDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.schema.DirectiveSupportTypes;
import no.sikt.graphitron.model.schema.EmittedRegistry;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.model.test.FactGraphs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The registry the generator emits, derived from the one capture transcribed.
 *
 * <p>Every case reads a fact document's graph rather than seeding rows, because the subject is
 * whether the patch and the expansion agree, and seeding the expansion's own output would assume the
 * thing under test. What the document states is a schema with an {@code @asConnection} on it, or a
 * node in a federation-linked graph; what the store then holds is whatever the anchoring decided,
 * which the document claims; and what these cases assert is that the emitted registry
 * {@link EmittedRegistry#derive} builds from those rows and the transcribed registry says the same.
 *
 * <p>The control case matters as much as the connection ones. A patch that added the machinery and
 * also quietly rewrote something nobody asked it to would pass every assertion about connections,
 * so one document here carries no macro at all and the emitted registry has to be the transcribed
 * one.
 */
class EmittedRegistryTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    /**
     * A carrier on the base declaration and a second on an extension, because a type is its
     * definition and its extensions and the anchors are neither: {@code graphitron_field} is merged
     * across declaration sites, so one row stands for a field whichever site declared it. A patch
     * reading only the base declares every extension's field a second time, which assembly refuses.
     * Having both in one document is what makes {@link #theEmittedRegistryAssembles} bite on that.
     */
    private static final String CONNECTION = "scenarios/connection-mint-extension-carrier";

    /** No macro anywhere in it: an object type, an input, an enum and an author's directive. */
    private static final String PLAIN = "scenarios/element-declaration";

    /** A graph's transcribed registry, as its capture read it, and what the derivation makes of it. */
    private record Derived(TypeDefinitionRegistry transcribed, EmittedRegistry.Emitted emitted) {

        TypeDefinitionRegistry registry() {
            return emitted.registry();
        }
    }

    /** {@code path}'s graph derived: its own capture's transcribed registry, over its own rows. */
    private static Derived derived(String path) {
        return FACTS.read(path, graph -> {
            var transcribed = transcribed(graph);
            return new Derived(transcribed, EmittedRegistry.derive(transcribed, graph.handle()));
        });
    }

    /** The emitted registry for {@code path}'s graph. */
    private static TypeDefinitionRegistry emitted(String path) {
        return derived(path).registry();
    }

    /** The documents as the capture read them, which is the registry before synthesis. */
    private static TypeDefinitionRegistry transcribed(FactGraphs.FactGraph graph) {
        return graph.schema().written().read().registry();
    }

    @Test
    @DisplayName("the machinery no author wrote is in the emitted registry and not in the transcribed one")
    void theMintedTypesArrive() {
        var connection = derived(CONNECTION);
        assertThat(connection.transcribed().getTypeOrNull("QueryFilmsConnection")).isNull();
        assertThat(connection.registry().getTypeOrNull("QueryFilmsConnection"))
            .isInstanceOf(ObjectTypeDefinition.class);
        assertThat(connection.registry().getTypeOrNull("PageInfo"))
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
        var connection = derived(CONNECTION);
        assertThat(returnTypeOf(connection.transcribed(), "Query", "films")).isEqualTo("[Film!]!");
        assertThat(returnTypeOf(connection.registry(), "Query", "films"))
            .isEqualTo("QueryFilmsConnection!");
    }

    /**
     * The expansion replaces what a field returns and says nothing about whether the field may be
     * null, so the outer non-null the author wrote survives it. The document carries one carrier of
     * each nullability for this: {@code films} is written {@code [Film!]!} and {@code shorts} is
     * written {@code [Film!]}, and the two have to come out differently.
     *
     * <p>Not a preference. An output field that loses its non-null is a breaking change to every
     * consumer reading it, so a row saying otherwise would describe a schema the run does not emit.
     */
    @Test
    @DisplayName("the carrier keeps the outer non-null its author wrote")
    void theCarrierKeepsTheAuthorsOuterNonNull() {
        var emitted = emitted(CONNECTION);
        assertThat(extensionFieldType(emitted, "Query"))
            .isEqualTo("QueryShortsConnection");
        assertThat(returnTypeOf(emitted, "Query", "films"))
            .as("and the non-null carrier beside it stays non-null")
            .isEqualTo("QueryFilmsConnection!");
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
        var emitted = emitted(CONNECTION);
        var base = (ObjectTypeDefinition) emitted.getTypeOrNull("Query");
        assertThat(base.getFieldDefinitions())
            .as("the extension's field does not migrate onto the base declaration")
            .extracting(FieldDefinition::getName)
            .containsExactly("films");

        assertThat(extensionFieldType(emitted, "Query"))
            .isEqualTo("QueryShortsConnection");
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
        var connection = derived(CONNECTION);
        assertThat(fieldOf(connection.transcribed(), "Query", "films").getInputValueDefinitions())
            .isEmpty();
        assertThat(fieldOf(connection.registry(), "Query", "films").getInputValueDefinitions())
            .extracting(InputValueDefinition::getName)
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
     * it. The document's {@code extend type Query} is what gives it something to catch.
     */
    @Test
    @DisplayName("the emitted registry assembles, so the minted types meet the specification's rules")
    void theEmittedRegistryAssembles() {
        assertThat(SchemaAssembly.of(emitted(CONNECTION)))
            .isInstanceOf(SchemaAssembly.Assembled.class);
    }

    @Test
    @DisplayName("the registry handed in is not the one patched")
    void theInputIsLeftAlone() {
        var connection = derived(CONNECTION);
        assertThat(connection.registry()).isNotSameAs(connection.transcribed());
        assertThat(connection.transcribed().types()).doesNotContainKey("QueryFilmsConnection");
    }

    /**
     * The control. With nothing to expand, the emitted registry is the transcribed one less what is
     * not published, the support types nothing references, and the check is on the whole type
     * population rather than on the absence of a connection: a patch that invented a type here
     * would be as wrong as one that failed to invent a connection above. The list field with an
     * argument of its own is the shape a connection rewrite would touch, so it is checked whole.
     */
    @Test
    @DisplayName("a schema with no macro emits the population it declared")
    void nothingIsInventedWithoutAMacro() {
        var plain = derived(PLAIN);
        var declared = new HashSet<>(plain.transcribed().types().keySet());
        declared.removeAll(DirectiveSupportTypes.all());
        assertThat(plain.registry().types().keySet()).isEqualTo(declared);
        assertThat(returnTypeOf(plain.registry(), "Shelf", "books")).isEqualTo("[String]");
        assertThat(fieldOf(plain.registry(), "Shelf", "books").getInputValueDefinitions())
            .extracting(InputValueDefinition::getName)
            .containsExactly("first");
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
        var emitted = emitted("scenarios/minted-tag-declared-page-info");

        assertThat(typeDirectives(emitted, "PageInfo"))
            .as("the author's PageInfo, as the author wrote it")
            .isEmpty();
        assertThat(typeDirectives(emitted, "QueryFilmsConnection"))
            .containsExactly("@tag(name: \"experimental\")");
        assertThat(typeDirectives(emitted, "QueryFilmsConnectionEdge"))
            .containsExactly("@tag(name: \"experimental\")");
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
        var emitted = derived("scenarios/minted-tag-intersection").emitted();
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
    }

    // ===== The keys the anchor mints =====

    /** Node types in a federation-linked graph, keyless, authored-keyed and otherwise. */
    private static final String LINKED = "defects/federation-key";

    /**
     * The minted {@code @key} arrives, with the arguments the anchor states rather than ones the
     * patch re-mints. Read from a captured graph, so the rule that decides it and the patch that
     * renders it are read together over what an author wrote.
     */
    @Test
    @DisplayName("a node type in a federation-linked graph gets the key the anchor minted")
    void theMintedKeyIsApplied() {
        assertThat(typeDirectives(emitted(LINKED), "Film"))
            .as("graphitron's own @table and @node are not published")
            .containsExactly("@key(fields: \"id\", resolvable: true)");
    }

    /** The opt-in is the whole rule, so a graph that never linked federation gets no key. */
    @Test
    @DisplayName("an unlinked graph has no key to apply")
    void nothingIsAppliedWithoutTheOptIn() {
        assertThat(typeDirectives(emitted("defects/federation-key-unlinked"), "Language")).isEmpty();
    }

    /**
     * A key the author already wrote is not applied a second time. The authored application is in
     * the registry because its author wrote it there, and it is in the directive anchor's authored
     * set too; only the minted set is applied, so this fails on a patch that applies both.
     */
    @Test
    @DisplayName("an authored key is not applied a second time")
    void theAuthoredKeyIsNotReapplied() {
        assertThat(typeDirectives(emitted(LINKED), "Actor"))
            .as("the author's own application, once")
            .containsExactly("@key(fields: \"id\")");
    }

    /**
     * The derivation starts from the registry before synthesis, and an application it finds there
     * already is a mint applied twice. Stated by handing it the captured registry with the minted
     * key already on {@code Film}: the patch has to say so rather than append a second one or
     * quietly skip.
     */
    @Test
    @DisplayName("a registry already carrying the minted key is refused as a generator defect")
    void aKeyAlreadyMintedIsRefused() {
        FACTS.read(LINKED, graph -> {
            var transcribed = transcribed(graph);
            var minted = ((ObjectTypeDefinition) EmittedRegistry.derive(transcribed, graph.handle())
                .registry().getTypeOrNull("Film")).getDirectives();
            var film = (ObjectTypeDefinition) transcribed.getTypeOrNull("Film");
            var carried = new ArrayList<Directive>(film.getDirectives());
            carried.addAll(minted);
            var alreadyMinted = new TypeDefinitionRegistry().merge(transcribed);
            alreadyMinted.remove(film);
            alreadyMinted.add(film.transform(b -> b.directives(carried)));

            assertThatThrownBy(() -> EmittedRegistry.derive(alreadyMinted, graph.handle()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'Film'")
                .hasMessageContaining("defect in graphitron");
            return null;
        });
    }

    // ===== What is not published =====

    /**
     * Graphitron's own directives are read while generating and mean nothing to a consumer, so
     * the emitted schema holds neither their definitions nor their applications, while a directive
     * the author declared stays with every application of it.
     */
    @Test
    @DisplayName("graphitron's own directives leave the emitted schema, an author's stay")
    void graphitronsOwnDirectivesAreNotPublished() {
        var emitted = emitted("scenarios/directive-argument-list-depth");

        assertThat(emitted.getDirectiveDefinition("table")).as("graphitron's definition").isEmpty();
        assertThat(typeDirectives(emitted, "FactAnchor")).as("graphitron's application").isEmpty();
        assertThat(emitted.getDirectiveDefinition("shelf")).as("the author's definition").isPresent();
        assertThat(typeDirectives(emitted, "Shelf"))
            .as("the author's application").containsExactly("@shelf(a: 1)");
    }

    /**
     * A support type is published where an author's type references it, and only then; a
     * strictly internal one is referenced by graphitron's own directives alone, which have gone,
     * so it never is.
     */
    @Test
    @DisplayName("a support type is published where an author's type references it, and only then")
    void aSupportTypeIsPublishedWhereReferenced() {
        var referenced = emitted("scenarios/order-by-index");
        assertThat(referenced.getTypeOrNull("SortDirection"))
            .as("FilmOrderBy references SortDirection").isNotNull();
        DirectiveSupportTypes.strictlyInternal().forEach(name ->
            assertThat(referenced.getTypeOrNull(name)).as("strictly internal %s", name).isNull());

        var unreferenced = emitted(PLAIN);
        DirectiveSupportTypes.all().forEach(name ->
            assertThat(unreferenced.getTypeOrNull(name)).as("unreferenced %s", name).isNull());
    }

    // ---------------------------------------------------------------------------------------

    private static List<String> typeDirectives(TypeDefinitionRegistry registry,
                                               String type) {
        assertThat(registry.getTypeOrNull(type)).as(type).isInstanceOf(ObjectTypeDefinition.class);
        return ((ObjectTypeDefinition) registry.getTypeOrNull(type)).getDirectives().stream()
            .map(AstPrinter::printAst).toList();
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
