package com.zeitwide.api.accounts.infra;

import com.zeitwide.api.accounts.app.AdoptionRepository;
import com.zeitwide.api.accounts.domain.AdoptionCounts;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAdoptionRepository implements AdoptionRepository {

  private final JdbcClient jdbc;

  JdbcAdoptionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public AdoptionCounts counts(Instant usedSince) {
    return jdbc.sql(
            """
            SELECT (SELECT count(*) FROM account) AS accounts,
                   count(*) FILTER (WHERE revoked_at IS NULL) AS active_keys,
                   count(*) FILTER (WHERE revoked_at IS NULL AND last_used_at >= :used_since)
                       AS keys_used
            FROM api_key
            """)
        .param("used_since", Timestamp.from(usedSince))
        .query(
            (rs, row) ->
                new AdoptionCounts(
                    rs.getLong("accounts"), rs.getLong("active_keys"), rs.getLong("keys_used")))
        .single();
  }
}
