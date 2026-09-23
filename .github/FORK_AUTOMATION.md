# Fork automation

Keeps this fork tracking `grimmory-tools/grimmory` and publishes images that carry the
embedded MariaDB patch (`Dockerfile` + `packaging/docker/entrypoint.sh`).

Two small backend patches ride along (both default to upstream behaviour):

| Patch | Files | Why |
| --- | --- | --- |
| `KOBO_SHELF_NAME` env var picks the shelf the native Kobo sync ships (default `Kobo`) | `backend/.../model/enums/ShelfType.java` | the shelf is called "Kobo OS" on the media server |
| admins may assign/unassign books on public shelves they do not own | `backend/.../service/book/BookUpdateService.java` (+ tests) | BookBridge files paired ebooks on one shared public shelf under more than one login |

## Branches

| Branch | Contents | Updated by |
| --- | --- | --- |
| `develop` | hard mirror of upstream `develop` | force-pushed by the sync workflow |
| `embedded-database` | upstream `develop` + patch + this automation. **Default branch.** | `git merge develop` |

`embedded-database` is the default branch because GitHub only runs `schedule:` workflows from
the default branch, and `develop` is overwritten on every sync.

## Images

Published to `ghcr.io/benjitobz/grimmory`, `linux/amd64` only.

| Tag | Source |
| --- | --- |
| `develop`, `develop-<sha>` | `embedded-database` |

The release stream (`embedded-main`, `latest`, `<x.y.z>`) was retired on 2026-09-23; the
`latest` and version tags still on GHCR predate that and carry neither backend patch.

## Required setup

1. **Actions** — enable them on this fork (forks start with Actions off).
2. **`SYNC_TOKEN` secret** — a PAT with `repo` + `workflow` scope. Required because mirroring
   `develop` rewrites files under `.github/workflows/`, which the built-in `GITHUB_TOKEN` may
   not push.
3. **Default branch** — set to `embedded-database`.
4. **Disable upstream's workflows** in the Actions tab, keeping only the two `Fork - *` ones.
   Several are scheduled or fire on pushes to `develop`, so they would otherwise run on every
   sync and try to publish upstream's own images from this fork.

## When a sync conflicts

The run fails and the job summary carries the resolution commands. Conflicts can only really
come from upstream editing `Dockerfile` or `packaging/docker/entrypoint.sh`. Issues are disabled
on this fork, so the failure notification is the emailed Actions failure.
