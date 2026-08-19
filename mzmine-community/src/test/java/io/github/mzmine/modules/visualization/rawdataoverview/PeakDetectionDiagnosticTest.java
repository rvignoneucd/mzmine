/*
 * Copyright (c) 2004-2026 The mzmine Development Team
 * SPDX-License-Identifier: MIT
 */
package io.github.mzmine.modules.visualization.rawdataoverview;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mzmine.project.impl.RawDataFileImpl;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * The first version of this report printed literal "%n" because {@code "a" + "b".formatted()} binds
 * the call to the second literal only, and repeated every threshold once per feature list. Both
 * were obvious on screen and invisible to a compiler, so they are pinned here.
 */
class PeakDetectionDiagnosticTest {

  private static final PeakDetectionDiagnostic.PeakShape PEAK =
      new PeakDetectionDiagnostic.PeakShape(28.690, 8.21e4, 4, 0.038, 1.03, 0.032);

  @Test
  void reportUsesRealLineBreaksRatherThanFormatTokens() throws IOException {
    final String report = PeakDetectionDiagnostic.report(new RawDataFileImpl("run.D", null, null),
        PEAK, "TIC");

    assertFalse(report.contains("%n"), "unformatted %n token leaked into the report");
    assertTrue(report.lines().count() > 3, "the report should be laid out over several lines");
  }

  @Test
  void reportStatesTheMeasurementsItMade() throws IOException {
    final String report = PeakDetectionDiagnostic.report(new RawDataFileImpl("run.D", null, null),
        PEAK, "TIC");

    assertTrue(report.contains("28.690"), "the report should name the retention time");
    assertTrue(report.contains("4 scans"), "the report should state the measured width");
    assertTrue(report.contains("1.03"), "the report should state the measured top/edge ratio");
    assertTrue(report.contains("TIC"), "the report should say which trace was measured");
  }
}
