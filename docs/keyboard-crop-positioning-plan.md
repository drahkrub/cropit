# Keyboard-Based Pixel-Precise Crop-Box Positioning – Implementation Plan

> **Status:** Planned  
> **Target component:** `frontend/src/components/CropPreview.vue`  
> **Related docs:** [`DESIGN.md`](../DESIGN.md), [`README.md`](../README.md)

---

## 1. UX Goals and Rationale

Mouse dragging is convenient for rough placement but becomes imprecise when the
crop box needs to align exactly with a page boundary, a column margin, or a
repeating header/footer. Users currently must zoom in mentally or accept small
misalignments of a few pixels.

**Goals**

| # | Goal |
|---|------|
| G1 | Allow the crop box (as a whole) to be nudged by exactly 1 px per arrow-key press. |
| G2 | Allow any individual resize handle to be moved by exactly 1 px per arrow-key press. |
| G3 | Provide a `Shift`-accelerated step (10 px) for larger adjustments. |
| G4 | Keep the keyboard interaction discoverable without requiring a manual. |
| G5 | Not break or replace existing mouse interaction. |
| G6 | Be accessible: work entirely without a mouse and expose focus to screen readers where practical. |

**Rationale**  
The crop-box coordinates are stored as normalised floats (0…1), but the visual
display is ~560 px wide. A 1-pixel step in display space corresponds to roughly
`1/560 ≈ 0.0018` normalised units – small enough for pixel-level precision,
large enough to be perceptible. The mapping must therefore be derived from the
actual container pixel dimensions at runtime (see §4).

---

## 2. Proposed Interaction Model

### 2.1 Activation States

```
(none selected)
    │  click crop-box interior
    ▼
(box selected)  ──────────────────── Esc ──► (none selected)
    │  click a handle
    ▼
(handle selected)  ────────────────── Esc ──► (box selected)
                   ─── click outside ──────► (none selected)
```

| State | Visual cue | Arrow keys do |
|-------|-----------|---------------|
| none  | normal appearance | — |
| box   | brighter/thicker border; subtle glow | move whole box |
| handle | handle turns blue/white, larger, with label | move that edge/corner |

### 2.2 Entering and Exiting Selection

| Action | Result |
|--------|--------|
| Click inside the crop-box interior | select **box** |
| Click a handle | select that **handle** |
| `Tab` (while component has focus) | cycle through: none → box → nw → n → ne → e → se → s → sw → w → none |
| `Esc` | deactivate current selection (one level up, see diagram above) |
| Click outside the crop-box entirely | clear all selection |

### 2.3 Arrow-Key Semantics

**When the box is selected** (`keyboardMode = 'box'`):

| Key | Effect |
|-----|--------|
| `←` | move box left by `step` px |
| `→` | move box right by `step` px |
| `↑` | move box up by `step` px |
| `↓` | move box down by `step` px |

**When a handle is selected** (`keyboardMode = 'handle'`):

Each handle controls one or two edges. Only the axes that the handle sits on
are affected:

| Handle | `←` / `→` moves | `↑` / `↓` moves |
|--------|-----------------|-----------------|
| `nw`   | left edge left/right | top edge up/down |
| `n`    | — | top edge up/down |
| `ne`   | right edge left/right | top edge up/down |
| `w`    | left edge left/right | — |
| `e`    | right edge left/right | — |
| `sw`   | left edge left/right | bottom edge up/down |
| `s`    | — | bottom edge up/down |
| `se`   | right edge left/right | bottom edge up/down |

> **Step size:**  
> – Default: **1 px** in display space.  
> – With `Shift` held: **10 px**.  
> – (Optional, future) With `Ctrl` held: **0.1 px** (sub-pixel movement stored
>   in normalised coords; the visual display rounds to the nearest integer pixel
>   but the stored value remains precise).

### 2.4 Focus / Keyboard Discoverability

- The crop-preview container gets `tabindex="0"` so it can receive focus when
  the user tabs to it.
- A small tooltip / badge appears the first time the box is drawn:
  *"Click box or handle, then use arrow keys for pixel-precise adjustment."*
- The active element name (e.g. `"nw handle"`, `"crop box"`) is set on an
  `aria-live` region so screen readers announce the change.

---

## 3. State Model for the Frontend

The existing `mode` ref in `CropPreview.vue` already tracks mouse drag modes
(`'idle' | 'drawing' | 'moving' | 'resizing'`). Keyboard selection is a
separate, orthogonal concern:

```ts
// New refs to add to CropPreview.vue

/** What the keyboard will move when arrow keys are pressed. */
type KeyboardMode = 'none' | 'box' | 'handle';
const keyboardMode = ref<KeyboardMode>('none');

/**
 * Which handle is currently keyboard-selected.
 * Only meaningful when keyboardMode === 'handle'.
 * Values: 'nw' | 'n' | 'ne' | 'w' | 'e' | 'sw' | 's' | 'se'
 */
const keyboardHandle = ref<string>('');
```

The state transitions are driven by click events and `keydown` handlers
(see §7 for the implementation sketch).

**Full state table**

| `keyboardMode` | `keyboardHandle` | meaning |
|----------------|-----------------|---------|
| `'none'` | `''` | no keyboard selection |
| `'box'` | `''` | whole box is keyboard-selected |
| `'handle'` | `'nw'` … `'se'` | one handle is keyboard-selected |

---

## 4. Pixel-to-Normalised-Coordinate Conversion Strategy

### 4.1 Core Formula

The crop box is stored in normalised coordinates relative to the displayed
container. A "1 px step" therefore corresponds to:

```
δ_x = 1 / containerWidthPx
δ_y = 1 / containerHeightPx
```

These values are computed at the moment the arrow key is pressed by reading the
container's current bounding rect:

```ts
function pixelDelta(): { dx: number; dy: number } {
  const rect = containerRef.value!.getBoundingClientRect();
  return { dx: 1 / rect.width, dy: 1 / rect.height };
}
```

### 4.2 Multi-Preview Consistency

Because all `CropPreview` instances in `IndexPage.vue` share the same `cropBox`
reactive object (passed as a prop with `v-model`), a keyboard step applied to
any preview is immediately reflected in all others. The normalised coordinates
are inherently resolution-independent, so a "1 px step" refers to 1 px in the
container that currently has keyboard focus – which is the natural expectation.

### 4.3 Step with `Shift`

```ts
const STEP_NORMAL = 1;   // px
const STEP_SHIFT  = 10;  // px

function stepSize(e: KeyboardEvent): number {
  return e.shiftKey ? STEP_SHIFT : STEP_NORMAL;
}
```

---

## 5. Boundary and Clamping Rules

All clamping reuses the existing `clamp(v, lo, hi)` helper already present in
`CropPreview.vue`.

### 5.1 Box-Move Clamping

```
newX = clamp(box.x + dx, 0, 1 − box.w)
newY = clamp(box.y + dy, 0, 1 − box.h)
```

The box cannot be pushed outside the image in any direction.

### 5.2 Handle-Move Clamping

The same rules that govern mouse resizing apply. In addition:

- **Minimum size guard**: The box must remain at least `MIN = 0.02` normalised
  units wide and tall. Moving a handle that would invert or collapse the
  rectangle is silently ignored.
- **Left/top edges**: When moving left (`'nw'`, `'w'`, `'sw'`) or top
  (`'nw'`, `'n'`, `'ne'`) edges, the corresponding dimension shrinks and the
  position shifts; if the new dimension would drop below `MIN`, the key press
  is discarded.
- **Right/bottom edges**: Moving outward can reach at most `1.0` normalised;
  the corresponding `w` or `h` is clamped to `1 − x` or `1 − y`.

### 5.3 Summary Table

| Condition | Rule |
|-----------|------|
| `box.x < 0` | clamp to `0` |
| `box.y < 0` | clamp to `0` |
| `box.x + box.w > 1` | clamp `x` to `1 − box.w` (box move) or `w` to `1 − box.x` (handle move) |
| `box.y + box.h > 1` | analogous |
| `box.w < MIN` | discard key press |
| `box.h < MIN` | discard key press |

---

## 6. Accessibility Considerations

| Topic | Approach |
|-------|----------|
| **Focus styling** | Add a visible focus ring around the `.crop-preview` container when it is focused (CSS `:focus-visible` outline, e.g. `2px dashed #ffcc00`). The selected handle gets a distinct fill colour (e.g. blue) and a `box-shadow` ring to distinguish it from unselected handles. |
| **Focus order** | `tabindex="0"` on the container. Tab cycles logically through box → handles in reading order (nw → n → ne → e → se → s → sw → w). |
| **ARIA live region** | A visually-hidden `<div role="status" aria-live="polite">` announces state changes, e.g. *"Crop box selected"*, *"Northwest handle selected"*, *"Selection cleared"*. |
| **ARIA label on handles** | Each handle `<div>` receives `aria-label` (e.g. `"Northwest resize handle"`) and `role="button"` so assistive technologies can identify it. |
| **Keyboard discoverability** | A `title` attribute on the container reads *"Use arrow keys to move crop box or handle after selecting"*. A one-time hint banner may also be shown. |
| **Screen-reader state** | The container gets `aria-description` describing current box position in normalised values. Updated on each arrow-key press. |

---

## 7. Suggested Component / Code Changes

### 7.1 `frontend/src/components/CropPreview.vue`

**Template changes**

```html
<!-- Make container focusable and keyboard-aware -->
<div
  ref="containerRef"
  class="crop-preview"
  :class="{
    'crop-preview--active': mode !== 'idle',
    'crop-preview--kb-box': keyboardMode === 'box',
  }"
  tabindex="0"
  :aria-label="`Page preview. ${ariaStatus}`"
  @mousedown.prevent="onMouseDown"
  @keydown="onKeyDown"
  @click="onContainerClick"
  @blur="onContainerBlur"
>
  <!-- ... existing content ... -->

  <!-- Crop rectangle (add click handler for box-selection) -->
  <div
    class="crop-rect"
    :class="{ 'crop-rect--selected': keyboardMode !== 'none' }"
    :style="cropRectStyle"
    @click.stop="onBoxClick"
  >
    <!-- Eight resize handles (add click handler for handle-selection) -->
    <div
      v-for="h in HANDLES"
      :key="h.name"
      role="button"
      :aria-label="`${h.label} resize handle`"
      :class="[
        'crop-handle',
        `crop-handle--${h.name}`,
        { 'crop-handle--kb-active': keyboardMode === 'handle' && keyboardHandle === h.name },
      ]"
      @mousedown.stop.prevent="startResize(h.name, $event)"
      @click.stop="onHandleClick(h.name)"
    />
  </div>

  <!-- Accessible live region -->
  <div class="sr-only" role="status" aria-live="polite" aria-atomic="true">
    {{ ariaStatus }}
  </div>
</template>
```

**Script additions**

```ts
// --- New refs ---
type KeyboardMode = 'none' | 'box' | 'handle';
const keyboardMode = ref<KeyboardMode>('none');
const keyboardHandle = ref<string>('');

// --- Constants ---
const STEP_NORMAL = 1;   // px
const STEP_SHIFT  = 10;  // px
const MIN = 0.02;

// --- Click handlers ---
function onBoxClick() {
  if (!props.cropBox) return;
  keyboardMode.value = 'box';
  keyboardHandle.value = '';
  containerRef.value?.focus();
}

function onHandleClick(handle: string) {
  if (!props.cropBox) return;
  keyboardMode.value = 'handle';
  keyboardHandle.value = handle;
  containerRef.value?.focus();
}

function onContainerClick(e: MouseEvent) {
  // click on background (outside crop-rect) → clear selection
  keyboardMode.value = 'none';
  keyboardHandle.value = '';
}

function onContainerBlur() {
  keyboardMode.value = 'none';
  keyboardHandle.value = '';
}

// --- Keyboard handler ---
function onKeyDown(e: KeyboardEvent) {
  if (!props.cropBox || keyboardMode.value === 'none') return;
  if (!['ArrowLeft','ArrowRight','ArrowUp','ArrowDown','Escape','Tab'].includes(e.key)) return;

  if (e.key === 'Escape') {
    if (keyboardMode.value === 'handle') {
      keyboardMode.value = 'box';
      keyboardHandle.value = '';
    } else {
      keyboardMode.value = 'none';
    }
    e.preventDefault();
    return;
  }

  if (e.key === 'Tab') {
    // handled by the browser for focus cycling; do not preventDefault
    return;
  }

  e.preventDefault(); // prevent page scrolling

  const rect = containerRef.value!.getBoundingClientRect();
  const step = (e.shiftKey ? STEP_SHIFT : STEP_NORMAL);
  const dx = step / rect.width;
  const dy = step / rect.height;

  let { x, y, w, h } = props.cropBox;

  if (keyboardMode.value === 'box') {
    if (e.key === 'ArrowLeft')  x = clamp(x - dx, 0, 1 - w);
    if (e.key === 'ArrowRight') x = clamp(x + dx, 0, 1 - w);
    if (e.key === 'ArrowUp')    y = clamp(y - dy, 0, 1 - h);
    if (e.key === 'ArrowDown')  y = clamp(y + dy, 0, 1 - h);
  } else {
    // handle-specific axis constraints (see §2.3 table)
    const handle = keyboardHandle.value;
    if (handle.includes('w') && (e.key === 'ArrowLeft' || e.key === 'ArrowRight')) {
      const sign = e.key === 'ArrowLeft' ? -1 : 1;
      const newX = x + sign * dx;
      const newW = w - sign * dx;
      if (newW >= MIN) { x = newX; w = newW; }
    }
    if (handle.includes('e') && (e.key === 'ArrowLeft' || e.key === 'ArrowRight')) {
      const sign = e.key === 'ArrowLeft' ? -1 : 1;
      const newW = w + sign * dx;
      if (newW >= MIN) w = newW;
    }
    if (handle.includes('n') && (e.key === 'ArrowUp' || e.key === 'ArrowDown')) {
      const sign = e.key === 'ArrowUp' ? -1 : 1;
      const newY = y + sign * dy;
      const newH = h - sign * dy;
      if (newH >= MIN) { y = newY; h = newH; }
    }
    if (handle.includes('s') && (e.key === 'ArrowUp' || e.key === 'ArrowDown')) {
      const sign = e.key === 'ArrowUp' ? -1 : 1;
      const newH = h + sign * dy;
      if (newH >= MIN) h = newH;
    }
    // Clamp to image bounds
    x = clamp(x, 0, 1 - MIN);
    y = clamp(y, 0, 1 - MIN);
    w = clamp(w, MIN, 1 - x);
    h = clamp(h, MIN, 1 - y);
  }

  emit('update:cropBox', { x, y, w, h });
}

// --- Aria status ---
const ariaStatus = computed(() => {
  if (!props.cropBox) return 'No crop box defined.';
  if (keyboardMode.value === 'none') return 'Crop box present. Click to select.';
  if (keyboardMode.value === 'box')  return 'Crop box selected. Use arrow keys to move.';
  const h = HANDLES.find(hh => hh.name === keyboardHandle.value);
  return `${h?.label ?? keyboardHandle.value} handle selected. Use arrow keys to resize.`;
});
```

**HANDLES definition** – add a `label` field:

```ts
const HANDLES = [
  { name: 'nw', label: 'Northwest' },
  { name: 'n',  label: 'North' },
  { name: 'ne', label: 'Northeast' },
  { name: 'w',  label: 'West' },
  { name: 'e',  label: 'East' },
  { name: 'sw', label: 'Southwest' },
  { name: 's',  label: 'South' },
  { name: 'se', label: 'Southeast' },
] as const;
```

**Style additions**

```scss
.crop-preview {
  // ... existing styles ...

  &:focus-visible {
    outline: 2px dashed #ffcc00;
    outline-offset: 2px;
  }

  &--kb-box .crop-rect {
    border-color: #ffffff;
    box-shadow: 0 0 0 2px rgba(255, 255, 255, 0.4);
  }
}

.crop-handle {
  // ... existing styles ...

  &--kb-active {
    background: #4fc3f7;
    border-color: #0288d1;
    box-shadow: 0 0 0 3px rgba(79, 195, 247, 0.5);
    z-index: 1;
  }
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
```

### 7.2 `frontend/src/pages/IndexPage.vue`

No functional changes are required. If a keyboard-shortcut help tooltip is
added, it can be placed near the preview cards. A future enhancement could add
a numeric input overlay (see §9).

### 7.3 `frontend/src/components/models.ts`

No changes required. `CropBox` (`{ x, y, w, h }`) is already sufficient.

---

## 8. Testing Plan

### 8.1 Manual Test Checklist

**Basic activation**
- [ ] Click inside the crop-box border → box highlighted, arrow keys move whole box.
- [ ] Click a handle → handle highlighted (blue), arrow keys resize.
- [ ] `Esc` while handle selected → back to box-selected state.
- [ ] `Esc` while box selected → selection cleared.
- [ ] Click outside crop area → selection cleared.
- [ ] Component loses focus (Tab away) → selection cleared.

**Arrow key movement – box mode**
- [ ] Each of the four arrow keys moves the box by exactly 1 display pixel.
- [ ] `Shift` + arrow key moves by 10 display pixels.
- [ ] Box cannot be pushed outside the image bounds (verify all four edges).

**Arrow key movement – handle mode**
- [ ] `nw` handle: `←/→` moves left edge; `↑/↓` moves top edge.
- [ ] `n` handle: only `↑/↓` work; `←/→` have no effect.
- [ ] `se` handle: `←/→` moves right edge; `↑/↓` moves bottom edge.
- [ ] (Repeat for all 8 handles.)
- [ ] Cannot shrink box below minimum size on any axis.

**Multi-preview synchronisation**
- [ ] Keyboard movement on preview 1 is immediately reflected in preview 2 (if multiple previews are shown).

**Accessibility**
- [ ] `Tab` to the crop-preview container → visible focus ring appears.
- [ ] Screen reader announces "Crop box selected" when box is clicked.
- [ ] Screen reader announces "Northwest handle selected" when nw handle is clicked.
- [ ] `aria-label` on each handle div is correct.

### 8.2 Automated / Unit Tests

The coordinate logic is pure (no DOM dependency). A Vitest unit-test file could
cover the following functions:

```
// File: frontend/src/components/__tests__/CropPreview.keyboard.test.ts

describe('keyboard step clamping – box mode', () => {
  it('moves left within bounds', ...)
  it('clamps at left edge (x = 0)', ...)
  it('clamps at right edge (x + w = 1)', ...)
  it('shift key multiplies step by 10', ...)
})

describe('keyboard step clamping – handle mode', () => {
  it('nw handle: left arrow shrinks width and shifts x', ...)
  it('nw handle: right arrow expands width (x moves right, w shrinks)', ...)
  it('n handle: left/right arrows have no effect', ...)
  it('discard key press if new width < MIN', ...)
})
```

The coordinate math can be extracted into a pure helper function
`applyKeyboardStep(box, mode, handle, key, shiftKey, containerW, containerH)`
to make it straightforwardly unit-testable without a mounted Vue component.

---

## 9. Open Questions and Optional Future Enhancements

| # | Topic | Notes |
|---|-------|-------|
| Q1 | **Sub-pixel precision**: Should `Ctrl+Arrow` allow steps smaller than 1 px? | Only meaningful in normalised storage; visual display rounds anyway. |
| Q2 | **Numeric input overlay**: Show a small editable number field near the active handle so the user can type an exact pixel value directly. | High precision; requires additional UX for confirming/dismissing the overlay. |
| Q3 | **Undo/redo**: Arrow-key steps accumulate quickly; a `Ctrl+Z` stack could help. | Would require a history stack in `IndexPage` or a composable. |
| Q4 | **Touch / mobile**: Touch-tap to select then swipe for pixel steps. | Out of scope for initial implementation. |
| Q5 | **Per-page crop boxes**: Currently all pages share one crop box. Future work may need per-page selection with keyboard focus following page navigation. | Noted in existing `DESIGN.md`. |
| Q6 | **Scroll prevention**: `e.preventDefault()` on arrow keys prevents page scrolling while the component is focused. This is the right behaviour but may surprise users who expect scrolling when nothing is selected. Guard it with `keyboardMode !== 'none'`. | Already accounted for in §7.1 code sketch. |
| Q7 | **Axis-lock indicator**: A visual cue showing which axes a handle controls (similar to CAD software axis highlights) would aid discoverability. | Nice-to-have. |
