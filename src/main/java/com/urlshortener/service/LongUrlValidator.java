package com.urlshortener.service;

import com.urlshortener.service.InvalidLinkRequestException.Reason;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides whether a long URL is something we're willing to redirect to.
 *
 * <p>A shortener lends its domain's reputation to whatever URL it points at, so validation is
 * a security feature, not input tidying. The rules, cheapest first:
 * <pre>
 * rule                              rejects                         why
 * --------------------------------  ------------------------------  ---------------------------------
 * non-blank, ≤ 2048 chars           huge payloads                   browsers and proxies cap URL length;
 *                                                                   also the DB CHECK
 * parses as an absolute URI         "example.com", "a b c"          a Location header must be absolute
 * scheme is http or https           javascript:, data:, file:,      javascript:/data: run code on click;
 *                                   ftp:                            others aren't web pages
 * has a host                        "https:///path"                 nothing to redirect to
 * no user-info                      https://paypal.com@evil.com     the text before '@' is a username, so
 *                                                                   this goes to evil.com while looking
 *                                                                   like paypal: a classic phishing trick
 * host isn't ours (or a subdomain)  https://sho.rt/abc, SHO.RT.     redirect loops, and chains that hide
 *                                                                   the final destination from scanners
 * </pre>
 * The trailing-dot check matters: {@code sho.rt.} is the same host as {@code sho.rt} to DNS,
 * so without normalizing it, the own-domain rule is trivially bypassed.
 *
 * <h2>What this class deliberately does not do</h2>
 * <ul>
 *   <li><b>Rewrite the URL.</b> Apart from trimming whitespace, we redirect to exactly what the
 *       user gave. Changing query parameters or case in the path could break their link.</li>
 *   <li><b>Fetch the URL.</b> Malware/phishing scanning (M5) runs against a reputation list.
 *       If we ever fetch URLs (scanning, link previews), that fetcher must block private
 *       and loopback addresses, or it becomes an SSRF hole. Redirecting a <i>browser</i> to
 *       {@code localhost} only affects the person clicking, so it isn't blocked here.</li>
 * </ul>
 * One known limitation: {@link URI} rejects characters browsers tolerate, such as spaces and
 * unencoded non-ASCII in paths. Those requests get a clear error asking for an encoded URL.
 */
public class LongUrlValidator {

    public static final int MAX_LENGTH = 2048;

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final Set<String> blockedHosts;

    /** @param blockedHosts our own domains; they and their subdomains are refused */
    public LongUrlValidator(Set<String> blockedHosts) {
        this.blockedHosts = blockedHosts.stream().map(LongUrlValidator::normalizeHost).collect(Collectors.toUnmodifiableSet());
    }

    /** Returns the URL to store (trimmed), or throws {@link InvalidLinkRequestException}. */
    public String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid(Reason.INVALID_URL, "long_url is required");
        }
        String url = raw.strip();
        if (url.length() > MAX_LENGTH) {
            throw invalid(Reason.INVALID_URL, "long_url must be at most " + MAX_LENGTH + " characters");
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw invalid(Reason.INVALID_URL, "long_url is not a valid URL; percent-encode spaces and special characters");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw invalid(Reason.INVALID_URL, "long_url must start with http:// or https://");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw invalid(Reason.INVALID_URL, "long_url must include a host name");
        }
        if (uri.getRawUserInfo() != null) {
            throw invalid(Reason.URL_NOT_ALLOWED, "long_url must not contain a username or password");
        }

        String host = normalizeHost(uri.getHost());
        for (String blocked : blockedHosts) {
            if (host.equals(blocked) || host.endsWith("." + blocked)) {
                throw invalid(Reason.URL_NOT_ALLOWED, "long_url must not point to this shortener");
            }
        }
        return url;
    }

    /** Lower-case, and drop a trailing dot: {@code SHO.RT.} and {@code sho.rt} are the same host. */
    private static String normalizeHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.endsWith(".") ? h.substring(0, h.length() - 1) : h;
    }

    private static InvalidLinkRequestException invalid(Reason reason, String message) {
        return new InvalidLinkRequestException(reason, message);
    }
}
