package me.wulfmarius.modinstaller.ui;

import static me.wulfmarius.modinstaller.ui.ControllerFactory.CONTROLLER_FACTORY;
import static me.wulfmarius.modinstaller.ui.ModInstallerUI.*;

import java.io.IOException;
import java.nio.file.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.value.ObservableValue;
import javafx.css.PseudoClass;
import javafx.fxml.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.TableColumn.CellDataFeatures;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.Pane;
import javafx.util.Callback;
import me.wulfmarius.modinstaller.*;
import me.wulfmarius.modinstaller.repository.*;
import me.wulfmarius.modinstaller.update.UpdateState;

public class InstallerMainPanelController {

    protected static final PseudoClass PSEUDO_CLASS_UPDATE_AVAILABLE = PseudoClass.getPseudoClass("update-available");
    protected static final PseudoClass PSEUDO_CLASS_RECENT = PseudoClass.getPseudoClass("recent");
    protected static final PseudoClass PSEUDO_CLASS_NOT_INSTALLED = PseudoClass.getPseudoClass("not-installed");
    protected static final PseudoClass PSEUDO_CLASS_REQUIRED = PseudoClass.getPseudoClass("required");

    private static final String DEFAULT_SOURCE_DEFINITION = "https://raw.githubusercontent.com/WulfMarius/Mod-Installer/master/descriptions/default-mod-installer-description.json";

    private final ModInstaller modInstaller;

    @FXML
    private Node root;

    @FXML
    private Pane detailsPane;

    @FXML
    private TableView<ModDefinition> tableView;

    @FXML
    private TableColumn<ModDefinition, String> columnName;
    @FXML
    private TableColumn<ModDefinition, String> columnInstalledVersion;
    @FXML
    private TableColumn<ModDefinition, String> columnAvailableVersion;
    @FXML
    private TableColumn<ModDefinition, Date> columnReleaseDate;

    public InstallerMainPanelController(ModInstaller modInstaller) {
        super();
        this.modInstaller = modInstaller;
    }

    public static <R, T> Callback<TableColumn<R, T>, TableCell<R, T>> getFormattedCell(Format format) {
        return column -> {
            return new TableCell<R, T>() {

                @Override
                protected void updateItem(T item, boolean empty) {
                    super.updateItem(item, empty);
                    if (item == null || empty) {
                        this.setText(null);
                    } else {
                        this.setText(format.format(item));
                    }
                }
            };
        };
    }

    public boolean isRecent(ModDefinition modDefinition) {
        if (modDefinition.getLastUpdated() == null) {
            return false;
        }

        return modDefinition.getLastUpdated().getTime() >= System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)
                && this.isNotInstalled(modDefinition);
    }

    protected boolean isNotInstalled(ModDefinition modDefinition) {
        return !this.modInstaller.isAnyVersionInstalled(modDefinition);
    }

    protected boolean isRequired(ModDefinition modDefinition) {
        return this.modInstaller.isRequiredByInstallation(modDefinition);
    }

    protected boolean isUpdateAvailable(ModDefinition modDefinition) {
        return this.modInstaller.isOtherVersionInstalled(modDefinition);
    }

    @FXML
    private void addSource() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Add Source");
        dialog.setHeaderText(null);
        dialog.setContentText("Enter Source");

        Optional<String> optional = dialog.showAndWait();
        optional.ifPresent(this::addSource);
    }

    private void addSource(String sourceDefinition) {
        startProgressDialog("Adding Source " + sourceDefinition, this.detailsPane,
                () -> this.modInstaller.registerSource(sourceDefinition));
    }

    private void askDeleteOtherVersions() {
        Path[] otherVersions = this.modInstaller.getOtherVersions();
        String message = Arrays.stream(otherVersions).map(Path::getFileName).map(Path::toString).collect(Collectors.joining("\n",
                "The following other versions of Mod-Installer are still present:\n", "\n\nDo you want to delete them now?"));

        ModInstallerUI.showYesNoChoice("Other Versions Present", message, () -> {
            for (Path eachOldVersion : otherVersions) {
                try {
                    Files.deleteIfExists(eachOldVersion);
                } catch (IOException e) {
                    showError("Could Not Delete", "Could not delete " + eachOldVersion.getFileName() + ": " + e.getMessage());
                }
            }
        });
    }

    private void askDownloadNewVersion() {
        UpdateState updateState = this.modInstaller.getUpdateState();

        String message;
        if (updateState.hasAsset()) {
            message = "Version " + updateState.getLatestVersion() + " is available for download.\nWould you like to download it now?";
        } else {
            message = "Version " + updateState.getLatestVersion()
                    + " is available for download.\nWould you like to go to the download page?";
        }

        ModInstallerUI.showYesNoChoice("Update Available", message, () -> {
            if (updateState.hasAsset()) {
                startProgressDialog("Download Update", this.root, this.modInstaller::prepareUpdate, this::startUpdate);
            } else {
                openURL(updateState.getReleaseUrl());
            }
        });
    }

    private void askInstallDefaultSource() {
        ModInstallerUI.showYesNoChoice("No Sources Found",
                "It looks like you don't have any sources yet. Would you like to import the default source now?",
                () -> this.addSource(DEFAULT_SOURCE_DEFINITION));
    }

    private void askQuestions() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::askQuestions);
            return;
        }

        if (this.modInstaller.isNewVersionAvailable()) {
            this.askDownloadNewVersion();
        }

        if (this.modInstaller.areOtherVersionsPresent()) {
            this.askDeleteOtherVersions();
        }

        if (this.modInstaller.getSources().isEmpty()) {
            this.askInstallDefaultSource();
        } else {
            this.migrateSources();
        }
    }

    private TableCell<ModDefinition, String> createTableCell(@SuppressWarnings("unused") TableColumn<ModDefinition, String> column) {
        return new ModStatusTableCell();
    }

    private TableRow<ModDefinition> createTableRow(@SuppressWarnings("unused") TableView<ModDefinition> view) {
        return new ModStatusTableRow();
    }

    private ObservableValue<String> getInstalledVersion(CellDataFeatures<ModDefinition, String> features) {
        return new ReadOnlyStringWrapper(this.modInstaller.getInstalledVersion(features.getValue().getName()));
    }

    @FXML
    private void initialize() throws IOException {
        WindowBecameVisibleHandler.install(this.root, this::initializeModInstaller);
        this.modInstaller.addInstallationsChangedListener(this::installationsChanged);
        this.modInstaller.addSourcesChangedListener(this::sourcesChanged);

        FXMLLoader fxmlLoader = new FXMLLoader(this.getClass().getResource("ModDetailsPanel.fxml"));
        fxmlLoader.setControllerFactory(CONTROLLER_FACTORY);
        Node detailsPanel = fxmlLoader.load();
        this.detailsPane.getChildren().setAll(detailsPanel);

        this.columnName.setCellValueFactory(new PropertyValueFactory<>("name"));
        this.columnName.setCellFactory(this::createTableCell);
        this.columnAvailableVersion.setCellValueFactory(new PropertyValueFactory<>("version"));
        this.columnInstalledVersion.setCellValueFactory(this::getInstalledVersion);
        this.columnReleaseDate.setCellValueFactory(new PropertyValueFactory<>("releaseDate"));
        this.columnReleaseDate.setCellFactory(getFormattedCell(DateFormat.getDateInstance(DateFormat.SHORT)));

        this.tableView.setRowFactory(this::createTableRow);
        this.tableView.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldValue, newValue) -> detailsPanel.fireEvent(ModInstallerEvent.modSelected(newValue)));
        this.tableView.boundsInLocalProperty().addListener((obs, oldValue, newValue) -> Platform.runLater(this.tableView::refresh));

        this.tableView.getSortOrder().add(this.columnName);
    }

    private void initializeModInstaller() {
        this.modInstaller.initialize();

        this.askQuestions();
    }

    private void installationsChanged() {
        Platform.runLater(this.tableView::sort);
        Platform.runLater(this.tableView::refresh);
    }

    private void migrateSources() {
        boolean requiresRefresh = this.modInstaller.getSources().stream()
                .anyMatch(source -> !source.hasParameterValue(SourceFactory.PARAMETER_VERSION, Source.VERSION));

        if (requiresRefresh) {
            this.modInstaller.invalidateSources();
            this.refreshSources();
        }
    }

    @FXML
    private void refreshSources() {
        ModInstallerUI.startProgressDialog("Refreshing Sources", this.detailsPane, () -> this.modInstaller.refreshSources());
    }

    private void sourcesChanged() {
        this.tableView.getItems().setAll(this.modInstaller.getLatestVersions());
        Platform.runLater(this.tableView::sort);
        Platform.runLater(this.tableView::refresh);
    }

    private void startUpdate() {
        if (!this.modInstaller.hasDownloadedNewVersion()) {
            return;
        }

        try {
            this.modInstaller.startUpdate();
            System.exit(0);
        } catch (Exception e) {
            showError("Could Not Start",
                    "The downloaded version could not be started: " + e.getMessage() + "\n\nYou should try to start it manually.");
        }
    }

    protected class ModStatusTableCell extends TableCell<ModDefinition, String> {

        @Override
        protected void updateItem(String item, boolean empty) {
            if (item == this.getItem()) {
                return;
            }

            super.updateItem(item, empty);
            this.setText(item);
            this.setGraphic(null);

            StringBuilder stringBuilder = new StringBuilder();

            ModDefinition modDefinition = (ModDefinition) this.getTableRow().getItem();
            if (modDefinition != null) {
                boolean updateAvailable = InstallerMainPanelController.this.isUpdateAvailable(modDefinition);
                this.pseudoClassStateChanged(PSEUDO_CLASS_UPDATE_AVAILABLE, updateAvailable);
                if (updateAvailable) {
                    stringBuilder.append("An newer version is available.");
                }

                boolean required = InstallerMainPanelController.this.isRequired(modDefinition);
                this.pseudoClassStateChanged(PSEUDO_CLASS_REQUIRED, required);
                if (required) {
                    stringBuilder.append("\nCannot be uninstalled because it is required by another mod.");
                }

                boolean recent = InstallerMainPanelController.this.isRecent(modDefinition);
                this.pseudoClassStateChanged(PSEUDO_CLASS_RECENT, recent);
                if (recent) {
                    stringBuilder.append("\nThis version was added or updated recently.");
                }
            }

            if (stringBuilder.length() == 0) {
                this.setTooltip(null);
            } else {
                this.setTooltip(new Tooltip(stringBuilder.toString().trim()));
            }
        }
    }

    protected class ModStatusTableRow extends TableRow<ModDefinition> {

        @Override
        protected void updateItem(ModDefinition item, boolean empty) {
            super.updateItem(item, empty);

            if (!empty) {
                this.pseudoClassStateChanged(PSEUDO_CLASS_NOT_INSTALLED, InstallerMainPanelController.this.isNotInstalled(item));
            }
        }
    }
}
