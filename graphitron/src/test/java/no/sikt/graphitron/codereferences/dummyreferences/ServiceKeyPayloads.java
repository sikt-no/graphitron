package no.sikt.graphitron.codereferences.dummyreferences;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmListRecord;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.LanguageRecord;

import java.util.List;

/**
 * Class-backed parent fixtures for the author-declared {@code @service} batch key: a child
 * {@code @service} on a class-backed parent keys on the table its {@code Sources} element type
 * names, and the parent has to be able to produce a record of that table.
 *
 * <p>One type per producer arm and per rejection arm, so a pipeline fixture names the shape it is
 * about. The accessor rule is deliberately name-free: nothing here is named after the SDL field the
 * {@code @service} sits on, which is the point (matching by field name would let a coincidentally
 * named accessor become the batch key).
 */
public final class ServiceKeyPayloads {

    private ServiceKeyPayloads() {}

    /**
     * The accessor arm: exactly one zero-arg accessor returning a {@code language} record. The
     * {@code title} component is deliberately present, so the reduction is over a class with more
     * than one accessor and only the record-returning one can qualify.
     */
    public record LanguageKeyed(LanguageRecord lang, String title) {}

    /**
     * The ambiguity arm: two zero-arg accessors returning a {@code language} record, so which one
     * produces the key is not determined by the element type alone.
     */
    public record TwoLanguageAccessors(LanguageRecord primary, LanguageRecord fallback) {}

    /**
     * The list-cardinality arm: the only accessor returning {@code language} records returns many of
     * them, and a child {@code @service} batches one key per parent.
     */
    public record ManyLanguages(List<LanguageRecord> langs) {}

    /**
     * The PK-less key-owner arm: the parent can produce the declared record, but {@code film_list}
     * has no primary key, so there is nothing to key the batch on.
     */
    public record FilmListKeyed(FilmListRecord list) {}

    /** The no-producer arm: a class-backed parent with no record-returning accessor at all. */
    public record NoRecordAccessor(String title) {}
}
