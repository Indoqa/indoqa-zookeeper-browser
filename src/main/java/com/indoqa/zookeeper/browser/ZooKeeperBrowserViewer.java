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

import static java.awt.event.InputEvent.SHIFT_MASK;

import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.NumberFormat;
import java.util.*;
import java.util.List;
import java.util.Timer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

import javax.swing.*;
import javax.swing.JToggleButton.ToggleButtonModel;
import javax.swing.border.EmptyBorder;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreePath;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZooKeeperBrowserViewer implements TreeWillExpandListener, TreeSelectionListener {

    private static final String BASE_TITLE = "Indoqa ZooKeeper Browser";
    private static final int DEFAULT_MAX_CHILDREN = 100;
    private static final int MAX_UPDATE_TIME = 500;

    private static final int WATCH_DOG_UPDATE_DELAY = 100;
    private static final int CONNECT_TIMEOUT = 30_000;

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private JFrame frame;
    private JTree tree;

    private DefaultTreeModel treeModel;
    private JTextArea textArea;
    private NodeProvider nodeProvider;
    private JComboBox<String> cbxHost;

    private String selectedZookeeperPath;
    private Set<ComponentEnabler> componentEnablers = new HashSet<>();

    private JToggleButton tglConnect;

    private final BlockingQueue<ZooKeeperTreeNode> pendingNodes = new LinkedBlockingQueue<>();
    private final Timer timer = new Timer(true);

    private boolean autoUpdate;
    private Set<String> knownHosts = new TreeSet<>();

    private ConnectionWatchDog watchDog;
    private JProgressBar pgrLoading;
    private Operation currentOperation;
    private JFileChooser fileChooser;
    private JTextField txtNodeDetails;

    public ZooKeeperBrowserViewer() {
        this.frame = new JFrame();
        this.frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        this.frame.setLayout(new BorderLayout());
        this.frame.setIconImage(Toolkit.getDefaultToolkit().getImage(this.getClass().getResource("/icon.png")));
        this.frame.addWindowListener(new WindowAdapter() {

            @Override
            public void windowClosed(WindowEvent e) {
                ZooKeeperBrowserViewer.this.disconnect();
            }
        });

        this.knownHosts.addAll(this.readKnownHosts());

        this.frame.add(this.createActionPanel(), BorderLayout.NORTH);
        this.frame.add(this.createMainPanel(), BorderLayout.CENTER);

        this.frame.setSize(800, 600);
        this.frame.setLocationRelativeTo(null);

        this.frame.setVisible(true);

        this.fileChooser = new JFileChooser();

        this.timer.schedule(new TimerTask() {

            @Override
            public void run() {
                ZooKeeperBrowserViewer.this.autoUpdate();
            }
        }, 5000, 5000);

        this.timer.schedule(new TimerTask() {

            @Override
            public void run() {
                ZooKeeperBrowserViewer.this.updatePendingNodes();
            }
        }, 10, 10);

        Thread.setDefaultUncaughtExceptionHandler(this::uncaughtException);
    }

    private static Path getKnownHostsFile() {
        return Paths.get(System.getProperty("user.home"), ".indoqa-zookeeper-browser/known-hosts.txt").toAbsolutePath();
    }

    public void clearContent() {
        this.tree.setModel(new DefaultTreeModel(null));
        this.resizeTree();
    }

    public void connectionStateChanged() {
        ConnectionState connectionState = this.nodeProvider.getConnectionState();
        String zookeeperHost = this.nodeProvider.getZookeeperHost();

        this.tglConnect.setText(connectionState.name());
        this.componentEnablers.forEach(ComponentEnabler::update);

        switch (connectionState) {
            case INITIALIZING:
                this.cbxHost.setEnabled(false);
                this.tglConnect.setEnabled(false);
                this.tglConnect.setSelected(true);
                this.updateTitle(null);
                this.clearContent();
                break;

            case DISCONNECTING:
                this.cbxHost.setEnabled(false);
                this.tglConnect.setEnabled(false);
                this.tglConnect.setSelected(true);
                this.updateTitle(null);
                this.clearContent();
                break;

            case DISCONNECTED:
                this.cbxHost.setEnabled(true);
                this.tglConnect.setEnabled(true);
                this.tglConnect.setSelected(false);
                this.tglConnect.setText("Connect");
                this.updateTitle(null);
                this.clearContent();
                break;

            case CONNECTING:
                this.cbxHost.setEnabled(false);
                this.tglConnect.setEnabled(true);
                this.tglConnect.setSelected(true);
                this.updateTitle(zookeeperHost);
                break;

            case CONNECTED:
                if (this.watchDog != null) {
                    this.watchDog.cancel();
                }

                this.cbxHost.setEnabled(false);
                this.tglConnect.setEnabled(true);
                this.tglConnect.setSelected(true);
                this.updateTitle(zookeeperHost);

                this.addKnownHost(zookeeperHost);

                break;

            default:
                break;
        }
    }

    public Set<String> getExpandedZooKeeperPaths() {
        Set<String> result = new TreeSet<>();

        if (this.tree == null || this.tree.getModel() == null || this.tree.getModel().getRoot() == null) {
            result.add("/");
            return result;
        }

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

    public void operationCompleted(Operation operation, String path) {
        this.currentOperation = null;

        this.pgrLoading.setVisible(false);
        this.componentEnablers.forEach(ComponentEnabler::update);
    }

    public void operationStarted(Operation operation, String path) {
        this.currentOperation = operation;

        this.pgrLoading.setVisible(true);
        this.componentEnablers.forEach(ComponentEnabler::update);
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

    public void setNodeProvider(NodeProvider nodeProvider) {
        this.nodeProvider = nodeProvider;
        this.connectionStateChanged();
    }

    @Override
    public void treeWillCollapse(TreeExpansionEvent event) throws ExpandVetoException {
        // nothing to do
    }

    @Override
    public void treeWillExpand(TreeExpansionEvent event) {
        ZooKeeperTreeNode node = (ZooKeeperTreeNode) event.getPath().getLastPathComponent();
        this.buildChildren(node);
    }

    @Override
    public void valueChanged(TreeSelectionEvent e) {
        TreePath selectionPath = this.tree.getSelectionPath();
        if (selectionPath == null) {
            this.setSelectedZookeeperPath(null);
            this.txtNodeDetails.setText(null);
        } else {
            ZooKeeperTreeNode node = (ZooKeeperTreeNode) selectionPath.getLastPathComponent();
            this.setSelectedZookeeperPath(node.getZooKeeperPath());
            NodeDetails nodeDetails = node.getNodeDetails();
            String created = nodeDetails.getCreated().toInstant().toString();
            String modified = nodeDetails.getModified().toInstant().toString();
            this.txtNodeDetails.setText(
                nodeDetails.getPath() + ", Created: " + created + ", Modified: " + modified + ", Version: "
                    + nodeDetails.getVersion() + ", Children: " + nodeDetails.getChildren());
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

    protected void disconnect() {
        ZooKeeperBrowserViewer.this.nodeProvider.disconnect();
    }

    protected void toggleAutoUpdate() {
        this.autoUpdate = !this.autoUpdate;
    }

    protected void updateContent() {
        if (SwingUtilities.isEventDispatchThread()) {
            new Thread(this::updateContent).start();
            return;
        }

        this.operationStarted(Operation.LOAD_CHILDREN, "/");

        this.pendingNodes.clear();
        Set<String> expandedZooKeeperPaths = this.getExpandedZooKeeperPaths();

        try {
            ZooKeeperTreeNode rootNode = this.nodeProvider.getNode("/");
            this.pendingNodes.add(rootNode);

            for (String eachExpandedPath : expandedZooKeeperPaths) {
                ZooKeeperTreeNode node = rootNode.getNodeWithPath(eachExpandedPath);
                if (node != null) {
                    this.buildChildren(node);
                }
            }

            this.treeModel = new DefaultTreeModel(rootNode);

            SwingUtilities.invokeLater(() -> {
                this.tree.setModel(this.treeModel);
                this.resizeTree();
            });
        } finally {
            SwingUtilities.invokeLater(() -> {
                for (String eachPath : expandedZooKeeperPaths) {
                    this.expandZooKeeperPath(eachPath);
                }
            });

            this.operationCompleted(Operation.LOAD_CHILDREN, "/");
        }
    }

    protected void updatePendingNodes() {
        if (this.pendingNodes.isEmpty()) {
            return;
        }

        Set<ZooKeeperTreeNode> affectedNode = new HashSet<>();

        long start = System.currentTimeMillis();
        while (true) {
            long elapsed = System.currentTimeMillis() - start;
            if (elapsed > MAX_UPDATE_TIME) {
                break;
            }

            ZooKeeperTreeNode node = this.pendingNodes.poll();
            if (node == null) {
                break;
            }

            this.nodeProvider.updateNodeStats(node);

            while (node != null) {
                affectedNode.add(node);
                node = (ZooKeeperTreeNode) node.getParent();
            }
        }

        SwingUtilities.invokeLater(() -> {
            for (ZooKeeperTreeNode eachNode : affectedNode) {
                this.treeModel.nodeChanged(eachNode);
            }
            this.resizeTree();
        });
    }

    private void addKnownHost(String zookeeperHost) {
        if (this.knownHosts.add(zookeeperHost)) {
            this.writeKnownHosts();
        }
    }

    private void buildChildren(ZooKeeperTreeNode node) {
        if (node.getChildCount() > 0) {
            return;
        }

        List<ZooKeeperTreeNode> children = this.nodeProvider.getChildren(node, DEFAULT_MAX_CHILDREN);

        for (ZooKeeperTreeNode eachChild : children) {
            node.add(eachChild);
            this.pendingNodes.add(eachChild);
        }
    }

    private boolean canEditNode() {
        return this.selectedZookeeperPath != null && this.canReload();
    }

    private boolean canReload() {
        return this.nodeProvider.getConnectionState() == ConnectionState.CONNECTED && this.currentOperation == null;
    }

    private JPanel createActionPanel() {
        JPanel result = new JPanel(new GridLayout(2, 1));

        JPanel connectPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        result.add(connectPanel);

        this.cbxHost = new JComboBox<>();
        this.cbxHost.setEditable(true);
        this.cbxHost.setModel(new DefaultComboBoxModel<>(this.knownHosts.toArray(new String[0])));
        this.cbxHost.setPrototypeDisplayValue("server-a:2181,server-b:2181,server-c:2181/directory");
        new EditableComboboxHandler<String>().install(this.cbxHost, this::removeKnownHost);
        connectPanel.add(this.cbxHost);

        this.tglConnect = new JToggleButton("Connect");
        connectPanel.add(this.tglConnect);
        this.tglConnect.setPreferredSize(new Dimension(150, this.tglConnect.getPreferredSize().height));
        this.tglConnect.addActionListener(this::toggleConnect);

        JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        result.add(actionsPanel);

        JButton btnReload = new JButton("Reload");
        btnReload.addActionListener(e -> this.updateContent());
        actionsPanel.add(btnReload);
        this.componentEnablers.add(new ComponentEnabler(btnReload, this::canReload));

        JToggleButton tglAutoReload = new JToggleButton("Auto-Reload");
        tglAutoReload.addActionListener(e -> this.toggleAutoUpdate());
        actionsPanel.add(tglAutoReload);
        this.componentEnablers.add(new ComponentEnabler(tglAutoReload, this::canReload));

        this.pgrLoading = new JProgressBar();
        this.pgrLoading.setIndeterminate(true);
        this.pgrLoading.setVisible(false);
        actionsPanel.add(this.pgrLoading);

        return result;
    }

    private JButton createButton(String name, ActionListener action, BooleanSupplier enabler) {
        JButton result = new JButton(name);
        result.addActionListener(action);
        this.componentEnablers.add(new ComponentEnabler(result, enabler));
        return result;
    }

    private JPanel createContentPanel() {
        JPanel result = new JPanel(new BorderLayout());

        this.textArea = new JTextArea();
        result.add(new JScrollPane(this.textArea), BorderLayout.CENTER);

        JPanel pnlButtons = new JPanel();
        pnlButtons.setLayout(new BoxLayout(pnlButtons, BoxLayout.X_AXIS));
        pnlButtons.setBorder(new EmptyBorder(6, 6, 6, 6));

        pnlButtons.add(this.createButton("Reload", event -> this.loadSelectedContent(), this::canEditNode));
        pnlButtons.add(Box.createHorizontalStrut(6));
        pnlButtons.add(this.createButton("Download", event -> this.downloadSelectedContent(), this::canEditNode));
        pnlButtons.add(Box.createHorizontalStrut(6));
        this.txtNodeDetails = new JTextField();
        this.txtNodeDetails.setEditable(false);
        pnlButtons.add(this.txtNodeDetails);
        pnlButtons.add(Box.createHorizontalGlue());
        pnlButtons.add(Box.createHorizontalStrut(6));
        pnlButtons.add(this.createButton("Save", event -> this.saveContent(), this::canEditNode));
        result.add(pnlButtons, BorderLayout.SOUTH);

        return result;
    }

    private JComponent createMainPanel() {
        JPanel result = new JPanel(new BorderLayout(6, 6));
        result.setBorder(new EmptyBorder(6, 6, 6, 6));

        JSplitPane splitPanel = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

        splitPanel.setLeftComponent(this.createTreePanel());
        splitPanel.setRightComponent(this.createContentPanel());
        splitPanel.setDividerLocation(400);

        result.add(splitPanel, BorderLayout.CENTER);

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

    private JPanel createTreePanel() {
        JPanel result = new JPanel(new BorderLayout());

        this.treeModel = new DefaultTreeModel(new ZooKeeperTreeNode(new NodeDetails()));
        this.tree = new JTree(this.treeModel);
        this.tree.setShowsRootHandles(true);
        this.tree.addTreeWillExpandListener(this);
        this.tree.addTreeSelectionListener(this);
        this.tree.setCellRenderer(new ZooKeeperTreeNodeRenderer());
        this.tree.setRowHeight(20);
        result.add(new JScrollPane(this.tree), BorderLayout.CENTER);

        JPanel pnlButtons = new JPanel(new BorderLayout(6, 6));
        pnlButtons.setBorder(new EmptyBorder(6, 6, 6, 6));
        pnlButtons.add(this.createButton("Delete", event -> this.deleteNode(), this::canEditNode), BorderLayout.WEST);
        pnlButtons.add(this.createButton("New Child", event -> this.createNewNode(), this::canEditNode), BorderLayout.EAST);
        result.add(pnlButtons, BorderLayout.SOUTH);

        return result;
    }

    private void deleteNode() {
        if (this.selectedZookeeperPath == null) {
            return;
        }

        DeleteNodePanel panel = new DeleteNodePanel(
            "Delete node '" + this.selectedZookeeperPath + "'?\n\nThere is no way to undo this!");

        if (JOptionPane.showConfirmDialog(
            this.frame,
            panel,
            "Delete node",
            JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }

        this.doDeleteNode(panel.isRecursively());
    }

    private void doDeleteNode(boolean recursively) {
        if (SwingUtilities.isEventDispatchThread()) {
            new Thread(() -> this.doDeleteNode(recursively)).start();
            return;
        }

        if (recursively) {
            this.nodeProvider.deleteNodeRecursively(this.selectedZookeeperPath);
        } else {
            this.nodeProvider.deleteNode(this.selectedZookeeperPath);
        }
        this.updateContent();
    }

    private void downloadSelectedContent() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::downloadSelectedContent);
            return;
        }

        if (this.selectedZookeeperPath == null) {
            return;
        }

        String name = ZooKeeperTreeNode.getLastName(this.selectedZookeeperPath);
        this.fileChooser.setSelectedFile(new File(name));
        int result = this.fileChooser.showSaveDialog(this.frame);

        if (result == JFileChooser.APPROVE_OPTION) {
            Path path = this.fileChooser.getSelectedFile().toPath();
            byte[] content = this.nodeProvider.getContent(this.selectedZookeeperPath);

            try {
                Files.write(path, content);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(
                    this.frame,
                    "Failed to write file.",
                    "Could not write file " + path + ": " + e.getMessage(),
                    JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void expandZooKeeperPath(String zooKeeperPath) {
        ZooKeeperTreeNode rootNode = (ZooKeeperTreeNode) this.treeModel.getRoot();
        ZooKeeperTreeNode node = rootNode.getNodeWithPath(zooKeeperPath);

        if (node != null) {
            this.tree.expandPath(new TreePath(node.getPath()));
        }

        this.resizeTree();
    }

    private void loadSelectedContent() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::loadSelectedContent);
            return;
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

    private List<String> readKnownHosts() {
        Path path = getKnownHostsFile();

        if (Files.exists(path)) {
            try {
                return Files.readAllLines(path, StandardCharsets.UTF_8);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(
                    this.frame,
                    "Failed to read known hosts.",
                    "Could not read known hosts from " + path + ": " + e.getMessage(),
                    JOptionPane.ERROR_MESSAGE);
            }
        }

        return Collections.emptyList();
    }

    private void removeKnownHost(String host) {
        if (this.knownHosts.remove(host)) {
            this.writeKnownHosts();
        }
    }

    private void resizeTree() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::resizeTree);
            return;
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
        this.componentEnablers.forEach(ComponentEnabler::update);
    }

    private void toggleConnect(ActionEvent event) {
        if (SwingUtilities.isEventDispatchThread()) {
            new Thread(() -> this.toggleConnect(event)).start();
            return;
        }

        if (this.tglConnect.isSelected()) {
            this.watchDog = new ConnectionWatchDog(CONNECT_TIMEOUT, this::watchDogCountDown);
            this.timer.scheduleAtFixedRate(this.watchDog, 0, WATCH_DOG_UPDATE_DELAY);

            String zookeeperHost = (String) this.cbxHost.getSelectedItem();
            zookeeperHost = zookeeperHost.trim();
            this.nodeProvider.connectTo(zookeeperHost);
        } else {
            if (this.watchDog != null) {
                this.watchDog.abortImmediately();
            } else {
                this.nodeProvider.disconnect();
            }
        }
    }

    private void uncaughtException(Thread thread, Throwable throwable) {
        JOptionPane.showMessageDialog(this.frame, throwable.toString(), "Error", JOptionPane.ERROR_MESSAGE);
    }

    private void updateTitle(String host) {
        if (host == null || host.trim().isEmpty()) {
            this.frame.setTitle(BASE_TITLE);
        } else {
            this.frame.setTitle(BASE_TITLE + " - " + host);
        }
    }

    private void watchDogCountDown(long remaining) {
        if (remaining <= 0) {
            this.nodeProvider.disconnect();
            return;
        }

        String remainingSeconds = NumberFormat.getIntegerInstance(Locale.ENGLISH).format((remaining + 999) / 1_000L);

        ConnectionState connectionState = this.nodeProvider.getConnectionState();
        this.tglConnect.setText(connectionState + " ... (" + remainingSeconds + ")");
    }

    private void writeKnownHosts() {
        Path path = getKnownHostsFile();

        try {
            Files.createDirectories(path.getParent());
            Files.write(path, this.knownHosts, StandardCharsets.UTF_8);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(
                this.frame,
                "Could not write known hosts",
                "Could not write known hosts to " + path + ": " + e.getMessage(),
                JOptionPane.ERROR_MESSAGE);
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

    public static final class UpdateableToggleButtonModel extends ToggleButtonModel {

        private static final long serialVersionUID = 1L;

        private transient BooleanSupplier supplier;

        public UpdateableToggleButtonModel(BooleanSupplier supplier) {
            super();

            this.supplier = supplier;
            this.update();
        }

        public void update() {
            this.setEnabled(this.supplier.getAsBoolean());
        }
    }

    protected static class ComponentEnabler {

        private final JComponent component;
        private final BooleanSupplier booleanSupplier;

        public ComponentEnabler(JComponent component, BooleanSupplier booleanSupplier) {
            super();
            this.component = component;
            this.booleanSupplier = booleanSupplier;
        }

        public void update() {
            this.component.setEnabled(this.booleanSupplier.getAsBoolean());
        }
    }

    protected static class EditableComboboxHandler<T> {

        private JComboBox<T> cbxTarget;
        private Consumer<T> removeValue;

        public void install(JComboBox<T> comboBox, Consumer<T> removeValue) {
            this.cbxTarget = comboBox;
            this.removeValue = removeValue;

            this.cbxTarget.getEditor().getEditorComponent().addKeyListener(new KeyAdapter() {

                @Override
                public void keyTyped(KeyEvent e) {
                    if (comboBox.isPopupVisible() && e.getModifiers() == SHIFT_MASK && KeyEvent.VK_DELETE == e.getKeyChar()) {
                        EditableComboboxHandler.this.removeCurrentSelection();
                    }
                }
            });
        }

        protected void removeCurrentSelection() {
            T item = this.cbxTarget.getModel().getElementAt(this.cbxTarget.getSelectedIndex());
            this.cbxTarget.removeItem(item);
            this.removeValue.accept(item);
        }
    }

    private static class ConnectionWatchDog extends TimerTask {

        private final long latestConnectTime;
        private final LongConsumer remainingTimeConsumer;

        public ConnectionWatchDog(long timeout, LongConsumer remainingTimeConsumer) {
            super();

            this.latestConnectTime = System.currentTimeMillis() + timeout;
            this.remainingTimeConsumer = remainingTimeConsumer;
        }

        public void abortImmediately() {
            this.cancel();
            this.remainingTimeConsumer.accept(-1);
        }

        @Override
        public void run() {
            long remainingTime = this.latestConnectTime - System.currentTimeMillis();

            if (remainingTime <= 0) {
                this.cancel();
            }

            this.remainingTimeConsumer.accept(remainingTime);
        }
    }
}
