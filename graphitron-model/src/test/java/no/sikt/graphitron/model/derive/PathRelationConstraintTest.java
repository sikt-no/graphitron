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

import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_FIELD_PATH_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_PATH_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_ANCHOR;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_HOLDER;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_NEED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_STEP;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.test.CapturedStore.GRAPH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The path relations hold a consistent row set and refuse an inconsistent one by their own keys and
 * checks: a need's landing is a type or a table as its kind says, a path names an anchor exactly when
 * it was not inferred, and a step needs its need.
 *
 * <p>The rows past the written elements are the test's own, written over a captured store whose
 * field, type, positions and tables they reference, since no stage derives them yet.
 */
class PathRelationConstraintTest {

    private static final String SDL = """
        type Query { customers: [Customer!]! }
        type Address @table(name: "address") { addressId: Int @field(name: "address_id") }
        type Customer @table(name: "customer") {
          address: Address @reference(path: [{key: "customer_address_id_fkey"}])
        }
        """;

    private static final String HOLDER = "Customer.address";
    private static final LocalDateTime NOW = LocalDateTime.now();

    @Test
    @DisplayName("a written path to a type and an inferred path to a table are accepted, and go with their holder")
    void aConsistentRowSetIsAccepted(@TempDir Path tmp) {
        try (var store = CapturedStore.ownStoreOfCatalog(tmp, SDL, CatalogInput.of(TestRunContext.of()))) {
            var dsl = store.dsl();
            var customer = table(dsl, "customer");
            var address = table(dsl, "address");
            seedHolderAndAnchor(dsl);

            insertTypeNeed(dsl, customer);
            insertPath(dsl, customer, "TYPE", "Address", address, "WRITTEN", HOLDER);
            insertStep(dsl, customer, "TYPE", "Address", customer, address, 0);

            insertTableNeed(dsl, customer, address);
            String landing = address.value2() + ".address";
            insertPath(dsl, customer, "TABLE", landing, address, "INFERRED", null);
            insertStep(dsl, customer, "TABLE", landing, customer, address, null);

            assertThat(dsl.fetchCount(GRAPHITRON_PATH)).isEqualTo(2);
            assertThat(dsl.fetchCount(GRAPHITRON_PATH_STEP)).isEqualTo(2);

            var h = GRAPHITRON_PATH_HOLDER;
            dsl.deleteFrom(h).where(h.GRAPH_NAME.eq(GRAPH), h.HOLDER.eq(HOLDER)).execute();
            assertThat(dsl.fetchCount(GRAPHITRON_PATH_NEED)).as("needs go with their holder").isZero();
            assertThat(dsl.fetchCount(GRAPHITRON_PATH)).as("paths go with their need").isZero();
            assertThat(dsl.fetchCount(GRAPHITRON_PATH_STEP)).as("steps go with their need").isZero();
        }
    }

    @Test
    @DisplayName("a TYPE need with a landing table, an inferred path with an anchor and a step on no need are refused")
    void anInconsistentRowIsRefused(@TempDir Path tmp) {
        try (var store = CapturedStore.ownStoreOfCatalog(tmp, SDL, CatalogInput.of(TestRunContext.of()))) {
            var dsl = store.dsl();
            var customer = table(dsl, "customer");
            var address = table(dsl, "address");
            seedHolderAndAnchor(dsl);

            var n = GRAPHITRON_PATH_NEED;
            assertRefused("Check constraint", () -> dsl.insertInto(n)
                .set(n.GRAPH_NAME, GRAPH).set(n.HOLDER, HOLDER)
                .set(n.START_SOURCE_NAME, customer.value1()).set(n.START_SCHEMA, customer.value2())
                .set(n.START_TABLE, customer.value3())
                .set(n.LANDING_KIND, "TYPE").set(n.LANDING, "Address").set(n.LANDING_TYPE, "Address")
                .set(n.LANDING_SOURCE_NAME, address.value1()).set(n.LANDING_SCHEMA, address.value2())
                .set(n.LANDING_TABLE, address.value3())
                .set(n.TOUCHED_AT, NOW).execute());

            insertTypeNeed(dsl, customer);
            assertRefused("Check constraint",
                () -> insertPath(dsl, customer, "TYPE", "Address", address, "INFERRED", HOLDER));

            assertRefused("Referential integrity",
                () -> insertStep(dsl, customer, "TYPE", "Customer", customer, address, 0));
        }
    }

    private static void seedHolderAndAnchor(DSLContext dsl) {
        var f = GRAPHITRON_AST_FIELD_PATH_ENTRY;
        var written = dsl.select(f.SOURCE_NAME, f.FIELD_LINE, f.FIELD_COLUMN, f.ELEMENT_LINE,
                f.ELEMENT_COLUMN)
            .from(f).where(f.GRAPH_NAME.eq(GRAPH), f.POSITION.eq(0)).fetchSingle();
        var a = GRAPHITRON_PATH_ANCHOR;
        dsl.insertInto(a)
            .set(a.GRAPH_NAME, GRAPH).set(a.ANCHOR, HOLDER).set(a.ANCHOR_KIND, "FIELD")
            .set(a.SOURCE_NAME, written.value1()).set(a.SOURCE_LINE, written.value2())
            .set(a.SOURCE_COLUMN, written.value3()).set(a.TOUCHED_AT, NOW).execute();
        var e = GRAPHITRON_AST_PATH_ENTRY;
        assertThat(dsl.fetchCount(e, e.GRAPH_NAME.eq(GRAPH), e.SOURCE_NAME.eq(written.value1()),
                e.ANCHOR_LINE.eq(written.value2()), e.ANCHOR_COLUMN.eq(written.value3()),
                e.PLACE.eq(0), e.ELEMENT_LINE.eq(written.value4()),
                e.ELEMENT_COLUMN.eq(written.value5())))
            .as("capture writes the field's element at its anchor, which the anchor row names")
            .isOne();
        var h = GRAPHITRON_PATH_HOLDER;
        dsl.insertInto(h)
            .set(h.GRAPH_NAME, GRAPH).set(h.HOLDER, HOLDER).set(h.KIND, "FIELD")
            .set(h.TYPE_NAME, "Customer").set(h.FIELD_NAME, "address")
            .set(h.TOUCHED_AT, NOW).execute();
    }

    private static void insertTypeNeed(DSLContext dsl, Record3<String, String, String> start) {
        var n = GRAPHITRON_PATH_NEED;
        dsl.insertInto(n)
            .set(n.GRAPH_NAME, GRAPH).set(n.HOLDER, HOLDER)
            .set(n.START_SOURCE_NAME, start.value1()).set(n.START_SCHEMA, start.value2())
            .set(n.START_TABLE, start.value3())
            .set(n.LANDING_KIND, "TYPE").set(n.LANDING, "Address").set(n.LANDING_TYPE, "Address")
            .set(n.TOUCHED_AT, NOW).execute();
    }

    private static void insertTableNeed(DSLContext dsl, Record3<String, String, String> start,
                                        Record3<String, String, String> landing) {
        var n = GRAPHITRON_PATH_NEED;
        dsl.insertInto(n)
            .set(n.GRAPH_NAME, GRAPH).set(n.HOLDER, HOLDER)
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
                                   String anchor) {
        var p = GRAPHITRON_PATH;
        dsl.insertInto(p)
            .set(p.GRAPH_NAME, GRAPH).set(p.HOLDER, HOLDER)
            .set(p.START_SOURCE_NAME, start.value1()).set(p.START_SCHEMA, start.value2())
            .set(p.START_TABLE, start.value3())
            .set(p.LANDING_KIND, landingKind).set(p.LANDING, landing)
            .set(p.TARGET_SOURCE_NAME, target.value1()).set(p.TARGET_SCHEMA, target.value2())
            .set(p.TARGET_TABLE, target.value3())
            .set(p.ORIGIN, origin).set(p.ANCHOR, anchor)
            .set(p.TOUCHED_AT, NOW).execute();
    }

    private static void insertStep(DSLContext dsl, Record3<String, String, String> start,
                                   String landingKind, String landing,
                                   Record3<String, String, String> from,
                                   Record3<String, String, String> to, Integer elementPlace) {
        var s = GRAPHITRON_PATH_STEP;
        dsl.insertInto(s)
            .set(s.GRAPH_NAME, GRAPH).set(s.HOLDER, HOLDER)
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
