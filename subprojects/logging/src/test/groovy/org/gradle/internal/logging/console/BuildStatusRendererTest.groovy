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

package org.gradle.internal.logging.console

import org.gradle.internal.logging.OutputSpecification
import org.gradle.internal.logging.events.OutputEventListener
import org.gradle.internal.logging.text.Span
import org.gradle.internal.logging.text.Style
import org.gradle.internal.nativeintegration.console.ConsoleMetaData
import spock.lang.Ignore
import spock.lang.Subject

@Ignore
class BuildStatusRendererTest extends OutputSpecification {
    def listener = Mock(OutputEventListener)
    def statusLabel = Mock(StyledLabel)
    def consoleMetaData = Mock(ConsoleMetaData)

    @Subject buildStatusRenderer = new BuildStatusRenderer(listener, statusLabel, consoleMetaData)

    // FIXME(ew): alla deez tests
    def "label renders most recent status"() {
        when:
        buildStatusRenderer.onOutput(start(shortDescription: 'status'))

        then:
        1 * consoleMetaData.getCols() >> 80
        // FIXME: As StyledLabel is designed, it's nearly impossible to assert the content here
        1 * statusLabel.setText(new ArrayList<Span>(new Span(Style.NORMAL, 'status')))
        0 * statusLabel._

        when:
        buildStatusRenderer.onOutput(progress('progress'))

        then:
        1 * statusLabel.setText(_ as List)
        0 * statusLabel._

        when:
        buildStatusRenderer.onOutput(complete('complete'))

        then:
        1 * statusLabel.setText(_ as List)
        0 * statusLabel._
    }

    def coalescesMultipleQueuedStatusUpdates() {
        when:
        buildStatusRenderer.onOutput(start(status: 'status'))

        then:
        1 * statusLabel.setText('> status')
        0 * statusLabel._

        when:
        buildStatusRenderer.onOutput(progress('progress1'))
        buildStatusRenderer.onOutput(progress('progress2'))
        buildStatusRenderer.onOutput(progress('progress3'))

        then:
        1 * statusLabel.setText('> progress3')
        0 * statusLabel._
    }

    def coalescesQueuedOperationStartStopAndStatusUpdates() {
        when:
        buildStatusRenderer.onOutput(event('something'))

        then:
        0 * statusLabel._

        when:
        buildStatusRenderer.onOutput(start(status: 'status'))
        buildStatusRenderer.onOutput(progress('progress1'))
        buildStatusRenderer.onOutput(progress('progress2'))
        buildStatusRenderer.onOutput(complete('done'))

        then:
        0 * statusLabel._
    }

    def statusBarTracksOperationProgressForOperationWithNoStatus() {
        when:
        buildStatusRenderer.onOutput(start(status: ''))

        then:
        1 * statusLabel.setText('')
        0 * statusLabel._

        when:
        buildStatusRenderer.onOutput(complete('complete'))

        then:
        1 * statusLabel.setText('')
        0 * statusLabel._
    }

    def statusBarTracksOperationProgressForOperationWithNoInitialStatus() {
        when:
        buildStatusRenderer.onOutput(start(status: ''))

        then:
        1 * statusLabel.setText('')
        0 * statusLabel._

        when:
        buildStatusRenderer.onOutput(progress('progress'))

        then:
        1 * statusLabel.setText('> progress')
        0 * statusLabel._

        when:
        buildStatusRenderer.onOutput(complete('complete'))

        then:
        1 * statusLabel.setText('')
        0 * statusLabel._
    }

    def statusBarTracksNestedOperationProgress() {
        when:
        buildStatusRenderer.onOutput(start(status: 'status'))

        then:
        1 * statusLabel.setText('> status')
        0 * statusLabel._

        when:
        buildStatusRenderer.onOutput(progress('progress'))

        then:
        1 * statusLabel.setText('> progress')
        0 * statusLabel._

        when:
        buildStatusRenderer.onOutput(start(status: 'status2'))

        then:
        1 * statusLabel.setText('> progress > status2')
        0 * statusLabel._

        when:
        buildStatusRenderer.onOutput(progress('progress2'))

        then:
        1 * statusLabel.setText('> progress > progress2')
        0 * statusLabel._

        when:
        buildStatusRenderer.onOutput(complete('complete'))

        then:
        1 * statusLabel.setText('> progress')
        0 * statusLabel._

        when:
        buildStatusRenderer.onOutput(complete('complete'))

        then:
        1 * statusLabel.setText('')
        0 * statusLabel._
    }

    def statusBarTracksNestedOperationProgressForOperationsWithNoInitialStatus() {
        when:
        buildStatusRenderer.onOutput(start(status: ''))
        buildStatusRenderer.onOutput(start(status: ''))

        then:
        2 * statusLabel.setText('')
        0 * statusLabel._

        when:
        buildStatusRenderer.onOutput(progress('progress'))

        then:
        1 * statusLabel.setText('> progress')
        0 * statusLabel._

        when:
        buildStatusRenderer.onOutput(complete('complete'))

        then:
        1 * statusLabel.setText('')
        0 * statusLabel._

        when:
        buildStatusRenderer.onOutput(complete('complete'))

        then:
        1 * statusLabel.setText('')
        0 * statusLabel._
    }

    def usesShortDescriptionWhenOperationHasNoStatus() {
        when:
        buildStatusRenderer.onOutput(start(shortDescription: 'short'))

        then:
        1 * statusLabel.setText('> short')
        0 * statusLabel._

        when:
        buildStatusRenderer.onOutput(progress('progress'))

        then:
        1 * statusLabel.setText('> progress')
        0 * statusLabel._

        when:
        buildStatusRenderer.onOutput(progress(''))

        then:
        1 * statusLabel.setText('> short')
        0 * statusLabel._

        when:
        buildStatusRenderer.onOutput(complete('complete'))

        then:
        1 * statusLabel.setText('')
        0 * statusLabel._
    }
}
