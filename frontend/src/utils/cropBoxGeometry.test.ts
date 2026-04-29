import { describe, expect, it } from 'vitest';

import type { CropBox } from '../components/models';
import {
  getMoveCapturePadding,
  MIN_CROP_BOX_SIZE,
  moveCropBox,
  resizeCropBox,
} from './cropBoxGeometry';

function expectBox(actual: CropBox, expected: CropBox): void {
  expect(actual.x).toBeCloseTo(expected.x);
  expect(actual.y).toBeCloseTo(expected.y);
  expect(actual.w).toBeCloseTo(expected.w);
  expect(actual.h).toBeCloseTo(expected.h);
}

describe('cropBoxGeometry', () => {
  it('keeps the opposite edge fixed when resizing past the left boundary', () => {
    const box: CropBox = { x: 0.1, y: 0.2, w: 0.5, h: 0.4 };

    expectBox(resizeCropBox(box, 'w', -0.2, 0), {
      x: 0,
      y: 0.2,
      w: 0.6,
      h: 0.4,
    });
  });

  it('keeps the opposite edge fixed when resizing past the top boundary', () => {
    const box: CropBox = { x: 0.1, y: 0.2, w: 0.5, h: 0.4 };

    expectBox(resizeCropBox(box, 'n', 0, -0.3), {
      x: 0.1,
      y: 0,
      w: 0.5,
      h: 0.6,
    });
  });

  it('enforces the minimum size while resizing from the northwest corner', () => {
    const box: CropBox = { x: 0.3, y: 0.3, w: 0.25, h: 0.25 };

    expectBox(resizeCropBox(box, 'nw', 0.5, 0.5), {
      x: 0.53,
      y: 0.53,
      w: MIN_CROP_BOX_SIZE,
      h: MIN_CROP_BOX_SIZE,
    });
  });

  it('clamps moved crop boxes to the image bounds', () => {
    const box: CropBox = { x: 0.4, y: 0.5, w: 0.35, h: 0.3 };

    expectBox(moveCropBox(box, 0.5, -0.8), {
      x: 0.65,
      y: 0,
      w: 0.35,
      h: 0.3,
    });
  });

  it('shrinks the move capture padding for very small boxes', () => {
    expect(getMoveCapturePadding({ x: 0, y: 0, w: 0.02, h: 0.02 })).toBe(0.005);
    expect(getMoveCapturePadding({ x: 0, y: 0, w: 0.5, h: 0.5 })).toBe(0.015);
  });
});