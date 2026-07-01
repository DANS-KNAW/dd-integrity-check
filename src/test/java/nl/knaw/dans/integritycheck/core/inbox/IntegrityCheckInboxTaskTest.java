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
package nl.knaw.dans.integritycheck.core.inbox;

import io.dropwizard.testing.junit5.DAOTestExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import nl.knaw.dans.integritycheck.core.IntegrityCheckTask;
import nl.knaw.dans.integritycheck.core.IntegrityCheckTaskStatus;
import nl.knaw.dans.integritycheck.db.IntegrityCheckTaskDao;
import org.apache.commons.io.FileUtils;
import org.hibernate.Session;
import org.hibernate.context.internal.ManagedSessionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@ExtendWith(DropwizardExtensionsSupport.class)
class IntegrityCheckInboxTaskTest {

    private final DAOTestExtension daoTestRule = DAOTestExtension.newBuilder()
        .addEntityClass(IntegrityCheckTask.class)
        .build();

    private IntegrityCheckTaskDao integrityCheckTaskDao;

    @TempDir
    Path tempDir;

    private File outbox;

    @BeforeEach
    void setUp() {
        integrityCheckTaskDao = new IntegrityCheckTaskDao(daoTestRule.getSessionFactory());
        outbox = tempDir.resolve("outbox").toFile();
        outbox.mkdirs();
    }

    @Test
    void should_schedule_tasks_from_csv() throws Exception {
        Path csvFile = tempDir.resolve("input.csv");
        FileUtils.writeStringToFile(csvFile.toFile(), "FILEID,FILESIZE,CHECKSUM_TYPE,CHECKSUM_VALUE\n1,100,SHA-1,sha1-1\n2,200,SHA-1,sha1-2", StandardCharsets.UTF_8);

        IntegrityCheckInboxTask task = new IntegrityCheckInboxTask(
            csvFile,
            integrityCheckTaskDao,
            daoTestRule.getSessionFactory(),
            outbox
        );

        task.run();

        List<IntegrityCheckTask> tasks;
        Session session = daoTestRule.getSessionFactory().openSession();
        try {
            ManagedSessionContext.bind(session);
            tasks = session.createQuery("FROM IntegrityCheckTask", IntegrityCheckTask.class).list();
        } finally {
            ManagedSessionContext.unbind(daoTestRule.getSessionFactory());
            session.close();
        }
        assertThat(tasks).hasSize(2);
        assertThat(tasks).extracting(IntegrityCheckTask::getFileId).containsExactlyInAnyOrder(1L, 2L);
        assertThat(new File(outbox, "input.csv")).exists();
    }

    @Test
    void should_skip_existing_task() throws Exception {
        // Prepare existing task
        daoTestRule.inTransaction(() -> {
            IntegrityCheckTask pendingTask = new IntegrityCheckTask();
            pendingTask.setFileId(1L);
            pendingTask.setFilesize(100L);
            pendingTask.setChecksumType("SHA-1");
            pendingTask.setExpectedChecksumValue("old-sha1");
            integrityCheckTaskDao.save(pendingTask);
        });

        Path csvFile = tempDir.resolve("input.csv");
        FileUtils.writeStringToFile(csvFile.toFile(), "FILEID,FILESIZE,CHECKSUM_TYPE,CHECKSUM_VALUE\n1,100,SHA-1,new-sha1", StandardCharsets.UTF_8);

        IntegrityCheckInboxTask task = new IntegrityCheckInboxTask(
            csvFile,
            integrityCheckTaskDao,
            daoTestRule.getSessionFactory(),
            outbox
        );

        task.run();

        List<IntegrityCheckTask> tasks;
        Session session = daoTestRule.getSessionFactory().openSession();
        try {
            ManagedSessionContext.bind(session);
            tasks = integrityCheckTaskDao.findByFileId(1L);
        } finally {
            ManagedSessionContext.unbind(daoTestRule.getSessionFactory());
            session.close();
        }
        // DataFile records are immutable, so the existing task is skipped and its metadata is left unchanged.
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getExpectedChecksumValue()).isEqualTo("old-sha1");
    }

    @Test
    void should_not_change_status_or_calculation_timestamp_of_existing_task() throws Exception {
        // Prepare previously executed task
        var calculationTimestamp = OffsetDateTime.now().minusDays(10);
        daoTestRule.inTransaction(() -> {
            IntegrityCheckTask recentTask = new IntegrityCheckTask();
            recentTask.setFileId(1L);
            recentTask.setFilesize(100L);
            recentTask.setChecksumType("SHA-1");
            recentTask.setExpectedChecksumValue("sha1-1");
            recentTask.setCalculatedChecksumValue("sha1-1");
            recentTask.setCalculationTimestamp(calculationTimestamp);
            recentTask.setStatus(IntegrityCheckTaskStatus.FINISHED);
            integrityCheckTaskDao.save(recentTask);
        });

        Path csvFile = tempDir.resolve("input.csv");
        FileUtils.writeStringToFile(csvFile.toFile(), "FILEID,FILESIZE,CHECKSUM_TYPE,CHECKSUM_VALUE\n1,100,SHA-1,sha1-1", StandardCharsets.UTF_8);

        IntegrityCheckInboxTask task = new IntegrityCheckInboxTask(
            csvFile,
            integrityCheckTaskDao,
            daoTestRule.getSessionFactory(),
            outbox
        );

        task.run();

        List<IntegrityCheckTask> tasks;
        Session session = daoTestRule.getSessionFactory().openSession();
        try {
            ManagedSessionContext.bind(session);
            tasks = integrityCheckTaskDao.findByFileId(1L);
        } finally {
            ManagedSessionContext.unbind(daoTestRule.getSessionFactory());
            session.close();
        }
        // DataFile records are immutable, so the inbox skips existing tasks entirely,
        // leaving status and calculationTimestamp unchanged.
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getStatus()).isEqualTo(IntegrityCheckTaskStatus.FINISHED);
        assertThat(tasks.get(0).getCalculationTimestamp()).isCloseTo(calculationTimestamp, within(1, ChronoUnit.SECONDS));
    }

    @Test
    void should_schedule_tasks_from_large_csv() throws Exception {
        int totalRecords = 2500;
        StringBuilder csvContent = new StringBuilder("FILEID,FILESIZE,CHECKSUM_TYPE,CHECKSUM_VALUE\n");
        for (int i = 0; i < totalRecords; i++) {
            csvContent.append(i).append(",100,SHA-1,sha1-").append(i).append("\n");
        }

        Path csvFile = tempDir.resolve("large_input.csv");
        FileUtils.writeStringToFile(csvFile.toFile(), csvContent.toString(), StandardCharsets.UTF_8);

        IntegrityCheckInboxTask task = new IntegrityCheckInboxTask(
            csvFile,
            integrityCheckTaskDao,
            daoTestRule.getSessionFactory(),
            outbox
        );

        task.run();

        long count;
        Session session = daoTestRule.getSessionFactory().openSession();
        try {
            ManagedSessionContext.bind(session);
            count = session.createQuery("SELECT COUNT(t) FROM IntegrityCheckTask t", Long.class).getSingleResult();
        } finally {
            ManagedSessionContext.unbind(daoTestRule.getSessionFactory());
            session.close();
        }
        assertThat(count).isEqualTo(totalRecords);
        assertThat(new File(outbox, "large_input.csv")).exists();
    }
}
