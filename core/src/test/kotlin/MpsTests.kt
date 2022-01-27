/*
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

import assertk.all
import assertk.assertThat
import assertk.assertions.*
import jetbrains.mps.project.Project
import jetbrains.mps.tool.environment.EnvironmentConfig
import jetbrains.mps.tool.environment.IdeaEnvironment
import jetbrains.mps.util.PathManager
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.mps.openapi.model.SNode
import org.junit.jupiter.api.*
import org.modelix.modelcheck.api.*
import org.modelix.modelcheck.manager.MPSModelCheckManager
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.util.*
import kotlin.test.fail


val SNode.ref : NodeReference
    get() {
        return NodeReference(primary = "$MPS_NODEREF_PREFIX${this.reference}", alternates = emptyList())
    }

class MpsTests {

    private val okRoot: SNode
        get() {
            var node: SNode? = null
            project.repository.modelAccess.runReadAction {
                node = project.projectModels.first()?.rootNodes?.find { it.getProperty("name") == "Ok" }!!
            }
            return node!!
        }

    private val errorRoot: SNode
        get() {
            var node: SNode? = null
            project.repository.modelAccess.runReadAction {
                node = project.projectModels.first()?.rootNodes?.find { it.getProperty("name") == "Error" }!!
            }
            return node!!
        }

    private val warningRoot: SNode
        get() {
            var node: SNode? = null
            project.repository.modelAccess.runReadAction {
                node = project.projectModels.first()?.rootNodes?.find { it.getProperty("name") == "Warning" }!!
            }
            return node!!
        }

    private suspend fun MPSModelCheckManager.waitForJobToFinish(jobId: UUID) {
        var status = this.getJobStatus(jobId)
        while (status == CheckingJobStatus.Created || status == CheckingJobStatus.Running) {
            delay(10)
            status = this.getJobStatus(jobId)
        }
    }

    companion object {

        private fun EnvironmentConfig.addPreInstalledPlugin(folder: String, id: String): EnvironmentConfig {
            this.addPlugin(PathManager.getPreInstalledPluginsPath() + "/" + folder, id)
            return this
        }

        lateinit var project: Project
        lateinit var env: IdeaEnvironment


        private fun basicEnvironmentConfig(): EnvironmentConfig {

            // This is a somewhat "safe" set of default plugins. It should work with most of the projects we have encountered
            // mbeddr projects won't build with this set of plugins for unknown reasons, most probably the runtime
            // dependencies in the mbeddr plugins are so messed up that they simply broken beyond repair.

            val config = EnvironmentConfig
                .emptyConfig()
                .withDefaultPlugins()
                .withBuildPlugin()
                .withBootstrapLibraries()
                .withWorkbenchPath()
                .withVcsPlugin()
                .withCorePlugin()
                .withJavaPlugin()
                .addPreInstalledPlugin("mps-httpsupport", "jetbrains.mps.ide.httpsupport")
            return config
        }

        @BeforeAll
        @JvmStatic
        fun initMps() {
            val environmentConfig = basicEnvironmentConfig()

            env = IdeaEnvironment(environmentConfig)
            env.init()
            env.flushAllEvents()

            project = env.openProject(File("mps").absoluteFile)
            env.flushAllEvents()
        }

        @AfterAll
        @JvmStatic
        fun shutdownMps() {
            env.flushAllEvents()
            env.dispose()
        }
    }


    @Test
    fun `create checking job`() {
        MPSModelCheckManager(project.repository).use {
            val jobId = it.createModelCheckJob(okRoot.ref)!!
            assertThat(it.getJobStatus(jobId)).isNotNull()
        }
    }

    @Test
    fun `get checkers`() {
        MPSModelCheckManager(project.repository).use {
            val checkers = it.getAvailableCheckers()
            assertThat(checkers).isNotEmpty()
        }
    }

    @Test
    fun `getting status for none existing job throws exception`() {
        MPSModelCheckManager(project.repository).use {
            val jobId = UUID.randomUUID()
            assertThrows<IllegalArgumentException> {
                it.getJobStatus(jobId)
            }
        }
    }

    @Test
    fun `getting result for none existing job throws exception`() {
        MPSModelCheckManager(project.repository).use {
            val jobId = UUID.randomUUID()
            assertThrows<IllegalArgumentException> {
                it.getJobResult(jobId)
            }
        }
    }

    @Test
    fun `incomplete checking job returns null result`() {
        MPSModelCheckManager(project.repository).use {
            val jobId = it.createModelCheckJob(okRoot.ref)!!
            assertThat(it.getJobResult(jobId)).isNull()
        }
    }

    @Test
    fun `incomplete checking job returns empty results`() {
        MPSModelCheckManager(project.repository).use {
            val jobId = it.createModelCheckJob(okRoot.ref)!!
            assertThat(it.getJobResults(jobId)).isEmpty()
        }
    }

    @Test
    fun `checking job completes`() {
        MPSModelCheckManager(project.repository).use {
            val jobId = it.createModelCheckJob(okRoot.ref)!!
            runBlocking {
                it.waitForJobToFinish(jobId)
                assertThat(it.getJobStatus(jobId)).isEqualTo(CheckingJobStatus.Completed)
            }
        }
    }

    @Test
    fun `result for ok node is empty`() {
        MPSModelCheckManager(project.repository).use {
            val jobId = it.createModelCheckJob(okRoot.ref)!!
            runBlocking {
                it.waitForJobToFinish(jobId)
                assertThat(it.getJobStatus(jobId)).isEqualTo(CheckingJobStatus.Completed)
                val jobResult = it.getJobResult(jobId)
                assertThat(jobResult).isNotNull().prop("messages") { it.messages }.isEmpty()
            }
        }
    }

    @Test
    fun `results for ok node is working`() {
        MPSModelCheckManager(project.repository).use {
            val jobId = it.createModelCheckJob(okRoot.ref)!!
            runBlocking {
                it.waitForJobToFinish(jobId)
                assertThat(it.getJobStatus(jobId)).isEqualTo(CheckingJobStatus.Completed)
                val jobResult = it.getJobResult(jobId)
                assertThat(it.getJobResults(jobId)).containsExactly(jobResult)
            }
        }
    }

    @Test
    fun `subscribing to a completed job yields last result`() {
        MPSModelCheckManager(project.repository).use {
            val jobId = it.createModelCheckJob(okRoot.ref)!!
            runBlocking {
                it.waitForJobToFinish(jobId)
                assertThat(it.getJobStatus(jobId)).isEqualTo(CheckingJobStatus.Completed)
                val jobResult = it.getJobResult(jobId)

                assertTimeout(Duration.ofSeconds(30)) {
                    runBlocking {
                        it.subscribeToJob(jobId).take(1).catch {
                            fail()
                        }.collect {
                            assertThat(it).isEqualTo(jobResult)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `subscribing to a job yields result`() {
        MPSModelCheckManager(project.repository).use {
            val jobId = it.createModelCheckJob(okRoot.ref)!!
            runBlocking {
                var executed = false
                assertTimeout(Duration.ofSeconds(30)) {
                    runBlocking {
                        it.subscribeToJob(jobId).take(1).catch {
                            fail()
                        }.collect {
                            executed = true
                        }
                    }
                }
                assertThat(executed)
            }
        }
    }

    @Test
    fun `result for error node is not empty and is correct`() {
        MPSModelCheckManager(project.repository).use {
            val jobId = it.createModelCheckJob(errorRoot.ref)!!
            runBlocking {
                it.waitForJobToFinish(jobId)
                assertThat(it.getJobStatus(jobId)).isEqualTo(CheckingJobStatus.Completed)
                val jobResult = it.getJobResult(jobId)
                assertThat(jobResult).isNotNull().prop("messages") { it.messages }.all {
                    isNotEmpty()
                    hasSize(1)
                    val firstElement = index(0)
                    firstElement.prop("severity") { it.severity }.isEqualTo(Severity.Error)
                    firstElement.prop("affected") { it.affected }.isNotEqualTo(errorRoot.reference)
                    firstElement.prop("target") { it.target }.isEqualTo(CheckMessageTarget.Property("name"))
                    firstElement.prop("sources") { it.sources }.all {
                        isNotEmpty()
                        hasSize(1)
                    }
                }
            }
        }
    }

    @Test
    fun `result for warning node is not empty and is correct`() {
        MPSModelCheckManager(project.repository).use {
            val jobId = it.createModelCheckJob(warningRoot.ref)!!
            runBlocking {
                it.waitForJobToFinish(jobId)
                assertThat(it.getJobStatus(jobId)).isEqualTo(CheckingJobStatus.Completed)
                val jobResult = it.getJobResult(jobId)
                assertThat(jobResult).isNotNull().all {
                    prop("messages") { it.messages }.all {
                        isNotEmpty()
                        hasSize(2)
                        val firstElement = index(0)
                        firstElement.prop("severity") { it.severity }.isEqualTo(Severity.Warning)
                        firstElement.prop("affected") { it.affected }.isNotEqualTo(warningRoot.reference)
                        firstElement.prop("target") { it.target }.isEqualTo(CheckMessageTarget.Node)
                        firstElement.prop("sources") { it.sources }.all {
                            isNotEmpty()
                            hasSize(1)
                        }
                    }
                    prop("completed") { it.completed }.all {
                        isGreaterThan(LocalDateTime.now().minusMinutes(1))
                        isLessThan(LocalDateTime.now())
                    }
                }
            }
        }
    }

    @Test
    fun `selecting checkers works`() {
        /*
        The test runs model check on the warning node bug excludes the type system and none type system checkers from MPS.
        This way the checking job should not produce any messages.
         */
        MPSModelCheckManager(project.repository).use {
            val checkers = it.getAvailableCheckers().drop(2)
            val jobId = it.createModelCheckJob(warningRoot.ref, checkers = Checkers.Multiple(checkers.map { it.id }))!!
            runBlocking {
                it.waitForJobToFinish(jobId)
                assertThat(it.getJobStatus(jobId)).isEqualTo(CheckingJobStatus.Completed)
                val jobResult = it.getJobResult(jobId)
                assertThat(jobResult).isNotNull().all {
                    prop("messages") { it.messages }.isEmpty()

                    prop("completed") { it.completed }.all {
                        isGreaterThan(LocalDateTime.now().minusMinutes(1))
                        isLessThan(LocalDateTime.now())
                    }
                }
            }
        }
    }

    @Test
    fun `changing model leads to recheck`() {
        /**
         * This test causes a model change that will create new errors in the model check result.
         * We assert that job starts after the change and the job completes. It is asserted that the completed job
         * produces a new result.
         */
        MPSModelCheckManager(project.repository).use {
            runBlocking {
                val jobId = it.createModelCheckJob(warningRoot.ref, continuous = true)!!
                val n = warningRoot
                it.waitForJobToFinish(jobId)
                val firstResult = it.getJobResult(jobId)
                assertThat(firstResult).isNotNull()
                try {
                    project.repository.modelAccess.executeCommandInEDT {
                        project.repository.modelAccess.runWriteAction {
                            n.setProperty("name", "Warning will fail")
                        }
                    }
                    assertTimeout(Duration.ofSeconds(120)) {
                        runBlocking {
                            var status = it.getJobStatus(jobId)
                            while (status != CheckingJobStatus.Running) {
                                status = it.getJobStatus(jobId)
                            }
                        }
                    }
                    it.waitForJobToFinish(jobId)
                    val secondResult = it.getJobResult(jobId)
                    assertThat(secondResult).all {
                        isNotNull()
                        isNotEqualTo(firstResult)
                    }
                } finally {
                    project.repository.modelAccess.executeCommandInEDT {
                        project.repository.modelAccess.runWriteAction {
                            n.setProperty("name", "Warning")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `changing model leads to recheck but result doesn't change`() {
        /**
         * This test triggers a change in the model that will not cause the model result to change.
         * We assert that the checking job starts after the change and completes. When the job has completed we assert
         * that no new result was produced because it was the same as before.
         */
        MPSModelCheckManager(project.repository).use {
            runBlocking {
                val jobId = it.createModelCheckJob(warningRoot.ref, continuous = true)!!
                val n = warningRoot
                it.waitForJobToFinish(jobId)
                val firstResult = it.getJobResult(jobId)
                assertThat(firstResult).isNotNull()
                try {
                    project.repository.modelAccess.executeCommandInEDT {
                        project.repository.modelAccess.runWriteAction {
                            n.setProperty("name", "Warning1")
                        }
                    }
                    assertTimeout(Duration.ofSeconds(120)) {
                        runBlocking {
                            var status = it.getJobStatus(jobId)
                            while (status != CheckingJobStatus.Running) {
                                status = it.getJobStatus(jobId)
                            }
                        }
                    }
                    it.waitForJobToFinish(jobId)
                    val secondResult = it.getJobResult(jobId)
                    assertThat(secondResult).all {
                        isNotNull()
                        isEqualTo(firstResult)
                    }
                } finally {
                    project.repository.modelAccess.executeCommandInEDT {
                        project.repository.modelAccess.runWriteAction {
                            n.setProperty("name", "Warning")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `changing model leads to recheck and subscribers get new result`() {
        /**
         * This test causes a model change that will create new errors in the model check result.
         * Then assert that the client gets a notification about the new checking result.
         */
        MPSModelCheckManager(project.repository).use {
            runBlocking {
                val jobId = it.createModelCheckJob(warningRoot.ref, continuous = true)!!
                val n = warningRoot
                var count = 0
                val job = GlobalScope.launch {
                    it.subscribeToJob(jobId).take(2).catch {
                        fail()
                    }.collect {
                        count++
                    }
                }
                try {
                    project.repository.modelAccess.executeCommandInEDT {
                        project.repository.modelAccess.runWriteAction {
                            n.setProperty("name", "Warning will fail")
                        }
                    }
                    assertTimeout(Duration.ofSeconds(30)) {
                        runBlocking {
                            job.join()
                        }
                    }

                    assertThat(count).isEqualTo(2)
                } finally {
                    project.repository.modelAccess.executeCommandInEDT {
                        project.repository.modelAccess.runWriteAction {
                            n.setProperty("name", "Warning")
                        }
                    }
                }
            }
        }
    }

}