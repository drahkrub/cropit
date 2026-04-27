# Cropit – PDF Crop Box Editor

A lightweight [Quasar](https://quasar.dev) (Vue 3 / TypeScript) browser UI for
drawing a shared crop box across four PDF page preview images in parallel.

## Purpose

When converting PDF pages to PNG with  
```
pdftoppm -png -r 200 -scale-to 1540 input.pdf output
```
the resulting images often contain repetitive headers and footers that degrade
visual-AI (LLM) Markdown extraction quality.  
**Cropit** lets you visually define a crop rectangle that covers the actual page
content and applies it consistently to all four displayed pages at once, giving
you normalised `(x, y, w, h)` coordinates you can pass back to the backend.

## Requirements

| Tool  | Version |
|-------|---------|
| Node  | 20 – 28 |
| npm   | ≥ 6.14  |

## Install

```bash
cd frontend
npm install
```

## Run (development)

```bash
npm run dev
```

The app starts on <http://localhost:9000> by default.

## Build (production SPA)

```bash
npm run build
# output in frontend/dist/spa/
```

## Lint / type-check

```bash
npm run lint
```

## Usage

1. Paste four PNG URLs into the input fields at the top (demo images from
   [picsum.photos](https://picsum.photos) are pre-filled).
2. **Draw** a crop rectangle by clicking and dragging on any preview image.
3. **Move** the rectangle by clicking inside it and dragging.
4. **Resize** it using the eight yellow handles on the border.
5. The rectangle is mirrored across all four previews simultaneously.
6. The **Crop Box Values** panel at the bottom shows:
   - Normalised coordinates `(x, y, w, h)` in the range `0…1`
   - Absolute pixel coordinates (based on the natural size of the first loaded
     image)
   - A JSON snippet ready to pass to your backend
7. Click **Clear crop** to start over.

