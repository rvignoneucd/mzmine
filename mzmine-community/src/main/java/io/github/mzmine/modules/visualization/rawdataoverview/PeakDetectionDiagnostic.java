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
import io.github.mzmine.project.ProjectService;
import java.util.ArrayList;
import java.util.List;
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
    final StringBuilder report = new StringBuilder();
    report.append("Peak at RT %.3f min in %s%n%n".formatted(peak.apexRetentionTime(),
        file.getName()));
    report.append("Measured from the %s:%n".formatted(traceDescription));
    report.append("  width          %d scans%n".formatted(peak.scanCount()));
    report.append("  duration       %.3f min%n".formatted(peak.durationMinutes()));
    report.append("  top/edge ratio %.2f%n".formatted(peak.topToEdgeRatio()));
    report.append("  height         %.3g (%.1f%% of the largest point on this trace)%n".formatted(
        peak.apexIntensity(), peak.relativeHeight() * 100d));

    final List<FeatureList.FeatureListAppliedMethod> steps = detectionSteps(file);
    if (steps.isEmpty()) {
      report.append("%nNo feature detection has been run on this file, so nothing excluded this "
          + "peak - there is simply no feature list yet.".formatted());
      return report.toString();
    }

    final List<String> blocking = new ArrayList<>();
    final List<String> unjudged = new ArrayList<>();
    report.append("%nSettings that produced the existing feature list:%n".formatted());

    for (FeatureList.FeatureListAppliedMethod step : steps) {
      final ParameterSet parameters = step.getParameters();
      if (parameters == null) {
        continue;
      }
      for (Parameter<?> parameter : parameters.getParameters()) {
        final String name = parameter.getName();
        final Object value = parameter.getValue();
        if (value == null) {
          continue;
        }
        if (SCAN_COUNT_PARAMETERS.contains(name) && value instanceof Number required) {
          report.append("  %-34s %s%n".formatted(name, required));
          if (peak.scanCount() < required.intValue()) {
            blocking.add("%s is %s but this peak is only %d scans wide. It would need to be at most %d."
                .formatted(name, required, peak.scanCount(), peak.scanCount()));
          }
        } else if (DURATION_PARAMETERS.contains(name) && value instanceof Range<?> range) {
          report.append("  %-34s %s%n".formatted(name, range));
          final Double lower = asDouble(range.hasLowerBound() ? range.lowerEndpoint() : null);
          final Double upper = asDouble(range.hasUpperBound() ? range.upperEndpoint() : null);
          if (lower != null && peak.durationMinutes() < lower) {
            blocking.add("%s starts at %.3f min but this peak lasts %.3f min.".formatted(name,
                lower, peak.durationMinutes()));
          } else if (upper != null && peak.durationMinutes() > upper) {
            blocking.add("%s ends at %.3f min but this peak lasts %.3f min.".formatted(name, upper,
                peak.durationMinutes()));
          }
        } else if (RATIO_PARAMETERS.contains(name) && value instanceof Number required) {
          report.append("  %-34s %s%n".formatted(name, required));
          if (peak.topToEdgeRatio() < required.doubleValue()) {
            blocking.add("%s is %s but this peak only reaches %.2f. It sits on a high baseline or overlaps a neighbour."
                .formatted(name, required, peak.topToEdgeRatio()));
          }
        } else if (ABSOLUTE_INTENSITY_PARAMETERS.contains(name) && value instanceof Number required) {
          report.append("  %-34s %s%n".formatted(name, required));
          unjudged.add("%s (%s)".formatted(name, required));
        }
      }
    }

    if (blocking.isEmpty()) {
      report.append("%nNothing in the comparable settings excludes this peak.%n".formatted());
      report.append("That points at an absolute intensity threshold, or at the peak being absent "
          + "from the extracted ion chromatograms rather than from the trace shown here.%n".formatted());
    } else {
      report.append("%nThis peak fails:%n".formatted());
      for (String reason : blocking) {
        report.append("  - ").append(reason).append(System.lineSeparator());
      }
      report.append("%nChange the setting in your batch and run detection again. Note that "
          + "relaxing a threshold applies to every peak in every file, not just this one.%n"
          .formatted());
    }

    if (!unjudged.isEmpty()) {
      report.append("%nNot judged, because these apply to extracted ion chromatograms and the "
          + "measurement above is from the displayed trace:%n  ".formatted());
      report.append(String.join(", ", unjudged)).append(System.lineSeparator());
    }
    return report.toString();
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

  private static @Nullable Double asDouble(@Nullable Object value) {
    return value instanceof Number number ? number.doubleValue() : null;
  }
}
