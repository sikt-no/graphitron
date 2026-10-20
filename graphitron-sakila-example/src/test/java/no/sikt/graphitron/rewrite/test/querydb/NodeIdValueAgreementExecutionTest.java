package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionResult;
import graphql.GraphQL;
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
 * Execution-tier coverage for the runtime value-agreement check that fires when more than one writer
 * lands on a single row column, on both materialization paths.
 *
 * <p>The {@code @service} jOOQ-record path uses the {@code agreeStorageBin} fixture: a
 * {@link no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.StorageBinRecord} {@code @service} param whose
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
 *
 * <p>On UPDATE, a reference sent as {@code null} means "no reference": it carries no value for a SET
 * column it shares, so the column takes the value of the writers that carry one, and is written
 * {@code NULL} only when none does. A plain field's {@code null} is a value for its column and still
 * conflicts with a reference's value. The {@code film_bookmark} cases cover a nullable shared column,
 * on the single-row arm and the payload arm.
 *
 * <p>On INSERT, a nullable {@code @nodeId} sent as an explicit {@code null} is a missing reference and contributes
 * nothing to a column it shares: on {@code insertEndorsementOverlap} the plain writer's value lands,
 * while a plain writer's own explicit {@code null} still names the column and still conflicts. The
 * {@code describeEmailReply} fixture carries the same rule onto the {@code @service} record path, over
 * two references sharing {@code email.mailbox_id}. (The lone explicit-null case, where nothing carries
 * a value for the shared column, needs a nullable column and lives on {@code film_bookmark} in
 * {@code DmlBulkMutationsExecutionTest}.)
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
        graphql = Graphitron.newGraphQL().build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    private Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).as("graphql errors: " + result.getErrors()).isEmpty();
        return result.getData();
    }

    private ExecutionResult executeRaw(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
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
        // mutation surfaces an error and yields no value (closing the silent last-write-wins drop).
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

    /** A StorageBin id carrying 2^32 + 1, which a lenient coercion of the int4 bin_id wraps to bin 1. */
    private static final String WRAPPED_BIN_1 = NodeIdEncoder.encode("StorageBin", "4294967297");

    @Test
    void serviceRecord_keyValueWrapsItsColumn_failsTheDecodeAndServiceNeverRuns() {
        ExecutionResult result = executeRaw(
            "mutation { agreeStorageBin(in: {binId: \"" + WRAPPED_BIN_1 + "\"}) }");
        assertThat(result.getErrors())
            .as("an id whose key is not a value of bin_id is the rail's decode failure")
            .isNotEmpty();
        Map<String, Object> data = result.getData();
        assertThat(data.get("agreeStorageBin"))
            .as("the service never runs on the wrapped key")
            .isNull();
    }

    @Test
    void serviceRecord_keyValueWrapsItsColumn_cannotAgreeWithAPlainWriterOfTheWrappedRow() {
        // Leniently decoded, the wrapped key would be bin 1 and agree with the plain "1".
        ExecutionResult result = executeRaw(
            "mutation { agreeStorageBin(in: {binId: \"" + WRAPPED_BIN_1 + "\", binIdText: \"1\"}) }");
        assertThat(result.getErrors()).isNotEmpty();
        Map<String, Object> data = result.getData();
        assertThat(data.get("agreeStorageBin")).isNull();
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
    void serviceRecord_explicitNullSelfFkReference_keepsMailboxRefsMailboxAndNullsReplyPointer() {
        // inReplyTo is the last decode to load, so without the load-phase guard its explicit null would
        // overwrite the mailbox_id mailboxRef already set. It drops out of agreement instead, and only
        // in_reply_to_no, the column it alone writes, is NULL.
        String mailbox5 = NodeIdEncoder.encode("Mailbox", 5);
        Map<String, Object> data = execute(
            "mutation { describeEmailReply(in: {mailboxRef: \"" + mailbox5 + "\", messageNo: 1,"
            + " inReplyTo: null}) }");
        assertThat(data).extractingByKey("describeEmailReply")
            .isEqualTo("mailbox_id=5, in_reply_to_no=null");
    }

    @Test
    void serviceRecord_agreeingSelfFkReference_loadsBothColumns() {
        String mailbox5 = NodeIdEncoder.encode("Mailbox", 5);
        String parent = NodeIdEncoder.encode("Email", 5, 1);
        Map<String, Object> data = execute(
            "mutation { describeEmailReply(in: {mailboxRef: \"" + mailbox5 + "\", messageNo: 2,"
            + " inReplyTo: \"" + parent + "\"}) }");
        assertThat(data).extractingByKey("describeEmailReply")
            .isEqualTo("mailbox_id=5, in_reply_to_no=1");
    }

    @Test
    void serviceRecord_disagreeingSelfFkReference_stillThrows() {
        // A parent in mailbox 9 against mailboxRef's mailbox 5: two values for one column still conflict.
        String mailbox5 = NodeIdEncoder.encode("Mailbox", 5);
        String parentInOtherMailbox = NodeIdEncoder.encode("Email", 9, 1);
        ExecutionResult result = executeRaw(
            "mutation { describeEmailReply(in: {mailboxRef: \"" + mailbox5 + "\", messageNo: 2,"
            + " inReplyTo: \"" + parentInOtherMailbox + "\"}) }");
        assertThat(result.getErrors())
            .as("disagreeing references on mailbox_id must surface a value-agreement error")
            .isNotEmpty();
        Map<String, Object> data = result.getData();
        assertThat(data.get("describeEmailReply")).isNull();
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

    @Test
    void mutationInsert_explicitNullReferenceBesidePlainValue_insertsPlainValue() {
        // filmRef sent as an explicit null is a missing reference: it contributes nothing to
        // endorsed_film, so the plain writer's value is the only one there and it lands.
        String note = "NULLREF-" + UUID.randomUUID();
        try {
            Map<String, Object> data = execute(
                "mutation { insertEndorsementOverlap(in: {filmRef: null, endorsedFilm: 1, note: \""
                + note + "\"}) { endorsedFilm } }");
            Map<String, Object> row = (Map<String, Object>) data.get("insertEndorsementOverlap");
            assertThat(row).extractingByKey("endorsedFilm").isEqualTo(1);
        } finally {
            dsl.deleteFrom(DSL.table("film_endorsement")).where(DSL.field("note").eq(note)).execute();
        }
    }

    @Test
    void mutationInsert_explicitNullPlainBesideReferenceValue_stillConflicts() {
        // A plain @field names the column itself, so its explicit null is a NULL for endorsed_film and
        // conflicts with the reference's decoded 1; nothing is inserted.
        String note = "NULLPLAIN-" + UUID.randomUUID();
        String film1 = NodeIdEncoder.encode("Film", 1);
        try {
            ExecutionResult result = executeRaw(
                "mutation { insertEndorsementOverlap(in: {filmRef: \"" + film1 + "\", endorsedFilm: null,"
                + " note: \"" + note + "\"}) { endorsedFilm } }");
            assertThat(result.getErrors())
                .as("a plain field's explicit null still disagrees with a decoded value")
                .isNotEmpty();
            assertThat(dsl.fetchCount(DSL.table("film_endorsement"), DSL.field("note").eq(note)))
                .as("nothing is inserted when the writers disagree").isZero();
        } finally {
            dsl.deleteFrom(DSL.table("film_endorsement")).where(DSL.field("note").eq(note)).execute();
        }
    }

    @Test
    void mutationUpdate_twoSetWritersAgree_updatesAgreedValue() {
        // Single-row UPDATE: endorsed_film is SET by both endorsedFilm (@field) and filmRef (@nodeId FK
        // reference). The single-row SET Map.put would silently last-write-wins; the agreement preamble
        // lets the agreeing writers (both -> 2) update endorsed_film to 2.
        String note = "R322-" + UUID.randomUUID();
        int id = seedEndorsement(1, note);
        String film2 = NodeIdEncoder.encode("Film", 2);
        try {
            Map<String, Object> data = execute(
                "mutation { updateEndorsementOverlap(in: {endorsementId: " + id + ", filmRef: \"" + film2
                + "\", endorsedFilm: 2}) { endorsedFilm } }");
            Map<String, Object> row = (Map<String, Object>) data.get("updateEndorsementOverlap");
            assertThat(row).extractingByKey("endorsedFilm").isEqualTo(2);
        } finally {
            dsl.deleteFrom(DSL.table("film_endorsement")).where(DSL.field("note").eq(note)).execute();
        }
    }

    @Test
    void mutationUpdate_twoSetWritersDisagree_throwsAndLeavesRowUnchanged() {
        // filmRef resolves endorsed_film to 2; endorsedFilm supplies 3. The agreement preamble throws
        // before .set(sets) runs, so the row is left at its seeded endorsed_film=1.
        String note = "R322-" + UUID.randomUUID();
        int id = seedEndorsement(1, note);
        String film2 = NodeIdEncoder.encode("Film", 2);
        try {
            ExecutionResult result = executeRaw(
                "mutation { updateEndorsementOverlap(in: {endorsementId: " + id + ", filmRef: \"" + film2
                + "\", endorsedFilm: 3}) { endorsedFilm } }");
            assertThat(result.getErrors())
                .as("disagreeing SET writers on endorsed_film must surface a value-agreement error")
                .isNotEmpty();
            Integer persisted = dsl.select(DSL.field("endorsed_film", Integer.class))
                .from(DSL.table("film_endorsement"))
                .where(DSL.field("endorsement_id", Integer.class).eq(id))
                .fetchOne().value1();
            assertThat(persisted).as("the row is unchanged when the writers disagree").isEqualTo(1);
        } finally {
            dsl.deleteFrom(DSL.table("film_endorsement")).where(DSL.field("note").eq(note)).execute();
        }
    }

    @Test
    void mutationUpdate_setReferenceWhoseKeyWrapsItsColumn_writesNothing() {
        // filmRef carries 2^32 + 2, which a lenient coercion of the int4 film_id wraps to film 2. The
        // decode refuses it, so the SET never runs and the row keeps its seeded endorsed_film=1.
        String note = "wrapped-ref-" + UUID.randomUUID();
        int id = seedEndorsement(1, note);
        String wrapped = NodeIdEncoder.encode("Film", "4294967298");
        try {
            ExecutionResult result = executeRaw(
                "mutation { updateEndorsementOverlap(in: {endorsementId: " + id + ", filmRef: \"" + wrapped
                + "\"}) { endorsedFilm } }");
            assertThat(result.getErrors())
                .as("an id whose key is not a value of film_id is the rail's decode failure")
                .isNotEmpty();
            Integer persisted = dsl.select(DSL.field("endorsed_film", Integer.class))
                .from(DSL.table("film_endorsement"))
                .where(DSL.field("endorsement_id", Integer.class).eq(id))
                .fetchOne().value1();
            assertThat(persisted).as("nothing is written for an id that does not decode").isEqualTo(1);
        } finally {
            dsl.deleteFrom(DSL.table("film_endorsement")).where(DSL.field("note").eq(note)).execute();
        }
    }

    @Test
    void mutationUpdate_clearedReferenceBesideValue_updatesTheValue() {
        // filmRef is sent as null: no reference, so it carries no value for endorsed_film and takes no
        // part in the agreement check. endorsedFilm's 2 is the column's value.
        String note = "R1042-" + UUID.randomUUID();
        int id = seedEndorsement(1, note);
        try {
            Map<String, Object> data = execute(
                "mutation { updateEndorsementOverlap(in: {endorsementId: " + id
                + ", filmRef: null, endorsedFilm: 2}) { endorsedFilm } }");
            Map<String, Object> row = (Map<String, Object>) data.get("updateEndorsementOverlap");
            assertThat(row).extractingByKey("endorsedFilm").isEqualTo(2);
            assertThat(endorsedFilmOf(id)).isEqualTo(2);
        } finally {
            dsl.deleteFrom(DSL.table("film_endorsement")).where(DSL.field("note").eq(note)).execute();
        }
    }

    @Test
    void mutationUpdate_plainNullBesideReferenceValue_throwsAndLeavesRowUnchanged() {
        // A plain field names its column, so its null is a value for endorsed_film and conflicts with
        // the reference's decoded 3. Only a reference's null means "no value".
        String note = "R1042-" + UUID.randomUUID();
        int id = seedEndorsement(1, note);
        String film3 = NodeIdEncoder.encode("Film", 3);
        try {
            ExecutionResult result = executeRaw(
                "mutation { updateEndorsementOverlap(in: {endorsementId: " + id + ", filmRef: \"" + film3
                + "\", endorsedFilm: null}) { endorsedFilm } }");
            assertThat(result.getErrors())
                .as("a plain null against a reference value must surface a value-agreement error")
                .isNotEmpty();
            assertThat(endorsedFilmOf(id)).as("the row is unchanged when the writers disagree").isEqualTo(1);
        } finally {
            dsl.deleteFrom(DSL.table("film_endorsement")).where(DSL.field("note").eq(note)).execute();
        }
    }

    // film_bookmark.bookmarked_film is nullable, so a cleared reference alone can land a row there.
    // In UpdateBookmarkOverlapInput the cleared writer filmRef comes after the plain bookmarkedFilm,
    // the order in which a cleared writer's NULL could overwrite the agreed value.

    @Test
    void mutationUpdate_bookmarkValueThenClearedReference_updatesTheValue() {
        String note = "R1042-" + UUID.randomUUID();
        int id = seedBookmark(5, note);
        try {
            Map<String, Object> data = execute(
                "mutation { updateBookmarkOverlap(in: {bookmarkId: " + id
                + ", bookmarkedFilm: 2, filmRef: null}) { bookmarkedFilm } }");
            Map<String, Object> row = (Map<String, Object>) data.get("updateBookmarkOverlap");
            assertThat(row).extractingByKey("bookmarkedFilm").isEqualTo(2);
            assertThat(bookmarkedFilmOf(id)).isEqualTo(2);
        } finally {
            deleteBookmarks(note);
        }
    }

    @Test
    void mutationUpdate_bookmarkClearedReferenceAlone_writesNull() {
        // The only present writer carries no value, so the column is written NULL.
        String note = "R1042-" + UUID.randomUUID();
        int id = seedBookmark(5, note);
        try {
            Map<String, Object> data = execute(
                "mutation { updateBookmarkOverlap(in: {bookmarkId: " + id
                + ", filmRef: null}) { bookmarkedFilm } }");
            Map<String, Object> row = (Map<String, Object>) data.get("updateBookmarkOverlap");
            assertThat(row).containsEntry("bookmarkedFilm", null);
            assertThat(bookmarkedFilmOf(id)).isNull();
        } finally {
            deleteBookmarks(note);
        }
    }

    @Test
    void mutationUpdate_bookmarkWritersAgree_updatesAgreedValue() {
        String note = "R1042-" + UUID.randomUUID();
        int id = seedBookmark(5, note);
        String film3 = NodeIdEncoder.encode("Film", 3);
        try {
            execute(
                "mutation { updateBookmarkOverlap(in: {bookmarkId: " + id
                + ", bookmarkedFilm: 3, filmRef: \"" + film3 + "\"}) { bookmarkedFilm } }");
            assertThat(bookmarkedFilmOf(id)).isEqualTo(3);
        } finally {
            deleteBookmarks(note);
        }
    }

    @Test
    void mutationUpdatePayload_bookmarkValueThenClearedReference_updatesTheValue() {
        // The payload arm runs no within-SET agreement preamble, so only the SET emission's own guard
        // keeps the cleared filmRef from putting NULL over bookmarkedFilm's 2.
        String note = "R1042-" + UUID.randomUUID();
        int id = seedBookmark(5, note);
        try {
            Map<String, Object> data = execute(
                "mutation { updateBookmarkOverlapPayload(in: {bookmarkId: " + id
                + ", bookmarkedFilm: 2, filmRef: null}) { bookmark { bookmarkedFilm } } }");
            Map<String, Object> payload = (Map<String, Object>) data.get("updateBookmarkOverlapPayload");
            assertThat((Map<String, Object>) payload.get("bookmark")).containsEntry("bookmarkedFilm", 2);
            assertThat(bookmarkedFilmOf(id)).isEqualTo(2);
        } finally {
            deleteBookmarks(note);
        }
    }

    private Integer endorsedFilmOf(int id) {
        return dsl.select(DSL.field("endorsed_film", Integer.class))
            .from(DSL.table("film_endorsement"))
            .where(DSL.field("endorsement_id", Integer.class).eq(id))
            .fetchOne().value1();
    }

    private int seedBookmark(int bookmarkedFilm, String note) {
        return dsl.insertInto(DSL.table("film_bookmark"))
            .set(DSL.field("bookmarked_film"), bookmarkedFilm)
            .set(DSL.field("note"), note)
            .returningResult(DSL.field("bookmark_id", Integer.class))
            .fetchOne().value1();
    }

    private Integer bookmarkedFilmOf(int id) {
        return dsl.select(DSL.field("bookmarked_film", Integer.class))
            .from(DSL.table("film_bookmark"))
            .where(DSL.field("bookmark_id", Integer.class).eq(id))
            .fetchOne().value1();
    }

    private void deleteBookmarks(String note) {
        dsl.deleteFrom(DSL.table("film_bookmark")).where(DSL.field("note").eq(note)).execute();
    }

    private int seedEndorsement(int endorsedFilm, String note) {
        return dsl.insertInto(DSL.table("film_endorsement"))
            .set(DSL.field("endorsed_film"), endorsedFilm)
            .set(DSL.field("note"), note)
            .returningResult(DSL.field("endorsement_id", Integer.class))
            .fetchOne().value1();
    }
}
