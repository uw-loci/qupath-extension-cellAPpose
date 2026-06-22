package qupath.ext.cellappose.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import qupath.ext.cellappose.core.TileMerger.Candidate;
import qupath.lib.roi.ROIs;
import qupath.lib.roi.interfaces.ROI;

/**
 * Unit tests for {@link TileMerger}. Pure JUnit5 -- no running QuPath.
 *
 * <p>Candidates carry a tile-of-origin id; suppression runs only ACROSS tiles.
 */
class TileMergerTest {

    private static Candidate c(ROI roi, int tileId) {
        return new Candidate(roi, tileId);
    }

    @Test
    void keepsDisjointObjects() {
        // Two non-overlapping squares from different tiles: both kept.
        ROI a = ROIs.createRectangleROI(0, 0, 10, 10);
        ROI b = ROIs.createRectangleROI(100, 100, 10, 10);
        List<ROI> kept = new TileMerger().merge(List.of(c(a, 0), c(b, 1)));
        assertThat(kept).hasSize(2);
    }

    @Test
    void dropsClippedFragmentMostlyInsideLargerCrossTileNeighbour() {
        // A large full object (tile 0) and a small clipped fragment (tile 1) that sits
        // entirely within it -- a cross-tile duplicate. The fragment is dropped.
        ROI full = ROIs.createRectangleROI(0, 0, 100, 100);
        ROI fragment = ROIs.createRectangleROI(10, 10, 20, 20);
        List<ROI> kept = new TileMerger().merge(List.of(c(fragment, 1), c(full, 0)));
        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).getArea()).isEqualTo(full.getArea());
    }

    @Test
    void keepsPartiallyOverlappingObjectsBelowThreshold() {
        // Two equal squares from different tiles overlapping by < 50% of each: both kept.
        ROI a = ROIs.createRectangleROI(0, 0, 100, 100); // area 10000
        ROI b = ROIs.createRectangleROI(90, 0, 100, 100); // overlap = 10*100 = 1000 (10%)
        List<ROI> kept = new TileMerger().merge(List.of(c(a, 0), c(b, 1)));
        assertThat(kept).hasSize(2);
    }

    @Test
    void cellBisectedAcrossSeamEndsAsOneObject() {
        // A cell the seam bisects ~50/50. Tile 0 saw the full cell (the overlap band gives
        // it the complete ~100x100); tile 1 saw a clipped half (~50x100) that lies entirely
        // within the full instance. The half loses 100% of its area to the larger
        // cross-tile neighbour and must be dropped -> ONE object, not two (scientist M1).
        ROI full = ROIs.createRectangleROI(0, 0, 100, 100);
        ROI clippedHalf = ROIs.createRectangleROI(50, 0, 50, 100); // right half, inside full
        List<ROI> kept = new TileMerger().merge(List.of(c(full, 0), c(clippedHalf, 1)));
        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).getArea()).isEqualTo(full.getArea());
    }

    @Test
    void twoDistinctTouchingCellsWithinOneTileBothSurvive() {
        // Two distinct cells segmented within the SAME tile whose envelopes overlap. Same-tile
        // candidates must NEVER be merged against each other -- both survive even though one
        // sits inside the other's envelope and would be >50%-dropped cross-tile (clinical M2).
        ROI big = ROIs.createRectangleROI(0, 0, 100, 100);
        ROI smallInsideEnvelope = ROIs.createRectangleROI(10, 10, 30, 30);
        List<ROI> kept = new TileMerger().merge(List.of(c(big, 0), c(smallInsideEnvelope, 0)));
        assertThat(kept).hasSize(2);
    }

    @Test
    void crossTileNearDuplicateOverThresholdIsDropped() {
        // Two near-identical squares from DIFFERENT tiles: the smaller overlaps the larger
        // by > 50% of its own area -> dropped, leaving the larger.
        ROI big = ROIs.createRectangleROI(0, 0, 100, 100);
        ROI nearDup = ROIs.createRectangleROI(5, 5, 90, 90); // fully inside big
        List<ROI> kept = new TileMerger().merge(List.of(c(nearDup, 1), c(big, 0)));
        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).getArea()).isEqualTo(big.getArea());
    }

    @Test
    void emptyInputYieldsEmptyOutput() {
        assertThat(new TileMerger().merge(List.of())).isEmpty();
    }
}
