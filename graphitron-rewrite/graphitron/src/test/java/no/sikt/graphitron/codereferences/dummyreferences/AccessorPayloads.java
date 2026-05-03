package no.sikt.graphitron.codereferences.dummyreferences;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.LanguageRecord;

import java.util.List;
import java.util.Set;

/**
 * Test fixtures for the {@code R60} accessor-derived BatchKey classifier path. Each public
 * type exposes a typed zero-arg accessor whose return type is a concrete jOOQ
 * {@link org.jooq.TableRecord} subtype (single, list, or set), or matches one of the rejection
 * shapes the classifier tests pin down.
 *
 * <p>Co-located with the other dummy references so the SDL fixtures inside
 * {@link no.sikt.graphitron.rewrite.GraphitronSchemaBuilderTest} can name them by FQN through
 * the same {@code @record(record: {className: "..."})} form the lifter cases use.
 */
public final class AccessorPayloads {

    private AccessorPayloads() {}

    /** Single-accessor case: bare {@link FilmRecord} return; matches a single child field. */
    public record SinglePayload(FilmRecord film) {}

    /** List-accessor case: {@code List<FilmRecord>}; matches a list child field. */
    public record ListPayload(List<FilmRecord> films) {}

    /** Set-accessor case: {@code Set<FilmRecord>}; matches a list child field via the SET container. */
    public record SetPayload(Set<FilmRecord> films) {}

    /**
     * Ambiguous case: two zero-arg accessors with the same name (one direct, one prefixed
     * {@code get...}) returning {@code List<FilmRecord>}; the field name {@code films} matches
     * both, exercising the {@code AccessorDerivation.Ambiguous} arm.
     */
    public static final class AmbiguousListPayload {
        private final List<FilmRecord> a;
        private final List<FilmRecord> b;

        public AmbiguousListPayload(List<FilmRecord> a, List<FilmRecord> b) {
            this.a = a;
            this.b = b;
        }

        public List<FilmRecord> films() { return a; }
        public List<FilmRecord> getFilms() { return b; }
    }

    /**
     * Heterogeneous-element case: the accessor returns a {@code TableRecord} whose mapped
     * jOOQ table is <em>not</em> the field's {@code @table} return. The classifier should fall
     * through to the rewritten three-option AUTHOR_ERROR rather than auto-deriving (since the
     * author may have intended a non-trivial transform that {@code @batchKeyLifter} can express).
     */
    public record HeterogeneousElementPayload(LanguageRecord films) {}

    /**
     * Cardinality-mismatch case: the field is a list ({@code [Film]}) but the accessor returns
     * a single {@link FilmRecord}. Exercises the
     * {@code AccessorDerivation.CardinalityMismatch} arm.
     */
    public record SingleAccessorOnListField(FilmRecord films) {}
}
