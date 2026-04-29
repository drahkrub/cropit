import type { CropBox } from '../components/models';

export type ResizeHandle = 'nw' | 'n' | 'ne' | 'w' | 'e' | 'sw' | 's' | 'se';

export const MIN_CROP_BOX_SIZE = 0.02;

const DEFAULT_MOVE_CAPTURE_PADDING = 0.015;

function clamp(value: number, min = 0, max = 1): number {
  return Math.min(Math.max(value, min), max);
}

export function moveCropBox(box: CropBox, dx: number, dy: number): CropBox {
  return {
    x: clamp(box.x + dx, 0, 1 - box.w),
    y: clamp(box.y + dy, 0, 1 - box.h),
    w: box.w,
    h: box.h,
  };
}

export function resizeCropBox(
  box: CropBox,
  handle: ResizeHandle,
  dx: number,
  dy: number,
  minSize = MIN_CROP_BOX_SIZE,
): CropBox {
  let left = box.x;
  let right = box.x + box.w;
  let top = box.y;
  let bottom = box.y + box.h;

  if (handle.includes('w')) {
    left = clamp(left + dx, 0, right - minSize);
  }
  if (handle.includes('e')) {
    right = clamp(right + dx, left + minSize, 1);
  }
  if (handle.includes('n')) {
    top = clamp(top + dy, 0, bottom - minSize);
  }
  if (handle.includes('s')) {
    bottom = clamp(bottom + dy, top + minSize, 1);
  }

  return {
    x: left,
    y: top,
    w: right - left,
    h: bottom - top,
  };
}

export function getMoveCapturePadding(box: CropBox, preferred = DEFAULT_MOVE_CAPTURE_PADDING): number {
  return Math.max(0, Math.min(preferred, box.w / 4, box.h / 4));
}