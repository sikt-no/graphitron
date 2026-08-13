package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier rule-table pin for {@link ArgMappingSigil}, the shared owner of the argMapping
 * sigil vocabulary, in the {@link FieldSourceSigilParseTest} shape: the literal set, the
 * pre-tokenization scan (lift recognized sigil entries, pass everything else through to the
 * residual verbatim), the per-site admission predicate, and the canonical rejections. Both
 * routings into the owner are pinned here too: {@code ArgBindingMap.parseArgMapping}'s
 * sigil-aware overload carries the bindings alongside the parsed overrides, and its
 * sigil-unaware single-arg overload (the columnMapping route) keeps the parser's ordinary
 * {@code $}-rejection, so no sigil leaks into sites that admit none.
 */
@UnitTier
class ArgMappingSigilTest {

    // ===== scan: lifting and pass-through =====

    @Test
    void nullAndBlankInput_scanToNoBindingsWithTheInputAsResidual() {
        var nullResult = (ArgMappingSigil.ScanResult.Ok) ArgMappingSigil.scan(null, ArgMappingSigil.Site.SERVICE);
        assertThat(nullResult.sigilBindings()).isEmpty();
        assertThat(nullResult.residual()).isNull();

        var blankResult = (ArgMappingSigil.ScanResult.Ok) ArgMappingSigil.scan("  ", ArgMappingSigil.Site.SERVICE);
        assertThat(blankResult.sigilBindings()).isEmpty();
    }

    @Test
    void sigilFreeMapping_passesThroughVerbatimAsResidual() {
        var result = (ArgMappingSigil.ScanResult.Ok) ArgMappingSigil.scan(
            "inputs: input, kvote: input.kvoteId", ArgMappingSigil.Site.SERVICE);
        assertThat(result.sigilBindings()).isEmpty();
        assertThat(result.residual()).isEqualTo("inputs: input, kvote: input.kvoteId");
    }

    @Test
    void sessionEntry_isLiftedIntoTheBindingMap_leavingTheRestAsResidual() {
        var result = (ArgMappingSigil.ScanResult.Ok) ArgMappingSigil.scan(
            "inputs: input, identity: $session, kvote: kvoteId", ArgMappingSigil.Site.SERVICE);
        assertThat(result.sigilBindings())
            .containsExactlyEntriesOf(Map.of("identity", ArgMappingSigil.SESSION_LITERAL));
        assertThat(result.residual()).isEqualTo("inputs: input, kvote: kvoteId");
    }

    @Test
    void sessionOnlyMapping_liftsToTheBindingWithABlankResidual() {
        var result = (ArgMappingSigil.ScanResult.Ok) ArgMappingSigil.scan(
            "identity: $session", ArgMappingSigil.Site.SERVICE);
        assertThat(result.sigilBindings()).containsOnlyKeys("identity");
        assertThat(result.residual()).isBlank();
    }

    // ===== scan: rejections =====

    @Test
    void unknownSigil_rejectsNamingTheAllowedSet() {
        var result = ArgMappingSigil.scan("identity: $handle", ArgMappingSigil.Site.SERVICE);
        assertThat(result).isInstanceOfSatisfying(ArgMappingSigil.ScanResult.Rejected.class, rejected ->
            assertThat(rejected.message()).isEqualTo(ArgMappingSigil.unknownSigilMessage("$handle")));
    }

    @ParameterizedTest
    @EnumSource(value = ArgMappingSigil.Site.class, names = "SERVICE", mode = EnumSource.Mode.EXCLUDE)
    void sessionAtANonAdmittedSite_rejectsNamingTheAdmittedOne(ArgMappingSigil.Site site) {
        assertThat(site.admitsSessionSigil()).isFalse();
        var result = ArgMappingSigil.scan("identity: $session", site);
        assertThat(result).isInstanceOfSatisfying(ArgMappingSigil.ScanResult.Rejected.class, rejected -> {
            assertThat(rejected.message()).isEqualTo(ArgMappingSigil.notAdmittedMessage(site));
            assertThat(rejected.message()).contains(site.description()).contains("@service argMapping");
        });
    }

    @Test
    void duplicateSigilTarget_rejectsAsTheDuplicateEntryRule() {
        var result = ArgMappingSigil.scan(
            "identity: $session, identity: $session", ArgMappingSigil.Site.SERVICE);
        assertThat(result).isInstanceOfSatisfying(ArgMappingSigil.ScanResult.Rejected.class, rejected ->
            assertThat(rejected.message()).contains("duplicate entries for Java parameter 'identity'"));
    }

    @Test
    void sigilEntryNamingNoJavaParameter_rejects() {
        var result = ArgMappingSigil.scan(": $session", ArgMappingSigil.Site.SERVICE);
        assertThat(result).isInstanceOf(ArgMappingSigil.ScanResult.Rejected.class);
    }

    // ===== both routings reach the one owner =====

    @Test
    void parseArgMapping_sigilAwareOverload_carriesBindingsBesideTheOverrides() {
        var result = ArgBindingMap.parseArgMapping(
            "inputs: input, identity: $session", ArgMappingSigil.Site.SERVICE);

        assertThat(result).isInstanceOfSatisfying(ArgBindingMap.ParsedArgMapping.Ok.class, ok -> {
            assertThat(ok.overrides()).containsExactlyEntriesOf(Map.of("inputs", List.of("input")));
            assertThat(ok.sigilBindings())
                .containsExactlyEntriesOf(Map.of("identity", ArgMappingSigil.SESSION_LITERAL));
        });
    }

    @Test
    void parseArgMapping_sigilAwareOverload_duplicateAcrossSigilAndOverride_rejects() {
        var result = ArgBindingMap.parseArgMapping(
            "identity: someArg, identity: $session", ArgMappingSigil.Site.SERVICE);
        assertThat(result).isInstanceOfSatisfying(ArgBindingMap.ParsedArgMapping.ParseError.class, error ->
            assertThat(error.message()).contains("duplicate entries for Java parameter 'identity'"));
    }

    @Test
    void parseArgMapping_sigilAwareOverload_nonAdmittedSite_surfacesTheOwnersMessage() {
        var result = ArgBindingMap.parseArgMapping(
            "identity: $session", ArgMappingSigil.Site.CONDITION);
        assertThat(result).isInstanceOfSatisfying(ArgBindingMap.ParsedArgMapping.ParseError.class, error ->
            assertThat(error.message())
                .isEqualTo(ArgMappingSigil.notAdmittedMessage(ArgMappingSigil.Site.CONDITION)));
    }

    @Test
    void parseArgMapping_singleArgOverload_keepsTheParsersDollarRejection() {
        // The columnMapping route: sigil-unaware by design. A $-prefixed value keeps the
        // shared parser's ordinary rejection (its lexer refuses VARIABLE tokens as values),
        // so no sigil is quietly admitted at a site that admits none.
        var result = ArgBindingMap.parseArgMapping("identity: $session");
        assertThat(result).isInstanceOf(ArgBindingMap.ParsedArgMapping.ParseError.class);
    }

    @Test
    void tokenizedParsing_isUntouchedByTheSigilScan() {
        // Regression pin on parseEntries' TokenKind.NAME gate: the residual path still parses
        // dot-path expressions exactly as the sigil-unaware overload does.
        var plain = (ArgBindingMap.ParsedArgMapping.Ok) ArgBindingMap.parseArgMapping(
            "kvote: input.kvoteId");
        var throughScan = (ArgBindingMap.ParsedArgMapping.Ok) ArgBindingMap.parseArgMapping(
            "kvote: input.kvoteId", ArgMappingSigil.Site.SERVICE);
        assertThat(throughScan.overrides()).isEqualTo(plain.overrides());
    }
}
