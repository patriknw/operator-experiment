/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */
package example.crd

import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.fabric8.kubernetes.api.builder.{Function => F8Function}
import io.fabric8.kubernetes.api.model.KubernetesResource
import io.fabric8.kubernetes.client.CustomResource
import io.fabric8.kubernetes.client.CustomResourceDoneable
import io.fabric8.kubernetes.client.CustomResourceList

class PodSet() extends CustomResource {
// FIXME constructor instead var, but trouble with Jackson deserialization
  private var _spec: PodSetSpec = _
  private var _status: PodSetStatus = _

  def spec = _spec

  def status = _status

  def getSpec: PodSetSpec = _spec

  def setSpec(spec: PodSetSpec): Unit = {
    this._spec = spec
  }

  def getStatus: PodSetStatus = _status

  def setStatus(status: PodSetStatus): Unit = {
    this._status = status
  }

}

final case class PodSetList() extends CustomResourceList[PodSet]

@JsonDeserialize(using = classOf[JsonDeserializer.None])
final case class PodSetSpec(replicas: Int) extends KubernetesResource

@JsonDeserialize(using = classOf[JsonDeserializer.None])
final case class PodSetStatus(availableReplicas: Int) extends KubernetesResource

final case class DoneablePodSet(podSet: PodSet, function: F8Function[PodSet, PodSet])
    extends CustomResourceDoneable[PodSet](podSet, function)
