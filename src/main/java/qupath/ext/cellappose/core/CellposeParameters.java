package qupath.ext.cellappose.core;

import java.util.LinkedHashMap;
import java.util.Map;
import org.apposed.appose.NDArray;

/**
 * Immutable holder for a single Cellpose detection configuration, plus the
 * builder of the bare-globals input map the vendored Python scripts expect.
 *
 * <p>The vendored {@code cp3.py}/{@code cp4.py} read every input as a bare global
 * (Appose injects task inputs into script scope). {@link #toApposeInputs} supplies
 * the FULL key set -- shared keys plus the family-specific ones -- so no key is
 * missing. See {@code docs/APPOSE_CELLPOSE_PORTING.md} for the contract.
 *
 * <p>Construct via {@link CellposeBuilder}.
 */
public final class CellposeParameters {

    /** Output object type. */
    public enum OutputType {
        DETECTIONS,
        CELLS,
        ANNOTATIONS
    }

    private final CellposeModelFamily family;

    // Model selection
    private final String modelName; // built-in model name (CP3); may be null
    private final String customModelPath; // custom model file path; may be null

    // CP3 channel pairing (1-based image channel index, or 0/none)
    private final Integer cellChannel;
    private final Integer nucleiChannel;

    // CP4 channel selection
    private final int nChannels;
    private final Integer chan0;
    private final Integer chan1;
    private final Integer chan2;

    // Detection parameters
    private final double diameter;
    private final double cellprobThreshold;
    private final double flowThreshold;
    private final int minSize;
    private final boolean normalize;

    // Tiling / resolution
    private final int tileSize;
    private final int tileOverlap; // pixels
    private final double pixelSize; // um/px processing resolution

    // Output
    private final OutputType outputType;
    private final double cellExpansion; // um
    private final boolean constrainToParent;

    // Compute
    private final boolean useGpu;

    CellposeParameters(
            CellposeModelFamily family,
            String modelName,
            String customModelPath,
            Integer cellChannel,
            Integer nucleiChannel,
            int nChannels,
            Integer chan0,
            Integer chan1,
            Integer chan2,
            double diameter,
            double cellprobThreshold,
            double flowThreshold,
            int minSize,
            boolean normalize,
            int tileSize,
            int tileOverlap,
            double pixelSize,
            OutputType outputType,
            double cellExpansion,
            boolean constrainToParent,
            boolean useGpu) {
        this.family = family;
        this.modelName = modelName;
        this.customModelPath = customModelPath;
        this.cellChannel = cellChannel;
        this.nucleiChannel = nucleiChannel;
        this.nChannels = nChannels;
        this.chan0 = chan0;
        this.chan1 = chan1;
        this.chan2 = chan2;
        this.diameter = diameter;
        this.cellprobThreshold = cellprobThreshold;
        this.flowThreshold = flowThreshold;
        this.minSize = minSize;
        this.normalize = normalize;
        this.tileSize = tileSize;
        this.tileOverlap = tileOverlap;
        this.pixelSize = pixelSize;
        this.outputType = outputType;
        this.cellExpansion = cellExpansion;
        this.constrainToParent = constrainToParent;
        this.useGpu = useGpu;
    }

    public CellposeModelFamily family() {
        return family;
    }

    public String modelName() {
        return modelName;
    }

    /**
     * The effective model actually used, for provenance/display. A custom model path
     * wins; otherwise CP4 (Cellpose-SAM) always uses {@code cpsam} (it ignores the CP3
     * {@code modelName}), and CP3 uses the chosen built-in (default {@code cyto3}).
     */
    public String effectiveModelName() {
        if (customModelPath != null && !customModelPath.isEmpty()) {
            return customModelPath;
        }
        if (family == CellposeModelFamily.CP4) {
            return "cpsam";
        }
        return modelName == null ? "cyto3" : modelName;
    }

    public String customModelPath() {
        return customModelPath;
    }

    public double diameter() {
        return diameter;
    }

    public int tileSize() {
        return tileSize;
    }

    public int tileOverlap() {
        return tileOverlap;
    }

    public double pixelSize() {
        return pixelSize;
    }

    public OutputType outputType() {
        return outputType;
    }

    public double cellExpansion() {
        return cellExpansion;
    }

    public boolean constrainToParent() {
        return constrainToParent;
    }

    public boolean useGpu() {
        return useGpu;
    }

    public double cellprobThreshold() {
        return cellprobThreshold;
    }

    public double flowThreshold() {
        return flowThreshold;
    }

    public int minSize() {
        return minSize;
    }

    public boolean normalize() {
        return normalize;
    }

    public Integer cellChannel() {
        return cellChannel;
    }

    public Integer nucleiChannel() {
        return nucleiChannel;
    }

    /**
     * The model-caching key. The init script reloads (and re-exports) the cellpose
     * model whenever model selection, diameter, or GPU usage change vs. the cached
     * configuration.
     */
    public String modelCacheKey() {
        return family.name() + "|" + (modelName == null ? "" : modelName) + "|"
                + (customModelPath == null ? "" : customModelPath) + "|gpu=" + useGpu;
    }

    /**
     * Builds the init-script inputs (model selection + GPU choice). These are the
     * bare globals {@code cp3_init.py}/{@code cp4_init.py} read to load the model.
     */
    public Map<String, Object> toInitInputs() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("use_gpu", useGpu);
        m.put("custom_model", customModelPath); // null => built-in
        if (family == CellposeModelFamily.CP3) {
            m.put("model_name", modelName == null ? "cyto3" : modelName);
        }
        return m;
    }

    /**
     * Builds the FULL per-tile bare-globals input map for the vendored Cellpose
     * task script. Supplies the shared keys plus the family-specific keys. The
     * caller passes the input tile NDArray and the pre-allocated label NDArray.
     *
     * @param tile           the input image tile NDArray (YX gray or YXC)
     * @param labels         the pre-allocated {@code output_labels} NDArray (YX uint16)
     * @param hasChannelAxis whether the tile carries a channel axis (multichannel)
     * @return a map of every global the task script reads
     */
    /**
     * For a CP3 run on a multichannel (non-RGB) image, the 0-based source bands to extract into a
     * compact cellpose tile, in [cyto, nucleus] order: the cell channel first (if set), then the
     * nuclei channel (if set). Empty if neither is set (caller falls back to band 0 grayscale).
     *
     * <p>{@code cellChannel}/{@code nucleiChannel} are 1-based combo indices (1 == the first image
     * channel), matching cellpose's 1-based channel convention, so the source band is value - 1.
     * Extracting just these channels avoids handing cellpose all N channels (which it mis-reads as
     * a Z-stack, emitting one mask per plane).
     */
    public int[] cp3SourceBands() {
        if (family != CellposeModelFamily.CP3) {
            return new int[0];
        }
        int n = (cellChannel != null ? 1 : 0) + (nucleiChannel != null ? 1 : 0);
        int[] bands = new int[n];
        int i = 0;
        if (cellChannel != null) {
            bands[i++] = cellChannel - 1;
        }
        if (nucleiChannel != null) {
            bands[i++] = nucleiChannel - 1;
        }
        return bands;
    }

    /**
     * For a CP4 (Cellpose-SAM) run on a multichannel image, the 0-based source bands to extract
     * (the non-null entries of {@code chan0/chan1/chan2}, in order). cpsam takes up to 3 channels;
     * the dialog supplies the user's chosen source-channel indices. Empty if none set (caller falls
     * back to band 0).
     */
    public int[] cp4SourceBands() {
        if (family != CellposeModelFamily.CP4) {
            return new int[0];
        }
        Integer[] raw = {chan0, chan1, chan2};
        int n = 0;
        for (Integer c : raw) {
            if (c != null) {
                n++;
            }
        }
        int[] bands = new int[n];
        int i = 0;
        for (Integer c : raw) {
            if (c != null) {
                bands[i++] = c;
            }
        }
        return bands;
    }

    public Map<String, Object> toApposeInputs(NDArray tile, NDArray labels, boolean hasChannelAxis) {
        Map<String, Object> m = new LinkedHashMap<>();

        // --- shared keys (read by both cp3.py and cp4.py) ---
        m.put("input", tile);
        m.put("output_labels", labels);
        m.put("output_flows", null);
        m.put("compute_flows", false);
        m.put("z_axis", null);
        m.put("t_axis", null);
        // channel_axis: numpy C-order tile is (H, W) gray or (H, W, C); when a
        // channel axis is present it is the last axis (index 2).
        m.put("channel_axis", hasChannelAxis ? Integer.valueOf(2) : null);
        m.put("anisotropy", 1.0);
        m.put("diameter", diameter);
        m.put("stitch_threshold", 0.0);
        m.put("resample", true);
        m.put("normalize", normalize);
        m.put("flow_threshold", flowThreshold);
        m.put("cellprob_threshold", cellprobThreshold);
        m.put("min_size", minSize);
        m.put("tile_overlap", 0.1); // cellpose internal sub-tiling fraction
        m.put("flow3D_smooth", 0);
        m.put("niter", null);
        m.put("use_gpu", useGpu);
        m.put("use_3D", false);
        m.put("model_name", modelName == null ? "cyto3" : modelName);
        m.put("custom_model", customModelPath);

        // --- family-specific keys ---
        if (family == CellposeModelFamily.CP3) {
            m.put("cell_channel", cellChannel);
            m.put("nuclei_channel", nucleiChannel);
        } else {
            m.put("n_channels", nChannels);
            m.put("chan0", chan0);
            m.put("chan1", chan1);
            m.put("chan2", chan2);
        }
        return m;
    }

    /**
     * Cellpose's default object diameter in pixels, used as the auto-diameter
     * seam-overlap floor reference. When {@code diameter <= 0} (auto-estimate),
     * the seam overlap cannot be derived from the user's diameter, so this default
     * stands in for "a typical object" when computing the {@link #overlapFloor}.
     */
    public static final int CELLPOSE_DEFAULT_DIAMETER = 30;

    /**
     * Absolute minimum seam overlap in pixels for the auto/zero-diameter case, so a
     * scripted CP4-SAM run never falls below a sane seam band.
     */
    public static final int MIN_AUTO_OVERLAP = 60;

    /**
     * The seam-overlap floor in pixels. Seam de-duplication only works when the tile
     * overlap band is at least as wide as the largest object that straddles a seam.
     * For an explicit diameter we want overlap {@code >= 2 * diameter}; for the
     * auto/zero-diameter case (CP4-SAM) we cannot read the user's diameter, so we
     * floor at {@code max(2 * CELLPOSE_DEFAULT_DIAMETER, MIN_AUTO_OVERLAP)}.
     *
     * @return the minimum overlap (px) this configuration should use
     */
    public int overlapFloor() {
        if (diameter > 0) {
            return (int) Math.round(2 * diameter);
        }
        return Math.max(2 * CELLPOSE_DEFAULT_DIAMETER, MIN_AUTO_OVERLAP);
    }

    /**
     * The effective tile overlap to use for tiling: the configured overlap, raised to
     * the {@link #overlapFloor} if the user set it lower (clamped below tileSize/2 so a
     * tile always has interior). This is applied in the WSI pipeline so both dialog and
     * scripted runs get the seam-double-count safety floor.
     *
     * @return the overlap (px) to pass to {@code RoiTools.computeTiledROIs}
     */
    public int effectiveTileOverlap() {
        int floor = overlapFloor();
        int overlap = Math.max(tileOverlap, floor);
        int cap = Math.max(2, tileSize / 2 - 1);
        return Math.min(overlap, cap);
    }

    /**
     * Builds a deterministic, ordered map of provenance fields stamped on every created
     * object (as object metadata) plus used to compute {@link #paramsHash}.
     */
    public Map<String, String> provenanceFields() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("cellappose:model_family", family.name());
        m.put("cellappose:model_name", effectiveModelName());
        m.put("cellappose:diameter", String.valueOf(diameter));
        m.put("cellappose:cellprob_threshold", String.valueOf(cellprobThreshold));
        m.put("cellappose:flow_threshold", String.valueOf(flowThreshold));
        m.put("cellappose:normalize", String.valueOf(normalize));
        m.put("cellappose:min_size", String.valueOf(minSize));
        m.put("cellappose:tile_size", String.valueOf(tileSize));
        m.put("cellappose:tile_overlap", String.valueOf(effectiveTileOverlap()));
        m.put("cellappose:pixel_size_um", String.valueOf(pixelSize));
        m.put("cellappose:cell_expansion_um", String.valueOf(cellExpansion));
        m.put("cellappose:output_type", outputType.name());
        m.put("cellappose:use_gpu", String.valueOf(useGpu));
        if (family == CellposeModelFamily.CP3) {
            m.put("cellappose:cell_channel", String.valueOf(cellChannel));
            m.put("cellappose:nuclei_channel", String.valueOf(nucleiChannel));
        }
        return m;
    }

    /**
     * A short, stable hash over the provenance fields, so two runs with different
     * settings are distinguishable in the hierarchy. ASCII hex (8 chars).
     */
    public String paramsHash() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : provenanceFields().entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
        }
        int h = sb.toString().hashCode();
        return String.format("%08x", h);
    }

    /**
     * Reconstructs an equivalent {@code Cellpose2D.builder(...)} Groovy script for the
     * QuPath History workflow, so a dialog run is replayable. ASCII-only.
     *
     * @return a runnable Groovy snippet that reproduces this configuration
     */
    public String toBuilderScript() {
        StringBuilder sb = new StringBuilder();
        sb.append("import qupath.ext.cellappose.core.Cellpose2D\n");
        sb.append("import qupath.ext.cellappose.core.CellposeModelFamily\n\n");
        sb.append("def cellpose = Cellpose2D.builder(CellposeModelFamily.")
                .append(family.name())
                .append(")\n");
        if (customModelPath != null) {
            sb.append("        .customModel(\"").append(escape(customModelPath)).append("\")\n");
        } else if (family == CellposeModelFamily.CP3) {
            sb.append("        .modelName(\"")
                    .append(escape(modelName == null ? "cyto3" : modelName))
                    .append("\")\n");
        }
        if (family == CellposeModelFamily.CP3) {
            sb.append("        .channels(")
                    .append(cellChannel)
                    .append(", ")
                    .append(nucleiChannel)
                    .append(")\n");
        } else {
            sb.append("        .channelsCP4(")
                    .append(nChannels)
                    .append(", ")
                    .append(chan0)
                    .append(", ")
                    .append(chan1)
                    .append(", ")
                    .append(chan2)
                    .append(")\n");
        }
        sb.append("        .diameter(").append(diameter).append(")\n");
        sb.append("        .cellprobThreshold(").append(cellprobThreshold).append(")\n");
        sb.append("        .flowThreshold(").append(flowThreshold).append(")\n");
        sb.append("        .minSize(").append(minSize).append(")\n");
        sb.append("        .normalize(").append(normalize).append(")\n");
        sb.append("        .tileSize(").append(tileSize).append(")\n");
        sb.append("        .tileOverlap(").append(tileOverlap).append(")\n");
        sb.append("        .pixelSize(").append(pixelSize).append(")\n");
        sb.append("        .cellExpansion(").append(cellExpansion).append(")\n");
        sb.append("        .constrainToParent(").append(constrainToParent).append(")\n");
        sb.append("        .useGpu(").append(useGpu).append(")\n");
        switch (outputType) {
            case CELLS -> sb.append("        .createCells()\n");
            case ANNOTATIONS -> sb.append("        .createAnnotations()\n");
            default -> sb.append("        .createDetections()\n");
        }
        sb.append("        .build()\n\n");
        sb.append("cellpose.detectObjects(getCurrentImageData(), getSelectedObjects())\n");
        return sb.toString();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
