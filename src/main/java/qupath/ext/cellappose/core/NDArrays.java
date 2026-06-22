package qupath.ext.cellappose.core;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import org.apposed.appose.NDArray;

/**
 * BufferedImage to/from Appose {@link NDArray} conversion helpers.
 *
 * <p>The input-tile helpers ({@code bufferedImageToRGBNDArray},
 * {@code bufferedImageToGrayNDArray}, {@code bufferedImageToGray16NDArray}) are
 * copied from PPM's {@code PPMPerpendicularityWorkflow} (lines 1826-1882) -- they
 * encode the current Appose Java NDArray API (Shape with C_ORDER, DType
 * UINT8/UINT16/FLOAT32, fill via {@code buffer()}). The
 * {@code allocateLabelNDArray} + {@code readLabelsAsFloat} pair is original: it
 * allocates the pre-allocated {@code output_labels} array the vendored Cellpose
 * scripts write into, and reads the label raster back after the task completes.
 *
 * <p>All NDArrays must be {@link NDArray#close()}d by the caller (shared memory
 * leaks otherwise). These helpers do not close anything they create.
 */
public final class NDArrays {

    private NDArrays() {}

    /**
     * Converts an RGB BufferedImage to an Appose NDArray (H, W, 3) uint8.
     */
    public static NDArray bufferedImageToRGBNDArray(BufferedImage img) {
        int h = img.getHeight();
        int w = img.getWidth();
        NDArray.Shape shape = new NDArray.Shape(NDArray.Shape.Order.C_ORDER, h, w, 3);
        NDArray ndArray = new NDArray(NDArray.DType.UINT8, shape);
        ByteBuffer buf = ndArray.buffer();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pixel = img.getRGB(x, y);
                buf.put((byte) ((pixel >> 16) & 0xFF));
                buf.put((byte) ((pixel >> 8) & 0xFF));
                buf.put((byte) (pixel & 0xFF));
            }
        }
        buf.flip();
        return ndArray;
    }

    /**
     * Converts a grayscale BufferedImage to an Appose NDArray (H, W) uint8.
     * Suitable for binary masks and 8-bit grayscale images.
     */
    public static NDArray bufferedImageToGrayNDArray(BufferedImage img) {
        int h = img.getHeight();
        int w = img.getWidth();
        NDArray.Shape shape = new NDArray.Shape(NDArray.Shape.Order.C_ORDER, h, w);
        NDArray ndArray = new NDArray(NDArray.DType.UINT8, shape);
        ByteBuffer buf = ndArray.buffer();
        Raster raster = img.getRaster();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                buf.put((byte) raster.getSample(x, y, 0));
            }
        }
        buf.flip();
        return ndArray;
    }

    /**
     * Converts a grayscale BufferedImage to an Appose NDArray (H, W) uint16.
     * Required for 16-bit images where values exceed 255.
     */
    public static NDArray bufferedImageToGray16NDArray(BufferedImage img) {
        int h = img.getHeight();
        int w = img.getWidth();
        NDArray.Shape shape = new NDArray.Shape(NDArray.Shape.Order.C_ORDER, h, w);
        NDArray ndArray = new NDArray(NDArray.DType.UINT16, shape);
        ShortBuffer buf = ndArray.buffer().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        Raster raster = img.getRaster();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                buf.put((short) raster.getSample(x, y, 0));
            }
        }
        return ndArray;
    }

    /**
     * Converts a multichannel BufferedImage to an Appose NDArray (H, W, nBands) float32,
     * preserving EVERY band in channel order (band i == QuPath channel i).
     *
     * <p>Used for fluorescence / multichannel images where the user may select any channel.
     * The RGB helper packs only bands 0-2 via {@code getRGB()}, which silently drops bands
     * 4+ and breaks cellpose's 1-based channel indices (cellpose maps channel value c to band
     * c-1, so picking channel 6 needs 6 bands present). float32 handles any source bit depth
     * (8/16-bit or float); cellpose normalizes internally.
     *
     * @param img a multi-band (nBands &gt;= 2) BufferedImage tile
     * @return a new (H, W, nBands) float32 NDArray; the caller owns it and must close it
     */
    public static NDArray bufferedImageToMultiChannelNDArray(BufferedImage img) {
        int h = img.getHeight();
        int w = img.getWidth();
        Raster raster = img.getRaster();
        int nb = raster.getNumBands();
        NDArray.Shape shape = new NDArray.Shape(NDArray.Shape.Order.C_ORDER, h, w, nb);
        NDArray ndArray = new NDArray(NDArray.DType.FLOAT32, shape);
        FloatBuffer buf = ndArray.buffer().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                for (int b = 0; b < nb; b++) {
                    buf.put(raster.getSampleFloat(x, y, b));
                }
            }
        }
        return ndArray;
    }

    /**
     * Extracts specific bands (0-based, in the given order) from a BufferedImage into a compact
     * float32 NDArray: {@code (H, W, bands.length)} when more than one band, or {@code (H, W)}
     * when exactly one.
     *
     * <p>This is how cellpose actually wants multichannel input: a small 1-2 channel array, NOT
     * the full N-channel image. Handing cellpose all N channels makes it mis-read the channel axis
     * as a Z-stack (it emits one mask per plane -> shape (N,H,W), which cannot broadcast into the
     * (H,W) label buffer). Extracting the user's chosen channel(s) here and passing cellpose
     * {@code channels=[0,0]} (single) or {@code [1,2]} (pair) avoids that entirely.
     *
     * @param img    a multi-band BufferedImage tile
     * @param bands0 0-based source band indices to pull, in cellpose [cyto, nucleus] order
     * @return a new float32 NDArray; the caller owns it and must close it
     */
    public static NDArray bufferedImageToSelectedChannelsNDArray(BufferedImage img, int[] bands0) {
        int h = img.getHeight();
        int w = img.getWidth();
        Raster raster = img.getRaster();
        int k = bands0.length;
        NDArray.Shape shape = k > 1
                ? new NDArray.Shape(NDArray.Shape.Order.C_ORDER, h, w, k)
                : new NDArray.Shape(NDArray.Shape.Order.C_ORDER, h, w);
        NDArray ndArray = new NDArray(NDArray.DType.FLOAT32, shape);
        FloatBuffer buf = ndArray.buffer().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                for (int b = 0; b < k; b++) {
                    buf.put(raster.getSampleFloat(x, y, bands0[b]));
                }
            }
        }
        return ndArray;
    }

    /**
     * Extracts specific bands (0-based, in order) from a BufferedImage into a CHANNELS-FIRST
     * float32 NDArray of shape {@code (k, H, W)} where {@code k == bands0.length} (always 3D, even
     * for a single band).
     *
     * <p>This is the layout the vendored {@code cp4.py} (Cellpose-SAM) expects: its channel
     * selection is {@code input_image[..., channels, :, :]}, which indexes the channel axis at
     * position -3 -- i.e. channels-first. We extract only the user's chosen channels here and pass
     * cellpose compact indices, so cpsam never sees more than 3 channels (avoiding the channel-axis
     * being mis-read as a Z-stack).
     *
     * @param img    a multi-band BufferedImage tile
     * @param bands0 0-based source band indices to pull, in channel order
     * @return a new {@code (k, H, W)} float32 NDArray; the caller owns it and must close it
     */
    public static NDArray bufferedImageToSelectedChannelsCHWNDArray(BufferedImage img, int[] bands0) {
        int h = img.getHeight();
        int w = img.getWidth();
        Raster raster = img.getRaster();
        int k = bands0.length;
        NDArray.Shape shape = new NDArray.Shape(NDArray.Shape.Order.C_ORDER, k, h, w);
        NDArray ndArray = new NDArray(NDArray.DType.FLOAT32, shape);
        FloatBuffer buf = ndArray.buffer().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        // C-order (k, h, w): outermost is band, then row (y), then column (x).
        for (int b = 0; b < k; b++) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    buf.put(raster.getSampleFloat(x, y, bands0[b]));
                }
            }
        }
        return ndArray;
    }

    /**
     * Allocates a zeroed (H, W) uint16 NDArray for the vendored scripts' pre-allocated
     * {@code output_labels} buffer. Cellpose label rasters are non-negative integers;
     * a single tile will not exceed 65535 objects, so uint16 is sufficient.
     *
     * @param h tile height in pixels
     * @param w tile width in pixels
     * @return a new, zeroed NDArray; the caller owns it and must close it
     */
    public static NDArray allocateLabelNDArray(int h, int w) {
        NDArray.Shape shape = new NDArray.Shape(NDArray.Shape.Order.C_ORDER, h, w);
        return new NDArray(NDArray.DType.UINT16, shape);
    }

    /**
     * Reads a (H, W) uint16 label NDArray (the {@code output_labels} buffer after a
     * Cellpose task completes) into a row-major {@code float[]} of length {@code h*w},
     * suitable for {@code SimpleImages.createFloatImage}. Label 0 is background.
     *
     * @param labels the uint16 label NDArray written by the Python task
     * @param h      tile height in pixels
     * @param w      tile width in pixels
     * @return float array of length {@code h*w} in row-major (Y then X) order
     */
    public static float[] readLabelsAsFloat(NDArray labels, int h, int w) {
        ShortBuffer buf = labels.buffer().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        float[] out = new float[h * w];
        int n = Math.min(out.length, buf.remaining());
        for (int i = 0; i < n; i++) {
            // uint16: mask off the sign extension from the signed short.
            out[i] = buf.get(i) & 0xFFFF;
        }
        return out;
    }
}
