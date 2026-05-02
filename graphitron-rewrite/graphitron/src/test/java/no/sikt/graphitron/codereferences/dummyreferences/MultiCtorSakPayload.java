package no.sikt.graphitron.codereferences.dummyreferences;

import java.util.List;

/**
 * Test fixture: a hand-rolled {@code @record} POJO that declares an extra no-arg constructor
 * alongside its canonical (all-fields) one. The carrier classifier must identify the all-fields
 * constructor by parameter count matching the SDL field count rather than rejecting on the bare
 * presence of multiple constructors.
 *
 * <p>Mirrors {@link SakPayload}'s shape ({@code (String data, List<?> errors)}) so the same SDL
 * fixtures exercise the canonical-ctor selection path.
 */
public class MultiCtorSakPayload {
    private final String data;
    private final List<?> errors;

    public MultiCtorSakPayload() {
        this(null, List.of());
    }

    public MultiCtorSakPayload(String data, List<?> errors) {
        this.data = data;
        this.errors = errors;
    }

    public String data() { return data; }
    public List<?> errors() { return errors; }
}
