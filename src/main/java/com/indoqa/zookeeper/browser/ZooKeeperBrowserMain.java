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

import static com.indoqa.zookeeper.browser.ConnectionState.DISCONNECTED;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.apache.zookeeper.*;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZooKeeperBrowserMain implements Watcher, NodeProvider {

    private static final int MAX_DELETE_ATTEMPTS = 10;

    private static final int DEFAULT_SESSION_TIMEOUT = 30000;

    private static final Logger LOGGER = LoggerFactory.getLogger(ZooKeeperBrowserMain.class);

    private ZooKeeperBrowserViewer viewer;

    private ZooKeeper zooKeeper;
    private String zookeeperHost;
    private ConnectionState connectionState = DISCONNECTED;

    public ZooKeeperBrowserMain() {
        this.viewer = new ZooKeeperBrowserViewer();
        this.viewer.setNodeProvider(this);
    }

    public static void main(String[] args) {
        new ZooKeeperBrowserMain();
    }

    private static String join(String parentPath, String path) {
        StringBuilder stringBuilder = new StringBuilder();

        stringBuilder.append(parentPath);
        if (stringBuilder.charAt(stringBuilder.length() - 1) != '/') {
            stringBuilder.append('/');
        }

        stringBuilder.append(path);

        return stringBuilder.toString();
    }

    @Override
    public void connectTo(String host) {
        this.disconnect();

        this.zookeeperHost = host;

        this.connect();
    }

    @Override
    public void createChild(String path, String name) {
        try {
            this.zooKeeper.create(join(path, name), new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch (Exception e) {
            throw new ZooKeeperBrowserException(e.getMessage(), e);
        }
    }

    @Override
    public void deleteNode(String path) {
        for (int i = 0; i < MAX_DELETE_ATTEMPTS; i++) {
            try {
                this.zooKeeper.delete(path, -1);
                return;
            } catch (KeeperException.NoNodeException | InterruptedException e) {
                // do nothing
            } catch (Exception e) {
                throw new ZooKeeperBrowserException(e.getMessage(), e);
            }
        }

        throw new ZooKeeperBrowserException("Could not delete node '" + path + "' after " + MAX_DELETE_ATTEMPTS + " attempts.");
    }

    @Override
    public void deleteNodeRecursively(String path) {
        this.viewer.operationStarted(Operation.DELETE_NODE, path);

        try {
            for (int i = 0; i < MAX_DELETE_ATTEMPTS; i++) {
                try {
                    List<String> children = this.zooKeeper.getChildren(path, false);

                    for (String eachChild : children) {
                        try {
                            this.zooKeeper.delete(join(path, eachChild), -1);
                        } catch (KeeperException.NoNodeException | InterruptedException e) {
                            // do nothing
                        }
                    }

                    this.zooKeeper.delete(path, -1);
                    return;
                } catch (KeeperException.NoNodeException | InterruptedException e) {
                    // do nothing
                } catch (Exception e) {
                    throw new ZooKeeperBrowserException(e.getMessage(), e);
                }
            }

            throw new ZooKeeperBrowserException(
                "Could not recursively delete node '" + path + "' after " + MAX_DELETE_ATTEMPTS + " attempts.");
        } finally {
            this.viewer.operationCompleted(Operation.DELETE_NODE, path);
        }
    }

    @Override
    public void disconnect() {
        if (this.zooKeeper == null) {
            return;
        }

        LOGGER.info("Start disconnect from {}", this.zookeeperHost);
        this.setConnectionState(ConnectionState.DISCONNECTING);

        try {
            this.zooKeeper.close();
            this.zooKeeper = null;
            this.setConnectionState(ConnectionState.DISCONNECTED);
        } catch (Exception e) {
            LOGGER.error("Could not close ZooKeeper client.", e);
        }

        LOGGER.info("Completed disconnect from {}", this.zookeeperHost);
    }

    @Override
    public List<ZooKeeperTreeNode> getChildren(ZooKeeperTreeNode node, int maxCount) {
        this.viewer.operationStarted(Operation.LOAD_CHILDREN, node.getZooKeeperPath());

        try {
            List<ZooKeeperTreeNode> result = new ArrayList<>();
            List<String> children = this.zooKeeper.getChildren(node.getZooKeeperPath(), false);
            Collections.sort(children);

            for (String eachChild : children) {
                String childPath = join(node.getZooKeeperPath(), eachChild);
                result.add(this.createNode(childPath));

                if (result.size() >= maxCount) {
                    break;
                }
            }

            return result;
        } catch (Exception e) {
            throw new ZooKeeperBrowserException(e.getMessage(), e);
        } finally {
            this.viewer.operationCompleted(Operation.LOAD_CHILDREN, node.getZooKeeperPath());
        }
    }

    @Override
    public ConnectionState getConnectionState() {
        return this.connectionState;
    }

    @Override
    public byte[] getContent(String path) {
        this.viewer.operationStarted(Operation.LOAD_CONTENT, path);

        try {
            return this.zooKeeper.getData(path, false, null);
        } catch (Exception e) {
            throw new ZooKeeperBrowserException(e.getMessage(), e);
        } finally {
            this.viewer.operationCompleted(Operation.LOAD_CONTENT, path);
        }
    }

    @Override
    public ZooKeeperTreeNode getNode(String path) {
        try {
            return this.createNode(path);
        } catch (Exception e) {
            throw new ZooKeeperBrowserException(e.getMessage(), e);
        }
    }

    @Override
    public String getZookeeperHost() {
        return this.zookeeperHost;
    }

    @Override
    public void process(WatchedEvent event) {
        LOGGER.info("Received {}", event);
        if (event.getType() != EventType.None) {
            return;
        }

        switch (event.getState()) {
            case Disconnected:
                LOGGER.info("ZooKeeper session was disconnected.");
                this.setConnectionState(ConnectionState.DISCONNECTED);
                break;

            case Expired:
                LOGGER.info("ZooKeeper session was expired.");
                this.setConnectionState(ConnectionState.DISCONNECTED);
                this.connect();
                break;

            case ConnectedReadOnly:
            case SyncConnected:
                LOGGER.info("Connected to the ZooKeeper ensemble.");
                this.setConnectionState(ConnectionState.CONNECTED);
                this.updateContent();
                break;

            default:
                LOGGER.info("Unhandled event of type {} and state {}", event.getType(), event.getState());
                break;
        }
    }

    @Override
    public void setContent(String path, byte[] bytes) {
        try {
            this.zooKeeper.setData(path, bytes, -1);
        } catch (Exception e) {
            throw new ZooKeeperBrowserException(e.getMessage(), e);
        }
    }

    @Override
    public void updateNodeStats(ZooKeeperTreeNode node) {
        if (this.zooKeeper == null) {
            return;
        }

        try {
            Stat stat = this.zooKeeper.exists(node.getZooKeeperPath(), false);
            if (stat == null) {
                return;
            }

            NodeDetails nodeDetails = node.getNodeDetails();
            nodeDetails.setChildren(stat.getNumChildren());
            nodeDetails.setCreated(new Date(stat.getCtime()));
            nodeDetails.setCversion(stat.getCversion());
            nodeDetails.setModified(new Date(stat.getMtime()));
            nodeDetails.setVersion(stat.getVersion());
        } catch (Exception e) {
            throw new ZooKeeperBrowserException(e.getMessage(), e);
        }
    }

    private void connect() {
        LOGGER.info("Start connect to {}", this.zookeeperHost);

        try {
            this.setConnectionState(ConnectionState.INITIALIZING);
            this.zooKeeper = new ZooKeeper(this.zookeeperHost, DEFAULT_SESSION_TIMEOUT, this);
            this.setConnectionState(ConnectionState.CONNECTING);
        } catch (Exception e) {
            LOGGER.error("Could not connect to ZooKeeper ensemble.", e);
        }

        LOGGER.info("Completed connect to {}", this.zooKeeper);
    }

    private ZooKeeperTreeNode createNode(String path) {
        return new ZooKeeperTreeNode(NodeDetails.withPath(path));
    }

    private void setConnectionState(ConnectionState connectionState) {
        this.connectionState = connectionState;
        this.viewer.connectionStateChanged();
    }

    private void updateContent() {
        this.viewer.updateContent();
    }
}
