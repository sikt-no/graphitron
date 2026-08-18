package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_REFERENCE_DISCOVERY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The registered agreement anchor for {@code intent_field_reference_discovery}: the foreign key an
 * omitted {@code @reference} path finds between a field's two endpoints. The complement of what
 * {@code ReferenceStepTargetTest} pins, which is the chain an authored path walks; here nothing was
 * written and the resolution is the catalog's own connectivity.
 *
 * <p>Every case captures real SDL against the test catalog rather than seeding rows, for the reason
 * that test states: a seeded fixture is free to declare a connectivity the catalog does not have, and
 * the case then pins behaviour no build can produce. The catalog supplies each shape already, one
 * foreign key facing either way, two between one pair, none between another, and a self-referential
 * one.
 *
 * <p>Each case asserts the whole graph's rows rather than a projection of them, so a coordinate that
 * should contribute nothing fails the case it appears in. Half the cases are exactly that: the
 * boundary of a discovery is what the relation claims, and a row where the walk discovers nothing
 * would render an author an overlay for a join the generator does not make.
 */
@PipelineTier
class ReferenceDiscoveryTest {

    @TempDir
    Path tmp;

    // ===== What the catalog connects =====

    /**
     * The ordinary shape: {@code customer} declares one foreign key to {@code address} and nothing
     * else connects them, so the departing table owns the key and the discovery is certain.
     */
    @Test
    void aUniqueForeignKeyFromTheParentIsTheDiscovery() {
        var sdl = """
            type Customer @table(name: "customer") {
                address: Address
            }
            type Address @table(name: "address") { address_id: ID }
            type Query { customers: [Customer] }
            """;
        withCapturedStore(sdl, dsl -> assertThat(discoveries(dsl)).containsExactly(
            "Customer.address customer->address customer_address_id_fkey fk-on-from 1"));
    }

    /**
     * The same discovery facing the other way, which is why direction is a column rather than an
     * assumption: {@code content} declares the key against {@code film}, so a field of {@code Film}
     * naming {@code Content} arrives at the table that owns the key.
     */
    @Test
    void aUniqueForeignKeyOnTheChildIsTheSameDiscovery() {
        var sdl = """
            type Film @table(name: "film") {
                content: Content
            }
            type Content @table(name: "content") { content_id: ID }
            type Query { films: [Film] }
            """;
        withCapturedStore(sdl, dsl -> assertThat(discoveries(dsl)).containsExactly(
            "Film.content film->content content_film_id_fkey fk-on-to 1"));
    }

    /**
     * Two keys, two rows, and the arity says so. {@code film} declares both
     * {@code film_language_id_fkey} and {@code film_original_language_id_fkey} against
     * {@code language}, which is the coordinate the walk rejects with "which foreign key did you
     * mean"; a reader demanding {@code candidates = 1} transcribes that refusal without re-counting.
     */
    @Test
    void twoForeignKeysBetweenTheEndpointsAreTwoRowsAndAnArityOfTwo() {
        var sdl = """
            type Film @table(name: "film") {
                language: Language
            }
            type Language @table(name: "language") { name: String }
            type Query { films: [Film] }
            """;
        withCapturedStore(sdl, dsl -> assertThat(discoveries(dsl)).containsExactly(
            "Film.language film->language film_language_id_fkey fk-on-from 2",
            "Film.language film->language film_original_language_id_fkey fk-on-from 2"));
    }

    /**
     * Endpoints the catalog connects only through a bridge table are endpoints no single key
     * connects: {@code film} and {@code actor} meet at {@code film_actor}, and the discovery never
     * searches past one hop, so this is a path the author has to write.
     */
    @Test
    void endpointsNoSingleKeyConnectsAreNoRows() {
        var sdl = """
            type Film @table(name: "film") {
                actors: [Actor!]!
            }
            type Actor @table(name: "actor") { actor_id: ID }
            type Query { films: [Film] }
            """;
        withCapturedStore(sdl, dsl -> assertThat(discoveries(dsl)).isEmpty());
    }

    /**
     * A self-referencing field is not a discovery, and the exclusion is the walk's own: both
     * endpoints are one table, so a key connecting them says nothing about which direction the field
     * navigates, and the walk asks for the key explicitly rather than guessing. Excluding the pair
     * outright is also what keeps the direction column meaningful, both orientations of such a key
     * landing on the same table.
     */
    @Test
    void aSelfReferencingFieldIsNotADiscovery() {
        var sdl = """
            type Category @table(name: "category") {
                parent: Category
            }
            type Query { categories: [Category] }
            """;
        withCapturedStore(sdl, dsl -> assertThat(discoveries(dsl)).isEmpty());
    }

    // ===== Where nothing is left to discover =====

    /**
     * An authored path answers the question this relation exists for, so the coordinate leaves it
     * nothing to answer. The chain view holds what such a field navigates; a row here as well would
     * be two relations claiming one field's join.
     */
    @Test
    void anAuthoredPathLeavesNothingToDiscover() {
        var sdl = """
            type Film @table(name: "film") {
                language: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Language @table(name: "language") { name: String }
            type Query { films: [Film] }
            """;
        withCapturedStore(sdl, dsl -> assertThat(discoveries(dsl)).isEmpty());
    }

    /**
     * A claimed field navigates nothing: the generator fetches it from the service rather than by
     * joining, so the two tables' connectivity is beside the point at this coordinate. Inherited from
     * the navigation the arriving endpoint is read through rather than restated here.
     */
    @Test
    void anAuthoredClaimDivertsTheFieldSoNothingIsDiscovered() {
        var sdl = """
            type Customer @table(name: "customer") {
                address: Address @service(service: {className: "com.example.AddressService", method: "address"})
            }
            type Address @table(name: "address") { address_id: ID }
            type Query { customers: [Customer] }
            """;
        withCapturedStore(sdl, dsl -> assertThat(discoveries(dsl)).isEmpty());
    }

    /** No arriving table, no discovery: the field names a type the catalog has no binding for. */
    @Test
    void anUnboundNamedTypeIsNoDiscovery() {
        var sdl = """
            type Customer @table(name: "customer") {
                summary: Summary
            }
            type Summary { text: String }
            type Query { customers: [Customer] }
            """;
        withCapturedStore(sdl, dsl -> assertThat(discoveries(dsl)).isEmpty());
    }

    /**
     * A root's fields navigate from no table, so a query field naming a bound type discovers nothing.
     * The other cases carry the same property implicitly, every one of them declaring a root field
     * over a bound type and asserting the whole graph's rows; this one states it.
     */
    @Test
    void aRootFieldHasNoDepartingTable() {
        var sdl = """
            type Query { addresses: [Address] }
            type Address @table(name: "address") { address_id: ID }
            """;
        withCapturedStore(sdl, dsl -> assertThat(discoveries(dsl)).isEmpty());
    }

    // ===== Helpers =====

    private static final String GRAPH = "ReferenceDiscoveryTest";

    /**
     * Every discovery the graph holds, one string per row: the coordinate, the hop, the key, which
     * end declares it, and the arity. Asserted whole so a coordinate that should discover nothing
     * cannot hide behind a filter.
     */
    private static List<String> discoveries(DSLContext dsl) {
        var d = INTENT_FIELD_REFERENCE_DISCOVERY;
        return dsl.select(d.fields())
            .from(d)
            .where(d.GRAPH_NAME.eq(GRAPH))
            .orderBy(d.TYPE_NAME, d.FIELD_NAME, d.CONSTRAINT_NAME)
            .fetch()
            .map(row -> row.get(d.TYPE_NAME) + "." + row.get(d.FIELD_NAME) + " "
                + (row.get(d.FROM_TABLE) + "->" + row.get(d.TO_TABLE)).toLowerCase(Locale.ROOT) + " "
                + row.get(d.CONSTRAINT_NAME)
                + (Boolean.TRUE.equals(row.get(d.FK_ON_FROM)) ? " fk-on-from " : " fk-on-to ")
                + row.get(d.CANDIDATES));
    }

    private void withCapturedStore(String sdl, Consumer<DSLContext> body) {
        var ctx = testContext();
        try (var store = CapturedStore.ofCatalog(tmp, GRAPH, sdl,
                new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader()))) {
            body.accept(store.dsl());
        }
    }
}
