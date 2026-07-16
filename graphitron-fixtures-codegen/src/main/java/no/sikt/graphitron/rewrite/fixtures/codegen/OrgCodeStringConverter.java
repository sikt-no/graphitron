package no.sikt.graphitron.rewrite.fixtures.codegen;

import org.jooq.Converter;

/**
 * Fixture converter: maps the {@code org_code_domain} PostgreSQL domain (over BIGINT) to
 * {@code java.lang.String} on the Java side, mirroring the utdanningsregisteret
 * {@code kodeverk.kode_numerisk_domain} Converter shape that exposed the parent-input VALUES
 * DataType bug. Registered via a {@code <forcedTypes>} entry on graphitron-sakila-db's
 * public-schema codegen execution; the generated {@code CONVERTER_ORG.ORG_CODE} /
 * {@code CONVERTER_CAMPUS.ORG_CODE} columns then carry {@code DataType<String>} whose bind goes
 * through this class. A {@code DSL.val(value)} bypassing the column DataType infers varchar and
 * reproduces the {@code operator does not exist} failure this converter's DataType binding avoids.
 */
public class OrgCodeStringConverter implements Converter<Long, String> {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    @Override
    public String from(Long databaseObject) {
        return databaseObject == null ? null : databaseObject.toString();
    }

    @Override
    public Long to(String userObject) {
        return userObject == null ? null : Long.valueOf(userObject);
    }

    @Override
    public Class<Long> fromType() {
        return Long.class;
    }

    @Override
    public Class<String> toType() {
        return String.class;
    }
}
