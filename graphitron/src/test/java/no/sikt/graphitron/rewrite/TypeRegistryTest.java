package no.sikt.graphitron.rewrite;

import graphql.language.SourceLocation;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.NestingType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@UnitTier
class TypeRegistryTest {

    private static GraphitronType plain(String name) {
        return new NestingType(name, null, null);
    }

    private static UnclassifiedType demoted(String name, String reason) {
        return new UnclassifiedType(name, null, Rejection.structural(reason));
    }

    @Test
    void register_addsEntry_andEntriesViewIsLive() {
        var registry = new TypeRegistry();
        registry.register("Film", plain("Film"));

        assertThat(registry.contains("Film")).isTrue();
        assertThat(registry.get("Film")).isInstanceOf(NestingType.class);
        assertThat(registry.entries()).containsOnlyKeys("Film");

        // Live view: a subsequent register should be visible without re-fetching entries().
        var view = registry.entries();
        registry.register("Actor", plain("Actor"));
        assertThat(view).containsKeys("Film", "Actor");
    }

    @Test
    void register_storesWhenAbsent() {
        var registry = new TypeRegistry();
        registry.register("Film", plain("Film"));
        assertThat(registry.get("Film")).isInstanceOf(NestingType.class);
    }

    @Test
    void register_isIdempotentOnEqualRepeat() {
        var registry = new TypeRegistry();
        registry.register("Film", plain("Film"));
        registry.register("Film", plain("Film")); // value-equal repeat: no throw, no change
        assertThat(registry.get("Film")).isEqualTo(plain("Film"));
    }

    @Test
    void register_replacesWithSameKindEnrichment() {
        // Non-synth same-kind repeat replaces with the incoming value (plain enrich).
        var registry = new TypeRegistry();
        registry.register("Film", new NestingType("Film", new SourceLocation(1, 1), null));
        var enriched = new NestingType("Film", new SourceLocation(2, 2), null);
        registry.register("Film", enriched);
        assertThat(registry.get("Film")).isSameAs(enriched);
    }

    @Test
    void register_mergesSameKindSynthArm_oringShareable() {
        // A tag-bearing synth arm (Connection / Edge / PageInfo) is merged, not replaced: shareable
        // ORs across registrations (the @tag union is covered end-to-end by ConnectionPromoterTest).
        var registry = new TypeRegistry();
        registry.register("PageInfo", new GraphitronType.PageInfoType("PageInfo", null, false, null));
        registry.register("PageInfo", new GraphitronType.PageInfoType("PageInfo", null, true, null));
        assertThat(((GraphitronType.PageInfoType) registry.get("PageInfo")).shareable()).isTrue();
    }

    @Test
    void register_replacesWithDemotionToUnclassified() {
        var registry = new TypeRegistry();
        registry.register("Film", plain("Film"));
        registry.register("Film", demoted("Film", "participant not table-bound"));
        assertThat(registry.get("Film")).isInstanceOf(UnclassifiedType.class);
    }

    @Test
    void register_demotesOnIncompatibleRepeat() {
        // Two different concrete classifications for one name demote to UnclassifiedType; the
        // accumulator reacts to the conflict rather than throwing, and the validator surfaces it.
        var registry = new TypeRegistry();
        registry.register("Film", plain("Film"));
        registry.register("Film", new GraphitronType.PageInfoType("Film", null, false, null));
        var result = registry.get("Film");
        assertThat(result).isInstanceOf(UnclassifiedType.class);
        assertThat(((UnclassifiedType) result).reason()).contains("classified incompatibly");
    }

    @Test
    void entriesView_isUnmodifiable() {
        var registry = new TypeRegistry();
        registry.register("Film", plain("Film"));
        var view = registry.entries();
        var direct = plain("Actor");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> view.put("Actor", direct))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
