# Translations

English is the **only authoritative documentation** for this SDK. Everything
under `docs/<lang>/` is a translation of an English source file and is expected
to lag behind it. When a translation and the English original disagree, the
English original is correct.

## Languages

| Code | Language | Directory |
|------|----------|-----------|
| `zh` | 简体中文 (Simplified Chinese) | [`docs/zh/`](./zh/) |
| `ja` | 日本語 (Japanese) | [`docs/ja/`](./ja/) |
| `ko` | 한국어 (Korean) | [`docs/ko/`](./ko/) |
| `pt` | Português (Portuguese) | [`docs/pt/`](./pt/) |
| `es` | Español (Spanish) | [`docs/es/`](./es/) |

## File mapping

Each language directory mirrors `docs/` one-to-one, with two renames so that
the repository root README and the documentation index can coexist in the same
directory:

| English source | Translated path |
|----------------|-----------------|
| `README.md` (repository root) | `docs/<lang>/README.md` |
| `docs/README.md` (documentation index) | `docs/<lang>/index.md` |
| `docs/<name>.md` (everything else) | `docs/<lang>/<name>.md` |

`docs/<lang>/README.md` is the landing page the language switcher links to, so
it holds the translated *project* README.

## Deliberately English-only

Three documents are **not** translated. The first two are linked from each
translated index; this file is contributor documentation and is not linked from
them at all:

| Document | Why |
|----------|-----|
| `docs/CHANGELOG.md` | Append-only release history. It grows with every release, so a translation is stale the moment the next one lands, and its content is almost entirely version numbers, API identifiers and commit-style entries. |
| `docs/PYTHON_SDK_PARITY.md` | A parity tracker aimed at contributors comparing the two SDKs, not at people learning this one. It churns on every Python-SDK sync. |
| `docs/TRANSLATIONS.md` | This file. It is the process contributors follow to produce the translations, not documentation of the SDK, and translating it would put the rules themselves out of sync. |

`scripts/check-translations.sh` skips all three, and reports a `STRAY` finding if
a translation of one appears in a language directory — nothing would keep it in
sync, and no index links to it.

To change this, edit `NOT_TRANSLATED` in `scripts/check-translations.sh` and
update this section.

## What gets translated

- **Translated:** prose, headings, table cells, list items, admonitions, link
  text.
- **Not translated:** code blocks and their comments, Java/XML/JSON
  identifiers, Maven coordinates, CLI flags, environment variable names, file
  paths, URLs, and the exception-hierarchy diagrams.

Leaving code blocks byte-identical to the English source is deliberate. It
keeps `diff` between a translation and its original meaningful, it means a code
fix has to be applied in exactly one place, and it removes the largest source
of translation errors in a document that is mostly code.

## Keeping translations in sync

Translations carry no version stamp; staleness is tracked through git. The
check is:

```bash
./scripts/check-translations.sh
```

It compares the last commit that touched each English source against the last
commit that touched each translation, and reports every translation that is
older than its source, plus any that are missing entirely.

CI runs the same script on pull requests that touch `docs/` or `README.md`
(`.github/workflows/translation-sync.yml`). **It reports, it does not block.**
Requiring five translations to land in the same pull request as an English
documentation fix would mean either delaying the fix or filling the
translations with machine output nobody reviewed — so an English-only change is
allowed to merge, and the report says what fell behind.

## Contributing a translation update

1. Change the English source first, in its own commit if convenient.
2. Update `docs/<lang>/<file>.md` for the languages you can actually read.
3. Keep code blocks byte-identical to the English source.
4. Run `./scripts/check-translations.sh` to see what is still behind.

Corrections to an existing translation are welcome and do not need to touch
any other language. Native-speaker review is especially welcome: the initial
translations were machine-produced and have not been reviewed by a speaker of
every language.

## Adding a new language

1. Create `docs/<code>/` using the ISO 639-1 code.
2. Add the language to `LANGS` in `scripts/check-translations.sh`.
3. Add it to the table above, and to the switcher block at the top of
   `README.md` and `docs/README.md`.
4. Translate at minimum `README.md` and `index.md`; the check script reports
   the rest as missing until they exist.
