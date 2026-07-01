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

import lombok.RequiredArgsConstructor;
import nl.knaw.dans.integritycheck.config.SchedulingConfig;
import nl.knaw.dans.integritycheck.db.IntegrityCheckTaskDao;
import nl.knaw.dans.lib.util.pollingtaskexec.TaskSource;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class IntegrityCheckTaskSource implements TaskSource<IntegrityCheckTask> {
    private final IntegrityCheckTaskDao integrityCheckTaskDao;
    private final SchedulingConfig schedulingConfig;
    private final Duration minimalFrequency;
    private final Clock clock;

    public IntegrityCheckTaskSource(IntegrityCheckTaskDao integrityCheckTaskDao, SchedulingConfig schedulingConfig, Duration minimalFrequency) {
        this(integrityCheckTaskDao, schedulingConfig, minimalFrequency, Clock.systemDefaultZone());
    }

    @Override
    public List<IntegrityCheckTask> nextInputs() {
        if (isOutsideWindow()) {
            return List.of();
        }
        var threshold = OffsetDateTime.now(clock).minus(minimalFrequency);
        var tasks = integrityCheckTaskDao.findNextExecutableTasks(threshold, schedulingConfig.getBatchSize());
        tasks.forEach(task -> {
            task.setStatus(IntegrityCheckTaskStatus.SCHEDULED);
            integrityCheckTaskDao.save(task);
        });
        return tasks;
    }

    @Override
    public Optional<IntegrityCheckTask> nextInput() {
        return nextInputs().stream().findFirst();
    }

    private boolean isOutsideWindow() {
        var now = LocalTime.now(clock);
        var startAfter = schedulingConfig.getStartAfter();
        var startBefore = schedulingConfig.getStartBefore();

        if (startAfter.isBefore(startBefore)) {
            // Standard window, e.g., 09:00 to 17:00
            return now.isBefore(startAfter) || now.isAfter(startBefore);
        }
        else {
            // Window crossing midnight, e.g., 22:00 to 06:00
            return now.isBefore(startAfter) && now.isAfter(startBefore);
        }
    }
}
