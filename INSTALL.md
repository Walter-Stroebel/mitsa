# Installing MITSA — from zero

MITSA (Make IT Small Again) is a small shared launcher/config layer for
Walter's Java apps — one place to look for config, one place jars get
cached, one short command to run any of them. It is a **prerequisite**,
the same category as "install Java 17" — install it once per machine,
then every MITSA-managed app's own install instructions collapse to a
single `mitsa add` instead of the wrapper-script dance you may have seen
in projects like [Voynich](https://github.com/Walter-Stroebel/Voynich)'s
own `INSTALL.md`.

This is for someone who has never installed a Java program before and
may not have a terminal open right now. If you already have Java 17 and
know your way around a terminal, skip ahead to
[step 2](#2-download-mitsa-itself).

*(Maven/git and building from source are only needed if you want to
modify MITSA's own code — that path is covered at the end of this
document.)*

---

## 1. Install Java 17

You need a **JDK** (Java Development Kit — includes the compiler, not
just the runtime), specifically **version 17**, an LTS (Long-Term
Support) release.

**The confusing part, up front:** searching "download Java" mostly leads
to Oracle's site, which pushes its own commercial JDK build and a login
wall. You don't need that one. Oracle owns the Java trademark and
language spec, but OpenJDK is the real, free, fully-compatible
open-source implementation.

**Recommended: [Eclipse Temurin](https://adoptium.net/)** — pick your OS,
download the **JDK 17 (LTS)** installer, run it.

**Watch out:** Temurin's own site defaults to whatever its newest release
is, not 17. On the [releases page](https://adoptium.net/temurin/releases/),
open the **Version** dropdown yourself and pick **"JDK 17 - LTS"** before
downloading.

- **Windows:** download the `.msi`, run it, accept defaults (make sure
  "Set JAVA_HOME" and "Add to PATH" are checked).
- **Mac:** download the `.pkg`, run it. Or with
  [Homebrew](https://brew.sh/): `brew install temurin@17`.
- **Linux:** your distro's package manager almost certainly has it —
  e.g. Debian/Ubuntu: `sudo apt install openjdk-17-jdk`; Fedora:
  `sudo dnf install java-17-openjdk-devel`.

**Verify it worked**:

```bash
java -version
```

You want to see `17` in the output.

## 2. Download MITSA itself

**MITSA has no GitHub repository or published release yet as of
2026-08-19** — this step is a placeholder until that exists. Once it
does, this will read the same way Voynich's own step 2 does: go to
`github.com/Walter-Stroebel/mitsa/releases/latest`, download the one
`.jar` file attached (`mitsa-<version>-jar-with-dependencies.jar`), no
building required.

Until then: build it from source — see
["Building from source"](#building-from-source-instead) at the end of
this document.

However you obtained it, put the jar at:

- **Linux:** `~/.config/mitsa/mitsa.jar`
- **Mac:** `~/Library/Application Support/mitsa/mitsa.jar`
- **Windows:** `%APPDATA%\mitsa\mitsa.jar`

(Create the folder if it doesn't exist yet.) MITSA installs its own jar
into its own config root — the same place every app it manages caches
its jars — rather than staying in whatever folder you happened to
download it into.

## 3. Write the one shim script

This is the one genuinely hands-on step — short, and you only do it
once.

**Linux/Mac** — create `~/bin/mitsa` (make sure `~/bin` is on your
PATH, or pick any folder already on PATH):

```bash
#!/bin/bash
exec java -jar "$HOME/.config/mitsa/mitsa.jar" "$@"
```

(Mac: replace the jar path with the `Library/Application Support/mitsa`
one from step 2.)

Then `chmod +x ~/bin/mitsa`.

**Windows** — create `mitsa.bat` somewhere on your PATH:

```bat
@echo off
java -jar "%APPDATA%\mitsa\mitsa.jar" %*
```

## 4. Verify it worked

Open a **new** terminal (so the PATH change is picked up) and run:

```bash
mitsa list
```

An empty line back (no apps registered yet) means it's working. If you
get "command not found," `~/bin` (or wherever you put the shim) isn't
actually on PATH — check that before continuing.

## 5. Register your first app

```bash
mitsa add infimg Walter-Stroebel infimg 'infimg-.*-jar-with-dependencies\.jar'
mitsa update infimg
mitsa run infimg
```

`mitsa add <id> <githubOwner> <githubRepo> <assetNamePattern>` registers
an app and writes it a matching set of one-line launch shims (under
`<configDir>/shims/<id>/`) that any *other* project's own install docs
can now point to instead of asking a user to hand-write a wrapper
script — `mitsa update <id>` fetches its jar, `mitsa run <id>` launches
it as its own separate process. `mitsa` never bakes a version number
into anything it writes; re-running `mitsa update` later just refreshes
the cache in place.

---

### If something goes wrong

- **`java: command not found`** — Java isn't on your PATH. See step 1.
- **`mitsa: command not found`** — the shim from step 3 isn't on PATH,
  or you need a new terminal for the PATH change to take effect.
- **`mitsa run <id>` says "No cached jar"** — run `mitsa update <id>`
  first; `mitsa run` never hits the network itself.
- **The system tray doesn't show a MITSA icon** — Linux tray icons are
  fragile in general, for reasons outside any one app's control. GNOME
  3.26 (2017) dropped the legacy XEmbed tray protocol that
  `java.awt.SystemTray` (and countless other apps) speak, in favor of a
  D-Bus-based one it never fully backfilled compatibility for — this
  broke tray icons for a long list of real, unrelated apps for years,
  not just Java ones. Confusingly, this is *not* automatically "fixed"
  by using a desktop that kept the old tray around either: on one
  Cinnamon 6.0.4 machine tested 2026-08-19, `SystemTray.isSupported()`
  still returned `false` across JDK 8/11/17 alike, root-caused to a
  Cinnamon-side tray-manager bug unrelated to the GNOME/D-Bus story
  (Cinnamon never adopted that protocol change in the first place). In
  short: if the tray icon doesn't appear, it is very likely your desktop
  environment's own tray plumbing, not a `mitsa tray` bug — the CLI
  (`run`/`update`/`list`/`add`) is fully independent of the tray and
  unaffected either way.

---

## Building from source instead

You'll additionally need **Maven** and **git** — see Voynich's own
[INSTALL.md](https://github.com/Walter-Stroebel/Voynich/blob/main/INSTALL.md#building-from-source-instead)
for how to get those, identical steps.

```bash
cd mitsa   # this project's checkout
mvn package
cp target/mitsa-jar-with-dependencies.jar ~/.config/mitsa/mitsa.jar   # Linux path shown; adjust per step 2 above
```

Then continue from [step 3](#3-write-the-one-shim-script) above.
