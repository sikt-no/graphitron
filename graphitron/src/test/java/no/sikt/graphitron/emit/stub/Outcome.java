package no.sikt.graphitron.emit.stub;

import java.util.List;

/** The generated outcome a claimed error is carried in, beside the success it replaces. */
public interface Outcome<T> {

    final class Success<T> implements Outcome<T> {
        private final T value;

        public Success(T value) {
            this.value = value;
        }

        @Override
        public String toString() {
            return "Success(" + value + ")";
        }
    }

    final class ErrorList<T> implements Outcome<T> {
        private final List<Throwable> errors;

        public ErrorList(List<Throwable> errors) {
            this.errors = errors;
        }

        @Override
        public String toString() {
            return "ErrorList(" + errors + ")";
        }
    }
}
