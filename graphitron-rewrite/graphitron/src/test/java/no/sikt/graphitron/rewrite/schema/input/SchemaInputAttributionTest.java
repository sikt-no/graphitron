package no.sikt.graphitron.rewrite.schema.input;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

@UnitTier
class SchemaInputAttributionTest {

    @Test
    void singleEntryWithTagAndNote() {
        var entry = new SchemaInput("a.graphqls", Optional.of("enrollment"), Optional.of("An enrolment note."));
        var map = SchemaInputAttribution.build(List.of(entry));
        assertThat(map).containsExactly(entry("a.graphqls", entry));
    }

    @Test
    void threeDistinctEntriesPreserveInputOrder() {
        var a = new SchemaInput("a.graphqls", Optional.of("x"), Optional.empty());
        var b = new SchemaInput("b.graphqls", Optional.empty(), Optional.of("nb"));
        var c = new SchemaInput("c.graphqls", Optional.of("y"), Optional.of("nc"));
        var map = SchemaInputAttribution.build(List.of(a, b, c));
        assertThat(map.keySet()).containsExactly("a.graphqls", "b.graphqls", "c.graphqls");
        assertThat(map).containsEntry("a.graphqls", a).containsEntry("b.graphqls", b).containsEntry("c.graphqls", c);
    }

    @Test
    void duplicateSourceNameThrowsWithBothEntriesInMessage() {
        var first = new SchemaInput("dup.graphqls", Optional.of("alpha"), Optional.empty());
        var second = new SchemaInput("dup.graphqls", Optional.of("beta"), Optional.of("n"));
        assertThatExceptionOfType(SchemaInputException.class)
            .isThrownBy(() -> SchemaInputAttribution.build(List.of(first, second)))
            .withMessageContaining("dup.graphqls")
            .withMessageContaining("#0")
            .withMessageContaining("#1")
            .withMessageContaining("alpha")
            .withMessageContaining("beta");
    }

    @Test
    void overlapErrorNamesPriorEntryFirst() {
        var first = new SchemaInput("d.graphqls", Optional.of("alpha"), Optional.empty());
        var second = new SchemaInput("d.graphqls", Optional.of("beta"), Optional.empty());
        assertThatExceptionOfType(SchemaInputException.class)
            .isThrownBy(() -> SchemaInputAttribution.build(List.of(first, second)))
            .withMessageMatching("(?s).*#0.*alpha.*#1.*beta.*");
    }

    @Test
    void emptyListReturnsEmptyMap() {
        assertThat(SchemaInputAttribution.build(List.of())).isEmpty();
    }

    private static java.util.Map.Entry<String, SchemaInput> entry(String k, SchemaInput v) {
        return java.util.Map.entry(k, v);
    }
}
