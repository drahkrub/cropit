package com.cropit.backend.model;

/**
 * Request body for the render endpoint.
 * All coordinate values are normalised to the range [0, 1]
 * relative to the preview image dimensions.
 */
public class CropBoxRequest {

    /** Normalised left edge of the crop box. */
    private double x;

    /** Normalised top edge of the crop box. */
    private double y;

    /** Normalised width of the crop box. */
    private double w;

    /** Normalised height of the crop box. */
    private double h;

    public double getX() { return x; }
    public void setX(double x) { this.x = x; }

    public double getY() { return y; }
    public void setY(double y) { this.y = y; }

    public double getW() { return w; }
    public void setW(double w) { this.w = w; }

    public double getH() { return h; }
    public void setH(double h) { this.h = h; }

    @Override
    public String toString() {
        return "CropBoxRequest{x=" + x + ", y=" + y + ", w=" + w + ", h=" + h + '}';
    }
}
