package no.sikt.graphitron.codereferences.dummyreferences;

import java.util.List;

/**
 * Test fixture record used by the carrier classifier's {@code ErrorChannel} resolution: the
 * payload a {@code @service} returns in the SDL fixtures that declare an {@code errors} field on
 * it. Graphitron never constructs it; the claimed errors ride the {@code Outcome} the fetcher
 * returns, and its {@code errors} component is the service's own.
 */
public record SakPayload(String data, List<Object> errors) {}
