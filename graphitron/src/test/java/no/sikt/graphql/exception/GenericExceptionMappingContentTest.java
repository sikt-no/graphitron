package no.sikt.graphql.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericExceptionMappingContentTest {

    private GenericExceptionMappingContent underTest;

    @BeforeEach
    void setUp() {
        underTest = new GenericExceptionMappingContent("java.lang.RuntimeException", "test message");
    }

    @Test
    @DisplayName("matches returns true when exception class and message match")
    void matchesReturnsTrueWhenExceptionClassAndMessageMatch() {
        Throwable throwable = new RuntimeException("test message");

        assertTrue(underTest.matches(throwable));
    }

    @Test
    @DisplayName("matches returns false when exception class does not match")
    void matchesReturnsFalseWhenExceptionClassDoesNotMatch() {
        Throwable throwable = new Exception("test message");

        assertFalse(underTest.matches(throwable));
    }

    @Test
    @DisplayName("matches returns false when exception message does not match")
    void matchesReturnsFalseWhenExceptionMessageDoesNotMatch() {
        Throwable throwable = new RuntimeException("different message");

        assertFalse(underTest.matches(throwable));
    }

    @Test
    @DisplayName("matches returns true when exception class and message match in cause")
    void matchesReturnsTrueWhenExceptionClassAndMessageMatchInCause() {
        Throwable cause = new RuntimeException("test message");
        Throwable throwable = new Exception("different message", cause);

        assertTrue(underTest.matches(throwable));
    }

    @Test
    @DisplayName("matches returns false when no exception message is provided")
    void matchesReturnsFalseWhenNoExceptionMessageIsProvided() {
        Throwable throwable = new RuntimeException();

        assertFalse(underTest.matches(throwable));
    }
}