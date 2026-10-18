# Commands

Every command is under `/izocam` (also `/izocamera`) and needs `izomap.camera` unless the table says otherwise. Every argument is suggested as you type; grid suggestions follow the camera's aspect ratio.

### Cameras

| Command | Does |
|---|---|
| `/izocam item` | Gives a camera item; right-click a block with it to place a camera |
| `/izocam create <name>` | Puts a camera two blocks in front of you |
| `/izocam move <name>` | Moves a camera to where you are looking |
| `/izocam open <name>` | Opens its capture screen |
| `/izocam ratio <name> <ratio>` | Sets its aspect ratio: `1:1`, `16:9` or `4:3` |
| `/izocam preview <name>` | Watches its live preview, as a viewer |
| `/izocam preview stop` | Closes the preview you are watching |
| `/izocam pickup <name>` | Takes it down and gives its item back; its photos are deleted with it |
| `/izocam remove <name>` | Deletes a camera |
| `/izocam remove all cameras` | Deletes every camera you own |
| `/izocam list cameras` | Lists your cameras |

### Photos

A photo is named `<camera>/<photo>`, for example `garden/sunset`; the photo name alone works too.

| Command | Does |
|---|---|
| `/izocam maps <camera> <grid>` | Takes a photo and gives you the map items instead of hanging them |
| `/izocam list photos` | Lists your photos and whether they are hung |
| `/izocam unplace <photo>` | Takes a photo off the wall; it stays in the list |
| `/izocam retake <photo>` | Shoots a hung photo again with its own camera |
| `/izocam retake with <camera> <photo>` | Shoots it again with another camera |
| `/izocam cancel` | Stops placing a photo |
| `/izocam remove all photos` | Deletes every photo you own |
| `/izocam cleanup` | Fixes photos that lost their frames and removes frames and models nobody owns, around you |

### Admin

| Command | Permission | Does |
|---|---|---|
| `/izocam export <photo>` | `izomap.export` | Writes a photo to `plugins/Izomap/exports/` as a PNG |
| `/izocam export as <file> <photo>` | `izomap.export` | The same, naming the file |
| `/izocam reload` | `izomap.admin` | Reloads every configuration file, block colors and filters included |

`export` sees every player's photos. When a bare photo name matches photos of more than one player, it asks for `<camera>/<photo>` rather than guess.

After a reload, photos already taken keep their look: they are loaded from their own files. Retake one to see new colors on it. The live preview picks changes up on the next click.
