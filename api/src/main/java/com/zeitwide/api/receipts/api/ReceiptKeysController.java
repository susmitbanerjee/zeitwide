package com.zeitwide.api.receipts.api;

import com.zeitwide.api.receipts.app.ReceiptService;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ReceiptKeysController {

  private final ReceiptService receipts;

  ReceiptKeysController(ReceiptService receipts) {
    this.receipts = receipts;
  }

  /** Keys change only on rotation, which keeps the old key listed, so an hour's cache is safe. */
  @GetMapping("/.well-known/zeitwide-keys.json")
  ResponseEntity<ReceiptDtos.KeySet> keys() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
        .body(ReceiptDtos.keySet(receipts.publicKeys()));
  }
}
