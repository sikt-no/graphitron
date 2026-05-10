package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-tier coverage of the {@link LoaderRegistration} record's null-rejection contract and
 * shape.
 */
@UnitTier
class LoaderRegistrationTest {

    @Test
    void positionalListLoadOneRoundTripsComponents() {
        var reg = new LoaderRegistration(
            true,
            LoaderRegistration.Container.POSITIONAL_LIST,
            LoaderRegistration.Dispatch.LOAD_ONE);
        assertThat(reg.valueIsList()).isTrue();
        assertThat(reg.container()).isEqualTo(LoaderRegistration.Container.POSITIONAL_LIST);
        assertThat(reg.dispatch()).isEqualTo(LoaderRegistration.Dispatch.LOAD_ONE);
    }

    @Test
    void mappedSetLoadManyRoundTripsComponents() {
        var reg = new LoaderRegistration(
            false,
            LoaderRegistration.Container.MAPPED_SET,
            LoaderRegistration.Dispatch.LOAD_MANY);
        assertThat(reg.valueIsList()).isFalse();
        assertThat(reg.container()).isEqualTo(LoaderRegistration.Container.MAPPED_SET);
        assertThat(reg.dispatch()).isEqualTo(LoaderRegistration.Dispatch.LOAD_MANY);
    }

    @Test
    void rejectsNullContainer() {
        assertThatThrownBy(() -> new LoaderRegistration(
                false, null, LoaderRegistration.Dispatch.LOAD_ONE))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("container");
    }

    @Test
    void rejectsNullDispatch() {
        assertThatThrownBy(() -> new LoaderRegistration(
                false, LoaderRegistration.Container.POSITIONAL_LIST, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("dispatch");
    }
}
