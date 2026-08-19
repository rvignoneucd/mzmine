/*
 * Copyright (c) 2004-2026 The mzmine Development Team
 * SPDX-License-Identifier: MIT
 */
package io.github.mzmine.modules.visualization.rawdataoverview;

import io.github.mzmine.javafx.concurrent.threading.FxThread;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.text.Font;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shows a peak detection diagnosis in a window that stays out of the way.
 *
 * <p>The report exists to be acted on: read which threshold excluded a peak, then go and change it
 * in the batch. A modal dialog makes that impossible, since it blocks the rest of the application
 * until dismissed - so the finding has to be memorised or screenshotted first. This window is not
 * modal, defaults to staying on top, and keeps its text selectable so a threshold can be copied
 * straight into the batch step.</p>
 *
 * <p>One window is reused. Diagnosing several peaks in a row should replace the text rather than
 * bury the screen in stale windows, and the previous report is rarely wanted once a new peak has
 * been asked about.</p>
 */
public final class PeakDiagnosisWindow {

  private static @Nullable Stage stage;
  private static @Nullable TextArea reportArea;
  private static boolean stayOnTop = true;

  private PeakDiagnosisWindow() {
  }

  /**
   * Shows {@code report}, reusing the existing window when one is already open.
   *
   * <p>Focus is handed back to whatever the user was working in. Diagnosing a peak is something
   * done <em>while</em> editing a batch, so pulling the keyboard away each time would make the
   * window as disruptive as the modal dialog it replaced.</p>
   */
  public static void show(@NotNull String report) {
    FxThread.runLater(() -> {
      final Window previouslyFocused = Window.getWindows().stream()
          .filter(Window::isFocused).findFirst().orElse(null);

      final boolean firstShow = stage == null || !stage.isShowing();
      if (stage == null) {
        build();
      }
      if (reportArea != null) {
        reportArea.setText(report);
        reportArea.positionCaret(0);
        reportArea.setScrollTop(0);
      }
      if (stage != null && firstShow) {
        // Only bring it up when it was not already on screen; re-raising an open window is what
        // made it feel like it was grabbing focus on every diagnosis.
        stage.show();
      }

      if (previouslyFocused != null && previouslyFocused != stage) {
        // Deferred so it runs after the new window has finished taking focus.
        FxThread.runLater(previouslyFocused::requestFocus);
      }
    });
  }

  private static void build() {
    final TextArea area = new TextArea();
    area.setEditable(false);
    // The report lines up values in columns, so a proportional font would scramble it.
    area.setFont(Font.font("Monospaced", 12));
    area.setWrapText(false);
    reportArea = area;

    final CheckBox onTop = new CheckBox("Stay on top");
    onTop.setSelected(stayOnTop);
    onTop.setOnAction(event -> {
      stayOnTop = onTop.isSelected();
      if (stage != null) {
        stage.setAlwaysOnTop(stayOnTop);
      }
    });

    final Button copy = new Button("Copy");
    copy.setTooltip(new javafx.scene.control.Tooltip("Copy the whole report to the clipboard."));
    copy.setOnAction(event -> {
      final ClipboardContent content = new ClipboardContent();
      content.putString(area.getText());
      Clipboard.getSystemClipboard().setContent(content);
    });

    final Button close = new Button("Close");
    close.setOnAction(event -> {
      if (stage != null) {
        stage.hide();
      }
    });

    final HBox buttons = new HBox(8, onTop, copy, close);
    buttons.setPadding(new Insets(8));

    final BorderPane content = new BorderPane();
    content.setCenter(area);
    content.setBottom(buttons);

    final Stage created = new Stage();
    created.setTitle("Peak detection diagnosis");
    // Not modal: the point is to keep reading this while editing the batch.
    created.initModality(Modality.NONE);
    created.setAlwaysOnTop(stayOnTop);
    created.setScene(new Scene(content, 720, 520));
    stage = created;
  }
}
