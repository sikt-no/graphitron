package no.sikt.graphitron.codereferences.dummyreferences;

import java.util.List;

/**
 * Test fixture record used by the carrier classifier's {@code ErrorChannel} resolution. The
 * canonical (all-fields) constructor exposes one parameter typed as {@code List<?>}: the
 * errors slot. The {@code data} field exists so the classifier exercises the multi-parameter
 * path (one defaulted slot plus the errors slot).
 *
 * <p>Used as {@code @record(record: {className: "...SakPayload"})} in the SDL fixtures that
 * declare an {@code errors} field on the payload. The element type is {@code ?} (i.e.
 * {@code Object}-bounded) because the channel-typed slot match accepts any parameter whose
 * element-type upper bound is a supertype of every {@code @error} class on the channel; the
 * test fixtures' {@code @error} types have no resolved backing class, so the constraint is
 * vacuous and {@code List<?>} suffices.
 */
public record SakPayload(String data, List<?> errors) {}
