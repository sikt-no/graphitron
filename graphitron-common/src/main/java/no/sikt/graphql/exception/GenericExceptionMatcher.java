package no.sikt.graphql.exception;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Generic matcher for exceptions that checks class type and message content.
 * Can match against the full exception cause chain.
 */
public class GenericExceptionMatcher {
    private final String substringOfExceptionMessage;
    private final String fullyQualifiedClassName;

    public GenericExceptionMatcher(String fullyQualifiedClassName, String substringOfExceptionMessage) {
        this.substringOfExceptionMessage = substringOfExceptionMessage;
        this.fullyQualifiedClassName = fullyQualifiedClassName;
    }

    boolean matches(Throwable throwable) {
        return streamCauses(throwable)
                .anyMatch(it -> {
                    try {
                        return Class.forName(fullyQualifiedClassName).isInstance(it) && messageMatches(it.getMessage());
                    } catch (ClassNotFoundException e) {
                        return false;
                    }
                });
    }

    private Stream<Throwable> streamCauses(Throwable throwable) {
        return Stream.iterate(throwable, Objects::nonNull, Throwable::getCause);
    }

    private boolean messageMatches(String actualExceptionMsg) {
        return Optional.ofNullable(this.substringOfExceptionMessage)
                .map(it -> actualExceptionMsg != null && actualExceptionMsg.contains(it))
                .orElse(true);
    }
}