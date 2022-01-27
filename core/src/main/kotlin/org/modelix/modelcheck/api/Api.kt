/*
   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package org.modelix.modelcheck.api

import kotlinx.coroutines.flow.Flow
import org.jetbrains.mps.openapi.model.SNodeReference
import java.time.LocalDateTime
import java.util.*


data class ModelCheckMessage(
    val affected: SNodeReference,
    val target: CheckMessageTarget,
    val sources: List<SNodeReference>,
    val text: String,
    val severity: Severity
)

data class ModelCheckResult(val id: String, val completed: LocalDateTime, val messages: List<ModelCheckMessage>)

fun ModelCheckResult.toResponse() =
    ModelJobCheckResult(this.id, this.completed, this.messages.map { it.toResponse() })


const val MPS_NODEREF_PREFIX = "mps:"
fun SNodeReference.asNodeRef() = NodeReference(primary = "$MPS_NODEREF_PREFIX${this}", alternates = emptyList())

fun ModelCheckMessage.toResponse() =
    ModelCheckMessageResponse(
        this.affected.asNodeRef(),
        this.target,
        this.sources.map { it.toString() },
        this.severity,
        this.text
    )

sealed class Checkers {
    object All: Checkers()
    class Single(val id: String): Checkers()
    class Multiple(val ids: List<String>): Checkers()
}
interface ModelCheckManager {
    fun createModelCheckJob(nodeRef: NodeReference, checkers: Checkers = Checkers.All, continuous: Boolean = false): UUID?
    fun cancelCheckingJob(id: UUID)
    fun getJobStatus(id: UUID): CheckingJobStatus
    fun getAvailableCheckers(): List<Checker>

    /**
     * Gets the latest result of the checking job. Might return null if the checking job has not produced a result
     * yet. Use [getJobStatus] to check the status of a job.
     * @param id id of a checking job
     * @return the latest result of the checking job
     */
    fun getJobResult(id: UUID): ModelCheckResult?

    /**
     * Gets the all available results for a checking job. The results might be empty if the job hasn't produced any, yet.
     * Use [getJobStatus] to check the status of a job.
     */
    fun getJobResults(id: UUID): List<ModelCheckResult>

    /**
     * Allows subscription to a specific checking job. The flow will emit a value each time the checking job completes.
     * If the job has already completes at least once new subscribers will get the latest result on subscription.
     */
    fun subscribeToJob(id: UUID): Flow<ModelCheckResult>

    /**
     * Gets all model checking job this instance is associated with. Includes every job no matter of its status or type.
     */
    fun getAllJobs(): List<ModelCheckJob>
}
