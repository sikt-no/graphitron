package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.TestRunContext;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jooq.DSLContext;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.jooq.Record3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_PATH_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_NEED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_STEP;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.test.CapturedStore.GRAPH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The path relations hold a consistent row set and refuse an inconsistent one by their own keys and
 * checks: a need's landing is a type or a table as its kind says, a path names a claim exactly when
 * it was not inferred, a need goes with its occurrence, and a claim outlives what it was built from
 * until the sweep takes it.
 *
 * <p>The rows past the claim are the test's own, written over a captured store whose occurrence,
 * claim, types and tables they reference, since no stage derives them yet.
 */
class PathRelationConstraintTest {

    private static final String SDL = """
        type Query { customers: [Customer!]! }
        type Address @table(name: "address") { addressId: Int @field(name: "address_id") }
        type Customer @table(name: "customer") {
          address: Address @reference(path: [{key: "customer_address_id_fkey"}])
        }
        """;

    private static final String COORDINATE = "Customer.address";
    private static final String TARGET = "Address";
    private static final LocalDateTime NOW = LocalDateTime.now();

    @Test
    @DisplayName("a written path to a type and an inferred path to a table are accepted, and go with their occurrence")
    void aConsistentRowSetIsAccepted(@TempDir Path tmp) {
        try (var store = CapturedStore.ownStoreOfCatalog(tmp, SDL, CatalogInput.of(TestRunContext.of()))) {
            var dsl = store.dsl();
            var customer = table(dsl, "customer");
            var address = table(dsl, "address");
            assertOccurrenceAndClaim(dsl);

            insertTypeNeed(dsl, customer);
            insertPath(dsl, customer, "TYPE", TARGET, address, "WRITTEN", COORDINATE, TARGET);
            insertStep(dsl, customer, "TYPE", TARGET, customer, address, 0);

            insertTableNeed(dsl, customer, address);
            String landing = address.value2() + ".address";
            insertPath(dsl, customer, "TABLE", landing, address, "INFERRED", null, null);
            insertStep(dsl, customer, "TABLE", landing, customer, address, null);

            assertThat(dsl.fetchCount(GRAPHITRON_PATH)).isEqualTo(2);
            assertThat(dsl.fetchCount(GRAPHITRON_PATH_STEP)).isEqualTo(2);

            var o = GRAPHITRON_OCCURRENCE;
            dsl.deleteFrom(o).where(o.GRAPH_NAME.eq(GRAPH), o.COORDINATE.eq(COORDINATE)).execute();
            assertThat(dsl.fetchCount(GRAPHITRON_PATH_NEED)).as("needs go with their occurrence").isZero();
            assertThat(dsl.fetchCount(GRAPHITRON_PATH)).as("paths go with their need").isZero();
            assertThat(dsl.fetchCount(GRAPHITRON_PATH_STEP)).as("steps go with their need").isZero();
            var a = GRAPHITRON_PATH_CLAIM;
            assertThat(dsl.fetchCount(a, a.GRAPH_NAME.eq(GRAPH), a.ELEMENT_COORDINATE.eq(COORDINATE)))
                .as("the claim is the declaration's, not the occurrence's, and stays")
                .isOne();
        }
    }

    @Test
    @DisplayName("a claim survives its element going, its reference set null, and the path following it stays")
    void aClaimOutlivesItsElement(@TempDir Path tmp) {
        try (var store = CapturedStore.ownStoreOfCatalog(tmp, SDL, CatalogInput.of(TestRunContext.of()))) {
            var dsl = store.dsl();
            var customer = table(dsl, "customer");
            var address = table(dsl, "address");
            assertOccurrenceAndClaim(dsl);
            insertTypeNeed(dsl, customer);
            insertPath(dsl, customer, "TYPE", TARGET, address, "WRITTEN", COORDINATE, TARGET);

            // The field row references the element without a cascade, so it goes first.
            var f = GRAPHITRON_FIELD;
            dsl.deleteFrom(f).where(f.GRAPH_NAME.eq(GRAPH), f.COORDINATE.eq(COORDINATE)).execute();
            var e = GRAPHITRON_ELEMENT;
            dsl.deleteFrom(e).where(e.GRAPH_NAME.eq(GRAPH), e.COORDINATE.eq(COORDINATE)).execute();

            var a = GRAPHITRON_PATH_CLAIM;
            var claim = dsl.select(a.ELEMENT_GRAPH_REF, a.ELEMENT_COORDINATE_REF, a.TARGET_TYPE_REF)
                .from(a).where(a.GRAPH_NAME.eq(GRAPH), a.ELEMENT_COORDINATE.eq(COORDINATE),
                    a.TARGET_TYPE.eq(TARGET))
                .fetchOptional();
            assertThat(claim).as("the claim survives its element").isPresent();
            assertThat(claim.get().value1()).as("the element reference's graph half").isNull();
            assertThat(claim.get().value2()).as("the element reference's coordinate half").isNull();
            assertThat(claim.get().value3()).as("the type still stands").isEqualTo(TARGET);
            assertThat(dsl.fetchCount(GRAPHITRON_PATH)).as("the path following it stays").isOne();
        }
    }

    @Test
    @DisplayName("a TYPE need with a landing table, a need on no occurrence, an inferred path with a claim, a written one without, and a step on no need are refused")
    void anInconsistentRowIsRefused(@TempDir Path tmp) {
        try (var store = CapturedStore.ownStoreOfCatalog(tmp, SDL, CatalogInput.of(TestRunContext.of()))) {
            var dsl = store.dsl();
            var customer = table(dsl, "customer");
            var address = table(dsl, "address");
            assertOccurrenceAndClaim(dsl);

            var n = GRAPHITRON_PATH_NEED;
            assertRefused("Check constraint", () -> dsl.insertInto(n)
                .set(n.GRAPH_NAME, GRAPH).set(n.COORDINATE, COORDINATE)
                .set(n.START_SOURCE_NAME, customer.value1()).set(n.START_SCHEMA, customer.value2())
                .set(n.START_TABLE, customer.value3())
                .set(n.LANDING_KIND, "TYPE").set(n.LANDING, TARGET).set(n.LANDING_TYPE, TARGET)
                .set(n.LANDING_SOURCE_NAME, address.value1()).set(n.LANDING_SCHEMA, address.value2())
                .set(n.LANDING_TABLE, address.value3())
                .set(n.TOUCHED_AT, NOW).execute());

            assertRefused("Referential integrity", () -> dsl.insertInto(n)
                .set(n.GRAPH_NAME, GRAPH).set(n.COORDINATE, "Customer.nowhere")
                .set(n.START_SOURCE_NAME, customer.value1()).set(n.START_SCHEMA, customer.value2())
                .set(n.START_TABLE, customer.value3())
                .set(n.LANDING_KIND, "TYPE").set(n.LANDING, TARGET).set(n.LANDING_TYPE, TARGET)
                .set(n.TOUCHED_AT, NOW).execute());

            insertTypeNeed(dsl, customer);
            assertRefused("Check constraint", () -> insertPath(dsl, customer, "TYPE", TARGET, address,
                "INFERRED", COORDINATE, TARGET));
            assertRefused("Check constraint", () -> insertPath(dsl, customer, "TYPE", TARGET, address,
                "WRITTEN", null, null));
            assertRefused("Check constraint", () -> insertPath(dsl, customer, "TYPE", TARGET, address,
                "WRITTEN", COORDINATE, null));

            assertRefused("Referential integrity",
                () -> insertStep(dsl, customer, "TYPE", "Customer", customer, address, 0));
        }
    }

    /**
     * The occurrence the needs key on and the claim the written path follows, both as the capture
     * wrote them: the field's own coordinate, and its declaration reaching its target type.
     */
    private static void assertOccurrenceAndClaim(DSLContext dsl) {
        var o = GRAPHITRON_OCCURRENCE;
        assertThat(dsl.fetchCount(o, o.GRAPH_NAME.eq(GRAPH), o.COORDINATE.eq(COORDINATE)))
            .as("capture writes the field's occurrence at its own coordinate")
            .isOne();
        var a = GRAPHITRON_PATH_CLAIM;
        var claim = dsl.select(a.CLAIM_KIND, a.ELEMENT_COORDINATE_REF, a.TARGET_TYPE_REF,
                a.SOURCE_NAME, a.SOURCE_LINE, a.SOURCE_COLUMN)
            .from(a).where(a.GRAPH_NAME.eq(GRAPH), a.ELEMENT_COORDINATE.eq(COORDINATE),
                a.TARGET_TYPE.eq(TARGET))
            .fetchSingle();
        assertThat(claim.value1()).isEqualTo("FIELD");
        assertThat(claim.value2()).as("the element reference").isEqualTo(COORDINATE);
        assertThat(claim.value3()).as("the type reference").isEqualTo(TARGET);
        var e = GRAPHITRON_AST_PATH_ENTRY;
        assertThat(dsl.fetchCount(e, e.GRAPH_NAME.eq(GRAPH), e.SOURCE_NAME.eq(claim.value4()),
                e.ANCHOR_LINE.eq(claim.value5()), e.ANCHOR_COLUMN.eq(claim.value6()),
                e.PLACE.eq(0)))
            .as("the claim stands at the position capture keyed the field's element under")
            .isOne();
    }

    private static void insertTypeNeed(DSLContext dsl, Record3<String, String, String> start) {
        var n = GRAPHITRON_PATH_NEED;
        dsl.insertInto(n)
            .set(n.GRAPH_NAME, GRAPH).set(n.COORDINATE, COORDINATE)
            .set(n.START_SOURCE_NAME, start.value1()).set(n.START_SCHEMA, start.value2())
            .set(n.START_TABLE, start.value3())
            .set(n.LANDING_KIND, "TYPE").set(n.LANDING, TARGET).set(n.LANDING_TYPE, TARGET)
            .set(n.TOUCHED_AT, NOW).execute();
    }

    private static void insertTableNeed(DSLContext dsl, Record3<String, String, String> start,
                                        Record3<String, String, String> landing) {
        var n = GRAPHITRON_PATH_NEED;
        dsl.insertInto(n)
            .set(n.GRAPH_NAME, GRAPH).set(n.COORDINATE, COORDINATE)
            .set(n.START_SOURCE_NAME, start.value1()).set(n.START_SCHEMA, start.value2())
            .set(n.START_TABLE, start.value3())
            .set(n.LANDING_KIND, "TABLE").set(n.LANDING, landing.value2() + "." + landing.value3())
            .set(n.LANDING_SOURCE_NAME, landing.value1()).set(n.LANDING_SCHEMA, landing.value2())
            .set(n.LANDING_TABLE, landing.value3())
            .set(n.TOUCHED_AT, NOW).execute();
    }

    private static void insertPath(DSLContext dsl, Record3<String, String, String> start,
                                   String landingKind, String landing,
                                   Record3<String, String, String> target, String origin,
                                   String claimCoordinate, String claimTargetType) {
        var p = GRAPHITRON_PATH;
        dsl.insertInto(p)
            .set(p.GRAPH_NAME, GRAPH).set(p.COORDINATE, COORDINATE)
            .set(p.START_SOURCE_NAME, start.value1()).set(p.START_SCHEMA, start.value2())
            .set(p.START_TABLE, start.value3())
            .set(p.LANDING_KIND, landingKind).set(p.LANDING, landing)
            .set(p.TARGET_SOURCE_NAME, target.value1()).set(p.TARGET_SCHEMA, target.value2())
            .set(p.TARGET_TABLE, target.value3())
            .set(p.ORIGIN, origin).set(p.CLAIM_COORDINATE, claimCoordinate)
            .set(p.CLAIM_TARGET_TYPE, claimTargetType)
            .set(p.TOUCHED_AT, NOW).execute();
    }

    private static void insertStep(DSLContext dsl, Record3<String, String, String> start,
                                   String landingKind, String landing,
                                   Record3<String, String, String> from,
                                   Record3<String, String, String> to, Integer elementPlace) {
        var s = GRAPHITRON_PATH_STEP;
        dsl.insertInto(s)
            .set(s.GRAPH_NAME, GRAPH).set(s.COORDINATE, COORDINATE)
            .set(s.START_SOURCE_NAME, start.value1()).set(s.START_SCHEMA, start.value2())
            .set(s.START_TABLE, start.value3())
            .set(s.LANDING_KIND, landingKind).set(s.LANDING, landing).set(s.POSITION, 0)
            .set(s.FROM_SOURCE_NAME, from.value1()).set(s.FROM_SCHEMA, from.value2())
            .set(s.FROM_TABLE, from.value3())
            .set(s.TO_SOURCE_NAME, to.value1()).set(s.TO_SCHEMA, to.value2())
            .set(s.TO_TABLE, to.value3())
            .set(s.ELEMENT_PLACE, elementPlace).set(s.TOUCHED_AT, NOW).execute();
    }

    private static Record3<String, String, String> table(DSLContext dsl, String name) {
        var t = SQL_TABLE;
        return dsl.select(t.SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME).from(t)
            .where(t.TABLE_NAME.eq(name)).fetchSingle();
    }

    private static void assertRefused(String constraint, ThrowingCallable insert) {
        assertThatThrownBy(insert)
            .isInstanceOf(IntegrityConstraintViolationException.class)
            .hasMessageContaining(constraint);
    }
}
