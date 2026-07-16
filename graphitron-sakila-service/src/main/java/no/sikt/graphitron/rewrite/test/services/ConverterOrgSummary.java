package no.sikt.graphitron.rewrite.test.services;

/**
 * Fixture: a free-form (non-table-backed) record-backed payload whose {@code orgCode}
 * carries the converter-backed {@code converter_org} primary key as its converted user type
 * (String; see {@code OrgCodeStringConverter} in graphitron-fixtures-codegen). The schema's
 * {@code ConverterOrgSummary.org} field lifts this value via
 * {@link ConverterOrgSummaryLifter#liftOrgCode} ({@code @sourceRow} leaf-PK arm), so the
 * DataLoader key is a {@code Row1<String>} whose parent-input VALUES cell must bind through the
 * PK column's Converter DataType to join against the BIGINT-domain column.
 */
public record ConverterOrgSummary(String orgCode) {}
