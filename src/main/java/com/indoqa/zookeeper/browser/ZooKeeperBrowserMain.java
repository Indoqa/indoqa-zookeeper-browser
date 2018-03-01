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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import javax.swing.tree.DefaultTreeModel;

import org.apache.zookeeper.*;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZooKeeperBrowserMain implements Watcher, NodeProvider {

    private static final int DEFAULT_SESSION_TIMEOUT = 30000;

    private static final Logger LOGGER = LoggerFactory.getLogger(ZooKeeperBrowserMain.class);

    private ZooKeeper zooKeeper;

    private ZooKeeperBrowserViewer viewer;

    private String connectString;

    public ZooKeeperBrowserMain() {
        this.viewer = new ZooKeeperBrowserViewer();
        this.viewer.setModel(new DefaultTreeModel(new ZooKeeperTreeNode(null)));
        this.viewer.setNodeProvider(this);
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            LOGGER.error("Usage: ZooKeeperBrowserMain <connect-string>");
            return;
        }

        ZooKeeperBrowserMain main = new ZooKeeperBrowserMain();
        main.connectTo(args[0]);
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
    public void createChild(String path, String name) {
        try {
            this.zooKeeper.create(join(path, name), new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        } catch (Exception e) {
            throw new ZooKeeperBrowserException(e.getMessage(), e);
        }
    }

    @Override
    public void deleteNode(String path) {
        try {
            this.zooKeeper.delete(path, -1);
        } catch (Exception e) {
            throw new ZooKeeperBrowserException(e.getMessage(), e);
        }
    }

    @Override
    public List<ZooKeeperTreeNode> getChildren(String zooKeeperPath) {
        try {
            List<ZooKeeperTreeNode> result = new ArrayList<>();
            List<String> children = this.zooKeeper.getChildren(zooKeeperPath, false);
            Collections.sort(children);

            for (String eachChild : children) {
                String childPath = join(zooKeeperPath, eachChild);
                result.add(this.createNode(childPath));
            }

            return result;
        } catch (Exception e) {
            throw new ZooKeeperBrowserException(e.getMessage(), e);
        }
    }

    @Override
    public byte[] getContent(String path) {
        try {
            return this.zooKeeper.getData(path, false, null);
        } catch (Exception e) {
            throw new ZooKeeperBrowserException(e.getMessage(), e);
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
    public void process(WatchedEvent event) {
        this.LOGGER.info("Received {}", event);
        if (event.getType() != EventType.None) {
            return;
        }

        switch (event.getState()) {
            case Disconnected:
                this.LOGGER.info("ZooKeeper session was disconnected.");
                break;

            case Expired:
                this.LOGGER.info("ZooKeeper session was expired.");
                this.connect();
                break;

            case ConnectedReadOnly:
            case SyncConnected:
                this.LOGGER.info("Connected to the ZooKeeper ensemble.");
                this.updateContent();
                break;

            default:
                this.LOGGER.info("Unhandled event of type {} and state {}", event.getType(), event.getState());
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

    private void connect() {
        try {
            this.zooKeeper = new ZooKeeper(this.connectString, DEFAULT_SESSION_TIMEOUT, this);
            this.viewer.setConnectString(this.connectString);
        } catch (Exception e) {
            this.LOGGER.error("Could not connect to ZooKeeper ensemble.", e);
        }
    }

    private void connectTo(String connect) {
        this.disconnect();

        this.connectString = connect;

        this.connect();
    }

    private ZooKeeperTreeNode createNode(String path) throws KeeperException, InterruptedException {
        Stat stat = this.zooKeeper.exists(path, false);

        NodeDetails nodeDetails = new NodeDetails();
        nodeDetails.setChildren(stat.getNumChildren());
        nodeDetails.setCreated(new Date(stat.getCtime()));
        nodeDetails.setCversion(stat.getCversion());
        nodeDetails.setModified(new Date(stat.getMtime()));
        nodeDetails.setPath(path);
        nodeDetails.setVersion(stat.getVersion());

        return new ZooKeeperTreeNode(nodeDetails);
    }

    private void disconnect() {
        if (this.zooKeeper != null) {
            try {
                this.zooKeeper.close();
                this.viewer.setConnectString("Not connected");
            } catch (Exception e) {
                this.LOGGER.error("Could not close ZooKeeper client.", e);
            }
        }

    }

    private void updateContent() {
        this.viewer.updateContent();
    }
}
