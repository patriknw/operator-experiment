/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */
package example

import java.util
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue

import scala.jdk.CollectionConverters._

import example.crd.DoneablePodSet
import example.crd.PodSet
import example.crd.PodSetList
import io.fabric8.kubernetes.api.model.OwnerReference
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import io.fabric8.kubernetes.client.dsl.MixedOperation
import io.fabric8.kubernetes.client.dsl.Resource
import io.fabric8.kubernetes.client.informers.ResourceEventHandler
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import io.fabric8.kubernetes.client.informers.cache.Cache
import io.fabric8.kubernetes.client.informers.cache.Lister
import org.slf4j.LoggerFactory

class PodSetController(
    client: DefaultKubernetesClient,
    podSetClient: MixedOperation[PodSet, PodSetList, DoneablePodSet, Resource[PodSet, DoneablePodSet]],
    podInformer: SharedIndexInformer[Pod],
    podSetInformer: SharedIndexInformer[PodSet],
    namespace: String) {

  private val logger = LoggerFactory.getLogger(getClass)

  private val workqueue = new ArrayBlockingQueue[String](1024)

  private val podSetLister: Lister[PodSet] = new Lister(podSetInformer.getIndexer, namespace)
  private val podLister: Lister[Pod] = new Lister(podInformer.getIndexer, namespace)

  def create(): Unit = {
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
  }

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

  private def getControllerOf(pod: Pod): Option[OwnerReference] = {
    val ownerReferences = pod.getMetadata.getOwnerReferences
    ownerReferences.asScala.find(_.getController)
  }

  private def enqueuePodSet(podSet: PodSet): Unit = {
    logger.info("enqueuePodSet({})", podSet.getMetadata.getName)
    val key = Cache.metaNamespaceKeyFunc(podSet)
    logger.info("Going to enqueue key {}", key)
    if (key != null && !key.isEmpty) {
      logger.info("Adding item to workqueue")
      workqueue.add(key)
    }
  }

  def run(): Unit = {
    logger.info("Starting PodSet controller")
    while (!podInformer.hasSynced || !podSetInformer.hasSynced) {
      Thread.sleep(10)
      // Wait till Informer syncs
    }

    while (true) try {
      logger.info("trying to fetch item from workqueue...")
      if (workqueue.isEmpty)
        logger.info("Work Queue is empty")
      // this blocks until there is some work
      val key = workqueue.take
      require(key != null, "key can't be null")
      logger.info("Got {}", key)
      if (key.isEmpty || (!key.contains("/")))
        logger.warn("invalid resource key: {}", key)
      // Get the PodSet resource's name from key which is in format namespace/name
      val name = key.split('/')(1)
      val podSet = podSetLister.get(key.split('/')(1))
      if (podSet == null) {
        logger.error("PodSet {} in workqueue no longer exists", name)
      } else {
        reconcile(podSet)
      }
    } catch {
      case _: InterruptedException =>
        Thread.currentThread.interrupt()
        logger.error("controller interrupted..")
    }
  }

  protected def reconcile(podSet: PodSet): Unit = {
    logger.info("reconcile {}", podSet)
    val pods = podCountByLabel("app", podSet.getMetadata.getName)
    if (pods.isEmpty) {
      createPods(podSet.spec.replicas, podSet)
    } else {
      val existingPods = pods.size
      // Compare it with desired state i.e spec.replicas
      // if less then spin up pods
      if (existingPods < podSet.spec.replicas)
        createPods(podSet.spec.replicas - existingPods, podSet)
      // If more pods then delete the pods
      var diff = existingPods - podSet.spec.replicas

      while (diff > 0) {
        val podName = pods.remove(0)
        client.pods.inNamespace(podSet.getMetadata.getNamespace).withName(podName).delete
        diff -= 1
      }
    }
  }

  private def createPods(numberOfPods: Int, podSet: PodSet): Unit = {
    (0 until numberOfPods).foreach { _ =>
      val pod = createNewPod(podSet)
      client.pods.inNamespace(podSet.getMetadata.getNamespace).create(pod)
    }
  }

  private def createNewPod(podSet: PodSet) =
    new PodBuilder().withNewMetadata
      .withGenerateName(podSet.getMetadata.getName + "-pod")
      .withNamespace(podSet.getMetadata.getNamespace)
      .withLabels(Collections.singletonMap("app", podSet.getMetadata.getName))
      .addNewOwnerReference()
      .withController(true)
      .withKind("PodSet")
      .withApiVersion("demo.k8s.io/v1alpha1")
      .withName(podSet.getMetadata.getName)
      .withNewUid(podSet.getMetadata.getUid)
      .endOwnerReference
      .endMetadata
      .withNewSpec
      .addNewContainer()
      .withName("busybox")
      .withImage("busybox")
      .withCommand("sleep", "3600")
      .endContainer
      .endSpec
      .build

  private def podCountByLabel(label: String, podSetName: String) = {
    val podNames = new util.ArrayList[String]
    val pods = podLister.list
    import scala.jdk.CollectionConverters._
    for (pod <- pods.asScala) {
      if (pod.getMetadata.getLabels.entrySet.contains(new util.AbstractMap.SimpleEntry(label, podSetName)))
        if (pod.getStatus.getPhase.equals("Running") || pod.getStatus.getPhase.equals("Pending"))
          podNames.add(pod.getMetadata.getName)
    }
    logger.info("count: {}", podNames.size)
    podNames
  }

}
