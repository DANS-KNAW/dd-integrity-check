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
import nl.knaw.dans.integritycheck.db.IntegrityCheckTaskDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(DropwizardExtensionsSupport.class)
class IntegrityCheckTaskSourceTest {

    private final DAOTestExtension daoTestRule = DAOTestExtension.newBuilder()
        .addEntityClass(IntegrityCheckTask.class)
        .build();

    private IntegrityCheckTaskDao integrityCheckTaskDao;
    private IntegrityCheckTaskSource taskSource;

    @BeforeEach
    void setUp() {
        integrityCheckTaskDao = new IntegrityCheckTaskDao(daoTestRule.getSessionFactory());
        taskSource = new IntegrityCheckTaskSource(integrityCheckTaskDao);
    }

    @Test
    void nextInput_should_return_first_available_task_and_mark_it_as_scheduled() {
        daoTestRule.inTransaction(() -> {
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(1L).expectedSha1("sha-1").build());
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(2L).expectedSha1("sha-2").build());
            return null;
        });

        Optional<IntegrityCheckTask> task = daoTestRule.inTransaction(() -> taskSource.nextInput());

        assertThat(task).isPresent();
        assertThat(task.get().getFileId()).isEqualTo(1L);
        assertThat(task.get().getStatus()).isEqualTo(IntegrityCheckTaskStatus.SCHEDULED);

        // Verify that the next call returns the second task, because first one is now SCHEDULED and nextInput only picks OPEN
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
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(1L).expectedSha1("sha-1").status(IntegrityCheckTaskStatus.OPEN).build());
            integrityCheckTaskDao.save(IntegrityCheckTask.builder().fileId(2L).expectedSha1("sha-2").status(IntegrityCheckTaskStatus.SCHEDULED).build());
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
    void nextInput_should_return_empty_if_no_tasks() {
        Optional<IntegrityCheckTask> task = daoTestRule.inTransaction(() -> taskSource.nextInput());

        assertThat(task).isEmpty();
    }
}
