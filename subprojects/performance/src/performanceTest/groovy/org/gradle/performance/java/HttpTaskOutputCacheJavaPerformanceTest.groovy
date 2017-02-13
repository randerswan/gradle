/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.performance.java

import org.gradle.performance.AbstractCrossVersionPerformanceTest
import org.gradle.test.fixtures.keystore.TestKeyStore
import org.gradle.test.fixtures.server.http.HttpBuildCache
import org.junit.Rule
import spock.lang.Unroll

@Unroll
class HttpTaskOutputCacheJavaPerformanceTest extends AbstractCrossVersionPerformanceTest {

    @Rule
    public HttpBuildCache buildCache = new HttpBuildCache(tmpDir)

    def "Builds '#testProject' calling #tasks with remote http cache"() {
        given:
        def initScript = tmpDir.file('httpCacheInit.gradle')
        runner.testId = "cached ${tasks.join(' ')} $testProject project - remote http cache"
        runner.testProject = testProject
        runner.tasksToRun = tasks
        runner.gradleOpts = ["-Xms768m", "-Xmx768m"]
        runner.args = ['-Dorg.gradle.cache.tasks=true', '--parallel', "-I${initScript.absolutePath}"]
        /*
         * Since every second build is a 'clean', we need more iterations
         * than usual to get reliable results.
         */
        runner.runs = 20
        runner.setupCleanupOnOddRounds()

        buildCache.start()

        initScript << remoteCacheInitScript

        when:
        def result = runner.run()

        then:
        result.assertCurrentVersionHasNotRegressed()

        where:
        testProject      | tasks
        'bigOldJava'     | ['assemble']
        'largeWithJUnit' | ['build']
    }

    def "Builds '#testProject' calling #tasks with remote https cache"() {
        given:
        def keyStore = TestKeyStore.init(tmpDir.file('ssl-keystore'))
        def initScript = tmpDir.file('httpCacheInit.gradle')
        runner.testId = "cached ${tasks.join(' ')} $testProject project - remote https cache"
        runner.testProject = testProject
        runner.tasksToRun = tasks
        runner.gradleOpts = ["-Xms768m", "-Xmx768m"]
        runner.args = ['-Dorg.gradle.cache.tasks=true', '--parallel', "-I${initScript.absolutePath}"] + keyStore.serverAndClientCertArgs
        /*
         * Since every second build is a 'clean', we need more iterations
         * than usual to get reliable results.
         */
        runner.runs = 20
        runner.setupCleanupOnOddRounds()

        keyStore.enableSslWithServerCert(buildCache)
        buildCache.start()

        initScript << remoteCacheInitScript

        when:
        def result = runner.run()

        then:
        result.assertCurrentVersionHasNotRegressed()

        where:
        testProject      | tasks
        'bigOldJava'     | ['assemble']
        'largeWithJUnit' | ['build']
    }

    private String getRemoteCacheInitScript() {
        """                                
            if (GradleVersion.current() > GradleVersion.version('3.4')) {
                settingsEvaluated { settings ->
                    def httpCacheClass = Class.forName('org.gradle.caching.http.HttpBuildCache')
                    settings.buildCache {
                        remote(httpCacheClass) {
                            url = '${buildCache.uri}/'
                        }
                    }
                }
            } else {
                def httpCacheClass = Class.forName('org.gradle.caching.http.internal.HttpBuildCacheFactory')
                gradle.buildCache.useCacheFactory(
                    httpCacheClass.getConstructor(URI.class).newInstance(
                        new URI('${buildCache.uri}/')
                    )
                )
            }
        """.stripIndent()
    }
}
