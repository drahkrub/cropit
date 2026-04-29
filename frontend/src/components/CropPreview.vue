<template>
  <div
    ref="containerRef"
    class="crop-preview"
    tabindex="-1"
    aria-label="Page preview crop editor"
    @pointerdown="onPointerDown"
    @click="onContainerClick"
    @keydown="onKeyDown"
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
          :class="[
            'crop-handle',
            `crop-handle--${h.name}`,
            { 'crop-handle--kb-active': keyboardMode === 'handle' && keyboardHandle === h.name },
          ]"
          @pointerdown.stop="startResize(h.name, $event)"
          @click.stop="onHandleClick(h.name)"
        />
      </div>
    </template>

    <!-- Label -->
    <div class="page-label text-caption text-white">{{ label }}</div>

  </div>
  <div class="sr-only" role="status" aria-live="polite" aria-atomic="true">
    {{ ariaStatusText }}
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onUnmounted } from 'vue';
import type { CropBox } from 'components/models';
import {
  getMoveCapturePadding,
  moveCropBox,
  resizeCropBox,
} from '../utils/cropBoxGeometry';
import type { ResizeHandle } from '../utils/cropBoxGeometry';

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

type DragMode = 'idle' | 'moving' | 'resizing';
const mode = ref<DragMode>('idle');
const dragStart = ref({ x: 0, y: 0 });
const dragStartBox = ref<CropBox | null>(null);
const activeHandle = ref<ResizeHandle | ''>('');
let activePointerId: number | null = null;

// ---------------------------------------------------------------------------
// Keyboard selection state
// ---------------------------------------------------------------------------
type KeyboardMode = 'none' | 'box' | 'handle';
const keyboardMode = ref<KeyboardMode>('none');
const keyboardHandle = ref<ResizeHandle | ''>('');

const STEP_NORMAL = 1; // px
const STEP_SHIFT = 10; // px

// ---------------------------------------------------------------------------
// Handles
// ---------------------------------------------------------------------------
const HANDLES: ReadonlyArray<{ name: ResizeHandle; label: string }> = [
  { name: 'nw', label: 'Northwest' },
  { name: 'n', label: 'North' },
  { name: 'ne', label: 'Northeast' },
  { name: 'w', label: 'West' },
  { name: 'e', label: 'East' },
  { name: 'sw', label: 'Southwest' },
  { name: 's', label: 'South' },
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
// Pointer helpers
// ---------------------------------------------------------------------------
function getRelativePos(e: PointerEvent): { x: number; y: number } {
  const rect = containerRef.value!.getBoundingClientRect();
  return {
    x: Math.min(Math.max((e.clientX - rect.left) / rect.width, 0), 1),
    y: Math.min(Math.max((e.clientY - rect.top) / rect.height, 0), 1),
  };
}

// ---------------------------------------------------------------------------
// Pointer events
// ---------------------------------------------------------------------------
function addPointerListeners() {
  document.addEventListener('pointermove', onPointerMove);
  document.addEventListener('pointerup', onPointerUp);
  document.addEventListener('pointercancel', onPointerCancel);
}

function removePointerListeners() {
  document.removeEventListener('pointermove', onPointerMove);
  document.removeEventListener('pointerup', onPointerUp);
  document.removeEventListener('pointercancel', onPointerCancel);
}

function captureActivePointer(pointerId: number) {
  const container = containerRef.value;
  if (!container) return;

  try {
    container.setPointerCapture(pointerId);
  } catch {
    // Pointer capture can fail for unsupported or already-finished pointers.
  }
}

function releaseActivePointer(pointerId: number | null) {
  const container = containerRef.value;
  if (!container || pointerId === null) return;

  if (container.hasPointerCapture(pointerId)) {
    container.releasePointerCapture(pointerId);
  }
}

function isPrimaryActivation(e: PointerEvent): boolean {
  return e.isPrimary && e.button === 0;
}

function startInteraction(
  modeValue: DragMode,
  pointerId: number,
  pos: { x: number; y: number },
  box: CropBox,
  handle: ResizeHandle | '' = '',
) {
  mode.value = modeValue;
  activePointerId = pointerId;
  activeHandle.value = handle;
  dragStart.value = pos;
  dragStartBox.value = { ...box };
  addPointerListeners();
  captureActivePointer(pointerId);
}

onUnmounted(stopDrag);

function onPointerDown(e: PointerEvent) {
  if (!isPrimaryActivation(e) || mode.value !== 'idle') return;
  if (!imageLoaded.value || imageError.value || !props.cropBox) return;

  const pos = getRelativePos(e);

  // If clicking inside the existing crop rect → start moving
  const b = props.cropBox;
  const pad = getMoveCapturePadding(b);
  if (
    pos.x >= b.x + pad &&
    pos.x <= b.x + b.w - pad &&
    pos.y >= b.y + pad &&
    pos.y <= b.y + b.h - pad
  ) {
    e.preventDefault();
    startInteraction('moving', e.pointerId, pos, b);
  }
}

function onPointerMove(e: PointerEvent) {
  if (mode.value === 'idle' || activePointerId !== e.pointerId) return;
  const pos = getRelativePos(e);

  if (mode.value === 'moving' && dragStartBox.value) {
    const b = dragStartBox.value;
    const dx = pos.x - dragStart.value.x;
    const dy = pos.y - dragStart.value.y;
    emit('update:cropBox', moveCropBox(b, dx, dy));
    return;
  }

  if (mode.value === 'resizing' && dragStartBox.value) {
    const b = dragStartBox.value;
    const dx = pos.x - dragStart.value.x;
    const dy = pos.y - dragStart.value.y;
    if (!activeHandle.value) return;

    emit('update:cropBox', resizeCropBox(b, activeHandle.value, dx, dy));
  }
}

function stopDrag() {
  const pointerId = activePointerId;
  mode.value = 'idle';
  dragStartBox.value = null;
  activeHandle.value = '';
  activePointerId = null;
  removePointerListeners();
  releaseActivePointer(pointerId);
}

function onPointerUp(e: PointerEvent) {
  if (activePointerId !== e.pointerId) return;
  stopDrag();
}

function onPointerCancel(e: PointerEvent) {
  if (activePointerId !== e.pointerId) return;
  stopDrag();
}

function startResize(handle: ResizeHandle, e: PointerEvent) {
  if (!isPrimaryActivation(e) || mode.value !== 'idle') return;
  if (!props.cropBox) return;

  e.preventDefault();
  startInteraction('resizing', e.pointerId, getRelativePos(e), props.cropBox, handle);
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

function onHandleClick(handle: ResizeHandle) {
  if (!props.cropBox) return;
  keyboardMode.value = 'handle';
  keyboardHandle.value = handle;
  containerRef.value?.focus();
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

  const rect = containerRef.value?.getBoundingClientRect();
  if (!rect) return;
  const stepPx = e.shiftKey ? STEP_SHIFT : STEP_NORMAL;
  const dx = stepPx / rect.width;
  const dy = stepPx / rect.height;

  if (keyboardMode.value === 'box') {
    const moveDx = e.key === 'ArrowLeft' ? -dx : e.key === 'ArrowRight' ? dx : 0;
    const moveDy = e.key === 'ArrowUp' ? -dy : e.key === 'ArrowDown' ? dy : 0;
    emit('update:cropBox', moveCropBox(props.cropBox, moveDx, moveDy));
  } else {
    const handle = keyboardHandle.value;
    if (!handle) return;
    const isLeft  = e.key === 'ArrowLeft';
    const isRight = e.key === 'ArrowRight';
    const isUp    = e.key === 'ArrowUp';
    const isDown  = e.key === 'ArrowDown';
    const resizeDx = isLeft ? -dx : isRight ? dx : 0;
    const resizeDy = isUp ? -dy : isDown ? dy : 0;

    emit('update:cropBox', resizeCropBox(props.cropBox, handle, resizeDx, resizeDy));
  }
}

// ---------------------------------------------------------------------------
// Aria status
// ---------------------------------------------------------------------------
const ariaStatusText = computed(() => {
  if (!props.cropBox) return 'No crop box defined.';
  if (keyboardMode.value === 'none') return 'Crop box present. Click to select.';
  if (keyboardMode.value === 'box') return 'Crop box selected. Use arrow keys to move. Press Escape to clear selection.';
  const h = HANDLES.find((hh) => hh.name === keyboardHandle.value);
  return `${h?.label ?? keyboardHandle.value} handle selected. Use arrow keys to resize. Press Escape to return to box selection.`;
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
  cursor: default;
  user-select: none;
  border-radius: 4px;
  min-height: 160px;
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
}

.crop-rect {
  position: absolute;
  border: 2px solid #ffcc00;
  box-sizing: border-box;
  cursor: move;
  touch-action: none;

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
  touch-action: none;

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
