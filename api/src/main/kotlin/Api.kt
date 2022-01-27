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

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

/*
    File contains data classes that are used by server implementations and returned from their REST API. Node of the
    classes contain Java or MPS specific types, except for UUID which should be replaced with https://github.com/benasher44/uuid/
    This is by design so these classes are usable in kotlin multiplatform project.
    This has the side effect that SNodeReferences are exchanged as strings rather than structured data.

 */

class UUIDAsStringSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor
        get() = buildClassSerialDescriptor("UUID") {
            element<String>("uuid")
        }

    override fun deserialize(decoder: Decoder): UUID {
        return UUID.fromString(decoder.decodeString())
    }

    override fun serialize(encoder: Encoder, value: UUID) {
        encoder.encodeString(value.toString())
    }
}

class DateAsStringSerializer : KSerializer<LocalDateTime> {
    val format = DateTimeFormatter.ISO_DATE
    override fun deserialize(decoder: Decoder): LocalDateTime {
        return format.parse(decoder.decodeString()) as LocalDateTime
    }

    override val descriptor: SerialDescriptor
        get() = PrimitiveSerialDescriptor("date", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDateTime) {
        encoder.encodeString(format.format(value))
    }
}

enum class CheckingJobType {
    /**
     * A checking job that is executed once.
     */
    OneOff,

    /**
     * A checking job that is created and will be executed again when changes to the model require it to be re-executed.
     * Job of these type need explicit cancellation by the client that created them.
     */
    Continuous
}

@Serializable
sealed class CheckingJobStatus {
    /**
     * The job was created and execution has not started yet.
     */
    @SerialName("created")
    @Serializable
    object Created : CheckingJobStatus()

    /**
     * The job is currently running and has not completed. Results of previous executions of the job might available.
     */
    @SerialName("running")
    @Serializable
    object Running : CheckingJobStatus()

    /**
     * The job was canceled by a client. This status is only set by explicit cancellation of a client. The manager might
     * cancel running jobs a model has changed while a checking job was running and restarts the job internally. These
     * internal cancellations aren't reflected with this state.
     */
    @SerialName("canceled")
    @Serializable
    object Canceled : CheckingJobStatus()

    /**
     * The checking job has complete. For job with type [CheckingJobType.OneOff] this is the final state.
     * Jobs with type [CheckingJobType.Continuous] will get executed again when changes require it and transition into
     * the state [Running] again.
     */
    @SerialName("completed")
    @Serializable
    object Completed : CheckingJobStatus()

    /**
     * The node reference of the job could not get resolved. The manager assumes that in that case the node was deleted.
     * For checking jobs with type [CheckingJobType.OneOff] this can happen if the node was deleted from the model in the
     * time it takes for the checking job to start execution after the job was created.
     * For checking jobs with type [CheckingJobType.Continuous] this will happen if the node no longer exists. In this
     * case previous results of the job are still available if it ever completed.
     */
    @SerialName("nodeDeleted")
    @Serializable
    object NodeDeleted : CheckingJobStatus()

    /**
     * An error occurred during the checking jobs execution. See the inner exception for more details.
     * Checking jobs with type [CheckingJobType.Continuous] will try to re-execute the next time a change triggers their
     * execution. For type [CheckingJobType.OneOff] this is a final state and job will not retry execution.
     */
    @SerialName("error")
    @Serializable
    data class Error(val message: String) : CheckingJobStatus()
}

/**
 * The target for a given model check message.
 */

@Serializable
sealed class CheckMessageTarget {
    /**
     * The model check message is associated with a property. The [propertyName] field contains the name of the property.
     * Editors can use this information to highlight or show the error message on specific parts of the editor.
     */
    @SerialName("property")
    @Serializable
    data class Property(val propertyName: String) : CheckMessageTarget()

    /**
     * The model check message is associated with a reference or containment link. The [featureName] field contain the
     * name of the concept feature. No distinction between references and containment is available. The MPS meta model
     * does not allow for a reference and containment role to share the same name hence the name is enough to identify
     * the feature uniquely. Editors can use this information to highlight or show the error message on specific parts
     * of the editor.
     */
    @SerialName("referenceOrContainment")
    @Serializable
    data class ReferenceOrContainment(val featureName: String) : CheckMessageTarget()

    /**
     * The model check message is not associated with any feature of the node.
     */
    @SerialName("node")
    @Serializable
    object Node : CheckMessageTarget()
}

enum class Severity {
    Info, Warning, Error
}

@Serializable
data class ModelCheckMessageResponse(
    /**
     * Reference to the affected node. This is a string serialised MPS SNodeReference/SNodePointer. The client needs to
     * do the mapping to an node of their side manually.
     */
    val affected: NodeReference,
    /**
     * The feature of the node associated with this message.
     */
    val target: CheckMessageTarget,
    /**
     * Sources in the MPS language definition that produced this message. A message can have multiple sources. The list
     * contains string serialised MPS SNodeReference/SNodePointer instances.
     */
    val sources: List<String>,
    val severity: Severity,
    val text: String
)

/**
 * Reference to a node. The reference is represented as a string. The string must allow the client to choose the
 * appropriate parser for the node reference. e.g. Serialised node references from MPS must have the prefix `mps:`.
 * A client can probe the string and choose the parse at runtime.
 *
 * The [NodeReference.primary] field must always contain
 * a valid references to the node. The server and client can choose the primary type of reference freely. An MPS based
 * server can choose to use the MPS SNodeReference as their primary id. A client that directly connects to the modelix
 * model server might choose to use a PNodReference as their primary id.
 *
 * [NodeReference.alternates] can optionally contain alternate ways of identifying the same node. An MPS based server with
 * a model stored in the modelix model server can include references to the PNode stored in the server. Implementations
 * that consume the node reference are free to choose the way how they refer to the node. They might not use the primary
 * reference for performance reasons if an alternate reference is cheaper for them to resolve.
 * Implementations that create a [NodeReference] must make sure that logically all alternate reference point to the same
 * "thing" as the primary.
 */
@Serializable
data class NodeReference(
    val primary: String,
    val alternates: List<String>
)

@Serializable
data class ModelCheckJob(
    @Serializable(with = UUIDAsStringSerializer::class)
    val id: UUID,
    val target: NodeReference,
    val type: CheckingJobType,
    val status: CheckingJobStatus
)

@Serializable
data class ModelJobCheckResult(
    /**
     * Identifier for the result of this checking job. Ids aren't globally unique. Ids are only guaranteed to be unique
     * within the set of results for a checking job.
     * Tow job results from different jobs might have the same id but aren't the same result! Comparing ids always needs
     * to happen within the context of a checking job. In order to construct a globally unique id for a job result
     * use jobId+resultId.
     */
    val id: String,
    @Serializable(with = DateAsStringSerializer::class)
    val completed: LocalDateTime,
    val messages: List<ModelCheckMessageResponse>
)

@Serializable
data class CreateJobResponse(@Serializable(with = UUIDAsStringSerializer::class) val jobId: UUID)

@Serializable
data class JobStatusResponse(
    @Serializable(with = UUIDAsStringSerializer::class) val jobId: UUID,
    val status: CheckingJobStatus
)

@Serializable
data class JobAllResultsResponse(
    @Serializable(with = UUIDAsStringSerializer::class) val jobId: UUID,
    val results: List<ModelJobCheckResult>
)

@Serializable
data class JobResultResponse(
    @Serializable(with = UUIDAsStringSerializer::class) val jobId: UUID,
    val result: ModelJobCheckResult
)

@Serializable
data class AllJobsResponse(val jobs: List<ModelCheckJob>)

/**
 * A checker available in the model checking server. [Checker.name] might be empty or duplicated, its main purpose is
 * debugging. [Checker.id] is guaranteed to be unique for each checker.
 */
@Serializable
data class Checker(val id: String, val name: String)

@Serializable
data class GetCheckersResponse(val checkers: List<Checker>)
