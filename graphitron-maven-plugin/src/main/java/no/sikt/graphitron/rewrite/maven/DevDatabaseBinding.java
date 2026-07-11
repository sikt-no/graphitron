package no.sikt.graphitron.rewrite.maven;

/**
 * POM XML binding for the {@code <devDatabase>} block (R428): the dev database the
 * {@code graphitron:dev} MCP {@code execute} tool runs queries against. Plain connection
 * coordinates, no pool:
 *
 * <pre>{@code
 * <devDatabase>
 *   <url>jdbc:postgresql://localhost:5432/sakila</url>
 *   <user>dev</user>
 *   <password>dev</password>
 *   <dialect>POSTGRES</dialect>
 *   <claims>{"sub":"dev-user"}</claims>                <!-- or @/path/to/claims-file -->
 *   <allowClaimsOverride>false</allowClaimsOverride>   <!-- per-call claims probing, default off -->
 * </devDatabase>
 * }</pre>
 *
 * <p>Every field has an environment-variable override that wins over the POM, so a developer
 * keeps credentials out of the checked-in file: {@code GRAPHITRON_DEV_DB_URL},
 * {@code GRAPHITRON_DEV_DB_USER}, {@code GRAPHITRON_DEV_DB_PASSWORD},
 * {@code GRAPHITRON_DEV_DB_DIALECT}, {@code GRAPHITRON_DEV_CLAIMS}, and
 * {@code GRAPHITRON_DEV_DB_ALLOW_CLAIMS_OVERRIDE}. Reconciliation and validation live in
 * {@code DevMojo.buildExecuteToolConfig}: no url (from either source) means no dev database and
 * the execute tool is simply absent; a url with a missing or unsupported {@code <dialect>} fails
 * the goal loudly (the dialect is explicit and enumerated, never defaulted).
 */
public class DevDatabaseBinding {
    /** The JDBC url; its absence (and no env override) disables the execute tool. */
    String url;
    /** The database user. */
    String user;
    /** The database password; prefer the {@code GRAPHITRON_DEV_DB_PASSWORD} env override. */
    String password;
    /** The jOOQ dialect, explicit and enumerated: {@code POSTGRES} or {@code ORACLE}. */
    String dialect;
    /**
     * The opaque per-request claims payload handed to the {@code <sessionState>} connect hook:
     * inline, or {@code @/path/to/file} to keep tokens out of listings. Required when the schema
     * configures {@code <sessionState>} (enforced by the generated executor at call time).
     */
    String claims;
    /**
     * Opt-in for a per-call {@code claims} argument on the execute tool (multi-identity RLS
     * probing). Default off, so shared or sensitive dev databases keep one pinned identity.
     */
    Boolean allowClaimsOverride;
}
