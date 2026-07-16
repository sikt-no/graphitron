package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier rule-table pin for {@link FieldBuilder#selectErrorsTransport}. The helper encodes
 * the errors-field defaulting rule as a pure switch over the parsed {@code @field(name:)}
 * value and an accessor-existence boolean; this test fixes every branch so a regression on
 * the default-name fallback or the explicit-name no-fallback rule fires immediately.
 *
 * <p>The site adapter that derives the inputs (reads {@code @field(name:)} from the SDL
 * field; queries {@code ClassAccessorResolver} for the {@code errors} accessor against the
 * payload class resolved by the reflection-driven binding walk) is added separately; this
 * class pins the rule table alone so the site adapter can be reviewed against a frozen contract.
 */
@UnitTier
class ErrorsTransportSelectionTest {

    @Test
    void errorsSigil_alwaysSelectsLocalContext_evenWhenAccessorExists() {
        var parsed = new FieldSourceSigil.ParseResult.Ok(new FieldSourceSigil.FieldNameRef.LocalContext());
        assertThat(FieldBuilder.selectErrorsTransport(parsed, /*accessorMatchesErrors=*/true))
            .isInstanceOf(ChildField.Transport.LocalContext.class);
    }

    @Test
    void errorsSigil_selectsLocalContext_whenAccessorAbsent() {
        var parsed = new FieldSourceSigil.ParseResult.Ok(new FieldSourceSigil.FieldNameRef.LocalContext());
        assertThat(FieldBuilder.selectErrorsTransport(parsed, /*accessorMatchesErrors=*/false))
            .isInstanceOf(ChildField.Transport.LocalContext.class);
    }

    @Test
    void explicitBareName_selectsPayloadAccessor_noLocalContextFallback() {
        // @field(name: "errors") — explicit literal, accessor lookup only. The author has
        // opted out of the default-name fallback; if no accessor matches the caller is
        // expected to surface an accessor-mismatch rejection rather than silently swap to
        // localContext.
        var parsed = new FieldSourceSigil.ParseResult.Ok(new FieldSourceSigil.FieldNameRef.BareName("errors"));
        assertThat(FieldBuilder.selectErrorsTransport(parsed, /*accessorMatchesErrors=*/true))
            .isInstanceOf(ChildField.Transport.PayloadAccessor.class);
        assertThat(FieldBuilder.selectErrorsTransport(parsed, /*accessorMatchesErrors=*/false))
            .isInstanceOf(ChildField.Transport.PayloadAccessor.class);
    }

    @Test
    void explicitDifferentBareName_selectsPayloadAccessor() {
        // @field(name: "<anyOtherLiteral>") behaves the same as the explicit "errors" case
        // from this helper's perspective: explicit non-sigil → accessor-only.
        var parsed = new FieldSourceSigil.ParseResult.Ok(new FieldSourceSigil.FieldNameRef.BareName("validationFailures"));
        assertThat(FieldBuilder.selectErrorsTransport(parsed, /*accessorMatchesErrors=*/true))
            .isInstanceOf(ChildField.Transport.PayloadAccessor.class);
        assertThat(FieldBuilder.selectErrorsTransport(parsed, /*accessorMatchesErrors=*/false))
            .isInstanceOf(ChildField.Transport.PayloadAccessor.class);
    }

    @Test
    void absentDirective_accessorPresent_selectsPayloadAccessor() {
        // Default-name path with the developer-supplied accessor available: the payload
        // class owns the errors slot, accessor wins.
        var parsed = new FieldSourceSigil.ParseResult.Absent();
        assertThat(FieldBuilder.selectErrorsTransport(parsed, /*accessorMatchesErrors=*/true))
            .isInstanceOf(ChildField.Transport.PayloadAccessor.class);
    }

    @Test
    void absentDirective_accessorAbsent_selectsLocalContext() {
        // Default-name path without a matching accessor: fall back to env.getLocalContext().
        // The errors-handling-parity contract requires that payloads with no errors property
        // still receive typed errors through localContext.
        var parsed = new FieldSourceSigil.ParseResult.Absent();
        assertThat(FieldBuilder.selectErrorsTransport(parsed, /*accessorMatchesErrors=*/false))
            .isInstanceOf(ChildField.Transport.LocalContext.class);
    }

    @Test
    void sourceSigilOnErrorsField_selectsPayloadAccessor() {
        // @field(name: "$source") on an errors-shaped field is rejected upstream
        // (errors-shaped fields are by definition polymorphic-of-@error, not passthrough
        // identity), but if the helper is ever reached with this input it must not select
        // LocalContext — that would silently swap the author's intent. PayloadAccessor is
        // the conservative pick that will fail loudly at accessor-resolution time.
        var parsed = new FieldSourceSigil.ParseResult.Ok(new FieldSourceSigil.FieldNameRef.UpstreamRoot());
        assertThat(FieldBuilder.selectErrorsTransport(parsed, /*accessorMatchesErrors=*/true))
            .isInstanceOf(ChildField.Transport.PayloadAccessor.class);
        assertThat(FieldBuilder.selectErrorsTransport(parsed, /*accessorMatchesErrors=*/false))
            .isInstanceOf(ChildField.Transport.PayloadAccessor.class);
    }

    @Test
    void unknownSigil_selectsPayloadAccessor() {
        // Unknown sigils are rejected upstream by the directive validator; the helper's
        // fall-through arm must not silently swap to LocalContext.
        var parsed = new FieldSourceSigil.ParseResult.UnknownSigil("$nope");
        assertThat(FieldBuilder.selectErrorsTransport(parsed, /*accessorMatchesErrors=*/true))
            .isInstanceOf(ChildField.Transport.PayloadAccessor.class);
        assertThat(FieldBuilder.selectErrorsTransport(parsed, /*accessorMatchesErrors=*/false))
            .isInstanceOf(ChildField.Transport.PayloadAccessor.class);
    }
}
