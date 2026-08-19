/*
 * Copyright (c) 2004-2026 The mzmine Development Team
 * SPDX-License-Identifier: MIT
 */
package io.github.mzmine.modules.visualization.rawdataoverview;

import com.google.common.collect.Range;
import io.github.mzmine.datamodel.FeatureStatus;
import io.github.mzmine.datamodel.RawDataFile;
import io.github.mzmine.datamodel.Scan;
import io.github.mzmine.datamodel.featuredata.FeatureDataUtils;
import io.github.mzmine.datamodel.featuredata.IonTimeSeries;
import io.github.mzmine.datamodel.featuredata.IonTimeSeriesUtils;
import io.github.mzmine.datamodel.features.FeatureList;
import io.github.mzmine.datamodel.features.FeatureListRow;
import io.github.mzmine.datamodel.features.ModularFeature;
import io.github.mzmine.datamodel.features.ModularFeatureList;
import io.github.mzmine.datamodel.features.ModularFeatureListRow;
import io.github.mzmine.datamodel.features.types.FeatureDataType;
import io.github.mzmine.project.ProjectService;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves somewhere to store NIST hits for a peak clicked in a chromatogram, across every loaded
 * raw file.
 *
 * <p>Clicking a peak asks a question about a retention time, not about one file, so every loaded
 * file is searched at that time. Where a file already has a feature row near it that row is reused;
 * otherwise one is created, because refusing to search an obvious peak just because feature
 * detection missed it is not useful to anyone. Created rows are marked
 * {@link FeatureStatus#MANUAL} so they are distinguishable from detected features later.</p>
 */
public final class ClickedPeakNistTargets {

  private static final Logger logger = Logger.getLogger(ClickedPeakNistTargets.class.getName());

  /** Suffix for a feature list created because a file had none at all. */
  private static final String CREATED_LIST_SUFFIX = " manual NIST picks";

  private ClickedPeakNistTargets() {
  }

  /**
   * A place to store hits for one raw file.
   *
   * @param created whether the row had to be made, rather than already existing
   */
  public record Target(@NotNull FeatureList featureList, @NotNull FeatureListRow row,
                       @NotNull RawDataFile file, @NotNull Scan scan, boolean created) {

  }

  /**
   * Finds or creates a row per loaded raw file at {@code retentionTime}.
   *
   * @param rtTolerance how close an existing row must be before it is reused rather than a new one
   *                    created
   * @return one target per file that has a scan near the retention time; files without one are
   * skipped, since there is nothing to search
   */
  public static @NotNull List<Target> resolve(double retentionTime, double rtTolerance) {
    return resolve(retentionTime, rtTolerance, new ArrayList<>());
  }

  /**
   * As {@link #resolve(double, double)}, additionally collecting the names of files skipped because
   * their run does not cover the retention time, so the caller can say so rather than reporting a
   * generic failure.
   */
  public static @NotNull List<Target> resolve(double retentionTime, double rtTolerance,
      @NotNull List<String> skippedForDistance) {
    final List<Target> targets = new ArrayList<>();
    for (RawDataFile file : ProjectService.getProjectManager().getCurrentProject()
        .getCurrentRawDataFiles()) {
      // binarySearchClosestScan returns the nearest scan whatever the distance, so a file whose
      // run never reached this time would otherwise be searched at its last scan and the hits
      // filed under a retention time it never measured.
      final Scan scan = file.binarySearchClosestScan((float) retentionTime, 1);
      if (scan == null || Math.abs(scan.getRetentionTime() - retentionTime) > rtTolerance) {
        logger.fine(() -> "%s has no scan within %.3f min of RT %.3f".formatted(file.getName(),
            rtTolerance, retentionTime));
        skippedForDistance.add(file.getName());
        continue;
      }

      final Target existing = findExistingRow(file, retentionTime, rtTolerance, scan);
      if (existing != null) {
        targets.add(existing);
        continue;
      }
      final Target created = createRow(file, retentionTime, rtTolerance, scan);
      if (created != null) {
        targets.add(created);
      }
    }
    return targets;
  }

  private static @Nullable Target findExistingRow(RawDataFile file, double retentionTime,
      double rtTolerance, Scan scan) {
    FeatureList bestList = null;
    FeatureListRow bestRow = null;
    double bestDistance = Double.POSITIVE_INFINITY;

    for (FeatureList featureList : ProjectService.getProjectManager().getCurrentProject()
        .getCurrentFeatureLists()) {
      if (!featureList.getRawDataFiles().contains(file)) {
        continue;
      }
      for (FeatureListRow row : featureList.getRows()) {
        final Double rowRt = rowRetentionTime(row, file);
        if (rowRt == null) {
          continue;
        }
        final double distance = Math.abs(rowRt - retentionTime);
        if (distance < bestDistance) {
          bestDistance = distance;
          bestRow = row;
          bestList = featureList;
        }
      }
    }
    return bestDistance <= rtTolerance && bestRow != null
        ? new Target(bestList, bestRow, file, scan, false) : null;
  }

  /** This file's retention time for the row, falling back to the row average for aligned rows. */
  static @Nullable Double rowRetentionTime(FeatureListRow row, RawDataFile file) {
    final var feature = row.getFeature(file);
    if (feature != null && feature.getRT() != null) {
      return feature.getRT().doubleValue();
    }
    final Float averageRt = row.getAverageRT();
    return averageRt == null ? null : averageRt.doubleValue();
  }

  /**
   * Builds a row for {@code file} at the clicked time.
   *
   * <p>The trace covers the scan's full m/z range, so for GC-EI it is effectively the local TIC.
   * That is enough to carry an annotation and to show the peak in the feature table; it is not a
   * substitute for running feature detection properly.</p>
   */
  private static @Nullable Target createRow(RawDataFile file, double retentionTime,
      double rtTolerance, Scan scan) {
    final ModularFeatureList featureList = listForNewRow(file);
    if (featureList == null) {
      return null;
    }

    try {
      final Range<Double> mzRange = scan.getDataPointMZRange();
      if (mzRange == null) {
        logger.fine(() -> "Scan at RT %.3f in %s has no m/z range".formatted(retentionTime,
            file.getName()));
        return null;
      }
      final Range<Float> rtRange = Range.closed((float) (retentionTime - rtTolerance),
          (float) (retentionTime + rtTolerance));

      List<? extends Scan> scans = featureList.getSeletedScans(file);
      if (scans == null || scans.isEmpty()) {
        scans = file.getScans();
      }
      final IonTimeSeries<?> series = IonTimeSeriesUtils.extractIonTimeSeries(file, scans, mzRange,
          rtRange, featureList.getMemoryMapStorage());

      final ModularFeature feature = new ModularFeature(featureList, file, FeatureStatus.MANUAL);
      feature.set(FeatureDataType.class, series);
      FeatureDataUtils.recalculateIonSeriesDependingTypes(feature);

      final ModularFeatureListRow row = new ModularFeatureListRow(featureList, nextRowId(
          featureList), feature);
      featureList.addRow(row);
      logger.info(() -> "Created a feature row for %s at RT %.3f to hold NIST hits".formatted(
          file.getName(), retentionTime));
      return new Target(featureList, row, file, scan, true);
    } catch (Throwable error) {
      // One file failing to yield a row must not stop the other files being searched.
      logger.log(Level.WARNING,
          "Could not create a feature row for " + file.getName() + " at RT " + retentionTime,
          error);
      return null;
    }
  }

  /**
   * The feature list a new row should join: an existing one covering this file, else a new list
   * created for the purpose so that a file without any feature detection still works.
   */
  private static @Nullable ModularFeatureList listForNewRow(RawDataFile file) {
    for (FeatureList featureList : ProjectService.getProjectManager().getCurrentProject()
        .getCurrentFeatureLists()) {
      // Aligned lists span several files; a single file list is a better home for a manual pick.
      if (featureList instanceof ModularFeatureList modular
          && modular.getRawDataFiles().size() == 1 && modular.getRawDataFiles().contains(file)) {
        return modular;
      }
    }
    for (FeatureList featureList : ProjectService.getProjectManager().getCurrentProject()
        .getCurrentFeatureLists()) {
      if (featureList instanceof ModularFeatureList modular
          && modular.getRawDataFiles().contains(file)) {
        return modular;
      }
    }

    try {
      final ModularFeatureList created = new ModularFeatureList(file.getName() + CREATED_LIST_SUFFIX,
          null, file);
      created.setSelectedScans(file, file.getScans());
      ProjectService.getProjectManager().getCurrentProject().addFeatureList(created);
      logger.info(() -> "Created feature list '" + created.getName()
          + "' because no existing list covered " + file.getName());
      return created;
    } catch (Throwable error) {
      logger.log(Level.WARNING, "Could not create a feature list for " + file.getName(), error);
      return null;
    }
  }

  private static int nextRowId(FeatureList featureList) {
    int highest = 0;
    for (FeatureListRow row : featureList.getRows()) {
      highest = Math.max(highest, row.getID());
    }
    return highest + 1;
  }
}
