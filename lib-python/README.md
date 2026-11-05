# zeitwide

Is this a business day, and what is the next one? Answers for payment rails (Fedwire,
FedACH, FedNow, T2, CHAPS) and public holiday calendars, each with the entry that decided
it and the official sources behind it. Here is a CHAPS date, answered offline:

```python
from datetime import date
from zeitwide import ZeitWide

print(ZeitWide().next_business_day("GB-CHAPS", date(2026, 12, 24)).date)  # 2026-12-29
```

```bash
pip install zeitwide            # needs only httpx
pip install "zeitwide[mcp]"     # adds the local MCP server
```

To give an AI agent the same answers, add the local MCP server to its configuration. It
runs offline from the bundled dataset:

```json
{ "mcpServers": { "zeitwide": { "command": "uvx", "args": ["zeitwide-mcp", "--offline"] } } }
```

## Calendars

Each calendar has a page on Zeitwide with its dates and their evidence:

| ID | Calendar |
| --- | --- |
| [`US-FEDWIRE`](https://zeitwide.com/calendars/US-FEDWIRE) | Fedwire Funds Service |
| [`US-FEDACH`](https://zeitwide.com/calendars/US-FEDACH) | FedACH Services |
| [`US-FEDNOW`](https://zeitwide.com/calendars/US-FEDNOW) | FedNow Service |
| [`US-FEDERAL`](https://zeitwide.com/calendars/US-FEDERAL) | US federal holidays |
| [`EU-T2`](https://zeitwide.com/calendars/EU-T2) | T2 (TARGET RTGS) |
| [`GB-CHAPS`](https://zeitwide.com/calendars/GB-CHAPS) | CHAPS |
| [`GB-EAW`](https://zeitwide.com/calendars/GB-EAW) | England and Wales bank holidays |
| [`DE`](https://zeitwide.com/calendars/DE) | Germany: nationwide public holidays |

The same dataset is on GitHub as CSV and JSON (`zeitwide-data`) and on Hugging Face as
Parquet (`zeitwide/payment-rail-calendars`), both under CC BY-NC 4.0.

## Offline

`ZeitWide()` answers from the dataset release bundled with the library. It never touches
the network, and it passes the same conformance suite as the hosted API.

```python
from datetime import date
from zeitwide import ZeitWide

zw = ZeitWide()
zw.is_business_day("US-FEDWIRE", date(2026, 11, 26)).is_business_day  # False
answer = zw.next_business_day(["US-FEDWIRE", "EU-T2"], date(2026, 11, 25))
answer.date, [day.date for day in answer.skipped]  # 2026-11-27, [2026-11-26]
zw.add_business_days("GB-CHAPS", date(2026, 12, 24), 2).date  # 2026-12-30
zw.explain_date("US-FEDWIRE", date(2026, 11, 26)).calendars[0].reason  # 'Thanksgiving Day'
zw.list_closures("EU-T2", date(2026, 1, 1), date(2026, 12, 31)).closures
```

Every answer names its `dataset_release`, the `calendar_revisions` it used and the
`as_of` instant it answers for. Pass `as_of=` to ask what Zeitwide had recorded at an
earlier moment. A date outside a calendar's published coverage raises `OutOfRangeError`:
the library never guesses. `combine="any_open"` asks whether at least one calendar is open
instead of all of them.

## Online

With an API key (free from the Zeitwide app), the same calls ask the hosted API. They
return the same types, with the latest data, `as_of` back to the first recorded version,
and a signed receipt with every answer.

```python
with ZeitWide(key="zw_live_…") as zw:
    answer = zw.is_business_day("US-FEDWIRE", date(2026, 11, 26))
    answer.receipt.id  # 'rcpt_…'
```

The online types come from `api/openapi.yaml` through the generated `zeitwide.api_types`
(`make generate-clients`; never edit them by hand).

## Receipts

Check a receipt offline: its hash, ID, Ed25519 signature, and that its key was valid when
it was issued. The public keys are a bundled copy of `/.well-known/zeitwide-keys.json`
(`--keys` or `KeyRing.parse(...)` to use a newer ring).

```bash
zeitwide verify answer.json      # a receipt, or an answer that carries one; '-' for stdin
```

```python
from zeitwide import verify_receipt

verify_receipt(answer.receipt)  # Verification(valid=True, …), falsy when invalid
```

## Webhooks

```python
from zeitwide import webhooks

event = webhooks.verify(request.body, request.headers["Zeitwide-Signature"], secret)
```

It checks the HMAC-SHA256 signature over the raw body and refuses deliveries signed more
than five minutes from now. It returns the event only once verified, and raises
`WebhookVerificationError` otherwise.

## Local MCP server

```bash
uvx zeitwide-mcp --offline
```

`--offline` answers from the bundled release. Without it, the server asks the hosted API
with the key in `ZEITWIDE_API_KEY`. With the library installed, `zeitwide mcp --offline`
runs the same server. In an MCP client:

```json
{ "mcpServers": { "zeitwide": { "command": "uvx", "args": ["zeitwide-mcp", "--offline"] } } }
```

Tools: `is_business_day`, `next_business_day`, `add_business_days`, `explain_date` and
`list_closures`, with the same names, arguments and answers as the hosted server at
`/mcp` (offline answers have no receipt), plus `verify_webhook`.

## Licenses

The code is MIT ([LICENSE](LICENSE)). The bundled dataset is under CC BY-NC 4.0
([LICENSE-DATA](LICENSE-DATA)); commercial use of the data needs the hosted API or a
separate agreement.

## Development

```bash
uv sync --all-packages
uv run python -m ruff format . && uv run python -m ruff check .
uv run python -m mypy
uv run python -m pytest --cov
```

The bundled release in `src/zeitwide/data/releases/` must be the newest file in
`dataset/releases/` (a test checks); copy a new release there when one is cut. The
`zeitwide-mcp/` folder is a launcher package so `uvx zeitwide-mcp` works; keep its version
in step. Tag `lib-python-v<version>` to publish both to PyPI
(`.github/workflows/publish-lib-python.yml`). The library supports Python 3.12 and later,
and CI tests 3.12, 3.13 and 3.14.
