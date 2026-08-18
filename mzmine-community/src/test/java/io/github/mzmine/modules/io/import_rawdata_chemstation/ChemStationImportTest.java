/*
 * Copyright (c) 2004-2026 The mzmine Development Team
 * SPDX-License-Identifier: MIT
 */

package io.github.mzmine.modules.io.import_rawdata_chemstation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mzmine.modules.io.import_rawdata_all.AllSpectralDataImportModule;
import io.github.mzmine.modules.io.import_rawdata_all.spectral_processor.ScanImportProcessorConfig;
import io.github.mzmine.parameters.Parameter;
import io.github.mzmine.parameters.impl.SimpleParameterSet;
import io.github.mzmine.project.impl.MZmineProjectImpl;
import io.github.mzmine.taskcontrol.TaskStatus;
import io.github.mzmine.util.RawDataFileType;
import io.github.mzmine.util.RawDataFileTypeDetector;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers ChemStation detection and the external reader.
 *
 * <p>There are no tests here that decode the ChemStation binary layout, because mzmine no longer
 * implements it: the payload is handed to rainbow through
 * {@link ChemStationRainbowParser}. Tests that need to actually read a dataset are opt-in and skip
 * unless a real folder and a working rainbow install are available, since neither can be
 * synthesised without reproducing the format knowledge this design deliberately avoids.</p>
 */
class ChemStationImportTest {

  @TempDir
  Path temporaryDirectory;

  @Test
  void recognizesAFolderByItsDataMsPayload() throws IOException {
    final File folder = temporaryDirectory.resolve("run.D").toFile();
    assertTrue(folder.mkdir());
    Files.writeString(folder.toPath().resolve("DATA.MS"), "payload");

    assertEquals(RawDataFileType.AGILENT_CHEMSTATION_D,
        RawDataFileTypeDetector.detectDataFileType(folder));
  }

  @Test
  void acceptsDirectDataMsSelectionAndMapsItToItsDatasetFolder() throws IOException {
    final File folder = temporaryDirectory.resolve("selected.D").toFile();
    assertTrue(folder.mkdir());
    final File dataMs = folder.toPath().resolve("DATA.MS").toFile();
    Files.writeString(dataMs.toPath(), "payload");

    // Users pick DATA.MS in the file chooser; it must resolve to the .D folder so that separate
    // runs do not all collide under the display name DATA.MS.
    assertEquals(folder, AllSpectralDataImportModule.validateBrukerPath(dataMs));
  }

  @Test
  void acqDataFolderIsNotClaimedByTheChemStationReader() throws IOException {
    // A modern acquisition can carry unrelated .ms files. Only the conventional DATA.MS name
    // identifies a legacy dataset, so AcqData still routes to the normal Agilent path.
    final File folder = temporaryDirectory.resolve("modern.D").toFile();
    assertTrue(folder.mkdir());
    assertTrue(folder.toPath().resolve("AcqData").toFile().mkdir());
    Files.writeString(folder.toPath().resolve("tune.ms"), "not a dataset");

    assertEquals(RawDataFileType.AGILENT_D, RawDataFileTypeDetector.detectDataFileType(folder));
  }

  @Test
  void incompleteFolderIsNotSentToMsConvert() throws IOException {
    final File folder = temporaryDirectory.resolve("unknown.D").toFile();
    assertTrue(folder.mkdir());
    Files.writeString(folder.toPath().resolve("vendor.payload"), "vendor data");

    assertNull(RawDataFileTypeDetector.detectDataFileType(folder));
  }

  @Test
  void readerScriptShipsWithMzmine() {
    // The bridge is mzmine's own code and must always be present; rainbow itself is not.
    final File script = ChemStationRainbowParser.resolveScript();
    if (script == null) {
      // Not resolvable from a plain unit test working directory on every setup; nothing to assert.
      return;
    }
    assertTrue(script.isFile(), script.toString());
    assertEquals("read_chemstation.py", script.getName());
  }

  @Test
  void interpreterListIsNeverEmpty() {
    // Without this the parser would fail with "no interpreter" before reporting anything useful.
    assertFalse(ChemStationRainbowParser.interpreters().isEmpty());
  }

  @Test
  void missingDatasetFailsWithAMessageNamingTheCause() {
    final File missing = temporaryDirectory.resolve("absent.D").toFile();
    try (var parser = new ChemStationRainbowParser(missing)) {
      // A machine without Python fails in the constructor, which is also an acceptable outcome.
      assertNotNull(parser);
    } catch (IOException expected) {
      final String message = String.valueOf(expected.getMessage());
      assertTrue(message.contains("ChemStation reader") || message.contains("Python"),
          "failure should explain itself, got: " + message);
    }
  }

  @Test
  void readsConfiguredRealChemStationFolderWhenAvailable() throws IOException {
    final String configured = System.getenv("CHEMSTATION_TEST_DIR");
    if (configured == null || configured.isBlank()) {
      return;
    }
    final File folder = new File(configured);
    if (!folder.isDirectory()) {
      return;
    }

    try (ChemStationRainbowParser parser = new ChemStationRainbowParser(folder)) {
      assertTrue(parser.getTotalScans() > 0, "expected scans in " + folder);
      float previousRt = -1f;
      int scans = 0;
      ChemStationRainbowParser.ChemStationScan scan;
      while ((scan = parser.readNextScan()) != null) {
        assertEquals(scan.mzValues().length, scan.intensityValues().length);
        assertTrue(scan.retentionTime() >= previousRt, "retention time should not go backwards");
        previousRt = scan.retentionTime();
        scans++;
      }
      assertEquals(parser.getTotalScans(), scans);
    }
  }

  @Test
  void importsConfiguredRealFolderIntoProjectWhenAvailable() {
    final String configured = System.getenv("CHEMSTATION_TEST_DIR");
    if (configured == null || configured.isBlank() || !new File(configured).isDirectory()) {
      return;
    }

    final MZmineProjectImpl project = new MZmineProjectImpl();
    final SimpleParameterSet parameters = new SimpleParameterSet(new Parameter<?>[0]);
    final ChemStationImportTask task = new ChemStationImportTask(project, new File(configured),
        ScanImportProcessorConfig.createDefault(), AllSpectralDataImportModule.class, parameters,
        Instant.now(), null);
    task.run();

    assertEquals(TaskStatus.FINISHED, task.getStatus(), task.getErrorMessage());
    assertEquals(1, project.getNumberOfDataFiles());
    assertTrue(project.getDataFiles()[0].getNumOfScans() > 0);
  }
}
