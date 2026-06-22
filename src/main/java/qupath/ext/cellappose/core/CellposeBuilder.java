package qupath.ext.cellappose.core;

import qupath.ext.cellappose.core.CellposeParameters.OutputType;

/**
 * Fluent builder for {@link Cellpose2D} -- the scripting entry point.
 *
 * <p>Mirrors BIOP's builder usage so a dialog run can be reproduced and batched from
 * the QuPath Groovy editor:
 *
 * <pre>{@code
 * import qupath.ext.cellappose.core.Cellpose2D
 * import qupath.ext.cellappose.core.CellposeModelFamily
 *
 * def cellpose = Cellpose2D.builder(CellposeModelFamily.CP3)
 *         .modelName("cyto3")
 *         .channels(1, 0)          // cell channel, nuclei channel (CP3)
 *         .diameter(30)
 *         .cellprobThreshold(0.0)
 *         .flowThreshold(0.4)
 *         .tileSize(1024)
 *         .tileOverlap(60)
 *         .pixelSize(0.5)
 *         .cellExpansion(0.0)
 *         .createDetections()
 *         .build()
 *
 * cellpose.detectObjects(getCurrentImageData(), getSelectedObjects())
 * fireHierarchyUpdate()
 * }</pre>
 */
public final class CellposeBuilder {

    private final CellposeModelFamily family;

    private String modelName = "cyto3";
    private String customModelPath = null;

    private Integer cellChannel = 1;
    private Integer nucleiChannel = null;

    private int nChannels = 1;
    private Integer chan0 = 0;
    private Integer chan1 = null;
    private Integer chan2 = null;

    private double diameter = 30.0;
    private double cellprobThreshold = 0.0;
    private double flowThreshold = 0.4;
    private int minSize = 15;
    private boolean normalize = true;

    private int tileSize = 1024;
    private int tileOverlap = 60;
    private double pixelSize = 0.5;

    private OutputType outputType = OutputType.DETECTIONS;
    private double cellExpansion = 0.0;
    private boolean constrainToParent = true;

    private boolean useGpu = true;

    CellposeBuilder(CellposeModelFamily family) {
        this.family = family;
    }

    /** Built-in model name (CP3), e.g. cyto3 / cyto2 / nuclei. */
    public CellposeBuilder modelName(String modelName) {
        this.modelName = modelName;
        this.customModelPath = null;
        return this;
    }

    /** Path to a custom trained Cellpose model file. */
    public CellposeBuilder customModel(String path) {
        this.customModelPath = path;
        return this;
    }

    /**
     * CP3 channel pairing: cell (cytoplasm) channel and nuclei channel.
     * Use 0 / null for "none" on a channel.
     */
    public CellposeBuilder channels(Integer cellChannel, Integer nucleiChannel) {
        this.cellChannel = cellChannel;
        this.nucleiChannel = nucleiChannel;
        return this;
    }

    /** CP4 channel selection: number of channels and up to three channel indices. */
    public CellposeBuilder channelsCP4(int nChannels, Integer chan0, Integer chan1, Integer chan2) {
        this.nChannels = nChannels;
        this.chan0 = chan0;
        this.chan1 = chan1;
        this.chan2 = chan2;
        return this;
    }

    public CellposeBuilder diameter(double diameter) {
        this.diameter = diameter;
        return this;
    }

    public CellposeBuilder cellprobThreshold(double cellprobThreshold) {
        this.cellprobThreshold = cellprobThreshold;
        return this;
    }

    public CellposeBuilder flowThreshold(double flowThreshold) {
        this.flowThreshold = flowThreshold;
        return this;
    }

    public CellposeBuilder minSize(int minSize) {
        this.minSize = minSize;
        return this;
    }

    public CellposeBuilder normalize(boolean normalize) {
        this.normalize = normalize;
        return this;
    }

    public CellposeBuilder tileSize(int tileSize) {
        this.tileSize = tileSize;
        return this;
    }

    public CellposeBuilder tileOverlap(int tileOverlap) {
        this.tileOverlap = tileOverlap;
        return this;
    }

    /** Alias for {@link #tileOverlap(int)} matching the design's builder name. */
    public CellposeBuilder setOverlap(int overlap) {
        return tileOverlap(overlap);
    }

    /** Processing resolution in micrometres per pixel. */
    public CellposeBuilder pixelSize(double pixelSize) {
        this.pixelSize = pixelSize;
        return this;
    }

    /** Cell-boundary expansion in micrometres (0 = no expansion). */
    public CellposeBuilder cellExpansion(double cellExpansion) {
        this.cellExpansion = cellExpansion;
        return this;
    }

    public CellposeBuilder constrainToParent(boolean constrainToParent) {
        this.constrainToParent = constrainToParent;
        return this;
    }

    public CellposeBuilder useGpu(boolean useGpu) {
        this.useGpu = useGpu;
        return this;
    }

    /** Emit detection objects (default). */
    public CellposeBuilder createDetections() {
        this.outputType = OutputType.DETECTIONS;
        return this;
    }

    /** Emit cell objects (nucleus plus expanded boundary). */
    public CellposeBuilder createCells() {
        this.outputType = OutputType.CELLS;
        return this;
    }

    /** Emit annotation objects (editable by hand afterward). */
    public CellposeBuilder createAnnotations() {
        this.outputType = OutputType.ANNOTATIONS;
        return this;
    }

    /** Builds the configured {@link Cellpose2D} detector. */
    public Cellpose2D build() {
        CellposeParameters params = new CellposeParameters(
                family,
                modelName,
                customModelPath,
                cellChannel,
                nucleiChannel,
                nChannels,
                chan0,
                chan1,
                chan2,
                diameter,
                cellprobThreshold,
                flowThreshold,
                minSize,
                normalize,
                tileSize,
                tileOverlap,
                pixelSize,
                outputType,
                cellExpansion,
                constrainToParent,
                useGpu);
        return new Cellpose2D(params);
    }
}
