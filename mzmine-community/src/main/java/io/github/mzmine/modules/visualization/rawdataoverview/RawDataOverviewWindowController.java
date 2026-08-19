/*
 * Copyright (c) 2004-2022 The MZmine Development Team
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package io.github.mzmine.modules.visualization.rawdataoverview;

import io.github.mzmine.datamodel.RawDataFile;
import io.github.mzmine.datamodel.Scan;
import io.github.mzmine.datamodel.features.FeatureList;
import io.github.mzmine.datamodel.features.FeatureListRow;
import io.github.mzmine.gui.mainwindow.NistMatchesTab;
import io.github.mzmine.gui.chartbasics.ChartLogicsFX;
import io.github.mzmine.gui.chartbasics.graphicsexport.GraphicsExportModule;
import io.github.mzmine.gui.chartbasics.graphicsexport.GraphicsExportParameters;
import io.github.mzmine.main.MZmineCore;
import io.github.mzmine.modules.dataprocessing.id_nist.NistMsSearchModule;
import io.github.mzmine.modules.dataprocessing.id_nist.NistMsSearchParameters;
import io.github.mzmine.modules.dataprocessing.id_nist.NistMsSearchTask;
import io.github.mzmine.modules.dataprocessing.id_nist.NistMatchUtils;
import io.github.mzmine.parameters.ParameterSet;
import io.github.mzmine.project.ProjectService;
import io.github.mzmine.taskcontrol.TaskStatus;
import io.github.mzmine.modules.visualization.chromatogram.TICDataSet;
import io.github.mzmine.modules.visualization.chromatogram.TICPlotType;
import io.github.mzmine.modules.visualization.chromatogramandspectra.ChromatogramAndSpectraVisualizer;
import io.github.mzmine.project.impl.ImagingRawDataFileImpl;
import io.github.mzmine.javafx.dialogs.DialogLoggerUtil;
import java.io.IOException;
import java.awt.BasicStroke;
import java.awt.Font;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.collections.FXCollections;
import javafx.collections.ObservableMap;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Point2D;
import javafx.scene.control.SplitPane;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.input.ContextMenuEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.util.Duration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jfree.chart.annotations.XYTextAnnotation;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.ui.TextAnchor;

/*
 * Raw data overview window controller class
 *
 * @author Ansgar Korf (ansgar.korf@uni-muenster)
 */
public class RawDataOverviewWindowController {

  public static final Logger logger = Logger.getLogger(
      RawDataOverviewWindowController.class.getName());

  private boolean initialized = false;

  private final ObservableMap<RawDataFile, RawDataFileInfoPaneController> rawDataFilesAndControllers = FXCollections.observableMap(
      new HashMap<>());
  private final ObservableMap<RawDataFile, Tab> rawDataFilesAndTabs = FXCollections.observableMap(
      new HashMap<>());
  private final List<RenderedNistMatch> renderedNistMatches = new ArrayList<>();
  /** How far a feature row may sit from a clicked peak and still receive its NIST hits. */
  private static final double EXPLICIT_SEARCH_RT_TOLERANCE = 0.15d;
  private Label nistLabelFilterStatus;
  private CheckMenuItem showNistLabelsMenuItem;
  private CheckBox showNistLabelsCheckBox;
  private RawDataFile nistLabelRawFile;
  private RawDataFile selectedChromatogramRawFile;
  private double selectedChromatogramRt = Double.NaN;
  private double contextMenuRetentionTime = Double.NaN;


  @FXML
  private ChromatogramAndSpectraVisualizer visualizer;

  @FXML
  private TabPane tpRawDataInfo;

  @FXML
  private BorderPane pnMaster;

  @FXML
  private SplitPane pnMain;

  public void initialize() {

    // Register straight away. Registration says "this window shows charts", not "the user just
    // clicked something", and doing it in event handlers meant a change made in the NIST matches
    // table repainted nothing until the user happened to click the chart first.
    NistChartLabelState.register(this);

    // Selecting a scan already updates the spectrum below. The automatic filled EIC obscures the
    // raw chromatogram in this overview and can be mistaken for converted source data.
    visualizer.setAutomaticEicOverlayEnabled(false);
    installNistMatchLabelToggle();
    installChromatogramQuickControls();
    installExplicitNistSearch();
    addChromatogramSelectedScanListener();

    // Reopening a file should look like it did when it was left. setShowNistMatchLabels also syncs
    // the checkbox and menu item, so this restores the controls as well as the labels. Deferred a
    // pulse because the chart has not been laid out yet at this point.
    if (NistChartLabelState.isShowLabels()) {
      Platform.runLater(() -> setShowNistMatchLabels(true));
    }

    initialized = true;
  }

  private void installExplicitNistSearch() {
    visualizer.getChromPlot().addEventHandler(ContextMenuEvent.CONTEXT_MENU_REQUESTED, event -> {
      final Point2D local = visualizer.getChromPlot().sceneToLocal(event.getSceneX(),
          event.getSceneY());
      final java.awt.geom.Point2D plotPoint = ChartLogicsFX.mouseXYToPlotXY(
          visualizer.getChromPlot(),
          local.getX(), local.getY());
      contextMenuRetentionTime = plotPoint == null ? Double.NaN : plotPoint.getX();
    });

    final MenuItem explicitSearch = new MenuItem("Run NIST search at clicked peak apex");
    explicitSearch.setOnAction(event -> runNistSearchAtContextRetentionTime());
    visualizer.getChromPlot().getContextMenu().getItems().add(explicitSearch);

    final MenuItem explainDetection = new MenuItem("Why was this peak not detected?");
    explainDetection.setOnAction(event -> explainDetectionAtContextRetentionTime());
    visualizer.getChromPlot().getContextMenu().getItems().add(explainDetection);
  }

  private void runNistSearchAtContextRetentionTime() {
    final RawDataFile rawDataFile = selectedChromatogramRawFile != null
        ? selectedChromatogramRawFile : visualizer.getSelectedRawDataFile();
    // A context-menu search is defined by the mouse click, not by a potentially stale dashed
    // cursor. Snap that click to the highest chromatogram point within the local peak window.
    final double initialRetentionTime = Double.isFinite(contextMenuRetentionTime)
        ? contextMenuRetentionTime : selectedChromatogramRt;
    final ChromatogramPoint clickedPeak = rawDataFile == null
        || !Double.isFinite(initialRetentionTime) ? null
        : findChromatogramPeak(rawDataFile, initialRetentionTime);
    final double requestedRetentionTime = clickedPeak == null ? initialRetentionTime
        : clickedPeak.retentionTime();
    logger.info(() -> "Explicit NIST request: file=%s, selected RT=%s, clicked RT=%s"
        .formatted(rawDataFile == null ? "none" : rawDataFile.getName(), selectedChromatogramRt,
            contextMenuRetentionTime));
    if (rawDataFile == null || !Double.isFinite(requestedRetentionTime)) {
      logger.warning("Explicit NIST request rejected because no raw-data scan/RT is selected");
      MZmineCore.getDesktop().displayErrorMessage(
          "Select a scan in the raw-data chromatogram, then right-click and run the NIST search.");
      return;
    }
    logger.info(() -> "Explicit NIST click snapped to local peak apex: clicked RT=%.3f, apex RT=%.3f"
        .formatted(initialRetentionTime, requestedRetentionTime));

    // A clicked peak is a question about a retention time, so every loaded file is searched at
    // that time, and a row is created for any file that has none there.
    final List<String> outOfRange = new ArrayList<>();
    final List<ClickedPeakNistTargets.Target> targets = ClickedPeakNistTargets.resolve(
        requestedRetentionTime, EXPLICIT_SEARCH_RT_TOLERANCE, outOfRange);
    if (targets.isEmpty()) {
      logger.warning(() -> "Explicit NIST request found nowhere to store hits at RT %.3f"
          .formatted(requestedRetentionTime));
      MZmineCore.getDesktop().displayErrorMessage(
          explainMissingFeatureRow(rawDataFile, requestedRetentionTime, outOfRange));
      return;
    }

    final ParameterSet parameters = MZmineCore.getConfiguration()
        .getModuleParameters(NistMsSearchModule.class).cloneParameterSet();
    final List<String> errors = new ArrayList<>();
    // Rows and lists are already resolved; only validate settings needed to execute the searches.
    // Generic batch validation must not demand another feature-list selection.
    if (!((NistMsSearchParameters) parameters).checkParameterValuesForExplicitSearch(errors)) {
      MZmineCore.getDesktop().displayErrorMessage(
          "NIST search settings are invalid:\n" + String.join("\n", errors));
      return;
    }

    final long createdRows = targets.stream().filter(ClickedPeakNistTargets.Target::created).count();
    logger.info(() -> "Starting explicit NIST search at RT %.3f across %d file(s), %d row(s) created"
        .formatted(requestedRetentionTime, targets.size(), createdRows));
    MZmineCore.getDesktop().setStatusBarText(
        "Queued NIST search at RT %.3f min across %d file(s)".formatted(requestedRetentionTime,
            targets.size()));

    final java.util.concurrent.atomic.AtomicInteger remaining =
        new java.util.concurrent.atomic.AtomicInteger(targets.size());
    final java.util.concurrent.atomic.AtomicInteger totalHits =
        new java.util.concurrent.atomic.AtomicInteger();
    final List<String> failures = java.util.Collections.synchronizedList(new ArrayList<>());

    for (ClickedPeakNistTargets.Target target : targets) {
      final NistMsSearchTask task = new NistMsSearchTask(target.row(), target.featureList(),
          parameters, Instant.now(), target.scan());
      task.addTaskStatusListener((changedTask, newStatus, oldStatus) -> {
        // Any terminal status completes this file. Counting only FINISHED and ERROR left the
        // run permanently unfinished when a task was cancelled, so the summary and the label
        // refresh never happened and the hits already stored stayed invisible.
        if (newStatus != TaskStatus.FINISHED && newStatus != TaskStatus.ERROR
            && newStatus != TaskStatus.CANCELED) {
          return;
        }
        if (newStatus == TaskStatus.ERROR) {
          failures.add(target.file().getName() + ": " + changedTask.getErrorMessage());
        } else if (newStatus == TaskStatus.FINISHED) {
          totalHits.addAndGet(task.getAddedHitCount());
        }
        if (remaining.decrementAndGet() > 0) {
          return;
        }
        // Report once, after every file has finished, rather than one dialog per file.
        final int hits = totalHits.get();
        Platform.runLater(() -> {
          refreshNistMatchLabels(rawDataFile, true);
          NistMatchesTab.selectMatchAt(rawDataFile, (float) requestedRetentionTime);
          NistMatchesTab.refresh();
          final StringBuilder message = new StringBuilder();
          message.append(hits == 0
              ? "No candidates passed the minimum match factor at RT %.3f min.".formatted(
              requestedRetentionTime)
              : "Stored %d candidate(s) at RT %.3f min across %d file(s).".formatted(hits,
                  requestedRetentionTime, targets.size()));
          if (createdRows > 0) {
            message.append(String.format(
                "%n%nCreated %d feature row(s) for files that had none at this time.",
                createdRows));
          }
          if (!failures.isEmpty()) {
            message.append(String.format("%n%nFailed for:%n"))
                .append(String.join(System.lineSeparator(), failures));
          }
          MZmineCore.getDesktop().displayMessage("NIST search complete", message.toString());
        });
      });
      final Thread searchThread = new Thread(task,
          "Explicit NIST search %.3f %s".formatted(requestedRetentionTime,
              target.file().getName()));
      searchThread.setDaemon(true);
      searchThread.start();
    }
  }

  /**
   * Explains why a clicked peak has no feature row to attach hits to.
   *
   * <p>The three reasons are very different to act on - no feature lists at all, none covering this
   * file, or one that is simply too far away - so they get different messages. A single "nothing
   * within 0.15 min" reads as a tolerance problem even when the real answer is that feature
   * detection has not been run.</p>
   */
  private String explainMissingFeatureRow(RawDataFile rawDataFile, double retentionTime,
      List<String> outOfRange) {
    final var featureLists = ProjectService.getProjectManager().getCurrentProject()
        .getCurrentFeatureLists();
    if (featureLists.isEmpty()) {
      return """
          There are no feature lists in this project, so there is nowhere to store NIST hits.

          Run feature detection first. For GC-EI data that usually means mass detection, then           chromatogram building, then deconvolution. You can then right-click a peak again.""";
    }
    if (!outOfRange.isEmpty()) {
      return ("No loaded raw file has a scan within %.2f min of RT %.3f, so there is nothing to "
          + "search.%n%nOut of range: %s").formatted(EXPLICIT_SEARCH_RT_TOLERANCE, retentionTime,
          String.join(", ", outOfRange));
    }
    // Scans were found, so the failure was in building somewhere to store the hits.
    return ("Scans were found near RT %.3f, but no feature row could be created to hold NIST hits "
        + "for any file.%n%nSee the mzmine log for the reason each file was skipped.").formatted(
        retentionTime);
  }

  /**
   * Finds the feature row closest to a clicked peak.
   *
   * <p>A row's retention time for this file is preferred, but a row that carries no feature for it
   * still counts, using the row average. Requiring a per-file feature made the search fail on
   * aligned feature lists, and whenever the list referenced a different {@link RawDataFile}
   * instance than the one on screen - which happens when data is re-imported. In both cases the row
   * is a perfectly good place to store hits, and refusing it left users staring at an obvious peak
   * being told nothing was found.</p>
   */
  /** Reports which detection setting excluded the clicked peak, without changing anything. */
  private void explainDetectionAtContextRetentionTime() {
    final RawDataFile rawDataFile = selectedChromatogramRawFile != null ? selectedChromatogramRawFile
        : visualizer.getSelectedRawDataFile();
    final double clickedRt = Double.isFinite(contextMenuRetentionTime) ? contextMenuRetentionTime
        : selectedChromatogramRt;
    if (rawDataFile == null || !Double.isFinite(clickedRt)) {
      MZmineCore.getDesktop().displayErrorMessage(
          "Right-click on the chromatogram to choose a peak first.");
      return;
    }

    final PeakDetectionDiagnostic.PeakShape shape = measurePeak(rawDataFile, clickedRt);
    if (shape == null) {
      MZmineCore.getDesktop().displayErrorMessage(
          "No chromatogram points were found near RT %.3f min.".formatted(clickedRt));
      return;
    }
    final String trace = visualizer.getChromPlot().getPlotType() == TICPlotType.BASEPEAK
        ? "base peak chromatogram" : "TIC";
    // Deliberately not a modal dialog: the finding is meant to be read while editing the batch.
    PeakDiagnosisWindow.show(PeakDetectionDiagnostic.report(rawDataFile, shape, trace));
  }

  /**
   * Measures the peak around {@code retentionTime} on the displayed trace by walking outwards from
   * the apex until the signal stops falling, which is the same idea a resolver uses to find peak
   * edges.
   */
  private PeakDetectionDiagnostic.@Nullable PeakShape measurePeak(RawDataFile rawDataFile,
      double retentionTime) {
    final var plot = visualizer.getChromPlot().getXYPlot();
    for (int datasetIndex = 0; datasetIndex < plot.getDatasetCount(); datasetIndex++) {
      if (!(plot.getDataset(datasetIndex) instanceof TICDataSet dataset)
          || !rawDataFile.equals(dataset.getDataFile())) {
        continue;
      }
      final int count = dataset.getItemCount(0);
      if (count == 0) {
        continue;
      }

      int apex = 0;
      double apexDistance = Double.POSITIVE_INFINITY;
      double traceMaximum = 0d;
      for (int item = 0; item < count; item++) {
        final double distance = Math.abs(dataset.getXValue(0, item) - retentionTime);
        if (distance < apexDistance) {
          apexDistance = distance;
          apex = item;
        }
        traceMaximum = Math.max(traceMaximum, dataset.getYValue(0, item));
      }
      // Climb to the local maximum so a click on a peak flank still measures the peak.
      while (apex + 1 < count && dataset.getYValue(0, apex + 1) > dataset.getYValue(0, apex)) {
        apex++;
      }
      while (apex > 0 && dataset.getYValue(0, apex - 1) > dataset.getYValue(0, apex)) {
        apex--;
      }

      int left = apex;
      while (left > 0 && dataset.getYValue(0, left - 1) < dataset.getYValue(0, left)) {
        left--;
      }
      int right = apex;
      while (right + 1 < count && dataset.getYValue(0, right + 1) < dataset.getYValue(0, right)) {
        right++;
      }

      final double apexIntensity = dataset.getYValue(0, apex);
      final double edge = Math.max(1e-12,
          Math.max(dataset.getYValue(0, left), dataset.getYValue(0, right)));
      return new PeakDetectionDiagnostic.PeakShape(dataset.getXValue(0, apex), apexIntensity,
          right - left + 1, dataset.getXValue(0, right) - dataset.getXValue(0, left),
          apexIntensity / edge, traceMaximum == 0d ? 0d : apexIntensity / traceMaximum);
    }
    return null;
  }

  private NistRowTarget findClosestFeatureRow(RawDataFile rawDataFile, double retentionTime) {
    NistRowTarget closest = null;
    for (FeatureList featureList : ProjectService.getProjectManager().getCurrentProject()
        .getCurrentFeatureLists()) {
      for (FeatureListRow row : featureList.getRows()) {
        final Double rowRt = featureRetentionTime(row, rawDataFile);
        if (rowRt == null) {
          continue;
        }
        final double distance = Math.abs(rowRt - retentionTime);
        if (closest == null || distance < closest.distanceMinutes()) {
          closest = new NistRowTarget(featureList, row, distance);
        }
      }
    }
    return closest;
  }

  /** This file's retention time for the row, falling back to the row average. */
  private static @Nullable Double featureRetentionTime(FeatureListRow row,
      RawDataFile rawDataFile) {
    final var feature = row.getFeature(rawDataFile);
    if (feature != null && feature.getRT() != null) {
      return feature.getRT().doubleValue();
    }
    final Float averageRt = row.getAverageRT();
    return averageRt == null ? null : averageRt.doubleValue();
  }

  private void installNistMatchLabelToggle() {
    showNistLabelsMenuItem = new CheckMenuItem("Show NIST match labels");
    showNistLabelsMenuItem.setSelected(false);
    showNistLabelsMenuItem.setOnAction(
        event -> setShowNistMatchLabels(showNistLabelsMenuItem.isSelected()));
    visualizer.getChromPlot().getContextMenu().getItems().add(new SeparatorMenuItem());
    visualizer.getChromPlot().getContextMenu().getItems().add(showNistLabelsMenuItem);
  }

  private void setShowNistMatchLabels(boolean visible) {
      NistChartLabelState.setShowLabels(visible);
      if (showNistLabelsMenuItem != null && showNistLabelsMenuItem.isSelected() != visible) {
        showNistLabelsMenuItem.setSelected(visible);
      }
      if (showNistLabelsCheckBox != null && showNistLabelsCheckBox.isSelected() != visible) {
        showNistLabelsCheckBox.setSelected(visible);
      }
      final var currentPosition = visualizer.getChromPosition();
      if (currentPosition != null && currentPosition.getDataFile() != null) {
        selectedChromatogramRawFile = currentPosition.getDataFile();
        selectedChromatogramRt = currentPosition.getRetentionTime();
        nistLabelRawFile = selectedChromatogramRawFile;
        NistMatchesTab.selectMatchAt(selectedChromatogramRawFile, selectedChromatogramRt);
      } else if (nistLabelRawFile == null) {
        // Raw Data Overview may already show a file before the user has clicked a scan. Resolve
        // that visible file now so enabling labels does not depend on a later selection event.
        nistLabelRawFile = selectedChromatogramRawFile != null ? selectedChromatogramRawFile
            : visualizer.getRawDataFiles().stream().findFirst().orElse(null);
      }
      if (visualizer.getChromPlot().getXYPlot().getRangeAxis() instanceof NumberAxis axis) {
        axis.setUpperMargin(NistChartLabelState.isShowLabels() ? 0.40d : 0.05d);
        axis.setAutoRange(true);
      }
      refreshNistMatchLabels(nistLabelRawFile, true);
      highlightNistMatch(selectedChromatogramRt);
      // Changing the axis margin schedules a JavaFX chart redraw. Re-apply annotations on the
      // following pulse and draw the canvas explicitly so the user does not need to click the
      // chart before newly enabled labels become visible.
      Platform.runLater(() -> {
        refreshNistMatchLabels(nistLabelRawFile, true);
        highlightNistMatch(selectedChromatogramRt);
        visualizer.getChromPlot().getCanvas().draw();
        visualizer.getChromPlot().requestLayout();
      });
      // The accordion and newly visible label headroom can complete layout on the next pulse.
      // Repaint once more after that layout instead of waiting for a chart click to trigger it.
      final PauseTransition repaintAfterLayout = new PauseTransition(Duration.millis(75));
      repaintAfterLayout.setOnFinished(event -> {
        refreshNistMatchLabels(nistLabelRawFile, true);
        visualizer.getChromPlot().getChart().fireChartChanged();
        visualizer.getChromPlot().getCanvas().draw();
      });
      repaintAfterLayout.play();
  }

  private void installChromatogramQuickControls() {
    final ParameterSet nistParameters = MZmineCore.getConfiguration()
        .getModuleParameters(NistMsSearchModule.class);
    NistChartLabelState.initialiseFiltersOnce(
        nistParameters.getValue(NistMsSearchParameters.MIN_MATCH_FACTOR));

    showNistLabelsCheckBox = new CheckBox("NIST labels");
    showNistLabelsCheckBox.setTooltip(new Tooltip("Show the best stored NIST match at each RT."));
    showNistLabelsCheckBox.setOnAction(
        event -> setShowNistMatchLabels(showNistLabelsCheckBox.isSelected()));

    final ToggleButton orientation = new ToggleButton("NIST: Vertical");
    orientation.setTooltip(
        new Tooltip("Switch the default orientation of NIST labels on this chart."));
    orientation.setSelected(NistChartLabelState.isDefaultHorizontal());
    orientation.setOnAction(event -> {
      final boolean horizontal = orientation.isSelected();
      orientation.setText(horizontal ? "NIST: Horizontal" : "NIST: Vertical");
      // Repaints every open Raw Data Overview window, not just this one.
      NistChartLabelState.setDefaultHorizontal(horizontal);
    });

    final Spinner<Integer> fmf = new Spinner<>(0, 999, NistChartLabelState.getMinimumFmf(), 25);
    fmf.setEditable(true);
    fmf.setPrefWidth(78);
    fmf.setTooltip(new Tooltip(
        "Minimum forward match factor shown on this chart. Type a value and press Enter."));
    installSpinnerCommit(fmf);
    fmf.valueProperty().addListener((obs, old, value) -> {
      NistChartLabelState.setMinimumFmf(value);
      refreshNistMatchLabels(nistLabelRawFile, true);
    });

    final Spinner<Integer> rmf = new Spinner<>(0, 999, NistChartLabelState.getMinimumRmf(), 25);
    rmf.setEditable(true);
    rmf.setPrefWidth(78);
    rmf.setTooltip(new Tooltip(
        "Minimum reverse match factor shown on this chart. Type a value and press Enter."));
    installSpinnerCommit(rmf);
    rmf.valueProperty().addListener((obs, old, value) -> {
      NistChartLabelState.setMinimumRmf(value);
      refreshNistMatchLabels(nistLabelRawFile, true);
    });

    final ChoiceBox<TICPlotType> display = new ChoiceBox<>(
        FXCollections.observableArrayList(TICPlotType.values()));
    display.setValue(visualizer.getPlotType());
    display.setTooltip(new Tooltip("Choose base-peak or total-ion chromatogram. XIC stays on the right."));
    display.valueProperty().addListener((obs, old, value) -> {
      if (value != null) {
        visualizer.setPlotType(value);
      }
    });

    final ColorPicker lineColor = new ColorPicker(javafx.scene.paint.Color.DODGERBLUE);
    lineColor.setPrefWidth(48);
    lineColor.setTooltip(new Tooltip("Set the visible chromatogram line color."));

    final Spinner<Double> lineWidth = new Spinner<>(0.5d, 8d, 1d, 0.5d);
    lineWidth.setEditable(true);
    lineWidth.setPrefWidth(72);
    lineWidth.setTooltip(new Tooltip("Chromatogram line width."));

    final Spinner<Integer> labelSize = new Spinner<>(8, 30, NistChartLabelState.getLabelFontSize(), 1);
    labelSize.setEditable(true);
    labelSize.setPrefWidth(68);
    labelSize.setTooltip(new Tooltip("Font size for NIST names and numeric apex labels."));

    final Runnable applyChartStyle = () -> {
      final javafx.scene.paint.Color fx = lineColor.getValue();
      final java.awt.Color awt = new java.awt.Color((float) fx.getRed(), (float) fx.getGreen(),
          (float) fx.getBlue(), (float) fx.getOpacity());
      visualizer.getChromPlot().setTicLineStyle(awt, lineWidth.getValue());
      NistChartLabelState.setLabelFontSize(labelSize.getValue());
      visualizer.getChromPlot().setTicItemLabelFontSize(NistChartLabelState.getLabelFontSize());
      refreshNistMatchLabels(nistLabelRawFile, true);
    };
    lineColor.valueProperty().addListener((obs, old, value) -> applyChartStyle.run());
    lineWidth.valueProperty().addListener((obs, old, value) -> applyChartStyle.run());
    labelSize.valueProperty().addListener((obs, old, value) -> applyChartStyle.run());

    final Button reset = new Button("Fit");
    reset.setTooltip(new Tooltip("Reset zoom and fit the complete chromatogram from zero."));
    reset.setOnAction(event -> visualizer.getChromPlot().resetZoomToData());

    final Button export = new Button("Export");
    export.setTooltip(new Tooltip("Open graphics export for this chromatogram."));
    export.setOnAction(event -> {
      final GraphicsExportParameters params = (GraphicsExportParameters) MZmineCore
          .getConfiguration().getModuleParameters(GraphicsExportModule.class);
      MZmineCore.getModuleInstance(GraphicsExportModule.class)
          .openDialog(visualizer.getChromPlot().getChart(), params);
    });

    nistLabelFilterStatus = new Label("NIST: off");
    nistLabelFilterStatus.setTooltip(
        new Tooltip("Number of stored NIST peak labels passing the FMF/RMF filters."));

    final FlowPane controls = new FlowPane(8, 5, showNistLabelsCheckBox, orientation,
        new Label("FMF"), fmf, new Label("RMF"), rmf, new Label("View"), display,
        new Label("Line"), lineColor, new Label("Width"), lineWidth, new Label("Labels"),
        labelSize, nistLabelFilterStatus, reset, export);
    controls.setAlignment(Pos.CENTER_LEFT);
    controls.setPadding(new Insets(4, 0, 4, 6));
    visualizer.setChromatogramToolbarLeft(controls);
  }

  private static void installSpinnerCommit(Spinner<Integer> spinner) {
    final Runnable commit = () -> {
      try {
        final int parsed = Integer.parseInt(spinner.getEditor().getText().trim());
        spinner.getValueFactory().setValue(Math.max(0, Math.min(999, parsed)));
      } catch (NumberFormatException ignored) {
        spinner.getEditor().setText(String.valueOf(spinner.getValue()));
      }
    };
    spinner.getEditor().setOnAction(event -> commit.run());
    spinner.getEditor().focusedProperty().addListener((obs, wasFocused, focused) -> {
      if (!focused) {
        commit.run();
      }
    });
  }

  private void refreshNistMatchLabels(RawDataFile rawDataFile, boolean force) {
    if (!force && rawDataFile == nistLabelRawFile) {
      return;
    }
    nistLabelRawFile = rawDataFile;
    final var plot = visualizer.getChromPlot().getXYPlot();
    final boolean notify = plot.isNotify();
    plot.setNotify(false);
    renderedNistMatches.forEach(rendered -> plot.removeAnnotation(rendered.annotation(), false));
    renderedNistMatches.clear();
    int visibleLabelCount = 0;

    if (NistChartLabelState.isShowLabels() && rawDataFile != null) {
      final List<LabeledNistPeak> labeledPeaks = NistMatchUtils.findBestMatches(rawDataFile).stream()
          .map(result -> {
            final NistMatchUtils.NistMatch selected = getSelectedNistChartMatch(rawDataFile,
                result.retentionTime());
            return selected == null ? result : selected;
          })
          .filter(result -> result.forwardMatchFactor() >= NistChartLabelState.getMinimumFmf()
              && result.reverseMatchFactor() >= NistChartLabelState.getMinimumRmf())
          .filter(result -> isNistMatchLabelVisible(rawDataFile, result.retentionTime()))
          .map(result -> new LabeledNistPeak(result,
              findChromatogramPeak(rawDataFile, result.retentionTime()))).toList();
      visibleLabelCount = labeledPeaks.size();
      // Lift each name clear of the trace so it does not sit on top of MZmine's numeric apex label.
      final double labelOffset = plot.getRangeAxis().getRange().getLength() * 0.035d;

      for (LabeledNistPeak labeled : labeledPeaks) {
        final var result = labeled.result();
        final ChromatogramPoint peak = labeled.peak();
        final boolean horizontal = isNistMatchLabelHorizontal(rawDataFile,
            result.retentionTime());
        final XYTextAnnotation annotation = new XYTextAnnotation(result.compoundName(),
            peak.retentionTime(), peak.intensity() + labelOffset);
        annotation.setRotationAngle(horizontal ? 0d : -Math.PI / 2d);
        // A vertical label grows upward from its anchor instead of straddling the plot line.
        final TextAnchor anchor = horizontal ? TextAnchor.BOTTOM_CENTER : TextAnchor.CENTER_LEFT;
        annotation.setTextAnchor(anchor);
        annotation.setRotationAnchor(anchor);
        annotation.setPaint(rawDataFile.getColorAWT());
        annotation.setFont(new Font(Font.SANS_SERIF, Font.BOLD, NistChartLabelState.getLabelFontSize()));
        plot.addAnnotation(annotation, false);
        renderedNistMatches.add(new RenderedNistMatch(result.retentionTime(), annotation));
      }
    }
    if (nistLabelFilterStatus != null) {
      nistLabelFilterStatus.setText(NistChartLabelState.isShowLabels() ? "NIST: " + visibleLabelCount : "NIST: off");
    }
    plot.setNotify(notify);
    if (notify) {
      visualizer.getChromPlot().getChart().fireChartChanged();
    }
  }

  private ChromatogramPoint findChromatogramPeak(RawDataFile rawDataFile, double retentionTime) {
    final var plot = visualizer.getChromPlot().getXYPlot();
    double nearestDistance = Double.POSITIVE_INFINITY;
    double nearestRt = retentionTime;
    double nearestIntensity = plot.getRangeAxis().getRange().getUpperBound() * 0.1d;
    for (int datasetIndex = 0; datasetIndex < plot.getDatasetCount(); datasetIndex++) {
      if (!(plot.getDataset(datasetIndex) instanceof TICDataSet dataset)
          || !rawDataFile.equals(dataset.getDataFile())) {
        continue;
      }
      for (int item = 0; item < dataset.getItemCount(0); item++) {
        final double itemRt = dataset.getXValue(0, item);
        final double itemIntensity = dataset.getYValue(0, item);
        final double distance = Math.abs(itemRt - retentionTime);
        if (distance < nearestDistance) {
          nearestDistance = distance;
          nearestRt = itemRt;
          nearestIntensity = itemIntensity;
        }
      }
    }
    // The NIST result already carries the sample-specific feature RT. Do not search for the
    // tallest point in a broad surrounding window: that can cross a valley and move two nearby
    // compounds onto the same peak.
    return new ChromatogramPoint(nearestRt, nearestIntensity);
  }

  private void highlightNistMatch(double retentionTime) {
    if (!NistChartLabelState.isShowLabels() || !Double.isFinite(retentionTime)) {
      return;
    }
    RenderedNistMatch closest = renderedNistMatches.stream().min(java.util.Comparator.comparingDouble(
        rendered -> Math.abs(rendered.retentionTime() - retentionTime))).orElse(null);
    if (closest != null && Math.abs(closest.retentionTime() - retentionTime) > 0.15d) {
      closest = null;
    }
    for (RenderedNistMatch rendered : renderedNistMatches) {
      final boolean selected = rendered == closest;
      rendered.annotation().setFont(
          new Font(Font.SANS_SERIF, Font.BOLD,
              selected ? NistChartLabelState.getLabelFontSize() + 3 : NistChartLabelState.getLabelFontSize()));
      rendered.annotation().setBackgroundPaint(
          selected ? new java.awt.Color(255, 245, 160, 220) : null);
      rendered.annotation().setOutlineVisible(selected);
      rendered.annotation().setOutlinePaint(selected ? java.awt.Color.DARK_GRAY : null);
      rendered.annotation().setOutlineStroke(new BasicStroke(1f));
    }
    visualizer.getChromPlot().getChart().fireChartChanged();
  }

  /**
   * Repaints this window's labels if it currently shows {@code rawDataFile}. Called by
   * {@link NistChartLabelState} after any shared-state change, so every open Raw Data Overview
   * window stays in sync rather than only the one that happened to register last.
   *
   * @param rawDataFile the file whose state changed, or {@code null} for a global change
   */
  void repaintNistLabelsFor(@Nullable RawDataFile rawDataFile) {
    // Labels may not have been drawn yet, in which case there is no remembered file. Fall back to
    // whatever this window is showing so the first change made from the table still lands.
    RawDataFile target = nistLabelRawFile;
    if (target == null) {
      target = selectedChromatogramRawFile != null ? selectedChromatogramRawFile
          : visualizer.getSelectedRawDataFile();
    }
    if (target == null || (rawDataFile != null && !rawDataFile.equals(target))) {
      return;
    }
    refreshNistMatchLabels(target, true);
    highlightNistMatch(selectedChromatogramRt);
  }

  public boolean isNistMatchLabelVisible(RawDataFile rawDataFile, double retentionTime) {
    return NistChartLabelState.isLabelVisible(rawDataFile, retentionTime);
  }

  public void setNistMatchLabelVisible(RawDataFile rawDataFile, double retentionTime,
      boolean visible) {
    NistChartLabelState.setLabelVisible(rawDataFile, retentionTime, visible);
  }

  public boolean isNistMatchLabelHorizontal(RawDataFile rawDataFile, double retentionTime) {
    return NistChartLabelState.isLabelHorizontal(rawDataFile, retentionTime);
  }

  public void setNistMatchLabelHorizontal(RawDataFile rawDataFile, double retentionTime,
      boolean horizontal) {
    NistChartLabelState.setLabelHorizontal(rawDataFile, retentionTime, horizontal);
  }

  public NistMatchUtils.NistMatch getSelectedNistChartMatch(RawDataFile rawDataFile,
      double retentionTime) {
    return NistChartLabelState.getSelectedMatch(rawDataFile, retentionTime);
  }

  public void setSelectedNistChartMatch(RawDataFile rawDataFile, double retentionTime,
      NistMatchUtils.NistMatch match) {
    NistChartLabelState.setSelectedMatch(rawDataFile, retentionTime, match);
  }

  private record ChromatogramPoint(double retentionTime, double intensity) {
  }

  private record LabeledNistPeak(NistMatchUtils.NistMatch result, ChromatogramPoint peak) {
  }

  private record RenderedNistMatch(double retentionTime, XYTextAnnotation annotation) {
  }

  private record NistRowTarget(FeatureList featureList, FeatureListRow row,
                               double distanceMinutes) {
  }

  /**
   * Sets the raw data files to be displayed. Already present files are not removed to optimise
   * performance. This should be called over
   * {@link RawDataOverviewWindowController#addRawDataFileTab} if possible.
   * <p>
   * Only add LC-MS data sets, exclude imaging
   *
   * @param rawDataFiles
   */
  public void setRawDataFiles(Collection<RawDataFile> rawDataFiles) {
    if(rawDataFiles.size()>25) {
      boolean result = DialogLoggerUtil.showDialogYesNo("Raw data overview",
          "Visualizing %d data files at once might slow down MZmine, continue?".formatted(
              rawDataFiles.size()));

      if(!result) {
        // just visualize the first file if user selected false
        rawDataFiles = rawDataFiles.stream().findFirst().map(List::of).orElse(List.of());
      }
    }

    // remove files first
    List<RawDataFile> filesToProcess = new ArrayList<>();
    for (RawDataFile rawDataFile : rawDataFilesAndTabs.keySet()) {
      if (!rawDataFiles.contains(rawDataFile)) {
        filesToProcess.add(rawDataFile);
      }
    }
    filesToProcess.forEach(this::removeRawDataFile);

    // presence of file is checked in the add method
    rawDataFiles.forEach(r -> {
      if (!(r instanceof ImagingRawDataFileImpl)) {
        addRawDataFileTab(r);
      }
    });
    visualizer.setRawDataFiles(rawDataFiles);
  }

  /**
   * Adds a raw data file table to the tab.
   *
   * @param raw The raw dataFile
   */
  public void addRawDataFileTab(RawDataFile raw) {

    if (!initialized) {
      initialize();
    }
    if (rawDataFilesAndControllers.containsKey(raw)) {
      return;
    }

    try {
      FXMLLoader loader = new FXMLLoader(getClass().getResource("RawDataFileInfoPane.fxml"));
      BorderPane pane = loader.load();
      rawDataFilesAndControllers.put(raw, loader.getController());
      RawDataFileInfoPaneController con = rawDataFilesAndControllers.get(raw);
      con.getRawDataTableView().getSelectionModel().selectedItemProperty()
          // TODO: this clears the spectrum plot, somehow bind to mouse input, currenty it is just
          // slower than the thread
          .addListener(((obs, old, newValue) -> {
            if (newValue == null) {
              // this is the case it the table was not populated before.
              // in that case we just select the table.
              return;
            }
            visualizer.setFocusedScan(raw, newValue);
          }));

      Tab rawDataFileTab = new Tab(raw.getName());
      rawDataFileTab.setContent(pane);
      tpRawDataInfo.getTabs().add(rawDataFileTab);

      rawDataFileTab.selectedProperty().addListener((obs, o, n) -> {
        if (n) {
          con.populate(raw);
        }
      });

      rawDataFileTab.setOnClosed((e) -> {
        logger.fine("Removing raw data file " + raw.getName());
        removeRawDataFile(raw);
      });

      if (rawDataFileTab.selectedProperty().getValue()) {
        con.populate(raw);
      }

      rawDataFilesAndTabs.put(raw, rawDataFileTab);
    } catch (IOException e) {
      logger.log(Level.SEVERE, "Could not load RawDataFileInfoPane.fxml", e);
    }

    logger.fine("Added raw data file tab for " + raw.getName());
  }

  public void removeRawDataFile(RawDataFile raw) {
    visualizer.removeRawDataFile(raw);
    rawDataFilesAndControllers.remove(raw);
    Tab tab = rawDataFilesAndTabs.remove(raw);
    tpRawDataInfo.getTabs().remove(tab);
  }

  // plot update methods

  /**
   * Updates the selected row in the raw data table if the user clicks in the chromatogram plot.
   */
  private void addChromatogramSelectedScanListener() {

    visualizer.chromPositionProperty().addListener((observable, oldValue, pos) -> {
      RawDataFile selectedRawDataFile = pos.getDataFile();
      if (selectedRawDataFile == null || selectedRawDataFile instanceof ImagingRawDataFileImpl) {
        return;
      }
      selectedChromatogramRt = pos.getRetentionTime();
      selectedChromatogramRawFile = selectedRawDataFile;
      NistMatchesTab.selectMatchAt(selectedRawDataFile, selectedChromatogramRt);
      refreshNistMatchLabels(selectedRawDataFile, false);
      highlightNistMatch(selectedChromatogramRt);
      RawDataFileInfoPaneController con = rawDataFilesAndControllers.get(selectedRawDataFile);
      if (con == null) {
        logger.info("Cannot find controller for raw data file " + selectedRawDataFile.getName());
        return;
      }

      TableView<Scan> rawDataTableView = con.getRawDataTableView();
      tpRawDataInfo.getSelectionModel().select(rawDataFilesAndTabs.get(selectedRawDataFile));

      if (rawDataTableView.getItems() != null) {
        try {
          Scan scan = pos.getScan();
          rawDataTableView.getItems().stream().filter(item -> item.equals(scan)).findFirst()
              .ifPresent(item -> {
                rawDataTableView.getSelectionModel().select(item);
                rawDataTableView.getSelectionModel().focus(rawDataTableView.getItems().indexOf(item));
                if (!con.getVisibleRange().contains(rawDataTableView.getItems().indexOf(item))) {
                  rawDataTableView.scrollTo(item);
                }
              });
        } catch (Exception e) {
          e.getStackTrace();
        }
      }
    });

  }

  public RawDataFile getSelectedRawDataFile() {
    return visualizer.getSelectedRawDataFile();
  }

  @NotNull
  public Collection<RawDataFile> getRawDataFiles() {
    return visualizer.getRawDataFiles();
  }

}
