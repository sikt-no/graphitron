package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Compact-constructor invariants for {@link ProducerBinding.DmlEmitted}.
 *
 * <p>The spec pins two invariants the constructor must enforce: every component is non-null,
 * and the reflected class identity matches the carried {@link TableRef}'s record class. The
 * second invariant is what lets the per-SDL-type binding fold in
 * {@link no.sikt.graphitron.rewrite.RecordBindingResolver} treat a {@code DmlEmitted} arm and a
 * {@link ProducerBinding.RootTable} arm for the same table as agreeing, so the structural
 * guarantee the retired {@code mutation-dml-record-field.data-table-equals-input-table}
 * invariant carried re-emerges through the existing fold without a dedicated check key.
 *
 * <p>The tests use {@link String} as a stand-in for a jOOQ record class so the test can
 * construct a {@link TableRef} whose {@code recordClass().reflectionName()} matches a real,
 * loaded {@link Class}. The downstream invariant the spec pins is on the FQN match itself, not
 * on the producer being a real jOOQ {@code TableRecord} subtype; using a JDK class here keeps
 * the invariant testable without dragging the generated jOOQ catalog into the unit tier.
 */
@UnitTier
class ProducerBindingDmlEmittedTest {

    /** Stand-in TableRef whose recordClass FQN matches {@link String}'s. */
    private static final TableRef STRING_TABLE = new TableRef(
        "string_table", "STRING_TABLE",
        ClassName.get("java.lang", "Object"),
        ClassName.get("java.lang", "String"),
        ClassName.get("java.lang", "Object"),
        List.of(),
        List.of());
    private static final SourceLocation LOC = new SourceLocation(1, 1, "schema.graphqls");

    @Test
    void roundTripsComponents() {
        var binding = new ProducerBinding.DmlEmitted(
            String.class, STRING_TABLE, DmlKind.UPDATE,
            Arity.ONE, LOC);
        assertThat(binding.reflectedClass()).isSameAs(String.class);
        assertThat(binding.tableRef()).isSameAs(STRING_TABLE);
        assertThat(binding.kind()).isEqualTo(DmlKind.UPDATE);
        assertThat(binding.arrival()).isEqualTo(Arity.ONE);
        assertThat(binding.location()).isSameAs(LOC);
    }

    @Test
    void describeNamesKindArrivalAndTable() {
        var binding = new ProducerBinding.DmlEmitted(
            String.class, STRING_TABLE, DmlKind.INSERT,
            Arity.MANY, LOC);
        assertThat(binding.describe())
            .contains("INSERT")
            .contains("MANY")
            .contains("string_table");
    }

    @Test
    void rejectsNullReflectedClass() {
        assertThatThrownBy(() -> new ProducerBinding.DmlEmitted(
                null, STRING_TABLE, DmlKind.UPDATE, Arity.ONE, LOC))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("reflectedClass");
    }

    @Test
    void rejectsNullTableRef() {
        assertThatThrownBy(() -> new ProducerBinding.DmlEmitted(
                String.class, null, DmlKind.UPDATE,
                Arity.ONE, LOC))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("tableRef");
    }

    @Test
    void rejectsNullKind() {
        assertThatThrownBy(() -> new ProducerBinding.DmlEmitted(
                String.class, STRING_TABLE, null,
                Arity.ONE, LOC))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("kind");
    }

    @Test
    void rejectsNullArrival() {
        assertThatThrownBy(() -> new ProducerBinding.DmlEmitted(
                String.class, STRING_TABLE, DmlKind.UPDATE, null, LOC))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("arrival");
    }

    @Test
    void rejectsNullLocation() {
        assertThatThrownBy(() -> new ProducerBinding.DmlEmitted(
                String.class, STRING_TABLE, DmlKind.UPDATE,
                Arity.ONE, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("location");
    }

    @Test
    void rejectsReflectedClassDisagreeingWithTableRefRecordClass() {
        assertThatThrownBy(() -> new ProducerBinding.DmlEmitted(
                Object.class, STRING_TABLE, DmlKind.UPDATE,
                Arity.ONE, LOC))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("per-SDL-type binding fold")
            .hasMessageContaining("java.lang.String");
    }
}
