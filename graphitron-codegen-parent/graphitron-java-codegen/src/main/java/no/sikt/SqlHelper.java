package no.sikt;

import java.io.File;
import java.io.FileInputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * This class contains deliberate vulnerabilities for CodeQL testing purposes.
 * DO NOT USE IN PRODUCTION CODE.
 */
public class SqlHelper {

    /**
     * Deliberately vulnerable method to test CodeQL detection.
     *
     * @param userInput Unsanitized user input
     * @param connection Database connection
     * @throws SQLException if a database error occurs
     */
    public void executeQuery(String userInput, Connection connection) throws SQLException {
        // VULNERABILITY: Direct use of user input in SQL query (more explicit)
        String query = "SELECT * FROM users WHERE id = " + userInput;

        Statement statement = connection.createStatement();
        statement.executeQuery(query);
    }

    /**
     * Deliberately vulnerable method with path traversal vulnerability.
     *
     * @param filePath User-provided file path
     * @return Always returns 0
     */
    public int readFile(String filePath) {
        try {
            // VULNERABILITY: Path traversal through direct file access
            File file = new File(filePath);
            FileInputStream fis = new FileInputStream(file);
            fis.close();
        } catch (Exception e) {
            // Intentionally ignoring exceptions
        }
        return 0;
    }
}