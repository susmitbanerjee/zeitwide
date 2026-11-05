---
title: Developer quickstart
description: Stop hard-coding holiday lists. Call the Zeitwide API, connect an agent over MCP, or get webhooks when a date changes.
updated: 2026-11-05
---

# Developer quickstart

One API for Fedwire, FedACH, FedNow, T2, CHAPS and public holidays. 10,000 calls a day per
key, free in the beta. Every answer names its sources and carries a signed receipt.

## Make your first call

Every call needs a key. Create one for free in the app at
<https://zeitwide.com/app/developers>; it takes a minute.

```bash
curl https://api.zeitwide.com/v1/calendars/GB-CHAPS/days/2026-12-28 \
  -H "Authorization: Bearer zw_live_…"

curl -X POST https://api.zeitwide.com/v1/business-days/add \
  -H "Authorization: Bearer zw_live_…" -H "Content-Type: application/json" \
  -d '{"calendars":["US-FEDWIRE","GB-CHAPS"],"date":"2026-12-24","days":2}'
```

## What every answer carries

- `sources`: the official pages behind each closure, with the hash of the copy Zeitwide read.
- `dataset_release`: the same request against the same release always gives the same answer.
- `receipt`: an Ed25519-signed record of the question and answer. Check it with
  `POST /v1/receipts/verify`, or offline with the public keys.

## Connect an agent

The hosted MCP server has six tools with the same answers as the API. It needs no key:
anonymous calls get 100 a day per network and answers without a receipt; send your key
for its limits and signed receipts.

```json
{ "mcpServers": { "zeitwide": { "url": "https://api.zeitwide.com/mcp" } } }
```

Or run it locally, with no network, from the open Python library:

```bash
uvx zeitwide-mcp --offline
```

Agents can also find Zeitwide on their own: the AI catalog at
<https://zeitwide.com/.well-known/ai-catalog.json> links the MCP server card, the A2A agent
card, the OpenAPI document and llms.txt. See [Zeitwide for agents](/docs/agents).

## Get told when a date changes

Subscribe a webhook to closure changes. Check the signature before you act on it:

```js
import crypto from "node:crypto";

// Zeitwide-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256 of "t.body">
export function verify(header, body, secret) {
  const parts = Object.fromEntries(header.split(",").map((p) => p.split("=")));
  if (Math.abs(Date.now() / 1000 - Number(parts.t)) > 300) return false;
  const expected = crypto.createHmac("sha256", secret).update(`${parts.t}.${body}`).digest();
  return crypto.timingSafeEqual(expected, Buffer.from(parts.v1, "hex"));
}
```

## Read the answers as Markdown

Every public page has a Markdown twin with the same facts: add `.md` to its path
(`https://zeitwide.com/calendars/GB-CHAPS/2026-12-28.md`), or send `Accept: text/markdown`.
The full API reference is at <https://zeitwide.com/docs/api>.
