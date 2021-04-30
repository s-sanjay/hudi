/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.hadoop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hudi.common.table.HoodieTableGloballyConsistentMetaClient;
import org.apache.hudi.common.util.FSUtils;

public class TestGloballyConsistentTimeStampFilteringInputFormat
    extends TestHoodieParquetInputFormat {

  @Override
  public void setUp() {
    super.setUp();
    jobConf.set(HoodieTableGloballyConsistentMetaClient.GLOBALLY_CONSISTENT_READ_TIMESTAMP,
        String.valueOf(Long.MAX_VALUE));
  }

  @Override
  public void testInputFormatLoad() throws IOException {
    super.testInputFormatLoad();

    // set filtering timestamp to 0 now the timeline wont have any commits.
    jobConf.set(HoodieTableGloballyConsistentMetaClient.GLOBALLY_CONSISTENT_READ_TIMESTAMP, "0");

    InputSplit[] inputSplits = inputFormat.getSplits(jobConf, 10);
    assertEquals(0, inputSplits.length);

    FileStatus[] files = inputFormat.listStatus(jobConf);
    assertEquals(0, files.length);

    for (String disableVal : Arrays.asList("", null)) {
      if (disableVal != null) {
        jobConf.set(HoodieTableGloballyConsistentMetaClient.GLOBALLY_CONSISTENT_READ_TIMESTAMP,
            disableVal);
      } else {
        jobConf.unset(HoodieTableGloballyConsistentMetaClient.GLOBALLY_CONSISTENT_READ_TIMESTAMP);
      }

      assertEquals(10, inputFormat.getSplits(jobConf, 10).length);

      assertEquals(10, inputFormat.listStatus(jobConf).length);
    }
  }

  @Override
  public void testInputFormatUpdates() throws IOException {
    super.testInputFormatUpdates();

    // set the globally replicated timestamp to 199 so only 100 is read and update is ignored.
    jobConf.set(HoodieTableGloballyConsistentMetaClient.GLOBALLY_CONSISTENT_READ_TIMESTAMP, "199");

    FileStatus[] files = inputFormat.listStatus(jobConf);
    assertEquals(10, files.length);

    ensureFilesInCommit("5 files have been updated to commit 200. but should get filtered out ",
        files,"200", 0);
    ensureFilesInCommit("We should see 10 files from commit 100 ", files, "100", 10);
  }

  @Override
  public void testIncrementalSimple() throws IOException {
    // setting filtering timestamp to zero should not in any way alter the result of the test which
    // pulls in zero files due to incremental ts being the actual commit time
    jobConf.set(HoodieTableGloballyConsistentMetaClient.GLOBALLY_CONSISTENT_READ_TIMESTAMP, "0");
    super.testIncrementalSimple();
  }

  @Override
  public void testIncrementalWithMultipleCommits() throws IOException {
    super.testIncrementalWithMultipleCommits();

    // set globally replicated timestamp to 400 so commits from 500, 600 does not show up
    jobConf.set(HoodieTableGloballyConsistentMetaClient.GLOBALLY_CONSISTENT_READ_TIMESTAMP, "400");
    InputFormatTestUtil.setupIncremental(jobConf, "100", HoodieHiveUtil.MAX_COMMIT_ALL);

    FileStatus[] files = inputFormat.listStatus(jobConf);

    assertEquals(
        "Pulling ALL commits from 100, should get us the 3 files from 400 commit, 1 file from 300 "
            + "commit and 1 file from 200 commit", 5, files.length);
    ensureFilesInCommit("Pulling 3 commits from 100, should get us the 3 files from 400 commit",
        files, "400", 3);
    ensureFilesInCommit("Pulling 3 commits from 100, should get us the 1 files from 300 commit",
        files, "300", 1);
    ensureFilesInCommit("Pulling 3 commits from 100, should get us the 1 files from 200 commit",
        files, "200", 1);

    List<String> commits = Arrays.asList("100", "200", "300", "400", "500", "600");
    for (int idx = 0; idx < commits.size(); ++idx) {
      for (int jdx = 0; jdx < commits.size(); ++jdx) {
        InputFormatTestUtil.setupIncremental(jobConf, commits.get(idx), HoodieHiveUtil.MAX_COMMIT_ALL);
        jobConf.set(HoodieTableGloballyConsistentMetaClient.GLOBALLY_CONSISTENT_READ_TIMESTAMP,
            commits.get(jdx));

        files = inputFormat.listStatus(jobConf);

        if (jdx <= idx) {
          assertEquals("all commits should be filtered", 0, files.length);
        } else {
          // only commits upto the timestamp is allowed
          for (FileStatus file : files) {
            String commitTs = FSUtils.getCommitTime(file.getPath().getName());
            assertTrue(commits.indexOf(commitTs) <= jdx);
            assertTrue(commits.indexOf(commitTs) > idx);
          }
        }
      }
    }
  }
}
