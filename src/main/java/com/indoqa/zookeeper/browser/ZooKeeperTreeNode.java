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

import javax.swing.tree.DefaultMutableTreeNode;

public class ZooKeeperTreeNode extends DefaultMutableTreeNode {

    private static final long serialVersionUID = 1L;

    public ZooKeeperTreeNode(NodeDetails nodeDetails) {
        super(nodeDetails);
    }

    private static String getLastName(String path) {
        if (path == null) {
            return null;
        }

        int separatorIndex = path.lastIndexOf('/');
        if (separatorIndex == -1) {
            return path;
        }

        return path.substring(separatorIndex + 1);
    }

    @Override
    public ZooKeeperTreeNode getChildAt(int index) {
        return (ZooKeeperTreeNode) super.getChildAt(index);
    }

    public int getDirectChildCount() {
        if (this.getChildCount() == 0) {
            return this.getNodeDetails().getChildren();
        }

        return this.getChildCount();
    }

    public NodeDetails getNodeDetails() {
        return (NodeDetails) this.getUserObject();
    }

    public String getPathName() {
        return getLastName(this.getZooKeeperPath());
    }

    public int getTotalChildCount() {
        if (this.getChildCount() == 0) {
            return this.getDirectChildCount();
        }

        int result = 0;

        for (int i = 0; i < this.getChildCount(); i++) {
            result++;
            result += this.getChildAt(i).getTotalChildCount();
        }

        return result;
    }

    public String getZooKeeperPath() {
        if (this.getUserObject() == null) {
            return null;
        }

        return this.getNodeDetails().getPath();
    }

    public boolean isFullyExplored() {
        if (this.getChildCount() == 0 && this.getDirectChildCount() != 0) {
            return false;
        }

        for (int i = 0; i < this.getChildCount(); i++) {
            if (!this.getChildAt(i).isFullyExplored()) {
                return false;
            }
        }

        return true;
    }

    @Override
    public boolean isLeaf() {
        if (this.userObject == null) {
            return true;
        }

        return this.getNodeDetails().getChildren() == 0;
    }
}
