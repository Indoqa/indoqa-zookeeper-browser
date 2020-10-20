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

import java.awt.Component;
import java.text.MessageFormat;
import java.util.Locale;

import javax.swing.JTree;
import javax.swing.tree.DefaultTreeCellRenderer;

public class ZooKeeperTreeNodeRenderer extends DefaultTreeCellRenderer {

    private static final long serialVersionUID = 1L;

    @Override
    public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded, boolean leaf, int row,
            boolean hasFocus) {
        if (value instanceof ZooKeeperTreeNode) {
            ZooKeeperTreeNode zooKeeperTreeNode = (ZooKeeperTreeNode) value;

            if (leaf) {
                return super.getTreeCellRendererComponent(
                    tree,
                    this.getLeafValue(zooKeeperTreeNode),
                    sel,
                    expanded,
                    leaf,
                    row,
                    hasFocus);
            }

            return super.getTreeCellRendererComponent(
                tree,
                this.getContainerValue(zooKeeperTreeNode),
                sel,
                expanded,
                leaf,
                row,
                hasFocus);
        }

        return super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
    }

    private String getContainerValue(ZooKeeperTreeNode zooKeeperTreeNode) {
        return new MessageFormat("{0} (Children: {1} / {2}{3})", Locale.ENGLISH).format(
            new Object[] {
                zooKeeperTreeNode.getPathName(),
                zooKeeperTreeNode.getChildCount(),
                zooKeeperTreeNode.getTotalChildCount(),
                zooKeeperTreeNode.isFullyExplored() ? "" : "*"});
    }

    private String getLeafValue(ZooKeeperTreeNode zooKeeperTreeNode) {
        if (zooKeeperTreeNode.isFullyExplored()) {
            return zooKeeperTreeNode.getPathName();
        }

        return zooKeeperTreeNode.getPathName() + "*";
    }
}
