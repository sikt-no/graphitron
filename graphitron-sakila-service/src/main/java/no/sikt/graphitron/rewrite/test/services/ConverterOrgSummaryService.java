package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/**
 * Fixture: root {@code @service} hand-rolling {@link ConverterOrgSummary} rows (no DB
 * round-trip) for the {@code @sourceRow}-over-converter-backed-key execution test. Two payloads
 * share {@code orgCode="1120"} so the DataLoader dedups to two distinct {@code Row1<String>}
 * keys; the codes match the {@code converter_org} seed in init.sql.
 */
public final class ConverterOrgSummaryService {

    private ConverterOrgSummaryService() {}

    public static List<ConverterOrgSummary> converterOrgSummaries() {
        return List.of(
            new ConverterOrgSummary("1120"),
            new ConverterOrgSummary("186"),
            new ConverterOrgSummary("1120")
        );
    }
}
