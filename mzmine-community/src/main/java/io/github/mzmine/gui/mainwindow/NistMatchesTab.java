/*
 * Copyright (c) 2004-2026 The mzmine Development Team
 * SPDX-License-Identifier: MIT
 */
package io.github.mzmine.gui.mainwindow;

import io.github.mzmine.datamodel.RawDataFile;
import io.github.mzmine.javafx.concurrent.threading.FxThread;
import io.github.mzmine.datamodel.features.FeatureListRow;
import io.github.mzmine.main.MZmineCore;
import io.github.mzmine.modules.visualization.spectra.spectralmatchresults.SpectraIdentificationResultsWindowFX;
import io.github.mzmine.util.spectraldb.entry.SpectralDBAnnotation;
import io.github.mzmine.modules.dataprocessing.id_nist.NistMatchUtils;
import io.github.mzmine.modules.dataprocessing.id_nist.NistMatchUtils.NistMatch;
import io.github.mzmine.modules.visualization.rawdataoverview.NistChartLabelState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.BorderPane;
import javafx.util.StringConverter;
import org.jetbrains.annotations.Nullable;

/**
 * Browses NIST hits already stored on feature list rows, and drives their chart labels.
 *
 * <p>This lives in its own class rather than inside {@code MainWindowController} on purpose. That
 * controller and {@code MainWindow.fxml} are among the most frequently edited files upstream, so
 * several hundred lines of local feature code sitting in them turns every upstream merge into a
 * conflict. Here the same code costs one line in the controller and nothing in the FXML.</p>
 *
 * <p>Label state itself lives in {@link NistChartLabelState}, shared with the Raw Data Overview
 * charts, so a change made in this table repaints every open chart showing that file.</p>
 */
public class NistMatchesTab extends Tab {

  private static @Nullable NistMatchesTab instance;

  private final TableView<NistMatchSummary> nistMatchesTable = new TableView<>();
  private final Label nistMatchStatusLabel = new Label(
      "Scrub the chromatogram to inspect stored NIST hits.");
  private RawDataFile nistMatchFilterRawFile;
  private double nistMatchSelectionRt = Double.NaN;
  private boolean updatingNistLabelCheckboxes;

  public NistMatchesTab() {
    super("NIST matches");
    setClosable(false);

    final Button refresh = new Button("Refresh");
    refresh.setOnAction(event -> refreshNistMatches());
    nistMatchStatusLabel.setWrapText(true);
    nistMatchStatusLabel.setPadding(new Insets(5));

    final BorderPane content = new BorderPane();
    content.setMinHeight(0);
    content.setMinWidth(0);
    content.setTop(refresh);
    content.setCenter(nistMatchesTable);
    content.setBottom(nistMatchStatusLabel);
    setContent(content);

    initNistMatchesTable();
    instance = this;
  }

  /** The live tab, or {@code null} before the main window has been built. */
  public static @Nullable NistMatchesTab getInstance() {
    return instance;
  }

  /**
   * Selects the stored hit nearest {@code retentionTime} for {@code rawDataFile}. No-op when the
   * tab does not exist yet, so callers do not need to know whether the window has been built.
   */
  public static void selectMatchAt(@Nullable RawDataFile rawDataFile, double retentionTime) {
    final NistMatchesTab tab = instance;
    if (tab != null) {
      FxThread.runLater(() -> tab.setNistMatchSelection(rawDataFile, retentionTime));
    }
  }

  /** Re-reads stored hits into the table. No-op when the tab does not exist yet. */
  public static void refresh() {
    final NistMatchesTab tab = instance;
    if (tab != null) {
      FxThread.runLater(tab::refreshNistMatchesFromChart);
    }
  }

  /** Filters the table to one raw file. No-op when the tab does not exist yet. */
  public static void filterTo(@Nullable RawDataFile rawDataFile) {
    final NistMatchesTab tab = instance;
    if (tab != null) {
      FxThread.runLater(() -> tab.setNistMatchFilterRawFile(rawDataFile));
    }
  }

  private void initNistMatchesTable() {
    TableColumn<NistMatchSummary, Boolean> labelColumn = new TableColumn<>("Chart");
    labelColumn.setCellValueFactory(cell -> cell.getValue().labelVisible());
    labelColumn.setCellFactory(CheckBoxTableCell.forTableColumn(labelColumn));
    labelColumn.setEditable(true);
    labelColumn.setPrefWidth(52);

    TableColumn<NistMatchSummary, String> sampleColumn = new TableColumn<>("Sample");
    sampleColumn.setCellValueFactory(
        cell -> new SimpleStringProperty(cell.getValue().sampleName()));
    sampleColumn.setPrefWidth(145);

    TableColumn<NistMatchSummary, NistMatchSummary> compoundColumn = new TableColumn<>("NIST match");
    compoundColumn.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue()));
    compoundColumn.setCellFactory(column -> new NistCandidateComboCell());
    compoundColumn.setPrefWidth(235);

    TableColumn<NistMatchSummary, Number> matchColumn = new TableColumn<>("FMF");
    matchColumn.setCellValueFactory(
        cell -> new SimpleIntegerProperty(cell.getValue().forwardMatchFactor()));
    matchColumn.setPrefWidth(55);

    TableColumn<NistMatchSummary, Number> reverseMatchColumn = new TableColumn<>("RMF");
    reverseMatchColumn.setCellValueFactory(
        cell -> new SimpleIntegerProperty(cell.getValue().reverseMatchFactor()));
    reverseMatchColumn.setPrefWidth(55);

    TableColumn<NistMatchSummary, String> rtColumn = new TableColumn<>("RT");
    rtColumn.setCellValueFactory(
        cell -> new SimpleStringProperty("%.3f".formatted(cell.getValue().retentionTime())));
    rtColumn.setPrefWidth(60);

    TableColumn<NistMatchSummary, Number> hitsColumn = new TableColumn<>("Options");
    hitsColumn.setCellValueFactory(
        cell -> new SimpleIntegerProperty(cell.getValue().optionCount()));
    hitsColumn.setPrefWidth(58);

    nistMatchesTable.getColumns()
        .setAll(labelColumn, sampleColumn, compoundColumn, matchColumn, reverseMatchColumn, rtColumn,
            hitsColumn);
    nistMatchesTable.setEditable(true);
    nistMatchesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    nistMatchesTable.setPlaceholder(
        new Label("No NIST matches yet. Run NIST MSPepSearch in batch mode, then refresh."));
    installNistMatchContextMenu();
    nistMatchesTable.setOnMouseClicked(event -> {
      if (event.getClickCount() == 2) {
        NistMatchSummary selected = nistMatchesTable.getSelectionModel().getSelectedItem();
        if (selected != null) {
          SpectraIdentificationResultsWindowFX results = new SpectraIdentificationResultsWindowFX();
          results.setFeatureRows(List.of(selected.row()));
          MZmineCore.getDesktop().addTab(results);
        }
      }
    });
    setOnSelectionChanged(event -> {
      if (isSelected()) {
        refreshNistMatches();
      }
    });
  }

  private void installNistMatchContextMenu() {
    final RadioMenuItem vertical = new RadioMenuItem("Vertical chart label");
    final RadioMenuItem horizontal = new RadioMenuItem("Horizontal chart label");
    final ToggleGroup orientation = new ToggleGroup();
    vertical.setToggleGroup(orientation);
    horizontal.setToggleGroup(orientation);

    vertical.setOnAction(event -> setSelectedNistLabelHorizontal(false));
    horizontal.setOnAction(event -> setSelectedNistLabelHorizontal(true));

    final ContextMenu menu = new ContextMenu(vertical, horizontal);
    menu.setOnShowing(event -> {
      final NistMatchSummary selected = nistMatchesTable.getSelectionModel().getSelectedItem();
      final boolean disabled = selected == null;
      vertical.setDisable(disabled);
      horizontal.setDisable(disabled);
      if (!disabled && NistChartLabelState.isLabelHorizontal(
          selected.rawDataFile(), selected.retentionTime())) {
        horizontal.setSelected(true);
      } else {
        vertical.setSelected(true);
      }
    });
    nistMatchesTable.setContextMenu(menu);
  }

  private void setSelectedNistLabelHorizontal(boolean horizontal) {
    final NistMatchSummary selected = nistMatchesTable.getSelectionModel().getSelectedItem();
    if (selected == null) {
      return;
    }
    NistChartLabelState.setLabelHorizontal(selected.rawDataFile(),
        selected.retentionTime(), horizontal);
    updateNistMatchStatus(selected);
  }

  private void refreshNistMatches() {
    final List<NistMatchSummary> combined = new ArrayList<>();
    for (NistMatch match : NistMatchUtils.findMatches(nistMatchFilterRawFile)) {
      final NistMatchSummary samePeak = combined.stream().filter(
          summary -> summary.isSameRtPeak(match)).findFirst().orElse(null);
      if (samePeak == null) {
        combined.add(new NistMatchSummary(match,
            NistChartLabelState.isLabelVisible(match.rawDataFile(), match.retentionTime())));
      } else {
        samePeak.addCandidate(match);
      }
    }
    combined.forEach(NistMatchSummary::finishCandidates);
    combined.sort(Comparator.comparing(NistMatchSummary::sampleName)
        .thenComparingDouble(NistMatchSummary::retentionTime)
        .thenComparing(Comparator.comparingInt(NistMatchSummary::forwardMatchFactor).reversed()));
    combined.forEach(summary -> summary.labelVisible().addListener(
        (observable, oldValue, visible) -> setNistChartLabelVisible(summary, visible)));
    nistMatchesTable.getItems().setAll(combined);
    selectNistMatchAtCurrentRt();
  }

  public void refreshNistMatchesFromChart() {
    refreshNistMatches();
  }

  private void setNistChartLabelVisible(NistMatchSummary selected, boolean visible) {
    if (updatingNistLabelCheckboxes) {
      return;
    }
    updatingNistLabelCheckboxes = true;
    try {
      NistChartLabelState.setLabelVisible(selected.rawDataFile(),
          selected.retentionTime(), visible);
      updateNistMatchStatus(selected);
    } finally {
      updatingNistLabelCheckboxes = false;
    }
  }

  public void setNistMatchFilterRawFile(RawDataFile rawDataFile) {
    nistMatchFilterRawFile = rawDataFile;
    if (isSelected()) {
      refreshNistMatches();
    }
  }

  public void setNistMatchSelection(RawDataFile rawDataFile, double retentionTime) {
    final boolean rawChanged = !Objects.equals(nistMatchFilterRawFile, rawDataFile);
    nistMatchFilterRawFile = rawDataFile;
    nistMatchSelectionRt = retentionTime;
    if (isSelected()) {
      if (rawChanged) {
        refreshNistMatches();
      } else {
        selectNistMatchAtCurrentRt();
      }
    }
  }

  private void selectNistMatchAtCurrentRt() {
    if (!Double.isFinite(nistMatchSelectionRt) || nistMatchesTable.getItems().isEmpty()) {
      if (Double.isFinite(nistMatchSelectionRt)) {
        nistMatchStatusLabel.setText(
            "No stored NIST matches for this file near %.3f min.".formatted(nistMatchSelectionRt));
      }
      return;
    }
    final NistMatchSummary closest = nistMatchesTable.getItems().stream()
        .min(Comparator.comparingDouble(
            result -> Math.abs(result.retentionTime() - nistMatchSelectionRt))).orElse(null);
    if (closest == null || Math.abs(closest.retentionTime() - nistMatchSelectionRt) > 0.15d) {
      nistMatchesTable.getSelectionModel().clearSelection();
      nistMatchStatusLabel.setText(closest == null
          ? "No stored NIST hit near %.3f min.".formatted(nistMatchSelectionRt)
          : "No stored NIST hit within 0.15 min of %.3f (nearest: %.3f). Not removed by chart trimming."
              .formatted(nistMatchSelectionRt, closest.retentionTime()));
      return;
    }
    nistMatchesTable.getSelectionModel().select(closest);
    nistMatchesTable.scrollTo(closest);
    updateNistMatchStatus(closest);
  }

  private void updateNistMatchStatus(NistMatchSummary result) {
    final String orientation = NistChartLabelState.isLabelHorizontal(
        result.rawDataFile(), result.retentionTime()) ? "horizontal" : "vertical";
    nistMatchStatusLabel.setText(
        "Stored hit at %.3f min: %s (FMF %d, RMF %d, %d options). Chart label %s, %s."
        .formatted(result.retentionTime(), result.compoundName(), result.forwardMatchFactor(),
            result.reverseMatchFactor(),
            result.optionCount(), result.labelVisible().get() ? "shown" : "hidden", orientation));
  }

  private final class NistCandidateComboCell extends TableCell<NistMatchSummary, NistMatchSummary> {

    private final ComboBox<NistMatch> choices = new ComboBox<>();
    private boolean updating;

    private NistCandidateComboCell() {
      choices.setMaxWidth(Double.MAX_VALUE);
      choices.setVisibleRowCount(12);
      choices.setConverter(new StringConverter<>() {
        @Override
        public String toString(NistMatch match) {
          return match == null ? "" : "%s (FMF %d, RMF %d)".formatted(match.compoundName(),
              match.forwardMatchFactor(), match.reverseMatchFactor());
        }

        @Override
        public NistMatch fromString(String string) {
          return null;
        }
      });
      choices.setOnAction(event -> {
        if (updating || getItem() == null) {
          return;
        }
        final NistMatch selected = choices.getSelectionModel().getSelectedItem();
        if (selected != null && selected != getItem().selectedMatch()) {
          getItem().setSelectedMatch(selected);
          NistChartLabelState.setSelectedMatch(getItem().rawDataFile(), getItem().retentionTime(),
              selected);
          nistMatchesTable.refresh();
          updateNistMatchStatus(getItem());
        }
      });
    }

    @Override
    protected void updateItem(NistMatchSummary item, boolean empty) {
      super.updateItem(item, empty);
      if (empty || item == null) {
        setGraphic(null);
        return;
      }
      updating = true;
      choices.setItems(FXCollections.observableArrayList(item.candidates()));
      choices.getSelectionModel().select(item.selectedMatch());
      updating = false;
      setGraphic(choices);
    }
  }

  private static final class NistMatchSummary {

    private final RawDataFile rawDataFile;
    private final String sampleName;
    private final double peakRetentionTime;
    private final List<NistMatch> candidates = new ArrayList<>();
    private final BooleanProperty labelVisible;
    private NistMatch selectedMatch;

    private NistMatchSummary(NistMatch first, boolean labelVisible) {
      rawDataFile = first.rawDataFile();
      sampleName = first.sampleName();
      peakRetentionTime = first.retentionTime();
      this.labelVisible = new SimpleBooleanProperty(labelVisible);
      addCandidate(first);
    }

    private boolean isSameRtPeak(NistMatch other) {
      return Objects.equals(rawDataFile, other.rawDataFile())
          && sampleName.equals(other.sampleName())
          && NistMatchUtils.isSameChartPeakRetentionTime(peakRetentionTime,
          other.retentionTime());
    }

    private void addCandidate(NistMatch candidate) {
      final String candidateIdentity = identityKey(candidate);
      for (int index = 0; index < candidates.size(); index++) {
        final NistMatch existing = candidates.get(index);
        if (identityKey(existing).equals(candidateIdentity)) {
          if (candidate.matchFactor() > existing.matchFactor()) {
            candidates.set(index, candidate);
          }
          return;
        }
      }
      candidates.add(candidate);
    }

    private void finishCandidates() {
      candidates.sort(Comparator.comparingInt(NistMatch::matchFactor).reversed());
      final NistMatch previouslySelected = NistChartLabelState.getSelectedMatch(rawDataFile,
          peakRetentionTime);
      // Match on compound identity, not on record equality. The table rebuilds its NistMatch
      // records on every refresh, and they carry the raw file the table was filtered to, so a
      // stored choice made under a different filter would never compare equal - which silently
      // reverted the user's pick to the top hit each time the table refreshed.
      selectedMatch = candidates.getFirst();
      if (previouslySelected != null) {
        final String wanted = identityKey(previouslySelected);
        for (NistMatch candidate : candidates) {
          if (identityKey(candidate).equals(wanted)) {
            selectedMatch = candidate;
            break;
          }
        }
      }
    }

    private void setSelectedMatch(NistMatch selectedMatch) {
      this.selectedMatch = selectedMatch;
    }

    private FeatureListRow row() {
      return selectedMatch.row();
    }

    private SpectralDBAnnotation match() {
      return selectedMatch.match();
    }

    private RawDataFile rawDataFile() {
      return rawDataFile;
    }

    private String sampleName() {
      return sampleName;
    }

    private String compoundName() {
      return selectedMatch.compoundName();
    }

    private int forwardMatchFactor() {
      return selectedMatch.forwardMatchFactor();
    }

    private int reverseMatchFactor() {
      return selectedMatch.reverseMatchFactor();
    }

    private double retentionTime() {
      return peakRetentionTime;
    }

    private int optionCount() {
      return candidates.size();
    }

    private List<NistMatch> candidates() {
      return candidates;
    }

    private NistMatch selectedMatch() {
      return selectedMatch;
    }

    private BooleanProperty labelVisible() {
      return labelVisible;
    }

    private static String identityKey(NistMatch result) {
      final String cas = result.match().getCAS();
      if (cas != null && !cas.isBlank()) {
        return "cas:" + cas.replaceAll("[^0-9]", "");
      }
      return "name:" + result.compoundName().strip().toLowerCase(Locale.ROOT);
    }
  }
}
