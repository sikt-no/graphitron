package no.sikt.graphitron.codereferences.dummyreferences;

import java.util.List;

/**
 * Test fixture record used by the carrier classifier's {@code ErrorChannel} resolution. The
 * canonical (all-fields) constructor exposes one parameter typed as {@code List<Object>}: the
 * errors slot. The {@code data} field exists so the classifier exercises the multi-parameter
 * path (one defaulted slot plus the errors slot).
 *
 * <p>Used as {@code @record(record: {className: "...SakPayload"})} in the SDL fixtures that
 * declare an {@code errors} field on the payload. The element type is {@code Object} per the
 * source-direct dispatch contract: the per-fetcher catch arm and the wrapper's pre-execution
 * Jakarta validation step both push raw {@code Throwable}s and {@code GraphQLError}s into the
 * list, so the slot must admit both unrelated bounds. {@code List<?>} is read-only at the call
 * site and would not express the runtime add-into-list contract; {@code List<Object>} does.
 */
public record SakPayload(String data, List<Object> errors) {}
