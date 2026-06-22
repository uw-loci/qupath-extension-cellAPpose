package qupath.ext.cellappose.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.interfaces.ROI;

/**
 * Unit tests for {@link LabelToObjects}. Pure JUnit5 -- no running QuPath.
 *
 * <p>Builds small synthetic label rasters and checks the right number of ROIs come
 * out, with coordinates lifted into full-image space by the tile RegionRequest.
 */
class LabelToObjectsTest {

    private static RegionRequest tileRegion(int x, int y, int w, int h) {
        // downsample 1.0; offset (x, y) so tile-local coords lift to full-image coords.
        return RegionRequest.createInstance("test", 1.0, x, y, w, h);
    }

    @Test
    void singleLabelYieldsOneROI() {
        // 5x5 raster, a 3x3 block of label 1 in the middle.
        int w = 5;
        int h = 5;
        float[] labels = new float[w * h];
        for (int yy = 1; yy <= 3; yy++) {
            for (int xx = 1; xx <= 3; xx++) {
                labels[yy * w + xx] = 1f;
            }
        }
        List<ROI> rois = LabelToObjects.labelsToROIs(labels, w, h, tileRegion(0, 0, w, h));
        assertThat(rois).hasSize(1);
        assertThat(rois.get(0).getArea()).isGreaterThan(0);
    }

    @Test
    void twoDisjointLabelsYieldTwoROIs() {
        // 6x3 raster: label 1 on the left 2x2, label 2 on the right 2x2.
        int w = 6;
        int h = 3;
        float[] labels = new float[w * h];
        for (int yy = 0; yy <= 1; yy++) {
            labels[yy * w + 0] = 1f;
            labels[yy * w + 1] = 1f;
            labels[yy * w + 4] = 2f;
            labels[yy * w + 5] = 2f;
        }
        List<ROI> rois = LabelToObjects.labelsToROIs(labels, w, h, tileRegion(0, 0, w, h));
        assertThat(rois).hasSize(2);
    }

    @Test
    void tileOffsetLiftsCoordinatesToFullImageSpace() {
        // A 3x3 block of label 1 at tile origin, tile offset by (100, 200).
        int w = 5;
        int h = 5;
        float[] labels = new float[w * h];
        for (int yy = 0; yy <= 2; yy++) {
            for (int xx = 0; xx <= 2; xx++) {
                labels[yy * w + xx] = 1f;
            }
        }
        List<ROI> rois = LabelToObjects.labelsToROIs(labels, w, h, tileRegion(100, 200, w, h));
        assertThat(rois).hasSize(1);
        ROI roi = rois.get(0);
        // The ROI must be shifted into full-image space by the tile offset.
        assertThat(roi.getBoundsX()).isGreaterThanOrEqualTo(100);
        assertThat(roi.getBoundsY()).isGreaterThanOrEqualTo(200);
    }

    @Test
    void emptyRasterYieldsNoROIs() {
        int w = 4;
        int h = 4;
        float[] labels = new float[w * h]; // all background (0)
        List<ROI> rois = LabelToObjects.labelsToROIs(labels, w, h, tileRegion(0, 0, w, h));
        assertThat(rois).isEmpty();
    }
}
