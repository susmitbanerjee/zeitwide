package com.zeitwide.api.accounts.infra;

import com.zeitwide.api.accounts.app.AdoptionRepository;
import com.zeitwide.api.accounts.domain.AdoptionCounts;
import com.zeitwide.api.accounts.domain.SignupSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
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
    Totals totals =
        jdbc.sql(
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
                    new Totals(
                        rs.getLong("accounts"), rs.getLong("active_keys"), rs.getLong("keys_used")))
            .single();
    return new AdoptionCounts(
        totals.accounts(), totals.activeKeys(), totals.keysUsed(), accountsBySignupSource());
  }

  private Map<SignupSource, Long> accountsBySignupSource() {
    Map<SignupSource, Long> counts = new EnumMap<>(SignupSource.class);
    jdbc.sql(
            """
            SELECT signup_source, count(*) AS accounts
            FROM account
            WHERE signup_source IS NOT NULL
            GROUP BY signup_source
            """)
        .query(
            rs -> {
              long accounts = rs.getLong("accounts");
              SignupSource.fromValue(rs.getString("signup_source"))
                  .ifPresent(source -> counts.put(source, accounts));
            });
    return counts;
  }

  private record Totals(long accounts, long activeKeys, long keysUsed) {}
}
