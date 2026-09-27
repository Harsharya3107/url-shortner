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

(Filled in as each milestone lands.)
