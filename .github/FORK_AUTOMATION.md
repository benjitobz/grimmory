# Fork automation

Keeps this fork tracking `grimmory-tools/grimmory` and publishes images that carry the
embedded MariaDB patch (`Dockerfile` + `packaging/docker/entrypoint.sh`).

Feature patches ride along (all default to upstream behaviour). They are proposed upstream from
`feat/external-koreader-sync`; keep that branch and this one identical for these files.

| Patch | Files | Why |
| --- | --- | --- |
| admin setting `KOREADER_SYNC_SETTINGS` (Application page, "External KOReader Sync": "Use BookBridge or external for KOReader Sync" + URL + KOReader shelf name + "Let users edit their KOReader login", stored in `app_settings`, no migration): the KOReader device page shows that server and hides the built-in toggles; the reader's login is created by the server and read-only unless user edits are allowed (`PUT /api/v1/koreader-users/me` is refused for non-admins otherwise) | `AppSettingKey`, `KoreaderSyncSettings`, `AppSettings`, `PublicAppSetting`, `AppSettingService`, `KoreaderUserService`, `KoreaderShelfService`, `app-settings.*`, `global-preferences.component.*`, `koreader-settings-component.*`, `i18n/en.json` | readers get their KOReader/BookBridge sync details from Grimmory; provisioning issues the sync codes and BookBridge mirrors them |
| bundled shelf icons (Kobo, KOReader, Audiobookshelf) copied into the icons folder at startup; the Kobo sync shelf uses the Kobo icon | `BundledIconService`, `resources/bundled-icons/*.svg`, `ShelfType`, `KoboSettingsService` | the device shelves get recognisable icons on a fresh install |

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

`embedded-database` is built by the sync workflow when the upstream merge changed it, on a
manual run with `force_build`, and on every push made by a person (the workflow's own merge
pushes are skipped so they are not built twice).

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
