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

import java.util.Date;

public class NodeDetails {

    private String path;
    private int children;
    private int version;
    private int cversion;
    private Date created;
    private Date modified;

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (this.getClass() != obj.getClass()) {
            return false;
        }
        NodeDetails other = (NodeDetails) obj;
        if (this.path == null) {
            if (other.path != null) {
                return false;
            }
        } else if (!this.path.equals(other.path)) {
            return false;
        }
        return true;
    }

    public int getChildren() {
        return this.children;
    }

    public Date getCreated() {
        return this.created;
    }

    public int getCversion() {
        return this.cversion;
    }

    public Date getModified() {
        return this.modified;
    }

    public String getPath() {
        return this.path;
    }

    public int getVersion() {
        return this.version;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (this.path == null ? 0 : this.path.hashCode());
        return result;
    }

    public void setChildren(int children) {
        this.children = children;
    }

    public void setCreated(Date created) {
        this.created = created;
    }

    public void setCversion(int cversion) {
        this.cversion = cversion;
    }

    public void setModified(Date modified) {
        this.modified = modified;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public void setVersion(int version) {
        this.version = version;
    }
}
