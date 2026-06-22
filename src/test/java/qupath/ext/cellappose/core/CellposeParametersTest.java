package qupath.ext.cellappose.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import qupath.ext.cellappose.core.CellposeParameters.OutputType;

/**
 * Unit tests for the seam-overlap floor and provenance fields in
 * {@link CellposeParameters}. Pure JUnit5 -- no running QuPath.
 */
class CellposeParametersTest {

    private static CellposeParameters params(double diameter, int tileSize, int tileOverlap) {
        return Cellpose2D.builder(CellposeModelFamily.CP4)
                .diameter(diameter)
                .tileSize(tileSize)
                .tileOverlap(tileOverlap)
                .createDetections()
                .build()
                .parameters();
    }

    @Test
    void explicitDiameterFloorsAtTwiceDiameter() {
        CellposeParameters p = params(50, 1024, 10);
        // overlap floor = 2 * 50 = 100; configured 10 < floor -> effective 100.
        assertThat(p.overlapFloor()).isEqualTo(100);
        assertThat(p.effectiveTileOverlap()).isEqualTo(100);
    }

    @Test
    void autoDiameterAppliesAbsoluteFloorNotZero() {
        // diameter 0 (auto). The floor must NOT be left at the small configured overlap
        // (this was the scientist M1 double-count regime). Expect max(2*30, 60) = 60.
        CellposeParameters p = params(0, 1024, 4);
        assertThat(p.overlapFloor()).isEqualTo(60);
        assertThat(p.effectiveTileOverlap()).isEqualTo(60);
    }

    @Test
    void configuredOverlapAboveFloorIsRespected() {
        CellposeParameters p = params(30, 1024, 200);
        // floor = 60; configured 200 > floor -> effective 200.
        assertThat(p.effectiveTileOverlap()).isEqualTo(200);
    }

    @Test
    void overlapIsCappedBelowHalfTileSize() {
        // A pathological huge diameter must not produce an overlap >= tileSize/2.
        CellposeParameters p = params(2000, 256, 8);
        assertThat(p.effectiveTileOverlap()).isLessThan(256 / 2);
    }

    @Test
    void provenanceFieldsAndHashAreStableAndDiscriminating() {
        CellposeParameters a = params(30, 1024, 60);
        CellposeParameters b = params(30, 1024, 60);
        CellposeParameters c = params(45, 1024, 60); // different diameter
        assertThat(a.paramsHash()).isEqualTo(b.paramsHash());
        assertThat(a.paramsHash()).isNotEqualTo(c.paramsHash());
        assertThat(a.provenanceFields()).containsKey("cellappose:model_family");
        assertThat(a.provenanceFields().get("cellappose:tile_overlap")).isEqualTo("60");
    }

    @Test
    void builderScriptReproducesConfiguration() {
        CellposeParameters p = Cellpose2D.builder(CellposeModelFamily.CP3)
                .modelName("cyto3")
                .channels(1, 0)
                .diameter(30)
                .createCells()
                .cellExpansion(5.0)
                .build()
                .parameters();
        String script = p.toBuilderScript();
        assertThat(script).contains("Cellpose2D.builder(CellposeModelFamily.CP3)");
        assertThat(script).contains(".modelName(\"cyto3\")");
        assertThat(script).contains(".createCells()");
        assertThat(script).contains(".detectObjects(");
    }

    @Test
    void outputTypeRoundTrips() {
        CellposeParameters p = Cellpose2D.builder(CellposeModelFamily.CP4)
                .createAnnotations()
                .build()
                .parameters();
        assertThat(p.outputType()).isEqualTo(OutputType.ANNOTATIONS);
    }
}
