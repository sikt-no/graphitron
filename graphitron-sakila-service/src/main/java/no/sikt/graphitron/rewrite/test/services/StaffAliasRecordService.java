package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.Tables;
import no.sikt.graphitron.rewrite.test.jooq.tables.records.StaffRecord;

/**
 * Execution-tier fixture: a jOOQ {@link StaffRecord} bound directly as a {@code @service} input
 * param whose input type declares <em>one write column reached through two names</em>, the standard
 * GraphQL rename-deprecation shape. {@code firstName} is the live name and {@code givenName} the
 * {@code @deprecated} one it replaced; both carry {@code @field(name: "first_name")}.
 *
 * <p>The generated helper folds the pair into one presence-guarded assignment that tries the read
 * paths in precedence order (live first) and takes the first <em>present</em> one, where present is
 * the wire {@code Map}'s {@code containsKey}. {@link #describeStaffAlias} reports the constructed
 * record's jOOQ {@code touched}-flags and values without writing, which is the only tier that can
 * observe the whole matrix: the deprecated name alone writes, the live name alone writes, both
 * present resolves to the live one, neither leaves the column {@code changed=false}, and an
 * explicit {@code null} on the live name writes SQL NULL rather than falling through to the alias's
 * value.
 *
 * <p>{@code StaffRecord} rather than a record another fixture already binds, so this input is the
 * only shape reaching its {@code create<Record>} helper and the alias behaviour is read off an
 * uncontended helper. The input declares no {@code @nodeId}, so no trailing key decode can reset the
 * touched flag of a null-valued column (see {@link CustomerRecordService} for that interaction).
 */
public final class StaffAliasRecordService {

    private StaffAliasRecordService() {}

    /**
     * Reports the {@code changed}-flag state and value of the aliased column ({@code first_name})
     * plus a control column no alias touches ({@code last_name}), so one helper answers the whole
     * precedence matrix by varying only the wire input.
     */
    public static String describeStaffAlias(StaffRecord in) {
        var t = Tables.STAFF;
        return "first[changed=" + in.touched(t.FIRST_NAME) + ",val=" + in.getFirstName() + "]"
            + " last[changed=" + in.touched(t.LAST_NAME) + ",val=" + in.getLastName() + "]";
    }
}
