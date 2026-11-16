package no.sikt.graphitron.model.read;

import no.sikt.graphitron.model.test.FactStores;
import no.sikt.graphitron.model.test.SeededStore;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;

import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a stamp is spelled, now that {@code store_source.stamp} holds two schemes.
 *
 * <p>The tag is what keeps them apart, and the case that matters is the reader that computes.
 * {@link SourceStamp#recordedMatches} is the only one: it hashes the caller's bytes and compares
 * the result against whatever the column holds. Every stamp it can legitimately meet is a
 * {@code sha256:} one, schema files being the only sources it is ever asked about, but nothing in
 * its signature says so. What is pinned here is that meeting the other scheme is answered false
 * rather than answered wrongly, since false is the direction that costs a re-read and true would
 * cost a stored position applied to text nobody checked.
 */
class SourceStampTest {

    /** A store for the class: each case seeds a source of its own, so the cases share it. */
    @RegisterExtension
    static final FactStores.ClassStore STORE = FactStores.perClass();

    /** The case's own source, named for its method, so the rows it seeds and reads are its own. */
    private final String source;

    SourceStampTest(TestInfo info) {
        source = "/tmp/" + info.getTestMethod().orElseThrow().getName() + ".graphqls";
    }

    @Test
    @DisplayName("a stamp says which scheme produced it")
    void aStampCarriesItsScheme() {
        assertThat(SourceStamp.of("content".getBytes(StandardCharsets.UTF_8)))
            .startsWith("sha256:");
        assertThat(SourceStamp.ofSha1("A".repeat(40)))
            .as("normalised to lower case, so one digest has one spelling")
            .isEqualTo("sha1:" + "a".repeat(40));
    }

    @Test
    @DisplayName("a value that is not a hex digest is no identity at all")
    void anUnparseableSha1IsNone() {
        assertThat(SourceStamp.ofSha1(null)).isNull();
        assertThat(SourceStamp.ofSha1("")).isNull();
        assertThat(SourceStamp.ofSha1("z".repeat(40))).as("hex, not merely 40 characters").isNull();
        assertThat(SourceStamp.ofSha1("a".repeat(39))).as("a truncated digest is not one").isNull();
        assertThat(SourceStamp.ofSha1("a".repeat(40) + " library.jar"))
            .as("the caller trims; this method takes the digest and nothing beside it")
            .isNull();
    }

    @Test
    @DisplayName("the reader that computes matches its own scheme")
    void aRecordedSha256Matches() {
        byte[] content = "type Query { a: String }".getBytes(StandardCharsets.UTF_8);
        DSLContext dsl = STORE.handle().dsl();
        SeededStore.seedSource(dsl, source, "SCHEMA_FILE");
        dsl.update(STORE_SOURCE)
            .set(STORE_SOURCE.STAMP, SourceStamp.of(content))
            .where(STORE_SOURCE.SOURCE_NAME.eq(source))
            .execute();

        assertThat(SourceStamp.recordedMatches(dsl, source, content)).isTrue();
    }

    /**
     * The guard the scheme rests on. A supplied identity cannot be recomputed from bytes, so a
     * reader that computes must never read one as a match; the tag makes that structural rather
     * than a convention, string equality across two schemes being false by construction.
     */
    @Test
    @DisplayName("a supplied identity is never read as a match by the reader that computes")
    void aRecordedSha1NeverMatches() {
        byte[] content = "type Query { a: String }".getBytes(StandardCharsets.UTF_8);
        DSLContext dsl = STORE.handle().dsl();
        SeededStore.seedSource(dsl, source, "SCHEMA_FILE");
        dsl.update(STORE_SOURCE)
            .set(STORE_SOURCE.STAMP, SourceStamp.ofSha1("c".repeat(40)))
            .where(STORE_SOURCE.SOURCE_NAME.eq(source))
            .execute();

        assertThat(SourceStamp.recordedMatches(dsl, source, content)).isFalse();
    }

    /**
     * The migration window, stated as a case rather than left to a plan. An untagged value is what
     * a store written before the scheme existed holds, and it has to read as "not this content"
     * until the next capture rewrites the row: the alternative would be inferring the scheme from
     * a length, which is the trap the tag exists to close.
     */
    @Test
    @DisplayName("an untagged value from an older store does not match")
    void anUntaggedRecordedValueDoesNotMatch() {
        byte[] content = "type Query { a: String }".getBytes(StandardCharsets.UTF_8);
        String untagged = SourceStamp.of(content).substring("sha256:".length());
        DSLContext dsl = STORE.handle().dsl();
        SeededStore.seedSource(dsl, source, "SCHEMA_FILE");
        dsl.update(STORE_SOURCE)
            .set(STORE_SOURCE.STAMP, untagged)
            .where(STORE_SOURCE.SOURCE_NAME.eq(source))
            .execute();

        assertThat(SourceStamp.recordedMatches(dsl, source, content)).isFalse();
    }
}
