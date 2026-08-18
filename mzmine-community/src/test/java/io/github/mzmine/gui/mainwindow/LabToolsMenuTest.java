/*
 * Copyright (c) 2004-2026 The mzmine Development Team
 * SPDX-License-Identifier: MIT
 */
package io.github.mzmine.gui.mainwindow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mzmine.javafx.concurrent.threading.FxThread;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import org.junit.jupiter.api.Test;

/**
 * Guards the lab tools submenu.
 *
 * <p>An earlier version of this menu was declared in {@code MainMenu.fxml} and never appeared,
 * because the running application builds its menu bar in code through the active workspace and does
 * not load that file. The tests then passed while the feature was invisible, so these exercise the
 * same entry point the application uses - {@link LabToolsMenu#addTo(MenuBar)} against a menu bar
 * shaped like a workspace's - rather than an FXML resource.</p>
 */
class LabToolsMenuTest {

  @Test
  void addsTheLabSubmenuUnderTools() throws Exception {
    final MenuBar menuBar = onFxThread(() -> {
      final MenuBar bar = new MenuBar();
      bar.getMenus().addAll(new Menu("Project"), new Menu("Tools"), new Menu("Help"));
      return LabToolsMenu.addTo(bar);
    });

    final Menu tools = menuBar.getMenus().stream()
        .filter(menu -> "Tools".equals(menu.getText())).findFirst().orElseThrow();
    final Menu lab = tools.getItems().stream().filter(item -> item instanceof Menu)
        .map(Menu.class::cast)
        .filter(menu -> LabToolsMenu.LAB_MENU_TITLE.equals(menu.getText())).findFirst()
        .orElse(null);

    assertNotNull(lab, "the lab submenu should sit inside Tools");
    assertFalse(lab.getItems().isEmpty(), "the lab submenu should offer at least one tool");
    // No stray top level menu; the whole point is that it lives under Tools.
    assertEquals(3, menuBar.getMenus().size(), "no extra top level menu should be added");
  }

  @Test
  void everyLabToolHasALabelAndAnAction() throws Exception {
    final MenuBar menuBar = onFxThread(() -> {
      final MenuBar bar = new MenuBar();
      bar.getMenus().add(new Menu("Tools"));
      return LabToolsMenu.addTo(bar);
    });

    final Menu tools = menuBar.getMenus().getFirst();
    final Menu lab = (Menu) tools.getItems().getFirst();
    for (var item : lab.getItems()) {
      assertNotNull(item.getText(), "a lab tool has no label");
      assertFalse(item.getText().isBlank(), "a lab tool has a blank label");
      assertNotNull(item.getOnAction(), "lab tool '" + item.getText() + "' does nothing when clicked");
    }
  }

  @Test
  void toolsStayReachableWhenAWorkspaceHasNoToolsMenu() throws Exception {
    // A workspace is free not to offer a Tools menu; the tools must not silently vanish.
    final MenuBar menuBar = onFxThread(() -> {
      final MenuBar bar = new MenuBar();
      bar.getMenus().add(new Menu("Project"));
      return LabToolsMenu.addTo(bar);
    });

    assertTrue(menuBar.getMenus().stream()
            .anyMatch(menu -> LabToolsMenu.LAB_MENU_TITLE.equals(menu.getText())),
        "the lab menu should be added at the top level when there is no Tools menu");
  }

  private static MenuBar onFxThread(java.util.function.Supplier<MenuBar> action) throws Exception {
    FxThread.initJavaFx();
    final AtomicReference<MenuBar> result = new AtomicReference<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    FxThread.runOnFxThreadAndWait(() -> {
      try {
        result.set(action.get());
      } catch (Throwable error) {
        failure.set(error);
      }
    });
    if (failure.get() != null) {
      throw new AssertionError("building the menu failed", failure.get());
    }
    return result.get();
  }
}
