# Messages

Every piece of text Izomap shows is in `plugins/Izomap/messages.yml`: chat messages, the action bar, the hologram, the capture screen, item names and every line it writes to the console. Translate the file and the whole plugin speaks your language.

### Format

Messages are [MiniMessage](https://docs.advntr.dev/minimessage/format.html): `<gold>`, `<bold>`, `<gradient:#d9a60c:#ecbd2c>`, `<#FF7A4D>`. Legacy `&` codes are not read.

- `<prefix>` inserts the `prefix` line at the top of the file.
- Placeholders such as `<name>`, `<count>` or `<camera>` are filled in by the plugin. The comment above a message lists them where it is not obvious; a placeholder you remove simply does not appear.
- A key you delete falls back to its built-in English text.

### Names of things

Settings with a list of values take their names from the file, and their color too:

<pre data-title="messages.yml">filter:
  WARM: "<gold>Warm"
sky:
  DUSK: "<#FF7A4D>Dusk"
dialog:
  filter-button: "Color: <value>"</pre>

The button shows `Color: Warm` in gold. Rename a value where it is listed and its color comes with it. Only ids are saved, so renaming changes nothing stored.

A filter or a frame you add needs its name here (`filter.<ID>`, `frame.<ID>`); without one it shows its id.

### The hologram

The lines above every camera are a list you can edit:

<pre data-title="messages.yml">camera:
  hologram:
    lines:
      - "<aqua><name>"
      - "<gray><photos> photo(s) · <filter>"</pre>

Placeholders: `<name>`, `<owner>`, `<ratio>`, `<zoom>`, `<blocks>`, `<yaw>`, `<pitch>`, `<filter>`, `<style>`, `<sky>`, `<photos>`. Delete a line to remove it. `camera.hologram.enabled` in `config.yml` turns holograms off entirely.

### The console

Lines under `log:` are what the plugin writes to the server log. They take MiniMessage too; a console without color ignores the tags. `<reason>` carries Java's own error text and stays in English.

Run `/izocam reload` after editing.
