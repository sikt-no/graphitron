package no.sikt.graphitron.codereferences.dummyreferences;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.LanguageRecord;

import java.util.List;

/**
 * Author-declared batch-key producers for the {@code @sourceRow} route on a batched child
 * {@code @service}, the static twin of the accessor arm {@link ServiceKeyPayloads} covers.
 *
 * <p>One method per arm, happy paths and rejections alike: the declaration is what the classifier
 * validates, so a fixture that names its shape is what makes each rejection's cause legible.
 * Nothing here is invoked at build time; the classifier reflects on the signatures only.
 */
public final class ServiceKeyLifters {

    private ServiceKeyLifters() {}

    /**
     * The motivating shape: a parent carrying only scalar key columns, which neither producer
     * inference can serve, and a declared producer that builds the key record from them.
     */
    public static LanguageRecord keyOfNoRecordAccessor(ServiceKeyPayloads.NoRecordAccessor parent) {
        var r = new LanguageRecord();
        r.setLanguageId(parent.title().length());
        return r;
    }

    /** The override arm: a parent that <em>could</em> be keyed by accessor inference, declared anyway. */
    public static LanguageRecord keyOfLanguageKeyed(ServiceKeyPayloads.LanguageKeyed parent) {
        return parent.lang();
    }

    /** The tie-break arm: the two-accessor ambiguity, resolved without editing the parent class. */
    public static LanguageRecord keyOfTwoAccessors(ServiceKeyPayloads.TwoLanguageAccessors parent) {
        return parent.primary();
    }

    /** Many-valued: a child {@code @service} batches one key per parent, so this is rejected by name. */
    public static List<LanguageRecord> manyKeys(ServiceKeyPayloads.NoRecordAccessor parent) {
        return List.of();
    }

    /** Returns a record of the wrong table: the declared {@code Sources} element type is the contract. */
    public static FilmRecord wrongRecordClass(ServiceKeyPayloads.NoRecordAccessor parent) {
        return new FilmRecord();
    }

    /** Takes something the parent is not assignable to. */
    public static LanguageRecord takesUnrelatedParameter(String notTheParent) {
        return new LanguageRecord();
    }

    /** Name-overloaded, so the directive's {@code method:} does not identify one method. */
    public static LanguageRecord overloaded(ServiceKeyPayloads.NoRecordAccessor parent) {
        return new LanguageRecord();
    }

    /** The overload that makes the name ambiguous. */
    public static LanguageRecord overloaded(ServiceKeyPayloads.LanguageKeyed parent) {
        return parent.lang();
    }
}
