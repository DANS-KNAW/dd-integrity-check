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

import io.dropwizard.hibernate.UnitOfWork;
import io.dropwizard.lifecycle.Managed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.knaw.dans.integritycheck.db.IntegrityCheckTaskDao;
import nl.knaw.dans.lib.util.pollingtaskexec.TaskScheduler;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class IntegrityCheckStartupHandler implements Managed {
    private final IntegrityCheckTaskDao integrityCheckTaskDao;
    private final IntegrityCheckTaskFactory integrityCheckTaskFactory;
    private final TaskScheduler taskScheduler;

    @Override
    @UnitOfWork
    public void start() throws Exception {
        log.info("Checking for previously scheduled tasks at startup...");
        List<IntegrityCheckTask> scheduledTasks = integrityCheckTaskDao.findScheduledTasks();
        log.info("Found {} tasks to resume", scheduledTasks.size());
        for (IntegrityCheckTask task : scheduledTasks) {
            log.info("Rescheduling task for file ID: {}", task.getFileId());
            taskScheduler.schedule(integrityCheckTaskFactory.create(task));
        }
    }

    @Override
    public void stop() throws Exception {
        // Nothing to do
    }
}
