/*
 * Copyright (c) 2004-2026 The mzmine Development Team
 * SPDX-License-Identifier: MIT
 */
package io.github.mzmine.modules.visualization.rawdataoverview;

import com.google.common.collect.Range;
import io.github.mzmine.datamodel.RawDataFile;
import io.github.mzmine.datamodel.features.FeatureList;
import io.github.mzmine.parameters.Parameter;
import io.github.mzmine.parameters.ParameterSet;
import io.github.mzmine.parameters.parametertypes.selectors.ScanSelection;
import io.github.mzmine.project.ProjectService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Explains why a peak visible in a chromatogram did not become a feature.
 *
 * <p>Finding this out by hand means opening the batch file, locating the detection step and
 * comparing thresholds against a peak measured off the screen. That is tedious and easy to get
 * wrong, and it is exactly the sort of comparison a machine should do. So this reads the parameters
 * that actually produced the feature list - mzmine records them on each list as applied methods -
 * measures the clicked peak, and reports which thresholds it fails.</p>
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>It does not change anything. A detection threshold is global: relaxing one to admit a single
 * peak also admits everything else above the new value, across every file, which can add a large
 * number of noise features. That trade is the analyst's to make, and it should be made in the batch
 * file where it stays reproducible, not silently in a live project.</p>
 *
 * <p>It also refuses to judge absolute intensity thresholds. Those apply to extracted ion
 * chromatograms, while the measurement here comes from the displayed trace, which for a TIC is a
 * sum over all m/z and therefore much larger. Comparing the two would produce confident nonsense,
 * so the report states the measured height and says plainly that the comparison is not valid.</p>
 */
public final class PeakDetectionDiagnostic {

  /** Parameter display names whose units are comparable with a measurement off the trace. */
  private static final List<String> SCAN_COUNT_PARAMETERS = List.of("Min # of data points",
      "Min group size in # of scans", "Minimum consecutive scans", "Minimum scans (data points)",
      "Minimum number of data points on a feature");
  private static final List<String> DURATION_PARAMETERS = List.of("Peak duration range");
  private static final List<String> RATIO_PARAMETERS = List.of("Min ratio of peak top/edge");
  private static final List<String> ABSOLUTE_INTENSITY_PARAMETERS = List.of("Min highest intensity",
      "Minimum absolute height", "Min absolute height",
      "Minimum intensity for consecutive scans");

  private PeakDetectionDiagnostic() {
  }

  /** A peak measured from the displayed chromatogram. */
  public record PeakShape(double apexRetentionTime, double apexIntensity, int scanCount,
                          double durationMinutes, double topToEdgeRatio, double relativeHeight) {

  }

  /**
   * Builds a human-readable report for a peak that has no feature row.
   *
   * @param traceDescription what the measurement came from, for example "TIC", so the reader can
   *                         judge the numbers
   */
  public static @NotNull String report(@NotNull RawDataFile file, @NotNull PeakShape peak,
      @NotNull String traceDescription) {
    final String br = System.lineSeparator();
    final StringBuilder report = new StringBuilder();
    report.append("Peak at RT %.3f min in %s".formatted(peak.apexRetentionTime(), file.getName()))
        .append(br).append(br);
    report.append("Measured from the ").append(traceDescription).append(':').append(br);
    report.append("  width          %d scans".formatted(peak.scanCount())).append(br);
    report.append("  duration       %.3f min".formatted(peak.durationMinutes())).append(br);
    report.append("  top/edge ratio %.2f".formatted(peak.topToEdgeRatio())).append(br);
    report.append("  height         %.3g (%.1f%% of the largest point on this trace)".formatted(
        peak.apexIntensity(), peak.relativeHeight() * 100d)).append(br);

    // A retention time restriction is checked first and reported on its own. It explains an entire
    // missing region of the chromatogram rather than one peak, so comparing peak shape against
    // thresholds afterwards would only distract from the real cause.
    final List<String> excludedBy = retentionTimeRestrictions(file, peak.apexRetentionTime());
    if (!excludedBy.isEmpty()) {
      report.append(br).append("This peak is outside the retention time range that processing "
          + "was told to look at:").append(br);
      for (String restriction : excludedBy) {
        report.append("  - ").append(restriction).append(br);
      }
      report.append(br).append("Every scan outside that range is dropped before feature detection "
          + "runs, so no threshold is involved and no peak beyond it can ever be found.").append(br);
      report.append("Widen the scan filter in that step and run the batch again.").append(br);
      return report.toString();
    }

    // One entry per distinct setting. A file usually belongs to several feature lists - the
    // chromatograms, the deconvoluted list, an aligned list - and each carries the whole applied
    // method chain, so without this the same thresholds are printed once per list.
    final Map<String, List<Object>> thresholds = collectThresholds(file);
    if (thresholds.isEmpty()) {
      report.append(br).append("No feature detection has been run on this file, so nothing "
          + "excluded this peak - there is simply no feature list yet.").append(br);
      return report.toString();
    }

    final Set<String> blocking = new LinkedHashSet<>();
    final Set<String> unjudged = new LinkedHashSet<>();
    report.append(br).append("Settings that produced the existing feature list:").append(br);

    for (Map.Entry<String, List<Object>> threshold : thresholds.entrySet()) {
      final String name = threshold.getKey();
      for (Object each : threshold.getValue()) {
        report.append("  %-34s %s".formatted(name, each)).append(br);
      }
      // Judge against the strictest value in play; that is the one that excluded the peak.
      final Object value = strictest(name, threshold.getValue());

      if (SCAN_COUNT_PARAMETERS.contains(name) && value instanceof Number required
          && peak.scanCount() < required.intValue()) {
        blocking.add("%s is %s but this peak is only %d scans wide.".formatted(name, required,
            peak.scanCount()));
      } else if (DURATION_PARAMETERS.contains(name) && value instanceof Range<?> range) {
        final Double lower = asDouble(range.hasLowerBound() ? range.lowerEndpoint() : null);
        final Double upper = asDouble(range.hasUpperBound() ? range.upperEndpoint() : null);
        if (lower != null && peak.durationMinutes() < lower) {
          blocking.add("%s starts at %.3f min but this peak lasts %.3f min.".formatted(name, lower,
              peak.durationMinutes()));
        } else if (upper != null && peak.durationMinutes() > upper) {
          blocking.add("%s ends at %.3f min but this peak lasts %.3f min.".formatted(name, upper,
              peak.durationMinutes()));
        }
      } else if (RATIO_PARAMETERS.contains(name) && value instanceof Number required
          && peak.topToEdgeRatio() < required.doubleValue()) {
        blocking.add(("%s is %s but this peak only reaches %.2f, so it sits on a high baseline or "
            + "overlaps a neighbour.").formatted(name, required, peak.topToEdgeRatio()));
      } else if (ABSOLUTE_INTENSITY_PARAMETERS.contains(name) && value instanceof Number required) {
        unjudged.add("%s (%s)".formatted(name, required));
      }
    }

    report.append(br);
    if (blocking.isEmpty()) {
      report.append("Nothing in the comparable settings excludes this peak.").append(br);
      report.append("That points at an absolute intensity threshold, or at the peak being absent "
          + "from the extracted ion chromatograms rather than from the trace shown here.").append(br);
    } else {
      report.append("This peak fails:").append(br);
      for (String reason : blocking) {
        report.append("  - ").append(reason).append(br);
      }
      report.append(br);
      report.append("Change the setting in your batch and run detection again. Relaxing a "
          + "threshold applies to every peak in every file, not just this one.").append(br);
    }

    if (!unjudged.isEmpty()) {
      report.append(br);
      report.append("Not judged, because these apply to extracted ion chromatograms while the "
          + "measurement above comes from the displayed trace:").append(br);
      report.append("  ").append(String.join(", ", unjudged)).append(br);
    }
    return report.toString();
  }

  /**
   * Every distinct threshold that shaped this file's feature lists.
   *
   * <p>Keyed by name and value together, so a setting that genuinely differs between two lists is
   * shown twice while the same setting repeated across lists collapses to one line.</p>
   */
  private static Map<String, List<Object>> collectThresholds(RawDataFile file) {
    final Map<String, List<Object>> thresholds = new LinkedHashMap<>();
    for (FeatureList.FeatureListAppliedMethod step : detectionSteps(file)) {
      final ParameterSet parameters = step.getParameters();
      if (parameters == null) {
        continue;
      }
      for (Parameter<?> parameter : parameters.getParameters()) {
        final String name = parameter.getName();
        final Object value = parameter.getValue();
        if (value == null || !isComparable(name)) {
          continue;
        }
        final List<Object> values = thresholds.computeIfAbsent(name, ignored -> new ArrayList<>());
        // Distinct values only. The same step repeated across feature lists collapses, while a
        // setting that genuinely differs between two lists is kept so the user sees both.
        if (values.stream().noneMatch(existing -> String.valueOf(existing).equals(
            String.valueOf(value)))) {
          values.add(value);
        }
      }
    }
    return thresholds;
  }

  /**
   * Scan selections whose retention time range excludes {@code retentionTime}.
   *
   * <p>This is the cause that looks least like a threshold problem and is the easiest to overlook:
   * the peak is plainly visible in the raw data, every threshold looks reasonable, and yet the
   * feature table simply stops at some time. Scans outside the range never reach detection at
   * all.</p>
   */
  private static List<String> retentionTimeRestrictions(RawDataFile file, double retentionTime) {
    final List<String> restrictions = new ArrayList<>();
    for (FeatureList.FeatureListAppliedMethod step : allSteps(file)) {
      final ParameterSet parameters = step.getParameters();
      if (parameters == null) {
        continue;
      }
      for (Parameter<?> parameter : parameters.getParameters()) {
        if (!(parameter.getValue() instanceof ScanSelection selection)) {
          continue;
        }
        final Range<Double> range = selection.getScanRTRange();
        if (range == null || range.contains(retentionTime)) {
          continue;
        }
        final String description = "%s in %s is set to %s, and this peak is at %.3f min".formatted(
            parameter.getName(), step.getModule().getName(), range, retentionTime);
        if (!restrictions.contains(description)) {
          restrictions.add(description);
        }
      }
    }
    return restrictions;
  }

  /** Every applied method on feature lists covering this file, whatever parameters it holds. */
  private static List<FeatureList.FeatureListAppliedMethod> allSteps(RawDataFile file) {
    final List<FeatureList.FeatureListAppliedMethod> steps = new ArrayList<>();
    final var projectManager = ProjectService.getProjectManager();
    final var project = projectManager == null ? null : projectManager.getCurrentProject();
    if (project == null) {
      return steps;
    }
    for (FeatureList featureList : project.getCurrentFeatureLists()) {
      if (featureList.getRawDataFiles().contains(file)) {
        steps.addAll(featureList.getAppliedMethods());
      }
    }
    return steps;
  }

  private static boolean isComparable(String name) {
    return SCAN_COUNT_PARAMETERS.contains(name) || DURATION_PARAMETERS.contains(name)
        || RATIO_PARAMETERS.contains(name) || ABSOLUTE_INTENSITY_PARAMETERS.contains(name);
  }

  /** Detection and deconvolution steps recorded on feature lists covering this file. */
  private static List<FeatureList.FeatureListAppliedMethod> detectionSteps(RawDataFile file) {
    final List<FeatureList.FeatureListAppliedMethod> steps = new ArrayList<>();
    final var projectManager = ProjectService.getProjectManager();
    final var project = projectManager == null ? null : projectManager.getCurrentProject();
    if (project == null) {
      return steps;
    }
    for (FeatureList featureList : project.getCurrentFeatureLists()) {
      if (!featureList.getRawDataFiles().contains(file)) {
        continue;
      }
      for (FeatureList.FeatureListAppliedMethod method : featureList.getAppliedMethods()) {
        final ParameterSet parameters = method.getParameters();
        if (parameters == null) {
          continue;
        }
        boolean relevant = false;
        for (Parameter<?> parameter : parameters.getParameters()) {
          final String name = parameter.getName();
          if (SCAN_COUNT_PARAMETERS.contains(name) || DURATION_PARAMETERS.contains(name)
              || RATIO_PARAMETERS.contains(name) || ABSOLUTE_INTENSITY_PARAMETERS.contains(name)) {
            relevant = true;
            break;
          }
        }
        if (relevant) {
          steps.add(method);
        }
      }
    }
    return steps;
  }

  /** The value most likely to have excluded a peak when a setting differs between lists. */
  private static Object strictest(String name, List<Object> values) {
    if (values.size() == 1) {
      return values.getFirst();
    }
    if (SCAN_COUNT_PARAMETERS.contains(name) || RATIO_PARAMETERS.contains(name)
        || ABSOLUTE_INTENSITY_PARAMETERS.contains(name)) {
      // Higher is stricter for a minimum.
      return values.stream().filter(Number.class::isInstance).map(Number.class::cast)
          .max(java.util.Comparator.comparingDouble(Number::doubleValue))
          .map(Object.class::cast).orElse(values.getFirst());
    }
    return values.getFirst();
  }

  private static @Nullable Double asDouble(@Nullable Object value) {
    return value instanceof Number number ? number.doubleValue() : null;
  }
}
