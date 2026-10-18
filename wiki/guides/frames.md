# Frames

A frame is a border drawn around a photo on the wall. Players add one by right-clicking their hung photo. `frames.yml` lists them in the order the frame screen shows them, and there are three ways to write one.

Each needs `izomap.frame.<ID>` (or `izomap.frame` for all), and its name players see goes in `messages.yml` under `frame.<ID>`.

### 1. Rings

Nested rings of a color and a width, outermost first. Every simple frame is this.

<pre data-title="frames.yml">frames:
  WOOD:
    rings:
      - { color: "#2B1D0E", width: 2 }
      - { color: "#7A5A32", width: 6 }
      - { color: "#3B2A16", width: 2 }</pre>

### 2. Pixel art

What rings cannot do: a pattern that changes along the edge, or a decorated corner. Characters stand for colors in `palette`; a `.` (or any character not in it) is transparent and shows the photo.

- `edge` rows run along each side, outermost first, and repeat along its length.
- `corner` is drawn at all four corners, mirrored. It must be square and as deep as the edge.

<pre data-title="frames.yml">  ORNATE:
    palette:
      x: "#E5C34C"
      o: "#8A6B1F"
    edge:
      - "oooooo"
      - "xxxxxx"
      - "xoxxox"
      - "oooooo"
    corner:
      - "..oo"
      - ".oxo"
      - "oxxo"
      - "oooo"</pre>

### 3. A PNG

Draw it in an image editor, put it in `plugins/Izomap/frames/`, and name it:

<pre data-title="frames.yml">  CUSTOM:
    texture: "my-frame.png"
    inset: 8</pre>

The image is nine-sliced at `inset`: the top-left `inset` x `inset` square is the corner, the strip between the two top corners repeats along the edge. Semi-transparent pixels count as transparent, since a map has no transparency to blend with.

### Scale

`scale` is how many photo pixels one art pixel covers. Leave it out (or write `auto`) and it grows with the photo, one step per 256 pixels of the short side: 1x on a single map, 3x on an 8x6, 4x on a 16x9. Without it, a frame that looks right on one map would be a hairline on a wall.

### Rules

- Colors are rounded to the map palette when the file loads.
- A frame is drawn **over** the outer pixels of the photo; the photo does not shrink.
- A frame may be at most a fifth of the short side deep. Deeper art is cut from the inside, so the outer rows, the part that reads as a frame, survive on a small photo.
- `photo.frames.embed: true` bakes frames into photos for good; see [Configuration](#rendering).
