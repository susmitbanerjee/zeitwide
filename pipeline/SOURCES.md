# Sources

What each source is, what its robots.txt and terms allow, and how the pipeline uses it.
Checked on 2026-10-28 with the `ZeitwideBot` User-Agent. Re-check before adding a
source and once a year.

**How Zeitwide uses every source.** It stores facts (a date is open or closed, and why)
and a link to the page, never copied page text, in anything it publishes. It keeps raw
snapshots privately as evidence, in the snapshots bucket and as test fixtures. It
fetches at most once a day per source, with conditional requests, at least one second
apart per host or longer when robots.txt asks.

> **Keep this repository private while `tests/fixtures/frbservices-holiday-schedules/`
> and `tests/fixtures/bundesbank-holidays/` are in it.** The Federal Reserve Banks' terms
> forbid reproducing or redistributing their pages, and the Bundesbank states no reuse
> terms (see below). The facts are free to use, but the copies kept as fixtures must not
> be published. Move those fixtures to private storage before the repository goes public.

## Monitored sources

| Source ID | Publisher | Calendars | robots.txt | Terms |
| --- | --- | --- | --- | --- |
| `frbservices-holiday-schedules` | Federal Reserve Banks | US-FEDWIRE, US-FEDACH, US-FEDNOW | Allowed | Restrictive: facts only, no republishing |
| `federalreserve-k8` | Board of Governors of the Federal Reserve System | US-FEDWIRE, US-FEDACH, US-FEDNOW | Allowed (no robots.txt) | Public domain; cite the Board |
| `ecb-public-holidays` | European Central Bank | EU-T2 | Allowed; `Crawl-delay: 5` | Free reuse; cite the ECB |
| `govuk-bank-holidays` | GOV.UK | GB-EAW, GB-CHAPS | Allowed | Open Government Licence v3.0 |
| `opm-federal-holidays` | U.S. Office of Personnel Management | US-FEDERAL | Allowed | Public domain (U.S. government work) |
| `bundesbank-holidays` | Deutsche Bundesbank | DE; corroborates EU-T2 | Allowed | No reuse terms found: facts only, no republishing |

### frbservices-holiday-schedules

- **URL:** <https://www.frbservices.org/about/holiday-schedules/>
- **robots.txt:** only `/wp-content/uploads/aba-search-list.csv` is disallowed.
- **Terms:** <https://www.frbservices.org/terms>. The site "contains copyrighted
  material" that may not be reproduced, published or redistributed "except as expressly
  authorized". It also prohibits using the site "for reasons other than its intended
  purposes" and using the Banks' names commercially.
  - Holiday dates are facts, so Zeitwide stores the dates and links to the page.
  - It never republishes the page or uses the Banks' names in marketing.
  - The admin review screen shows snapshots to reviewers only.
- **Content:** one table, with a row per holiday and a column per year (five years),
  each cell a nominal date. `*` marks a Saturday: the Reserve Banks open the Friday
  before. `**` marks a Sunday: they close the Monday after.
- **Parser:**
  - It checks every marker against the weekday and requires both observance footnotes,
    so a rule change fails loudly.
  - It applies each calendar's observance rule from the catalog.
  - It emits closures for Fedwire and FedACH, and "open" for FedNow, which runs every
    day.
  - It ignores the FedACH table of processing pauses around each holiday; that is
    future work.
- **Snapshots:** `live-2026-10-28` (2026–2030, about 4 MB) and `archive-2025-03`
  (Internet Archive capture, 2025–2029, an older layout of about 90 KB).

### federalreserve-k8

- **URL:** <https://www.federalreserve.gov/aboutthefed/k8.htm>
- **robots.txt:** the site has none (404), which allows everything.
- **Terms:** <https://www.federalreserve.gov/disclaimer.htm>. "Unless otherwise
  indicated, information on Board's website is in the public domain"; please cite the
  Board as the source. No framing.
- **Content:** the same table format as frbservices, and its corroborating source. Both
  come from the Federal Reserve System, but they are published separately: the Board
  publishes K.8 and the Reserve Banks publish their schedule.
  - The March 2025 version marks 20 January 2025 with `***`. That points to a footnote
    about MLK Day falling on Inauguration Day; the Reserve Banks were still closed.
  - The parser accepts three or more stars only on a weekday, and only when the
    footnote is on the page.
- **Snapshots:** `live-2026-10-28` and `archive-2025-03`.

### ecb-public-holidays

- **URL:** <https://www.ecb.europa.eu/ecb/contacts/working-hours/html/index.en.html>
- **robots.txt:** only localized `_content` fragments and media folders are disallowed.
  It sets `Crawl-delay: 5`, which the fetcher honours.
- **Terms:** <https://www.ecb.europa.eu/services/disclaimer/html/index.en.html>. Free
  reuse as long as the information is accurate and "the ECB must be cited as the
  source". If sold, buyers must be told the information is free on the ECB's website.
- **Content:** a table per year of ECB public holidays. Entries marked `*` are TARGET
  closing days (legend "`* : TARGET closing day`").
  - Starred days close T2.
  - Unstarred weekday holidays (Ascension Day, Day of German Unity and so on) are ECB
    office closures on which T2 is open.
  - Unstarred weekend holidays are skipped.
  - The parser requires the legend and exactly six closing days per year.
- **Snapshots:** `live-2026-10-28` (2026–2028) and `archive-2025-03` (2025–2027).

### govuk-bank-holidays

- **URL:** <https://www.gov.uk/bank-holidays.json>
- **robots.txt:** allowed. It disallows only `*/print` pages, site search and a few
  named crawlers.
- **Terms:** <https://www.gov.uk/help/terms-conditions>. Crown copyright under the
  [Open Government Licence v3.0](https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/):
  free reuse, including commercial, with attribution ("Contains public sector
  information licensed under the Open Government Licence v3.0").
- **Content:** JSON by division. Only `england-and-wales` is used. Substitute days are
  already listed (for example "Boxing Day (Substitute day)").
  - Each date closes GB-EAW and GB-CHAPS, because the Bank of England states that
    CHAPS is closed on England and Wales bank holidays (see GB-CHAPS below).
- **Snapshots:** `live-2026-10-28` (2019–2028) and `archive-2025-03` (2019–2027).

### opm-federal-holidays

- **URL:** <https://www.opm.gov/policy-data-oversight/pay-leave/federal-holidays/>
- **robots.txt:** allowed. It disallows only search, login and print paths.
- **Terms:** a work of the U.S. Government, so not subject to copyright in the United
  States (17 U.S.C. § 105). The site states no other reuse terms.
- **Content:** a table per year (captioned "`YYYY Holiday Schedule`") of observed dates,
  each printed with its weekday. OPM has already applied 5 U.S.C. 6103(b): Saturday
  holidays move to Friday and Sunday holidays to Monday.
  - **Weekday check:** the parser checks every printed weekday. A New Year's Day on a
    Saturday is observed on 31 December of the year before.
  - **Inauguration Day is skipped:** it is a holiday only for federal employees in the
    Washington, DC, area.
  - **Older tables are skipped:** the parser reads only tables from the year before the
    fetch onward. The archive goes back to 2011, and the 2011 table prints Monday,
    26 December 2011 as a Tuesday.
- **Snapshots:** `live-2026-10-28` (2025–2030) and `archive-2025-03` (2024–2030).

### bundesbank-holidays

- **URL:** <https://www.bundesbank.de/en/tasks/payment-systems/target/holiday-calendar-764518>,
  a stable page that redirects to the current year's (for example `.../public-holidays-in-germany-2027-764518`).
  It links to the year's PDF, whose URL changes with each edition. The source follows
  that one link (`document` in the source module); the link must stay on the page's host,
  over https.
- **robots.txt:** allowed. Only `/.enodia/challenge`, the site's bot-challenge path, is
  disallowed. No challenge was served to the pipeline's requests.
- **Terms:** the [imprint](https://www.bundesbank.de/en/homepage/imprint) states no reuse
  terms that I could find. Treat it like frbservices:
  - store the dates and link to the page;
  - never republish the PDF;
  - keep the PDF copies in `tests/fixtures/bundesbank-holidays/` out of any public
    repository.
- **Content:** a two-page PDF, "Public holidays in Germany in YYYY" (the English edition;
  the German one has the same layout).
  - TARGET holidays (six) become EU-T2 closures. This is a second, independent source
    for T2, whose operator is the ECB.
  - Public holidays throughout the Federal Republic of Germany become DE closures.
  - Holidays in only some states, and the Bundesbank's own closing days (24 and 31
    December), are not part of either calendar.
  - The parser reads at most five pages. It requires the title, all four section
    headings and the weekend rule, exactly six TARGET holidays and at least nine
    nationwide ones.
- **Editions:** the page shows one year at a time. The seed reads earlier editions with
  `--also bundesbank-holidays=<Internet Archive capture of the PDF>`.
- **Snapshots:** `live-2026-10-28` (the 2027 edition, via the stable page) and
  `archive-2025-11` (the 2026 edition, an Internet Archive capture of 26 November 2025).

## Rule sources (not monitored)

These pages state rules rather than list dates. Their facts are in the calendar catalog
(`src/zeitwide_pipeline/calendars/catalog/`), each with a `provenance` entry. They are
not yet monitored for changes.

| Page | Used for | robots.txt | Terms |
| --- | --- | --- | --- |
| [Board: FedNow Service](https://www.federalreserve.gov/paymentsystems/fednow_about.htm) | US-FEDNOW: 24x7x365, a business day every day including weekends and holidays | Allowed | Public domain; cite the Board |
| [Fedwire Funds Service operating hours](https://www.frbservices.org/resources/financial-services/wires/fedwire-funds-service-national-settlement-service-operating-hours/) | US-FEDWIRE: opens 9:00 p.m. ET the day before, closes 7:00 p.m. ET, cutoffs | Allowed | Restrictive (frbservices, above) |
| [FedACH Processing Schedule](https://www.frbservices.org/resources/resource-centers/same-day-ach-resource-center/fedach-processing-schedule/) | US-FEDACH: transmission deadlines and settlement times (effective 12 Sept 2022) | Allowed | Restrictive (frbservices, above) |
| [Bank of England: CHAPS](https://www.bankofengland.co.uk/payments/chaps) | GB-CHAPS: 6am–6pm, Monday to Friday, excluding England and Wales bank holidays; cutoffs | Allowed (search, forms and some apps are disallowed) | Restrictive: see below |

**Bank of England terms:** <https://www.bankofengland.co.uk/legal>. Content may be used
"for personal use or internal use within an individual organisation for non-commercial
purposes"; commercial reuse needs permission (enquiries@bankofengland.co.uk). The rule
is a fact, so Zeitwide states it and links to the page. Monitoring the page would mean
storing copies of it, which these terms don't clearly allow for a commercial service.

**Decision (2026-10-28): GB-CHAPS dates come from GOV.UK only.** The Bank of England
publishes no list of CHAPS closing dates, only the rule, so its page stays a rule
reference in the calendar catalog and is not monitored. As a result:
- CHAPS entries are basis=projected, because the operator didn't list the dates itself.
- CHAPS candidates are flagged `single_source` in the review queue.

Revisit this if the Bank of England publishes CHAPS closing dates, or if a second
independent source for England and Wales bank holidays appears.

## Adding a source

1. Check robots.txt and the terms; add a section here.
2. Add the module in `src/zeitwide_pipeline/sources/catalog/` and any new calendar in
   `src/zeitwide_pipeline/calendars/catalog/`.
3. Save two real snapshots: the live page, and an older one, for example an Internet
   Archive `id_` capture, to catch layout drift.

   ```bash
   uv run zeitwide-pipeline save-fixture <source-id> live-YYYY-MM-DD
   ```

   ```bash
   uv run zeitwide-pipeline save-fixture <source-id> archive-YYYY-MM --url "https://web.archive.org/web/YYYYMMDD000000id_/<page url>"
   ```

4. Write `<name>.expected.json` from the parser's output only after checking the dates by
   hand. `tests/test_sources.py` then runs them as goldens.
5. Run `uv run zeitwide-pipeline dry-run <source-id>` against the live site.
