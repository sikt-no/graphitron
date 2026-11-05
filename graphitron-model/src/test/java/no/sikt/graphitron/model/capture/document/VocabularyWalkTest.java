package no.sikt.graphitron.model.capture.document;

import graphql.language.Directive;
import graphql.language.ObjectTypeDefinition;
import graphql.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VocabularyWalk}'s trail: each value is handed over with the names it was written under,
 * so a reference inside a field's path is told apart from one on the field, and from the same
 * path's in another field.
 */
class VocabularyWalkTest {

    private static Directive applicationOf(String sdl) {
        var document = Parser.parse(sdl);
        var type = (ObjectTypeDefinition) document.getDefinitions().getFirst();
        return type.getFieldDefinitions().getFirst().getDirectives().getFirst();
    }

    @Test
    void aConditionInAnOrderFieldsPathCarriesBothIndices() {
        var application = applicationOf("""
            type Query {
              films: [Int] @defaultOrder(fields: [
                {method: {className: "a.Keys", method: "first"}},
                {name: "name", path: [{key: "k"}, {condition: {className: "a.Conditions", method: "hop"}}]}
              ])
            }
            """);
        var seen = new ArrayList<String>();
        VocabularyWalk.walk(application, (trail, value, declared) -> {
            if (declared.getName().equals("ExternalCodeReference")) {
                seen.add(trail.argument() + " " + trail.argumentIndex() + " " + trail.pathIndex()
                    + " " + trail.name());
            }
        });
        assertThat(seen).containsExactly("fields 0 null method", "fields 1 1 condition");
    }

    @Test
    void aRootReferenceHasNoIndex() {
        var application = applicationOf("""
            type Query {
              films: [Int] @service(service: {className: "a.Service", method: "films"})
            }
            """);
        var seen = new ArrayList<String>();
        VocabularyWalk.walk(application, (trail, value, declared) -> {
            if (declared.getName().equals("ExternalCodeReference")) {
                seen.add(trail.argument() + " " + trail.argumentIndex() + " " + trail.pathIndex());
            }
        });
        assertThat(seen).isEqualTo(List.of("service null null"));
    }
}
