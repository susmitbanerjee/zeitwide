# Discovery schemas

JSON Schemas for the documents agents use to find Zeitwide. `api/` tests validate every
document the API serves against these, and the repo-root `server.json` against the MCP
Registry's schema.

| File | Validates | Source |
| --- | --- | --- |
| `server-card.schema.json` | `/v1/server-card` and `/mcp/server-card` | Copied unchanged from [modelcontextprotocol/ext-server-card](https://github.com/modelcontextprotocol/ext-server-card) `schema.json` @ `526201bbc80231daa40ffcdecfc9da4e54e5dc93` (SEP-2127) |
| `mcp-registry-server.schema.json` | `server.json` | Copied unchanged from [modelcontextprotocol/registry](https://github.com/modelcontextprotocol/registry) `internal/validators/schemas/2025-12-11.json` @ `a48b6a733e808366d5e1fda65ab5fbfb5478e9fd` |
| `a2a-agent-card.schema.json` | `/.well-known/agent-card.json` | Written from `AgentCard` in [a2aproject/A2A](https://github.com/a2aproject/A2A) `specification/a2a.proto` @ `4735f4876449c6f381825d8a841b31b16c6ad0c4` (A2A 1.0); the project commits no JSON Schema |
| `ai-catalog.schema.json` | `/.well-known/ai-catalog.json` | Written from [Agent-Card/ai-catalog](https://github.com/Agent-Card/ai-catalog) `specification/ai-catalog.md` @ `9aac2c1bfa622356e45a31f3dc1d342bdb329e00` (spec 1.0); the spec publishes no JSON Schema |
| `zeitwide-keys.schema.json` | `/.well-known/zeitwide-keys.json` | Ours; matches `ReceiptKeySet` in `api/openapi.yaml` |

To update a copied schema, replace the file with the upstream one and change the commit
above in the same commit.
