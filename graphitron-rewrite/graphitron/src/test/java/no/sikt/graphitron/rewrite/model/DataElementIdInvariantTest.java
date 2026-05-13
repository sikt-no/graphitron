package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-tier coverage of the {@link DataElement.Id} compact-constructor invariants (R156). The
 * carrier walk's structural rejections at
 * {@code BuildContext.classifyCarrierField} are belt; the compact-constructor invariants here
 * are braces — a programming-error construction (e.g. a future caller passing a
 * list-of-nullable or {@link FieldWrapper.Connection} wrapper) fails fast at construction
 * rather than surfacing later in the emitter pipeline.
 */
@UnitTier
class DataElementIdInvariantTest {

    @Test
    void singleton_id_admits() {
        var e = new DataElement.Id("ID", new FieldWrapper.Single(true));
        assertThat(e.wrapper()).isInstanceOf(FieldWrapper.Single.class);
    }

    @Test
    void singleton_non_null_id_admits() {
        var e = new DataElement.Id("ID", new FieldWrapper.Single(false));
        assertThat(e.wrapper()).isInstanceOf(FieldWrapper.Single.class);
    }

    @Test
    void list_of_non_null_id_admits_nullable_list() {
        var e = new DataElement.Id("ID", new FieldWrapper.List(true, false));
        assertThat(e.wrapper()).isInstanceOf(FieldWrapper.List.class);
    }

    @Test
    void list_of_non_null_id_admits_non_null_list() {
        var e = new DataElement.Id("ID", new FieldWrapper.List(false, false));
        assertThat(e.wrapper()).isInstanceOf(FieldWrapper.List.class);
    }

    @Test
    void list_of_nullable_id_rejects() {
        assertThatThrownBy(() -> new DataElement.Id("ID", new FieldWrapper.List(true, true)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("list-of-nullable");
    }

    @Test
    void connection_wrapper_rejects() {
        assertThatThrownBy(() -> new DataElement.Id("ID", new FieldWrapper.Connection(true, 100)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("singleton ID/ID! or list-of-non-null");
    }
}
