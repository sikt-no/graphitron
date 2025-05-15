package no.sikt.graphitron.configuration;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;

public class ExceptionToErrorMapping {

    private final ErrorHandlerType handler;
    private final String exceptionClassName;
    private final String errorTypeName;
    private final String databaseErrorCode;
    private final String exceptionMessageContains;
    private final String errorDescription;

    public ExceptionToErrorMapping(ErrorHandlerType handler, String exceptionClassName, String errorTypeName, String databaseErrorCode,
                                   @Nullable String exceptionMessageContains, @Nullable String errorDescription) {
        this.handler = handler;
        this.exceptionClassName = exceptionClassName;
        this.errorTypeName = errorTypeName;
        this.databaseErrorCode = databaseErrorCode;
        this.exceptionMessageContains = exceptionMessageContains;
        this.errorDescription = errorDescription;
    }

    public String getExceptionClassName() {
        return exceptionClassName;
    }

    public String getErrorTypeName() {
        return errorTypeName;
    }

    public String getExceptionMessageContains() {
        return exceptionMessageContains;
    }

    public String getDatabaseErrorCode() {
        return databaseErrorCode;
    }

    public Optional<String> getErrorDescription() {
        return Optional.ofNullable(errorDescription);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ExceptionToErrorMapping that = (ExceptionToErrorMapping) o;
        return Objects.equals(exceptionClassName, that.exceptionClassName) && Objects.equals(errorTypeName, that.errorTypeName) && Objects.equals(databaseErrorCode, that.databaseErrorCode) && Objects.equals(exceptionMessageContains, that.exceptionMessageContains) && Objects.equals(errorDescription, that.errorDescription);
    }

    @Override
    public int hashCode() {
        return Objects.hash(exceptionClassName, errorTypeName, databaseErrorCode, exceptionMessageContains, errorDescription);
    }

    public ErrorHandlerType getHandler() {
        return handler;
    }
}
