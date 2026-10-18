# Taking photos

Everything a camera can do, from aiming it to the capture screen's last button.

### The camera in the world

A camera is three entities: the model you see (a spyglass by default), an invisible click box around it, and the info hologram above it. The hologram shows the camera's name, ratio, zoom, angles and how many photos it holds; while a photo is being taken it shows a progress bar too.

Any player with `izomap.camera` can place cameras, up to `settings.max-cameras-per-player`. A camera keeps every setting until it is changed, across restarts.

### Aiming

Clicking a camera changes one setting at a time:

| Setting | Right-click | Left-click |
|---|---|---|
| **Facing** | Turn right by `camera.angle-step` | Turn left |
| **Tilt** | Look further down | Look further up |
| **Zoom** | Zoom in by `camera.zoom-step` | Zoom out |
| **Move** | Push the camera where *you* are looking | Pull it back |

**Shift + right-click** moves to the next setting. **Shift + left-click** opens the capture screen.

The first click on a camera you are not watching only opens its preview. It changes nothing, so you never nudge a camera before you can see what it sees.

Zoom is a factor: every click multiplies or divides by the same amount, so a step feels the same whether the frame is 400 blocks wide or 20. Move mode follows your own view, tilt included, so pushing a camera up a hill needs no separate height setting.

### The live preview

While you watch a camera, the map in your off hand is its live view, and the action bar shows its settings. To watch, your off hand has to be empty. The preview map cannot be dropped, moved or swapped; dropping it with `Q` closes the preview, and so does `/izocam preview stop`.

Several players can watch the same camera at once, and it is rendered only once for all of them. Only one of them can adjust it: the first to click is the **editor**, the others are told who and join as viewers. An editor who stops clicking for `camera.edit-lock-seconds` loses the seat to the next player who clicks.

The preview is taken at the camera's own aspect ratio, letterboxed on the square map, so it shows the real frame and the real cost: if the preview renders, the photo will too.

### Why the angle matters

Izomap uses an **orthographic** projection: every ray is parallel, as in an isometric game. Two things follow.

- **Distance does not change size.** Only the zoom does. Moving a camera closer to a house shows the same house at the same size.
- **A level camera sees no ground.** With rays parallel to the ground, nothing hits its surface: the top of the photo becomes sky and the bottom a cross-section of the earth. Keep the tilt between 20 and 45 degrees. The plugin warns you below 10.

Low angles do work for horizon shots, with a sky behind the scene: the cathedral photos on the front page were taken at a 3 degree tilt.

### The capture screen

Shift + left-click a camera, or `/izocam open <camera>`.

| Row | Buttons |
|---|---|
| Fields | Photo name · Layout (grid) · Focus distance when focus is on |
| 1 | Aspect ratio: 1:1, 16:9, 4:3 |
| 2 | Color · Style · Sky |
| 3 | Focus · Rule of thirds · Photos |
| 4 | Take photo · Pick up camera · Reset aim |

The info line at the top says what will be shot: camera, ratio, zoom with the blocks it covers, facing and tilt. Those are set by clicking the camera, not here.

A button that cycles (color, style) moves to the next value and reopens the screen with the preview updated. Buttons you have no permission for are hidden, except that a camera's current value always shows, so you can see and change a setting somebody else left on it.

### Grid sizes

The grid is how many maps the photo spans, and it follows the aspect ratio:

| Ratio | Grids |
|---|---|
| 1:1 | 1x1, 2x2, 3x3 |
| 16:9 | 4x2, 8x4, 16x9 |
| 4:3 | 4x3, 8x6 |

Each map is 128x128 pixels, so a 16x9 photo is 2048x1152. The tile count is the cost: it decides how long a photo takes far more than anything else. `settings.max-map-tiles` (12 by default) limits it, and a permission can raise it per group.

### Style

**Sharp** casts a ray for every pixel. **Fast** renders smaller and scales up (`photo.style.fast-scale`), casting a quarter of the rays at the default, at the price of a softer image. Fast needs no permission; sharp needs `izomap.style.sharp`.

### Color filters

Filters change colors before they are matched to the map palette: **Original**, **Warm**, **Cool**, **Black and white**, **Sepia**, **Vivid** and **Noir** ship with the plugin, and server owners can write their own in [Filters](#filters). A filter costs nothing per pixel.

### Sky

Rays that hit nothing leave transparent pixels, so a photo can hang without a background. Pick a sky to paint them instead: **Game time** freezes the hour the photo is taken at; **Dawn**, **Day**, **Dusk** and **Night** are fixed.

The sky screen has a second switch, **Sun, moon and stars** (`izomap.sky.bodies`). The sun is drawn where the shading's sun shines from, the moon opposite it in the phase of the world's day, and stars appear as the sky darkens, fixed to the world so they never slide when the camera turns.

### Depth of field

**Focus** (`izomap.focus`) keeps one distance sharp and blurs everything nearer and further. When it is on, a slider picks the distance; it starts on the ground the frame is aimed at and its far end is the furthest the camera's rays reach.

There is no lens in an orthographic camera, so this is a chosen look, not a simulation: on a tilted camera it makes the scene look like a miniature (tilt-shift), on a level one it lifts the subject off its background. The slider's value reaches the server when you press any button on the screen.

### Rule of thirds

Draws a 3x3 guide on the preview to help composing. It never appears on a photo.

### Taking the photo

**Take photo** shoots at the grid you picked. The hologram and the action bar show progress; a large photo takes a few seconds. The photo lands in the camera's list, named after the field (a taken name gets a number: `garden`, `garden-2`). A camera holds at most `settings.max-photos-per-camera` photos, hung or not.

If a setting on the camera needs a permission you do not have, the photo is refused and the message names the setting. It is never quietly taken with different settings than the ones on screen.

### Picking a camera up

**Pick up camera** (or `/izocam pickup <camera>`) takes the camera down and gives its item back, if it was placed with one. Only the camera's owner and players with `izomap.admin` see the button. Photos belong to the camera that took them and cannot be retaken without it, so they are deleted with it; the screen asks first.

**Reset aim** puts zoom, facing and tilt back to their defaults and leaves everything else alone.
