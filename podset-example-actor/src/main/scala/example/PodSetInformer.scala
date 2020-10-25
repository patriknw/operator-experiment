/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */
 package example

 import scala.jdk.CollectionConverters._

 import akka.actor.typed.ActorRef
 import example.crd.PodSet
 import io.fabric8.kubernetes.api.model.OwnerReference
 import io.fabric8.kubernetes.api.model.Pod
 import io.fabric8.kubernetes.client.informers.ResourceEventHandler
 import io.fabric8.kubernetes.client.informers.SharedIndexInformer
 import io.fabric8.kubernetes.client.informers.cache.Cache
 import io.fabric8.kubernetes.client.informers.cache.Lister
 import org.slf4j.LoggerFactory

 object PodSetInformer {
  private val logger = LoggerFactory.getLogger("example.PodSetInformer")


  def create(podInformer: SharedIndexInformer[Pod],
   podSetInformer: SharedIndexInformer[PodSet], podSetLister: Lister[PodSet], reconciler: ActorRef[Reconciler.Command]): Unit = {

   podSetInformer.addEventHandler(new ResourceEventHandler[PodSet]() {
    override def onAdd(podSet: PodSet): Unit = {
     enqueuePodSet(podSet)
    }

    override def onUpdate(oldPodSet: PodSet, newPodSet: PodSet): Unit = {
     enqueuePodSet(newPodSet)
    }

    override def onDelete(podSet: PodSet, deletedFinalStateUnknown: Boolean): Unit = {
     // Do nothing
    }
   })

   podInformer.addEventHandler(new ResourceEventHandler[Pod]() {
    override def onAdd(pod: Pod): Unit = {
     handlePodObject(pod)
    }

    override def onUpdate(oldPod: Pod, newPod: Pod): Unit = {
     if (oldPod.getMetadata.getResourceVersion != newPod.getMetadata.getResourceVersion) {
      handlePodObject(newPod)
     }
    }

    override def onDelete(pod: Pod, deletedFinalStateUnknown: Boolean): Unit = {
     // Do nothing
    }
   })

   def handlePodObject(pod: Pod): Unit = {
    logger.info("handlePodObject({})", pod.getMetadata.getName)
    getControllerOf(pod) match {
     case Some(ownerReference) =>
      if (ownerReference.getKind.equalsIgnoreCase("PodSet")) {
       val podSet = podSetLister.get(ownerReference.getName)
       if (podSet != null)
        enqueuePodSet(podSet)
      }
     case None =>
      throw new IllegalArgumentException("owner must be defined")
    }
   }

   def getControllerOf(pod: Pod): Option[OwnerReference] = {
    val ownerReferences = pod.getMetadata.getOwnerReferences
    ownerReferences.asScala.find(_.getController)
   }

   def enqueuePodSet(podSet: PodSet): Unit = {
    logger.info("enqueuePodSet({})", podSet.getMetadata.getName)
    val key = Cache.metaNamespaceKeyFunc(podSet)
    logger.info("Going to enqueue key {}", key)
    if (key != null && !key.isEmpty) {
     logger.info("Adding item to workqueue")
     reconciler ! Reconciler.AddPodSet(key)
    }
   }
  }
}
