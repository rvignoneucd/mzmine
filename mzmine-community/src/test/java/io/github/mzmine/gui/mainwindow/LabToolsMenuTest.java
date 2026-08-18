/*
 * Copyright (c) 2004-2026 The mzmine Development Team
 * SPDX-License-Identifier: MIT
 */
package io.github.mzmine.gui.mainwindow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mzmine.javafx.concurrent.threading.FxThread;
import java.net.URL;
import java.util.concurrent.atomic.AtomicReference;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import org.junit.jupiter.api.Test;

/**
 * Guards the lab tools menu, which is pulled into {@code MainMenu.fxml} by a single
 * {@code fx:include}. FXML problems only surface when the file is loaded, so a compile alone proves
 * nothing here - a bad include, a missing controller method or a renamed module class would
 * otherwise not be noticed until someone opened the application.
 */
class LabToolsMenuTest {

  private static final String LAB_MENU = "Lab tools";

  @Test
  void mainMenuLoadsAndContainsTheLabToolsMenu() throws Exception {
    final MenuBar menuBar = loadMainMenu();

    final Menu labMenu = menuBar.getMenus().stream()
        .filter(menu -> LAB_MENU.equals(menu.getText())).findFirst().orElse(null);
    assertNotNull(labMenu, "the lab tools menu should be included in the main menu bar");
    assertFalse(labMenu.getItems().isEmpty(), "the lab tools menu should not be empty");
  }

  @Test
  void everyLabToolPointsAtALoadableModuleClass() throws Exception {
    final MenuBar menuBar = loadMainMenu();
    final Menu labMenu = menuBar.getMenus().stream()
        .filter(menu -> LAB_MENU.equals(menu.getText())).findFirst().orElseThrow();

    for (MenuItem item : labMenu.getItems()) {
      final Object moduleClass = item.getUserData();
      assertNotNull(moduleClass, "menu item '" + item.getText() + "' has no module class");
      // Fails loudly if a module is renamed or removed without updating the menu.
      assertNotNull(Class.forName(moduleClass.toString()),
          "menu item '" + item.getText() + "' points at a class that does not exist");
      assertTrue(item.getOnAction() != null,
          "menu item '" + item.getText() + "' has no action handler");
    }
  }

  private static MenuBar loadMainMenu() throws Exception {
    FxThread.initJavaFx();
    final AtomicReference<MenuBar> loaded = new AtomicReference<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    FxThread.runOnFxThreadAndWait(() -> {
      try {
        final URL resource = MainMenuController.class.getResource("MainMenu.fxml");
        loaded.set(FXMLLoader.load(resource));
      } catch (Throwable error) {
        failure.set(error);
      }
    });
    if (failure.get() != null) {
      throw new AssertionError("MainMenu.fxml failed to load", failure.get());
    }
    return loaded.get();
  }
}
