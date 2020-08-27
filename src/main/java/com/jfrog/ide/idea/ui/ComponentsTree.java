package com.jfrog.ide.idea.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.JBMenuItem;
import com.intellij.openapi.ui.JBPopupMenu;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiInvalidElementAccessException;
import com.intellij.ui.components.JBMenu;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.messages.MessageBusConnection;
import com.jfrog.ide.common.filter.FilterManager;
import com.jfrog.ide.common.utils.ProjectsMap;
import com.jfrog.ide.idea.events.ProjectEvents;
import com.jfrog.ide.idea.exclusion.Excludable;
import com.jfrog.ide.idea.exclusion.ExclusionUtils;
import com.jfrog.ide.idea.log.Logger;
import com.jfrog.ide.idea.navigation.NavigationService;
import com.jfrog.ide.idea.navigation.NavigationTarget;
import com.jfrog.ide.idea.scan.ScanManagersFactory;
import com.jfrog.ide.idea.ui.filters.FilterManagerService;
import com.jfrog.ide.idea.ui.filters.FilterMenu;
import com.jfrog.ide.idea.utils.Utils;
import org.jetbrains.annotations.NotNull;
import org.jfrog.build.extractor.scan.DependenciesTree;

import javax.swing.*;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * @author yahavi
 */
public class ComponentsTree extends Tree {

    private static final String SHOW_IN_PROJECT_DESCRIPTOR = "Show in project descriptor";
    private static final String EXCLUDE_DEPENDENCY = "Exclude dependency";

    private final List<FilterMenu<?>> filterMenus = new ArrayList<>();
    private final JBPopupMenu popupMenu = new JBPopupMenu();
    private ProjectsMap projects = new ProjectsMap();

    protected Project mainProject;

    public ComponentsTree(@NotNull Project mainProject) {
        super((TreeModel) null);
        this.mainProject = mainProject;
        expandRow(0);
        setRootVisible(false);
        setCellRenderer(new ComponentsTreeCellRenderer());
    }

    public static ComponentsTree getInstance(@NotNull Project project) {
        return ServiceManager.getService(project, ComponentsTree.class);
    }

    public void populateTree(DependenciesTree root) {
        filterMenus.forEach(FilterMenu::refresh);
        setModel(new DefaultTreeModel(root));
        validate();
        repaint();
    }

    public void reset() {
        projects = new ProjectsMap();
        setModel(null);
    }

    public void addFilterMenu(FilterMenu<?> filterMenu) {
        this.filterMenus.add(filterMenu);
    }

    public void addScanResults(String projectName, DependenciesTree dependenciesTree) {
        projects.put(projectName, dependenciesTree);
    }

    public void applyFiltersForAllProjects() {
        setModel(null);
        for (Map.Entry<ProjectsMap.ProjectKey, DependenciesTree> entry : projects.entrySet()) {
            applyFilters(entry.getKey());
        }
    }

    public void addOnProjectChangeListener(MessageBusConnection busConnection) {
        busConnection.subscribe(ProjectEvents.ON_SCAN_PROJECT_CHANGE, this::applyFilters);
    }

    public void applyFilters(ProjectsMap.ProjectKey projectKey) {
        DependenciesTree project = projects.get(projectKey);
        if (project == null) {
            return;
        }
        FilterManager filterManager = FilterManagerService.getInstance(mainProject);
        DependenciesTree filteredRoot = filterManager.applyFilters(project);
        filteredRoot.setIssues(filteredRoot.processTreeIssues());
        appendProjectWhenReady(filteredRoot);
        DumbService.getInstance(mainProject).smartInvokeLater(() -> ScanManagersFactory.getInstance(mainProject).runInspectionsForAllScanManagers());
    }

    protected void appendProjectWhenReady(DependenciesTree filteredRoot) {
        ApplicationManager.getApplication().invokeLater(() -> appendProject(filteredRoot));
    }

    public void appendProject(DependenciesTree filteredRoot) {
        // No projects in tree - Add filtered root as a single project and show only its children.
        if (getModel() == null) {
            populateTree(filteredRoot);
            return;
        }

        DependenciesTree root = (DependenciesTree) getModel().getRoot();
        // One project in tree - Append filtered root and the old root the a new empty parent node.
        if (root.getUserObject() != null) {
            DependenciesTree newRoot = filteredRoot;
            if (!Utils.areRootNodesEqual(root, filteredRoot)) {
                newRoot = new DependenciesTree();
                newRoot.add(root);
                newRoot.add(filteredRoot);
            }
            populateTree(newRoot);
            return;
        }

        // Two or more projects in tree - Append filtered root to the empty parent node.
        addOrReplace(root, filteredRoot);
        populateTree(root);
    }

    private int searchNode(DependenciesTree root, DependenciesTree filteredRoot) {
        Vector<DependenciesTree> children = root.getChildren();
        for (int i = 0; i < children.size(); i++) {
            if (Utils.areRootNodesEqual(children.get(i), filteredRoot)) {
                return i;
            }
        }
        return -1;
    }

    private void addOrReplace(DependenciesTree root, DependenciesTree filteredRoot) {
        int childIndex = searchNode(root, filteredRoot);
        if (childIndex >= 0) {
            root.remove(childIndex);
        }
        root.add(filteredRoot);
    }

    public void addRightClickListener() {
        MouseListener mouseListener = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handleContextMenu(ComponentsTree.this, e);
            }
        };
        addMouseListener(mouseListener);
    }

    private void handleContextMenu(ComponentsTree tree, MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }

        // Event is right-click.
        TreePath selPath = tree.getPathForLocation(e.getX(), e.getY());
        if (selPath == null) {
            return;
        }
        createNodePopupMenu((DependenciesTree) selPath.getLastPathComponent());
        popupMenu.show(tree, e.getX(), e.getY());
    }

    private void createNodePopupMenu(DependenciesTree selectedNode) {
        popupMenu.removeAll();
        NavigationService navigationService = NavigationService.getInstance(mainProject);
        Set<NavigationTarget> navigationCandidates = navigationService.getNavigation(selectedNode);
        DependenciesTree affectedNode = selectedNode;
        if (navigationCandidates == null) {
            // Find the direct dependency containing the selected dependency.
            affectedNode = navigationService.getNavigableParent(selectedNode);
            if (affectedNode == null) {
                return;
            }
            navigationCandidates = navigationService.getNavigation(affectedNode);
            if (navigationCandidates == null) {
                return;
            }
        }

        addNodeNavigation(navigationCandidates);
        addNodeExclusion(selectedNode, navigationCandidates, affectedNode);
    }

    private void addNodeNavigation(Set<NavigationTarget> navigationCandidates) {
        if (navigationCandidates.size() > 1) {
            addMultiNavigation(navigationCandidates);
        } else {
            addSingleNavigation(navigationCandidates.iterator().next());
        }
    }

    private void addSingleNavigation(NavigationTarget navigationTarget) {
        popupMenu.add(createNavigationMenuItem(navigationTarget, SHOW_IN_PROJECT_DESCRIPTOR));
    }

    private void addMultiNavigation(Set<NavigationTarget> navigationCandidates) {
        JMenu multiMenu = new JBMenu();
        multiMenu.setText(SHOW_IN_PROJECT_DESCRIPTOR);
        for (NavigationTarget navigationTarget : navigationCandidates) {
            String descriptorPath = getRelativizedDescriptorPath(navigationTarget);
            multiMenu.add(createNavigationMenuItem(navigationTarget, descriptorPath + " " + (navigationTarget.getLineNumber() + 1)));
        }
        popupMenu.add(multiMenu);
    }

    private String getRelativizedDescriptorPath(NavigationTarget navigationTarget) {
        String pathResult = "";
        try {
            VirtualFile descriptorVirtualFile = navigationTarget.getElement().getContainingFile().getVirtualFile();
            pathResult = descriptorVirtualFile.getName();
            String projBasePath = mainProject.getBasePath();
            if (projBasePath == null) {
                return pathResult;
            }
            Path basePath = Paths.get(mainProject.getBasePath());
            Path descriptorPath = Paths.get(descriptorVirtualFile.getPath());
            pathResult = basePath.relativize(descriptorPath).toString();
        } catch (InvalidPathException | PsiInvalidElementAccessException ex) {
            Logger log = Logger.getInstance(mainProject);
            log.error("Failed getting project-descriptor's path.", ex);
        }
        return pathResult;
    }

    private JMenuItem createNavigationMenuItem(NavigationTarget navigationTarget, String headLine) {
        return new JBMenuItem(new AbstractAction(headLine) {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!(navigationTarget.getElement() instanceof Navigatable)) {
                    return;
                }
                Navigatable navigatable = (Navigatable) navigationTarget.getElement();
                if (navigatable.canNavigate()) {
                    navigatable.navigate(true);
                }
            }
        });
    }

    private void addNodeExclusion(DependenciesTree nodeToExclude, Set<NavigationTarget> parentCandidates, DependenciesTree affectedNode) {
        if (parentCandidates.size() > 1) {
            addMultiExclusion(nodeToExclude, affectedNode, parentCandidates);
        } else {
            addSingleExclusion(nodeToExclude, affectedNode, parentCandidates.iterator().next());
        }
    }

    private void addMultiExclusion(DependenciesTree nodeToExclude, DependenciesTree affectedNode, Set<NavigationTarget> parentCandidates) {
        if (!ExclusionUtils.isExcludable(nodeToExclude, affectedNode)) {
            return;
        }
        JMenu multiMenu = new JBMenu();
        multiMenu.setText(EXCLUDE_DEPENDENCY);
        for (NavigationTarget parentCandidate : parentCandidates) {
            Excludable excludable = ExclusionUtils.getExcludable(nodeToExclude, affectedNode, parentCandidate);
            if (excludable == null) {
                continue;
            }
            String descriptorPath = getRelativizedDescriptorPath(parentCandidate);
            multiMenu.add(createExcludeMenuItem(excludable, descriptorPath + " " + (parentCandidate.getLineNumber() + 1)));
        }
        if (multiMenu.getItemCount() > 0) {
            popupMenu.add(multiMenu);
        }
    }

    private void addSingleExclusion(DependenciesTree nodeToExclude, DependenciesTree affectedNode, NavigationTarget parentCandidate) {
        Excludable excludable = ExclusionUtils.getExcludable(nodeToExclude, affectedNode, parentCandidate);
        if (excludable == null) {
            return;
        }
        popupMenu.add(createExcludeMenuItem(excludable, EXCLUDE_DEPENDENCY));
    }

    private JBMenuItem createExcludeMenuItem(Excludable excludable, String headLine) {
        return new JBMenuItem(new AbstractAction(headLine) {
            @Override
            public void actionPerformed(ActionEvent e) {
                excludable.exclude(mainProject);
            }
        });
    }
}