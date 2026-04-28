<template>
  <div
    ref="containerRef"
    class="crop-preview"
    :class="{
      'crop-preview--active': mode !== 'idle',
      'crop-preview--kb-box': keyboardMode === 'box',
    }"
    :tabindex="cropBox ? '0' : '-1'"
    aria-label="Page preview crop editor"
    @mousedown.prevent="onMouseDown"
    @click="onContainerClick"
    @focus="onContainerFocus"
    @keydown.capture="onKeyDown"
    @blur="onContainerBlur"
  >
    <!-- Page image -->
    <img
      v-if="imageUrl"
      :src="imageUrl"
      class="preview-image"
      draggable="false"
      @load="onImageLoad"
      @error="onImageError"
    />
    <div v-else class="preview-placeholder">
      <q-icon name="image" size="3rem" color="grey-5" />
      <div class="text-grey-5 text-caption q-mt-sm">No image URL</div>
    </div>
    <div v-if="imageError" class="preview-placeholder preview-placeholder--error">
      <q-icon name="broken_image" size="3rem" color="negative" />
      <div class="text-negative text-caption q-mt-sm">Failed to load image</div>
    </div>

    <!-- Dark overlay regions outside the crop box -->
    <template v-if="cropBox && imageLoaded && !imageError">
      <div class="crop-overlay crop-overlay--top" :style="overlayTopStyle" />
      <div class="crop-overlay crop-overlay--bottom" :style="overlayBottomStyle" />
      <div class="crop-overlay crop-overlay--left" :style="overlayLeftStyle" />
      <div class="crop-overlay crop-overlay--right" :style="overlayRightStyle" />

      <!-- Crop rectangle border -->
      <div
        class="crop-rect"
        :class="{ 'crop-rect--kb-selected': keyboardMode !== 'none' }"
        :style="cropRectStyle"
        @click.stop="onBoxClick"
      >
        <!-- Eight resize handles -->
        <div
          v-for="h in HANDLES"
          :key="h.name"
          role="button"
          :tabindex="cropBox ? '0' : '-1'"
          :aria-label="`${h.label} resize handle`"
          :class="[
            'crop-handle',
            `crop-handle--${h.name}`,
            { 'crop-handle--kb-active': keyboardMode === 'handle' && keyboardHandle === h.name },
          ]"
          @focus="onHandleFocus(h.name)"
          @mousedown.stop.prevent="startResize(h.name, $event)"
          @click.stop="onHandleClick(h.name)"
        />
      </div>
    </template>

    <!-- Label -->
    <div class="page-label text-caption text-white">{{ label }}</div>

    <!-- Accessible live region (screen-reader announcements) -->
    <div class="sr-only" role="status" aria-live="polite" aria-atomic="true">
      {{ ariaStatusText }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onUnmounted } from 'vue';
import type { CropBox } from 'components/models';

// ---------------------------------------------------------------------------
// Props / emits
// ---------------------------------------------------------------------------
const props = defineProps<{
  imageUrl: string;
  cropBox: CropBox | null;
  label?: string;
}>();

const emit = defineEmits<{
  (e: 'update:cropBox', box: CropBox): void;
  (e: 'imageSize', w: number, h: number): void;
}>();

// ---------------------------------------------------------------------------
// Refs
// ---------------------------------------------------------------------------
const containerRef = ref<HTMLElement | null>(null);
const imageLoaded = ref(false);
const imageError = ref(false);

type DragMode = 'idle' | 'drawing' | 'moving' | 'resizing';
const mode = ref<DragMode>('idle');
const dragStart = ref({ x: 0, y: 0 });
const dragStartBox = ref<CropBox | null>(null);
const activeHandle = ref('');

// ---------------------------------------------------------------------------
// Keyboard selection state
// ---------------------------------------------------------------------------
type KeyboardMode = 'none' | 'box' | 'handle';
const keyboardMode = ref<KeyboardMode>('none');
const keyboardHandle = ref<string>('');

const STEP_NORMAL = 1;  // px
const STEP_SHIFT = 10;  // px

// ---------------------------------------------------------------------------
// Handles
// ---------------------------------------------------------------------------
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

// ---------------------------------------------------------------------------
// Image load
// ---------------------------------------------------------------------------
function onImageLoad(e: Event) {
  const img = e.target as HTMLImageElement;
  imageLoaded.value = true;
  imageError.value = false;
  emit('imageSize', img.naturalWidth, img.naturalHeight);
}

function onImageError() {
  imageError.value = true;
  imageLoaded.value = false;
}

// ---------------------------------------------------------------------------
// Mouse helpers
// ---------------------------------------------------------------------------
function getRelativePos(e: MouseEvent): { x: number; y: number } {
  const rect = containerRef.value!.getBoundingClientRect();
  return {
    x: Math.min(Math.max((e.clientX - rect.left) / rect.width, 0), 1),
    y: Math.min(Math.max((e.clientY - rect.top) / rect.height, 0), 1),
  };
}

function clamp(v: number, lo = 0, hi = 1) {
  return Math.min(Math.max(v, lo), hi);
}

// ---------------------------------------------------------------------------
// Mouse events
// ---------------------------------------------------------------------------
function addDocumentListeners() {
  document.addEventListener('mousemove', onMouseMove);
  document.addEventListener('mouseup', onMouseUp);
}

function removeDocumentListeners() {
  document.removeEventListener('mousemove', onMouseMove);
  document.removeEventListener('mouseup', onMouseUp);
}

onUnmounted(stopDrag);

function onMouseDown(e: MouseEvent) {
  if (!imageLoaded.value || imageError.value) return;
  const pos = getRelativePos(e);

  // If clicking inside the existing crop rect → start moving
  if (props.cropBox) {
    const b = props.cropBox;
    const pad = 0.015; // inner dead-zone (avoids accidental move near edges)
    if (
      pos.x >= b.x + pad &&
      pos.x <= b.x + b.w - pad &&
      pos.y >= b.y + pad &&
      pos.y <= b.y + b.h - pad
    ) {
      mode.value = 'moving';
      dragStart.value = pos;
      dragStartBox.value = { ...b };
      addDocumentListeners();
      return;
    }
  }

  // Otherwise → start drawing a new crop rect
  mode.value = 'drawing';
  dragStart.value = pos;
  emit('update:cropBox', { x: pos.x, y: pos.y, w: 0, h: 0 });
  addDocumentListeners();
}

function onMouseMove(e: MouseEvent) {
  if (mode.value === 'idle') return;
  const pos = getRelativePos(e);

  if (mode.value === 'drawing') {
    const x = Math.min(pos.x, dragStart.value.x);
    const y = Math.min(pos.y, dragStart.value.y);
    const w = Math.abs(pos.x - dragStart.value.x);
    const h = Math.abs(pos.y - dragStart.value.y);
    emit('update:cropBox', { x, y, w, h });
    return;
  }

  if (mode.value === 'moving' && dragStartBox.value) {
    const b = dragStartBox.value;
    const dx = pos.x - dragStart.value.x;
    const dy = pos.y - dragStart.value.y;
    emit('update:cropBox', {
      x: clamp(b.x + dx, 0, 1 - b.w),
      y: clamp(b.y + dy, 0, 1 - b.h),
      w: b.w,
      h: b.h,
    });
    return;
  }

  if (mode.value === 'resizing' && dragStartBox.value) {
    const b = dragStartBox.value;
    let { x, y, w, h } = b;
    const dx = pos.x - dragStart.value.x;
    const dy = pos.y - dragStart.value.y;
    const handle = activeHandle.value;
    const MIN = 0.02;

    if (handle.includes('n')) {
      const newY = b.y + dy;
      const newH = b.h - dy;
      if (newH >= MIN) { y = newY; h = newH; }
    }
    if (handle.includes('s')) {
      const newH = b.h + dy;
      if (newH >= MIN) h = newH;
    }
    if (handle.includes('w')) {
      const newX = b.x + dx;
      const newW = b.w - dx;
      if (newW >= MIN) { x = newX; w = newW; }
    }
    if (handle.includes('e')) {
      const newW = b.w + dx;
      if (newW >= MIN) w = newW;
    }

    // Clamp to container bounds
    x = clamp(x, 0, 1 - MIN);
    y = clamp(y, 0, 1 - MIN);
    w = clamp(w, MIN, 1 - x);
    h = clamp(h, MIN, 1 - y);

    emit('update:cropBox', { x, y, w, h });
  }
}

function stopDrag() {
  mode.value = 'idle';
  dragStartBox.value = null;
  removeDocumentListeners();
}

function onMouseUp() {
  stopDrag();
}

function startResize(handle: string, e: MouseEvent) {
  if (!props.cropBox) return;
  mode.value = 'resizing';
  activeHandle.value = handle;
  dragStart.value = getRelativePos(e);
  dragStartBox.value = { ...props.cropBox };
  addDocumentListeners();
}

// ---------------------------------------------------------------------------
// Keyboard interaction
// ---------------------------------------------------------------------------
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

function onContainerFocus(e: FocusEvent) {
  if (!props.cropBox) return;
  if (e.target !== e.currentTarget) return;
  keyboardMode.value = 'box';
  keyboardHandle.value = '';
}

function onHandleFocus(handle: string) {
  if (!props.cropBox) return;
  keyboardMode.value = 'handle';
  keyboardHandle.value = handle;
}

function onContainerClick() {
  // A click that bubbles to the container (outside crop-rect) clears selection.
  keyboardMode.value = 'none';
  keyboardHandle.value = '';
}

function onContainerBlur() {
  keyboardMode.value = 'none';
  keyboardHandle.value = '';
}

function onKeyDown(e: KeyboardEvent) {
  if (!props.cropBox || keyboardMode.value === 'none') return;

  if (e.key === 'Escape') {
    e.preventDefault();
    if (keyboardMode.value === 'handle') {
      keyboardMode.value = 'box';
      keyboardHandle.value = '';
    } else {
      keyboardMode.value = 'none';
    }
    return;
  }

  if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(e.key)) return;
  e.preventDefault(); // prevent page scrolling

  const rect = containerRef.value!.getBoundingClientRect();
  const stepPx = e.shiftKey ? STEP_SHIFT : STEP_NORMAL;
  const dx = stepPx / rect.width;
  const dy = stepPx / rect.height;
  const MIN = 0.02;

  let { x, y, w, h } = props.cropBox;

  if (keyboardMode.value === 'box') {
    if (e.key === 'ArrowLeft')  x = clamp(x - dx, 0, 1 - w);
    if (e.key === 'ArrowRight') x = clamp(x + dx, 0, 1 - w);
    if (e.key === 'ArrowUp')    y = clamp(y - dy, 0, 1 - h);
    if (e.key === 'ArrowDown')  y = clamp(y + dy, 0, 1 - h);
  } else {
    const handle = keyboardHandle.value;
    const isLeft  = e.key === 'ArrowLeft';
    const isRight = e.key === 'ArrowRight';
    const isUp    = e.key === 'ArrowUp';
    const isDown  = e.key === 'ArrowDown';

    if (handle.includes('w') && (isLeft || isRight)) {
      const sign = isLeft ? -1 : 1;
      const newX = x + sign * dx;
      const newW = w - sign * dx;
      if (newW >= MIN) { x = newX; w = newW; }
    }
    if (handle.includes('e') && (isLeft || isRight)) {
      const sign = isLeft ? -1 : 1;
      const newW = w + sign * dx;
      if (newW >= MIN) w = newW;
    }
    if (handle.includes('n') && (isUp || isDown)) {
      const sign = isUp ? -1 : 1;
      const newY = y + sign * dy;
      const newH = h - sign * dy;
      if (newH >= MIN) { y = newY; h = newH; }
    }
    if (handle.includes('s') && (isUp || isDown)) {
      const sign = isUp ? -1 : 1;
      const newH = h + sign * dy;
      if (newH >= MIN) h = newH;
    }

    x = clamp(x, 0, 1 - MIN);
    y = clamp(y, 0, 1 - MIN);
    w = clamp(w, MIN, 1 - x);
    h = clamp(h, MIN, 1 - y);
  }

  emit('update:cropBox', { x, y, w, h });
}

// ---------------------------------------------------------------------------
// Aria status
// ---------------------------------------------------------------------------
const ariaStatusText = computed(() => {
  if (!props.cropBox) return 'No crop box defined.';
  if (keyboardMode.value === 'none') return 'Crop box present. Click to select.';
  if (keyboardMode.value === 'box')  return 'Crop box selected. Use arrow keys to move.';
  const h = HANDLES.find((hh) => hh.name === keyboardHandle.value);
  return `${h?.label ?? keyboardHandle.value} handle selected. Use arrow keys to resize.`;
});

// ---------------------------------------------------------------------------
// Computed styles
// ---------------------------------------------------------------------------
const cropRectStyle = computed(() => {
  if (!props.cropBox) return {};
  const { x, y, w, h } = props.cropBox;
  return {
    left: `${x * 100}%`,
    top: `${y * 100}%`,
    width: `${w * 100}%`,
    height: `${h * 100}%`,
  };
});

const overlayTopStyle = computed(() => {
  if (!props.cropBox) return {};
  return { height: `${props.cropBox.y * 100}%` };
});

const overlayBottomStyle = computed(() => {
  if (!props.cropBox) return {};
  const { y, h } = props.cropBox;
  return { top: `${(y + h) * 100}%`, bottom: '0' };
});

const overlayLeftStyle = computed(() => {
  if (!props.cropBox) return {};
  const { x, y, h } = props.cropBox;
  return {
    top: `${y * 100}%`,
    height: `${h * 100}%`,
    width: `${x * 100}%`,
  };
});

const overlayRightStyle = computed(() => {
  if (!props.cropBox) return {};
  const { x, y, w, h } = props.cropBox;
  return {
    top: `${y * 100}%`,
    height: `${h * 100}%`,
    left: `${(x + w) * 100}%`,
    right: '0',
  };
});
</script>

<style scoped lang="scss">
.crop-preview {
  position: relative;
  width: 100%;
  background: #1a1a1a;
  overflow: hidden;
  cursor: crosshair;
  user-select: none;
  border-radius: 4px;
  min-height: 160px;

  &--active {
    cursor: crosshair;
  }

  &:focus-visible {
    outline: 2px dashed #ffcc00;
    outline-offset: 2px;
  }
}

.preview-image {
  display: block;
  width: 100%;
  height: auto;
  pointer-events: none;
}

.preview-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 160px;

  &--error {
    position: absolute;
    inset: 0;
    background: rgba(0, 0, 0, 0.55);
  }
}

.crop-overlay {
  position: absolute;
  left: 0;
  right: 0;
  background: rgba(0, 0, 0, 0.45);
  pointer-events: none;

  &--top    { top: 0; }
  &--left   { }
  &--right  { }
  &--bottom { }
}

.crop-rect {
  position: absolute;
  border: 2px solid #ffcc00;
  box-sizing: border-box;
  cursor: move;

  &--kb-selected {
    border-color: #ffffff;
    box-shadow: 0 0 0 2px rgba(255, 255, 255, 0.35);
  }
}

// Resize handles
$handle-size: 10px;

.crop-handle {
  position: absolute;
  width: $handle-size;
  height: $handle-size;
  background: #ffcc00;
  border: 1px solid #333;
  box-sizing: border-box;

  &--nw { top: -5px;  left: -5px;  cursor: nw-resize; }
  &--n  { top: -5px;  left: calc(50% - 5px); cursor: n-resize; }
  &--ne { top: -5px;  right: -5px; cursor: ne-resize; }
  &--w  { top: calc(50% - 5px); left: -5px;  cursor: w-resize; }
  &--e  { top: calc(50% - 5px); right: -5px; cursor: e-resize; }
  &--sw { bottom: -5px; left: -5px;  cursor: sw-resize; }
  &--s  { bottom: -5px; left: calc(50% - 5px); cursor: s-resize; }
  &--se { bottom: -5px; right: -5px; cursor: se-resize; }

  &--kb-active {
    background: #4fc3f7;
    border-color: #0288d1;
    box-shadow: 0 0 0 3px rgba(79, 195, 247, 0.5);
    z-index: 1;
  }
}

.page-label {
  position: absolute;
  top: 4px;
  left: 6px;
  background: rgba(0, 0, 0, 0.6);
  padding: 2px 6px;
  border-radius: 3px;
  font-size: 11px;
  pointer-events: none;
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
</style>
