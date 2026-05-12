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
import nl.knaw.dans.integritycheck.config.DdIntegrityCheckConfig;

public class DdIntegrityCheckApplication extends Application<DdIntegrityCheckConfig> {

    public static void main(final String[] args) throws Exception {
        new DdIntegrityCheckApplication().run(args);
    }

    @Override
    public String getName() {
        return "Dd Integrity Check";
    }

    @Override
    public void initialize(final Bootstrap<DdIntegrityCheckConfig> bootstrap) {
        // TODO: application initialization
    }

    @Override
    public void run(final DdIntegrityCheckConfig config, final Environment environment) {

    }

}
