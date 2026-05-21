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

import nl.knaw.dans.integritycheck.config.IntegrityCheckConfig;
import nl.knaw.dans.integritycheck.db.IntegrityCheckTaskDao;
import nl.knaw.dans.lib.dataverse.DataverseClient;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrityCheckTaskFactoryTest {

    @Test
    void create_should_return_IntegrityCheckExecutorTask() {
        var dao = Mockito.mock(IntegrityCheckTaskDao.class);
        var sessionFactory = Mockito.mock(SessionFactory.class);
        var dataverseClient = Mockito.mock(DataverseClient.class);
        var config = Mockito.mock(IntegrityCheckConfig.class);
        var factory = new IntegrityCheckTaskFactory(dao, sessionFactory, dataverseClient, config);
        var taskRecord = IntegrityCheckTask.builder().id(1L).fileId(1L).build();

        Runnable task = factory.create(taskRecord);

        assertThat(task).isInstanceOf(IntegrityCheckExecutorTask.class);
    }
}
