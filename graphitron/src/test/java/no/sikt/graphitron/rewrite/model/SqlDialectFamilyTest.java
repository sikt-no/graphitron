package no.sikt.graphitron.rewrite.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

/**
 * R63: unit coverage of {@link SqlDialectFamily#fromDialectName(String)}, the boundary mapping the
 * generated dialect guard consults at request time. The critical rows mirror jOOQ 3.20.11's
 * {@code SQLDialect.family()} collapse for the two families graphitron gates DML on: {@code POSTGRES}
 * (bulk UPDATE requires it) and {@code ORACLE} (UPSERT rejects it). The name-prefix approach covers
 * commercial-only enum values the OSS jOOQ distribution omits.
 */
@UnitTier
class SqlDialectFamilyTest {

    @ParameterizedTest
    @ValueSource(strings = {"POSTGRES", "POSTGRESPLUS", "YUGABYTEDB"})
    void postgresFamily_collapsesLikeJooq(String name) {
        // Mirrors jOOQ's family(): POSTGRES, the POSTGRESPLUS spelling, and YUGABYTEDB all fold
        // to POSTGRES. This is the set the bulk-UPDATE RequiresFamily(POSTGRES) guard admits.
        assertThat(SqlDialectFamily.fromDialectName(name)).isEqualTo(SqlDialectFamily.POSTGRES);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ORACLE", "ORACLE11G", "ORACLE12C", "ORACLE18C", "ORACLE19C", "ORACLE21C", "ORACLE23AI"})
    void oracleFamily_coversCommercialOnlyVersionedNames(String name) {
        // The OSS jOOQ distribution omits the versioned ORACLE* enum values; the name prefix covers
        // them so the UPSERT RejectsFamily(ORACLE) guard fires on every Oracle spelling.
        assertThat(SqlDialectFamily.fromDialectName(name)).isEqualTo(SqlDialectFamily.ORACLE);
    }

    @Test
    void otherFamilies_mapToTheirOwnArm() {
        assertThat(SqlDialectFamily.fromDialectName("MYSQL")).isEqualTo(SqlDialectFamily.MYSQL);
        assertThat(SqlDialectFamily.fromDialectName("SQLSERVER")).isEqualTo(SqlDialectFamily.MSSQL);
        assertThat(SqlDialectFamily.fromDialectName("SQLSERVER2017")).isEqualTo(SqlDialectFamily.MSSQL);
        assertThat(SqlDialectFamily.fromDialectName("H2")).isEqualTo(SqlDialectFamily.H2);
        assertThat(SqlDialectFamily.fromDialectName("SQLITE")).isEqualTo(SqlDialectFamily.SQLITE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEFAULT", "DERBY", "HSQLDB", "FIREBIRD", "REDSHIFT", "MARIADB", "COCKROACHDB"})
    void unrecognisedNames_mapToOther(String name) {
        // MARIADB keeps its own jOOQ family (it is not a MYSQL member), REDSHIFT is not POSTGRES,
        // and the rest have no graphitron gate; all land on OTHER.
        assertThat(SqlDialectFamily.fromDialectName(name)).isEqualTo(SqlDialectFamily.OTHER);
    }

    @Test
    void nullOrBlankName_mapsToOther() {
        assertThat(SqlDialectFamily.fromDialectName(null)).isEqualTo(SqlDialectFamily.OTHER);
        assertThat(SqlDialectFamily.fromDialectName("")).isEqualTo(SqlDialectFamily.OTHER);
        assertThat(SqlDialectFamily.fromDialectName("   ")).isEqualTo(SqlDialectFamily.OTHER);
    }

    @Test
    void mappingIsCaseInsensitive() {
        // SQLDialect.name() is uppercase in jOOQ, but the mapping is defensive against a lowercased
        // spelling so a caller passing dialect().getName() (mixed case) is still classified.
        assertThat(SqlDialectFamily.fromDialectName("postgres")).isEqualTo(SqlDialectFamily.POSTGRES);
        assertThat(SqlDialectFamily.fromDialectName("oracle19c")).isEqualTo(SqlDialectFamily.ORACLE);
    }

    @Test
    void jooqFamilyName_returnsTheStringDslDialectFamilyNameReports() {
        // The emitter compares dsl.dialect().family().name() against this string. For the two gated
        // families it equals name(); MSSQL diverges to jOOQ's SQLSERVER spelling.
        assertThat(SqlDialectFamily.POSTGRES.jooqFamilyName()).isEqualTo("POSTGRES");
        assertThat(SqlDialectFamily.ORACLE.jooqFamilyName()).isEqualTo("ORACLE");
        assertThat(SqlDialectFamily.MYSQL.jooqFamilyName()).isEqualTo("MYSQL");
        assertThat(SqlDialectFamily.MSSQL.jooqFamilyName()).isEqualTo("SQLSERVER");
        assertThat(SqlDialectFamily.H2.jooqFamilyName()).isEqualTo("H2");
        assertThat(SqlDialectFamily.SQLITE.jooqFamilyName()).isEqualTo("SQLITE");
    }

    @Test
    void jooqFamilyName_onOther_rejects() {
        // OTHER is not a gate-able family; a DialectRequirement must name a concrete family.
        assertThatThrownBy(SqlDialectFamily.OTHER::jooqFamilyName)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no jOOQ family name");
    }
}
