package qupath.ext.cellappose.core;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apposed.appose.NDArray;
import org.apposed.appose.Service.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.ext.cellappose.service.ApposeCellposeService;
import qupath.lib.geom.ImmutableDimension;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.objects.CellTools;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.workflow.DefaultScriptableWorkflowStep;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.RoiTools;
import qupath.lib.roi.interfaces.ROI;

/**
 * Whole-slide Cellpose detection orchestrator.
 *
 * <p>Runs the pipeline from {@code wip}/{@code 02_design.md}: resolve a processing
 * downsample from the requested pixel size, tile each parent ROI, run each tile
 * through the Appose Cellpose backend, convert label rasters to geometries in
 * full-image coordinates, resolve tile-seam fragments, optionally expand to cells,
 * constrain to the parent, and add the objects to the hierarchy.
 *
 * <p>Construct via {@link CellposeBuilder}. The static {@link #builder} entry point
 * makes the detector scriptable from Groovy.
 */
public final class Cellpose2D {

    private static final Logger logger = LoggerFactory.getLogger(Cellpose2D.class);

    /**
     * Largest label value the shared uint16 output buffer can carry
     * (see {@link NDArrays#allocateLabelNDArray(int, int)}).
     */
    private static final int MAX_LABELS_PER_TILE = 65535;

    private final CellposeParameters params;

    /** Set once the missing-{@code n_labels} warning has been logged, to keep it off the per-tile path. */
    private boolean warnedMissingLabelCount = false;

    Cellpose2D(CellposeParameters params) {
        this.params = params;
    }

    /**
     * Starts a fluent builder for the given model family.
     *
     * @param family the Cellpose model family
     * @return a new builder
     */
    public static CellposeBuilder builder(CellposeModelFamily family) {
        return new CellposeBuilder(family);
    }

    /**
     * The configuration this detector will run.
     */
    public CellposeParameters parameters() {
        return params;
    }

    /**
     * Runs detection over the given parent objects and adds the resulting objects to
     * the image hierarchy.
     *
     * @param imageData the image data
     * @param parents   the parent objects to detect within (whole image if a single
     *                  full-image annotation, or each selected annotation)
     * @return the total number of objects added to the hierarchy; 0 means the run
     *         completed but detected nothing, which is a result the caller should
     *         surface rather than treat as success
     * @throws IOException if the Appose backend is unavailable or a tile task fails
     */
    public int detectObjects(ImageData<BufferedImage> imageData, Collection<? extends PathObject> parents)
            throws IOException {
        ImageServer<BufferedImage> server = imageData.getServer();
        PathObjectHierarchy hierarchy = imageData.getHierarchy();

        double downsample = resolveDownsample(server);
        int overlap = params.effectiveTileOverlap();
        // One run id per detectObjects call; one params hash per configuration.
        String runId = UUID.randomUUID().toString();
        String paramsHash = params.paramsHash();
        logger.info(
                "CellAPpose detect: family={} downsample={} tile={} overlap={} (configured {}) run_id={} params_hash={}",
                params.family(),
                downsample,
                params.tileSize(),
                overlap,
                params.tileOverlap(),
                runId,
                paramsHash);

        // Cellpose-SAM only sees the channels picked in the dialog. On an RGB image a
        // single-channel selection hands it one colour plane while the CP3 path on the
        // same image gets all three, which usually looks like "CP4 segments badly".
        if (params.family() == CellposeModelFamily.CP4 && server.isRGB() && params.cp4SourceBands().length == 1) {
            logger.warn(
                    "CellAPpose: Cellpose-SAM is running on an RGB image with only one channel selected"
                            + " (source band {}); the other two colour planes are ignored. Select all three"
                            + " channels for brightfield/H&E images.",
                    params.cp4SourceBands()[0]);
        }

        ApposeCellposeService service = ApposeCellposeService.getInstance();
        // Lazily ensure the family's environment is built and its service is running.
        // The GUI dialog does build-then-run; the scripting API (batch / headless
        // 'QuPath script ...') reaches here directly, so without this it would throw
        // "service is not available". buildEnvironment is idempotent and cheap once the
        // env exists on disk (it just re-attaches the Python service).
        if (!service.isAvailable(params.family())) {
            logger.info("CellAPpose: {} service not initialized; building/attaching environment...", params.family());
            service.buildEnvironment(params.family(), msg -> logger.info("[CellAPpose env] {}", msg));
        }
        // Load (and cache) the model for this configuration once.
        service.ensureModel(params.family(), params.modelCacheKey(), params.toInitInputs());

        int totalCreated = 0;
        for (PathObject parent : parents) {
            ROI parentRoi = parent.getROI();
            if (parentRoi == null) {
                continue;
            }
            List<PathObject> created = detectInParent(server, parentRoi, downsample, overlap, service);
            if (created.isEmpty()) {
                logger.warn("CellAPpose: no objects detected in parent '{}'", parent.getDisplayedName());
                continue;
            }
            totalCreated += created.size();
            stampProvenance(created, runId, paramsHash, downsample);
            parent.addChildObjects(created);
            parent.setLocked(true);
        }
        hierarchy.fireHierarchyChangedEvent(this);

        if (totalCreated == 0) {
            // A run that finds nothing is a failed run from the user's point of view;
            // say so, and name the settings that most often cause it.
            logger.warn(
                    "CellAPpose: detection finished but produced NO objects across {} parent object(s)."
                            + " Check the channel selection, the object diameter ({} um) and the requested"
                            + " pixel size ({} um/px).",
                    parents.size(),
                    params.diameter(),
                    params.pixelSize());
        } else {
            logger.info(
                    "CellAPpose: detection created {} objects across {} parent object(s)",
                    totalCreated,
                    parents.size());
        }

        // Record a replayable WorkflowStep so a dialog run can be reproduced from
        // History (classify-object-subset pattern: emit the equivalent builder script).
        try {
            String script = params.toBuilderScript();
            imageData
                    .getHistoryWorkflow()
                    .addStep(new DefaultScriptableWorkflowStep("Cellpose detection (CellAPpose)", script));
        } catch (RuntimeException e) {
            logger.warn("Could not add CellAPpose workflow step: {}", e.getMessage());
        }
        return totalCreated;
    }

    /**
     * Stamps run-level provenance on every created object: a run_id (one per
     * detectObjects call), a params_hash, and the full parameter set as object metadata
     * plus a couple of numeric measurements. Mirrors the fiber-analysis run_id +
     * params_hash precedent.
     */
    private void stampProvenance(List<PathObject> objects, String runId, String paramsHash, double downsample) {
        Map<String, String> fields = params.provenanceFields();
        String extVersion = qupath.lib.common.GeneralTools.getPackageVersion(Cellpose2D.class);
        for (PathObject obj : objects) {
            Map<String, String> meta = obj.getMetadata();
            meta.put("cellappose:run_id", runId);
            meta.put("cellappose:params_hash", paramsHash);
            meta.put("cellappose:downsample", String.valueOf(downsample));
            meta.put("cellappose:extension_version", extVersion != null ? extVersion : "dev");
            meta.putAll(fields);
            // A couple of numeric measurements for quick filtering / export.
            obj.getMeasurementList().put("cellappose:diameter", params.diameter());
            obj.getMeasurementList().put("cellappose:cellprob_threshold", params.cellprobThreshold());
            obj.getMeasurementList().put("cellappose:flow_threshold", params.flowThreshold());
        }
    }

    private List<PathObject> detectInParent(
            ImageServer<BufferedImage> server,
            ROI parentRoi,
            double downsample,
            int overlap,
            ApposeCellposeService service)
            throws IOException {

        ImmutableDimension preferred = ImmutableDimension.getInstance(params.tileSize(), params.tileSize());
        int maxDim = (int) Math.round(params.tileSize() * 1.5);
        ImmutableDimension maxSize = ImmutableDimension.getInstance(maxDim, maxDim);

        Collection<? extends ROI> tiles = RoiTools.computeTiledROIs(parentRoi, preferred, maxSize, true, overlap);

        // Track tile-of-origin so the merger only de-duplicates ACROSS tiles, never
        // within one tile (distinct touching cells must both survive).
        List<TileMerger.Candidate> candidates = new ArrayList<>();
        int tileId = 0;
        for (ROI tileRoi : tiles) {
            List<ROI> tileObjects = runTile(server, tileRoi, downsample, service);
            for (ROI roi : tileObjects) {
                candidates.add(new TileMerger.Candidate(roi, tileId));
            }
            tileId++;
        }

        // Resolve duplicates / clipped fragments across tile seams (cross-tile only).
        List<ROI> mergedRois = new TileMerger().merge(candidates);
        logger.info(
                "CellAPpose: {} candidates from {} tiles -> {} after cross-tile seam merge",
                candidates.size(),
                tileId,
                mergedRois.size());

        return buildObjects(mergedRois, parentRoi, downsample);
    }

    private List<ROI> runTile(
            ImageServer<BufferedImage> server, ROI tileRoi, double downsample, ApposeCellposeService service)
            throws IOException {
        RegionRequest request = RegionRequest.createInstance(
                server.getPath(),
                downsample,
                (int) Math.round(tileRoi.getBoundsX()),
                (int) Math.round(tileRoi.getBoundsY()),
                (int) Math.round(tileRoi.getBoundsWidth()),
                (int) Math.round(tileRoi.getBoundsHeight()),
                tileRoi.getImagePlane());

        BufferedImage img;
        try {
            img = server.readRegion(request);
        } catch (IOException e) {
            throw new IOException("Failed to read tile region " + request, e);
        }
        if (img == null) {
            return List.of();
        }
        int w = img.getWidth();
        int h = img.getHeight();

        // Tile encoding by image type / family. cellpose must never receive more channels than it
        // needs, or it mis-reads the channel axis as a Z-stack (one mask per plane -> (N,H,W) cannot
        // broadcast into the (H,W) label buffer). So for multichannel images we EXTRACT only the
        // user's selected channel(s):
        //  - CP3 + multichannel -> compact channels-LAST (H,W) or (H,W,2), cellpose channels [0,0]
        //                          (single) or [1,2] (pair). channel_axis=2 (pair) or None (single).
        //  - CP4 + multichannel -> compact channels-FIRST (k,H,W) because cp4.py selects via
        //                          input_image[..., channels, :, :] (channel axis at -3). cpsam gets
        //                          channel_axis=0 and compact chan indices 0..k-1.
        //  - true 8-bit RGB (CP3) -> packed (H,W,3) uint8, channels left as-is.
        //  - single channel       -> (H,W) uint8, channel_axis=None.
        int nBands = img.getRaster().getNumBands();
        boolean trueRgb = server.isRGB();
        boolean isCp4 = params.family() == CellposeModelFamily.CP4;
        boolean cp3Multichannel = !isCp4 && !trueRgb && nBands > 1;
        boolean cp4Multichannel = isCp4 && nBands > 1; // CP4 extracts CHW for any >1-band image (incl. RGB)
        boolean hasChannelAxis;
        Integer cellChannelOverride = null;
        Integer nucleiChannelOverride = null;
        boolean overrideCp4Channels = false;
        Integer cp4Chan1 = null;
        Integer cp4Chan2 = null;
        int cp4NChannels = 1;
        NDArray tile = null;
        NDArray labels = null;
        try {
            if (cp4Multichannel) {
                int[] bands = params.cp4SourceBands();
                if (bands.length == 0) {
                    bands = new int[] {0}; // no channel selected -> first channel
                }
                if (bands.length > 3) {
                    bands = java.util.Arrays.copyOf(bands, 3); // cpsam takes at most 3 channels
                }
                tile = NDArrays.bufferedImageToSelectedChannelsCHWNDArray(img, bands); // (k, H, W)
                hasChannelAxis = true; // overridden below to channel_axis=0 (channels-first)
                overrideCp4Channels = true;
                cp4NChannels = bands.length;
                cp4Chan1 = bands.length > 1 ? 1 : null;
                cp4Chan2 = bands.length > 2 ? 2 : null;
            } else if (cp3Multichannel) {
                int[] bands = params.cp3SourceBands();
                if (bands.length == 0) {
                    bands = new int[] {0}; // no channel selected -> first channel, grayscale
                }
                tile = NDArrays.bufferedImageToSelectedChannelsNDArray(img, bands);
                if (bands.length > 1) {
                    hasChannelAxis = true;
                    cellChannelOverride = 1; // compact tile: band 0 = cyto -> cellpose channel 1
                    nucleiChannelOverride = 2; // band 1 = nucleus -> cellpose channel 2
                } else {
                    hasChannelAxis = false; // single (H,W) grayscale plane
                    cellChannelOverride = 0; // cellpose channels=[0,0] -> grayscale
                    nucleiChannelOverride = 0;
                }
            } else if (trueRgb) {
                tile = NDArrays.bufferedImageToRGBNDArray(img); // (H, W, 3) uint8 (CP3 RGB)
                hasChannelAxis = true;
            } else {
                tile = NDArrays.bufferedImageToGrayNDArray(img); // (H, W) uint8 single channel
                hasChannelAxis = false;
            }
            labels = NDArrays.allocateLabelNDArray(h, w);

            java.util.Map<String, Object> inputs = params.toApposeInputs(tile, labels, hasChannelAxis);
            if (cp3Multichannel) {
                // The compact tile re-indexes channels, so override the source-image indices.
                inputs.put("cell_channel", cellChannelOverride);
                inputs.put("nuclei_channel", nucleiChannelOverride);
            }
            if (overrideCp4Channels) {
                // Channels-first compact tile: cp4.py selects on axis -3, so channel_axis=0 and the
                // chan indices are compact (0..k-1) into the extracted tile.
                inputs.put("channel_axis", 0);
                inputs.put("chan0", 0);
                inputs.put("chan1", cp4Chan1);
                inputs.put("chan2", cp4Chan2);
                inputs.put("n_channels", cp4NChannels);
            }
            Task task = service.runTile(params.family(), inputs);
            int nLabels = labelCount(task);
            logger.debug("CellAPpose tile {} -> n_labels={}", request, nLabels);
            checkLabelCount(nLabels, request);

            float[] labelRaster = NDArrays.readLabelsAsFloat(labels, h, w);
            return LabelToObjects.labelsToROIs(labelRaster, w, h, request);
        } finally {
            if (tile != null) {
                try {
                    tile.close();
                } catch (Exception ignored) {
                    // shared memory will be GC'd eventually
                }
            }
            if (labels != null) {
                try {
                    labels.close();
                } catch (Exception ignored) {
                    // shared memory will be GC'd eventually
                }
            }
        }
    }

    /**
     * Reads the {@code n_labels} scalar the Python scripts publish alongside the shared
     * label buffer (the largest label value written, i.e. the object count for a
     * contiguous Cellpose labelling).
     *
     * @param task the completed tile task
     * @return the label count, or -1 if the script did not publish a usable value
     */
    private static int labelCount(Task task) {
        Object value = task.outputs.get("n_labels");
        if (value instanceof Number number) {
            return number.intValue();
        }
        return -1;
    }

    /**
     * Fails the run when a tile produced more objects than the shared uint16 label
     * buffer can represent. Python writes the labels with {@code output_labels[:] = masks},
     * a numpy slice assignment that casts UNSAFELY and SILENTLY: object 65536 would wrap
     * to 0 (background) and 65537 would merge into object 1. Detecting it here and
     * stopping is the difference between a wrong result and a reported one.
     *
     * @param nLabels the label count reported by the Python task, or -1 if unknown
     * @param request the tile region, for the error message
     * @throws IOException if the count exceeds what the uint16 buffer can hold
     */
    private void checkLabelCount(int nLabels, RegionRequest request) throws IOException {
        if (nLabels < 0) {
            // The scripts always publish n_labels; if one stops, say so rather than
            // letting the overflow guard quietly become a no-op.
            if (!warnedMissingLabelCount) {
                warnedMissingLabelCount = true;
                logger.warn("CellAPpose: the Python task published no usable 'n_labels' output;"
                        + " the 16-bit label-buffer overflow guard is inactive for this run.");
            }
            return;
        }
        if (nLabels <= MAX_LABELS_PER_TILE) {
            return;
        }
        int suggested = Math.max(64, params.tileSize() / 2);
        throw new IOException(String.format(
                "CellAPpose: the tile at %s produced %d objects, more than the %d that the 16-bit label"
                        + " buffer shared with Python can hold. Labels above %d would be silently corrupted"
                        + " (object %d would become background), so the run was stopped instead of returning"
                        + " a wrong result. Reduce the tile size from %d px to about %d px (or increase the"
                        + " requested pixel size) so each tile holds fewer objects.",
                request,
                nLabels,
                MAX_LABELS_PER_TILE,
                MAX_LABELS_PER_TILE,
                MAX_LABELS_PER_TILE + 1,
                params.tileSize(),
                suggested));
    }

    private List<PathObject> buildObjects(List<ROI> rois, ROI parentRoi, double downsample) {
        // Optional cell expansion (nucleus -> cell).
        if (params.outputType() == CellposeParameters.OutputType.CELLS && params.cellExpansion() > 0) {
            List<PathObject> nuclei = new ArrayList<>(rois.size());
            for (ROI roi : rois) {
                nuclei.add(PathObjects.createDetectionObject(roi));
            }
            double expansionPixels = params.cellExpansion() / Math.max(downsample, 1e-9);
            List<PathObject> cells = CellTools.detectionsToCells(nuclei, expansionPixels, 1.0);
            if (params.constrainToParent()) {
                List<PathObject> constrained = new ArrayList<>(cells.size());
                for (PathObject cell : cells) {
                    ROI clipped = constrain(cell.getROI(), parentRoi);
                    if (clipped != null && !clipped.isEmpty()) {
                        constrained.add(PathObjects.createCellObject(clipped, cell.getROI(), null));
                    }
                }
                return constrained;
            }
            return cells;
        }

        // Detections or annotations.
        List<PathObject> objects = new ArrayList<>(rois.size());
        for (ROI roi : rois) {
            ROI out = params.constrainToParent() ? constrain(roi, parentRoi) : roi;
            if (out == null || out.isEmpty()) {
                continue;
            }
            if (params.outputType() == CellposeParameters.OutputType.ANNOTATIONS) {
                objects.add(PathObjects.createAnnotationObject(out));
            } else {
                objects.add(PathObjects.createDetectionObject(out));
            }
        }
        return objects;
    }

    private static ROI constrain(ROI roi, ROI parentRoi) {
        if (roi == null) {
            return null;
        }
        try {
            return RoiTools.combineROIs(roi, parentRoi, RoiTools.CombineOp.INTERSECT);
        } catch (RuntimeException e) {
            // Topology error: keep the unclipped ROI rather than dropping it.
            return roi;
        }
    }

    /**
     * Resolves the processing downsample from the requested pixel size against the
     * image's calibration. Falls back to 1.0 when calibration is unknown.
     */
    private double resolveDownsample(ImageServer<BufferedImage> server) {
        PixelCalibration cal = server.getPixelCalibration();
        if (cal != null && cal.hasPixelSizeMicrons() && params.pixelSize() > 0) {
            double base = cal.getAveragedPixelSizeMicrons();
            if (base > 0) {
                return Math.max(1.0, params.pixelSize() / base);
            }
        }
        return 1.0;
    }
}
