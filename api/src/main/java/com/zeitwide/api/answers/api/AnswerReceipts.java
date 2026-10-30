package com.zeitwide.api.answers.api;

import com.zeitwide.api.answers.app.Answered;
import com.zeitwide.api.receipts.api.ReceiptDtos;
import com.zeitwide.api.receipts.app.ReceiptService;

final class AnswerReceipts {

  private AnswerReceipts() {}

  static ReceiptDtos.Receipt issue(
      ReceiptService receipts, AnswerRequestEvidence request, Object answer, Answered<?> answered) {
    return ReceiptDtos.Receipt.fromDomain(
        receipts.issue(
            request, answer, answered.release().value(), AnswerMapper.revisions(answered)));
  }
}
