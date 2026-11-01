"use client";

import { useMutation } from "@tanstack/react-query";

import { unwrap, type Receipt } from "@/api/client";
import { browserApi } from "@/lib/api-browser";
import { errorMessage } from "@/lib/queries";
import { formatInstant, shortHash } from "@/lib/format";

import { Button } from "./ui/button";
import { ErrorText, Status } from "./ui/primitives";

/** A signed receipt, and a button that asks the API to check its signature. */
export function ReceiptView({ receipt }: { receipt: Receipt }) {
  const verify = useMutation({
    mutationFn: async () => unwrap(await browserApi.POST("/v1/receipts/verify", { body: receipt })),
  });
  return (
    <div className="flex flex-col gap-3 text-sm">
      <dl className="grid grid-cols-[max-content_minmax(0,1fr)] gap-x-4 gap-y-1">
        <dt className="text-ink-muted">Receipt</dt>
        <dd className="font-mono break-all">{receipt.id}</dd>
        <dt className="text-ink-muted">Issued</dt>
        <dd>{formatInstant(receipt.payload.issued_at)}</dd>
        <dt className="text-ink-muted">Release</dt>
        <dd className="font-mono">{receipt.payload.dataset_release}</dd>
        <dt className="text-ink-muted">Payload SHA-256</dt>
        <dd className="font-mono" title={receipt.payload_hash}>
          {shortHash(receipt.payload_hash)}
        </dd>
        <dt className="text-ink-muted">Signed by</dt>
        <dd>
          Ed25519 · key <span className="font-mono">{receipt.key_id}</span>
        </dd>
      </dl>
      <div className="flex flex-wrap items-center gap-3">
        <Button variant="outline" onClick={() => verify.mutate()} disabled={verify.isPending}>
          Verify receipt
        </Button>
        {verify.isSuccess && (
          <Status className={verify.data.valid ? "text-success" : "text-closed-ink"}>
            {verify.data.valid ? "Signature valid" : "Signature does not match"}
          </Status>
        )}
      </div>
      {verify.isError && <ErrorText>{errorMessage(verify.error)}</ErrorText>}
    </div>
  );
}
