package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.util.NodeIdEncoder;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R322 execution-tier coverage: the runtime value-agreement check that fires when more than one writer
 * lands on a single row column, on both materialization paths.
 *
 * <p>The {@code @service} jOOQ-record path uses the {@code agreeStorageBin} fixture: a
 * {@link no.sikt.graphitron.rewrite.test.jooq.tables.records.StorageBinRecord} {@code @service} param whose
 * input carries two writers on {@code bin_id} (the same-table identity {@code @nodeId(typeName:
 * "StorageBin")} decode and a plain {@code @field}). The generated {@code createStorageBinRecord} helper
 * calls {@code NodeIdEncoder.requireColumnAgreement} before loading.
 *
 * <p>The {@code @mutation} INSERT DML path uses the {@code insertEndorsementOverlap} fixture: a
 * {@code film_endorsement} {@code @table} INSERT input where {@code endorsed_film} is written by both a
 * plain {@code @field} and a {@code @nodeId(typeName: "Film")} FK reference. The structural dedup collapses
 * the duplicate column (no Postgres "column specified more than once" crash) and the same agreement check
 * fires on the coalesced cell.
 *
 * <p>Agreement is defined by the destination column's coercion, so across both fixtures the matrix the spec
 * calls out is covered: agreement on a format-variant wire value ({@code "01"} coerces to the decoded
 * {@code 1}); disagreement (distinct values) throws and nothing is written; agreeing writers materialize /
 * insert the agreed value; and the presence guard (an omitted nullable writer leaves the lone decode and
 * does not throw).
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class NodeIdValueAgreementExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;

    @BeforeAll
    static void startDatabase() {
        var localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            var user = System.getProperty("test.db.username", "postgres");
            var pass = System.getProperty("test.db.password", "postgres");
            dsl = DSL.using(localUrl, user, pass);
        } else {
            postgres = new PostgreSQLContainer("postgres:18-alpine").withInitScript("init.sql");
            postgres.start();
            dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
        GraphQLSchema schema = Graphitron.buildSchema(b -> {});
        graphql = GraphQL.newGraphQL(schema).build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    private Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).as("graphql errors: " + result.getErrors()).isEmpty();
        return result.getData();
    }

    private ExecutionResult executeRaw(String query) {
        var input = Graphitron.newExecutionInput(dsl, "test-user").query(query).build();
        return graphql.execute(input);
    }

    @Test
    void serviceRecord_twoWritersAgreeOnFormatVariantWireValue_buildsRecord() {
        // The plain @field supplies "01"; the same-table identity decodes StorageBin(1) to "1". Both
        // target bin_id (integer). String.valueOf("01") != "1", but bin_id's DataType.convert collapses
        // both to 1 — the coerced-comparison choice (D3) is what keeps this from false-throwing.
        String binId1 = NodeIdEncoder.encode("StorageBin", 1);
        Map<String, Object> data = execute(
            "mutation { agreeStorageBin(in: {binId: \"" + binId1 + "\", binIdText: \"01\"}) }");
        assertThat(data).extractingByKey("agreeStorageBin").isEqualTo("bin_id=1");
    }

    @Test
    void serviceRecord_twoWritersDisagree_throwsAndServiceNeverRuns() {
        // The decode resolves bin_id to 1; the plain @field supplies "2". They coerce to different values,
        // so requireColumnAgreement throws inside createStorageBinRecord before the service body — the
        // mutation surfaces an error and yields no value (the silent last-write-wins drop is what R322 closes).
        String binId1 = NodeIdEncoder.encode("StorageBin", 1);
        ExecutionResult result = executeRaw(
            "mutation { agreeStorageBin(in: {binId: \"" + binId1 + "\", binIdText: \"2\"}) }");
        assertThat(result.getErrors())
            .as("disagreeing writers on bin_id must surface a value-agreement error")
            .isNotEmpty();
        Map<String, Object> data = result.getData();
        assertThat(data.get("agreeStorageBin"))
            .as("the mutation does not succeed when the two writers disagree")
            .isNull();
    }

    @Test
    void serviceRecord_omittedNullableWriter_leavesLoneDecode_doesNotThrow() {
        // Only the decode is present (binIdText omitted). The presence guard means a single present writer
        // is never a conflict, even though an absent writer's "value" would differ — no agreement check fires.
        String binId7 = NodeIdEncoder.encode("StorageBin", 7);
        Map<String, Object> data = execute(
            "mutation { agreeStorageBin(in: {binId: \"" + binId7 + "\"}) }");
        assertThat(data).extractingByKey("agreeStorageBin").isEqualTo("bin_id=7");
    }

    @Test
    void mutationInsert_twoWritersAgree_dedupsColumnAndInsertsAgreedValue() {
        // Both filmRef (@nodeId FK reference -> endorsed_film) and endorsedFilm (@field) target
        // endorsed_film. The structural dedup emits endorsed_film once (no "column specified more than
        // once" crash) and the agreement passes (both resolve to 1), so the row inserts with endorsed_film=1.
        String note = "R322-" + UUID.randomUUID();
        String film1 = NodeIdEncoder.encode("Film", 1);
        try {
            Map<String, Object> data = execute(
                "mutation { insertEndorsementOverlap(in: {filmRef: \"" + film1 + "\", endorsedFilm: 1, note: \""
                + note + "\"}) { endorsedFilm } }");
            Map<String, Object> row = (Map<String, Object>) data.get("insertEndorsementOverlap");
            assertThat(row).extractingByKey("endorsedFilm").isEqualTo(1);
        } finally {
            dsl.deleteFrom(DSL.table("film_endorsement")).where(DSL.field("note").eq(note)).execute();
        }
    }

    @Test
    void mutationInsert_twoWritersDisagree_throwsAndInsertsNothing() {
        // filmRef resolves endorsed_film to 1; endorsedFilm supplies 2. requireColumnAgreement throws on the
        // coalesced cell before the INSERT runs, so the statement errors and no row is written.
        String note = "R322-" + UUID.randomUUID();
        String film1 = NodeIdEncoder.encode("Film", 1);
        try {
            ExecutionResult result = executeRaw(
                "mutation { insertEndorsementOverlap(in: {filmRef: \"" + film1 + "\", endorsedFilm: 2, note: \""
                + note + "\"}) { endorsedFilm } }");
            assertThat(result.getErrors())
                .as("disagreeing writers on endorsed_film must surface a value-agreement error")
                .isNotEmpty();
            Map<String, Object> data = result.getData();
            assertThat(data.get("insertEndorsementOverlap")).isNull();
            assertThat(dsl.fetchCount(DSL.table("film_endorsement"), DSL.field("note").eq(note)))
                .as("nothing is inserted when the writers disagree").isZero();
        } finally {
            dsl.deleteFrom(DSL.table("film_endorsement")).where(DSL.field("note").eq(note)).execute();
        }
    }

    @Test
    void mutationInsert_omittedNullableWriter_leavesLoneDecode_insertsDecodedValue() {
        // endorsedFilm omitted, only the filmRef decode present. The presence guard means no agreement
        // check fires, and the decoded FK value (1) is what lands on endorsed_film.
        String note = "R322-" + UUID.randomUUID();
        String film1 = NodeIdEncoder.encode("Film", 1);
        try {
            Map<String, Object> data = execute(
                "mutation { insertEndorsementOverlap(in: {filmRef: \"" + film1 + "\", note: \""
                + note + "\"}) { endorsedFilm } }");
            Map<String, Object> row = (Map<String, Object>) data.get("insertEndorsementOverlap");
            assertThat(row).extractingByKey("endorsedFilm").isEqualTo(1);
        } finally {
            dsl.deleteFrom(DSL.table("film_endorsement")).where(DSL.field("note").eq(note)).execute();
        }
    }
}
