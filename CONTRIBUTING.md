# Contributing

Contributions are welcome — particularly device support beyond the Fairphone 6,
and anything on the [SECURITY.md](SECURITY.md) roadmap.

## Before you open a pull request

**Run the preflight checks.** They take under a second and catch several classes
of error that otherwise cost a ~110-minute build cycle, or surface only at flash
time:

```bash
python3 tools/lowha-preflight.py .
python3 tools/check-resources.py
```

**Explain the reasoning, not just the change.** This codebase has a dense
comment style in the places where the non-obvious choice was made. That is
deliberate: nearly every one of those comments exists because someone lost time
to the obvious-looking alternative. If you change one of those decisions, update
the comment and the relevant
[decision record](docs/decisions/).

## Architecture decision records

Significant design changes get an ADR in `docs/decisions/`, numbered
sequentially. Keep them short: context, decision, consequences. Record what was
rejected and why — that is usually the part worth having later.

## Code style

Follow the surrounding code, which follows AOSP conventions. Kotlin for new app
code. Four-space indent, 100-column soft limit.

## Two things that will bite you

**Apostrophes in Android string resources** must be escaped as `\'`. Not
`&#39;` — that is valid XML but `aapt2` rejects it with "unescaped apostrophe
in string".

**`--` is illegal inside an XML comment.** The build failure points at the file,
not at the comment. Preflight check D catches both.

## Testing

There is no automated test suite for the enforcement path yet, because the
behaviour that matters — the framework refusing to launch a suspended package —
is only observable on a real device. Changes to enforcement should be verified
by hand on hardware, and the verification described in the pull request.

Building the case for a proper instrumentation harness is itself a welcome
contribution.
