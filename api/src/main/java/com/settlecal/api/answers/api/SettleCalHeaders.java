package com.settlecal.api.answers.api;

import com.settlecal.api.answers.app.Answered;
import org.springframework.http.HttpHeaders;

/** The SettleCal-* headers that repeat an answer's provenance from its body. */
final class SettleCalHeaders {

  static final String DATASET_RELEASE = "SettleCal-Dataset-Release";
  static final String CALENDAR_REVISION = "SettleCal-Calendar-Revision";
  static final String AS_OF = "SettleCal-As-Of";

  private SettleCalHeaders() {}

  static HttpHeaders of(Answered<?> answered) {
    HttpHeaders headers = release(answered);
    headers.set(CALENDAR_REVISION, AnswerMapper.revisionHeader(answered));
    return headers;
  }

  /** Without the revision header, for the list of every calendar. */
  static HttpHeaders release(Answered<?> answered) {
    HttpHeaders headers = new HttpHeaders();
    headers.set(DATASET_RELEASE, answered.release().value());
    headers.set(AS_OF, answered.asOf().toString());
    return headers;
  }
}
