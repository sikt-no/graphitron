package no.sikt.graphitron.rewrite;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.RootField;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@UnitTier
class FieldRegistryTest {

    private static FieldCoordinates coords(String parent, String name) {
        return FieldCoordinates.coordinates(parent, name);
    }

    private static InputField.ColumnField simpleColumnField(String parent, String name) {
        return new InputField.ColumnField(
            parent, name, null, "String", true, false,
            new ColumnRef("col", "COL", "java.lang.String"),
            Optional.empty(),
            new CallSiteExtraction.Direct());
    }

    @Test
    void classify_addsEntry_andEntriesViewIsLive() {
        var registry = new FieldRegistry();
        var field = simpleColumnField("FilmInput", "title");
        registry.classify(coords("FilmInput", "title"), field);

        assertThat(registry.get(coords("FilmInput", "title"))).isSameAs(field);
        assertThat(registry.entries()).containsOnlyKeys(coords("FilmInput", "title"));

        var view = registry.entries();
        var another = simpleColumnField("FilmInput", "year");
        registry.classify(coords("FilmInput", "year"), another);
        assertThat(view).containsKey(coords("FilmInput", "year"));
    }

    @Test
    void classify_duplicateCoordinates_recordsConflictWithoutThrowing() {
        // A double-classification is a generator conflict, but it must not abort
        // the classification pass (which would hide every other diagnostic). classify records the
        // colliding coordinate as an UnclassifiedField — surfaced cleanly by the validator — and
        // continues, rather than throwing an IllegalStateException.
        var registry = new FieldRegistry();
        var field = simpleColumnField("FilmInput", "title");
        registry.classify(coords("FilmInput", "title"), field);
        registry.classify(coords("FilmInput", "title"), field);

        var entry = registry.get(coords("FilmInput", "title"));
        assertThat(entry).isInstanceOf(no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField.class);
        assertThat(((no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField) entry).rejection().message())
            .contains("classified more than once");
    }

    @Test
    void classifyInput_acceptsResolved_withoutCentralStorage() {
        var registry = new FieldRegistry();
        var resolution = new InputFieldResolution.Resolved(simpleColumnField("FilmInput", "title"));
        // Trace-only: no exception, no central-map mutation. The output-fields map stays empty.
        registry.classifyInput("FilmInput", "title", null, resolution);
        assertThat(registry.entries()).isEmpty();
    }

    @Test
    void classifyInput_acceptsUnresolved_withoutCentralStorage() {
        var registry = new FieldRegistry();
        var resolution = new InputFieldResolution.Unresolved("title", "title", "no such column");
        registry.classifyInput("FilmInput", "title", null, resolution);
        assertThat(registry.entries()).isEmpty();
    }

    @Test
    void entriesView_isUnmodifiable() {
        var registry = new FieldRegistry();
        registry.classify(coords("FilmInput", "title"),
            simpleColumnField("FilmInput", "title"));
        var view = registry.entries();
        var newField = simpleColumnField("FilmInput", "year");
        try {
            view.put(coords("FilmInput", "year"), newField);
            throw new AssertionError("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }
}
