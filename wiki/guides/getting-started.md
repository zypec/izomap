# Getting started

Install Izomap, put a camera down, take your first photo and hang it on a wall. Five minutes, start to finish.

### What it does

Izomap puts cameras in your world. A camera looks at the world from a fixed angle, like an isometric game, and takes a photo of what it sees by casting a ray for every pixel. The photo is cut into 128x128 pieces and printed onto maps, which hang in item frames as one picture: from a single map up to a 16x9 wall.

Photos are real map items. Every player sees them, they survive restarts, and no resource pack or client mod is needed.

### Install

1. Your server runs **Paper 26.3** (or newer) on **Java 25**.
2. Put `Izomap-<version>.jar` in the `plugins` folder.
3. Start the server. The plugin writes its files to `plugins/Izomap/`.

That is all. Nothing needs to be configured before the first photo; the defaults are tuned for a normal survival server.

### Your first camera

Run `/izocam item` to get a camera, then **right-click a block** with it. The camera appears there, already looking down at the classic isometric angle, and the map in your off hand starts showing what it sees.

The map in your off hand is the **live preview**. It changes every time you adjust the camera, so you can aim without taking a single photo.

<pre class="console" data-title="In game">/izocam item</pre>

You can also make one without an item: `/izocam create garden` puts a camera called `garden` two blocks in front of you.

### Aim it

Click the camera itself:

| Click | Does |
|---|---|
| Right-click | Raise the current setting |
| Left-click | Lower the current setting |
| Shift + right-click | Switch the setting: facing, tilt, zoom, move |
| Shift + left-click | Open the capture screen |

The action bar always says which setting you are changing and what every setting is at. Zoom shows how many blocks the frame covers, which is usually what you want to know.

Start with **zoom** to frame what you want, then turn the camera with **facing**. Leave the tilt near 30 degrees until you know why you would change it.

### Take the photo

Shift + left-click the camera to open the capture screen. Pick a size under **Layout**: `1x1` is a single map, `4x2` a wall of eight, `16x9` a cinema screen of 144. Then press **Take photo**.

The photo is now in the camera's photo list. It is not on a wall yet: taking a photo and hanging it are two separate steps, so you can take a few and keep the best.

### Hang it

On the capture screen, open **Photos** and press **Hang** next to your photo. A green ghost of the frames follows your view. Point it at a wall and **right-click** to hang the photo. **Shift + right-click** cancels.

The ghost turns red where the photo does not fit. By default Izomap builds a block behind any frame that has nothing to hang on, so the photo can hang in the open air too.

### Where to go next

- [Taking photos](#taking-photos): every setting on the capture screen, skies, filters, depth of field.
- [Hanging photos](#hanging-photos): frames, retaking, taking a photo down, exporting it to a PNG.
- [Permissions](#permissions): decide who may take how expensive a photo.
- [Performance](#performance): what a photo costs your server, and how to keep it cheap.
