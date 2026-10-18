# Hanging photos

A photo exists whether it is on a wall or not. This page is everything you can do with one once it is taken.

### The photo list

Open a camera's capture screen and press **Photos**. Every photo the camera holds is a row of buttons: its name (rename), **Hang** or **Take down**, **Delete** and **Retake**. `/izocam list photos` lists every photo you own, across cameras.

Commands name a photo as `<camera>/<photo>`, for example `garden/sunset`. The photo name alone works too, since names are unique per player. Every command suggests them as you type.

### Hanging

**Hang** starts placement mode. A ghost of the frames, only visible to you, follows your view `placement.distance` blocks ahead:

- **Green** means it fits. **Right-click** hangs the photo.
- **Red** means a frame would land in a block, or (with `build-backing-wall` off) a frame has nothing solid behind it.
- **Shift + right-click** or `/izocam cancel` stops. Dying, changing worlds, leaving and `placement.timeout-seconds` stop it too.

The ghost shows two layers: glass where each item frame will be, and a block outline behind the frames that need a backing block built. Where your wall already stands, no backing is shown and none is built.

While placing, an item frame sits in your off hand. It is how your right-click reaches the server (the game sends nothing for a right-click with two empty hands); you get your own off-hand item back when placement ends.

### On the wall

Item frames that hold a photo are protected. They cannot be rotated, and breaking one, by hand, explosion or physics, takes the **whole photo** off the wall without dropping a thing. The photo stays in its camera's list, ready to hang again.

**Right-click** a hung photo to open its screen: **Retake**, **Add a frame** or **Change the frame**, and **Take off the wall**. Only the photo's owner and players with `izomap.admin` get the screen; for everybody else a right-click does nothing.

### Decoration frames

A frame is a border drawn on the outer pixels of the photo: Wood, Gold, Black, Mat, Stone, Rope and Ornate ship with the plugin. Each needs `izomap.frame.<ID>` (or `izomap.frame` for all). The frame scales with the photo, so it reads as a frame on a single map and on a 16x9 wall alike.

By default a frame can be changed or removed at any time. A server that sets `photo.frames.embed: true` bakes it into the photo for good instead. Server owners can draw their own; see [Frames](#frames).

### Retaking

**Retake** shoots the photo again onto the same maps, without taking it off the wall: the same frames, the world as it is now. It uses the camera's **current** settings, so you can turn the camera a little and retake.

<pre class="console" data-title="In game">/izocam retake garden/sunset
/izocam retake with castle-cam garden/sunset</pre>

The second form shoots it with another camera and moves the photo to that camera. If the photo's own camera is gone, it is shot from the settings saved with the photo. If the retake fails, nothing changes and the old image stays.

### Photos survive restarts

A photo is saved as its own small file (`plugins/Izomap/photos/<id>.izm`, about a megabyte for a 16x9 landscape) and loaded as it was. It is not taken again on startup, so it never changes because the world changed. If that file is lost, the photo is shot once more from the settings it was taken with.

A photo also keeps every setting that shaped it: shading, water, sky, focus. Changing the server's configuration later does not change photos already taken.

### Taking a photo down, deleting it

- **Take down** (or `/izocam unplace <camera>/<photo>`) removes the frames. The photo stays in the list.
- **Delete** removes it for good: its image, its settings and its frames. It asks first.
- `/izocam remove all photos` deletes every photo you own.

### Getting the maps as items

`/izocam maps <camera> <grid>` takes a photo and gives you the map items instead of hanging them, to place by hand or to give away.

### Exporting to a PNG

`/izocam export <camera>/<photo>` writes the photo at full size to `plugins/Izomap/exports/` (`izomap.export`). `/izocam export as <file> <camera>/<photo>` names the file. The images on this site were made this way.

### Cleaning up

If a world was edited outside the game, or a server crashed at the wrong moment, frames can outlive their photo or a photo can lose its frames. `/izocam cleanup` fixes both around you: photos whose frames are gone are listed as not hung, and frames and camera models no record claims are removed. It searches loaded chunks only, so run it where the problem is.
