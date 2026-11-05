# zeitwide-mcp

Zeitwide's local MCP server over stdio. It answers "is this a business day, and what is
the next one?" for payment rails (Fedwire, FedACH, FedNow, T2, CHAPS) and public holiday
calendars. Each answer comes with the entry that decided it and its official sources.

```bash
uvx zeitwide-mcp --offline
```

`--offline` answers from the dataset release bundled with the
[`zeitwide`](https://pypi.org/project/zeitwide/) library, with no network. Without it,
the server asks the hosted Zeitwide API with the key in `ZEITWIDE_API_KEY`.

For example, in an MCP client's configuration:

```json
{
  "mcpServers": {
    "zeitwide": { "command": "uvx", "args": ["zeitwide-mcp", "--offline"] }
  }
}
```

Tools: `is_business_day`, `next_business_day`, `add_business_days`, `explain_date`,
`list_closures` (the same names, arguments and answers as the hosted server at `/mcp`)
and `verify_webhook`.

This package only launches the server: the code is in `zeitwide` (MIT) and the bundled
dataset is under CC BY-NC 4.0.

Also listed in the [MCP Registry](https://registry.modelcontextprotocol.io) as
`io.github.susmitbanerjee/zeitwide`, with the hosted server at `https://api.zeitwide.com/mcp`,
which needs no key.

<!-- mcp-name: io.github.susmitbanerjee/zeitwide -->
