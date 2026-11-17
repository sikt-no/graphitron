package no.sikt.graphitron.rewrite.test.services;

/**
 * Execution-tier fixture: a Java record whose fields are read through its components, the one way
 * a record is read. Reached via {@code Inventory.filmCardData.example} so the addition is minimal:
 * no new top-level query field, just a {@code RecordExample} SDL type wired into the existing
 * {@code FilmCardWrapper} surface.
 *
 * <ul>
 *   <li>{@code fieldA()} and {@code fieldB()}: component accessors, read under the field's own
 *       name.</li>
 *   <li>{@code rebound()}: a component reached via {@code @field(name: "rebound")}, the override
 *       rebinding the SDL field name to the differently-named component.</li>
 * </ul>
 */
public record RecordExampleType(String fieldA, String fieldB, String rebound) {
}
