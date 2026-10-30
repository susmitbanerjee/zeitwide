package com.zeitwide.api.receipts.api;

import com.zeitwide.api.receipts.app.ReceiptService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ReceiptKeysController {

  private final ReceiptService receipts;

  ReceiptKeysController(ReceiptService receipts) {
    this.receipts = receipts;
  }

  @GetMapping("/.well-known/zeitwide-keys.json")
  ReceiptDtos.KeySet keys() {
    return ReceiptDtos.keySet(receipts.publicKeys());
  }
}
