package com.zeitwide.api.auth.api;

import com.zeitwide.api.auth.app.ApiKeyService;
import com.zeitwide.api.auth.domain.AccountPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/account/api-keys")
public class ApiKeysController {

  private final ApiKeyService keys;

  public ApiKeysController(ApiKeyService keys) {
    this.keys = keys;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  ApiKeyService.CreatedApiKey create(
      @AuthenticationPrincipal AccountPrincipal principal,
      @Valid @RequestBody CreateApiKey body,
      @RequestHeader(name = "Idempotency-Key") String idempotencyKey) {
    return keys.create(principal, body.name(), idempotencyKey);
  }

  @GetMapping
  List<ApiKeyService.ApiKeySummary> list(@AuthenticationPrincipal AccountPrincipal principal) {
    return keys.list(principal);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void revoke(@AuthenticationPrincipal AccountPrincipal principal, @PathVariable UUID id) {
    keys.revoke(principal, id);
  }

  public record CreateApiKey(@NotBlank @Size(max = 100) String name) {}
}
