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
| M1 | `Base62`, `POST /v1/urls`, `GET /{code}` → 302 on Postgres with a UNIQUE key; random generator first | End-to-end flow, validation, conditional insert, 404 vs 410 |
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
