package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.RenamedGetterDraftRecord;

import java.util.List;

/**
 * Fixture behind {@code Mutation.saveDrafts} in the {@code scenarios/validation-renamed-getter} fact
 * document: a {@code @service} taking jOOQ {@link RenamedGetterDraftRecord}s under a
 * {@code {handler: VALIDATION}} channel. The fixture codegen renames that record's getter for
 * description to {@code readDescription}, so the validator pre-step finds no getter to check that
 * column by, while title keeps {@code getTitle}. Counts the drafts.
 */
public final class RenamedGetterDraftService {

    private RenamedGetterDraftService() {}

    public static FilmDraftsCountedPayload count(List<RenamedGetterDraftRecord> drafts) {
        return new FilmDraftsCountedPayload(drafts.size(), List.of());
    }
}
