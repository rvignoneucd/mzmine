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
import javafx.scene.control.MenuBar;
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
      menuBar.getMenus().add(FxMenuUtil.addModuleMenuItems(LAB_MENU_TITLE, modules));
      return menuBar;
    }

    FxMenuUtil.addModuleMenuItems(tools, LAB_MENU_TITLE, modules);
    return menuBar;
  }
}
