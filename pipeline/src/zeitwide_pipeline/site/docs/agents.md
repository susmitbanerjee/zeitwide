---
title: Zeitwide for agents
description: Connect an agent with no key or setup. The hosted MCP server, the A2A agent, and the discovery files that point to both.
updated: 2026-11-05
---

# Zeitwide for agents

An agent can find Zeitwide and ask it questions with no API key and no human setting it
up. Anonymous answers carry their sources and the dataset release. With a key, they also
carry a signed receipt and the key's higher limits.

## Hosted MCP server, no key needed

```json
{ "mcpServers": { "zeitwide": { "url": "https://api.zeitwide.com/mcp" } } }
```

The six read tools answer without credentials: `is_business_day`, `next_business_day`,
`add_business_days`, `explain_date`, `list_closures` and `get_changes`.

- **No credentials:** 100 tool calls a day per IPv4 /24 (IPv6 /48), and no receipt.
- **With an API key** (`Authorization: Bearer zw_live_…`): the key's limit, 10,000 calls a day, and an
  Ed25519-signed receipt on every answer.

A bad or revoked key is refused with 401; it never falls back to anonymous. Get a key at
<https://zeitwide.com/app/developers>.

The same server is listed in the [MCP Registry](https://registry.modelcontextprotocol.io)
as `io.github.susmitbanerjee/zeitwide`, with the offline PyPI package `zeitwide-mcp`
(`uvx zeitwide-mcp --offline`) as its local install.

## A2A agent

Zeitwide is also an [A2A 1.0](https://a2a-protocol.org) agent with one skill,
`settlement-date-answers`. Send one message and get one message back: a sentence that
gives the answer and cites its sources, and the full answer as JSON. No tasks or streams.

```bash
curl https://api.zeitwide.com/a2a -H "Content-Type: application/json" -H "A2A-Version: 1.0" \
  -d '{"jsonrpc":"2.0","id":1,"method":"SendMessage","params":{"message":{"messageId":"m1",
  "role":"ROLE_USER","parts":[{"text":"Is 2026-12-28 a CHAPS business day?"}]}}}'
```

Questions it reads:

- `Is 2026-12-28 a CHAPS business day?`
- `T+2 from 2026-12-24 on US-FEDWIRE and GB-CHAPS`
- `Next business day after 2026-07-03 on US-FEDACH`
- `CHAPS closures from 2026-12-01 to 2026-12-31`

Dates must be `YYYY-MM-DD`. Calendars go by ID (`GB-CHAPS`) or by the name after the
region (`CHAPS`, `FEDWIRE`, `T2`). With several calendars, every one must be open. For
exact control, send a data part instead of text, with the MCP tools' argument names:
`{"operation": "add_business_days", "calendars": ["GB-CHAPS"], "date": "2026-12-24", "days": 2}`.

Anonymous messages share the anonymous MCP limit. Add `Authorization: Bearer zw_live_…`
for a receipt in the JSON part.

## Discovery files

Each file is public, allows any origin (CORS `*`) and can be cached for an hour.

- [AI catalog](https://zeitwide.com/.well-known/ai-catalog.json): an
  [AI Catalog](https://github.com/Agent-Card/ai-catalog) linking everything below.
- [MCP server card](https://api.zeitwide.com/v1/server-card): an
  [MCP Server Card](https://github.com/modelcontextprotocol/ext-server-card) with the name,
  version, endpoint and protocol versions (also at `/mcp/server-card`).
- [A2A agent card](https://zeitwide.com/.well-known/agent-card.json): the skill, examples,
  endpoint and security schemes.
- [OpenAPI](https://zeitwide.com/openapi.json): the REST API as OpenAPI 3.1.
- [llms.txt](https://zeitwide.com/llms.txt): an index of the site for language models.
- [All docs in one file](https://zeitwide.com/docs/llms-full.txt): every doc and the API
  reference.
- [Receipt keys](https://zeitwide.com/.well-known/zeitwide-keys.json): the Ed25519 keys that
  verify receipts, with `kid`, `alg`, `valid_from` and `valid_to`.

## Check a receipt offline

A receipt names its key in `key_id`. Find that key in `zeitwide-keys.json`, check the
receipt's `issued_at` falls between its `valid_from` and `valid_to` (null means still in
use), and verify the Ed25519 signature over the RFC 8785 canonical JSON of its `payload`.
The open Python library does all of this: `zeitwide verify receipt.json`.
