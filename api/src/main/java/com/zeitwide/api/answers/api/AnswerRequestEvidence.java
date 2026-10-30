package com.zeitwide.api.answers.api;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Normalized route, query and body values covered by an answer receipt. */
record AnswerRequestEvidence(String method, String path, Map<String, Object> query, Object body) {

  static Map<String, Object> query(Map<String, Object> values, OffsetDateTime asOf) {
    Map<String, Object> query = new LinkedHashMap<>(values);
    if (asOf != null) {
      query.put("as_of", asOf.toInstant());
    }
    return Map.copyOf(query);
  }
}
