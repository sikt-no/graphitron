package no.sikt.graphitron.rewrite.derive;

import java.util.List;

/** The class the shadow's producer delivers: one plain member, one hop, one hop through a list. */
public record TestBackingFilm(String title, TestBackingLanguage language,
                              List<TestBackingActor> actors) {
}
