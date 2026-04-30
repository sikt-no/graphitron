package no.sikt.graphitron.codereferences.dummyreferences;

import java.util.List;

/**
 * Test fixture record used by the carrier classifier's {@code ErrorChannel} resolution. The
 * canonical (all-fields) constructor exposes one parameter assignable from
 * {@code List<? extends GraphitronError>}: the errors slot. The {@code data} field exists so
 * the classifier exercises the multi-parameter path (one defaulted slot plus the errors slot).
 *
 * <p>Used as {@code @record(record: {className: "...SakPayload"})} in the SDL fixtures that
 * declare an {@code errors} field on the payload.
 */
public record SakPayload(String data, List<? extends GraphitronError> errors) {}
