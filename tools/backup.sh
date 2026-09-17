#!/usr/bin/env bash
#
# Backs the repository up as a single git bundle, OUTSIDE the working tree.
#
# WHY THIS EXISTS: the object store was destroyed once during a session — .git/refs
# gone, and .git/objects/pack holding only the .idx while the .pack itself was
# missing. There was no remote, so nothing could be fetched back and the commit
# history was unrecoverable; only the working tree survived, and it survived by
# luck. A bundle is a complete, self-contained copy of every ref and object that a
# `git clone` can be made from — cheap enough to run often.
#
# USAGE
#   tools/backup.sh                          # writes to $OPENTVCAST_BACKUP_DIR
#   OPENTVCAST_BACKUP_DIR=/mnt/backup tools/backup.sh   # put it on another disk
#
# RESTORE
#   git clone <bundle> restored-repo
#
# A NOTE ON THE ENVIRONMENT: this repository lives under a sandbox that has, at
# least once, discarded files created during a session. Keeping backups on a path
# the sandbox does not own — and preferably on another machine or a remote —
# is the only version of this that is worth anything.

set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
# Default is deliberately outside the repository: a bundle kept inside the tree it
# backs up disappears with the tree. Prefer another disk or another machine.
backup_dir="${OPENTVCAST_BACKUP_DIR:-$HOME/opentvcast-backups}"
stamp="$(date +%Y%m%d-%H%M)"
bundle="$backup_dir/opentvcast-$stamp.bundle"

mkdir -p "$backup_dir"

# --all covers every branch and tag; a bundle with no refs is not restorable.
git -C "$repo_root" bundle create "$bundle" --all

echo "wrote $bundle"
echo
echo "verify with:  git bundle verify '$bundle'"
echo "restore with: git clone '$bundle' restored-opentvcast"

# Keep the last 10 only: a bundle per run adds up, and ten is more than enough
# to survive "I broke it and only noticed a week later".
ls -1t "$backup_dir"/opentvcast-*.bundle 2>/dev/null | tail -n +11 | while read -r old; do
    rm -f "$old"
    echo "pruned $old"
done
