package com.zeitwide.api.answers.api;

import com.zeitwide.api.answers.app.Answered;
import org.springframework.http.HttpHeaders;

/** The Zeitwide-* headers that repeat an answer's provenance from its body. */
final class ZeitwideHeaders {

  static final String DATASET_RELEASE = "Zeitwide-Dataset-Release";
  static final String CALENDAR_REVISION = "Zeitwide-Calendar-Revision";
  static final String AS_OF = "Zeitwide-As-Of";

  private ZeitwideHeaders() {}

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
