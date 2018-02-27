/*
 * Licensed to the Indoqa Software Design und Beratung GmbH (Indoqa) under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Indoqa licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.indoqa.zookeeper.browser;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Timer;
import java.util.function.BooleanSupplier;

import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZooKeeperBrowserViewer implements TreeWillExpandListener, TreeSelectionListener {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private JFrame frame;

    private JTree tree;
    private DefaultTreeModel treeModel;
    private JTextArea textArea;
    private NodeProvider nodeProvider;

    private final Timer timer = new Timer(true);

    private boolean autoUpdate;
    private String selectedZookeeperPath;

    private Set<UpdateableButtonModel> buttonModels = new HashSet<>();

    public ZooKeeperBrowserViewer() {
        this.frame = new JFrame("ZooKeeper Browser");
        this.frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        this.frame.setLayout(new BorderLayout());

        this.frame.add(this.createActionPanel(), BorderLayout.NORTH);
        this.frame.add(this.createMainPanel(), BorderLayout.CENTER);

        this.frame.setSize(800, 600);
        this.frame.setLocationRelativeTo(null);

        this.frame.setVisible(true);

        this.timer.schedule(new TimerTask() {

            @Override
            public void run() {
                ZooKeeperBrowserViewer.this.autoUpdate();
            }
        }, 1000, 1000);
    }

    public Set<String> getExpandedZooKeeperPaths() {
        Set<String> result = new TreeSet<>();

        Enumeration<TreePath> expandedDescendants = this.tree.getExpandedDescendants(new TreePath(this.tree.getModel().getRoot()));
        if (expandedDescendants != null) {
            while (expandedDescendants.hasMoreElements()) {
                TreePath treePath = expandedDescendants.nextElement();
                ZooKeeperTreeNode treeNode = (ZooKeeperTreeNode) treePath.getLastPathComponent();
                result.add(((NodeDetails) treeNode.getUserObject()).getPath());
            }
        }

        return result;
    }

    public void setConnectString(String connectString) {
        this.frame.setTitle("ZooKeeper Browser - " + connectString);
    }

    @SuppressWarnings("unchecked")
    public void setExpandedZooKeeperPaths(Set<String> expandedPaths) {
        DefaultMutableTreeNode rootNode = (DefaultMutableTreeNode) this.tree.getModel().getRoot();
        Enumeration<ZooKeeperTreeNode> nodes = rootNode.breadthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            ZooKeeperTreeNode treeNode = nodes.nextElement();

            if (expandedPaths.contains(treeNode.getZooKeeperPath())) {
                this.tree.expandPath(new TreePath(treeNode.getPath()));
            }
        }
    }

    public void setModel(TreeModel model) {
        this.tree.setModel(model);
    }

    public void setNodeProvider(NodeProvider nodeProvider) {
        this.nodeProvider = nodeProvider;
    }

    @Override
    public void treeWillCollapse(TreeExpansionEvent event) throws ExpandVetoException {
        // nothing to do
    }

    @Override
    public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
        TreePath path = event.getPath();
        ZooKeeperTreeNode node = (ZooKeeperTreeNode) path.getLastPathComponent();

        if (node.isLeaf() || node.getChildCount() > 0) {
            return;
        }

        List<ZooKeeperTreeNode> children = this.nodeProvider.getChildren(node.getZooKeeperPath());
        for (ZooKeeperTreeNode eachChild : children) {
            this.treeModel.insertNodeInto(eachChild, node, node.getChildCount());
        }

        this.treeModel.nodeChanged(node);
    }

    public void updateContent() {
        Set<String> expandedZooKeeperPaths = this.getExpandedZooKeeperPaths();

        ZooKeeperTreeNode rootNode = this.nodeProvider.getNode("/");
        this.treeModel = new DefaultTreeModel(rootNode);

        List<ZooKeeperTreeNode> children = this.nodeProvider.getChildren("/");
        for (ZooKeeperTreeNode eachChild : children) {
            this.treeModel.insertNodeInto(eachChild, rootNode, rootNode.getChildCount());
        }

        this.tree.setModel(this.treeModel);
        this.resizeTree();

        for (String eachExpandedZooKeeperPath : expandedZooKeeperPaths) {
            SwingUtilities.invokeLater(() -> this.expandZooKeeperPath(eachExpandedZooKeeperPath));
        }
    }

    @Override
    public void valueChanged(TreeSelectionEvent e) {
        TreePath selectionPath = this.tree.getSelectionPath();
        if (selectionPath == null) {
            this.setSelectedZookeeperPath(null);
        } else {
            ZooKeeperTreeNode node = (ZooKeeperTreeNode) selectionPath.getLastPathComponent();
            this.setSelectedZookeeperPath(node.getZooKeeperPath());
        }

        this.loadSelectedContent();
    }

    protected void autoUpdate() {
        if (this.autoUpdate) {
            try {
                this.updateContent();
            } catch (Exception e) {
                this.logger.error("Auto-Update failed", e);
            }
        }
    }

    protected void toggleAutoUpdate() {
        this.autoUpdate = !this.autoUpdate;
    }

    private JPanel createActionPanel() {
        JPanel result = new JPanel();

        result.setLayout(new FlowLayout(FlowLayout.LEFT));

        result.add(new JButton(new AbstractAction("Reload") {

            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                ZooKeeperBrowserViewer.this.updateContent();
            }
        }));

        result.add(new JToggleButton(new AbstractAction("Auto-Reload") {

            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                ZooKeeperBrowserViewer.this.toggleAutoUpdate();
            }
        }));

        return result;
    }

    private JPanel createContentPanel() {
        JPanel result = new JPanel(new BorderLayout());

        this.textArea = new JTextArea();
        result.add(new JScrollPane(this.textArea), BorderLayout.CENTER);

        JPanel pnlButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        pnlButtons.add(this.createReloadContentButton());
        pnlButtons.add(this.createSaveContentButton());
        result.add(pnlButtons, BorderLayout.SOUTH);

        return result;
    }

    private JButton createDeleteNodeButton() {
        JButton result = new JButton("Delete");

        UpdateableButtonModel model = new UpdateableButtonModel(() -> this.getSelectedZookeeperPath() != null);
        this.buttonModels.add(model);
        result.setModel(model);
        result.addActionListener(event -> this.deleteNode());

        return result;
    }

    private JComponent createMainPanel() {
        JSplitPane result = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

        result.setLeftComponent(this.createTreePanel());
        result.setRightComponent(this.createContentPanel());
        result.setDividerLocation(400);

        return result;
    }

    private void createNewNode() {
        if (this.selectedZookeeperPath == null) {
            return;
        }

        String name = JOptionPane.showInputDialog(this.frame, "Node Name");
        if (name == null) {
            return;
        }

        this.nodeProvider.createChild(this.selectedZookeeperPath, name);
        this.updateContent();
    }

    private JButton createNewNodeButton() {
        JButton result = new JButton("New Child");

        UpdateableButtonModel buttonModel = new UpdateableButtonModel(() -> this.getSelectedZookeeperPath() != null);
        this.buttonModels.add(buttonModel);
        result.setModel(buttonModel);
        result.addActionListener(event -> this.createNewNode());

        return result;
    }

    private JButton createReloadContentButton() {
        JButton result = new JButton("Reload");

        UpdateableButtonModel buttonModel = new UpdateableButtonModel(() -> this.getSelectedZookeeperPath() != null);
        this.buttonModels.add(buttonModel);
        result.setModel(buttonModel);
        result.addActionListener(event -> this.loadSelectedContent());

        return result;
    }

    private JButton createSaveContentButton() {
        JButton result = new JButton("Save");

        UpdateableButtonModel buttonModel = new UpdateableButtonModel(() -> this.getSelectedZookeeperPath() != null);
        this.buttonModels.add(buttonModel);
        result.setModel(buttonModel);
        result.addActionListener(event -> this.saveContent());

        return result;
    }

    private JPanel createTreePanel() {
        JPanel result = new JPanel(new BorderLayout());

        this.tree = new JTree(new DefaultTreeModel(new ZooKeeperTreeNode(new NodeDetails())));
        this.tree.setShowsRootHandles(true);
        this.tree.addTreeWillExpandListener(this);
        this.tree.addTreeSelectionListener(this);
        this.tree.setCellRenderer(new ZooKeeperTreeNodeRenderer());
        this.tree.setRowHeight(20);
        result.add(new JScrollPane(this.tree), BorderLayout.CENTER);

        JPanel pnlButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        pnlButtons.add(this.createDeleteNodeButton());
        pnlButtons.add(this.createNewNodeButton());
        result.add(pnlButtons, BorderLayout.SOUTH);

        return result;
    }

    private void deleteNode() {
        if (this.selectedZookeeperPath == null) {
            return;
        }

        if (JOptionPane.showConfirmDialog(this.frame,
            "Delete node '" + this.selectedZookeeperPath + "'?\n\nThere is no way to undo this!", "Delete node",
            JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }

        this.nodeProvider.deleteNode(this.selectedZookeeperPath);
        this.updateContent();
    }

    @SuppressWarnings("unchecked")
    private void expandZooKeeperPath(String zooKeeperPath) {
        DefaultMutableTreeNode rootNode = (DefaultMutableTreeNode) this.treeModel.getRoot();
        Enumeration<ZooKeeperTreeNode> nodes = rootNode.breadthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            ZooKeeperTreeNode treeNode = nodes.nextElement();

            if (zooKeeperPath.equals(treeNode.getZooKeeperPath())) {
                this.tree.expandPath(new TreePath(treeNode.getPath()));
                break;
            }
        }

        this.resizeTree();
    }

    private String getSelectedZookeeperPath() {
        return this.selectedZookeeperPath;
    }

    private void loadSelectedContent() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> this.loadSelectedContent());
        }

        if (this.selectedZookeeperPath == null) {
            this.textArea.setText("");
            return;
        }

        byte[] content = this.nodeProvider.getContent(this.selectedZookeeperPath);
        if (content == null) {
            this.textArea.setText("");
        } else {
            this.textArea.setText(new String(content, StandardCharsets.UTF_8));
        }
    }

    private void resizeTree() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> this.resizeTree());
        }

        SwingUtilities.getAncestorOfClass(JPanel.class, this.tree).revalidate();
    }

    private void saveContent() {
        if (this.selectedZookeeperPath == null) {
            return;
        }

        byte[] content = this.textArea.getText().getBytes(StandardCharsets.UTF_8);
        this.nodeProvider.setContent(this.selectedZookeeperPath, content);
    }

    private void setSelectedZookeeperPath(String selectedZookeeperPath) {
        this.selectedZookeeperPath = selectedZookeeperPath;

        for (UpdateableButtonModel eachButtonModel : this.buttonModels) {
            eachButtonModel.update();
        }
    }

    public static final class UpdateableButtonModel extends DefaultButtonModel {

        private static final long serialVersionUID = 1L;

        private transient BooleanSupplier supplier;

        public UpdateableButtonModel(BooleanSupplier supplier) {
            super();

            this.supplier = supplier;
            this.update();
        }

        public void update() {
            this.setEnabled(this.supplier.getAsBoolean());
        }
    }
}
