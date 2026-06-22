package qupath.ext.cellappose.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.index.quadtree.Quadtree;
import qupath.lib.roi.interfaces.ROI;

/**
 * Resolves duplicate / fragmented objects produced where overlapping tiles meet.
 *
 * <p>Cellpose runs per tile, so an object straddling a tile seam can appear twice:
 * once as a (larger, more complete) instance in the tile whose interior contains
 * most of it, and once as a clipped fragment in the neighbouring tile. This merger
 * keeps the larger instance and drops a candidate that loses more than
 * {@link #DEFAULT_OVERLAP_FRACTION} of its own area to an already-kept, larger
 * neighbour.
 *
 * <p><b>Tile-of-origin gating (clinical undercount fix).</b> Suppression runs ONLY
 * between candidates from <i>different</i> tiles. Two objects produced within the
 * same tile are cellpose's own segmentation -- distinct touching cells -- and must
 * never be merged against each other, even if their envelopes overlap. Only
 * cross-tile duplicates in the overlap band are de-duplicated. Each candidate
 * therefore carries a tile-of-origin id ({@link Candidate}).
 *
 * <p>Algorithm (BIOP-inspired): index kept candidates' envelopes in a JTS
 * {@link Quadtree} (which, unlike a packed STRtree, supports interleaved insert and
 * query); process candidates from largest area to smallest; for each, query the tree
 * for spatially-overlapping already-kept candidates from a <i>different</i> tile and
 * drop the current one if its overlap fraction against any larger cross-tile kept
 * neighbour exceeds the threshold.
 *
 * <p>Pure and unit-testable: it operates on {@link Candidate} records only and touches
 * no QuPath GUI or hierarchy state.
 */
public final class TileMerger {

    /**
     * Drop a candidate when more than this fraction of its area is shared with an
     * already-kept larger cross-tile neighbour (i.e. it is mostly a duplicate /
     * clipped fragment of an object the neighbouring tile saw more completely).
     */
    public static final double DEFAULT_OVERLAP_FRACTION = 0.5;

    private final double overlapFraction;

    /**
     * A candidate object plus the id of the tile it was segmented in. Candidates with
     * the same {@code tileId} are never suppressed against one another.
     */
    public static final class Candidate {
        private final ROI roi;
        private final int tileId;

        public Candidate(ROI roi, int tileId) {
            this.roi = roi;
            this.tileId = tileId;
        }

        public ROI roi() {
            return roi;
        }

        public int tileId() {
            return tileId;
        }
    }

    /** Creates a merger with the default 0.5 overlap-fraction threshold. */
    public TileMerger() {
        this(DEFAULT_OVERLAP_FRACTION);
    }

    /**
     * @param overlapFraction drop a candidate when its shared-area fraction with a
     *                        larger cross-tile kept neighbour exceeds this value (0..1)
     */
    public TileMerger(double overlapFraction) {
        this.overlapFraction = overlapFraction;
    }

    /**
     * Returns the candidates to keep after cross-tile seam-fragment resolution.
     * Input order is irrelevant; output preserves descending-area processing order.
     * Same-tile candidates are never merged against each other.
     *
     * @param candidates candidate objects (full-image coordinates) with tile-of-origin ids
     * @return the ROIs to keep (cross-tile duplicates / clipped fragments removed)
     */
    public List<ROI> merge(List<Candidate> candidates) {
        List<Candidate> sorted = new ArrayList<>(candidates);
        // Largest first: a larger instance always wins over a smaller fragment.
        sorted.sort(
                Comparator.comparingDouble((Candidate c) -> c.roi().getArea()).reversed());

        Quadtree tree = new Quadtree();
        List<ROI> kept = new ArrayList<>();

        for (Candidate candidate : sorted) {
            Geometry geom = candidate.roi().getGeometry();
            if (geom == null || geom.isEmpty()) {
                continue;
            }
            double candidateArea = geom.getArea();
            boolean drop = false;

            @SuppressWarnings("unchecked")
            List<KeptGeom> neighbours = tree.query(geom.getEnvelopeInternal());
            for (KeptGeom kg : neighbours) {
                // Tile-of-origin gating: never suppress two objects from the same tile.
                // They are cellpose's own (distinct) segmentation in that tile.
                if (kg.tileId == candidate.tileId()) {
                    continue;
                }
                if (!geom.getEnvelopeInternal().intersects(kg.geom.getEnvelopeInternal())) {
                    continue;
                }
                double shared = intersectionArea(geom, kg.geom);
                if (shared <= 0) {
                    continue;
                }
                // A kept neighbour is always >= this candidate in area (descending order).
                // Drop the current candidate if most of IT lies inside that neighbour.
                if (candidateArea > 0 && shared / candidateArea > overlapFraction) {
                    drop = true;
                    break;
                }
            }

            if (!drop) {
                tree.insert(geom.getEnvelopeInternal(), new KeptGeom(geom, candidate.tileId()));
                kept.add(candidate.roi());
            }
        }
        return kept;
    }

    /** A kept geometry paired with its tile-of-origin id, stored in the Quadtree. */
    private static final class KeptGeom {
        private final Geometry geom;
        private final int tileId;

        KeptGeom(Geometry geom, int tileId) {
            this.geom = geom;
            this.tileId = tileId;
        }
    }

    private static double intersectionArea(Geometry a, Geometry b) {
        try {
            if (!a.intersects(b)) {
                return 0;
            }
            Geometry inter = a.intersection(b);
            return inter == null ? 0 : inter.getArea();
        } catch (RuntimeException e) {
            // Topology exceptions on degenerate geometries: treat as no overlap.
            return 0;
        }
    }
}
