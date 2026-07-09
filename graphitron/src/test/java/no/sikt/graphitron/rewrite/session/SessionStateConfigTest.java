package no.sikt.graphitron.rewrite.session;

import no.sikt.graphitron.rewrite.session.SessionStateConfig.FunctionHooks;
import no.sikt.graphitron.rewrite.session.SessionStateConfig.None;
import no.sikt.graphitron.rewrite.session.SessionStateConfig.RawHook;
import no.sikt.graphitron.rewrite.session.SessionStateConfig.Unmount;
import no.sikt.graphitron.rewrite.session.SessionStateConfig.Variable;
import no.sikt.graphitron.rewrite.session.SessionStateConfig.Variables;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-tier coverage of {@link SessionStateConfig#from}, the build-time reconciliation and rejection
 * of the {@code <sessionState>} shape. Config-shape defects are a {@code pom.xml} concern with no SDL
 * coordinate, so they are validated here (throwing {@link IllegalArgumentException} the Maven seam
 * turns into a build failure) rather than routed through the SDL validator. These are the
 * "validator-tier assertions on the pairing rejections" R429 slice 3 calls for.
 */
@UnitTier
class SessionStateConfigTest {

    @Test
    void noBlock_isNone() {
        assertThat(SessionStateConfig.from(null, null, List.of())).isInstanceOf(None.class);
        assertThat(SessionStateConfig.none()).isInstanceOf(None.class);
    }

    @Test
    void variablesOnly_isVariables() {
        var config = SessionStateConfig.from(null, null, List.of(new Variable("app.user_id", "sub")));
        assertThat(config).isInstanceOfSatisfying(Variables.class, v ->
            assertThat(v.variables()).containsExactly(new Variable("app.user_id", "sub")));
    }

    @Test
    void bothForms_rejected() {
        assertThatThrownBy(() -> SessionStateConfig.from(
            new RawHook("Pk.Connect", false), new RawHook("Pk.Disconnect", false),
            List.of(new Variable("app.user_id", "sub"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("both <variables> and <connect>/<disconnect>");
    }

    @Test
    void functionForm_pairedNoHandle() {
        var config = SessionStateConfig.from(new RawHook("Pk.Connect", false), new RawHook("Pk.Disconnect", false), List.of());
        assertThat(config).isInstanceOfSatisfying(FunctionHooks.class, fh -> {
            assertThat(fh.connectCall()).isEqualTo("Pk.Connect");
            assertThat(fh.unmount()).isEqualTo(new Unmount.PairedDisconnect("Pk.Disconnect", false));
        });
    }

    @Test
    void functionForm_pairedWithHandle() {
        var config = SessionStateConfig.from(new RawHook("Pk_Ras.Connect", true), new RawHook("Pk_Ras.Disconnect", true), List.of());
        assertThat(config).isInstanceOfSatisfying(FunctionHooks.class, fh ->
            assertThat(fh.unmount()).isEqualTo(new Unmount.PairedDisconnect("Pk_Ras.Disconnect", true)));
    }

    @Test
    void handleOnConnectOnly_rejected() {
        assertThatThrownBy(() -> SessionStateConfig.from(
            new RawHook("Pk.Connect", true), new RawHook("Pk.Disconnect", false), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("handle must be declared on both");
    }

    @Test
    void handleOnDisconnectOnly_rejected() {
        assertThatThrownBy(() -> SessionStateConfig.from(
            new RawHook("Pk.Connect", false), new RawHook("Pk.Disconnect", true), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("handle must be declared on both");
    }

    @Test
    void connectWithoutDisconnect_rejected() {
        assertThatThrownBy(() -> SessionStateConfig.from(new RawHook("Pk.Connect", false), null, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("<connect> but no <disconnect>");
    }

    @Test
    void disconnectWithoutConnect_rejected() {
        assertThatThrownBy(() -> SessionStateConfig.from(null, new RawHook("Pk.Disconnect", false), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("<disconnect> but no <connect>");
    }

    @Test
    void emptyDisconnect_isUnmountFreeOptOut() {
        // <disconnect/> present but with no call attribute: the explicit unmount-free marker.
        var config = SessionStateConfig.from(new RawHook("Pk.SetContext", false), new RawHook(null, false), List.of());
        assertThat(config).isInstanceOfSatisfying(FunctionHooks.class, fh -> {
            assertThat(fh.connectCall()).isEqualTo("Pk.SetContext");
            assertThat(fh.unmount()).isInstanceOf(Unmount.UnmountFree.class);
        });
    }

    @Test
    void unmountFreeButConnectProducesHandle_rejected() {
        assertThatThrownBy(() -> SessionStateConfig.from(new RawHook("Pk.Connect", true), new RawHook(null, false), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("produces a handle, but the empty <disconnect/>");
    }

    @Test
    void connectWithBlankCall_rejected() {
        assertThatThrownBy(() -> SessionStateConfig.from(new RawHook("  ", false), new RawHook("Pk.Disconnect", false), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("<connect> requires a non-blank <call>");
    }

    @Test
    void variable_blankNameOrClaim_rejected() {
        assertThatThrownBy(() -> new Variable("", "sub"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("name");
        assertThatThrownBy(() -> new Variable("app.user_id", " "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("claim");
    }
}
