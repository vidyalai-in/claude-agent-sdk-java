#!/usr/bin/env bash
#
# Reports translations under docs/<lang>/ that are missing, or older than the
# English source they were translated from.
#
# "Older" means the last commit touching the translation is an ancestor of the
# last commit touching its English source. File mtimes are useless here: a
# fresh clone gives every file the checkout time.
#
# Exit status is 0 even when translations are stale — see docs/TRANSLATIONS.md
# for why this reports rather than blocks. Pass --strict to exit 1 instead.

set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

LANGS=(zh ja ko pt es)

# Documents that are deliberately English-only. Both are append-only records
# that grow every release and consist mostly of version numbers and API
# identifiers, so a translation is stale the moment the next release lands.
# Each translated index links to the English original instead.
# See docs/TRANSLATIONS.md.
NOT_TRANSLATED=(CHANGELOG.md PYTHON_SDK_PARITY.md)

STRICT=0
[ "${1:-}" = "--strict" ] && STRICT=1

# Maps an English source path to its path inside a language directory.
# The repository README and the docs index would otherwise collide.
translated_path() {
    local lang="$1" source="$2"
    case "$source" in
        README.md)      echo "docs/$lang/README.md" ;;
        docs/README.md) echo "docs/$lang/index.md" ;;
        docs/*)         echo "docs/$lang/${source#docs/}" ;;
    esac
}

last_commit() {
    git log -1 --format=%H -- "$1" 2>/dev/null
}

is_excluded() {
    local base="$1"
    for skip in "${NOT_TRANSLATED[@]}"; do
        [ "$base" = "$skip" ] && return 0
    done
    return 1
}

sources=(README.md docs/README.md)
while IFS= read -r f; do
    is_excluded "$(basename "$f")" && continue
    sources+=("$f")
done < <(find docs -maxdepth 1 -name '*.md' ! -name 'README.md' ! -name 'TRANSLATIONS.md' | sort)

stale=0
missing=0

for source in "${sources[@]}"; do
    source_commit=$(last_commit "$source")
    [ -z "$source_commit" ] && continue

    for lang in "${LANGS[@]}"; do
        target=$(translated_path "$lang" "$source")

        if [ ! -f "$target" ]; then
            printf 'MISSING  %s\n' "$target"
            missing=$((missing + 1))
            continue
        fi

        target_commit=$(last_commit "$target")
        [ -z "$target_commit" ] && continue
        [ "$target_commit" = "$source_commit" ] && continue

        # Stale only when the translation's commit predates the source's.
        if git merge-base --is-ancestor "$target_commit" "$source_commit" 2>/dev/null; then
            printf 'STALE    %-40s behind %s\n' "$target" "$source"
            stale=$((stale + 1))
        fi
    done
done

# An excluded document must not have a translation sitting in a language
# directory: nothing keeps it in sync, and the index does not link to it.
stray=0
for lang in "${LANGS[@]}"; do
    for skip in "${NOT_TRANSLATED[@]}"; do
        if [ -f "docs/$lang/$skip" ]; then
            printf 'STRAY    %-40s English-only; see docs/TRANSLATIONS.md\n' "docs/$lang/$skip"
            stray=$((stray + 1))
        fi
    done
done

total=$((stale + missing + stray))
if [ "$total" -eq 0 ]; then
    echo "All translations are up to date with their English sources."
    exit 0
fi

printf '\n%d stale, %d missing, %d stray.\n' "$stale" "$missing" "$stray"
echo "English is authoritative; see docs/TRANSLATIONS.md."

[ "$STRICT" -eq 1 ] && exit 1
exit 0
