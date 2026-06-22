# CellAPpose -- User Guide

[Back to README](../README.md)

CellAPpose segments cells and nuclei with **Cellpose**, then stitches the per-tile
results back into whole-image objects. The model itself is upstream Cellpose; what
this extension adds is the QuPath-side tiling, coordinate translation, and
tile-seam merging that lets Cellpose run on regions far larger than a single
Cellpose call.

The sections below are collapsed by default -- expand the ones you need. If you are
new, read **Overview** and **Choosing CP3 vs CP4**, then run from the Quick Start
in the [README](../README.md).

> **Testbed.** Research use only; not for clinical decision-making. Parameter
> names and output formats may change between revisions.

---

<details>
<summary><strong>1. Overview</strong> (read this first)</summary>

CellAPpose runs a six-step pipeline each time you click **Run**:

1. **Resolve a processing downsample** from the requested processing resolution
   (um/px) against the image's pixel calibration, so Cellpose always sees a
   consistent physical scale and the diameter stays meaningful.
2. **Tile** the parent region into overlapping squares
   (`RoiTools.computeTiledROIs`).
3. **Segment** each tile through the Appose Cellpose backend, reusing a cached
   model loaded once per run.
4. **Convert** each tile's label mask into QuPath geometries in full-image
   coordinates.
5. **Merge tile seams** -- objects split across a tile boundary are reconciled into
   single objects (keep the larger, drop the boundary fragment).
6. **Build objects**, optionally expand nuclei into cells, constrain them to the
   parent region, and add them to the hierarchy.

**When to use it:** whole-slide or large-region segmentation where you want
Cellpose specifically. For a small region with QuPath's own watershed cell
detection, the built-in `Analyze > Cell detection` is lighter-weight and needs no
Python environment.

The model itself is unchanged upstream Cellpose -- segmentation quality is bounded
by your diameter, channel, and threshold choices. A wrong diameter or channel pick
degrades every downstream object, so it is worth getting those right on a small
test region before running a slide.

</details>

<details>
<summary><strong>2. Choosing CP3 vs CP4</strong></summary>

CellAPpose offers two Cellpose families behind the **Model family** switch at the
top of the Run dialog. Each builds and caches its **own** Appose environment on
first use (`cellappose-cp3` / `cellappose-cp4`) -- see *Managing environments*.

**Cellpose 3 (CP3)** uses explicit cell/nuclei channel pairing and the classic
built-in models (`cyto3`, `cyto2`, `nuclei`). It is the default. Choose it when
you have a defined cytoplasm/nucleus channel pair or want one of those models.

**Cellpose-SAM / Cellpose 4 (CP4)** is channel-flexible -- it uses all channels
with no cell/nuclei split -- and uses the SAM-based generalist model. Choose it for
arbitrary or many-channel images, or when you want the newest model.

| If you... | Use | Why |
|---|---|---|
| have a paired cytoplasm + nucleus channel | CP3 | explicit cell / nuclei channel selectors |
| want the `cyto3`, `cyto2`, or `nuclei` built-ins | CP3 | those are Cellpose 3 models |
| have arbitrary / many channels, or want the newest model | CP4 (Cellpose-SAM) | channel-flexible, no pairing |
| are unsure | CP3 | the shipped default; switch to CP4 if it underperforms |

The dialog only shows the controls relevant to the selected family: CP3 shows the
built-in-model dropdown and the cell/nuclei channel selectors; CP4 shows a note
that it uses all channels with no split.

</details>

<details>
<summary><strong>3. Parameters explained</strong></summary>

Every Run-dialog control, in dialog order, with the **real defaults** from
`CellAPposePrefs`. The dialog persists these between sessions (saved on Run).

| Control | Section | Default | Range / units | What it does |
|---|---|---|---|---|
| Model family | (top) | CP3 (Cellpose 3) | CP3 / CP4 | Selects the Cellpose family; drives which environment builds. |
| Built-in model | Model and channels (CP3) | `cyto3` | cyto3 / cyto2 / nuclei | CP3 built-in model. cyto3/cyto2 segment whole cells; nuclei segments nuclei only. |
| Cell channel | Model and channels (CP3) | first channel | None or a channel | Image channel showing the cell body / cytoplasm. |
| Nuclei channel | Model and channels (CP3) | None | None or a channel | Image channel showing nuclei. Set to None for cells only. |
| Diameter (px) | Detection parameters | 30 | 0..1000 px | Expected object diameter in pixels. **0 = let Cellpose estimate automatically.** |
| Cell probability | Detection parameters | 0.0 | -6.0..6.0 | Detection sensitivity. Lower finds more (and smaller) objects; higher is stricter. |
| Flow threshold | Detection parameters | 0.4 | 0.0..1.0 | Maximum allowed flow error per mask. Lower rejects more poorly-shaped objects. |
| Minimum object size | Detection parameters | 15 | 0..100000 px | Objects smaller than this many pixels are discarded. |
| Normalize image intensities | Detection parameters | on | -- | Rescale each tile's intensities (1-99 percentile) before segmentation. Recommended for most images. |
| Tile size (px) | Tiling and resolution | 1024 | 64..8192 px | Edge length of each square tile sent to Cellpose. Larger tiles use more memory. |
| Tile overlap (px) | Tiling and resolution | 60 | 0..4096 px | Overlap band between neighbouring tiles so edge objects are not split. ~2x diameter works well; recomputed automatically when you change the diameter. |
| Processing resolution (um/px) | Tiling and resolution | 0.5 | 0.0..100.0 um/px | Physical resolution Cellpose sees; sets the downsample. Coarser runs faster; finer resolves smaller objects. |
| Output as | Output | Detections | Detections / Cells / Annotations | What object type to create (see *Cell expansion & output types*). |
| Cell expansion (um) | Output | 0.0 | 0..1000 um | Grow each object outward by this many um to approximate the cell boundary. 0 = no expansion. Only used when Output = Cells. |
| Constrain expanded cells to parent | Output | on | -- | Trim any object part that spills outside the parent annotation. |
| Use GPU if available | Compute | on | -- | Run on the GPU when one is present, falling back to CPU otherwise. |
| Run on | (bottom) | Selected (if any), else Whole image | Selected annotations / Whole image | Whether to segment inside selected annotations or across the entire image. |

Notes on a few that surprise people:

- **Diameter is in pixels** at the processing resolution, not microns. If you set a
  processing resolution of 0.5 um/px and your nuclei are ~10 um across, a diameter
  near 20 px is sensible. Set `0` and Cellpose estimates per tile.
- **Tile overlap auto-recomputes** to roughly 2x the diameter whenever you change
  the diameter spinner, capped at half the tile size. You can override it.
- **Cell probability runs -6 to 6** (Cellpose's range), not 0..1.

</details>

<details>
<summary><strong>4. Tiling & whole-slide images</strong></summary>

CellAPpose tiles the parent region into overlapping squares at a chosen
**processing resolution**, runs Cellpose on each tile independently, then merges
objects that straddle a tile boundary.

**Processing resolution (um/px).** This sets the downsample: the extension divides
your requested um/px by the image's calibrated pixel size and reads each tile at
that downsample. The benefit is that the diameter stays meaningful regardless of
the slide's native magnification -- Cellpose always sees the same physical scale.
If the image has no pixel calibration, the downsample falls back to 1.0 (native
pixels) and the diameter is then in native pixels. Pick a resolution near the scale
Cellpose was trained on; 0.5 um/px (the default) is a reasonable starting point for
most cell/nuclei work.

**Tile size.** Big enough to give Cellpose context around each object, small enough
to fit comfortably in GPU/CPU memory. 1024 px (the default) is a good balance.
Larger tiles mean fewer seams to merge but more memory per tile.

**Tile overlap.** Neighbouring tiles overlap by this many pixels so an object on a
tile edge appears whole in at least one tile. **Set the overlap to at least the
largest object diameter you expect** (the rule of thumb is ~2x diameter, BIOP's
guidance). This is not cosmetic: the seam merge can only collapse two halves of a
straddling cell into one object if at least one tile saw that cell **whole**, which
requires the overlap band to be wider than the object. If the overlap is narrower
than the object, both tiles see only a clipped piece and the merge cannot tell they
are the same cell -- you get a double-count.

The dialog recomputes the overlap automatically: for an explicit diameter it uses
2x diameter; for **auto-diameter** (Cellpose-SAM / diameter 0), where it cannot read
your diameter, it applies a **minimum floor** (at least 60 px) so a scripted or
auto-diameter run is never left with a tiny overlap. The same floor is enforced
inside the detector (`CellposeParameters.effectiveTileOverlap`), so scripted
`Cellpose2D.builder(...)` runs get it too -- if you set a smaller overlap it is
raised to the floor (and capped below half the tile size). If you know your objects
are large, set the overlap explicitly above the floor.

**Seam merging.** After all tiles are segmented, candidate objects are collected in
full-image coordinates and reconciled by `TileMerger`. Each candidate is tagged with
the tile it came from. The merger indexes object envelopes in a JTS Quadtree and
runs suppression **only across different tiles**: where a candidate from one tile is
mostly (>50% of its own area) inside a larger candidate from another tile in the
overlap band, the smaller (clipped) one is dropped. **Objects from the same tile are
never merged against each other** -- two distinct cells that genuinely touch within a
single tile are Cellpose's own segmentation and both survive. This avoids both the
seam double-count (cross-tile duplicates removed) and the undercount that pooled,
tile-blind suppression would cause (distinct touching cells wrongly merged).

**Honest limit and residual bias.** The seam-merge result is judged clean from unit
tests and WSL smoke runs, not from large-scale production grading. The residual bias
direction is toward **under-merging** when the overlap is too small (a straddling
cell seen only as two clipped halves cannot be merged and double-counts), so when in
doubt set overlap >= largest object diameter. If you still see duplicated objects
along seams, increase the overlap; if it persists, that is worth filing an issue
against (with a screenshot of the seam).

</details>

<details>
<summary><strong>5. Cell expansion & output types</strong></summary>

The **Output as** radio buttons choose what object type CellAPpose creates from the
Cellpose label masks:

- **Detections** (default) -- one `PathDetectionObject` per segmented object,
  created directly from the label mask. This is the lightest output and what you
  want for most downstream measurement and classification.
- **Cells** -- `PathCellObject`s, where each Cellpose object becomes a nucleus and
  is expanded outward by the **Cell expansion (um)** distance to approximate a cell
  boundary (`CellTools.detectionsToCells`). The expansion in microns is converted
  to pixels at the processing downsample before expansion. Use this when you want
  paired nucleus + cell ROIs (e.g. for cytoplasmic intensity measurements). With
  Cell expansion = 0 there is nothing to expand, so pick a positive distance.
- **Annotations** -- one `PathAnnotationObject` per segmented object, editable by
  hand afterward. Useful when you want to curate or correct the segmentation
  manually. Note: large slides produce many objects; annotations are heavier than
  detections.

**Constrain to parent.** When on (default), each created object is intersected with
the parent annotation so nothing spills outside the region you ran on. If the
intersection hits a topology error, the unclipped object is kept rather than
dropped. Objects are added as children of the parent, and the parent is locked
after a successful run.

**Whole-image runs.** When you pick **Run on: Whole image**, the extension creates
a full-image rectangle annotation as the parent and runs inside it, so the same
constrain-to-parent and hierarchy logic applies. The temporary full-image annotation
is added just before detection and **removed automatically if the run fails or is
interrupted**, so a failed run never leaves an orphan annotation in your project.

</details>

<details>
<summary><strong>5b. Provenance &amp; reproducibility</strong></summary>

Every object CellAPpose creates is stamped with the run's parameters so a result is
traceable and reproducible later.

**On each object (metadata).** Each detection/cell/annotation carries object metadata
keys (visible via scripting or the measurement/metadata tables): `cellappose:run_id`
(a unique id shared by every object from one detection run), `cellappose:params_hash`
(a short hash of the parameter set -- two runs with different settings get different
hashes), `cellappose:model_family`, `cellappose:model_name`, `cellappose:diameter`,
`cellappose:cellprob_threshold`, `cellappose:flow_threshold`, `cellappose:normalize`,
`cellappose:min_size`, `cellappose:tile_size`, `cellappose:tile_overlap` (the
effective overlap after the floor), `cellappose:pixel_size_um`,
`cellappose:cell_expansion_um`, `cellappose:output_type`, `cellappose:use_gpu`,
`cellappose:downsample`, and `cellappose:extension_version`. A few are also written as
numeric measurements (`cellappose:diameter`, `cellappose:cellprob_threshold`,
`cellappose:flow_threshold`) for quick filtering/export.

**In the project history (workflow).** Each run also records a **replayable workflow
step** in the image's History (`Workflow > Show workflow` / `Create workflow`). The
step is the equivalent `Cellpose2D.builder(...)` Groovy script, so a run you did from
the dialog can be re-run or batched from the script editor without re-entering the
parameters by hand.

</details>

<details>
<summary><strong>6. Scripting (the <code>Cellpose2D.builder</code> API)</strong></summary>

The entire detection pipeline is scriptable from the QuPath Groovy editor
(`Automate > Show script editor`) via a fluent `Cellpose2D.builder(family)` API.
Build a configured detector once and call `.detectObjects(imageData, parents)`. The
same parameters the dialog exposes are available as builder methods, so a dialog
run can be reproduced and batched.

The model environment is built on demand the first time the family is used, exactly
as from the dialog -- so the first scripted run on a fresh machine will block while
the env builds.

**CP3 example** (paired channels, built-in model, detections):

```groovy
import qupath.ext.cellappose.core.Cellpose2D
import qupath.ext.cellappose.core.CellposeModelFamily

def cellpose = Cellpose2D.builder(CellposeModelFamily.CP3)
        .modelName("cyto3")        // cyto3 / cyto2 / nuclei
        .channels(1, 0)            // cell channel, nuclei channel (1-based; 0 or null = none)
        .diameter(30)              // px at the processing resolution; 0 = auto
        .cellprobThreshold(0.0)    // -6.0 .. 6.0
        .flowThreshold(0.4)        // 0.0 .. 1.0
        .minSize(15)               // px
        .normalize(true)
        .tileSize(1024)            // px
        .tileOverlap(60)           // px (about 2x diameter)
        .pixelSize(0.5)            // processing resolution, um/px
        .cellExpansion(0.0)        // um; > 0 only matters with createCells()
        .constrainToParent(true)
        .useGpu(true)
        .createDetections()        // or .createCells() / .createAnnotations()
        .build()

def imageData = getCurrentImageData()
def parents   = getSelectedObjects()   // or getAnnotationObjects()
cellpose.detectObjects(imageData, parents)
fireHierarchyUpdate()
```

**CP4 example** (channel-flexible, cells with expansion):

```groovy
import qupath.ext.cellappose.core.Cellpose2D
import qupath.ext.cellappose.core.CellposeModelFamily

def cellpose = Cellpose2D.builder(CellposeModelFamily.CP4)
        .channelsCP4(3, 0, 1, 2)   // n channels, then chan0/chan1/chan2 indices (null to skip)
        .diameter(0)               // 0 = let Cellpose estimate
        .cellprobThreshold(0.0)
        .flowThreshold(0.4)
        .tileSize(1024)
        .tileOverlap(60)
        .pixelSize(0.5)
        .cellExpansion(5.0)        // um -> creates a cell boundary around each nucleus
        .createCells()
        .build()

cellpose.detectObjects(getCurrentImageData(), getAnnotationObjects())
fireHierarchyUpdate()
```

**Builder methods** (all return the builder for chaining):
`modelName(String)`, `customModel(String path)`, `channels(Integer cell, Integer
nuclei)` (CP3), `channelsCP4(int n, Integer chan0, Integer chan1, Integer chan2)`
(CP4), `diameter(double)`, `cellprobThreshold(double)`, `flowThreshold(double)`,
`minSize(int)`, `normalize(boolean)`, `tileSize(int)`, `tileOverlap(int)` (alias
`setOverlap(int)`), `pixelSize(double)`, `cellExpansion(double um)`,
`constrainToParent(boolean)`, `useGpu(boolean)`, `createDetections()`,
`createCells()`, `createAnnotations()`, `build()`.

`detectObjects` adds the objects to the hierarchy itself; call
`fireHierarchyUpdate()` afterward to refresh the viewer. It throws `IOException`
if the Appose backend is unavailable or a tile task fails -- check the Python
Console for the underlying Python traceback.

**Scripting covers what the dialog does not yet surface.** Two options are
available in the builder today but not exposed as dialog controls in this release:
a **CP3 custom trained model** (`.customModel("/path/to/model")`) and an explicit
**CP4 channel picker** (`.channelsCP4(...)`). The dialog uses the CP3 built-in
models and "all channels" for CP4; use the builder above if you need a custom model
or a specific channel subset. Surfacing both in the dialog is planned for v1.1.

</details>

<details>
<summary><strong>7. Managing environments</strong></summary>

CellAPpose builds and caches **two independent Appose environments**, one per
Cellpose family, under `~/.local/share/appose/`:

- `cellappose-cp3` -- Cellpose 3
- `cellappose-cp4` -- Cellpose-SAM (Cellpose 4)

Each builds **lazily on first use** of its family. They are separate because the
two Cellpose majors have mutually exclusive PyPI constraints (`>=3,<4` vs
`>=4,<5`) and cannot share one Python env. Running both families costs roughly 2x
disk (PyTorch is duplicated) -- an accepted testbed tradeoff.

**`Extensions > CellAPpose > Manage Environments...`** is the control panel. For
each family it shows a status tag (built / not built) and offers:

- **Build** -- create the environment ahead of time, with a progress indicator.
  Do this before your first real run so the run itself does not appear to hang.
- **Rebuild** -- force a fresh build (use after changing the bundled pixi manifest,
  or to recover from a corrupt env).
- **Delete** -- remove the environment from disk (confirmation prompt). The next
  run of that family will rebuild it.

**To force a rebuild manually,** delete the env's `.pixi/` under
`~/.local/share/appose/cellappose-cpN/`. The service content-hashes the bundled
pixi manifest and lockfile (`syncManifest()`), so a changed manifest or lock also
triggers a rebuild automatically. Each env is installed with `pixi install
--frozen` from a bundled `cpN.lock` that pins the exact versions, so a rebuild
reinstalls the same tested dependency set rather than re-resolving.

**Python Console.** `Extensions > CellAPpose > Python Console` is where Cellpose
and Appose output -- including build progress, model-weight downloads, and
tracebacks -- appears. Appose reserves stdout for its IPC protocol, so Python
diagnostics route here rather than to the QuPath log. Keep it open the first time
you build an environment or run a detection. Console lines carry a
`[cellappose-cpN]` prefix so you can tell the two families apart.

</details>

<details>
<summary><strong>8. Troubleshooting</strong></summary>

Most Appose issues are visible only in the **Python Console**
(`Extensions > CellAPpose > Python Console`), because Appose reserves stdout for
its protocol and routes diagnostics there. The Java side logs to `Help > Show
log...`.

| Symptom | Likely cause | Fix |
|---|---|---|
| Run appears to hang on first use, nothing happens | The Appose env for the chosen family has not been built; the first Run silently triggers a multi-GB, multi-minute build | Build it first via `Manage Environments...` and wait for the ready tag; watch the Python Console for build progress. The Run dialog's amber `[!]` banner warns you when a Run will build. |
| "Environment not built" / env-missing on Run | The selected family's env (`cellappose-cp3` or `cellappose-cp4`) was never built; the other family's env does not satisfy this one | Build the matching family's env via `Manage Environments...`. Each family has its **own** env. |
| Built CP4 but CP3 says "not built" (or vice versa) | The two families build separate envs lazily; building one does not build the other | Switch to the family you want and build it. Check `~/.local/share/appose/` for `cellappose-cp3` vs `cellappose-cp4`. |
| Very slow first detection even after the env build | Cellpose is downloading model weights on first inference (network) | Wait it out once; weights cache under the Cellpose user dir and later runs are fast. Watch the Python Console for the download line. |
| GPU not used / runs on CPU (slow) | No CUDA/MPS GPU detected, or a CPU-only PyTorch resolved into the env | Expected fallback, not an error. Confirm `torch.cuda.is_available()` in the Python Console; on NVIDIA verify drivers and that the GPU pixi variant resolved. Apple Silicon uses MPS. |
| First task hangs forever **on Windows**, GPU idle | numpy imported after the Appose stdin reader thread started (Windows-only deadlock) | The `*_init.py` / `verify_env.py` pre-import numpy to avoid this; if you still hit it, file an issue -- it means an init script regressed. |
| `KeyError: '<name>'` in the Python Console on Run | A bare-globals input the vendored `cp3.py`/`cp4.py` reads was not supplied | The Java parameter map should supply every key. File an issue with the missing key; see `docs/APPOSE_CELLPOSE_PORTING.md` (the bare-globals contract). |
| `KeyError: 'rsize'` on a shared-memory transfer | appose-java (Maven) vs appose-python (pixi) wire-format mismatch | Keep the two Appose versions compatible; we pin `org.apposed:appose:0.11.0` against a matching pixi `appose >=0.10.1`. |
| Task `FAILED` with an empty error | A stray `print()` in Python corrupted the IPC protocol | Remove stdout writes from any edited script; route diagnostics to stderr. Check the Python Console for the real message. |
| "thread death" in the Python Console | A prior task's worker-thread cleanup event is misattributed to the next task's UUID (known Appose race) | The service retries on thread death; if it lands repeatedly on the same task, file an issue with the console log. |
| Env build fails / hangs (esp. Windows) | Pixi cache dir not writable, or a firewall blocking conda-forge / PyPI / the PyTorch index | Re-run the build from `Manage Environments...`; verify the pixi cache dir is writable and conda-forge / `download.pytorch.org` are reachable. |
| Where do I even see Python errors? | Appose reserves stdout for IPC; tracebacks go to the console, not the QuPath log | Open `Extensions > CellAPpose > Python Console`. Also check `Help > Show log...` for the Java side. |
| Windows crash on a non-ASCII name | A class / annotation / path name contains non-ASCII the logger cannot encode (cp1252) | Logs are ASCII-only by design; rename the offending item to ASCII. The analysis does not care, but the log line mentioning it can crash. |
| Wrong family ran / unexpected model | The Model family radio was on the other family, or a stale built-in model was selected | Check the **Model family** radio and (CP3) the **Built-in model** dropdown before Run; the dialog persists the last family used. |
| Objects duplicated / doubled along tile seams | Seam merge did not drop a boundary fragment (overlap too small) | Increase tile overlap (~2x diameter). If it persists, file an issue with a screenshot of the seam -- this is the novel `TileMerger` path. |

</details>

<details>
<summary><strong>8b. Planned (v1.1)</strong></summary>

The following are intentionally deferred to a future release:

- **Cancel / Stop for long runs.** A running detection currently cannot be cancelled
  mid-run; closing QuPath is the only way to stop a very long run.
- **Run across a whole project (batch).** Detection runs on the current image only;
  batching across project images is not yet built (scripting can loop today).
- **Custom model + channel picker in the dialog.** The CP3 custom-model path and the
  CP4 channel subset are available via the `Cellpose2D.builder` scripting API today
  (see section 6); surfacing them as dialog controls is planned.
- **Documentation screenshots** of the dialogs and a worked seam example.
- **Per-tile error-resilience tuning** (e.g. continue-on-tile-failure policy).

</details>

<details>
<summary><strong>9. References</strong></summary>

Citations for the methods and external sources named in this guide and the README,
per the project's cite-sources-in-user-facing-docs policy.

- **Cellpose.** Stringer, C., Wang, T., Michaelos, M., Pachitariu, M. (2021).
  *Cellpose: a generalist algorithm for cellular segmentation.* Nature Methods
  18, 100-106.
- **Cellpose-SAM / Cellpose 4.** The SAM-based generalist Cellpose model (the CP4
  family).
- **QuPath.** Bankhead, P., et al. (2017). *QuPath: Open source software for
  digital pathology image analysis.* Scientific Reports 7, 16878. (GPL-licensed
  host application.)
- **BIOP `qupath-extension-cellpose`** --
  <https://github.com/BIOP/qupath-extension-cellpose> (Apache-2.0). The QuPath-side
  tiling and tile-seam merge architecture is inspired by this project.
- **`Image-Analysis-Hub/imglib2-cellpose`** --
  <https://github.com/Image-Analysis-Hub/imglib2-cellpose> (BSD-3-Clause). The
  Python segmentation scripts are vendored from this project.

See also [`docs/APPOSE_CELLPOSE_PORTING.md`](../docs/APPOSE_CELLPOSE_PORTING.md)
for the developer-facing backend contract, the exact vendored-file list, and the
local edits, and the shipped
[`THIRD_PARTY.md`](../src/main/resources/qupath/ext/cellappose/THIRD_PARTY.md)
notice.

</details>
