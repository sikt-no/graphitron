package no.sikt.graphitron.rewrite.model;

/**
 * A graphitron-internal collapse of jOOQ's {@code SQLDialect} enum values into the small set of
 * dialect families the generator gates DML on. Used by {@link DialectRequirement} to name the
 * family a DML verb requires or rejects.
 *
 * <p>This is a graphitron enum rather than jOOQ's {@code SQLDialect} because the OSS jOOQ
 * distribution omits commercial-only dialect enum values (e.g. {@code ORACLE12C},
 * {@code ORACLE19C}, {@code POSTGRESPLUS}); a compile-time reference to those constants would not
 * resolve.
 *
 * <p>Two boundary mappings live here. {@link #fromDialectName(String)} maps a
 * {@code SQLDialect.name()} string to a family (for the generator / a future validator that reads
 * the model). {@link #jooqFamilyName()} maps a family to the {@code SQLDialect.family().name()}
 * string the emitter compares against at request time. The emitter does not reference this enum in
 * generated code: the {@code graphitron} artifact is not on a consumer's runtime classpath, so the
 * emitted guard is a self-contained jOOQ {@code family()} comparison (see
 * {@code TypeFetcherGenerator.emitDialectGuard}).
 */
public enum SqlDialectFamily {
    POSTGRES, ORACLE, MYSQL, MSSQL, H2, SQLITE, OTHER;

    /**
     * Maps a jOOQ {@code SQLDialect.name()} to a graphitron dialect family. Name-prefix-based to
     * cover commercial-only dialect enum values that aren't present in the OSS jOOQ distribution
     * (e.g. {@code ORACLE12C}, {@code ORACLE19C}).
     *
     * <p>Mirrors jOOQ's own {@code SQLDialect.family()} collapse: {@code POSTGRES}, the
     * {@code POSTGRESPLUS} spelling, and {@code YUGABYTEDB} all fold to {@code POSTGRES} (the same
     * membership {@code SQLDialect.family()} produces in jOOQ 3.20.11). {@code SQLSERVER*} folds to
     * {@code MSSQL} and {@code MARIADB} keeps its own {@code SQLDialect} family, so it is not
     * collapsed into {@code MYSQL}. Everything unrecognised (including {@code DEFAULT},
     * {@code DERBY}, {@code HSQLDB}, {@code FIREBIRD}, {@code REDSHIFT}) is {@link #OTHER}.
     *
     * <p>A {@code null} or blank name maps to {@link #OTHER}. Matching is case-insensitive on the
     * uppercased name so a lowercased {@code SQLDialect.name()} spelling is still classified.
     *
     * @param name the jOOQ {@code SQLDialect.name()} string, e.g. {@code "POSTGRES"} or
     *             {@code "ORACLE19C"}
     * @return the graphitron dialect family the name belongs to
     */
    public static SqlDialectFamily fromDialectName(String name) {
        if (name == null || name.isBlank()) {
            return OTHER;
        }
        var n = name.toUpperCase(java.util.Locale.ROOT);
        if (n.startsWith("POSTGRES") || n.equals("YUGABYTEDB")) {
            return POSTGRES;
        }
        if (n.startsWith("ORACLE")) {
            return ORACLE;
        }
        if (n.startsWith("MYSQL")) {
            return MYSQL;
        }
        if (n.startsWith("SQLSERVER")) {
            return MSSQL;
        }
        if (n.startsWith("H2")) {
            return H2;
        }
        if (n.startsWith("SQLITE")) {
            return SQLITE;
        }
        return OTHER;
    }

    /**
     * The string {@code dsl.dialect().family().name()} reports for a runtime dialect in this
     * family. Used by the emitter to render the request-time dialect guard as a self-contained
     * jOOQ-only check: generated code cannot reference this generator-internal enum, because the
     * {@code graphitron} artifact is not on a consumer's runtime classpath (only its generated
     * output package plus jOOQ are). jOOQ's {@code SQLDialect.family()} collapses every versioned
     * spelling onto its family constant, so comparing against this name gates every version of the
     * family (e.g. {@code ORACLE19C.family().name()} is {@code "ORACLE"}).
     *
     * <p>The name equals {@link #name()} for most families but diverges where jOOQ's family
     * spelling differs: {@link #MSSQL} maps to jOOQ's {@code SQLSERVER}. {@link #OTHER} has no
     * single family to gate on and rejects, since a {@code DialectRequirement} must name a concrete
     * family.
     */
    public String jooqFamilyName() {
        return switch (this) {
            case POSTGRES -> "POSTGRES";
            case ORACLE   -> "ORACLE";
            case MYSQL    -> "MYSQL";
            case MSSQL    -> "SQLSERVER";
            case H2       -> "H2";
            case SQLITE   -> "SQLITE";
            case OTHER    -> throw new IllegalStateException(
                "SqlDialectFamily.OTHER has no jOOQ family name to gate a DialectRequirement on; "
                + "a guard must name a concrete family");
        };
    }
}
