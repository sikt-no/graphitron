package no.sikt.graphitron.codereferences.dummyreferences;

/**
 * Stub that simulates the record-class footer emitted by {@code KjerneJooqGenerator} for
 * tables with a legacy composite platform key. Used to test
 * {@code JooqCatalog.hasPlatformIdMethods()} in isolation.
 */
public class PlatformIdRecord {
    public String getId() { return ""; }
    public void setId(String id) {}
}
