package no.sikt.graphitron.definitions.sql;

/**
 * Enum of supported SQL join types.
 */
public enum SQLJoinType {
    DEFAULT, // Default should be "JOIN".
    JOIN,
    LEFT,
    // RIGHT, // Not supported yet.
}
