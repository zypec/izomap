<!--
  Everything Izomap's front page says. Change it here and the page follows; nothing else needs touching.

  ## A heading             starts a section; ## Gallery {#gallery} gives it the id the links use
  A paragraph             with **bold**, *italic*, `code` and [a link](wiki/#filters)

  A block starts with "::: kind" and ends with ":::". Its fields come first, one a line, up to the first
  empty line; after that is what it says. A field can be given more than once, and a | splits it in parts.
  The kinds this page knows:

  hero      kicker, title, badge, button: Words | address | primary (any number), note,
            image: picture | what it shows, toggle: Name | picture | what it shows (a switch named Name, on
            while the image shows, off for the other picture); then the opening words
  feature   name, icon (camera, cube, sun, focus, wall or sliders); then what it says. Features side by side form a grid
  step      name; then what it says. Steps side by side form a row
  gallery   picture: file | caption (any number); a picture opens large when clicked
  filters   folder (where the filter pictures are), filter: file name | button (any number, the first is shown first),
            link: Words | address; then what it says
  notice    just its words
-->

::: hero
kicker: Isometric photography for Minecraft servers
title: Izomap
badge: Coming soon
button: Read the docs | wiki/ | primary
note: Paper 26.3 · Java 25 · Free and open source, MIT
image: gallery/fog.png | A cathedral and a town at dusk, the land behind fading into the sky, photographed by Izomap
toggle: Fog | gallery/hero.png | The same cathedral and town without fog, the sea open to the horizon

Put a camera in your world, aim it with a live preview and take a photo of what it sees. Izomap casts a ray
for every pixel, then prints the picture onto maps that hang on any wall: from a single map to a 16x9 mural,
seen by every player, with no resource pack.
:::

## What it does

::: feature
name: A live preview in your hand
icon: camera

The map in your off hand shows what the camera sees, updated with every click. Turn, tilt, zoom and move it
until the frame is right, before a single photo is taken.
:::

::: feature
name: Isometric, or the horizon
icon: cube

An orthographic camera, like an isometric game: the classic 30 degree view of a whole town, or a low shot
across the sea with a sky behind it.
:::

::: feature
name: Light, shadow and sky
icon: sun

Sun shadows, ambient occlusion and dark interiors. Dawn, day, dusk and night skies, with the sun, the moon in
its phase and stars that stay put as the camera turns.
:::

::: feature
name: Depth of field
icon: focus

Keep one distance sharp and let the rest fall away: a miniature tilt-shift look on a tilted camera, a subject
lifted off its background on a level one.
:::

::: feature
name: Real maps on real walls
icon: wall

Every photo is cut into 128x128 maps in item frames. They survive restarts, never change unless you retake
them, and come down whole if a frame is broken.
:::

::: feature
name: Every cost is yours to set
icon: sliders

Permissions decide how large and how expensive a photo may be, per group. Colors, filters, frames and every
message are plain files you can change.
:::

## How it works

::: step
name: Place a camera

Right-click a block with the camera from `/izocam item`. It starts at the classic isometric angle.
:::

::: step
name: Aim it

Click the camera to turn, tilt, zoom or move it, and watch the preview map in your off hand follow.
:::

::: step
name: Take it, hang it

Pick a size from one map to 16x9, take the photo, and hang it on any wall with a ghost that shows where it will go.
:::

## Gallery {#gallery}

Every picture on this page is a photo Izomap took, at the size it took it.

::: gallery
picture: gallery/dawn.png | Dawn over the town, the sun drawn where the shadows fall from
picture: gallery/night.png | The same view at night, under stars fixed to the sky
picture: gallery/town.png | Isometric, with sun shadows, ambient occlusion and block light
picture: gallery/village.png | Water shaded by its depth, flowers in their own colors
picture: gallery/focus.png | Depth of field, focused on the hilltop houses
picture: gallery/water.png | Lakes and a river: the shoreline draws itself
:::

## Color filters {#filters}

::: filters
folder: gallery/filters/
filter: original | Original
filter: warm | Warm
filter: cool | Cool
filter: vivid | Vivid
filter: sepia | Sepia
filter: grayscale | Black and white
filter: noir | Noir
link: Write your own filter | wiki/#filters

Seven filters ship with the plugin, and a server can write its own: chains of brightness, contrast,
saturation, tint and more, applied before every color is matched to the map palette. They cost nothing per pixel.
:::

## Coming soon

::: notice
Izomap is in its last round of testing before its first release, as a free and open source plugin. Until then,
[the documentation](wiki/) is already complete.
:::
