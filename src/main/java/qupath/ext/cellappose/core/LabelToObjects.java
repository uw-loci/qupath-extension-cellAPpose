package qupath.ext.cellappose.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.simplify.VWSimplifier;
import qupath.lib.analysis.images.ContourTracing;
import qupath.lib.analysis.images.SimpleImage;
import qupath.lib.analysis.images.SimpleImages;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.GeometryTools;
import qupath.lib.roi.interfaces.ROI;

/**
 * Converts a Cellpose label raster (one integer label per object, 0 = background)
 * into QuPath geometries in full-image coordinates.
 *
 * <p>Pipeline: {@code float[] labels} -> {@link SimpleImages#createFloatImage} ->
 * {@link ContourTracing#createGeometries} (the tile's {@link RegionRequest} lifts
 * tile-local coordinates into full-image space and applies the downsample) ->
 * {@link VWSimplifier} (visvalingam-whyatt vertex reduction) ->
 * {@link GeometryTools#geometryToROI}. The result is a list of candidate ROIs
 * already in full-image coordinates, ready for tile-seam merging.
 */
public final class LabelToObjects {

    /** Default simplification tolerance, in geometry units (pixels at downsample). */
    private static final double SIMPLIFY_TOLERANCE = 1.4;

    private LabelToObjects() {}

    /**
     * Converts a label raster to candidate ROIs in full-image coordinates.
     *
     * @param labels    row-major (Y then X) label values, length {@code w*h}
     * @param w         tile width in pixels (at processing downsample)
     * @param h         tile height in pixels (at processing downsample)
     * @param tileRegion the tile's RegionRequest (carries offset + downsample for
     *                   the tile-local -> full-image coordinate lift)
     * @return candidate ROIs, one per non-background label, in full-image coords
     */
    public static List<ROI> labelsToROIs(float[] labels, int w, int h, RegionRequest tileRegion) {
        SimpleImage image = SimpleImages.createFloatImage(labels, w, h);
        // minLabel=1 skips background; maxLabel<minLabel auto-discovers the max.
        Map<Number, Geometry> geometries = ContourTracing.createGeometries(image, tileRegion, 1, -1);

        ImagePlane plane = tileRegion.getImagePlane();
        List<ROI> rois = new ArrayList<>(geometries.size());
        for (Geometry geom : geometries.values()) {
            if (geom == null || geom.isEmpty()) {
                continue;
            }
            Geometry simplified = VWSimplifier.simplify(geom, SIMPLIFY_TOLERANCE);
            if (simplified == null || simplified.isEmpty()) {
                simplified = geom;
            }
            ROI roi = GeometryTools.geometryToROI(simplified, plane);
            if (roi != null && !roi.isEmpty()) {
                rois.add(roi);
            }
        }
        return rois;
    }
}
