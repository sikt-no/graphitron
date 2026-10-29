package no.sikt.graphitron.jakarta.rest;

import jakarta.ws.rs.core.HttpHeaders;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Reads a request's {@code Accept-Language} header into the ordered list of language tags a
 * {@code List<String>} context argument takes, per RFC 9110: split on commas, order by quality
 * (header order breaks a tie), drop {@code q=0} and the {@code *} wildcard.
 *
 * <pre>{@code
 * Graphitron.newOwnedExecutionInput(session, AcceptLanguage.parse(headers))
 * }</pre>
 */
public final class AcceptLanguage {

    private AcceptLanguage() {
    }

    /**
     * The request's preferred language tags, most preferred first.
     *
     * @param headers this request's headers
     * @return the tags, empty when the header is absent or yields none
     */
    public static List<String> parse(HttpHeaders headers) {
        if (headers == null) {
            return List.of();
        }
        List<String> values = headers.getRequestHeader(HttpHeaders.ACCEPT_LANGUAGE);
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return parse(String.join(",", values));
    }

    /**
     * The preferred language tags of an {@code Accept-Language} header value, most preferred first.
     * A member that is malformed (an empty tag, a {@code q} that is not a number in 0 to 1) is
     * skipped rather than failing the request.
     *
     * @param header the header value, possibly several members separated by commas
     * @return the tags, empty when none survive
     */
    public static List<String> parse(String header) {
        if (header == null || header.isBlank()) {
            return List.of();
        }
        record Member(String tag, double quality, int position) { }
        List<Member> members = new ArrayList<>();
        int position = 0;
        for (String raw : header.split(",")) {
            String[] parts = raw.split(";");
            String tag = parts[0].trim();
            if (tag.isEmpty() || tag.equals("*")) {
                continue;
            }
            double quality = 1.0;
            boolean malformed = false;
            for (int i = 1; i < parts.length; i++) {
                String parameter = parts[i].trim().toLowerCase(Locale.ROOT);
                if (parameter.startsWith("q=")) {
                    try {
                        quality = Double.parseDouble(parameter.substring(2).trim());
                    } catch (NumberFormatException e) {
                        malformed = true;
                    }
                    if (quality < 0.0 || quality > 1.0 || Double.isNaN(quality)) {
                        malformed = true;
                    }
                }
            }
            if (malformed || quality == 0.0) {
                continue;
            }
            members.add(new Member(tag, quality, position++));
        }
        members.sort(Comparator.comparingDouble(Member::quality).reversed()
            .thenComparingInt(Member::position));
        List<String> tags = new ArrayList<>(members.size());
        for (Member member : members) {
            tags.add(member.tag());
        }
        return List.copyOf(tags);
    }
}
