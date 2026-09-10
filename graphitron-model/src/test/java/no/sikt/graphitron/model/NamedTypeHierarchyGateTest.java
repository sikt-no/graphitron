package no.sikt.graphitron.model;

import org.jooq.DSLContext;
import org.jooq.exception.IntegrityConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_IMPLEMENTS_INTERFACE;
import static no.sikt.graphitron.model.test.SeededStore.SEEDED_READING;
import static no.sikt.graphitron.model.test.SeededStore.seedDeclaredType;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.seedTable;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Holds the named-type hierarchy to refusing the states it has no reading of. A named type is one of
 * the specification's six kinds, and two facts hang off kinds rather than off types: a binding to a
 * catalog table, which only an object or an interface can carry, and membership of a polymorphic
 * container, which only a union or an interface can have. Neither restriction was expressible while
 * the dependent keyed the type alone, so both were carried by whichever writer happened to run.
 *
 * <p>The mechanism is one unique and one composite reference, and it is the same one twice. The
 * anchor takes a unique on its key plus its discriminator, which its primary key already implies and
 * which exists so a dependent can reference the pair; each dependent then carries the discriminator
 * itself, references the pair, and checks its own admissible values. The tree arrived at this shape
 * twice without naming it: {@code graphitron_tabletype} carries a redundant unique whose comment says
 * it is "what lets a relation carrying both a type and the table it resolved to reference the pair
 * rather than each half separately", and {@code graphitron_node} keys into
 * {@code graphitron_tabletype} rather than into the type element, so an unbound node is already
 * unwritable.
 *
 * <p>A binding on a union, on a scalar and on an enum is the same missing constraint met three
 * times, and each is worth its own case because a constraint admitting one kind too many would pass
 * a test that only tried the others. Beside them the claim itself is checked, so a row honestly
 * naming a kind that cannot bind is refused where a row lying about one is caught by the reference.
 *
 * <p>The negative cases are only worth what the positive one is worth beside them. A gate that
 * refuses everything would pass all of them, so the legal binding is asserted here rather than left
 * to the rest of the suite, and it is the case to look at first when this class fails as a whole.
 *
 * <p>The last case is a refusal this hierarchy deliberately does not make, recorded here because the
 * plan called for it and measuring said no. {@code graphql_poly_member} was to reference its
 * container's kind on the same mechanism, which would have refused a member claiming
 * {@code UNION} for a type declared an object. The reference was written, run against the corpus,
 * and withdrawn when it refused 103 captures.
 *
 * <p>What it collides with is when capture runs, not what the container name means. Implementing an
 * interface nobody declared is refused, and the refusal is real: graphql-java's assembly reports
 * "The interface type 'Node' is not present when resolving type 'Inventory'" and no such schema
 * builds. But capture reads the parsed registry before assembly and is handed the refusal as a
 * value rather than an exception, so the store's record of a schema that did not build is this row
 * and the {@code graphql_schema_problem} row beside it. Measured on exactly that document, capture
 * completes and both rows are there. A reference from {@code container_kind} would make that
 * capture throw an integrity violation instead, so the store would refuse to record the one schema
 * whose error it exists to explain. What the reference would have caught is a detection over the
 * two relations instead.
 */
class NamedTypeHierarchyGateTest {

    private static final String GRAPH = "hierarchy";
    private static final String CATALOG = "catalog";
    private static final String SCHEMA = "public";
    private static final String TABLE = "film";

    @Test
    @DisplayName("an object may be bound to a table, which is what the refusals below are measured against")
    void anObjectMayCarryABinding() {
        withSeededStore(GRAPH, dsl -> {
            seedDeclaredType(dsl, GRAPH, "Film", "OBJECT");
            seedSource(dsl, CATALOG, "JOOQ_SCHEMA");
            seedTable(dsl, CATALOG, SCHEMA, TABLE);
            assertThatCode(() -> bind(dsl, "Film"))
                .as("the hierarchy admits the kind the binding is a fact about; a gate refusing this "
                    + "one refuses everything and the three below prove nothing")
                .doesNotThrowAnyException();
        });
    }

    @Test
    @DisplayName("a union cannot be bound to a table")
    void aUnionCannotCarryABinding() {
        refusesBindingOn("UNION");
    }

    @Test
    @DisplayName("a scalar cannot be bound to a table")
    void aScalarCannotCarryABinding() {
        refusesBindingOn("SCALAR");
    }

    @Test
    @DisplayName("an enum cannot be bound to a table")
    void anEnumCannotCarryABinding() {
        refusesBindingOn("ENUM");
    }

    @Test
    @DisplayName("a member may name a container nothing declares, which is why it references no kind")
    void aMemberMayNameAContainerNothingDeclares() {
        withSeededStore(GRAPH, dsl -> {
            seedDeclaredType(dsl, GRAPH, "Cash", "OBJECT");
            assertThatCode(() -> dsl.insertInto(GRAPHQL_IMPLEMENTS_INTERFACE)
                    .set(GRAPHQL_IMPLEMENTS_INTERFACE.GRAPH_NAME, GRAPH)
                    .set(GRAPHQL_IMPLEMENTS_INTERFACE.TYPE_NAME, "Cash")
                    .set(GRAPHQL_IMPLEMENTS_INTERFACE.INTERFACE_NAME, "Node")
                    .set(GRAPHQL_IMPLEMENTS_INTERFACE.POSITION, 1)
                    .set(GRAPHQL_IMPLEMENTS_INTERFACE.DECLARATION_LINE, 1)
                    .set(GRAPHQL_IMPLEMENTS_INTERFACE.DECLARATION_COLUMN, 1)
                    .set(GRAPHQL_IMPLEMENTS_INTERFACE.SOURCE_NAME, "seed.graphqls")
                    .set(GRAPHQL_IMPLEMENTS_INTERFACE.SOURCE_LINE, 1)
                    .set(GRAPHQL_IMPLEMENTS_INTERFACE.SOURCE_COLUMN, 1)
                    .set(GRAPHQL_IMPLEMENTS_INTERFACE.TOUCHED_AT, SEEDED_READING)
                    .execute())
                .as("capture runs before assembly and records its refusal rather than throwing, so "
                    + "the transcription of a schema that does not build has to be writable; this "
                    + "row and the graphql_schema_problem row beside it are that record")
                .doesNotThrowAnyException();
        });
    }

    @Test
    @DisplayName("a binding cannot claim a kind that carries no binding, however the type is declared")
    void aBindingCannotClaimAKindThatCannotBind() {
        withSeededStore(GRAPH, dsl -> {
            seedDeclaredType(dsl, GRAPH, "Payment", "UNION");
            seedSource(dsl, CATALOG, "JOOQ_SCHEMA");
            seedTable(dsl, CATALOG, SCHEMA, TABLE);
            assertThatThrownBy(() -> bind(dsl, "Payment", "UNION"))
                .as("the other half of the mechanism: the reference makes the claim agree with the "
                    + "anchor, and the check is what says which claims are admissible at all, so a "
                    + "row honestly naming UNION is refused where the one lying about it is caught "
                    + "by the reference")
                .isInstanceOf(IntegrityConstraintViolationException.class);
        });
    }

    /**
     * Seeds a type of {@code kind} and asserts the binding is refused. One helper for three cases so
     * the kind under test is the only thing that differs between them, which is what makes a
     * constraint admitting one kind too many show up as one failing case rather than as none.
     */
    private static void refusesBindingOn(String kind) {
        withSeededStore(GRAPH, dsl -> {
            seedDeclaredType(dsl, GRAPH, "Subject", kind);
            seedSource(dsl, CATALOG, "JOOQ_SCHEMA");
            seedTable(dsl, CATALOG, SCHEMA, TABLE);
            assertThatThrownBy(() -> bind(dsl, "Subject"))
                .as("a table binding is a fact about an object or an interface, and %s is neither; "
                    + "the reference carries the kind so the store refuses this rather than a "
                    + "downstream reader having to know it cannot happen", kind)
                .isInstanceOf(IntegrityConstraintViolationException.class);
        });
    }

    /**
     * A binding claiming {@code OBJECT}, which is the shape a writer that did not consult the kind
     * would produce. On an object it is true and the row lands; on any other kind the claim is false
     * and the reference is what catches it, which is the half of the mechanism the check cannot do.
     */
    private static int bind(DSLContext dsl, String typeName) {
        return bind(dsl, typeName, "OBJECT");
    }

    private static int bind(DSLContext dsl, String typeName, String claimedKind) {
        return dsl.insertInto(GRAPHITRON_TABLETYPE)
            .set(GRAPHITRON_TABLETYPE.GRAPH_NAME, GRAPH)
            .set(GRAPHITRON_TABLETYPE.TYPE_NAME, typeName)
            .set(GRAPHITRON_TABLETYPE.NAMED_TYPE_KIND, claimedKind)
            .set(GRAPHITRON_TABLETYPE.TABLE_SOURCE_NAME, CATALOG)
            .set(GRAPHITRON_TABLETYPE.TABLE_SCHEMA, SCHEMA)
            .set(GRAPHITRON_TABLETYPE.TABLE_NAME, TABLE)
            .execute();
    }
}
