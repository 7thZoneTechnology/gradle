/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.api.tasks

import org.gradle.integtests.fixtures.DirectoryBuildCacheFixture

class CachedPathSensitivityIntegrationTest extends AbstractPathSensitivityIntegrationSpec implements DirectoryBuildCacheFixture {
    def setup() {
        buildFile << """
            task clean {
                doLast {
                    delete(tasks*.outputs*.files)
                }
            }
        """
    }

    @Override
    void execute(String... tasks) {
        withBuildCache().succeeds tasks
    }

    @Override
    void cleanWorkspace() {
        run "clean"
    }
}
