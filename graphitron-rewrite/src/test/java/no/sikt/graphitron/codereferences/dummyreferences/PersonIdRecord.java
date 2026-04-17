package no.sikt.graphitron.codereferences.dummyreferences;

/**
 * Stub that simulates the record-class footer emitted by {@code KjerneJooqGenerator} for
 * tables with a legacy composite platform key where the column is named {@code PERSON_ID}.
 * Used to test {@code JooqCatalog.recordHasPlatformIdAccessors()} with non-"id" accessors.
 */
public class PersonIdRecord {
    public String getPersonId() { return ""; }
    public void setPersonId(String id) {}
}
