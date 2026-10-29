package no.sikt.graphitron.rewrite.session;

import no.sikt.graphitron.model.config.SessionStateConfig;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit-tier coverage of the authored {@code <sessionState>} reconciliation: a present block is
 * the callback form with or without a {@code <handle>}, a blank handle is a defect, and the
 * retired {@code <mount>}/{@code <unmount>} elements fail with the migration message. All
 * config-shape defects are {@code pom.xml} concerns with no SDL coordinate, so
 * {@link SessionStateConfig#from} throws {@link IllegalArgumentException} and the Maven seam
 * turns it into a build failure. What the handle class offers is the classpath corpus's fact,
 * covered at the store tier, not here.
 */
@UnitTier
class SessionStateConfigTest {

    @Test
    void absentBlock_isNone() {
        assertThat(SessionStateConfig.none()).isEqualTo(SessionStateConfig.None.INSTANCE);
    }

    @Test
    void aBareBlock_isTheCallbackFormWithNoHandle() {
        var config = SessionStateConfig.from(null, null, null);
        assertThat(config).isInstanceOfSatisfying(SessionStateConfig.Callbacks.class,
            callbacks -> assertThat(callbacks.handleType()).isEmpty());
    }

    @Test
    void aNamedHandle_isCarriedAsAuthoredAndTrimmed() {
        var config = SessionStateConfig.from("  com.example.Identity  ", null, null);
        assertThat(config).isInstanceOfSatisfying(SessionStateConfig.Callbacks.class,
            callbacks -> assertThat(callbacks.handleType()).isEqualTo(Optional.of("com.example.Identity")));
    }

    @Test
    void aBlankHandle_isADefectNeverSilentlyAbsent() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> SessionStateConfig.from("  ", null, null))
            .withMessageContaining("<handle>");
    }

    @Test
    void theRetiredMountAndUnmountElements_failWithTheMigration() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> SessionStateConfig.from(null, "com.example.Hooks#mount", null))
            .withMessageContaining("<mount>")
            .withMessageContaining("SessionState")
            .withMessageContaining("newOwnedExecutionInput");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> SessionStateConfig.from(null, null, "com.example.Hooks#unmount"))
            .withMessageContaining("<unmount>");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> SessionStateConfig.from("com.example.Identity", "com.example.Hooks#mount", null))
            .withMessageContaining("<mount>");
    }

    @Test
    void theConfigIsTwoArmsAndOneReconciler_nothingElseIsAccepted() {
        // Pins the shape structurally: the sealed alternation carries exactly None and the
        // callback arm, and the one reconciler takes exactly the three authored strings.
        assertThat(SessionStateConfig.class.getPermittedSubclasses())
            .extracting(Class::getSimpleName)
            .containsExactlyInAnyOrder("None", "Callbacks");
        assertThat(Arrays.stream(SessionStateConfig.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("from")))
            .allSatisfy(m -> assertThat(m.getParameterTypes())
                .containsExactly(String.class, String.class, String.class));
    }
}
