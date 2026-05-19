package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier rule-table pin for {@link FieldSourceSigil#parseRawValue}. Covers both admitted
 * sigil literals ({@code $source} from R159, {@code $errors} from R178), the {@code BareName}
 * fall-through, blank/null absence, and the {@code UnknownSigil} arm for any other
 * {@code $}-prefixed value. The directive-container plumbing in
 * {@link FieldSourceSigil#parseArgFieldNameRef} is covered separately by
 * {@code FieldSourceSigilPipelineTest}; this class pins the literal-parsing logic in
 * isolation so a regression on either sigil arm fires at the unit tier.
 */
@UnitTier
class FieldSourceSigilParseTest {

    @Test
    void nullInputResolvesToAbsent() {
        assertThat(FieldSourceSigil.parseRawValue(null))
            .isInstanceOf(FieldSourceSigil.ParseResult.Absent.class);
    }

    @Test
    void emptyInputResolvesToAbsent() {
        assertThat(FieldSourceSigil.parseRawValue(""))
            .isInstanceOf(FieldSourceSigil.ParseResult.Absent.class);
    }

    @Test
    void blankInputResolvesToAbsent() {
        assertThat(FieldSourceSigil.parseRawValue("   \t  "))
            .isInstanceOf(FieldSourceSigil.ParseResult.Absent.class);
    }

    @Test
    void bareNameRoundTrips() {
        var result = FieldSourceSigil.parseRawValue("film_id");
        assertThat(result).isInstanceOf(FieldSourceSigil.ParseResult.Ok.class);
        var ref = ((FieldSourceSigil.ParseResult.Ok) result).ref();
        assertThat(ref).isEqualTo(new FieldSourceSigil.FieldNameRef.BareName("film_id"));
    }

    @Test
    void bareNameLeadingTrailingWhitespaceStripped() {
        var result = FieldSourceSigil.parseRawValue("  errors  ");
        assertThat(result).isInstanceOf(FieldSourceSigil.ParseResult.Ok.class);
        assertThat(((FieldSourceSigil.ParseResult.Ok) result).ref())
            .isEqualTo(new FieldSourceSigil.FieldNameRef.BareName("errors"));
    }

    @Test
    void sourceSigilResolvesToUpstreamRoot() {
        var result = FieldSourceSigil.parseRawValue("$source");
        assertThat(result).isInstanceOf(FieldSourceSigil.ParseResult.Ok.class);
        assertThat(((FieldSourceSigil.ParseResult.Ok) result).ref())
            .isEqualTo(new FieldSourceSigil.FieldNameRef.UpstreamRoot());
    }

    @Test
    void errorsSigilResolvesToLocalContext() {
        var result = FieldSourceSigil.parseRawValue("$errors");
        assertThat(result).isInstanceOf(FieldSourceSigil.ParseResult.Ok.class);
        assertThat(((FieldSourceSigil.ParseResult.Ok) result).ref())
            .isEqualTo(new FieldSourceSigil.FieldNameRef.LocalContext());
    }

    @Test
    void errorsSigilLiteralExposesExpectedConstant() {
        // Pin the literal so a typo in the constant (e.g. "$error" vs "$errors") fires
        // a unit-tier failure rather than slipping past the parser.
        assertThat(FieldSourceSigil.LOCAL_CONTEXT_LITERAL).isEqualTo("$errors");
        assertThat(FieldSourceSigil.parseRawValue(FieldSourceSigil.LOCAL_CONTEXT_LITERAL))
            .isEqualTo(new FieldSourceSigil.ParseResult.Ok(new FieldSourceSigil.FieldNameRef.LocalContext()));
    }

    @Test
    void unknownDollarPrefixedValueResolvesToUnknownSigil() {
        var result = FieldSourceSigil.parseRawValue("$context");
        assertThat(result).isInstanceOf(FieldSourceSigil.ParseResult.UnknownSigil.class);
        assertThat(((FieldSourceSigil.ParseResult.UnknownSigil) result).raw()).isEqualTo("$context");
    }

    @Test
    void unknownSigilStrippedBeforeReturn() {
        var result = FieldSourceSigil.parseRawValue("  $unknown  ");
        assertThat(result).isInstanceOf(FieldSourceSigil.ParseResult.UnknownSigil.class);
        assertThat(((FieldSourceSigil.ParseResult.UnknownSigil) result).raw()).isEqualTo("$unknown");
    }

    @Test
    void unknownSigilMessageMentionsBothAdmittedLiterals() {
        // The diagnostic family is what authors see in LSP and build output; both R159's
        // $source and R178's $errors must appear so an author writing $context (or any
        // other $-prefixed literal) sees the actual menu of valid choices.
        String msg = FieldSourceSigil.unknownSigilMessage("$context");
        assertThat(msg).contains("$source").contains("$errors").contains("$context");
    }

    @Test
    void dollarOnlyResolvesToUnknownSigil() {
        // A bare "$" is not a valid literal of either sigil but matches the
        // sigil-prefix branch; it should surface UnknownSigil rather than fall through to
        // a structurally-impossible BareName("$").
        var result = FieldSourceSigil.parseRawValue("$");
        assertThat(result).isInstanceOf(FieldSourceSigil.ParseResult.UnknownSigil.class);
        assertThat(((FieldSourceSigil.ParseResult.UnknownSigil) result).raw()).isEqualTo("$");
    }
}
