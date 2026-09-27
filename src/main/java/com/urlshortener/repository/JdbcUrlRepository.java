package com.urlshortener.repository;

import com.urlshortener.domain.LinkStatus;
import com.urlshortener.domain.ShortUrl;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Postgres implementation of {@link UrlRepository}, using explicit SQL.
 *
 * <h2>Why check-then-insert is wrong</h2>
 * <pre>
 * time   request A (code X)            request B (code X)
 * -----  ----------------------------  ----------------------------
 * t1     SELECT … WHERE code = X → none
 * t2                                   SELECT … WHERE code = X → none
 * t3     INSERT X → ok
 * t4                                   INSERT X → ?
 * </pre>
 * Both requests saw "free". Without a unique key, B's insert succeeds and there are two rows
 * for X. With a unique key, B gets an exception it wasn't expecting. Either way the check at
 * t1/t2 proved nothing. The question "is it free?" and the act of taking it must be a single
 * statement, so the database decides who wins.
 *
 * <h2>Why ON CONFLICT DO NOTHING, not catching DuplicateKeyException</h2>
 * <pre>
 * approach                              in a transaction                 other constraint errors
 * ------------------------------------  -------------------------------  -----------------------
 * plain INSERT, catch the exception     Postgres aborts the whole         caught by mistake if
 *                                       transaction (SQLSTATE 25P02):     the catch is too broad
 *                                       the retry can't run in it
 * INSERT … ON CONFLICT (code)           transaction stays usable; the     still thrown, because
 *   DO NOTHING, then check row count    retry is just another statement   only PK conflicts are
 *                                                                          named
 * </pre>
 * It also keeps collisions (expected ~5% of the time at scale) out of the error logs and off
 * the exception-handling path.
 *
 * <p>Naming the conflict target {@code (code)} matters: a bare {@code ON CONFLICT DO NOTHING}
 * would also swallow a future unique index (say, per-owner dedup) and report it as a code
 * collision.
 *
 * <h2>Timestamps</h2>
 * The Postgres driver maps {@code TIMESTAMPTZ} to {@link OffsetDateTime}, not
 * {@link Instant}, so values are converted at this boundary, always in UTC. The rest of the
 * code only ever sees {@code Instant}.
 */
@Repository
public class JdbcUrlRepository implements UrlRepository {

    private static final String INSERT_IF_ABSENT = """
            INSERT INTO short_urls (code, long_url, owner_id, created_at, expires_at, status, is_custom)
            VALUES (:code, :longUrl, :ownerId, :createdAt, :expiresAt, :status, :custom)
            ON CONFLICT (code) DO NOTHING
            """;

    private static final String FIND_BY_CODE = """
            SELECT code, long_url, owner_id, created_at, expires_at, status, is_custom
            FROM short_urls
            WHERE code = :code
            """;

    private static final RowMapper<ShortUrl> ROW_MAPPER = JdbcUrlRepository::mapRow;

    private final JdbcClient jdbc;

    public JdbcUrlRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(ShortUrl url) {
        int rows = jdbc.sql(INSERT_IF_ABSENT)
                .param("code", url.code())
                .param("longUrl", url.longUrl())
                .param("ownerId", url.ownerId())
                .param("createdAt", toDb(url.createdAt()))
                .param("expiresAt", toDb(url.expiresAt()))
                .param("status", url.status().name())
                .param("custom", url.custom())
                .update();
        return rows == 1; // 0 = the code was already taken and nothing was written
    }

    @Override
    public Optional<ShortUrl> findByCode(String code) {
        return jdbc.sql(FIND_BY_CODE)
                .param("code", code)
                .query(ROW_MAPPER)
                .optional();
    }

    private static ShortUrl mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ShortUrl(
                rs.getString("code"),
                rs.getString("long_url"),
                rs.getString("owner_id"),
                fromDb(rs.getObject("created_at", OffsetDateTime.class)),
                fromDb(rs.getObject("expires_at", OffsetDateTime.class)),
                LinkStatus.valueOf(rs.getString("status")),
                rs.getBoolean("is_custom"));
    }

    private static OffsetDateTime toDb(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant fromDb(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
