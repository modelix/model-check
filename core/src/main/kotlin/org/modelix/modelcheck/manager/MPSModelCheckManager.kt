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

package org.modelix.modelcheck.manager

import com.intellij.openapi.application.ApplicationManager
import jetbrains.mps.checkers.*
import jetbrains.mps.errors.MessageStatus
import jetbrains.mps.errors.item.NodeReportItem
import jetbrains.mps.errors.item.RuleIdFlavouredItem
import jetbrains.mps.errors.messageTargets.MessageTarget
import jetbrains.mps.errors.messageTargets.NodeMessageTarget
import jetbrains.mps.errors.messageTargets.PropertyMessageTarget
import jetbrains.mps.errors.messageTargets.ReferenceMessageTarget
import jetbrains.mps.extapi.model.SModelBase
import jetbrains.mps.ide.MPSCoreComponents
import jetbrains.mps.progress.EmptyProgressMonitor
import jetbrains.mps.project.validation.StructureChecker
import jetbrains.mps.smodel.SNodePointer
import jetbrains.mps.smodel.event.*
import jetbrains.mps.typesystemEngine.checker.NonTypesystemChecker
import jetbrains.mps.typesystemEngine.checker.TypesystemChecker
import jetbrains.mps.util.CollectConsumer
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.jetbrains.mps.openapi.model.SModelId
import org.jetbrains.mps.openapi.model.SNode
import org.jetbrains.mps.openapi.model.SNodeReference
import org.jetbrains.mps.openapi.module.SRepository
import org.modelix.modelcheck.api.*
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureTimeMillis

private fun MessageTarget.toCheckMessageTarget(): CheckMessageTarget {
    return when (this) {
        is PropertyMessageTarget -> CheckMessageTarget.Property(this.role)
        is ReferenceMessageTarget -> CheckMessageTarget.ReferenceOrContainment(this.role)
        is NodeMessageTarget -> CheckMessageTarget.Node
        else -> error("Unknown message target ${this::class.java}")
    }
}

fun MessageStatus.toSeverity(): Severity {
    return when (this) {
        MessageStatus.OK -> Severity.Info
        MessageStatus.WARNING -> Severity.Warning
        MessageStatus.ERROR -> Severity.Error
    }
}

private fun CollectConsumer<NodeReportItem>.toCheckResult(): ModelCheckResult {
    val messages = this.result.map {
        val sources = if (it is RuleIdFlavouredItem) {
            val ruleIds = RuleIdFlavouredItem.FLAVOUR_RULE_ID.get(it)
            ruleIds.mapNotNull { it.sourceNode }
        } else {
            emptyList()
        }
        ModelCheckMessage(
            affected = it.node,
            target = it.messageTarget.toCheckMessageTarget(),
            sources = sources,
            severity = it.severity.toSeverity(),
            text = it.message
        )
    }
    return ModelCheckResult(id = messages.hashCode().toString(), completed = LocalDateTime.now(), messages = messages)
}



private data class CheckingJob(
    val id: UUID,
    val target: SNodeReference,
    val type: CheckingJobType,
    val checkers: Checkers,
    val status: CheckingJobStatus = CheckingJobStatus.Created,
    val events: MutableSharedFlow<CheckingJob>,
    val result: RingBuffer<ModelCheckResult> = RingBuffer(25),
    val resultEvents: MutableSharedFlow<ModelCheckResult>
)

private data class CheckerData(val checker: Checker, val mpsImpl: IChecker.AbstractRootChecker<NodeReportItem>)

fun Class<*>.toChecker() = Checker(this.name, this.simpleName)

class MPSModelCheckManager(private val repo: SRepository) : ModelListenerBase, ModelCheckManager, Closeable {
    private val checkers: List<CheckerData>
    private val jobs = ConcurrentHashMap<UUID, CheckingJob>()
    private val changeListeners = mutableMapOf<SModelId, List<UUID>>()
    private val logger = LoggerFactory.getLogger(this::class.java)

    init {
        val componentHost = ApplicationManager.getApplication().getComponent(MPSCoreComponents::class.java).platform

        // we don't use the MPS model check configuration and their extension point because those return checker for
        // a complete model. We want to check roots and not models.
        checkers = listOf(
            CheckerData(TypesystemChecker::class.java.toChecker(), TypesystemChecker()),
            CheckerData(NonTypesystemChecker::class.java.toChecker(), NonTypesystemChecker()),
            CheckerData(ConstraintsChecker::class.java.toChecker(), ConstraintsChecker(componentHost).asRootChecker()),
            CheckerData(RefScopeChecker::class.java.toChecker(), RefScopeChecker(componentHost).asRootChecker()),
            CheckerData(TargetConceptChecker2::class.java.toChecker(), TargetConceptChecker2(componentHost).asRootChecker()),
            CheckerData(StructureChecker::class.java.toChecker(), StructureChecker().asRootChecker())
        )
    }

    override fun close() {
        jobs.clear()
        changeListeners.keys.forEach {
            (repo.getModel(it) as SModelBase).removeModelListener(this)
        }
        changeListeners.clear()
    }

    private suspend fun runJob(job: CheckingJob) {
        try {
            logger.debug("starting job ${job.id}")
            jobs[job.id] = job.copy(status = CheckingJobStatus.Running)

            var node: SNode? = null
            repo.modelAccess.runReadAction {
                node = job.target.resolve(repo)
            }

            if (node == null) {
                logger.error("failed to resolve node for job ${job.id}")
                jobs[job.id] = job.copy(status = CheckingJobStatus.NodeDeleted)
                return
            }
            val errorCollector = CollectConsumer<NodeReportItem>()
            val checkersToRun = when (job.checkers) {
                Checkers.All -> checkers
                is Checkers.Single -> checkers.filter { it.javaClass.name == job.checkers.id }
                is Checkers.Multiple -> checkers.filter { job.checkers.ids.contains(it.checker.id) }
            }
            checkersToRun.forEach { checker ->
                if (jobs[job.id]?.status == CheckingJobStatus.Canceled) {
                    logger.warn("job ${job.id} got canceled")
                    return
                }
                repo.modelAccess.runReadAction {
                    logger.debug("job ${job.id}  executing checker ${checker.mpsImpl.category}")
                    val time = measureTimeMillis {
                        checker.mpsImpl.check(node, repo, errorCollector, EmptyProgressMonitor())
                    }
                    logger.debug("job ${job.id} checker ${checker.mpsImpl.category} completed in $time ms")
                }
            }

            logger.debug("job ${job.id} completed")

            val checkResult = errorCollector.toCheckResult()
            if (job.result.empty || job.result.last().messages != checkResult.messages) {
                logger.debug("job ${job.id} produced a new result.")
                jobs[job.id] = job.copy(
                    status = CheckingJobStatus.Completed,
                    result = job.result.clone().add(checkResult)
                )
                logger.debug("job ${job.id} notifying listeners.")
                job.resultEvents.emit(checkResult)
                logger.debug("job ${job.id} listeners notified.")
            } else {
                logger.debug("job ${job.id} did not produce a new result.")
                jobs[job.id] = job.copy(
                    status = CheckingJobStatus.Completed
                )
            }
        } catch (e: Exception) {
            logger.error("job ${job.id} failed", e)
            jobs[job.id] = job.copy(status = CheckingJobStatus.Error(e.message ?: ""))
        }
    }

    /**
     *
     */
    override fun createModelCheckJob(genericNodeRef: NodeReference, checkers: Checkers, continuous: Boolean): UUID? {
        val id = UUID.randomUUID()

        val resultFlow = MutableSharedFlow<ModelCheckResult>(replay = 1)
        val flow = MutableSharedFlow<CheckingJob>(1, 0, BufferOverflow.DROP_OLDEST)
        val nodeRef = if (genericNodeRef.primary.startsWith(MPS_NODEREF_PREFIX)) {
            SNodePointer.deserialize(genericNodeRef.primary.substring(MPS_NODEREF_PREFIX.length))
        } else {
            genericNodeRef.alternates.find { it.startsWith(MPS_NODEREF_PREFIX) }?.run {
                SNodePointer.deserialize(
                    this.substring(
                        MPS_NODEREF_PREFIX.length
                    )
                )
            }
        }

        if (nodeRef == null) {
            logger.error("No deserializer for noderef $genericNodeRef found.")
            return null
        }

        val job = CheckingJob(
            id,
            nodeRef,
            if (continuous) CheckingJobType.Continuous else CheckingJobType.OneOff,
            checkers = checkers,
            resultEvents = resultFlow,
            events = flow
        )
        jobs[id] = job
        GlobalScope.launch {
            //flow.buffer(0, BufferOverflow.DROP_OLDEST).collectLatest { j -> runJob(j) }
            flow.collectLatest { j -> runJob(j) }
        }

        if (!flow.tryEmit(job)) {
            logger.error("could not emit checking job")
            throw RuntimeException("failed to emit checking job")
        }

        if (continuous) {
            var modelId: SModelId? = null
            repo.modelAccess.runReadAction {
                val model = nodeRef.resolve(repo)?.model
                (model as SModelBase?)?.addModelListener(this)
                modelId = model?.modelId
            }
            require(modelId != null) { "could not resolve model for node reference $genericNodeRef" }
            synchronized(changeListeners) {
                val jobIds = changeListeners[modelId] ?: emptyList()
                changeListeners[modelId!!] = jobIds + id
            }
        }
        return id
    }

    override fun getAvailableCheckers(): List<Checker> {
        return this.checkers.map { it.checker }
    }

    override fun cancelCheckingJob(id: UUID) {
        require(jobs.containsKey(id)) { "Unknown checking job id: $id" }
        val job = jobs[id]!!
        if (job.status != CheckingJobStatus.Canceled) {
            jobs[id] = job.copy(status = CheckingJobStatus.Canceled)

            if (job.type == CheckingJobType.Continuous) {
                synchronized(changeListeners) {
                    changeListeners.forEach { (model, listeners) ->
                        if (listeners.contains(job.id)) {
                            changeListeners[model] = listeners.filter { it != job.id }
                            if (changeListeners[model]!!.isEmpty()) {
                                (repo.getModel(model) as SModelBase).removeModelListener(this)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun getJobStatus(id: UUID): CheckingJobStatus {
        require(jobs.containsKey(id)) { "Unknown checking job id: $id" }
        return jobs[id]!!.status
    }

    /**
     * Gets the latest result of the checking job. Might return null if the checking job has not produced a result
     * yet. Use [getJobStatus] to check the status of a job.
     * @param id id of a checking job
     * @return the latest result of the checking job
     */
    override fun getJobResult(id: UUID): ModelCheckResult? {
        require(jobs.containsKey(id)) { "Unknown checking job id: $id" }
        return jobs[id]?.result?.lastOrNull()
    }

    /**
     * Gets the all available results for a checking job. The results might be empty if the job hasn't produced any, yet.
     * Use [getJobStatus] to check the status of a job.
     */
    override fun getJobResults(id: UUID): List<ModelCheckResult> {
        require(jobs.containsKey(id)) { "Unknown checking job id: $id" }
        return jobs[id]!!.result.toList()
    }

    /**
     * Allows subscription to a specific checking job. The flow will emit a value each time the checking job completes.
     * If the job has already completes at least once new subscribers will get the latest result on subscription.
     */
    override fun subscribeToJob(id: UUID): SharedFlow<ModelCheckResult> {
        require(jobs.containsKey(id)) { "Unknown checking job id: $id" }
        return jobs[id]!!.resultEvents
    }

    override fun getAllJobs(): List<ModelCheckJob> {
        return jobs.values.map { ModelCheckJob(it.id, it.target.asNodeRef(), it.type, it.status) }
    }

    private fun SModelEvent.emit() {
        val modelId = this.model?.modelId
        if (modelId == null) {
            logger.error("No model in event $this.")
            return
        }
        changeListeners[modelId]?.forEach {
            val job = jobs[it]
            job?.events?.tryEmit(job)
        }
    }

    override fun propertyChanged(event: SModelPropertyEvent?) {
        event?.emit()
    }

    override fun childAdded(event: SModelChildEvent?) {
        event?.emit()
    }

    override fun childRemoved(event: SModelChildEvent?) {
        event?.emit()
    }

    override fun referenceAdded(event: SModelReferenceEvent?) {
        event?.emit()
    }

    override fun referenceRemoved(event: SModelReferenceEvent?) {
        event?.emit()
    }

    override fun getPriority(): SModelListener.SModelListenerPriority {
        return SModelListener.SModelListenerPriority.CLIENT
    }
}