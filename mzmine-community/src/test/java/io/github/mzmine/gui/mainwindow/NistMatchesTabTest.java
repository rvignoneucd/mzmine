/*
 * Copyright (c) 2004-2026 The mzmine Development Team
 * SPDX-License-Identifier: MIT
 */
package io.github.mzmine.gui.mainwindow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.mzmine.javafx.concurrent.threading.FxThread;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The NIST matches tab used to be declared in {@code MainWindow.fxml}. It is now built in code, so
 * this checks it constructs without the main window and registers itself for other views to reach.
 */
class NistMatchesTabTest {

  @Test
  void buildsStandaloneAndRegistersItself() throws Exception {
    FxThread.initJavaFx();
    final AtomicReference<NistMatchesTab> built = new AtomicReference<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    FxThread.runOnFxThreadAndWait(() -> {
      try {
        built.set(new NistMatchesTab());
      } catch (Throwable error) {
        failure.set(error);
      }
    });
    if (failure.get() != null) {
      throw new AssertionError("NistMatchesTab failed to build", failure.get());
    }

    final NistMatchesTab tab = built.get();
    assertNotNull(tab, "the tab should have been constructed");
    assertEquals("NIST matches", tab.getText());
    assertFalse(tab.isClosable(), "the tab should not be closable");
    assertNotNull(tab.getContent(), "the tab should have content");
    assertEquals(tab, NistMatchesTab.getInstance(),
        "the tab should register itself so other views can reach it");
  }
}
