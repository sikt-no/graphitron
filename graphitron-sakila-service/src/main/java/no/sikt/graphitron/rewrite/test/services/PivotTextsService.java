package no.sikt.graphitron.rewrite.test.services;

/**
 * {@code @service} producer for the {@code @pivot} coexistence fixture: hands back a
 * {@link PivotTexts} record so the {@code TranslatedTexts} projection type is also reached
 * class-backed. The execution tier asserts the same registered slot fetchers read this record's
 * accessors and the pivot subselect's jOOQ record through one run-time dispatch.
 */
public final class PivotTextsService {

    private PivotTextsService() {}

    public static PivotTexts translatedTexts() {
        return new PivotTexts("service-nn", "service-nb", null, "service-en");
    }
}
