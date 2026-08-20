# MITSA

**M**ake **IT** **S**mall **A**gain — one tiny launcher that downloads,
updates, and runs all of Walter's GitHub-released Java apps, so none of
them need to ship their own updater, installer, or wrapper script ever
again.

Register an app once:

```bash
mitsa add infimg Walter-Stroebel infimg 'infimg-.*-jar-with-dependencies\.jar'
mitsa run infimg
```

...and from then on it's just `mitsa update` and `mitsa run <id>` —
plus a system tray icon that launches any registered app with a click.

The same jar can also be registered a second time under a different
id with its own fixed launch args — say, the same tool pointed at a
different config file:

```bash
mitsa add infimg-work --like infimg --config-file ~/work-infimg.json
```

`infimg-work` shows up as its own tray entry and shim, no separate
download. See [MANUAL.md](MANUAL.md#mitsa-add-newid---like-existingid-launchargs)
for how launch variants work.

- **Downloader** — fetches the right release asset from GitHub, no
  manual jar-hunting.
- **Updater** — one command refreshes every app's cache in place.
- **Multi-launcher** — one tray, every app, no per-app tray clutter.

New here? Start with [INSTALL.md](INSTALL.md) — zero to running in a
few minutes, no prior Java experience assumed. Already set up? The
full command reference lives in [MANUAL.md](MANUAL.md).
