# url-shortener

A Bitly-scale URL shortener, built one milestone at a time as system-design and LLD practice.
Design notebook: https://claude.ai/artifact/X7kYJHJBeUbdiHiB1SHA6Q

## Run locally

```bash
docker compose up -d
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn spring-boot:run
```

App: http://localhost:8082 · Postgres: localhost:5434 (urlshortener / urlshortener)

Build with JDK 21. Homebrew's `mvn` defaults to a newer JDK, which breaks Lombok.

## Milestones

| # | Build | Proves |
|---|-------|--------|
| M1 ✅ | `Base62`, `POST /v1/urls`, `GET /{code}` → 302 on Postgres with a UNIQUE key; random generator first | End-to-end flow, validation, conditional insert, 404 vs 410 |
| M2 | `IdBlockAllocator` + `RangeLeasingGenerator` + `FeistelPermutation` | 1M codes from 64 threads with zero duplicates; a killed instance only leaves gaps |
| M3 | Redis cache-aside, Caffeine L1, negative cache, single-flight on miss | p99 and DB QPS with and without each layer; hot-key test |
| M4 | Custom aliases, reserved words, expiry, `PATCH`/`DELETE` with invalidation | Alias race gives exactly one 201 and one 409 |
| M5 | Click events → Kafka → aggregator (per-minute counts, HyperLogLog); rate limiting | Redirect latency unchanged; redirects work with Kafka stopped |
| M6 | Two Postgres shards by `hash(code)`, chaos tests | Redirects stay up, creation fails fast |

## Decisions worth remembering

### Base62 (`codegen/Base62`)
- **62, not 64:** `+` and `/` break URLs; base64url's `-`/`_` get trimmed by chat auto-linkers and `-` is reserved for custom aliases.
- **Fixed width 7, zero-padded:** one shape for every generated code, so custom aliases (8+ chars or containing `-`) can never collide with a future generated code; length doesn't reveal age.
- **`0-9A-Za-z` = ASCII order:** string order equals numeric order. Handy, but it means sequential ids would hotspot a key-sorted store (Bigtable). The M2 permutation fixes that and guessability together.
- **Throws instead of growing:** a value that needs 8 chars is a bug, not something to hide.

### ShortCodeGenerator + RandomCodeGenerator (`codegen/`)
- **Strategy interface, `String next()` with no input:** a code identifies a *link*, not a *URL*. Hashing needs the URL as input, so it can't fit, and that coupling is exactly its flaw (shared codes across owners, salted retries).
- **A code is a candidate, not a reservation:** the service always does conditional insert + retry. The DB constraint sees things the generator can't (custom aliases, other regions, restores).
- **Collision odds are linear, not birthday:** P = used / 62⁷, so ≈5% at 183B links → ~1.055 attempts per insert.
- **SecureRandom, not Random:** a 48-bit LCG can be recovered from a few outputs, letting an attacker predict the next users' codes.
- **`nextLong(bound)`, not `abs(nextLong()) % N`:** modulo bias, and `abs(Long.MIN_VALUE)` is negative.

### short_urls table + ShortUrl record (`V1__create_short_urls.sql`, `domain/`)
- **JDBC + record, not JPA:** with an assigned id, `save()` merges (SELECT, then INSERT *or UPDATE*), so a code collision would silently overwrite someone's link. The one operation that matters is `INSERT ... ON CONFLICT DO NOTHING`.
- **`VARCHAR(32) COLLATE "C"` PK:** room for custom aliases; byte comparison is case-sensitive (RFC 3986 paths), fast, and matches Base62's ASCII order.
- **No EXPIRED status:** expiry is derived from `expires_at` on read. A stored flag needs a job and is wrong until the job runs.
- **Delete = DISABLED, row kept:** the code is never reused, and takedowns are auditable.
- **Namespace split enforced by a CHECK:** generated = exactly `[0-9A-Za-z]{7}`, custom = anything else. The app gives nice errors; the DB makes the rule unbreakable.
- **Timestamps truncated to micros:** Postgres precision, so a DB round trip gives an equal record.

### UrlRepository + JdbcUrlRepository (`repository/`)
- **Interface of two methods:** `insertIfAbsent` and `findByCode`. M3's cache wraps it as a Decorator; the redirect service never knows which layer answered.
- **Check-then-insert is a race:** the test forces 16 threads through "SELECT → barrier → INSERT"; all 16 see "free". Only one atomic statement can decide the winner.
- **`ON CONFLICT (code) DO NOTHING` + row count, not catching `DuplicateKeyException`:** in Postgres a failed statement aborts the whole transaction (25P02), so a catch-and-retry can't continue in it. Both behaviors are proven by tests.
- **Name the conflict target `(code)`:** a bare `ON CONFLICT DO NOTHING` would swallow future unique indexes and misreport them as code collisions. Other constraint errors still throw, so a bad URL is never retried as a "collision".
- **Tests hit real Postgres (`urlshortener_test`),** because ON CONFLICT, CHECKs and aborted transactions are what an in-memory fake gets wrong. (Boot 3.3.4's Testcontainers is too old for Docker 29's API.)

### ShortenService + LongUrlValidator (`service/`, `config/`)
- **Retry budget from the math:** failures/day = volume × p^k. At year 5 (p = 5.2%, 100M/day): k=5 → ~38 failures/day, k=8 → one every ~200 days. So 8. Exhausting it means a bug → `CodeAllocationException` (5xx, logged), never an infinite loop.
- **No `@Transactional`:** each attempt is one atomic statement; a transaction would only hold a connection across retries.
- **Injected `Clock`:** one "now" per request for `createdAt` and the expiry check; tests pin time exactly.
- **URL validation is security, not tidying:** http/https only (no `javascript:`/`data:`), no user-info (`https://paypal.com@evil.com`), never our own domain or its subdomains, trailing dot normalized (`sho.rt.` = `sho.rt`). Store the URL as given (trimmed): rewriting it could break the user's link.
- **Not blocked: `localhost`/private IPs.** Redirecting a browser there only affects the clicker. It matters only if *we* fetch URLs (scanning, previews): that fetcher must block them (SSRF).
- **Service classes have no Spring annotations:** `ShortenerConfig` wires them, so unit tests build them with fakes. `ShortenerProperties` is validated at startup, so a missing base URL fails the boot.

### RedirectService + RedirectResult (`service/`)
- **Sealed result type (Found / NotFound / Gone), not exceptions:** misses are normal traffic on the hottest path; a `switch` over the sealed type won't compile if a case is missed.
- **410 vs 404:** 410 = existed but expired/disabled, so crawlers drop it and support can tell "expired" from "mistyped". Disabled and expired look identical to the client, so takedowns aren't revealed.
- **Reject impossible codes before the lookup:** 1–32 chars of `[0-9A-Za-z-]`, checked in memory, keeps scanner junk off the DB (and, from M3, the cache).
- **Stays unchanged later:** M3 swaps the injected repository for the caching decorator; M5 records clicks in the controller *after* the response, so analytics can never slow or fail a redirect.

### Controllers + error handling (`controller/`, `api/`)
- **Two controllers:** `UrlController` (`/v1/urls`, small, authenticated) and `RedirectController` (`/{code}`, ~100× the traffic). They'll scale and deploy separately.
- **`POST` → 201 + `Location` + `short_url` in the body:** clients never build short URLs themselves, so moving domains or adding custom domains doesn't break them.
- **Redirect headers:** 302 + `Cache-Control: private, max-age=90` (repeat clicks within 90 s skip us; takedowns reach everyone within 90 s; shared proxies don't cache). 404/410 are `no-store`: the code may be created, or the link restored, a moment later.
- **`/{code}` matches one path segment,** so it never swallows `/v1/urls`. Reserved words (`v1`, `api`, `actuator`) must be banned as custom aliases in M4.
- **`ResponseEntity`, not `"redirect:"`:** the status and every header stay visible; `"redirect:"` can leak model attributes into the query string.
- **ProblemDetail (RFC 9457) everywhere:** extend `ResponseEntityExceptionHandler` so Spring's own 400/405/415 share the shape. No catch-all `@ExceptionHandler(Exception.class)`: it would turn clients' 405s into our 500s.
- **422 vs 400:** 400 = the request couldn't be parsed; 422 = parsed fine, content refused. `CodeAllocationException` → 503 + `Retry-After`, since a retry draws fresh random codes.
- **Owner id length checked in the service,** so a 65-char header is a 422, not a DB error surfacing as 500.
