package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.UniqueKey;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assumption the {@code @reference} fan-out rule rests on where SQL:2011 application-time
 * periods are in play: a key declared {@code WITHOUT OVERLAPS} must not clear a hop.
 *
 * <p>{@code PRIMARY KEY (a, b, valid_at WITHOUT OVERLAPS)} guarantees one row per
 * <em>instant</em>, not one row per {@code (a, b)}, so a path entering and leaving such a table on
 * {@code a} and {@code b} alone still fans out. The rule's predicate is
 * {@code columns(constraint)} inside the columns the two hops bind, and the period column is bound
 * by no hop: graphitron binds columns only through foreign-key equality and has no range or
 * overlap join vocabulary. So the predicate is false and the rule fires, which is the direction a
 * warning should fail in. That is an argument, and this is the fixture that holds it to a
 * database.
 *
 * <p>Neither jOOQ nor the catalog capture can see the period concept: {@code org.jooq.Key} and
 * {@code org.jooq.UniqueKey} carry no period, and {@code sql_constraint}'s type is closed over
 * {@code PRIMARY KEY}, {@code UNIQUE} and {@code FOREIGN KEY}. Both plausible behaviours land
 * conservatively, and this case asserts the disjunction rather than picking one: either the key is
 * reported with all its columns, the period column among them, so it does not cover; or it is not
 * reported at all and the table has no covering key. The hole opens only if a reader ever sees the
 * key with its period column stripped, which is what would make it look like a plain
 * {@code (a, b)} key, and that is the case this test exists to catch.
 *
 * <p>Requires PostgreSQL 18, the first release that accepts the syntax. Under the local-database
 * profile, whose server is older, the case declines rather than passing quietly.
 */
@ExecutionTier
class TemporalKeyDoesNotCoverExecutionTest {

    private static final String TABLE = "temporal_membership";

    /** The columns a two-hop path through this table would bind, the period column excluded. */
    private static final Set<String> BOUND_BY_THE_HOPS = Set.of("party_id", "team_id");

    static PostgreSQLContainer postgres;
    static DSLContext dsl;

    @BeforeAll
    static void startDatabase() {
        var localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            var user = System.getProperty("test.db.username", "postgres");
            var pass = System.getProperty("test.db.password", "postgres");
            dsl = DSL.using(localUrl, user, pass);
        } else {
            postgres = new PostgreSQLContainer("postgres:18-alpine");
            postgres.start();
            dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
        Assumptions.assumeTrue(serverVersionNum() >= 180_000,
            "WITHOUT OVERLAPS needs PostgreSQL 18; this server cannot express the fixture");
        // A WITHOUT OVERLAPS primary key is a GiST exclusion constraint, and the scalar columns
        // beside the range have no GiST operator class without this. Stock PostgreSQL 18 ships the
        // extension and does not enable it, so a fresh container fails the CREATE TABLE below
        // while a developer's own database, where something enabled it once, does not.
        dsl.execute("CREATE EXTENSION IF NOT EXISTS btree_gist");
        dsl.execute("DROP TABLE IF EXISTS " + TABLE);
        dsl.execute("""
            CREATE TABLE %s (
                party_id int       NOT NULL,
                team_id  int       NOT NULL,
                valid_at daterange NOT NULL,
                PRIMARY KEY (party_id, team_id, valid_at WITHOUT OVERLAPS)
            )""".formatted(TABLE));
    }

    @AfterAll
    static void stopDatabase() {
        if (dsl != null) dsl.execute("DROP TABLE IF EXISTS " + TABLE);
        if (postgres != null) postgres.stop();
    }

    /**
     * Every uniqueness constraint the catalog reports on the temporal table has at least one column
     * outside the pair a path would bind, so none of them covers and the hop keeps fanning out. A
     * table reported with no key at all satisfies this the same way, which is the conservative half
     * of the disjunction.
     */
    @Test
    void aTemporalKeyDoesNotCoverThePairTheHopsBind() {
        var keys = dsl.meta().getTables(TABLE).stream()
            .flatMap(table -> table.getKeys().stream())
            .toList();

        assertThat(keys).allSatisfy(key ->
            assertThat(BOUND_BY_THE_HOPS.containsAll(columnsOf(key)))
                .as("a key whose columns all sit inside the bound pair would clear this hop, and a"
                    + " period key guarantees one row per instant rather than one per pair: %s",
                    columnsOf(key))
                .isFalse());
    }

    /**
     * The floor against a vacuous pass from the other side: the fixture really is a temporal table,
     * so the case above is about the shape it claims to be about rather than about a table that
     * failed to be created.
     */
    @Test
    void theFixtureReallyDeclaresAPeriodKey() {
        assertThat(scalar(
            "SELECT count(*) FROM pg_constraint WHERE conrelid = '" + TABLE
                + "'::regclass AND conperiod"))
            .as("the primary key is declared WITHOUT OVERLAPS")
            .isEqualTo(1);
    }

    private static List<String> columnsOf(UniqueKey<?> key) {
        return Arrays.stream(key.getFieldsArray()).map(Field::getName).toList();
    }

    private static int serverVersionNum() {
        return scalar("SELECT current_setting('server_version_num')::int");
    }

    private static int scalar(String sql) {
        return ((Number) dsl.fetchSingle(sql).get(0)).intValue();
    }
}
