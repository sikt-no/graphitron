package no.sikt.graphql.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class GenericExceptionMatcherTest {

    private GenericExceptionMatcher underTest;

    @BeforeEach
    void setUp() {
        underTest = new GenericExceptionMatcher("java.lang.IllegalStateException", "test message");
    }

    @Test
    @DisplayName("matches returns true when exception class and message match")
    void matchesClassAndMessage() {
        Throwable throwable = new IllegalStateException("test message");

        assertTrue(underTest.matches(throwable));
    }

    @Test
    @DisplayName("matches returns true when exception class and message match as substring of the exception message")
    void matchesClassAndMessageSubstring() {
        Throwable throwable = new IllegalStateException("some message that contains test message bla bla");

        assertTrue(underTest.matches(throwable));
    }

    @Test
    @DisplayName("matches returns false when exception class does not match")
    void notMatchesWrongClass() {
        Throwable throwable = new IllegalArgumentException("test message");

        assertFalse(underTest.matches(throwable));
    }

    @Test
    @DisplayName("matches returns false when exception message does not match")
    void notMatchesWrongMessage() {
        Throwable throwable = new IllegalStateException("different message");

        assertFalse(underTest.matches(throwable));
    }

    @Test
    @DisplayName("matches returns true when exception class and message match in cause")
    void matchesInCause() {
        Throwable cause = new IllegalStateException("test message");
        Throwable throwable = new IllegalArgumentException("different message", cause);

        assertTrue(underTest.matches(throwable));
    }

    @Test
    @DisplayName("matches returns true when exception class matches via instanceOf")
    void matchesSubclass() {
        Throwable throwable = new CancellationException("test message"); //CancellationException is a subclass of IllegalStateException

        assertTrue(underTest.matches(throwable));
    }

    @Test
    @DisplayName("matches returns false when exception class is superclass of the provided class")
    void notMatchesSuperclass() {
        Throwable throwable = new RuntimeException("test message"); //RuntimeException is a superclass of IllegalStateException

        assertFalse(underTest.matches(throwable));
    }

    @Test
    @DisplayName("matches returns false when no exception message is provided")
    void notMatchesNullMessage() {
        Throwable throwable = new IllegalStateException();

        assertFalse(underTest.matches(throwable));
    }
}