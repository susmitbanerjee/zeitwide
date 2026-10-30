package com.zeitwide.api.receipts.api;

import com.zeitwide.api.receipts.app.ReceiptService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/receipts")
class ReceiptController {

  private final ReceiptService receipts;

  ReceiptController(ReceiptService receipts) {
    this.receipts = receipts;
  }

  @PostMapping(value = "/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
  ReceiptDtos.Verification verify(@Valid @RequestBody ReceiptDtos.Receipt receipt) {
    return new ReceiptDtos.Verification(receipts.verify(receipt.toDomain()));
  }
}
