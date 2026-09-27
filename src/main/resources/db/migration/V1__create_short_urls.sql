-- The source of truth: one row per short link.
--
-- Everything here is a backstop for rules the application also enforces. The app gives
-- friendly errors; the database makes sure a bug can never write a row that breaks them.
--
--   rule                                   app check                enforced here by
--   -------------------------------------  -----------------------  -------------------------
--   one code → one link                     insert-and-retry loop    PRIMARY KEY (code)
--   generated codes are exactly 7 base62    Base62.isGeneratedShape  chk_code_shape
--   custom aliases never look generated     alias policy (M4)        chk_code_shape
--   long URL ≤ 2048 chars                   request validation       chk_long_url_length
--   expiry is after creation                request validation       chk_expiry_after_creation

CREATE TABLE short_urls (
    -- VARCHAR(32), not CHAR(7): custom aliases (M4) are longer than generated codes.
    -- COLLATE "C" compares raw bytes: case-sensitive (aZ3 ≠ az3, as RFC 3986 requires for
    -- paths), faster than a locale collation, and index order matches Base62's ASCII order.
    code        VARCHAR(32) COLLATE "C" PRIMARY KEY,

    -- TEXT + a CHECK instead of VARCHAR(2048): same limit, and changing it later is a
    -- constraint swap rather than a column type change.
    long_url    TEXT        NOT NULL,

    -- Nullable: accounts are out of scope, and anonymous links are allowed. Stands in for
    -- the notebook's urls_by_owner table: at one-node scale a secondary index does the job.
    owner_id    VARCHAR(64),

    created_at  TIMESTAMPTZ NOT NULL,

    -- NULL means "never expires". There's no EXPIRED status: expiry is derived from this
    -- column at read time. A stored EXPIRED flag would need a job to flip it and would be
    -- wrong between the moment of expiry and the job's next run.
    expires_at  TIMESTAMPTZ,

    -- Only states a human or trust & safety sets on purpose. Disabling keeps the row, so the
    -- code is never reused and a takedown can be audited or reversed.
    status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',

    is_custom   BOOLEAN     NOT NULL DEFAULT FALSE,

    CONSTRAINT chk_status CHECK (status IN ('ACTIVE', 'DISABLED')),

    CONSTRAINT chk_long_url_length CHECK (char_length(long_url) BETWEEN 1 AND 2048),

    CONSTRAINT chk_expiry_after_creation CHECK (expires_at IS NULL OR expires_at > created_at),

    -- The namespace split from the notebook (section 10), enforced where it can't be bypassed:
    -- generated codes are exactly 7 base62 chars, and custom aliases must never have that
    -- shape, so a future generated code can never already be taken by an alias.
    CONSTRAINT chk_code_shape CHECK (
        (NOT is_custom AND code ~ '^[0-9A-Za-z]{7}$')
        OR (is_custom AND code !~ '^[0-9A-Za-z]{7}$')
    )
);

-- "My links, newest first" (GET /v1/urls?owner=…). Partial: anonymous links aren't listed.
CREATE INDEX idx_short_urls_owner_created ON short_urls (owner_id, created_at DESC)
    WHERE owner_id IS NOT NULL;

-- No index on expires_at yet. The expiry sweeper arrives in M4 with its own migration;
-- adding an index before anything queries it only slows down inserts.
