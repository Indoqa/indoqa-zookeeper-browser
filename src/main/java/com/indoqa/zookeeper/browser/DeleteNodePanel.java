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
import java.awt.GridLayout;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

public class DeleteNodePanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private JCheckBox cbxRecursive;

    public DeleteNodePanel(Object message) {
        super();

        this.setLayout(new BorderLayout(6, 6));

        JPanel pnlMessage = new JPanel(new GridLayout(-1, 1));
        this.addMessage(pnlMessage, message);
        this.add(pnlMessage, BorderLayout.CENTER);

        this.cbxRecursive = new JCheckBox("Delete recursively");
        this.add(this.cbxRecursive, BorderLayout.SOUTH);
    }

    public boolean isRecursively() {
        return this.cbxRecursive.isSelected();
    }

    private void addMessage(JPanel panel, Object message) {
        if (message instanceof JComponent) {
            panel.add((JComponent) message);
            return;
        }

        if (message instanceof String) {
            String string = (String) message;
            int index = string.indexOf("\n");

            if (index != -1) {
                this.addMessage(panel, string.substring(0, index));
                this.addMessage(panel, string.substring(index + 1));
            } else {
                JLabel label = new JLabel(string);
                // label.setAlignmentX(0);
                panel.add(label);
            }

            return;
        }
    }
}
