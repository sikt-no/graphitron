package no.fellesstudentsystem.graphql.exception;

import org.jooq.exception.SQLStateClass;

public class DataAccessExceptionMappingContent {
    private final SQLStateClass sqlStateClass;
    private final int errorCode;
    private final String description;

    public DataAccessExceptionMappingContent(SQLStateClass sqlStateClass, int errorCode, String description) {
        this.sqlStateClass = sqlStateClass;
        this.errorCode = errorCode;
        this.description = description;
    }

    public boolean matches(DataAccessExceptionMappingContent other) {
        return this.sqlStateClass == other.sqlStateClass && this.errorCode == other.errorCode && this.description.contains(other.description);
    }
}
