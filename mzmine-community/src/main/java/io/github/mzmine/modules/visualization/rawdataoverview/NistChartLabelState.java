/*
 * Copyright (c) 2004-2026 The mzmine Development Team
 * SPDX-License-Identifier: MIT
 */
package io.github.mzmine.modules.visualization.rawdataoverview;

import io.github.mzmine.datamodel.RawDataFile;
import io.github.mzmine.modules.dataprocessing.id_nist.NistMatchUtils;
import io.github.mzmine.modules.dataprocessing.id_nist.NistMatchUtils.NistMatch;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Per-peak display state for NIST chart labels, shared by every view that reads or writes it.
 *
 * <p>The NIST matches table in the main window and the chromatograms in any number of Raw Data
 * Overview windows all edit the same state. Keeping it here rather than inside one controller means
 * a getter and its setter always agree, even when no overview window is open, and that a change
 * made in one window repaints every other window showing the same raw file.</p>
 *
 * <p>State is keyed by raw file and matched by retention time within
 * {@link NistMatchUtils#isSameChartPeakRetentionTime(double, double)}, so it survives a feature
 * list being recomputed. All access happens on the JavaFX application thread.</p>
 */
public final class NistChartLabelState {

  private static final Map<RawDataFile, List<Double>> hiddenRetentionTimes = new HashMap<>();
  private static final Map<RawDataFile, List<Double>> horizontalRetentionTimes = new HashMap<>();
  private static final Map<RawDataFile, List<Double>> verticalRetentionTimes = new HashMap<>();
  private static final Map<RawDataFile, List<SelectedNistMatch>> selectedMatches = new HashMap<>();
  private static final List<WeakReference<RawDataOverviewWindowController>> controllers =
      new ArrayList<>();

  private static boolean defaultHorizontal = false;

  // Chart-wide label settings. These live here rather than on the window controller so they
  // survive closing and reopening a Raw Data Overview, and so two open windows agree. Previously
  // they were controller fields, which meant leaving a file and coming back silently reset the
  // labels to off and the filters to their defaults.
  private static boolean showLabels = false;
  private static int minimumFmf = 0;
  private static int minimumRmf = 0;
  private static int labelFontSize = 13;
  private static boolean filtersInitialised = false;

  private NistChartLabelState() {
  }

  /** Registers a chart controller to be repainted whenever the shared state changes. */
  public static void register(RawDataOverviewWindowController controller) {
    // Also prunes references to windows that have since been closed and collected.
    controllers.removeIf(reference -> reference.get() == null || reference.get() == controller);
    controllers.add(new WeakReference<>(controller));
  }

  /** Applies an action to every controller still alive, dropping collected references. */
  private static void forEachController(java.util.function.Consumer<RawDataOverviewWindowController> action) {
    for (Iterator<WeakReference<RawDataOverviewWindowController>> it = controllers.iterator();
        it.hasNext(); ) {
      final RawDataOverviewWindowController controller = it.next().get();
      if (controller == null) {
        it.remove();
      } else {
        action.accept(controller);
      }
    }
  }

  private static void repaint(@Nullable RawDataFile rawDataFile) {
    forEachController(controller -> controller.repaintNistLabelsFor(rawDataFile));
  }

  public static boolean isShowLabels() {
    return showLabels;
  }

  public static void setShowLabels(boolean visible) {
    showLabels = visible;
  }

  public static int getMinimumFmf() {
    return minimumFmf;
  }

  public static void setMinimumFmf(int value) {
    minimumFmf = value;
  }

  public static int getMinimumRmf() {
    return minimumRmf;
  }

  public static void setMinimumRmf(int value) {
    minimumRmf = value;
  }

  public static int getLabelFontSize() {
    return labelFontSize;
  }

  public static void setLabelFontSize(int size) {
    labelFontSize = size;
  }

  /**
   * Seeds the score filters from the NIST module's minimum match factor, once per session. Doing
   * it every time a window opens would discard a filter the user had adjusted.
   */
  public static void initialiseFiltersOnce(int minimumMatchFactor) {
    if (filtersInitialised) {
      return;
    }
    filtersInitialised = true;
    minimumFmf = minimumMatchFactor;
    minimumRmf = minimumMatchFactor;
  }

  public static boolean isDefaultHorizontal() {
    return defaultHorizontal;
  }

  public static void setDefaultHorizontal(boolean horizontal) {
    defaultHorizontal = horizontal;
    repaint(null);
  }

  public static boolean isLabelVisible(@Nullable RawDataFile rawDataFile, double retentionTime) {
    if (rawDataFile == null) {
      return true;
    }
    return hiddenRetentionTimes.getOrDefault(rawDataFile, List.of()).stream().noneMatch(
        hiddenRt -> NistMatchUtils.isSameChartPeakRetentionTime(hiddenRt, retentionTime));
  }

  public static void setLabelVisible(@Nullable RawDataFile rawDataFile, double retentionTime,
      boolean visible) {
    if (rawDataFile == null) {
      return;
    }
    final List<Double> hidden = hiddenRetentionTimes.computeIfAbsent(rawDataFile,
        ignored -> new ArrayList<>());
    hidden.removeIf(hiddenRt -> NistMatchUtils.isSameChartPeakRetentionTime(hiddenRt,
        retentionTime));
    if (!visible) {
      hidden.add(retentionTime);
    }
    repaint(rawDataFile);
  }

  public static boolean isLabelHorizontal(@Nullable RawDataFile rawDataFile, double retentionTime) {
    if (rawDataFile == null) {
      return defaultHorizontal;
    }
    if (matchesStoredRetentionTime(horizontalRetentionTimes, rawDataFile, retentionTime)) {
      return true;
    }
    if (matchesStoredRetentionTime(verticalRetentionTimes, rawDataFile, retentionTime)) {
      return false;
    }
    return defaultHorizontal;
  }

  public static void setLabelHorizontal(@Nullable RawDataFile rawDataFile, double retentionTime,
      boolean horizontal) {
    if (rawDataFile == null) {
      return;
    }
    final List<Double> horizontals = horizontalRetentionTimes.computeIfAbsent(rawDataFile,
        ignored -> new ArrayList<>());
    final List<Double> verticals = verticalRetentionTimes.computeIfAbsent(rawDataFile,
        ignored -> new ArrayList<>());
    horizontals.removeIf(rt -> NistMatchUtils.isSameChartPeakRetentionTime(rt, retentionTime));
    verticals.removeIf(rt -> NistMatchUtils.isSameChartPeakRetentionTime(rt, retentionTime));
    (horizontal ? horizontals : verticals).add(retentionTime);
    repaint(rawDataFile);
  }

  public static @Nullable NistMatch getSelectedMatch(@Nullable RawDataFile rawDataFile,
      double retentionTime) {
    if (rawDataFile == null) {
      return null;
    }
    return selectedMatches.getOrDefault(rawDataFile, List.of()).stream().filter(
            selected -> NistMatchUtils.isSameChartPeakRetentionTime(selected.retentionTime(),
                retentionTime)).map(SelectedNistMatch::match).findFirst().orElse(null);
  }

  public static void setSelectedMatch(@Nullable RawDataFile rawDataFile, double retentionTime,
      @Nullable NistMatch match) {
    if (rawDataFile == null || match == null) {
      return;
    }
    final List<SelectedNistMatch> selections = selectedMatches.computeIfAbsent(rawDataFile,
        ignored -> new ArrayList<>());
    selections.removeIf(selected -> NistMatchUtils.isSameChartPeakRetentionTime(
        selected.retentionTime(), retentionTime));
    selections.add(new SelectedNistMatch(retentionTime, match));
    repaint(rawDataFile);
  }

  /**
   * Forgets every stored setting for one peak: hidden, orientation and chosen candidate.
   *
   * <p>Used when a peak's hits are deleted. Leaving the state behind would mean a later search at
   * the same retention time silently inherited a hidden label or a candidate that no longer
   * exists, which is exactly the sort of thing that makes debugging a detection problem harder.</p>
   */
  public static void clearPeak(@Nullable RawDataFile rawDataFile, double retentionTime) {
    if (rawDataFile == null) {
      return;
    }
    for (Map<RawDataFile, List<Double>> store : List.of(hiddenRetentionTimes,
        horizontalRetentionTimes, verticalRetentionTimes)) {
      final List<Double> stored = store.get(rawDataFile);
      if (stored != null) {
        stored.removeIf(rt -> NistMatchUtils.isSameChartPeakRetentionTime(rt, retentionTime));
      }
    }
    final List<SelectedNistMatch> selections = selectedMatches.get(rawDataFile);
    if (selections != null) {
      selections.removeIf(selected -> NistMatchUtils.isSameChartPeakRetentionTime(
          selected.retentionTime(), retentionTime));
    }
    repaint(rawDataFile);
  }

  private static boolean matchesStoredRetentionTime(Map<RawDataFile, List<Double>> store,
      RawDataFile rawDataFile, double retentionTime) {
    return store.getOrDefault(rawDataFile, List.of()).stream()
        .anyMatch(stored -> NistMatchUtils.isSameChartPeakRetentionTime(stored, retentionTime));
  }

  private record SelectedNistMatch(double retentionTime, NistMatch match) {

  }
}
