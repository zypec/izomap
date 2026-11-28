# Izomap — working agreement

Izomap is a Paper plugin for isometric photography printed onto maps. How it is built and why is in
`docs/ARCHITECTURE.md`; what a server owner needs is the wiki (`wiki/`, published at
<https://zypec.github.io/izomap/>). `TODO.md` is the local roadmap and is not in git.

This file and `docs/ARCHITECTURE.md` are maintained by Claude. Change them in the same commit as the
code they describe.

## Communication

- Reply to the maintainer in **Turkish**. Everything that lands in the repository is **English**:
  code, javadoc, comments, config keys, commit messages, docs, the wiki and the in-game messages of
  `lang/en.yml`, the reference language. `lang/tr.yml` is the Turkish translation: its values are
  Turkish, its comments are not repeated (they live in `en.yml`), and it changes with every message
  that changes in English.
- Player-facing English (messages, the wiki, shipped `.yml` comments) is spelled the **American**
  way (`color`, `gray`), like the config keys, and uses no em dash (—): a comma, a colon,
  parentheses or a new sentence instead. Javadoc and code comments are exempt.
- The middle dot (·) is held to the same rule, for the same reason: as a separator it reads as
  machine-written. A status line separates with `<dark_gray>|</dark_gray>`, a label with a colon,
  and a count or a state beside a name goes in brackets.

## Documentation lives in the wiki

- Config comments are **minimal**: one line per key, what it does and its range. The explanation
  goes in the wiki, and each shipped file links its page.
- A change a server owner or player can see (a setting, a command, a permission, a message, a
  behavior) updates the wiki in the same change: a guide under `wiki/guides/`, and for a config key
  its entry in `wiki/reference/config.yml`. `./gradlew generateWiki` reports keys with no entry and
  entries with no key; both must be empty before a commit.
- The wiki is generated, never written as HTML by hand: guides are Markdown, the reference is read
  off `config.yml` and `paper-plugin.yml`. See `docs/ARCHITECTURE.md` §11.

## Build and run

```bash
./gradlew build          # jar in build/libs/, tests included
./gradlew runServer      # Paper 26.3 in run/ with the fresh jar (run/ is not in git)
./gradlew generateWiki   # the site in build/site; needs ../zypec.github.io/kit or -Pkit=<folder>
./gradlew editorSite     # the web editor's relay site in build/editor-site, its worker in build/relay
```

The same are IntelliJ run configurations in `.run/`: Build Jar, Build + Test, Tests, Run Server,
Generate Wiki.

- Java 25 toolchain, Paper `26.3` dev bundle (`minecraftVersion` in `build.gradle.kts`, which also
  fills `api-version`). Never lower the toolchain: Paper 26.1+ refuses older JVMs.
- `./gradlew build` must be green before a commit.

The web editor's relay, tunnel and the plugin's end of it are the editor kit, shared with
Particles+ (`zypec/editor-kit`, `dev.zypec:editor-kit`); a sibling `../editor-kit` checkout replaces
the published jar in the build. The relay is deployed from `relay/` with `npx wrangler deploy`, by
the maintainer. See `docs/ARCHITECTURE.md`, the web editor.

## The development server

`runServer` passes `-Dizomap.dev=true`, which registers `/izodev` (console and ops only, absent from
release servers). It shoots the views in `plugins/Izomap/dev-shots.yml` straight to PNG with no
camera and no player:

```
izodev shots            # every view in the file, one after another
izodev shot <name>      # one view
izodev clicks <name> <n> # n preview-size renders, four a second, turning a step each, timed
izodev try <name>       # the view in every combination the site's try-it page offers (try-*.png)
izodev palettes         # exports/filters.json: what each filter turns every map color into
```

Each view: `world` (default `world`), `x y z yaw pitch zoom`, `grid` (`8x5` → 1024x640) or `size`
(`1024x576`, pixels), `sky`, `filter`, `style`, `bodies`, `focus: <distance>` to turn depth of field
on, `fog-start`/`fog-end` to turn fog on, `sun-yaw`/`sun-pitch`. The file is reread
on every call. Output goes to `plugins/Izomap/exports/`. To drive the server from a script, enable
RCON in `run/server.properties`.

The gallery's views are in `wiki/gallery/shots.yml`, with the config they were shot under. Pictures
for the site are stored as palette PNGs: map photos have at most 244 colors, so an indexed PNG is
lossless and a fraction of the size.

## Code

- Follow the rules in `docs/ARCHITECTURE.md` §9: nothing heavy on the main thread, Paper API over
  NMS, every visible text through `Messages`, Brigadier commands, config through `ConfigManager`.
- Comments are English and minimal: only a *why* the code cannot say. No TODO ids, conversation or
  history in code.
- Match the surrounding code: `var` for locals, explicit types elsewhere, feature packages.
- A message key used in code must exist in `lang/en.yml` and `lang/tr.yml`; `MessagesFileTest` checks it with
  Bukkit's own YAML loader (YAML 1.1: a bare `on` key is the boolean `true`, quote it).

## Compatibility

Until the first public release nothing is kept for old installs. From that release on its shipped
files are version 1: a renamed key keeps being read, a removed one is ignored, and a file whose
format changes bumps its `version:` key together with a migration.

## Git

- Commit and push to `origin/main` (SSH, `git@github.com:zypec/izomap.git`) as work completes; no
  need to ask.
- Commit messages are English: an imperative subject that says what changed for the plugin (the
  history's style, e.g. "Let a swamp be the colour of a swamp"), a body wrapped at 80 columns that
  says what and why, measurements with how they were taken. No TODO ids.
- End with the `Co-Authored-By` line the session gives.
