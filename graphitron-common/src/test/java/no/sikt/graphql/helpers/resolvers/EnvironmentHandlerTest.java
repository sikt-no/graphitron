package no.sikt.graphql.helpers.resolvers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static no.sikt.graphql.helpers.resolvers.EnvironmentHandler.*;
import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentHandlerTest {

    @Test
    @DisplayName("flattens simple arguments")
    void listedInputArguments() {
        Map<String, Object> arguments = Map.of(
                "in",
                List.of(
                        Map.of(
                                "id", "123",
                                "name", "test"
                        ),
                        Map.of(
                                "id", "123",
                                "active", true
                        )
                )
        );

        assertThat(flattenArgumentKeys(arguments, ""))
                .containsExactlyInAnyOrder("in", "in/id", "in/name", "in/active");
    }

    @Nested
    @DisplayName("flattenIndexedArgumentKeys")
    class FlattenIndexedArgumentKeysTest {

        @Test
        @DisplayName("produces per-element indexed keys for list input")
        void indexedKeysForListInput() {
            Map<String, Object> arguments = Map.of(
                    "in",
                    List.of(
                            Map.of("id", "123", "name", "test"),
                            Map.of("id", "456", "active", true)
                    )
            );

            assertThat(flattenIndexedArgumentKeys(arguments, ""))
                    .containsExactlyInAnyOrder(
                            "in",
                            "in[0]/id", "in[0]/name",
                            "in[1]/id", "in[1]/active"
                    );
        }

        @Test
        @DisplayName("handles single non-list argument")
        void singleNonListArgument() {
            Map<String, Object> arguments = Map.of("title", "hello");

            assertThat(flattenIndexedArgumentKeys(arguments, ""))
                    .containsExactlyInAnyOrder("title");
        }

        @Test
        @DisplayName("handles nested map inside list elements")
        void nestedMapInsideListElements() {
            Map<String, Object> arguments = Map.of(
                    "in",
                    List.of(
                            Map.of("address", Map.of("city", "Oslo", "zip", "0123")),
                            Map.of("address", Map.of("city", "Bergen"))
                    )
            );

            assertThat(flattenIndexedArgumentKeys(arguments, ""))
                    .containsExactlyInAnyOrder(
                            "in",
                            "in[0]/address", "in[0]/address/city", "in[0]/address/zip",
                            "in[1]/address", "in[1]/address/city"
                    );
        }

        @Test
        @DisplayName("handles single map argument (not in a list)")
        void singleMapArgument() {
            Map<String, Object> arguments = Map.of(
                    "in", Map.of("id", "123", "name", "test")
            );

            assertThat(flattenIndexedArgumentKeys(arguments, ""))
                    .containsExactlyInAnyOrder("in", "in/id", "in/name");
        }

        @Test
        @DisplayName("includes key for element with explicit null value")
        void explicitNullInListElement() {
            var element0 = new HashMap<String, Object>();
            element0.put("id", "123");
            element0.put("name", null);

            var element1 = new HashMap<String, Object>();
            element1.put("id", "456");

            Map<String, Object> arguments = Map.of("in", List.of(element0, element1));

            var result = flattenIndexedArgumentKeys(arguments, "");

            assertThat(result).contains("in[0]/id", "in[0]/name", "in[1]/id");
            assertThat(result).doesNotContain("in[1]/name");
        }
    }

    @Nested
    @DisplayName("getArgumentsForIndex")
    class GetArgumentsForIndexTest {

        @Test
        @DisplayName("returns per-element keys for the requested index")
        void returnsPerElementKeys() {
            var indexedArgs = Set.of(
                    "in",
                    "in[0]/id", "in[0]/name",
                    "in[1]/id", "in[1]/active"
            );
            var sharedArgs = Set.of("in", "in/id", "in/name", "in/active");

            assertThat(getArgumentsForIndex(indexedArgs, sharedArgs, "in", 0))
                    .containsExactlyInAnyOrder("in/id", "in/name");

            assertThat(getArgumentsForIndex(indexedArgs, sharedArgs, "in", 1))
                    .containsExactlyInAnyOrder("in/id", "in/active");
        }

        @Test
        @DisplayName("element with explicit null has the key, element without does not")
        void explicitNullVsAbsent() {
            // Element 0: {id: "123", rentalDuration: null}  -> rentalDuration explicitly set
            // Element 1: {id: "456"}                        -> rentalDuration absent
            var indexedArgs = Set.of(
                    "in",
                    "in[0]/id", "in[0]/rentalDuration",
                    "in[1]/id"
            );
            var sharedArgs = Set.of("in", "in/id", "in/rentalDuration");

            var args0 = getArgumentsForIndex(indexedArgs, sharedArgs, "in", 0);
            assertThat(args0).contains("in/rentalDuration");

            var args1 = getArgumentsForIndex(indexedArgs, sharedArgs, "in", 1);
            assertThat(args1).doesNotContain("in/rentalDuration");
        }

        @Test
        @DisplayName("falls back to shared args when no indexed entries exist (single-item case)")
        void fallbackToSharedArgs() {
            // Single-item case: input is not a list, so no indexed entries
            var indexedArgs = Set.of("in", "in/id", "in/name");
            var sharedArgs = Set.of("in", "in/id", "in/name");

            var result = getArgumentsForIndex(indexedArgs, sharedArgs, "in", 0);

            assertThat(result).isSameAs(sharedArgs);
        }

        @Test
        @DisplayName("handles nested fields within list elements")
        void nestedFieldsInListElements() {
            var indexedArgs = Set.of(
                    "in",
                    "in[0]/address", "in[0]/address/city", "in[0]/address/zip",
                    "in[1]/address", "in[1]/address/city"
            );
            var sharedArgs = Set.of("in", "in/address", "in/address/city", "in/address/zip");

            assertThat(getArgumentsForIndex(indexedArgs, sharedArgs, "in", 0))
                    .containsExactlyInAnyOrder("in/address", "in/address/city", "in/address/zip");

            assertThat(getArgumentsForIndex(indexedArgs, sharedArgs, "in", 1))
                    .containsExactlyInAnyOrder("in/address", "in/address/city");
        }

        @Test
        @DisplayName("handles empty path prefix")
        void emptyPathPrefix() {
            var indexedArgs = Set.of("[0]/id", "[0]/name", "[1]/id");
            var sharedArgs = Set.of("id", "name");

            assertThat(getArgumentsForIndex(indexedArgs, sharedArgs, "", 0))
                    .containsExactlyInAnyOrder("id", "name");

            assertThat(getArgumentsForIndex(indexedArgs, sharedArgs, "", 1))
                    .containsExactlyInAnyOrder("id");
        }

        @Test
        @DisplayName("index out of range falls back to shared args")
        void outOfRangeIndexFallsBackToSharedArgs() {
            var indexedArgs = Set.of("in", "in[0]/id");
            var sharedArgs = Set.of("in", "in/id");

            var result = getArgumentsForIndex(indexedArgs, sharedArgs, "in", 5);

            assertThat(result).isSameAs(sharedArgs);
        }

        @Test
        @DisplayName("end-to-end: flattenIndexedArgumentKeys feeds getArgumentsForIndex correctly")
        void endToEndWithFlatten() {
            var element0 = new HashMap<String, Object>();
            element0.put("title", "Film A");
            element0.put("rentalDuration", null);

            var element1 = new HashMap<String, Object>();
            element1.put("title", "Film B");

            Map<String, Object> arguments = Map.of("in", List.of(element0, element1));

            var indexedArgs = flattenIndexedArgumentKeys(arguments, "");
            var sharedArgs = flattenArgumentKeys(arguments, "");

            var args0 = getArgumentsForIndex(indexedArgs, sharedArgs, "in", 0);
            assertThat(args0).containsExactlyInAnyOrder("in/title", "in/rentalDuration");

            var args1 = getArgumentsForIndex(indexedArgs, sharedArgs, "in", 1);
            assertThat(args1).containsExactlyInAnyOrder("in/title");
            assertThat(args1).doesNotContain("in/rentalDuration");
        }
    }
}
