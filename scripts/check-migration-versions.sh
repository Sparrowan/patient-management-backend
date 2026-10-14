#!/usr/bin/env bash
# Guards the one hazard sequential migration versions carry: the version number is a shared
# namespace that git cannot referee. Two branches can each add V12__…, merge cleanly, and only fail
# at application start-up — in whichever environment deploys first.
#
# This catches it at PR time instead. Checked per service, because each owns its own schema and the
# same version number in two different services is perfectly fine.
#
# Deliberately not solved by switching to timestamp versions: timestamps make collisions impossible
# but make *out-of-order* merges silent, and an untested apply order in production is worse than a
# loud failure in CI. See the migration note in CLAUDE.md.
set -euo pipefail

status=0
for dir in */src/main/resources/db/migration; do
    [ -d "$dir" ] || continue
    service="${dir%%/*}"

    duplicates=$(
        find "$dir" -maxdepth 1 -name 'V*__*.sql' -exec basename {} \; \
            | sed -E 's/^V([0-9]+)__.*/\1/' \
            | sort -n | uniq -d
    )
    if [ -n "$duplicates" ]; then
        echo "FAIL  $service: duplicate migration version(s): $(echo "$duplicates" | tr '\n' ' ')"
        echo "      Two migrations share a version — renumber the later one before merging."
        status=1
        continue
    fi

    count=$(find "$dir" -maxdepth 1 -name 'V*__*.sql' | wc -l | tr -d ' ')
    echo "ok    $service: $count migrations, versions unique"
done

exit $status
