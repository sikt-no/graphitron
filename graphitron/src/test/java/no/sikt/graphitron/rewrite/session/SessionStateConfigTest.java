package no.sikt.graphitron.rewrite.session;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit-tier coverage of the authored {@code <sessionState>} reconciliation: the method-hook arm
 * with and without {@code <unmount>}, the surviving unmount-without-mount rejection, and the
 * {@code fqcn#method} shape rejections. All config-shape defects are {@code pom.xml} concerns
 * with no SDL coordinate, so {@link SessionStateConfig#from} throws
 * {@link IllegalArgumentException} and the Maven seam turns it into a build failure. Failures of
 * the referenced methods themselves are reflection facts, covered by the resolution tests, not
 * here.
 */
@UnitTier
class SessionStateConfigTest {

    @Test
    void absentBlock_isNone() {
        assertThat(SessionStateConfig.from(null, null)).isEqualTo(SessionStateConfig.none());
    }

    @Test
    void mountAlone_isTheSupportedMountOnlyConfiguration() {
        // Omitting <unmount> is mount-only, with no opt-out ceremony and no rejection: the next
        // request's mount overwrites wholesale.
        var config = SessionStateConfig.from("com.example.db.Routines#connect", null);
        assertThat(config).isInstanceOfSatisfying(SessionStateConfig.MethodHooks.class, hooks -> {
            assertThat(hooks.mount().className()).isEqualTo("com.example.db.Routines");
            assertThat(hooks.mount().methodName()).isEqualTo("connect");
            assertThat(hooks.unmount()).isEmpty();
        });
    }

    @Test
    void mountAndUnmount_carryBothReferencesVerbatim() {
        var config = SessionStateConfig.from(
            "com.example.KernelIdentity#mount", "com.example.KernelIdentity#unmount");
        assertThat(config).isInstanceOfSatisfying(SessionStateConfig.MethodHooks.class, hooks -> {
            assertThat(hooks.mount().raw()).isEqualTo("com.example.KernelIdentity#mount");
            assertThat(hooks.unmount()).map(SessionStateConfig.HookRef::raw)
                .isEqualTo(Optional.of("com.example.KernelIdentity#unmount"));
        });
    }

    @Test
    void referencesAreTrimmed() {
        var config = SessionStateConfig.from("  com.example.Hooks#mount  ", null);
        assertThat(((SessionStateConfig.MethodHooks) config).mount().raw())
            .isEqualTo("com.example.Hooks#mount");
    }

    @Test
    void unmountWithoutMount_isRejected() {
        // Unmounting what nothing mounted is a defect in either direction of reading it.
        assertThatIllegalArgumentException()
            .isThrownBy(() -> SessionStateConfig.from(null, "com.example.Hooks#unmount"))
            .withMessageContaining("<unmount>")
            .withMessageContaining("no <mount>");
    }

    @Test
    void malformedReferences_areRejectedNamingTheElement() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> SessionStateConfig.from("com.example.Hooks", null))
            .withMessageContaining("<mount>")
            .withMessageContaining("fqcn#method");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> SessionStateConfig.from("#mount", null))
            .withMessageContaining("<mount>");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> SessionStateConfig.from("com.example.Hooks#", null))
            .withMessageContaining("<mount>");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> SessionStateConfig.from("a#b#c", null))
            .withMessageContaining("<mount>");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> SessionStateConfig.from("com.example.Hooks#mount", "unmount"))
            .withMessageContaining("<unmount>")
            .withMessageContaining("fqcn#method");
    }

    @Test
    void blankElements_areDefectsNeverSilentlyAbsent() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> SessionStateConfig.from("  ", null))
            .withMessageContaining("<mount>");
        assertThatIllegalArgumentException()
            .isThrownBy(() -> SessionStateConfig.from("com.example.Hooks#mount", " "))
            .withMessageContaining("<unmount>");
    }

    @Test
    void theConfigIsTwoArmsAndTwoStrings_nothingElseIsAcceptedAnyMore() {
        // Pins the deletions structurally: the sealed alternation carries exactly None and the
        // method-hook arm (no FunctionHooks, no Variables, no Unmount hierarchy), and the one
        // reconciler takes exactly the two authored strings, so <variables>, <handle> and
        // <stateSurvivesTransactions> have no seam left to be accepted through.
        assertThat(SessionStateConfig.class.getPermittedSubclasses())
            .extracting(Class::getSimpleName)
            .containsExactlyInAnyOrder("None", "MethodHooks");
        assertThat(Arrays.stream(SessionStateConfig.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("from")))
            .allSatisfy(m -> assertThat(m.getParameterTypes())
                .containsExactly(String.class, String.class));
    }
}
