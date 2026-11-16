package no.sikt.graphitron.rewrite.test.services;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Services declaring checked exceptions, refused as {@code CHECKED_EXCEPTION_UNHANDLED} where no
 * handler of their field's outcome covers what they declare. Never called.
 */
public final class FilmThrowingService {

    private FilmThrowingService() {}

    public static String titleOrSql(Integer id) throws SQLException {
        return null;
    }

    public static String titleOrChecked(Integer id) throws FilmLookupCheckedException {
        return null;
    }

    public static String titleOrIo(Integer id) throws IOException {
        return null;
    }

    public static String titleOrRuntime(Integer id) throws IllegalStateException {
        return null;
    }
}
