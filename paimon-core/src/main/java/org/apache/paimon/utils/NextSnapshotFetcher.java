/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.utils;

import org.apache.paimon.Snapshot;
import org.apache.paimon.table.source.OutOfRangeException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.FileNotFoundException;

/** Fetcher for getting the next snapshot by snapshot id. */
public class NextSnapshotFetcher {

    public static final Logger LOG = LoggerFactory.getLogger(NextSnapshotFetcher.class);

    private final SnapshotManager snapshotManager;
    private final ChangelogManager changelogManager;
    private final boolean changelogDecoupled;

    public NextSnapshotFetcher(
            SnapshotManager snapshotManager,
            ChangelogManager changelogManager,
            boolean changelogDecoupled) {
        this.snapshotManager = snapshotManager;
        this.changelogManager = changelogManager;
        this.changelogDecoupled = changelogDecoupled;
    }

    @Nullable
    public Snapshot getNextSnapshot(long nextSnapshotId) {
        Snapshot latest = snapshotManager.latestSnapshot();

        if (latest != null && latest.id() == nextSnapshotId) {
            return latest;
        }

        try {
            return snapshotManager.tryGetSnapshot(nextSnapshotId);
        } catch (FileNotFoundException ignored) {
        }

        Long earliestSnapshotId = snapshotManager.earliestSnapshotId();
        // No snapshot now
        if (earliestSnapshotId == null || earliestSnapshotId <= nextSnapshotId) {
            if ((earliestSnapshotId == null && nextSnapshotId > 1)
                    || (latest != null && nextSnapshotId > latest.id() + 1)) {
                throw new OutOfRangeException(
                        String.format(
                                "The next expected snapshot is too big! Most possible cause might be the table had been recreated."
                                        + "The next snapshot id is %d, while the latest snapshot id is %s",
                                nextSnapshotId, latest.id()));
            }

            LOG.debug(
                    "Next snapshot id {} does not exist, wait for the snapshot generation.",
                    nextSnapshotId);
            return null;
        }

        if (!changelogDecoupled || !changelogManager.longLivedChangelogExists(nextSnapshotId)) {
            throw new OutOfRangeException(
                    String.format(
                            "The snapshot with id %d has expired. You can: "
                                    + "1. increase the snapshot or changelog expiration time. "
                                    + "2. use consumer-id to ensure that unconsumed snapshots will not be expired.",
                            nextSnapshotId));
        }
        return changelogManager.changelog(nextSnapshotId);
    }
}
