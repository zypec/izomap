# FAQ

### The photo is empty, or half of it is

The camera is looking nearly level. Rays in an isometric camera are parallel, so a level one never reaches the ground's surface. Tilt it down, between 20 and 45 degrees. If every photo is empty no matter the tilt, check `photo.frame-shift` in `config.yml`: it should be `0.0`.

### There are square transparent holes in the photo

Those are chunks that could not be copied, and the log says how many. Either they were never generated (`settings.generate-missing-chunks` is off, as it should be on most servers) or `settings.load-missing-chunks` is off.

### A straight empty band runs across the top of the photo

The rays ran out of distance before reaching the far ground. Raise `settings.render-depth`, or `settings.max-capture-area` if the shot is very wide.

### "The frame is too wide"

The photo would need more chunks than `settings.max-capture-area` allows. Zoom in, or raise the limit on a server that can afford it (see [Performance](#performance)).

### The preview does not open

Your off hand has to be empty: the preview is a map held there.

### Someone else is adjusting my camera

One player edits a camera at a time; the others watch. The editor's seat frees itself after `camera.edit-lock-seconds` without a click.

### The colors are not the ones the vanilla map shows

On purpose, in three places: tuff and flowers get colors closer to the real block (`settings.correct-vanilla-colors`), grass, leaves and water take their biome's color (`photo.biome-tint`), and thin blocks blend into what is behind them (`photo.coverage`). Each can be turned off. See [Block colors](#block-colors).

### I changed a color and the photo on the wall did not change

Photos are saved as the image they were taken as. Retake it (right-click it, or `/izocam retake <camera>/<photo>`).

### The night sky photo is bright

The sky and the stars are drawn for night, but the ground is lit by a fixed sun, so a night photo shows the landscape in daylight under a night sky. Turning on `photo.shading.block-light` darkens caves and interiors but not open ground.

### Can players see photos without a resource pack?

Yes. Photos are ordinary maps in item frames.

### Does it work on Spigot or Folia?

No. Izomap uses Paper's API (the dialog screens among others) and is tested on Paper only.

### Where do I report a bug?

On the [issue tracker](https://github.com/zypec/izomap/issues). Include your Paper version, the plugin version and anything the console printed.
