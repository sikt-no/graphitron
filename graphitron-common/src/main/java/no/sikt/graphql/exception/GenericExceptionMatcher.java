package no.sikt.graphql.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Generic matcher for exceptions that checks class type and message content.
 * Can match against the full exception cause chain.
 */
public class GenericExceptionMatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(GenericExceptionMatcher.class);

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
                        return Class.forName(fullyQualifiedClassName, true, Thread.currentThread().getContextClassLoader())
                                .isInstance(it) && messageMatches(it.getMessage());
                    } catch (ClassNotFoundException e) {
                        LOGGER.warn("Could not find exception class '{}'. Ensure the class is on the classpath.", fullyQualifiedClassName);
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