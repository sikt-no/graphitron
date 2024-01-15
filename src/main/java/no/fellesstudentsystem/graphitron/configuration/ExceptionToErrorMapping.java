package no.fellesstudentsystem.graphitron.configuration;

import org.apache.maven.plugin.MojoExecutionException;

import javax.annotation.Nullable;
import java.util.Optional;

public class ExceptionToErrorMapping {

    private String mutationName;
    private String errorTypeName;
    private String databaseErrorCode;
    private String sqlStateClassCode;
    private String exceptionMessageContains;
    private String errorDescription;

    public ExceptionToErrorMapping() {}

    public ExceptionToErrorMapping(String mutationName, String errorTypeName, String databaseErrorCode, @Nullable String sqlStateClassCode, @Nullable String exceptionMessageContains, @Nullable String errorDescription) {
        this.mutationName = mutationName;
        this.errorTypeName = errorTypeName;
        this.databaseErrorCode = databaseErrorCode;
        this.sqlStateClassCode = sqlStateClassCode;
        this.exceptionMessageContains = exceptionMessageContains;
        this.errorDescription = errorDescription;
    }

    public void validate() throws MojoExecutionException {
        if (mutationName == null || errorTypeName == null || databaseErrorCode == null) {
            throw new MojoExecutionException("'mutationName', 'errorTypeName', 'databaseErrorCode' fields are required in 'ExceptionToErrorMapping'");
        }
    }

    public String getMutationName() {
        return mutationName;
    }

    public String getErrorTypeName() {
        return errorTypeName;
    }

    public String getExceptionMessageContains() {
        return exceptionMessageContains;
    }

    public Optional<String> getSqlStateClassCode() {
        return Optional.ofNullable(sqlStateClassCode);
    }

    public String getDatabaseErrorCode() {
        return databaseErrorCode;
    }

    public Optional<String> getErrorDescription() {
        return Optional.ofNullable(errorDescription);
    }
}
