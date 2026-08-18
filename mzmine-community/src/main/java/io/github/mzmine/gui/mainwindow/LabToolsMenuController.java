/*
 * Copyright (c) 2004-2026 The mzmine Development Team
 * SPDX-License-Identifier: MIT
 */
package io.github.mzmine.gui.mainwindow;

import io.github.mzmine.main.MZmineCore;
import io.github.mzmine.modules.MZmineRunnableModule;
import java.util.logging.Logger;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.scene.control.MenuItem;

/**
 * Controller for {@link LabToolsMenu} entries.
 *
 * <p>Deliberately separate from {@link MainMenuController}: that controller's {@code initialize}
 * drives the recent-projects menu and fails if those nodes are absent, so it cannot be reused by an
 * included fragment. Keeping a minimal controller here also means the lab menu does not depend on
 * anything upstream may change in the main controller.</p>
 */
public class LabToolsMenuController {

  private static final Logger logger = Logger.getLogger(LabToolsMenuController.class.getName());

  /**
   * Opens the setup dialog for the module named in the menu item's {@code userData}, matching the
   * behaviour of the main menu.
   */
  @FXML
  @SuppressWarnings("unchecked")
  public void runModule(Event event) {
    if (!(event.getSource() instanceof MenuItem menuItem)
        || !(menuItem.getUserData() instanceof String moduleClass)) {
      logger.warning("Lab tools menu item is missing its module class");
      return;
    }

    logger.info("Lab tools menu item activated for module " + moduleClass);
    final Class<? extends MZmineRunnableModule> moduleJavaClass;
    try {
      moduleJavaClass = (Class<? extends MZmineRunnableModule>) Class.forName(moduleClass);
    } catch (Throwable error) {
      logger.log(java.util.logging.Level.SEVERE, "Cannot load module class " + moduleClass, error);
      MZmineCore.getDesktop().displayMessage("Cannot load module class " + moduleClass);
      return;
    }
    MZmineCore.setupAndRunModule(moduleJavaClass);
  }
}
