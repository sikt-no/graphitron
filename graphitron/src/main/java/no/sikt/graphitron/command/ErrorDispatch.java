package no.sikt.graphitron.command;

import java.util.Objects;

/**
 * How one fetcher entry point disposes of a throw: the {@code catch} arm's whole content, as
 * command data. Two arms, because two dispositions exist for the families on the seam, and each
 * names the generated units it calls rather than a package plus a class-name formula.
 *
 * <p>The arms are not the classifier's error-channel taxonomy and deliberately do not mirror it.
 * A channel carries the resolved {@code @error} types and the payload class the mappings were
 * built from, which is a classification fact; what a catch arm emits from it is the mappings
 * constant's name and nothing else. Restating the constant name here keeps the emit vocabulary
 * to plain data and keeps the fact hierarchy out of the renderers: borrowing the channel whole
 * would admit the resolved error types, and through them every arm of the type hierarchy, into
 * the surface {@code PackageImportDirectionTest} pins.
 */
public sealed interface ErrorDispatch permits ErrorDispatch.Redacting, ErrorDispatch.LocalContextRouted {

    /** The generated error router both arms route the throw through. */
    UnitRef errorRouter();

    /**
     * No typed-error channel: the throw goes through the router's privacy disposition, which
     * surfaces a client exception's own message and redacts everything else to a correlation id.
     */
    record Redacting(UnitRef errorRouter) implements ErrorDispatch {
        public Redacting {
            Objects.requireNonNull(errorRouter, "errorRouter");
        }
    }

    /**
     * A channel that hands the matched throwable back as graphql-java {@code localContext}: the
     * payload's errors field reads it from there, and the data side is short-circuited by the
     * non-null sentinel the renderer composes from the row's own key columns.
     *
     * <p>{@code mappingsConstantName} is the {@code Mapping[]} constant on the generated
     * mappings class holding this channel's dispatch table, deduped at classify time. It rides
     * as a name because that is all the arm emits.
     */
    record LocalContextRouted(UnitRef errorRouter, UnitRef errorMappings,
                              String mappingsConstantName) implements ErrorDispatch {
        public LocalContextRouted {
            Objects.requireNonNull(errorRouter, "errorRouter");
            Objects.requireNonNull(errorMappings, "errorMappings");
            if (mappingsConstantName == null || mappingsConstantName.isBlank()) {
                throw new IllegalArgumentException(
                    "a routed dispatch names the mappings constant it dispatches on; a blank name"
                    + " would emit a reference to nothing");
            }
        }
    }
}
