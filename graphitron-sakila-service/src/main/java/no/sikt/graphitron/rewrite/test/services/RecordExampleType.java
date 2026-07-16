package no.sikt.graphitron.rewrite.test.services;

/**
 * Execution-tier fixture: a Java record exercising three of
 * {@code AccessorResolution.Resolved}'s arms in one type.
 * Reached via {@code Inventory.filmCardData.example} so the addition is minimal — no new
 * top-level query field, just a new {@code RecordExample} SDL type wired into the existing
 * {@code FilmCardWrapper} surface.
 *
 * <p>The three arms exercised:
 * <ul>
 *   <li>{@code fieldA()} — canonical Java record component accessor; resolves under
 *       {@code RECORD_FIRST} candidate ordering as
 *       {@code AccessorResolution.BareName}.</li>
 *   <li>{@code getFieldB()} — bean-style getter; resolves as
 *       {@code AccessorResolution.GetterPrefixed}.</li>
 *   <li>{@code getRebound()} — bean-style getter reached via
 *       {@code @field(name: "rebound")}; the override path is honoured and rebinds the SDL
 *       field name to the differently-named accessor.</li>
 * </ul>
 */
public record RecordExampleType(String fieldA) {

    public String getFieldB() { return "B-" + fieldA; }

    public String getRebound() { return "rebound-" + fieldA; }
}
