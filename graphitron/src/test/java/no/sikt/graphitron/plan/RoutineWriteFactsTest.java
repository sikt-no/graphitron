package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.Arity;
import no.sikt.graphitron.command.CatalogTable;
import no.sikt.graphitron.command.JoinBasis;
import no.sikt.graphitron.command.JoinCondition;
import no.sikt.graphitron.command.KeyPair;
import no.sikt.graphitron.command.RoutineCall;
import no.sikt.graphitron.command.RoutineWriteCommand;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.catalog.ClasspathScanner;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.rewrite.CapturedStore.GRAPH;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agreement anchor for {@link RoutineWriteFacts}: what the store states about a graph's
 * routine-writing coordinates equals what the walk classified for the same SDL, fact for fact.
 *
 * <p>Agreement and not a projection of it. Each case renders both sides of one coordinate into the
 * same string, covering every fact the command row carries: the call's class, method, result table
 * and ordered arguments; the chain's hops with their aliases, tables, keying, pairing and filters;
 * the carrier's target table and captured pairing; the delivered cardinality. A divergence anywhere
 * in that surface fails the case, which is the property that makes this an agreement test rather
 * than two independent assertions that happen to pass.
 *
 * <p>The one spelling difference is reconciled rather than asserted away. A routine parameter's
 * bound type reaches the walk as a javapoet type built from a live {@code Class} and reaches the
 * store as that class's own reflected name, which writes an array as a JVM descriptor; both are
 * decoded here before comparing, because the two forms denote one type and a string comparison
 * would report a difference that is not one.
 *
 * <p>Real SDL captured against the test catalog, never seeded rows: a seeded fixture is free to
 * declare a shape capture never writes, and the case would then pin behaviour no build can produce.
 * The catalog supplies the routine the writes depart from ({@code rent_film}) and the tables their
 * re-reads reach.
 */
@PipelineTier
class RoutineWriteFactsTest {

    @TempDir
    Path tmp;

    private static final String ERRORS = """
        type DbErr @error(handlers: [{handler: DATABASE, sqlState: "23503"}]) {
            path: [String!]!
            message: String!
        }
        union RentFilmError = DbErr
        """;

    private static final String RENTAL = """
        type Rental @table(name: "rental") { rentalId: Int! @field(name: "rental_id") }
        type Query { rental: Rental }
        """;

    /**
     * The chain seat, whole: the routine call departs the write, the single {@code @reference}
     * element anchors the post-commit re-read, and the field's return names that same table.
     */
    @Test
    void theChainSeatAgreesWithTheWalk() {
        String sdl = RENTAL + """
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): [Rental!]!
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @reference(path: [{table: "rental"}])
            }
            """;
        assertThat(fromStore(sdl))
            .as("every fact the chain-seated row carries, as the store states it")
            .containsExactly(
                "Mutation.rentFilm CHAIN Rental list=true"
                + " call=no.sikt.graphitron.rewrite.test.jooq.Routines.rentFilm"
                + " -> no.sikt.graphitron.rewrite.test.jooq.tables.RentFilm"
                + " args=[pInventoryId:java.lang.Integer=inventoryId,"
                + " pCustomerId:java.lang.Integer=customerId]"
                + " hops=[1 rentFilm_0 rental RENTAL_ID->RENTAL_ID]");
        assertThat(agreementFromStore(sdl))
            .as("and the walk classified the same facts from the same SDL")
            .isEqualTo(agreementFromWalk(sdl));
        assertThat(hopKeying(sdl, 1))
            .as("the chain departs a function result, which declares no constraint to name, so its"
                + " first hop is keyed by matching the arriving primary key's column names")
            .isInstanceOf(JoinBasis.Keying.NameMatched.class);
    }

    /** The keying the store states for one hop of the fixture's single coordinate. */
    private JoinBasis.Keying hopKeying(String sdl, int seq) {
        var hop = storeRows(sdl).getFirst().hops().stream()
            .filter(h -> h.seq() == seq).findFirst().orElseThrow();
        return ((JoinBasis.ColumnPairs) hop.on()).keying();
    }

    /**
     * The carrier seat, whole: no path is written, the return is a payload wrapping one data field
     * beside an error channel, and the capture keys on the name match out of the routine result.
     */
    @Test
    void theCarrierSeatAgreesWithTheWalk() {
        String sdl = ERRORS + RENTAL + """
            type RentFilmPayload { rental: Rental errors: [RentFilmError!] }
            type Mutation {
              rentFilmPayload(inventoryId: Int!, customerId: Int!): RentFilmPayload
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
            }
            """;
        assertThat(fromStore(sdl))
            .as("the carrier's target table and its captured pairing, as the store states them")
            .containsExactly(
                "Mutation.rentFilmPayload CARRIER RentFilmPayload list=false"
                + " call=no.sikt.graphitron.rewrite.test.jooq.Routines.rentFilm"
                + " -> no.sikt.graphitron.rewrite.test.jooq.tables.RentFilm"
                + " args=[pInventoryId:java.lang.Integer=inventoryId,"
                + " pCustomerId:java.lang.Integer=customerId]"
                + " target=rental captured=[RENTAL_ID->RENTAL_ID]");
        assertThat(agreementFromStore(sdl)).isEqualTo(agreementFromWalk(sdl));
    }

    /**
     * A mutation that writes through DML draws no row, which is membership stated by the seat
     * relation rather than re-decided here: the fixture carries a live non-member so the emptiness
     * is a refusal and not an empty graph.
     */
    @Test
    void aDmlMutationIsNoMemberOfTheRelation() {
        String sdl = RENTAL + """
            type Film @table(name: "film") { title: String }
            input FilmInput { title: String }
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): [Rental!]!
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @reference(path: [{table: "rental"}])
              createFilm(in: FilmInput!): Film @mutation(typeName: INSERT)
            }
            """;
        assertThat(fromStore(sdl))
            .as("the routine write is a row and the DML insert beside it is not")
            .hasSize(1)
            .allMatch(row -> row.startsWith("Mutation.rentFilm "));
        assertThat(agreementFromStore(sdl)).isEqualTo(agreementFromWalk(sdl));
    }

    /**
     * A chain of two hops: the aliases run continuously across the chain from zero, the hop order is
     * the authored one, and the second hop's foreign key is the one connecting its own endpoints.
     */
    @Test
    void aTwoHopChainAgreesWithTheWalk() {
        String sdl = """
            type Customer @table(name: "customer") { customerId: Int! @field(name: "customer_id") }
            type Query { customer: Customer }
            type Mutation {
              rentFilm(inventoryId: Int!, customerId: Int!): [Customer!]!
                @routine(name: "rent_film", argMapping: "pInventoryId: inventoryId, pCustomerId: customerId")
                @reference(path: [{table: "rental"}, {table: "customer"}])
            }
            """;
        assertThat(agreementFromStore(sdl))
            .as("two hops, aliased from zero, each keyed by the foreign key joining its own ends")
            .allMatch(row -> row.contains(
                "hops=[1 rentFilm_0 rental RENTAL_ID->RENTAL_ID,"
                + " 2 rentFilm_1 customer fk CUSTOMER_ID->CUSTOMER_ID]"));
        assertThat(agreementFromStore(sdl)).isEqualTo(agreementFromWalk(sdl));
    }

    // -------------------------------------------------------------------------------------
    // Both sides, rendered into one form.
    // -------------------------------------------------------------------------------------

    private List<RoutineWriteFacts.Row> storeRows(String sdl) {
        var ctx = testContext();
        try (var store = CapturedStore.ofCatalog(tmp, GRAPH, sdl,
                new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader()), census())) {
            return RoutineWriteFacts.read(new StoreHandle(store.dsl(), GRAPH)).rows();
        }
    }

    private List<String> fromStore(String sdl) {
        return storeRows(sdl).stream().map(RoutineWriteFactsTest::render).toList();
    }

    /**
     * One store row rendered whole, return type included. The literal assertions read this form;
     * the agreement comparison reads {@link #agreementForm} instead, because the return type is a
     * fact the store carries at both seats and the command row carries only at one.
     */
    private static String render(RoutineWriteFacts.Row row) {
        return row.typeName() + "." + row.fieldName() + " " + row.seat() + " "
            + row.returnTypeName() + " " + agreementBody(row);
    }

    private static String agreementForm(RoutineWriteFacts.Row row) {
        return row.typeName() + "." + row.fieldName() + " " + row.seat() + " "
            + agreementBody(row);
    }

    private static String agreementBody(RoutineWriteFacts.Row row) {
        String head = "list=" + row.listReturn() + " " + render(row.call());
        return row.seat() == RoutineWriteFacts.Seat.CHAIN
            ? head + " hops=[" + joined(row.hops().stream()
                .map(h -> renderHop(h.seq(), h.alias(), h.table(), h.on(), h.filter()))) + "]"
            : head + " target=" + row.targetTable().sqlName()
                + " captured=[" + renderPairs(row.capturedPairs()) + "]";
    }

    /**
     * One command row in the same form. The chain arm's anchor is the chain's first hop, split out
     * as its own component because the re-read departs from it rather than joining it, so it is
     * rendered back at sequence one and the tail follows from two.
     */
    private static String agreementForm(RoutineWriteCommand command) {
        return switch (command) {
            case RoutineWriteCommand.ChainReread c -> c.coordinate().getTypeName() + "."
                + c.coordinate().getFieldName() + " CHAIN"
                + " list=" + (c.arity() == Arity.LIST) + " " + render(c.call())
                + " hops=[" + joined(Stream.concat(
                    Stream.of(renderHop(1, c.anchor().alias(), c.anchor().table(),
                        new JoinBasis.ColumnPairs(new JoinBasis.Keying.NameMatched(),
                            c.anchor().capturedPairs()),
                        null)),
                    Stream.iterate(2, i -> i + 1).limit(c.hops().size())
                        .map(i -> renderHop(i, c.hops().get(i - 2).alias(),
                            c.hops().get(i - 2).table(), c.hops().get(i - 2).on(),
                            c.hops().get(i - 2).filter())))) + "]";
            case RoutineWriteCommand.CarrierKeys c -> c.coordinate().getTypeName() + "."
                + c.coordinate().getFieldName() + " CARRIER"
                + " list=" + (c.arity() == Arity.LIST) + " " + render(c.call())
                + " target=" + c.targetTable().sqlName()
                + " captured=[" + renderPairs(c.capturedPairs()) + "]";
        };
    }

    private static String render(RoutineCall call) {
        return "call=" + call.routinesClassName() + "." + call.methodName()
            + " -> " + call.resultTable().tableClassName()
            + " args=[" + joined(call.arguments().stream()
                .map(a -> a.parameterName() + ":" + decode(a.javaTypeName()) + "=" + a.path()))
            + "]";
    }

    /**
     * One hop, with its keying stated from sequence two.
     *
     * <p>The chain's first hop is the re-read's anchor, and a command row carries no keying for it:
     * the statement departs from that table rather than joining it, its pairing being what the
     * capture projects and filters on. So the agreement form states the anchor's pairing alone,
     * there being nothing on the other side for a keying to agree with.
     */
    private static String renderHop(int seq, String alias, CatalogTable table, JoinBasis on,
                                    JoinCondition filter) {
        return seq + " " + alias + " " + table.sqlName() + " "
            + (seq == 1 ? renderPairs(((JoinBasis.ColumnPairs) on).pairs()) : renderBasis(on))
            + renderFilter(filter);
    }

    private static String renderBasis(JoinBasis basis) {
        return switch (basis) {
            case JoinBasis.ColumnPairs cp -> (cp.keying() instanceof JoinBasis.Keying.ForeignKey
                ? "fk " : "name ") + renderPairs(cp.pairs());
            case JoinBasis.Predicate p -> "on " + p.condition().className() + "."
                + p.condition().methodName();
        };
    }

    private static String renderFilter(JoinCondition filter) {
        return filter == null ? "" : " filter=" + filter.className() + "." + filter.methodName();
    }

    private static String renderPairs(List<KeyPair> pairs) {
        return joined(pairs.stream()
            .map(p -> p.sourceSide().javaName() + "->" + p.targetSide().javaName()));
    }

    private static String joined(Stream<String> parts) {
        return parts.collect(Collectors.joining(", "));
    }

    /**
     * The two type spellings reduced to one. The walk writes a routine parameter's type the way
     * source writes it and the store writes the reflected name; both decode to the same javapoet
     * type, which is what the comparison is about.
     */
    private static String decode(String javaTypeName) {
        return javaTypeName.endsWith("[]") ? javaTypeName
            : ColumnRef.decodeBindingType(javaTypeName).toString();
    }

    private List<String> agreementFromStore(String sdl) {
        return storeRows(sdl).stream().map(RoutineWriteFactsTest::agreementForm).toList();
    }

    private static List<String> agreementFromWalk(String sdl) {
        var bundle = TestSchemaHelper.buildBundle(sdl);
        return EmitPlan.produceWithoutStore(bundle.model(), bundle.federationLink(),
                bundle.usesOneOf(), DEFAULT_OUTPUT_PACKAGE)
            .routineWrites().rows().stream()
            .map(RoutineWriteFactsTest::agreementForm)
            .toList();
    }

    private static List<CompletionData.ExternalReference> census() {
        return ClasspathScanner.scan(testClassRoot(), testContext().jooqPackage());
    }

    private static Path testClassRoot() {
        try {
            return Path.of(RoutineWriteFactsTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("the test classes are not on a file path", e);
        }
    }

}
