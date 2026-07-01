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
package nl.knaw.dans.integritycheck.core;

import io.dropwizard.testing.junit5.DAOTestExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import nl.knaw.dans.integritycheck.config.SchedulingConfig;
import nl.knaw.dans.integritycheck.db.IntegrityCheckTaskDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(DropwizardExtensionsSupport.class)
class IntegrityCheckTaskSourceTest {

    private static final Duration MINIMAL_FREQUENCY = Duration.ofDays(30);

    private final DAOTestExtension daoTestRule = DAOTestExtension.newBuilder()
        .addEntityClass(IntegrityCheckTask.class)
        .build();

    private IntegrityCheckTaskDao integrityCheckTaskDao;
    private IntegrityCheckTaskSource taskSource;
    private SchedulingConfig schedulingConfig;

    @BeforeEach
    void setUp() {
        integrityCheckTaskDao = new IntegrityCheckTaskDao(daoTestRule.getSessionFactory());
        schedulingConfig = Mockito.mock(SchedulingConfig.class);
        // Default to always open window with batch size 1
        when(schedulingConfig.getStartAfter()).thenReturn(LocalTime.MIN);
        when(schedulingConfig.getStartBefore()).thenReturn(LocalTime.MAX);
        when(schedulingConfig.getBatchSize()).thenReturn(1);
        taskSource = new IntegrityCheckTaskSource(integrityCheckTaskDao, schedulingConfig, MINIMAL_FREQUENCY);
    }

    @Test
    void nextInput_should_return_first_available_task_and_mark_it_as_scheduled() {
        daoTestRule.inTransaction(() -> {
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(1L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-1").build());
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(2L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-2").build());
            return null;
        });

        Optional<IntegrityCheckTask> task = daoTestRule.inTransaction(() -> taskSource.nextInput());

        assertThat(task).isPresent();
        assertThat(task.get().getFileId()).isEqualTo(1L);
        assertThat(task.get().getStatus()).isEqualTo(IntegrityCheckTaskStatus.SCHEDULED);

        // Verify that the next call returns the second task, because the first one is now SCHEDULED and thus excluded
        Optional<IntegrityCheckTask> nextTask = daoTestRule.inTransaction(() -> taskSource.nextInput());
        assertThat(nextTask).isPresent();
        assertThat(nextTask.get().getFileId()).isEqualTo(2L);
        assertThat(nextTask.get().getStatus()).isEqualTo(IntegrityCheckTaskStatus.SCHEDULED);

        // Verify that the next call returns empty because both are SCHEDULED
        Optional<IntegrityCheckTask> thirdTask = daoTestRule.inTransaction(() -> taskSource.nextInput());
        assertThat(thirdTask).isEmpty();
    }

    @Test
    void nextInput_should_not_return_scheduled_tasks() {
        daoTestRule.inTransaction(() -> {
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(1L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-1").status(IntegrityCheckTaskStatus.OPEN).build());
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(2L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-2").status(IntegrityCheckTaskStatus.SCHEDULED).build());
            return null;
        });

        Optional<IntegrityCheckTask> task = daoTestRule.inTransaction(() -> taskSource.nextInput());

        assertThat(task).isPresent();
        assertThat(task.get().getFileId()).isEqualTo(1L);
        assertThat(task.get().getStatus()).isEqualTo(IntegrityCheckTaskStatus.SCHEDULED);

        Optional<IntegrityCheckTask> nextTask = daoTestRule.inTransaction(() -> taskSource.nextInput());
        assertThat(nextTask).isEmpty();
    }

    @Test
    void nextInput_should_reselect_finished_task_checked_longer_ago_than_minimal_frequency() {
        daoTestRule.inTransaction(() -> {
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(1L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-1")
                .status(IntegrityCheckTaskStatus.FINISHED).calculatedChecksumValue("sha-1").calculationTimestamp(OffsetDateTime.now().minusDays(40)).build());
            return null;
        });

        Optional<IntegrityCheckTask> task = daoTestRule.inTransaction(() -> taskSource.nextInput());

        assertThat(task).isPresent();
        assertThat(task.get().getFileId()).isEqualTo(1L);
        assertThat(task.get().getStatus()).isEqualTo(IntegrityCheckTaskStatus.SCHEDULED);
    }

    @Test
    void nextInput_should_not_reselect_recently_finished_task() {
        daoTestRule.inTransaction(() -> {
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(1L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-1")
                .status(IntegrityCheckTaskStatus.FINISHED).calculatedChecksumValue("sha-1").calculationTimestamp(OffsetDateTime.now().minusDays(1)).build());
            return null;
        });

        Optional<IntegrityCheckTask> task = daoTestRule.inTransaction(() -> taskSource.nextInput());

        assertThat(task).isEmpty();
    }

    @Test
    void nextInput_should_return_empty_if_no_tasks() {
        Optional<IntegrityCheckTask> task = daoTestRule.inTransaction(() -> taskSource.nextInput());

        assertThat(task).isEmpty();
    }

    @Test
    void nextInput_should_return_empty_if_current_time_is_before_startAfter() {
        when(schedulingConfig.getStartAfter()).thenReturn(LocalTime.now().plusHours(1));
        when(schedulingConfig.getStartBefore()).thenReturn(LocalTime.now().plusHours(2));

        daoTestRule.inTransaction(() -> {
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(1L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-1").build());
            return null;
        });

        Optional<IntegrityCheckTask> task = daoTestRule.inTransaction(() -> taskSource.nextInput());
        assertThat(task).isEmpty();
    }

    @Test
    void nextInput_should_return_empty_if_current_time_is_after_startBefore() {
        when(schedulingConfig.getStartAfter()).thenReturn(LocalTime.now().minusHours(2));
        when(schedulingConfig.getStartBefore()).thenReturn(LocalTime.now().minusHours(1));

        daoTestRule.inTransaction(() -> {
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(1L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-1").build());
            return null;
        });

        Optional<IntegrityCheckTask> task = daoTestRule.inTransaction(() -> taskSource.nextInput());
        assertThat(task).isEmpty();
    }

    @Test
    void nextInput_should_return_task_if_current_time_is_within_window() {
        when(schedulingConfig.getStartAfter()).thenReturn(LocalTime.now().minusHours(1));
        when(schedulingConfig.getStartBefore()).thenReturn(LocalTime.now().plusHours(1));

        daoTestRule.inTransaction(() -> {
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(1L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-1").build());
            return null;
        });

        Optional<IntegrityCheckTask> task = daoTestRule.inTransaction(() -> taskSource.nextInput());
        assertThat(task).isPresent();
    }

    @Test
    void nextInput_should_handle_midnight_crossing_window_inside() {
        // Window from 22:00 to 06:00. Assume current time is 23:00
        when(schedulingConfig.getStartAfter()).thenReturn(LocalTime.of(22, 0));
        when(schedulingConfig.getStartBefore()).thenReturn(LocalTime.of(6, 0));

        // 2024-01-01 23:00 UTC
        Clock clock = Clock.fixed(Instant.parse("2024-01-01T23:00:00Z"), ZoneId.of("UTC"));
        taskSource = new IntegrityCheckTaskSource(integrityCheckTaskDao, schedulingConfig, MINIMAL_FREQUENCY, clock);

        daoTestRule.inTransaction(() -> {
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(1L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-1").build());
            return null;
        });

        Optional<IntegrityCheckTask> task = daoTestRule.inTransaction(() -> taskSource.nextInput());
        assertThat(task).isPresent();
    }

    @Test
    void nextInput_should_handle_midnight_crossing_window_inside_after_midnight() {
        // Window from 22:00 to 06:00. Assume current time is 01:00
        when(schedulingConfig.getStartAfter()).thenReturn(LocalTime.of(22, 0));
        when(schedulingConfig.getStartBefore()).thenReturn(LocalTime.of(6, 0));

        // 2024-01-01 01:00 UTC
        Clock clock = Clock.fixed(Instant.parse("2024-01-01T01:00:00Z"), ZoneId.of("UTC"));
        taskSource = new IntegrityCheckTaskSource(integrityCheckTaskDao, schedulingConfig, MINIMAL_FREQUENCY, clock);

        daoTestRule.inTransaction(() -> {
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(1L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-1").build());
            return null;
        });

        Optional<IntegrityCheckTask> task = daoTestRule.inTransaction(() -> taskSource.nextInput());
        assertThat(task).isPresent();
    }

    @Test
    void nextInput_should_handle_midnight_crossing_window_outside() {
        // Window from 22:00 to 06:00. Assume current time is 12:00
        when(schedulingConfig.getStartAfter()).thenReturn(LocalTime.of(22, 0));
        when(schedulingConfig.getStartBefore()).thenReturn(LocalTime.of(6, 0));

        // 2024-01-01 12:00 UTC
        Clock clock = Clock.fixed(Instant.parse("2024-01-01T12:00:00Z"), ZoneId.of("UTC"));
        taskSource = new IntegrityCheckTaskSource(integrityCheckTaskDao, schedulingConfig, MINIMAL_FREQUENCY, clock);

        daoTestRule.inTransaction(() -> {
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(1L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-1").build());
            return null;
        });

        Optional<IntegrityCheckTask> task = daoTestRule.inTransaction(() -> taskSource.nextInput());
        assertThat(task).isEmpty();
    }

    @Test
    void nextInputs_should_return_multiple_tasks_up_to_batch_size_and_mark_them_scheduled() {
        when(schedulingConfig.getBatchSize()).thenReturn(3);
        daoTestRule.inTransaction(() -> {
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(1L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-1").build());
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(2L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-2").build());
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(3L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-3").build());
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(4L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-4").build());
            return null;
        });

        List<IntegrityCheckTask> tasks = daoTestRule.inTransaction(() -> taskSource.nextInputs());

        assertThat(tasks).hasSize(3);
        assertThat(tasks).extracting(IntegrityCheckTask::getFileId).containsExactly(1L, 2L, 3L);
        assertThat(tasks).extracting(IntegrityCheckTask::getStatus).containsOnly(IntegrityCheckTaskStatus.SCHEDULED);

        // The 4th task is still available in the next batch
        List<IntegrityCheckTask> nextBatch = daoTestRule.inTransaction(() -> taskSource.nextInputs());
        assertThat(nextBatch).hasSize(1);
        assertThat(nextBatch.get(0).getFileId()).isEqualTo(4L);
    }

    @Test
    void nextInputs_should_return_empty_list_if_outside_window() {
        when(schedulingConfig.getStartAfter()).thenReturn(LocalTime.now().plusHours(1));
        when(schedulingConfig.getStartBefore()).thenReturn(LocalTime.now().plusHours(2));
        when(schedulingConfig.getBatchSize()).thenReturn(3);

        daoTestRule.inTransaction(() -> {
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(1L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-1").build());
            return null;
        });

        List<IntegrityCheckTask> tasks = daoTestRule.inTransaction(() -> taskSource.nextInputs());
        assertThat(tasks).isEmpty();
    }

    @Test
    void nextInputs_should_return_fewer_tasks_than_batch_size_when_queue_is_short() {
        when(schedulingConfig.getBatchSize()).thenReturn(5);
        daoTestRule.inTransaction(() -> {
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(1L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-1").build());
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(2L).filesize(100L).checksumType("SHA-1").expectedChecksumValue("sha-2").build());
            return null;
        });

        List<IntegrityCheckTask> tasks = daoTestRule.inTransaction(() -> taskSource.nextInputs());

        assertThat(tasks).hasSize(2);
        assertThat(tasks).extracting(IntegrityCheckTask::getStatus).containsOnly(IntegrityCheckTaskStatus.SCHEDULED);
    }
}
