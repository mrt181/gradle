/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.internal.changedetection.state;

import com.google.common.hash.HashCode;
import org.gradle.api.internal.tasks.cache.TaskCacheKeyBuilder;

class FileHashSnapshot implements IncrementalFileSnapshot, FileSnapshot {
    private final HashCode hash;
    private final transient long lastModified; // Currently not persisted

    public FileHashSnapshot(HashCode hash) {
        this(hash, 0L);
    }

    public FileHashSnapshot(HashCode hash, long lastModified) {
        this.hash = hash;
        this.lastModified = lastModified;
    }

    public boolean isContentUpToDate(IncrementalFileSnapshot snapshot) {
        if (!(snapshot instanceof FileHashSnapshot)) {
            return false;
        }
        FileHashSnapshot other = (FileHashSnapshot) snapshot;
        return hash.equals(other.hash);
    }

    @Override
    public boolean isContentAndMetadataUpToDate(IncrementalFileSnapshot snapshot) {
        if (!(snapshot instanceof FileHashSnapshot)) {
            return false;
        }
        FileHashSnapshot other = (FileHashSnapshot) snapshot;
        return lastModified == other.lastModified && hash.equals(other.hash);
    }

    @Override
    public String toString() {
        return hash.toString();
    }

    @Override
    public HashCode getHash() {
        return hash;
    }

    @Override
    public void appendToCacheKey(TaskCacheKeyBuilder builder) {
        builder.putHashCode(hash);
    }
}
