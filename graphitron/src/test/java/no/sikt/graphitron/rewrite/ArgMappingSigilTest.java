package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.grammar.ArgMappingSigil;

/**
 * Unit-tier rule-table pin for {@link ArgMappingSigil}, the shared owner of the argMapping
 * sigil vocabulary, in the {@link FieldSourceSigilParseTest} shape: the literal set, the
 * pre-tokenization scan (lift recognized sigil entries, pass everything else through to the
 * residual verbatim), the per-site admission predicate, and the canonical rejections. Both
 * routings into the owner are pinned here too: {@code ArgBindingMap.parseArgMapping}'s
 * sigil-aware overload carries the bindings alongside the parsed overrides, and its
 * sigil-unaware single-arg overload, which parses what the scan leaves, keeps the parser's
 * ordinary {@code $}-rejection, so no sigil leaks past the scan.
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
            .containsExactlyEntriesOf(Map.of("identity", new ArgMappingSigil.Binding.SessionHandle()));
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
    @EnumSource(value = ArgMappingSigil.Site.class, names = {"SERVICE", "CONDITION"}, mode = EnumSource.Mode.EXCLUDE)
    void sessionAtANonAdmittedSite_rejectsNamingTheAdmittedOne(ArgMappingSigil.Site site) {
        assertThat(site.admitsSessionSigil()).isFalse();
        var result = ArgMappingSigil.scan("identity: $session", site);
        assertThat(result).isInstanceOfSatisfying(ArgMappingSigil.ScanResult.Rejected.class, rejected -> {
            assertThat(rejected.message()).isEqualTo(ArgMappingSigil.notAdmittedMessage(site));
            assertThat(rejected.message()).contains(site.description())
                .contains("@service and @condition argMapping");
        });
    }

    @Test
    void sourceColumnEntry_isLiftedAtTheRoutineSite() {
        var result = (ArgMappingSigil.ScanResult.Ok) ArgMappingSigil.scan(
            "pActorId: $source.actor_id, pMinLength: minLength", ArgMappingSigil.Site.ROUTINE);
        assertThat(result.sigilBindings()).containsExactlyEntriesOf(
            Map.of("pActorId", new ArgMappingSigil.Binding.SourceColumn("actor_id")));
        assertThat(result.residual()).isEqualTo(" pMinLength: minLength");
        assertThat(ArgMappingSigil.sourceColumn("$source.actor_id")).isEqualTo("actor_id");
    }

    @Test
    void sourceWithoutExactlyOneColumn_isAnUnknownSigil() {
        for (String rhs : new String[] {"$source", "$source.", "$source.a.b"}) {
            var result = ArgMappingSigil.scan("pActorId: " + rhs, ArgMappingSigil.Site.ROUTINE);
            assertThat(result).isInstanceOfSatisfying(ArgMappingSigil.ScanResult.Rejected.class, rejected ->
                assertThat(rejected.message()).isEqualTo(ArgMappingSigil.unknownSigilMessage(rhs)));
        }
    }

    @ParameterizedTest
    @EnumSource(value = ArgMappingSigil.Site.class, names = "ROUTINE", mode = EnumSource.Mode.EXCLUDE)
    void sourceAtANonAdmittedSite_rejectsNamingTheRoutineSite(ArgMappingSigil.Site site) {
        assertThat(site.admitsSourceSigil()).isFalse();
        var result = ArgMappingSigil.scan("pActorId: $source.actor_id", site);
        assertThat(result).isInstanceOfSatisfying(ArgMappingSigil.ScanResult.Rejected.class, rejected ->
            assertThat(rejected.message()).isEqualTo(ArgMappingSigil.sourceNotAdmittedMessage(site)));
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
                .containsExactlyEntriesOf(Map.of("identity", new ArgMappingSigil.Binding.SessionHandle()));
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
            "identity: $session", ArgMappingSigil.Site.REFERENCE_STEP);
        assertThat(result).isInstanceOfSatisfying(ArgBindingMap.ParsedArgMapping.ParseError.class, error ->
            assertThat(error.message())
                .isEqualTo(ArgMappingSigil.notAdmittedMessage(ArgMappingSigil.Site.REFERENCE_STEP)));
    }

    @Test
    void conditionSite_admitsTheWholeHandleAndAMember() {
        var result = (ArgMappingSigil.ScanResult.Ok) ArgMappingSigil.scan(
            "sokerId: $session.sokerId, h: $session, kvote: kvoteId", ArgMappingSigil.Site.CONDITION);
        assertThat(result.sigilBindings()).containsExactly(
            Map.entry("sokerId", new ArgMappingSigil.Binding.SessionMember("sokerId")),
            Map.entry("h", new ArgMappingSigil.Binding.SessionHandle()));
        assertThat(result.sigilBindings().get("sokerId").raw()).isEqualTo("$session.sokerId");
        assertThat(result.residual()).isEqualTo(" kvote: kvoteId");
    }

    @Test
    void referenceStep_stillRejectsTheSessionSigil() {
        assertThat(ArgMappingSigil.Site.REFERENCE_STEP.admitsSessionSigil()).isFalse();
        assertThat(ArgMappingSigil.scan("x: $session.member", ArgMappingSigil.Site.REFERENCE_STEP))
            .isInstanceOf(ArgMappingSigil.ScanResult.Rejected.class);
    }

    @Test
    void sessionMember_servicesAdmitIt_andADeeperPathRejectsNamingTheOneMemberForm() {
        var ok = (ArgMappingSigil.ScanResult.Ok) ArgMappingSigil.scan(
            "x: $session.brukerId", ArgMappingSigil.Site.SERVICE);
        assertThat(ok.sigilBindings()).containsExactly(
            Map.entry("x", new ArgMappingSigil.Binding.SessionMember("brukerId")));
        for (String rhs : new String[] {"$session.a.b", "$session."}) {
            var result = ArgMappingSigil.scan("x: " + rhs, ArgMappingSigil.Site.SERVICE);
            assertThat(result).isInstanceOfSatisfying(ArgMappingSigil.ScanResult.Rejected.class, rejected ->
                assertThat(rejected.message()).isEqualTo(ArgMappingSigil.deepSessionPathMessage(rhs)));
        }
    }

    @Test
    void sessionMember_isNotAdmittedAtTheNonAdmittedSites() {
        for (var site : new ArgMappingSigil.Site[] {ArgMappingSigil.Site.ROUTINE, ArgMappingSigil.Site.RECORD,
                ArgMappingSigil.Site.EXTERNAL_FIELD, ArgMappingSigil.Site.ENUM}) {
            assertThat(ArgMappingSigil.scan("x: $session.m", site))
                .isInstanceOfSatisfying(ArgMappingSigil.ScanResult.Rejected.class, rejected ->
                    assertThat(rejected.message()).isEqualTo(ArgMappingSigil.notAdmittedMessage(site)));
        }
    }

    @Test
    void parseArgMapping_singleArgOverload_keepsTheParsersDollarRejection() {
        // The residual parse: sigil-unaware by design. A $-prefixed value keeps the shared
        // parser's ordinary rejection (its lexer refuses VARIABLE tokens as values), so a sigil
        // the scan did not lift is never quietly admitted.
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
