export interface Todo {
  id: number;
  content: string;
}

export interface Meta {
  totalCount: number;
}

/** Normalised crop box – all values in the range 0..1 relative to the image container */
export interface CropBox {
  x: number;
  y: number;
  w: number;
  h: number;
}
