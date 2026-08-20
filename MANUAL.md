# MITSA Manual

This is the full command reference. For getting MITSA installed in the
first place, see [INSTALL.md](INSTALL.md) instead — this document
assumes `mitsa` already runs.

MITSA does three jobs for any number of Walter's Java apps, from one
shared config root:

- **Downloader** — fetches an app's jar from its GitHub releases and
  caches it locally (`mitsa update`).
- **Updater** — re-checks for a newer release and refreshes the cache
  in place, with no version literal ever baked into a shim or shortcut
  (`mitsa update`, `mitsa reshim`).
- **Multi-launcher** — runs any registered app as its own OS process,
  including multiple differently-configured launch variants of the
  *same* jar (`mitsa run`, `mitsa add ... --like`, `mitsa tray`).

## Commands

### `mitsa run <id> [args...]`

Launches the cached jar for `<id>` as a separate process, passing
`args` straight through. If the app was registered with stored
`launchArgs` (see `add --like` below), those are prepended ahead of
whatever you pass here — the app's own arg parsing decides which wins
if they overlap.

Never touches the network. If nothing is cached yet:

```
No cached jar for '<id>'. Run: mitsa update <id>
```

### `mitsa update [id]`

Checks GitHub's latest release for `id` (or every registered app, if
`id` is omitted) and downloads a new jar if the tag has moved.

### `mitsa list`

Prints every registered app: id, `owner/repo`, and the currently
cached version (or `(not cached)`).

### `mitsa add <id> <githubOwner> <githubRepo> <assetNamePattern>`

Registers a brand-new app. `assetNamePattern` is a regex matched
against the release's asset filenames to pick the right one (e.g.
`'infimg-.*-jar-with-dependencies\.jar'`). Writes launch shims for the
new id (see [INSTALL.md](INSTALL.md#5-register-your-first-app)) and
installs a bare-command shim onto PATH.

Already registered the app you want and just need another launch
config for it? See `add ... --like` below instead — no need to
re-register from scratch.

### `mitsa add <newId> --like <existingId> [launchArgs...]`

Registers a **launch variant**: a second id that shares an
already-registered app's jar lineage (same owner/repo/asset pattern,
same update cycle) but always launches with its own fixed extra
arguments. Use this when one jar serves more than one purpose
depending on how it's started — e.g. a normal run versus a run against
a different `--config-file`.

```bash
mitsa add voynich Walter-Stroebel Voynich 'Voynich-.*-jar-with-dependencies\.jar'
mitsa add voynich-special --like voynich --config-file /path/to/special.json
```

`voynich-special` gets its own registry entry, its own JarCache slot,
and its own shims/PATH command — `mitsa run voynich-special` always
passes `--config-file /path/to/special.json` first, then any args you
add on the command line. `existingId` must already be registered
(register the base app with the plain `add` form first). The tray
menu lists it separately too, by id — there's no separate
display-label field, so pick ids that read fine in a menu.

### `mitsa tray`

Starts the single long-lived tray icon for every registered app
(including launch variants, listed by id). Each menu click launches
that app as its own process — never in-process, so one crashing app
can't take the tray down.

On Windows/macOS this uses `java.awt.SystemTray` directly. On Linux
desktops that dropped the legacy XEmbed tray protocol (GNOME 3.26+,
some Cinnamon builds), it falls back automatically to a built-in D-Bus
`org.kde.StatusNotifierItem` client — no extra setup needed. See
[INSTALL.md's troubleshooting section](INSTALL.md#if-something-goes-wrong)
if no icon appears either way.

Only one tray can run at a time (a file lock enforces this). A running
tray also listens on a fixed loopback UDP port for two short commands
from another `mitsa` invocation on the same machine:

- **refresh** — reload the app list into the menu (only meaningful on
  the AWT `SystemTray` path; the D-Bus path already rebuilds its menu
  fresh on every click).
- **stop** — shut the tray down; the caller waits for a PID
  acknowledgment rather than guessing whether it worked.

### `mitsa reshim [id]`

Re-writes launch shims for already-registered apps (one `id`, or all
of them) without touching `apps.json` or re-fetching jars. Needed
after upgrading MITSA itself changes what shims look like — e.g. an
app registered under an older MITSA version that predates PATH
installation.

## Where things live

Everything MITSA manages sits under one config root per OS
(`~/.config/mitsa` on Linux, `~/Library/Application Support/mitsa` on
Mac, `%APPDATA%\mitsa` on Windows): MITSA's own jar, `apps.json` (the
registry), each app's cached jar, and per-app shim scripts under
`shims/<id>/`. Nothing else on the machine needs to know these paths —
every interaction goes through the `mitsa` command.
