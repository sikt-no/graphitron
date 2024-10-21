package no.fellesstudentsystem.graphql.exception;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

public class GenericExceptionMappingContent {
    private final String substringOfExceptionMessage;
    private final String fullyQualifiedClassName;

    public GenericExceptionMappingContent(String fullyQualifiedClassName, String substringOfExceptionMessage) {
        this.substringOfExceptionMessage = substringOfExceptionMessage;
        this.fullyQualifiedClassName = fullyQualifiedClassName;
    }

    boolean matches(Throwable throwable) {
        return streamCauses(throwable)
                .anyMatch(it -> it.getClass().getName().equals(fullyQualifiedClassName) && messageMatches(it.getMessage()));
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


