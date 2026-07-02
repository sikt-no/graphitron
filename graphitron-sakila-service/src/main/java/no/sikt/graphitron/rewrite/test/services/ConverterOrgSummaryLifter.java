package no.sikt.graphitron.rewrite.test.services;

import org.jooq.Row1;
import org.jooq.impl.DSL;

/**
 * R413 fixture: lifter for {@code ConverterOrgSummary.org} ({@code @sourceRow} leaf-PK arm over
 * a converter-backed key). The {@code Row1<String>} matches {@code converter_org}'s PK at its
 * converted user type (the {@code org_code_domain} BIGINT column is forced to
 * {@code java.lang.String} by {@code OrgCodeStringConverter}). Cells are scalar bind values via
 * {@code DSL.row(value)} — the {@code Param} contract the generated
 * {@code parentKeyCellValue} helper (R413) extracts through when rebinding the cell at the
 * column's Converter DataType.
 */
public final class ConverterOrgSummaryLifter {

    private ConverterOrgSummaryLifter() {}

    public static Row1<String> liftOrgCode(ConverterOrgSummary parent) {
        return DSL.row(parent.orgCode());
    }
}
