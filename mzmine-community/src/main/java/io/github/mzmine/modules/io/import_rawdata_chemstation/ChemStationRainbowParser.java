/*
 * Copyright (c) 2004-2026 The mzmine Development Team
 * SPDX-License-Identifier: MIT
 */
package io.github.mzmine.modules.io.import_rawdata_chemstation;

import io.github.mzmine.util.files.FileAndPathUtil;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reads an Agilent ChemStation {@code .D} dataset by running the bundled
 * {@code read_chemstation.py} bridge, which uses the rainbow library.
 *
 * <p>mzmine deliberately does not implement the ChemStation binary layout itself. rainbow is
 * LGPL-3.0 and mzmine is MIT, so rainbow is used as a separate tool through its public API rather
 * than having its format logic reproduced here. rainbow is installed by the user and is not
 * distributed with mzmine; see {@code external_tools/chemstation_rainbow/README.txt}.</p>
 *
 * <p>The bridge streams a compact big-endian binary described in that script. Scans are read one at
 * a time as the process produces them, so a large run is never held in memory at once.</p>
 */
public final class ChemStationRainbowParser implements AutoCloseable {

  private static final Logger logger = Logger.getLogger(ChemStationRainbowParser.class.getName());

  private static final String BRIDGE_DIR = "chemstation_rainbow";
  private static final String BRIDGE_SCRIPT = "read_chemstation.py";
  private static final byte[] MAGIC = {'M', 'Z', 'C', 'S'};
  private static final int SUPPORTED_VERSION = 1;

  /** Interpreter names to try when no virtual environment is present. */
  private static final List<String> DEFAULT_INTERPRETERS = List.of("python", "python3", "py");

  private final Process process;
  private final DataInputStream input;
  private final Path errorLog;
  private final int totalScans;
  private int readScans;

  public ChemStationRainbowParser(@NotNull File dataset) throws IOException {
    final File script = resolveScript();
    if (script == null) {
      throw new IOException(
          "The ChemStation reader script was not found. It should sit at external_tools/%s/%s."
              .formatted(BRIDGE_DIR, BRIDGE_SCRIPT));
    }

    errorLog = Files.createTempFile("mzmine_chemstation_", ".log");
    IOException lastFailure = null;
    Process started = null;
    for (String interpreter : interpreters()) {
      try {
        started = new ProcessBuilder(interpreter, script.getAbsolutePath(),
            dataset.getAbsolutePath()).redirectError(errorLog.toFile()).start();
        break;
      } catch (IOException cannotStart) {
        // Not present under that name on this machine; try the next spelling.
        lastFailure = cannotStart;
      }
    }
    if (started == null) {
      Files.deleteIfExists(errorLog);
      throw new IOException(
          "No Python interpreter could be started for the ChemStation reader. Install Python and "
              + "rainbow (pip install rainbow-api), or create a virtual environment under "
              + "external_tools/" + BRIDGE_DIR + ". See the README.txt in that directory.",
          lastFailure);
    }

    process = started;
    input = new DataInputStream(new BufferedInputStream(process.getInputStream()));
    try {
      totalScans = readHeader(dataset);
    } catch (IOException headerFailure) {
      close();
      throw headerFailure;
    }
  }

  private int readHeader(File dataset) throws IOException {
    final byte[] magic = new byte[MAGIC.length];
    try {
      input.readFully(magic);
    } catch (EOFException empty) {
      // The bridge exited before writing anything; its stderr explains why.
      throw new IOException(describeFailure(dataset), empty);
    }
    for (int index = 0; index < MAGIC.length; index++) {
      if (magic[index] != MAGIC[index]) {
        throw new IOException(describeFailure(dataset));
      }
    }
    final int version = input.readInt();
    if (version != SUPPORTED_VERSION) {
      throw new IOException("The ChemStation reader script reports format version " + version
          + ", but this build expects " + SUPPORTED_VERSION
          + ". The script and mzmine are out of step.");
    }
    final int scans = input.readInt();
    if (scans < 0) {
      throw new IOException("The ChemStation reader reported a negative scan count for " + dataset);
    }
    return scans;
  }

  /** Reads the bridge's stderr so a failure names a cause rather than a byte count. */
  private String describeFailure(File dataset) {
    String detail = "";
    try {
      if (Files.exists(errorLog)) {
        detail = Files.readString(errorLog, StandardCharsets.UTF_8).strip();
      }
    } catch (IOException ignored) {
      // Fall through to the generic message.
    }
    return detail.isBlank() ? "The ChemStation reader produced no data for " + dataset
        : "The ChemStation reader failed for " + dataset + ": " + detail;
  }

  public int getTotalScans() {
    return totalScans;
  }

  public int getReadScans() {
    return readScans;
  }

  public double getFinishedPercentage() {
    return totalScans == 0 ? 0d : Math.min(1d, (double) readScans / totalScans);
  }

  /** Reads the next scan, or returns {@code null} once every declared scan has been read. */
  public @Nullable ChemStationScan readNextScan() throws IOException {
    if (readScans >= totalScans) {
      return null;
    }
    final float retentionTime = (float) input.readDouble();
    final int pointCount = input.readInt();
    if (pointCount < 0) {
      throw new IOException("The ChemStation reader reported a negative point count");
    }
    final double[] mzValues = new double[pointCount];
    final double[] intensityValues = new double[pointCount];
    for (int index = 0; index < pointCount; index++) {
      mzValues[index] = input.readDouble();
    }
    for (int index = 0; index < pointCount; index++) {
      intensityValues[index] = input.readDouble();
    }
    readScans++;
    return new ChemStationScan(retentionTime, mzValues, intensityValues);
  }

  /** The bridge script shipped with mzmine, or {@code null} when it is missing. */
  static @Nullable File resolveScript() {
    final File script = FileAndPathUtil.resolveInExternalToolsDir(BRIDGE_DIR + "/" + BRIDGE_SCRIPT);
    return script.isFile() ? script : null;
  }

  /**
   * Interpreters to try, most specific first: a virtual environment created beside the script, then
   * the usual names on PATH. A local environment keeps rainbow out of the user's system Python.
   */
  static List<String> interpreters() {
    final File script = resolveScript();
    if (script != null) {
      final File toolDir = script.getParentFile();
      for (String relative : List.of("Scripts/python.exe", "bin/python3", "bin/python")) {
        final File venv = new File(toolDir, ".venv/" + relative);
        if (venv.isFile()) {
          logger.fine(() -> "Using the ChemStation reader environment at " + venv);
          return List.of(venv.getAbsolutePath());
        }
      }
    }
    return DEFAULT_INTERPRETERS;
  }

  @Override
  public void close() {
    try {
      input.close();
    } catch (IOException ignored) {
      // Nothing useful to do while tearing down.
    }
    process.destroy();
    try {
      Files.deleteIfExists(errorLog);
    } catch (IOException ignored) {
      // The OS temp directory is cleaned up eventually.
    }
  }

  public record ChemStationScan(float retentionTime, double[] mzValues,
                                double[] intensityValues) {

  }
}
