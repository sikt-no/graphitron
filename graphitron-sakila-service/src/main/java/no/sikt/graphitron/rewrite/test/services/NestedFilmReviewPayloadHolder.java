package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/**
 * Compilation-tier fixture: the nested-carrier photo-negative of {@link FilmReviewPayload}.
 * Where {@code FilmReviewPayload} is a top-level record (binary name has no {@code $}), this payload
 * is a <em>nested</em> record ({@code Payload} enclosed here), so its binary name is
 * {@code NestedFilmReviewPayloadHolder$Payload}.
 *
 * <p>Returned by {@link NestedFilmReviewService#submit}, so the SDL {@code NestedFilmReviewPayload}
 * type binds as a class-backed {@code ResultReturnType} whose {@code fqClassName} carries the
 * {@code $}-qualified binary name, and the field classifies as a {@code MutationServiceRecordField}
 * with an {@code errors} slot resolving an {@code ErrorChannel} against this record's canonical
 * {@code (List<?> errors)} constructor. It is the only compiling witness for the two remaining
 * in-hand {@code bestGuess}-over-binary-name sites this pass fixes:
 * <ul>
 *   <li>{@code TypeFetcherGenerator.computeMutationServiceRecordReturnType} — the mutation twin of
 *   the query-side {@code computeServiceRecordReturnType}; it declared the fetcher return type
 *   ({@code DataFetcherResult<Outer$Nested>} / {@code Outer$Nested result}) via {@code bestGuess}
 *   and failed {@code javac}. Now sourced from {@code ServiceMethodCall.javaReturnType()}.</li>
 *   <li>{@code FieldBuilder.resolveErrorChannel} — the {@code @service} Outcome payload-construction
 *   resolver; the ctor arm ({@code buildErrorChannelCtorArm}) emitted {@code new Outer$Nested(...)}
 *   via {@code bestGuess}. Now built via {@code ClassName.get(payloadCls)}.</li>
 * </ul>
 * Before this pass both emitted {@code NestedFilmReviewPayloadHolder$Payload} and failed the
 * {@code graphitron-sakila-example} compile gate; after, they spell
 * {@code NestedFilmReviewPayloadHolder.Payload} and compile.
 *
 * <p>The errors-slot type is {@code List<?>} to match the dispatch lambda's
 * {@code Function<List<?>, P>} parameter, mirroring {@link FilmReviewPayload}. The payload carries
 * no other field on purpose; see the {@link Payload} javadoc for why a scalar data field would drag
 * in the no-Class-in-hand emit sites and defeat this witness.
 */
public final class NestedFilmReviewPayloadHolder {

    private NestedFilmReviewPayloadHolder() {}

    /**
     * Nested record payload carrying <em>only</em> the errors slot; its canonical
     * {@code (List<?> errors)} constructor drives the error-channel ctor arm
     * ({@code buildErrorChannelCtorArm}), which this pass fixes to emit {@code new Outer.Nested(...)}.
     *
     * <p>Deliberately no scalar/property data field: a scalar read off the backing record would
     * route through {@code FetcherEmitter.propertyOrRecordBinding} / {@code inlineSuccessRead}, whose
     * own {@code ClassName.bestGuess(fqClassName)} cast is the <em>no-Class-in-hand</em> emit-site
     * defect (it holds only a binary string, no reflected {@code Class<?>} or
     * {@code codegenLoader} at the site). Keeping the payload errors-only isolates this witness to the
     * two in-hand sites this pass fixes ({@code resolveErrorChannel} + {@code computeMutationServiceRecordReturnType})
     * so it compiles without depending on a fix for the no-Class-in-hand sites. Binary name has a {@code $}.
     */
    public record Payload(List<?> errors) {}
}
