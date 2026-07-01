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
            .filesize(100L)
            .checksumType("SHA-1")
            .expectedChecksumValue("sha-1")
            .build();

        var savedTask = daoTestRule.inTransaction(() -> integrityCheckTaskDao.save(task));

        assertThat(savedTask.getId()).isNotNull();
        assertThat(savedTask.getFileId()).isEqualTo(1L);
        assertThat(savedTask.getCreationTimestamp()).isNotNull();
    }

    @Test
    void findNextExecutableTasks_should_skip_scheduled_and_recently_checked_tasks() {
        var now = OffsetDateTime.now();
        daoTestRule.inTransaction(() -> {
            // In-flight task: never selectable
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(1L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-1").status(IntegrityCheckTaskStatus.SCHEDULED).build());
            // Recently checked: not yet due
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(2L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-2").status(IntegrityCheckTaskStatus.FINISHED).calculatedChecksumValue("sha-2").calculationTimestamp(now.minusDays(1)).build());
            // Checked long ago: due for recheck
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(3L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-3").status(IntegrityCheckTaskStatus.FINISHED).calculatedChecksumValue("sha-3").calculationTimestamp(now.minusDays(40)).build());
            return null;
        });

        var threshold = now.minusDays(30);
        var tasks = daoTestRule.inTransaction(() -> integrityCheckTaskDao.findNextExecutableTasks(threshold, 1));

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getFileId()).isEqualTo(3L);
    }

    @Test
    void findNextExecutableTasks_should_pick_least_recently_touched_first() {
        var now = OffsetDateTime.now();
        daoTestRule.inTransaction(() -> {
            // Never checked, but only just created: eligible via null calculationTimestamp
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(1L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-1").status(IntegrityCheckTaskStatus.OPEN).build());
            // Checked long ago: became eligible earlier than the new task was created, so should be picked first
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(2L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-2").status(IntegrityCheckTaskStatus.FINISHED).calculatedChecksumValue("sha-2").calculationTimestamp(now.minusDays(40)).build());
            return null;
        });

        var threshold = now.minusDays(30);
        var tasks = daoTestRule.inTransaction(() -> integrityCheckTaskDao.findNextExecutableTasks(threshold, 1));

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getFileId()).isEqualTo(2L);
    }

    @Test
    void findNextExecutableTasks_should_return_empty_when_nothing_is_due() {
        var now = OffsetDateTime.now();
        daoTestRule.inTransaction(() -> {
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(1L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-1").status(IntegrityCheckTaskStatus.FINISHED).calculatedChecksumValue("sha-1").calculationTimestamp(now.minusDays(1)).build());
            return null;
        });

        var tasks = daoTestRule.inTransaction(() -> integrityCheckTaskDao.findNextExecutableTasks(now.minusDays(30), 1));

        assertThat(tasks).isEmpty();
    }

    @Test
    void findNextExecutableTasks_should_return_up_to_maxResults_tasks_in_order() {
        var now = OffsetDateTime.now();
        daoTestRule.inTransaction(() -> {
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(1L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-1").status(IntegrityCheckTaskStatus.FINISHED).calculationTimestamp(now.minusDays(60)).build());
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(2L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-2").status(IntegrityCheckTaskStatus.FINISHED).calculationTimestamp(now.minusDays(50)).build());
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(3L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-3").status(IntegrityCheckTaskStatus.FINISHED).calculationTimestamp(now.minusDays(40)).build());
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(4L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-4").status(IntegrityCheckTaskStatus.FINISHED).calculationTimestamp(now.minusDays(35)).build());
            return null;
        });

        var threshold = now.minusDays(30);
        var tasks = daoTestRule.inTransaction(() -> integrityCheckTaskDao.findNextExecutableTasks(threshold, 2));

        assertThat(tasks).hasSize(2);
        assertThat(tasks).extracting(IntegrityCheckTask::getFileId).containsExactly(1L, 2L);
    }

    @Test
    void findNextExecutableTasks_should_return_fewer_than_maxResults_when_not_enough_eligible() {
        var now = OffsetDateTime.now();
        daoTestRule.inTransaction(() -> {
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(1L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-1").status(IntegrityCheckTaskStatus.FINISHED).calculationTimestamp(now.minusDays(40)).build());
            // Recently checked, not eligible
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(2L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-2").status(IntegrityCheckTaskStatus.FINISHED).calculationTimestamp(now.minusDays(1)).build());
            return null;
        });

        var tasks = daoTestRule.inTransaction(() -> integrityCheckTaskDao.findNextExecutableTasks(now.minusDays(30), 5));

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getFileId()).isEqualTo(1L);
    }

    @Test
    void findScheduledTasks_should_return_tasks_with_status_scheduled() {
        daoTestRule.inTransaction(() -> {
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(1L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-1").status(IntegrityCheckTaskStatus.OPEN).build());
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(2L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-2").status(IntegrityCheckTaskStatus.SCHEDULED).build());
            return null;
        });

        List<IntegrityCheckTask> tasks = daoTestRule.inTransaction(() -> integrityCheckTaskDao.findScheduledTasks());

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getFileId()).isEqualTo(2L);
    }

    @Test
    void findOneByFileId_should_return_the_task_for_that_file() {
        daoTestRule.inTransaction(() -> {
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(1L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-1").build());
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(2L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-2").build());
            return null;
        });

        var task = daoTestRule.inTransaction(() -> integrityCheckTaskDao.findOneByFileId(2L));

        assertThat(task).isPresent();
        assertThat(task.get().getFileId()).isEqualTo(2L);
        assertThat(daoTestRule.inTransaction(() -> integrityCheckTaskDao.findOneByFileId(99L))).isEmpty();
    }
}
