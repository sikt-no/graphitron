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
    void positionalListWithListValueRoundTripsComponents() {
        var reg = new LoaderRegistration(
            "tenant/films/actors",
            true,
            LoaderRegistration.Container.POSITIONAL_LIST);
        assertThat(reg.loaderName()).isEqualTo("tenant/films/actors");
        assertThat(reg.valueIsList()).isTrue();
        assertThat(reg.container()).isEqualTo(LoaderRegistration.Container.POSITIONAL_LIST);
    }

    @Test
    void mappedSetWithSingleValueRoundTripsComponents() {
        var reg = new LoaderRegistration(
            "tenant/films/category",
            false,
            LoaderRegistration.Container.MAPPED_SET);
        assertThat(reg.valueIsList()).isFalse();
        assertThat(reg.container()).isEqualTo(LoaderRegistration.Container.MAPPED_SET);
    }

    @Test
    void rejectsNullLoaderName() {
        assertThatThrownBy(() -> new LoaderRegistration(
                null, false, LoaderRegistration.Container.POSITIONAL_LIST))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("loaderName");
    }

    @Test
    void rejectsNullContainer() {
        assertThatThrownBy(() -> new LoaderRegistration(
                "name", false, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("container");
    }
}
