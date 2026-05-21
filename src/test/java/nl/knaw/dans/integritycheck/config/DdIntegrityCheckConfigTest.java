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
package nl.knaw.dans.integritycheck.config;

import io.dropwizard.configuration.YamlConfigurationFactory;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.jersey.validation.Validators;
import io.dropwizard.util.DataSize;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;

class DdIntegrityCheckConfigTest {

    @Test
    void should_load_config_from_yaml() throws Exception {
        var factory = new YamlConfigurationFactory<>(DdIntegrityCheckConfig.class,
            Validators.newValidator(),
            Jackson.newObjectMapper(),
            "dw");
        
        var configFile = new File("src/main/assembly/dist/cfg/config.yml");
        var config = factory.build(configFile);

        assertThat(config.getIntegrityCheck()).isNotNull();
        assertThat(config.getIntegrityCheck().getMinimalFrequency()).isEqualTo(Duration.days(30));
        assertThat(config.getIntegrityCheck().getChunkSize().toGigabytes()).isEqualTo(1);
    }
}
