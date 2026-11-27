# Messages

Every piece of text Izomap shows is in a language file under `plugins/Izomap/lang/`: chat messages, the action bar, the hologram, the capture screen, item names and every line it writes to the console.

### Languages

English (`en.yml`) and Turkish (`tr.yml`) ship with the plugin, and both are written to `lang/` on first start. `settings.language` in `config.yml` picks one:

<pre data-title="config.yml">settings:
  language: tr</pre>

`/izocam reload` switches to it. To add a language, copy `en.yml` to `lang/<code>.yml`, translate it and set `language: <code>`. A key your file lacks, such as one a newer version added, shows the shipped text of the same language, or English where there is none, never a blank.

### Format

Messages are [MiniMessage](https://docs.advntr.dev/minimessage/format.html): `<gold>`, `<bold>`, `<gradient:#d9a60c:#ecbd2c>`, `<#FF7A4D>`. Legacy `&` codes are not read.

- `<prefix>` inserts the `prefix` line at the top of the file.
- Placeholders such as `<name>`, `<count>` or `<camera>` are filled in by the plugin. The comment above a message lists them where it is not obvious; a placeholder you remove simply does not appear.
- A key you delete falls back to the shipped text.

### Names of things

Settings with a list of values take their names from the file, and their color too:

<pre data-title="lang/en.yml">filter:
  WARM: "<gold>Warm"
sky:
  DUSK: "<#FF7A4D>Dusk"
dialog:
  filter-button: "Color: <value>"</pre>

The button shows `Color: Warm` in gold. Rename a value where it is listed and its color comes with it. Only ids are saved, so renaming changes nothing stored.

A filter or a frame you add needs its name here (`filter.<ID>`, `frame.<ID>`); without one it shows its id.

### The hologram

The lines above every camera are a list you can edit:

<pre data-title="lang/en.yml">camera:
  hologram:
    lines:
      - "<aqua><name>"
      - "<gray><photos> photo(s) <dark_gray>|</dark_gray> <filter>"</pre>

Placeholders: `<name>`, `<owner>`, `<ratio>`, `<zoom>`, `<blocks>`, `<yaw>`, `<pitch>`, `<filter>`, `<style>`, `<sky>`, `<photos>`. Delete a line to remove it. `camera.hologram.enabled` in `config.yml` turns holograms off entirely.

### The console

Lines under `log:` are what the plugin writes to the server log. They take MiniMessage too; a console without color ignores the tags. `<reason>` carries Java's own error text and stays in English.

Run `/izocam reload` after editing.
