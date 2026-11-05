# Outreach checklist

Manual, on purpose: nothing here is automated, scheduled or scripted. The open dataset
(GitHub `zeitwide-data`, Hugging Face `zeitwide/payment-rail-calendars`, PyPI `zeitwide`) is
published by the release builder; this file is for the human part, getting people to find it.

The rule behind every item: be useful first, and say who we are.

## Stack Overflow and GitHub questions

Answer real questions about payment-rail holidays, bank holidays and business-day
calculations (Fedwire, FedACH, T2/TARGET, CHAPS, "is X a bank holiday").

- [ ] The question is real, recent and unanswered or badly answered. Skip anything that
      looks seeded, and skip threads where the asker has already solved it.
- [ ] The answer is **useful without Zeitwide**: the rule, the official source (Federal
      Reserve, ECB, GOV.UK), the dates, a few lines of code. Someone who never clicks a
      Zeitwide link still has their answer.
- [ ] Always disclose, in the answer itself and not only in a profile: **"I build
      Zeitwide."** Put it before any link to Zeitwide, in plain words.
- [ ] Link Zeitwide only where it adds something the answer can't (the evidence page for a
      date, the offline library, the dataset), and only one link. Prefer the free offline
      library or the open data over the hosted API.
- [ ] Follow the site's self-promotion rules (Stack Overflow's "Help, I'm using this site
      to promote my product" and each repository's contributing guide). When in doubt, leave
      it out.
- [ ] One answer per question, none copied between threads, a few a week at most. If an
      answer would only be a link, don't post it.
- [ ] Correct our own errors in public. If an answer turns out wrong, edit it and say so.

## Awesome-lists

Submit by pull request, never by email or direct edit, to lists where the entry fits
(awesome lists for fintech, payments, MCP servers, datasets, open data, Python finance
libraries, holiday data).

- [ ] Read the list's contributing guide and its existing entries; follow the format,
      ordering and one-line style exactly.
- [ ] The entry fits the list: an open Python library on PyPI (`zeitwide`), an MCP server
      (`zeitwide-mcp`), or an open dataset (GitHub `zeitwide-data`, Hugging Face). One entry
      per list.
- [ ] A neutral, factual description with no superlatives ("Business-day answers for
      payment rails, offline, with sources"). Say that the data is CC BY-NC 4.0 and the
      code MIT.
- [ ] State in the PR description that we are the authors ("I build Zeitwide").
- [ ] Respect a rejection or a request to wait. Don't resubmit unchanged.
- [ ] Track the PR here when opened: list, link, date, outcome.

## Never

- [ ] **Never post on Wikipedia or Wikidata about our own product** or the dataset: no edits,
      no references to our pages, no talk-page requests worded to get a link added, no
      sockpuppet or friend edits. Conflict-of-interest rules apply to us. If something about
      the payment rails or a holiday is wrong on Wikipedia, fix it with the official source
      and without any Zeitwide link, or leave it.
- [ ] No fake or purchased reviews, stars, downloads or upvotes.
- [ ] No unsolicited direct messages or cold email to people who posted a question.
- [ ] No answers that pretend to be from a neutral user.

## Before sharing a release

- [ ] The release is out: GitHub tag, Hugging Face revision and PyPI version all show the
      same release ID (`MANIFEST.json` in each data repository has the release ID and the
      release's SHA-256).
- [ ] Each artefact links back to the calendar pages on zeitwide.com (the builder does this;
      open one link from each to check).
- [ ] The README's example still prints the date in its comment.
