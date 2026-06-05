package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.NestingType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

@UnitTier
class TypeRegistryTest {

    private static GraphitronType plain(String name) {
        return new NestingType(name, null, null);
    }

    private static UnclassifiedType demoted(String name, String reason) {
        return new UnclassifiedType(name, null, Rejection.structural(reason));
    }

    @Test
    void classify_addsEntry_andEntriesViewIsLive() {
        var registry = new TypeRegistry();
        registry.classify("Film", plain("Film"));

        assertThat(registry.contains("Film")).isTrue();
        assertThat(registry.get("Film")).isInstanceOf(NestingType.class);
        assertThat(registry.entries()).containsOnlyKeys("Film");

        // Live view: subsequent classify should be visible without re-fetching entries().
        var view = registry.entries();
        registry.classify("Actor", plain("Actor"));
        assertThat(view).containsKeys("Film", "Actor");
    }

    @Test
    void classify_rejectsDuplicate() {
        var registry = new TypeRegistry();
        registry.classify("Film", plain("Film"));
        assertThatIllegalStateException()
            .isThrownBy(() -> registry.classify("Film", plain("Film")))
            .withMessageContaining("classify('Film')")
            .withMessageContaining("already classified");
    }

    @Test
    void synthesize_rejectsDuplicate() {
        var registry = new TypeRegistry();
        registry.classify("PageInfo", plain("PageInfo"));
        assertThatIllegalStateException()
            .isThrownBy(() -> registry.synthesize("PageInfo",
                new GraphitronType.PageInfoType("PageInfo", null, false, null)))
            .withMessageContaining("synthesize('PageInfo')")
            .withMessageContaining("already classified");
    }

    @Test
    void enrich_rejectsMissingPrior() {
        var registry = new TypeRegistry();
        assertThatIllegalStateException()
            .isThrownBy(() -> registry.enrich("Film", plain("Film")))
            .withMessageContaining("enrich('Film')")
            .withMessageContaining("no prior classification");
    }

    @Test
    void demote_rejectsMissingPrior() {
        var registry = new TypeRegistry();
        assertThatIllegalStateException()
            .isThrownBy(() -> registry.demote("Film", demoted("Film", "x")))
            .withMessageContaining("demote('Film')")
            .withMessageContaining("no prior classification");
    }

    @Test
    void enrich_replacesExistingEntry() {
        var registry = new TypeRegistry();
        registry.classify("Film", plain("Film"));
        var enriched = new GraphitronType.PageInfoType("Film", null, true, null);
        registry.enrich("Film", enriched);
        assertThat(registry.get("Film")).isSameAs(enriched);
    }

    @Test
    void demote_replacesExistingEntry() {
        var registry = new TypeRegistry();
        registry.classify("Film", plain("Film"));
        registry.demote("Film", demoted("Film", "structural"));
        assertThat(registry.get("Film")).isInstanceOf(UnclassifiedType.class);
    }

    @Test
    void entriesView_isUnmodifiable() {
        var registry = new TypeRegistry();
        registry.classify("Film", plain("Film"));
        var view = registry.entries();
        var direct = plain("Actor");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> view.put("Actor", direct))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
