# settlecal

The open SettleCal library (MIT) and, later, the local MCP server
(`uvx settlecal-mcp --offline`) running on the bundled dataset release.

```python
from datetime import date
from pathlib import Path

from settlecal import add_business_days, load_release

release = load_release(Path("2026.10.29.1.json"))
add_business_days(release, ["US-FEDWIRE", "EU-T2"], date(2026, 11, 24), 2).date
# datetime.date(2026, 11, 27)
```

Offline answers need only the standard library. The online client for the hosted API, with
the evidence behind every answer, needs `pip install "settlecal[online]"`:

```python
from settlecal.client import SettleCalClient

with SettleCalClient(api_key="sc_live_…") as api:
    api.day("US-FEDWIRE", date(2026, 11, 26))["is_business_day"]  # False
```

Its types (`settlecal.api_types`) are generated from `api/openapi.yaml` by
`make generate-clients`; never edit them by hand.

```bash
uv sync
uv run pytest
```
