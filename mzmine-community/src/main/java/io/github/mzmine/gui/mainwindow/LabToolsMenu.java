/*
 * Copyright (c) 2004-2026 The mzmine Development Team
 * SPDX-License-Identifier: MIT
 */
package io.github.mzmine.gui.mainwindow;

import io.github.mzmine.modules.MZmineRunnableModule;
import io.github.mzmine.modules.dataprocessing.id_nist.NistMsSearchModule;
import io.github.mzmine.util.javafx.FxMenuUtil;
import java.util.List;
import java.util.logging.Logger;
import javafx.scene.control.Menu;
import io.github.mzmine.main.MZmineCore;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import org.jetbrains.annotations.NotNull;

/**
 * Adds this build's local tools to the main menu as a submenu of Tools.
 *
 * <p>The menu bar is built in code by the active workspace, not loaded from {@code MainMenu.fxml},
 * so local additions have to be injected after the workspace has built it. Doing that here rather
 * than in a workspace class keeps the change out of {@code AbstractWorkspace}, which upstream edits
 * often, and costs one line in {@code MainWindowController}.</p>
 *
 * <p>To add a tool, list its module class in {@link #LAB_MODULES}. Menu labels come from each
 * module's own {@code getName()}, so there is no separate label to keep in step.</p>
 */
public final class LabToolsMenu {

  private static final Logger logger = Logger.getLogger(LabToolsMenu.class.getName());

  /** Title of the submenu added under Tools. */
  public static final String LAB_MENU_TITLE = "Lab tools";

  /** Modules offered in the lab submenu, in the order they should appear. */
  private static final List<Class<? extends MZmineRunnableModule>> LAB_MODULES = List.of(
      NistMsSearchModule.class);

  private LabToolsMenu() {
  }

  /**
   * Adds the lab submenu to the Tools menu of {@code menuBar} and returns the same bar, so callers
   * can wrap the workspace's menu in one expression.
   *
   * <p>If no Tools menu is present - a workspace is free not to have one - the submenu is added as
   * a top level menu instead, so the tools stay reachable rather than silently disappearing.</p>
   */
  public static @NotNull MenuBar addTo(@NotNull MenuBar menuBar) {
    @SuppressWarnings("unchecked") final Class<? extends MZmineRunnableModule>[] modules =
        LAB_MODULES.toArray(Class[]::new);

    final Menu tools = menuBar.getMenus().stream()
        .filter(menu -> "Tools".equals(menu.getText())).findFirst().orElse(null);
    if (tools == null) {
      logger.fine("No Tools menu in this workspace; adding the lab tools as a top level menu");
      final Menu standalone = FxMenuUtil.addModuleMenuItems(LAB_MENU_TITLE, modules);
      addAboutItem(standalone);
      menuBar.getMenus().add(standalone);
      return menuBar;
    }

    addAboutItem(FxMenuUtil.addModuleMenuItems(tools, LAB_MENU_TITLE, modules));
    return menuBar;
  }

  private static void addAboutItem(Menu labMenu) {
    labMenu.getItems().add(new SeparatorMenuItem());
    final MenuItem about = new MenuItem("About these tools");
    about.setOnAction(event -> MZmineCore.getDesktop()
        .displayMessage("About " + LAB_MENU_TITLE, aboutText()));
    labMenu.getItems().add(about);
  }

  /** Kept here rather than in a resource file so it cannot drift from the code it describes. */
  static String aboutText() {
    return """
        Developed by Robert Vignone, University of California, Davis.

        Additions to mzmine for GC-MS work in this lab.

        Data import
          - Agilent ChemStation .D/DATA.MS read directly, without converting first
          - INFICON HAPSITE .hps read directly
          - Several vendor raw data folders can be selected at once

        NIST identification
          - Headless MSPepSearch EI search; no NIST window opens, and it works in batch
          - Searches your own licensed mainlib/replib, which never leave this machine
          - Optional retry of the raw apex when a deconvoluted spectrum returns nothing
          - NIST matches tab listing stored hits, with the alternative candidates per peak
          - Compound labels drawn on the chromatogram, with score filters and orientation
          - Right-click a peak to search it across every loaded file, creating a feature row
            where detection did not produce one

        Diagnostics
          - "Why was this peak not detected?" compares a clicked peak against the settings
            that actually produced the feature list, and names the threshold that excluded it

        Attribution
          - ChemStation format decoding is a Java port of rainbow
            (https://github.com/evanyeyeye/rainbow, LGPL-3.0), used with the permission of its
            maintainer. See docs/THIRD-PARTY-ATTRIBUTION.md.
          - NIST MSPepSearch is a free NIST download and is not part of mzmine.
        """;
  }
}
