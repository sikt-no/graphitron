package no.sikt.graphql.helpers.resolvers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ArgumentPresenceTest {

    @Nested
    @DisplayName("build")
    class BuildTest {

        @Test
        @DisplayName("list input with per-element fields")
        void listInputWithPerElementFields() {
            Map<String, Object> arguments = Map.of(
                    "in", List.of(
                            Map.of("id", "123", "name", "test"),
                            Map.of("id", "456", "active", true)
                    )
            );

            var presence = ArgumentPresence.build(arguments);

            assertThat(presence.hasField("in")).isTrue();
            assertThat(presence.child("in").itemAt(0).hasField("id")).isTrue();
            assertThat(presence.child("in").itemAt(0).hasField("name")).isTrue();
            assertThat(presence.child("in").itemAt(0).hasField("active")).isFalse();
            assertThat(presence.child("in").itemAt(1).hasField("id")).isTrue();
            assertThat(presence.child("in").itemAt(1).hasField("active")).isTrue();
            assertThat(presence.child("in").itemAt(1).hasField("name")).isFalse();
        }

        @Test
        @DisplayName("single non-list scalar argument")
        void singleNonListArgument() {
            Map<String, Object> arguments = Map.of("title", "hello");

            var presence = ArgumentPresence.build(arguments);

            assertThat(presence.hasField("title")).isTrue();
            assertThat(presence.child("title").isEmpty()).isTrue();
        }

        @Test
        @DisplayName("nested map inside list elements")
        void nestedMapInsideListElements() {
            Map<String, Object> arguments = Map.of(
                    "in", List.of(
                            Map.of("address", Map.of("city", "Oslo", "zip", "0123")),
                            Map.of("address", Map.of("city", "Bergen"))
                    )
            );

            var presence = ArgumentPresence.build(arguments);

            assertThat(presence.child("in").itemAt(0).child("address").hasField("city")).isTrue();
            assertThat(presence.child("in").itemAt(0).child("address").hasField("zip")).isTrue();
            assertThat(presence.child("in").itemAt(1).child("address").hasField("city")).isTrue();
            assertThat(presence.child("in").itemAt(1).child("address").hasField("zip")).isFalse();
        }

        @Test
        @DisplayName("single map argument (not in a list)")
        void singleMapArgument() {
            Map<String, Object> arguments = Map.of(
                    "in", Map.of("id", "123", "name", "test")
            );

            var presence = ArgumentPresence.build(arguments);

            assertThat(presence.hasField("in")).isTrue();
            assertThat(presence.child("in").hasField("id")).isTrue();
            assertThat(presence.child("in").hasField("name")).isTrue();
        }

        @Test
        @DisplayName("explicit null is present, absent field is not")
        void explicitNullVsAbsent() {
            var element0 = new HashMap<String, Object>();
            element0.put("id", "123");
            element0.put("name", null);

            var element1 = new HashMap<String, Object>();
            element1.put("id", "456");

            Map<String, Object> arguments = Map.of("in", List.of(element0, element1));

            var presence = ArgumentPresence.build(arguments);

            assertThat(presence.child("in").itemAt(0).hasField("id")).isTrue();
            assertThat(presence.child("in").itemAt(0).hasField("name")).isTrue();
            assertThat(presence.child("in").itemAt(1).hasField("id")).isTrue();
            assertThat(presence.child("in").itemAt(1).hasField("name")).isFalse();
        }

        @Test
        @DisplayName("deeply nested: list → object → list → object")
        void deeplyNested() {
            Map<String, Object> arguments = Map.of(
                    "in", List.of(
                            Map.of("films", List.of(
                                    Map.of("title", "Film A", "rentalDuration", 3),
                                    Map.of("title", "Film B")
                            ))
                    )
            );

            var presence = ArgumentPresence.build(arguments);

            assertThat(presence.child("in").itemAt(0).child("films").itemAt(0).hasField("title")).isTrue();
            assertThat(presence.child("in").itemAt(0).child("films").itemAt(0).hasField("rentalDuration")).isTrue();
            assertThat(presence.child("in").itemAt(0).child("films").itemAt(1).hasField("title")).isTrue();
            assertThat(presence.child("in").itemAt(0).child("films").itemAt(1).hasField("rentalDuration")).isFalse();
        }
    }

    @Nested
    @DisplayName("navigation")
    class NavigationTest {

        @Test
        @DisplayName("EMPTY returns EMPTY for all navigation and false for hasField")
        void emptyBehavior() {
            assertThat(ArgumentPresence.EMPTY.hasField("anything")).isFalse();
            assertThat(ArgumentPresence.EMPTY.child("anything")).isSameAs(ArgumentPresence.EMPTY);
            assertThat(ArgumentPresence.EMPTY.itemAt(0)).isSameAs(ArgumentPresence.EMPTY);
            assertThat(ArgumentPresence.EMPTY.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("missing child returns EMPTY")
        void missingChildReturnsEmpty() {
            var presence = ArgumentPresence.build(Map.of("a", "value"));

            assertThat(presence.child("missing")).isSameAs(ArgumentPresence.EMPTY);
            assertThat(presence.child("missing").hasField("x")).isFalse();
        }

        @Test
        @DisplayName("out of range index returns EMPTY")
        void outOfRangeIndexReturnsEmpty() {
            var presence = ArgumentPresence.build(Map.of("in", List.of(Map.of("id", "1"))));

            assertThat(presence.child("in").itemAt(99).hasField("id")).isFalse();
        }
    }

    @Nested
    @DisplayName("asSingleItem")
    class AsSingleItemTest {

        @Test
        @DisplayName("wraps this node so itemAt(0) returns it")
        void wrapsAsSingleItem() {
            var presence = ArgumentPresence.build(Map.of("id", "123", "name", "test"));

            var wrapped = presence.asSingleItem();

            assertThat(wrapped.itemAt(0).hasField("id")).isTrue();
            assertThat(wrapped.itemAt(0).hasField("name")).isTrue();
            assertThat(wrapped.itemAt(1).hasField("id")).isFalse();
        }

        @Test
        @DisplayName("single-item wrapping of a non-list input mirrors List.of() pattern")
        void singleItemMirrorsListOfPattern() {
            // Simulates: input is a single map {title: "X", rentalDuration: null}
            // RecordTransformer wraps in List.of() and calls asSingleItem()
            var element = new HashMap<String, Object>();
            element.put("title", "X");
            element.put("rentalDuration", null);

            var presence = ArgumentPresence.build(Map.of("in", element));
            var inPresence = presence.child("in");

            // The single-item overload wraps: asSingleItem()
            var wrapped = inPresence.asSingleItem();

            // Mapper iterates and calls itemAt(0):
            var item0 = wrapped.itemAt(0);
            assertThat(item0.hasField("title")).isTrue();
            assertThat(item0.hasField("rentalDuration")).isTrue();
        }
    }

    @Nested
    @DisplayName("end-to-end")
    class EndToEndTest {

        @Test
        @DisplayName("explicit null vs absent in list mutation input")
        void explicitNullVsAbsentInListMutation() {
            var element0 = new HashMap<String, Object>();
            element0.put("title", "Film A");
            element0.put("rentalDuration", null);

            var element1 = new HashMap<String, Object>();
            element1.put("title", "Film B");

            Map<String, Object> arguments = Map.of("in", List.of(element0, element1));

            var presence = ArgumentPresence.build(arguments);
            var inPresence = presence.child("in");

            var args0 = inPresence.itemAt(0);
            assertThat(args0.hasField("title")).isTrue();
            assertThat(args0.hasField("rentalDuration")).isTrue();

            var args1 = inPresence.itemAt(1);
            assertThat(args1.hasField("title")).isTrue();
            assertThat(args1.hasField("rentalDuration")).isFalse();
        }

        @Test
        @DisplayName("nested non-list input inside a list item")
        void nestedNonListInsideListItem() {
            // Simulates: in: [{fornavn: "Ola", folkeregistrertAdresse: {gate: "Gata 1", postnummer: "0123"}}]
            Map<String, Object> arguments = Map.of(
                    "input", List.of(
                            Map.of(
                                    "fornavn", "Ola",
                                    "folkeregistrertAdresse", Map.of("gate", "Gata 1", "postnummer", "0123")
                            )
                    )
            );

            var presence = ArgumentPresence.build(arguments);
            var item0 = presence.child("input").itemAt(0);

            assertThat(item0.hasField("fornavn")).isTrue();
            assertThat(item0.hasField("folkeregistrertAdresse")).isTrue();

            // Nested mapper receives child("folkeregistrertAdresse")
            var addrPresence = item0.child("folkeregistrertAdresse");

            // Single-item overload wraps with asSingleItem()
            var wrapped = addrPresence.asSingleItem();
            var addrItem = wrapped.itemAt(0);

            assertThat(addrItem.hasField("gate")).isTrue();
            assertThat(addrItem.hasField("postnummer")).isTrue();
        }
    }
}
