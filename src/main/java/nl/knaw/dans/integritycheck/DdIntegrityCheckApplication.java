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

package nl.knaw.dans.integritycheck;

import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import io.dropwizard.db.PooledDataSourceFactory;
import io.dropwizard.hibernate.HibernateBundle;
import nl.knaw.dans.integritycheck.config.DdIntegrityCheckConfig;
import nl.knaw.dans.integritycheck.core.IntegrityCheckTask;
import nl.knaw.dans.integritycheck.core.inbox.IntegrityCheckInboxTaskFactory;
import nl.knaw.dans.integritycheck.db.IntegrityCheckTaskDao;
import nl.knaw.dans.lib.util.inbox.Inbox;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.IOFileFilter;

public class DdIntegrityCheckApplication extends Application<DdIntegrityCheckConfig> {

    private final HibernateBundle<DdIntegrityCheckConfig> hibernateBundle = new HibernateBundle<>(IntegrityCheckTask.class) {
        @Override
        public PooledDataSourceFactory getDataSourceFactory(DdIntegrityCheckConfig configuration) {
            return configuration.getDatabase();
        }
    };

    public static void main(final String[] args) throws Exception {
        new DdIntegrityCheckApplication().run(args);
    }

    @Override
    public String getName() {
        return "DD Integrity Check";
    }

    @Override
    public void initialize(final Bootstrap<DdIntegrityCheckConfig> bootstrap) {
        bootstrap.addBundle(hibernateBundle);
    }

    @Override
    public void run(final DdIntegrityCheckConfig config, final Environment environment) {
        final IntegrityCheckTaskDao integrityCheckTaskDao = new IntegrityCheckTaskDao(hibernateBundle.getSessionFactory());

        final var inboxConfig = config.getIntegrityCheck();
        final var inboxTaskFactory = new IntegrityCheckInboxTaskFactory(
            integrityCheckTaskDao,
            hibernateBundle.getSessionFactory(),
            inboxConfig.getOutbox(),
            java.time.Duration.ofMillis(inboxConfig.getMinimalFrequency().toMilliseconds())
        );

        final var inbox = Inbox.builder()
            .inbox(inboxConfig.getInbox().toPath())
            .fileFilter(FileFilterUtils.fileFileFilter().and((FileFilterUtils.suffixFileFilter(".csv"))))
            .taskFactory(inboxTaskFactory)
            .executorService(environment.lifecycle().executorService("inbox").minThreads(1).maxThreads(1).build())
            .build();

        environment.lifecycle().manage(inbox);
    }

}
