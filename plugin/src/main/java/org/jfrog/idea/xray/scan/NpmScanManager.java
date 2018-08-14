package org.jfrog.idea.xray.scan;

import com.fasterxml.jackson.databind.JsonNode;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.project.LibraryDependencyData;
import com.intellij.openapi.externalSystem.model.project.ProjectData;
import com.intellij.openapi.externalSystem.service.project.ExternalProjectRefreshCallback;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.jfrog.xray.client.impl.ComponentsFactory;
import com.jfrog.xray.client.impl.services.summary.ComponentDetailImpl;
import com.jfrog.xray.client.services.summary.Components;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.jfrog.idea.xray.ScanTreeNode;
import org.jfrog.idea.xray.persistency.types.GeneralInfo;
import org.jfrog.idea.xray.utils.Utils;
import org.jfrog.idea.xray.utils.npm.NpmDriver;
import org.jfrog.idea.xray.utils.npm.NpmPackageFileFinder;

import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeModel;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Created by Yahav Itzhak on 13 Dec 2017.
 */
public class NpmScanManager extends ScanManager {

    static final String NPM_PREFIX = "npm://";
    ScanTreeNode rootNode = new ScanTreeNode(ROOT_NODE_HEADER);
    private NpmDriver npmDriver;
    private Set<String> applicationsDirs;

    public NpmScanManager() {
    }

    public NpmScanManager(Project project, Set<String> applicationsDirs) {
        super(project);
        npmDriver = new NpmDriver();
        this.applicationsDirs = applicationsDirs;
    }

    private NpmScanManager(Set<String> applicationsDirs) {
        super();
        this.applicationsDirs = applicationsDirs;
    }

    static NpmScanManager CreateNpmScanManager(Project project, Set<String> applicationsDirs) {
        NpmScanManager npmScanManager = new NpmScanManager(applicationsDirs);
        npmScanManager.project = project;
        npmScanManager.npmDriver = new NpmDriver();
        return npmScanManager;
    }

    public static boolean isApplicable(Set<String> applicationsDirs) {
        // Check that npm is installed and application dirs exists
        return NpmDriver.isNpmInstalled() && CollectionUtils.isNotEmpty(applicationsDirs);
    }

    @Override
    protected void refreshDependencies(ExternalProjectRefreshCallback cbk, @Nullable Collection<DataNode<LibraryDependencyData>> libraryDependencies) {
        try {
            rootNode = new ScanTreeNode(ROOT_NODE_HEADER);
            for (String appDir : applicationsDirs) {
                checkCanceled();
                JsonNode jsonRoot = npmDriver.list(appDir);
                String packageName = jsonRoot.get("name").asText();
                if (jsonRoot.get("problems") != null) {
                    packageName += " (Missing Information)";
                    Utils.log(logger, "JFrog Xray - npm ls command result had errors", jsonRoot.get("problems").toString(), NotificationType.ERROR);
                }
                String packageVersion = jsonRoot.get("version").asText();
                ScanTreeNode module = new ScanTreeNode(packageName, true);
                module.setGeneralInfo(new GeneralInfo().componentId(packageName + ":" + packageVersion).pkgType("npm").path(appDir));
                rootNode.add(module);
                JsonNode dependencies = jsonRoot.get("dependencies");
                if (dependencies != null) {
                    dependencies.fields().forEachRemaining(stringJsonNodeEntry -> {
                        String componentId = getComponentId(stringJsonNodeEntry);
                        addSubtree(stringJsonNodeEntry, module, componentId); // Populate the tree recursively
                    });
                }
            }
            cbk.onSuccess(null);
        } catch (ProcessCanceledException e) {
            Utils.notify(logger, "JFrog Xray", "Xray scan was canceled", NotificationType.INFORMATION);
        } catch (Exception e) {
            cbk.onFailure(e.getMessage(), e.getCause().getMessage());
        }
    }

    @Override
    protected Components collectComponentsToScan(@Nullable DataNode<ProjectData> externalProject) {
        Components components = ComponentsFactory.create();
        addAllArtifacts(components, rootNode, NPM_PREFIX);
        return components;
    }

    @Override
    protected TreeModel updateResultsTree(TreeModel currentScanResults) {
        scanTree(rootNode);
        return new DefaultTreeModel(rootNode, false);
    }

    private void addSubtree(Map.Entry<String, JsonNode> stringJsonNodeEntry, ScanTreeNode node, String componentId) {
        if (StringUtils.isBlank(componentId)) {
            return;
        }
        ComponentDetailImpl scanComponent = new ComponentDetailImpl(componentId, "");
        ScanTreeNode childTreeNode = new ScanTreeNode(scanComponent);
        JsonNode childDependencies = stringJsonNodeEntry.getValue().get("dependencies");
        populateDependenciesTree(childTreeNode, childDependencies); // Mutual recursive call
        node.add(childTreeNode);
    }

    private void populateDependenciesTree(ScanTreeNode scanTreeNode, @Nullable JsonNode dependencies) {
        if (dependencies == null) {
            return;
        }
        dependencies.fields().forEachRemaining(stringJsonNodeEntry -> {
            String componentId = getComponentId(stringJsonNodeEntry);
            addSubtree(stringJsonNodeEntry, scanTreeNode, componentId); // Mutual recursive call
        });
    }

    private String getComponentId(Map.Entry<String, JsonNode> stringJsonNodeEntry) {
        String artifactId = stringJsonNodeEntry.getKey();
        JsonNode version = stringJsonNodeEntry.getValue().get("version");
        return version == null ? "" : artifactId + ":" + version.textValue();
    }

    /**
     * Get package.json parent directories.
     *
     * @param paths - Input - List of project base paths
     */
    public static Set<String> findApplicationDirs(Set<Path> paths) throws IOException {
        Set<String> applicationsDirs = new HashSet<>();
        NpmPackageFileFinder npmPackageFileFinder = new NpmPackageFileFinder(paths);
        applicationsDirs.addAll(npmPackageFileFinder.getPackageFilePairs());
        return applicationsDirs;
    }
}