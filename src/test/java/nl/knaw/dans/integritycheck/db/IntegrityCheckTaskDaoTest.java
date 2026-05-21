/*
 * Copyright (C) 2026 DANS - Data Archiving and Networked Services (info@dans.knaw.nl)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.knaw.dans.integritycheck.db;

import io.dropwizard.testing.junit5.DAOTestExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import nl.knaw.dans.integritycheck.core.IntegrityCheckTask;
import nl.knaw.dans.integritycheck.core.IntegrityCheckTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(DropwizardExtensionsSupport.class)
class IntegrityCheckTaskDaoTest {

    private final DAOTestExtension daoTestRule = DAOTestExtension.newBuilder()
        .addEntityClass(IntegrityCheckTask.class)
        .build();

    private IntegrityCheckTaskDao integrityCheckTaskDao;

    @BeforeEach
    void setUp() {
        integrityCheckTaskDao = new IntegrityCheckTaskDao(daoTestRule.getSessionFactory());
    }

    @Test
    void save_should_persist_task() {
        var task = IntegrityCheckTask.builder()
            .fileId(1L)
            .expectedSha1("sha-1")
            .build();

        var savedTask = daoTestRule.inTransaction(() -> integrityCheckTaskDao.save(task));

        assertThat(savedTask.getId()).isNotNull();
        assertThat(savedTask.getFileId()).isEqualTo(1L);
        assertThat(savedTask.getCreationTimestamp()).isNotNull();
    }

    @Test
    void findTasksToExecute_should_return_only_tasks_with_status_open() {
        daoTestRule.inTransaction(() -> {
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(1L).expectedSha1("sha-1").status(IntegrityCheckTaskStatus.OPEN).build());
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(2L).expectedSha1("sha-2").status(IntegrityCheckTaskStatus.FINISHED).calculatedSha1("sha-2").build());
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(3L).expectedSha1("sha-3").status(IntegrityCheckTaskStatus.SCHEDULED).build());
            return null;
        });

        List<IntegrityCheckTask> tasks = daoTestRule.inTransaction(() -> integrityCheckTaskDao.findTasksToExecute());

        assertThat(tasks).hasSize(1);
        assertThat(tasks).extracting("fileId").containsExactly(1L);
    }

    @Test
    void findScheduledTasks_should_return_tasks_with_status_scheduled() {
        daoTestRule.inTransaction(() -> {
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(1L).expectedSha1("sha-1").status(IntegrityCheckTaskStatus.OPEN).build());
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(2L).expectedSha1("sha-2").status(IntegrityCheckTaskStatus.SCHEDULED).build());
            return null;
        });

        List<IntegrityCheckTask> tasks = daoTestRule.inTransaction(() -> integrityCheckTaskDao.findScheduledTasks());

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getFileId()).isEqualTo(2L);
    }

    @Test
    void findPendingOrRecentTasks_should_return_correct_tasks() {
        var now = OffsetDateTime.now();
        var recently = now.minusDays(1);
        var longAgo = now.minusDays(10);

        daoTestRule.inTransaction(() -> {
            // Pending task for file-1
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(1L).expectedSha1("sha-1").status(IntegrityCheckTaskStatus.OPEN).build());
            // Recent task for file-1
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(1L).expectedSha1("sha-1").status(IntegrityCheckTaskStatus.FINISHED).calculatedSha1("sha-1").calculationTimestamp(now).build());
            // Old task for file-1 (should NOT be returned)
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(1L).expectedSha1("sha-1").status(IntegrityCheckTaskStatus.FINISHED).calculatedSha1("sha-1").calculationTimestamp(longAgo).build());
            // Pending task for file-2 (should NOT be returned when querying for file-1)
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(2L).expectedSha1("sha-2").status(IntegrityCheckTaskStatus.OPEN).build());
            return null;
        });

        List<IntegrityCheckTask> tasks = daoTestRule.inTransaction(() -> integrityCheckTaskDao.findPendingOrRecentTasks(1L, recently));

        assertThat(tasks).hasSize(2);
        assertThat(tasks).allMatch(t -> t.getFileId().equals(1L));
    }
}
