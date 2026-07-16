package no.sikt.graphitron.rewrite.walker.internal;

import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Optional;

/**
 * Channel-level handler rules over a resolved {@code @error}-type list. Absorbed from
 * {@code FieldBuilder.checkChannelLevelHandlerRules} / {@code checkDuplicateMatchCriteria} so the
 * {@code ErrorChannelWalker} owns the in-scope error-channel rule surface; the two checks are pure
 * functions of {@code mappedErrorTypes}, so they live here as static helpers the walker wraps into
 * {@link no.sikt.graphitron.rewrite.model.ErrorChannelWalkerError.ChannelRuleViolation} arms with
 * the rule number and the returned detail string.
 *
 * <p>Each method returns the detail (the actionable second half of the diagnostic, no type/field
 * prefix) when the rule fires, or {@code null} when the channel is well-formed; the walker supplies
 * the outcome-type and errors-field names. The DML path keeps its own
 * {@code FieldBuilder} copies during the additive window; a later slice consolidates onto these.
 */
public final class ChannelRuleChecks {

    private ChannelRuleChecks() {}

    /**
     * Rule 7: at most one {@code {handler: VALIDATION}} entry across the channel's flattened
     * handler list. VALIDATION is a single fan-out target per payload, and two
     * VALIDATION-marked {@code @error} types would compete for the same slot. Returns a detail
     * string when more than one carries VALIDATION, or {@code null} otherwise.
     */
    public static String checkMultiValidation(java.util.List<ErrorType> mappedErrorTypes) {
        var validationCarriers = new ArrayList<String>();
        for (var et : mappedErrorTypes) {
            for (var h : et.handlers()) {
                if (h instanceof ErrorType.ValidationHandler) {
                    validationCarriers.add(et.name());
                    break;
                }
            }
        }
        if (validationCarriers.size() > 1) {
            return "@error channel has more than one {handler: VALIDATION} entry across "
                + "@error types " + String.join(", ", validationCarriers)
                + "; VALIDATION is a single fan-out target per payload — split into separate "
                + "fields with distinct payloads, or collapse to one VALIDATION-carrying type";
        }
        return null;
    }

    /**
     * Rule 8: rejects a channel whose flattened handler list contains two intra-variant handlers
     * with identical match-criteria. The runtime's source-order {@code findFirst} on
     * {@code MAPPINGS} would make the second mapping unreachable. Cross-variant overlap is
     * intentionally allowed (an {@code ExceptionHandler(SQLException)} and a
     * {@code SqlStateHandler("23503")} discriminate on different fields). {@link
     * ErrorType.ValidationHandler} is excluded; rule 7 already caps it at one per channel and it
     * has no discriminator. Returns the first duplicate's detail string, or {@code null} when no
     * duplicates exist.
     */
    public static String checkDuplicateMatchCriteria(java.util.List<ErrorType> mappedErrorTypes) {
        record CriteriaKey(String variant, String discriminator, Optional<String> matches) {}
        var seen = new LinkedHashMap<CriteriaKey, String>();
        for (var et : mappedErrorTypes) {
            for (var h : et.handlers()) {
                CriteriaKey key;
                String fingerprint;
                if (h instanceof ErrorType.ExceptionHandler eh) {
                    key = new CriteriaKey("ExceptionHandler", eh.exceptionClassName(), eh.matches());
                    fingerprint = "ExceptionHandler(className=\"" + eh.exceptionClassName() + "\""
                        + matchesSuffix(eh.matches()) + ")";
                } else if (h instanceof ErrorType.SqlStateHandler sh) {
                    key = new CriteriaKey("SqlStateHandler", sh.sqlState(), sh.matches());
                    fingerprint = "SqlStateHandler(sqlState=\"" + sh.sqlState() + "\""
                        + matchesSuffix(sh.matches()) + ")";
                } else if (h instanceof ErrorType.VendorCodeHandler vh) {
                    key = new CriteriaKey("VendorCodeHandler", vh.vendorCode(), vh.matches());
                    fingerprint = "VendorCodeHandler(vendorCode=\"" + vh.vendorCode() + "\""
                        + matchesSuffix(vh.matches()) + ")";
                } else {
                    continue;
                }
                String trace = et.name() + " " + fingerprint;
                String prior = seen.putIfAbsent(key, trace);
                if (prior != null) {
                    return "@error channel has two handlers with identical match-criteria: "
                        + prior + " and " + trace
                        + "; the runtime's source-order findFirst on MAPPINGS would make the "
                        + "second mapping unreachable — collapse the duplicate or differentiate "
                        + "the criteria";
                }
            }
        }
        return null;
    }

    private static String matchesSuffix(Optional<String> matches) {
        return matches.isPresent() ? ", matches=\"" + matches.get() + "\"" : "";
    }
}
