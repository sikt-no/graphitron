package no.sikt.graphitron.model.diagnostics;

import no.sikt.graphitron.model.diagnostics.StaleModules.Change;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What a session says about stale modules across rounds: each change once, and nothing else. */
class StaleModulesTest {

    @Test
    @DisplayName("a module is said when it goes stale, not again while it stays so, and once when it is compiled")
    void eachChangeIsSaidOnce() {
        var modules = new StaleModules();

        assertThat(modules.update(Map.of("ex:a", "ex:a is stale")))
            .containsExactly(new Change(true, "ex:a", "ex:a is stale"));
        assertThat(modules.update(Map.of("ex:a", "ex:a is stale")))
            .as("the same sentence is not said twice").isEmpty();
        assertThat(modules.update(Map.of("ex:a", "ex:a is staler")))
            .as("a changed sentence is a change")
            .containsExactly(new Change(true, "ex:a", "ex:a is staler"));
        assertThat(modules.update(Map.of()))
            .containsExactly(new Change(false, "ex:a", "ex:a is no longer read stale"));
        assertThat(modules.update(Map.of())).isEmpty();
    }
}
